# 项目需求
本项目需要完成一个纯客户端快速 rng 附魔的Minecraft 26.1.2的mod，通过预测rng实现快速获取需求的附魔。  

需求：

1. rng-hack部分参考 reference/clientcommands （注意版本号，请在正式开始工作前将这个项目使用git回到26.1.2对应版本） 项目实现。
2. 全局的配置通过依赖库 reference/malilib （版本取26.1.2-0.28.9，请在正式开始工作前将这个项目使用git回到这个版本，并正确添加主项目的依赖） 来实现。
3. Minecraft 26.1.2 的源代码在 reference/minecraft-source，可以用于参考。
4. 快捷潜影盒（版本取3.0.1-26.1，请在正式开始工作前将这个项目使用git回到这个版本），需要调用这个mod来实现在背包内潜影盒中取货。

[x] 以上需要调整版本的完成后将这个复选框标记。（2026-09-19：clientcommands → v2.14.3、malilib → 26.1.2-0.28.8（0.28.9 无 git tag，经确认采用 0.28.8）、quickshulker → 3.0.1-26.1）

具体需求：

1. 自动连续进行 rng 附魔行为，最开始的获取随机数的部分继承 clientcommands 的行为（玩家手动操作），之后都自动。
2. 获取到随机数后选择背包内盒装的栏位，其中，分为可附魔物品，青金石（盒装，用快捷潜影盒提取），以及若干个扔出白名单物品栏位（白名单物品需要能在配置文件中配置列表）。
3. 可附魔物品在完成后需要放回原盒，顺序无要求。
4. 检测所有可附魔物品后弹出界面用于选择附魔，最上方选择物品类型，同样的物品合并配置（显示 物品名*数量），一个单选（是否exactly），之后一个列表附魔和等级，同 clientcommands。
5. 之后选择附魔台、讲台（用于调整书架数，第x页就是x个书架，请在调整后检查是否正确，到正确后再进行附魔，可开关，如果关闭则书架数不能修改）。
6. 最后在确认（快捷键，可配置）后执行，整体过程中玩家始终低头向正下方，白名单物品栏位扔出时要留一个，如果所有栏位都只剩一个就等待到有。
7. 过程中打开附魔台放入物品准备附魔时，检测三个条目的显示附魔来检查，如果与预期不同则终止并通过只本地显示的聊天消息警告，附魔后也同理。

# 项目计划

## 架构总览

纯客户端 Fabric mod（QuickEnchant），在移植自 clientcommands 的 RNG 破解/预测层之上，构建自动化引擎：

- **RNG 层**（移植自 clientcommands v2.14.3，包名改为 `me.tianhangj.quickenchant`，剥离了钓鱼/合唱果/无限工具/重登等无关特性）：
  - `PlayerRandCracker`：玩家 48 位 java.util.Random 的客户端镜像；`RNG_CALLED_EVENT` + 24 个 rngevents mixin 覆盖所有 RNG 消耗点（保证镜像不失步）；`throwSlotFilter` 钩子让自动扔物限制在白名单栏位并遵守"留一个"规则。
  - `EnchantmentCracker`：XP seed 2^20 暴力破解、附魔列表预测、`predictTable`（逐条复刻服务端 slotsChanged 的消耗与线索抽取，用于需求 7 的三条目校验）、书架数扫描。
  - `CCrackRng`/`CCrackRngGen`：/qecrackrng 扔 10 物品格求解玩家种子（seedfinding + latticg 已 JiJ）。
  - task 框架（TaskManager/LongTask/ItemThrowTask 含服务器往返确认）、SuggestionsHook、本地聊天工具。
