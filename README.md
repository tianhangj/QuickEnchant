# Quick Enchant

纯客户端 Minecraft 26.1.2（Fabric）快速 RNG 附魔 mod：预测附魔随机数，自动连续获取需求的附魔。

RNG 破解与预测核心移植自 [clientcommands](https://github.com/Earthcomputer/clientCommands)（v2.14.3，LGPL-3.0）。

## 运行环境要求

- Minecraft 26.1.2 + Fabric Loader 0.19.5+，Java 25
- 前置 mod：[MaLiLib](https://modrinth.com/mod/malilib)（0.28.8+，全局配置）、[QuickShulker](https://github.com/MoRanpcy/quickshulker)（3.0.1+，背包内潜影盒取放；**服务端也需要安装**）

## 使用流程（1.1+）

准备（一次性）：附魔台旁的讲台放一本足够页数的书（第 x 页 = x 个书架，由你的红石电路映射）；背包里放装有可附魔物品的潜影盒、装有青金石的潜影盒；普通栏位放白名单物品（mod 配置里配置物品 ID）用于自动扔出推进随机数；备一些 dummy 物品（默认 `minecraft:book`，可在配置修改）。

1. **/qeprepare** — 打开背包样式的盒选择界面：Shift+左键 标记/取消标记待附魔的潜影盒（只含可附魔且未附魔物品的盒可标记，忽略已附魔/不可附魔物品），背包下方确认。上次未正常结束时自动继承上次的标记。
2. **选择附魔** — 确认盒子后自动进入附魔选择界面：顶部切换物品类型（同类合并"物品名*数量"），exactly 精确模式，按"想要/不想要"添加附魔与等级。同 id 物品自动预填上次的全部参数。确认后进入破解阶段。
3. **破解随机数** — 右上角常驻状态与提示：
   - 使用 `/qecrackrng` 破解玩家随机数（扔 10 个物品）；
   - 在附魔台**附魔任意物品**一次，破解附魔种子。
4. **/qestart** — 开始自动附魔：取物 → 扔白名单物品 → 讲台调书架 → dummy/真实附魔（全程三重校验）→ 放回原盒 → 下一件。求解器带**单步前瞻**：本件真实附魔前在窗口内多扔 m 个物品，使写入的下一件种子直接命中其目标，除第一件外通常全程零 dummy、零额外扔物。
5. 中止快捷键随时可停；校验不符立即终止并本地聊天警告；未正常结束时下次 /qeprepare 继承盒选择，附魔选择按物品 id 永久保留。

## 构建

```
gradlew build
```

每次构建自动产出 `1.1.0-dev.N` 版本（N 从 `dev-build-number.txt` 读取并在构建成功后自动 +1），用于区分不同构建的 jar。`gradle.properties` 中 `version` 为基础版本号，`org.gradle.java.home` 指向本机 JDK 25（其他机器需相应修改）。

## License

Ported RNG code is derived from clientcommands (LGPL-3.0, Copyright (c) 2021 Earthcomputer); this project remains under LGPL-3.0 (see LICENSE.txt).
