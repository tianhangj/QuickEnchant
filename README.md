# Quick Enchant

纯客户端 Minecraft 26.1.2（Fabric）快速 RNG 附魔 mod：预测附魔随机数，自动连续获取需求的附魔。

RNG 破解与预测核心移植自 [clientcommands](https://github.com/Earthcomputer/clientCommands)（v2.14.3，LGPL-3.0）。

## 运行环境要求

- Minecraft 26.1.2 + Fabric Loader 0.19.5+，Java 25
- 前置 mod：[MaLiLib](https://modrinth.com/mod/malilib)（0.28.8+，全局配置）、[QuickShulker](https://github.com/MoRanpcy/quickshulker)（3.0.1+，背包内潜影盒取放；**服务端也需要安装**）

## 使用流程

1. **准备**：附魔台旁的讲台放一本足够页数的书（第 x 页 = x 个书架，由你的红石电路映射）；背包里放入：
   - 若干潜影盒：分别装可附魔物品和青金石（自动扫描识别，无需指定栏位）；
   - 普通栏位放白名单物品（在 mod 配置里配置物品 ID 列表），用于自动扔出推进随机数（每格至少留 1 个）；
   - 若干 dummy 物品（默认 `minecraft:book`，可在配置修改）：当前种子不提供目标附魔时，需要用一个 dummy 物品做 dummy 附魔推进种子——原版服务端不允许给已附魔物品再附魔，dummy 不能省略；dummy 副本附魔后会被自动丢弃。
2. **破解种子**（继承 clientcommands 的手动流程）：
   - 输入 `/qecrackrng`（扔出 10 个物品自动求解玩家 RNG）；
   - 右键附魔台（记录位置），放入任意可附魔物品，点击 GUI 右上角 "Add Info"（添加信息）按钮破解附魔 XP 种子；
   - 破解完成后会自动弹出附魔选择界面。
3. **选择附魔**：顶部选择物品类型（同类物品合并显示 `物品名*数量`），设置是否 exactly（精确集合），按"想要/不想要"添加附魔与等级，点击"预备"。
4. **执行**：按配置的确认快捷键（默认未绑定，需在 mod 配置界面设置）开始自动附魔。过程中玩家始终低头，自动完成：取物 → 扔白名单物品推进 RNG → 讲台调书架数 → 放入物品与青金石 → 校验三条目显示附魔 → dummy 附魔 → 校验 → 附魔 → 校验 → 放回原盒 → 下一件。求解器带**单步前瞻**：本件真实附魔前会在窗口内多扔 m 个物品，使写入的下一件种子直接命中其目标，因此除第一件外通常全程零 dummy、零额外扔物。
5. 任何校验不符会立即终止并通过本地聊天消息警告；中止快捷键随时可停。

## 构建

```
gradlew build
```

每次构建自动产出 `1.0.0-dev.N` 版本（N 从 `dev-build-number.txt` 读取并在构建成功后自动 +1），用于区分不同构建的 jar。`gradle.properties` 中 `version` 为基础版本号，`org.gradle.java.home` 指向本机 JDK 25（其他机器需相应修改）。

## License

Ported RNG code is derived from clientcommands (LGPL-3.0, Copyright (c) 2021 Earthcomputer); this project remains under LGPL-3.0 (see LICENSE.txt).