- **配置层**（malilib 0.28.8）：`config/ModConfig`（白名单物品列表、讲台调整开关、热键：打开选择界面/确认执行/中止、最大投掷数、书架/等级范围、每刻投掷数）+ 配置界面 + 按键注册。
- **背包层**：`InventoryScanner`（直接读潜影盒数据组件分类：物品盒/青金石盒/白名单堆，无需开菜单）；`BoxOps`（通过 quickshulker 的服务端权威流程打开背包内潜影盒 + 容器点击取放 + 空手安全关闭）。
- **求解器** `EnchantSolver`：搜索（投掷数 × 书架数 × dummy 栏位 × 目标栏位）。关键事实（经源码验证）：**原版附魔台不能给已附魔的物品再附魔，且这是服务端强制**——`ItemStack.isEnchantable()` 要求 ENCHANTMENTS 组件为空（ItemStack.java:1075），`EnchantmentMenu.slotsChanged` 对不可附魔物品把三档 cost 清零（EnchantmentMenu.java:112-118），`clickMenuButton` 在 `costs[buttonId] <= 0` 时直接拒绝且种子不推进（155-196 行）；创造模式只豁免青金石/经验，不豁免 cost 检查，客户端发包点击同样被服务端拒绝，无法绕过。因此当当前种子不提供目标附魔时，必须用一个**独立的 dummy 物品**（可配置，默认书）完成 dummy 附魔推进表格种子到目标值，再取出 dummy（其保留 dummy 附魔结果，自动丢弃）、换入真物品附魔。dummy 的附魔列表用 dummy 物品自身的堆栈模拟（其附魔能力影响 cost 与候选池），且列表非空才会真正消耗种子；谓词只作用于真物品的附魔列表。
- **单步前瞻**：真实附魔会把"当时的下一次 nextInt"写入 DataSlot 成为下一件的种子，而 dummy→real 之间的窗口扔物不影响 real 自身（它读 DataSlot）。求解器解出本件方案后，额外搜索窗口扔物数 m（0..256），使 real 写入的种子（dummy 情形 `out(s+4n+5+4m)`、无 dummy 情形 `out(s+4m)`）满足下一件的谓词（下一件可自由调书架，故扫全部书架数与槽位，含等级约束）。命中则引擎在 final 校验后执行 m 次窗口扔物，下一件求解时快速路径自动命中——**免 dummy 免扔物**；未命中则 m=0 优雅回退。无需跨物品状态：前瞻只是把 RNG 状态推到对下一件有利的位置。第一件仍可能需要一次 dummy（流水线启动），此后每件通常零 dummy。
- **执行引擎** `EnchantAutomation`：单 LongTask 状态机，逐件执行：解析方案 → 开盒取物 → 扔白名单物品（留一个；全部只剩一个则等待）→ 讲台跳页调整书架数（100+页码 直接跳页，校验 DataSlot 页码与实际书架扫描数）→ 开附魔台放物品+青金石 → 三条目 clue 校验（不符则终止+本地聊天警告）→ dummy 附魔 → 二次校验 → 目标附魔 → 物品组件校验 → 取回物品 → 放回原盒 → 下一件。全程强制玩家低头（pitch=90）；中止热键随时可停；意外 RNG 调用自动终止。
- **GUI** `EnchantSelectScreen`：顶部物品类型选择（同类合并显示"物品名*数量"）→ exactly 单选 → 附魔/等级列表（想要/不想要 模式，等级 1..max 或任意）。两份种子都破解后自动弹出一次；确认后按配置热键开始执行。

## 关键决策记录

- malilib 26.1.2-0.28.9 在远端不存在（0.28.8 跳到 0.28.10），经确认采用 **0.28.8**（masa maven 上 0.28.9 的构建产物存在，需要时升级只需改 gradle.properties 一行）。
- clientcommands、quickshulker、malilib 全部为 Mojang 官方映射，主项目隐式 mojmap，移植代码可直接编译。
- loom 1.17 已移除 `modImplementation`，mod 依赖统一用 `implementation`（loom 自动识别并重映射）。
- quickshulker 打开背包内潜影盒是服务端权威行为：运行环境（服务器或单人客户端）必须安装 quickshulker。
- gradle 跑在本机 `D:/games/Minecraft/java/jdk-25.0.3+9`（loom 1.17 需要 21+，MC 26.1.2 需要 25）。

# 工作记录

## 2026-09-19 / 2026-09-20

