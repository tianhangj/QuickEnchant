"""静态校验某个 MC 版本下所有 mixin 注入点的目标方法是否真实存在。

背景：mixin 的 method= / target= 是字符串，编译期完全不检查，解析发生在运行期。
当目标方法不存在且 mixins.json 里 defaultRequire=1（本项目如此）时，游戏会在
加载阶段直接抛 InjectionError 崩溃。26.3 就曾因此崩溃（HoeAndShovelItemMixin
指向的 Item.useOn 里没有 isClientSide 调用）。

用法:
    # 1) 解包目标版本的客户端 jar
    mkdir -p /tmp/cf26.3 && cd /tmp/cf26.3 && unzip -q /path/to/client-26.3.jar
    # 2) 先构建一次，生成合并源码
    ./gradlew build -Pmc_ver=26.3
    # 3) 审计（类目录用正斜杠，Windows 下反斜杠会被 javap 当转义符）
    python tools/audit-injections.py 26.3 "C:/path/to/cf26.3"

退出码 0 表示全部解析成功，1 表示存在无法解析的注入点。
"""
import re, subprocess, pathlib, sys

JDK = r"D:/games/Minecraft/java/jdk-25.0.4.1+1/bin/javap.exe"
PRIM = {"Z": "boolean", "I": "int", "J": "long", "F": "float", "D": "double",
        "B": "byte", "S": "short", "C": "char", "V": "void"}


def make_dump(cp):
    cache = {}

    def dump(cls):
        if cls not in cache:
            try:
                r = subprocess.run([JDK, "-p", "-cp", cp, cls],
                                   capture_output=True, text=True, timeout=180)
                cache[cls] = r.stdout if r.returncode == 0 else ""
            except Exception:
                cache[cls] = ""
        return cache[cls]
    return dump


def parse_desc(desc):
    params = desc[1:desc.rindex(")")]
    out, i = [], 0
    while i < len(params):
        arr = 0
        while params[i] == "[":
            arr += 1
            i += 1
        if params[i] == "L":
            j = params.index(";", i)
            out.append(params[i + 1:j].replace("/", ".").rsplit(".", 1)[-1] + "[]" * arr)
            i = j + 1
        else:
            out.append(PRIM[params[i]] + "[]" * arr)
            i += 1
    return out


def find_method(javap_out, spec):
    if not javap_out:
        return None
    spec = spec.strip()
    if spec.startswith("<init>"):
        # 构造器：javap 打印为 "<修饰符> 全限定类名(参数)"，只要类里有构造器即可
        return bool(re.search(r"public\s+[\w.]+\.[\w$]+\s*\(", javap_out))
    if "(" in spec:
        name = spec[: spec.index("(")]
        ptypes = parse_desc(spec[spec.index("("):])
        for line in javap_out.splitlines():
            if not re.search(r"\b%s\s*\(" % re.escape(name), line):
                continue
            inside = line[line.index("(") + 1: line.rindex(")")]
            have = [p.strip().rsplit(".", 1)[-1] for p in inside.split(",") if p.strip()]
            if len(have) != len(ptypes):
                continue
            if all(h.rstrip("[]") == w.rstrip("[]") for h, w in zip(have, ptypes)):
                return True
        return False
    return bool(re.search(r"\b%s\s*\(" % re.escape(spec), javap_out))


def main():
    ver, cp = sys.argv[1], sys.argv[2]
    dump = make_dump(cp)
    root = pathlib.Path("build/generated/merged/%s/main" % ver)
    if not root.exists():
        print("no merged sources for %s (run a build first)" % ver)
        return 2
    problems, checked = [], 0
    for f in sorted(root.rglob("*.java")):
        src = f.read_text(encoding="utf-8", errors="replace")
        mm = re.search(r"@Mixin\(\s*(?:value\s*=\s*)?\{?\s*([A-Za-z0-9_.]+)\.class", src)
        if not mm:
            continue
        tmap = {}
        for line in src.splitlines():
            line = line.strip()
            if line.startswith("import ") and line.endswith(";"):
                fq = line[7:-1].strip()
                tmap[fq.rsplit(".", 1)[1]] = fq
        cls = tmap.get(mm.group(1), mm.group(1))
        if not cls.startswith("net.minecraft."):
            continue
        jout = dump(cls)
        for im in re.finditer(r'@(?:Inject|Redirect|ModifyVariable|ModifyArg)\([^)]*?method\s*=\s*"([^"]+)"', src, re.S):
            checked += 1
            res = find_method(jout, im.group(1))
            if res is False:
                problems.append((f.name, cls, im.group(1)))
            elif res is None:
                problems.append((f.name, cls, im.group(1) + "  [CLASS NOT FOUND]"))
    print("[%s] checked %d injection points" % (ver, checked))
    if problems:
        print("  UNRESOLVED (%d):" % len(problems))
        for p in problems:
            print("    %-42s %-50s %s" % p)
        return 1
    print("  all resolved OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