1. **版本调整**：clientcommands → v2.14.3（26.1.2），malilib → 26.1.2-0.28.8，quickshulker → 3.0.1-26.1；复选框已勾选。
2. **依赖接入**：添加 Sakura Ryoko / Fallen Breath / TerraformersMC / MoRanpcy / Seedfinding / LatticG maven 仓库；malilib、quickshulker 以 `implementation` 引入；seedfinding mc_seed + latticg 以 JiJ 嵌入；fabric.mod.json 改为 client-only（environment: client、client 入口、depends malilib/quickshulker）、添加 accessWidener；清理模板代码；JDK 25 固定到 gradle.properties。
3. **RNG 移植**：批量移植 56 个文件（features/command/task/util/event/interfaces/mixin），逐一剥离未移植特性（ChorusManipulation、LegacyEnchantment、Relogger、ServerBrandManager、toolBreakWarning、infiniteTools、fishing），版本判断固定为 26.1.2 常量结果；`MultiVersionCompat`/`CUtil`/`CComponentUtil`/`ClientCommandHelper` 写为精简版；`Configs` 写为 malilib 门面（临时破解状态 + 配置委托）。验证：unbreaking/装备损坏在 26.1.x 使用服务端 Level 随机，不消耗玩家 RNG（onItemDamage 为无操作）。
4. **配置**：ModConfig（malilib IConfigHandler）+ QuickEnchantConfigGui + 按键注册，中英语言文件。
5. **背包/盒装**：InventoryScanner（组件直读分类）+ BoxOps（quickshulker 打包流程 + QUICK_MOVE + 安全关闭）。
6. **GUI + 求解 + 引擎**：EnchantSelectScreen、EnchantSolver（union 方案）、EnchantAutomation 状态机、`/qecrackrng` 命令、热键与自动弹窗逻辑。
7. **测试与构建**：RngMirrorTest（LCG 镜像对拍 java.util.Random、unthrowItem 逆步、求解器种子约定与镜像一致性）通过；`gradlew build` 全绿（含 accessWidener 校验、嵌套 jar 校验）。
8. **修正 dummy 附魔设计**（2026-09-20）：用户引用 wiki 指出附魔台不能给已附魔物品附魔。经源码验证为**服务端强制**（isEnchantable 要求无魔咒 → slotsChanged 清零 cost → clickMenuButton 拒绝，创造模式与客户端发包均无法绕过），此前"dummy 附魔做在真物品上、并集匹配"的设计无效。已改回独立 dummy 物品方案：新增 `dummyItem` 配置（默认 minecraft:book）；dummy 附魔只要求列表非空（推进种子），真物品在换入并校验后附魔，dummy 副本通过"PICKUP 取到光标 → 点击容器外(-999)丢弃"处理（丢弃消耗的 4 次 nextInt 由镜像自动跟踪，且不影响已写入 DataSlot 的目标种子）。dummy 来源：先背包后任意盒子，自动取用。
9. **单步前瞻流水线**（2026-09-20）：`EnchantSolver.solve` 增加下一件队列条目参数，解出本件方案后搜索窗口扔物数 m（上限 256），使 real 附魔写入的下一件种子满足其谓词；引擎新增 `WINDOW_THROWING` 阶段（在 final 校验后、真实附魔前执行 m 次扔物），扔物驱动泛化为 `doThrowing(count, next)`，超时豁免改为"有活动扔物任务即豁免"，扔槽位过滤器增加保护（附魔台菜单打开时排除台内物品/青金石槽位）。下一件求解无需任何跨物品状态，快速路径自动命中。整轮 dummy 数从"每件可能一个"降为"最多一个（第一件启动）"。
10. **修复盒菜单打不开导致的超时**（2026-09-20）：`closeAndWait` 原先只等客户端菜单关闭，但服务端异步处理关闭包；紧接着的 open-box 请求在服务端 `Util.openItem` 里按 `player.containerMenu.slots.get(invSlot)` 索引进尚未收掉的旧菜单，取出错误物品或越界，服务端静默不开菜单 → 客户端等 `ShulkerBoxMenu` 超时（青金石补给/取 dummy/取回归还等"关后立即开"的路径必现）。修复：新增 `syncClosed`——关盒后用移植的 `SuggestionsHook.fence()` 做服务端往返栅栏（命令补全请求在关闭包之后发出，收到响应即证明服务端已处理关闭），带 40 tick 兜底防个别服务器不回包；`RESOLVE` 入口也先 sync。另为盒菜单未打开的超时增加专门诊断提示（提示检查服务端是否安装 QuickShulker）。已核实 `Slot.getContainerSlot()` 为纯函数（两侧对称）、`InventoryMenu` 布局与 quickshulker 自身调用约定一致（主手 36+selected、副手 45）、`Player.closeContainer` 同步重置客户端菜单——排除槽位语义问题。
11. **修复引擎单 tick 空转导致的瞬间超时**（2026-09-26，用户日志定位）：日志显示"开始、TAKE_ITEM 超时、盒界面打开"全在同一秒内——`EnchantAutomation` 直接继承 `LongTask` 但从未调用 `scheduleDelay()`，而移植的 `TaskManager.tick()` 只有在任务调度延迟时才把它从本轮迭代快照移除，否则会在**同一个 tick 内被无限重跑**。状态机在一个 tick 内空转：TAKE_ITEM 等待服务端开盒——网络往返在 tick 结束前不可能完成 → phaseTicks 瞬间冲到 200 → 超时中止；tick 结束后服务端才打开盒界面（日志中 ShulkerBoxMenu initialized 紧跟在超时后）。之前的往返栅栏也因此从未生效（栅栏完成同样依赖 tick 间网络处理）。修复：`body()` 末尾调用 `scheduleDelay()`，恢复"每 tick 推进一步"的设计语义。另确认：用户同时安装了 clientcommands，droppableinventory 的 mixin 覆写冲突警告无害（双方实现同一 duck 接口），两个 RNG 镜像各自独立运行互不干扰。
12. **自动 dev 版本号**（2026-09-26）：按用户要求，每次 `gradlew build` 产出 `基础版本-dev.N`（如 `1.0.0-dev.1`），N 存于项目根 `dev-build-number.txt`，构建成功后自动 +1；版本号同时写入 jar 内 fabric.mod.json。已验证连续构建 dev.1 → dev.2 递增正确。
13. **修复"意外随机数调用中止 + 界面重弹"**（2026-09-26，用户日志定位）：日志显示盒界面正常打开 1 秒后在第一批扔物时被"意外的随机数调用:丢弃物品"中止——移植的 `EnchantmentCracker.onRNGCallEvent` 把"附魔操纵期间任何非附魔 RNG 调用"视为意外，唯一豁免是 clientcommands 自己的 `isCurrentlyThrowingItems` 标志，引擎从未置位。修复：(a) 暴露 `setCurrentlyThrowingItems`，引擎在扔物批次与 dummy 丢弃期间置位；(b) dummy 丢弃没有 throwTask 监听、其 DROP_ITEM 无人标记"已维护"会导致 `resetCracker` 丢破解状态且镜像不推进——引擎注册自己的监听器在丢弃瞬间标记；(c) 自动弹窗逻辑不再在运行期间重置 `selectionScreenShown`（否则中止后必重弹），仅在破解状态丢失时重置，运行中由热键打开界面也被禁止。
14. **修复"目标物品不在预期位置"1 秒即中止 + 增加阶段诊断**（2026-09-26，日志 3 定位）：日志 3 显示盒界面打开 1 秒内即以 itemGone 中止，且中止前无任何菜单初始化日志——最符合的解释是盒菜单的界面包先于内容包被处理，`takeItem` 首次扫描到空槽位即误判"物品不在"。修复：`takeItem`/`refillLapisTake`/`fetchDummyTake`/`returnPut` 的无匹配中止增加 `MENU_CONTENT_GRACE_TICKS = 5` 的内容同步宽限（宽限期内继续等待而不是中止）。诊断增强：每次阶段切换写游戏日志（`automation phase: X -> Y`），所有中止消息附带阶段名（`quick-enchant.run.phase` 中英双语），下次日志可精确还原状态机轨迹。
15. **修复 DUMMY_VERIFY 误报校验失败**（2026-09-26，日志 4 阶段轨迹定位）：阶段日志显示整条 RNG 链路正确（32 次扔物定位、place 预测 7 tick 校验通过），失败在 dummy 附魔后的校验——预测模型错误：dummy 附魔执行后物品已带魔咒，服务端 `isEnchantable()` 为 false，`slotsChanged` 会把三档供价**全部清零**（dummy 为书时还会转成附魔书、连 ENCHANTABLE 组件都没有），而预测按"未附魔 dummy + 新种子"算出非零供价，必然不符。修复：DUMMY_VERIFY 改为校验"三档 cost 全 0、clue 全 -1"（证明 dummy 附魔确实执行）；种子是否推进到目标值由 VERIFY_SWAPPED（换入真物品后按 realXpSeed 预测）真正校验。Plan 记录中移除 postDummyPrediction。
16. **修复"附魔完一把后跳过剩余全部"**（2026-09-26，日志 5 阶段轨迹确认）：第一件完整成功（扔物定位、dummy、换入、附魔、校验、放回原盒、前瞻窗口扔物全部通过），第二件 RESOLVE 重扫时误报无物——根因：附魔完的镐被放回原盒后，盒内变成"9 未附魔 + 1 已附魔"，而盒分类函数是"全部内容物必须是可附魔未附魔"的全有全无规则，一件已附魔回流物品就把整个盒子归为 OTHER。修复：分类规则放宽为"含至少一件可附魔未附魔物品即物品盒（已附魔回流物品视为杂物）；无则含青金石即青金石盒"。取物扫描本就逐个检查 isEnchantableCandidate，不会误取已附魔的回流物品。
17. **待人工验证**：需要在真实 26.1.2 环境（装 malilib + quickshulker）实测：破解流程、自动取放、讲台红石电路时序、服务端真实服务器兼容性。

## 已知限制 / 风险

- 游戏内行为未在本开发环境实测（无法启动真实客户端进行完整联机流程），需要用户配合验证。
- 讲台方案要求书页 ≥ 目标书架数 + 1，红石电路由用户搭建（页码 x ↔ x 书架）。
- 若服务器存在未镜像的玩家 RNG 消耗（其他 mod、Paper 服务端），校验机制会终止并警告。