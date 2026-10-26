# QuickEnchant 交接文档

> 更新时间：2026-10-26（第 2 版，接续上一位交接者）
> 状态：**编译通过**，`26.1.2 / 26.2 / 26.3` 三个版本 `build` 全绿，`RngMirrorTest` 12 个测试全绿。
> 尚未做的是**进游戏实测**（见第五节）。

---

## 一、权威规格（用户原话，务必按此实现）

> 第一件物品：投掷物品控制玩家随机数到附魔第1件物品需要的
> -> 附魔dummy
> -> 投掷物品控制玩家随机数到附魔第2件物品需要的
> -> 附魔第1件物品
> -> 投掷物品控制玩家随机数到附魔第3件物品需要的
> -> 附魔第2件物品
> -> ……以此类推

```
投掷 n0  -> 附魔 dummy    写出 S1  ->  第 1 件用 S1 附魔
投掷 n1  -> 附魔第 1 件   写出 S2  ->  第 2 件用 S2 附魔
投掷 n2  -> 附魔第 2 件   写出 S3  ->  第 3 件用 S3 附魔
...
附魔第 k 件（最后一件）不需要为“下一件”铺路
```

- **第 0 步一定是附魔 dummy**：此时还没有“上一次附魔”能写种子。
- dummy **只在最开始用一次**，之后每一件都靠“上一件附魔”写出的种子。
- 每一步之间的投掷，目的是让**紧接着的那次附魔**写出**下一件**需要的种子。

---

## 二、关键机制（已由测试锁定，不要推翻）

- 每次附魔服务端执行 `Player.onEnchantmentPerformed` → `enchantmentSeed = random.nextInt()`，把玩家 RNG 的下一个 32 位抽取值写入附魔台种子（DataSlot）。
- **投掷物品只推进玩家 RNG，永远不改变附魔台种子**；所以“这次附魔读到什么种子”是固定的，投掷只能决定“这次附魔**写出**什么种子”。
- 每次投掷 = 4 步；一次附魔 = 1 步；dummy 掉出（丢弃）= 4 步。
- 这些常量与推导都集中在 **`automation/SeedChain.java`**（纯逻辑、无 MC 依赖），求解器与测试共用同一份实现——**不要在别处再写一遍步数**。
- **投掷数搜索也只允许出现在 `SeedChain.findThrows`**（`SeedChain.writtenSeedAfterThrows`）。`Rand` 是有状态的，谁想"推进一次再取值"地循环探测，候选就会被自己的探测推歪（第 3 版就是因为这个在实测里挂掉的，见 4.0）。

### 步数记账

```
链路径：  投掷 n 次 → 附魔            （写入 out(playerSeed + 4n)，本件用表里已有的种子）
dummy 路径：投掷 n0 次 → dummy 附魔   （写入 out(playerSeed + 4n0) = 本件种子）
           丢弃 dummy（+4）→ 窗口投掷 m 次
           本件附魔                   （写入 out(playerSeed + 4n0 + 5 + 4m) = 下一件种子）
```

---

## 三、代码结构（当前实现）

| 位置 | 职责 |
|---|---|
| `automation/SeedChain.java` | 步数常量 + `decide(seedOffersItem, firstOfRun)`（唯一允许 dummy 的规则）+ 种子推导 + `findThrows`（唯一允许做投掷数搜索的地方） |
| `automation/EnchantSolver.java` | 搜索（投掷数 × 书架数 × dummy 栏位 × 目标栏位）；链路径优先，否则 dummy 路径；无解返回 `null` |
| `automation/EnchantAutomation.java` | 单 LongTask 状态机：取 dummy → 青金石 → 取物 → 扔物 → 讲台调书架 → 开台 → 放青金石 → 放首个物品 → 校验 → dummy 附魔/校验/取出/丢弃 → 换真物品 → 校验 → 窗口投掷 → 真实附魔 → 校验 → 放回 |
| `features/PlayerRandCracker.java` | 玩家 RNG 镜像（4 步投掷、1 步附魔、4 步丢弃） |
| `features/EnchantmentCracker.java` | 附魔台种子破解 + `predictTable` / `tableMatchesPrediction` |
| `util/QEDebug.java` | `debug` 开关下的全量日志与失败快照（`<gameDir>/quick-enchant-debug/`） |

### dummy 路径的状态时序（`EnchantAutomation`）

```
RESOLVE → FETCH_DUMMY_OPEN → FETCH_DUMMY_TAKE → FETCH_DUMMY_CLOSE
        → (REFILL_LAPIS_*) → OPEN_ITEM_BOX → TAKE_ITEM → CLOSE_ITEM_BOX
        → THROWING(n0) → BOOKSHELF_* → TABLE_OPEN → PLACE_LAPIS
        → PLACE_FIRST(dummy) → VERIFY_FIRST(placePrediction) → DUMMY → DUMMY_VERIFY
        → TAKE_DUMMY_OUT → DROP_DUMMY(+4 步) → PLACE_REAL → VERIFY_SWAPPED(swapPrediction)
        → WINDOW_THROWING(m) → ENCHANT → ENCHANT_WAIT → ENCHANT_VERIFY
        → TAKE_ITEM_OUT → TABLE_CLOSE → RETURN_* → NEXT_ITEM
```

链路径跳过 `FETCH_DUMMY_*` / `PLACE_FIRST` 的 dummy 分支 / `DUMMY*` / `DROP_DUMMY` / `PLACE_REAL` / `VERIFY_SWAPPED` / `WINDOW_THROWING`（`m` 恒为 0）。

---

## 四、本轮修了什么

### 4.0 第 3 版：实测暴露的真 bug —— 搜索用同一个 Rand 逐项步进

**现象**：第一件附魔成功后，第二件在 `RESOLVE` 阶段必报 `abortDesync chainBroken`（`abort-RESOLVE-1793002967806.txt`，16:22:47，`xpSeed=222E2480`）。

**根因**：`EnchantSolver.findThrows` 用**同一个** `Rand` 做候选探测：

```java
playerRand.advance(offsetSteps);
for (int n = 0; n <= maxThrows; n++) {
    if (n > 0) playerRand.advance(4);
    int writtenSeed = (int) playerRand.nextBits(32);   // ← 探测本身消耗一步，掩码还会推进内部状态
    ...
}
```

`nextBits` 是**有状态**的，取一个值就前进一步。于是第 n 个候选其实是 `out(base + 5n)` 而不是 `out(base + 4n)`：**每个候选比真实位置多 1 步**，n 越大偏得越多。

用实测数据验证（`playerSeed=0x0402914F8CF4`，`n0=13`，`base=4×13+5=57`）：

| 投掷数 | 求解器以为写出的种子（漂移值） | 游戏实际写出的种子 |
|---|---|---|
| 10（该计划选的） | `A350381E` | `222E2480` ← 日志实测 |

求解器按 `A350381E` 判定"这一档能满足第二件"，实际写出的是 `222E2480`，在 15 书架、栏位 0~2 下什么也给不出 → 第二件 `chainBroken`。**计划本身没错，是校验它用错了种子。**

**修复**：
1. `SeedChain.writtenSeedAfterThrows(playerSeed, baseSteps, throwsCount)` —— 每次从 `playerSeed` 全新推导，候选之间互不影响。
2. `SeedChain.findThrows(playerSeed, baseSteps, maxThrows, IntPredicate accepts)` —— 投掷数搜索的**唯一**实现，落在纯逻辑类里，可单测。
3. `EnchantSolver.findThrows` 改为薄委托，删除自己那份循环（及不再需要的 `LCG`/`Rand` import）。
4. `gui/EnchantSelectScreen` 的可行性预览有**同一个 bug**（`rand.advance(n == 0 ? 1 : 4)` 同样是共享 Rand），一并改为调用 `SeedChain.findThrows`。

**新增回归测试**（`RngMirrorTest`，共 14 个，全绿）：
- `throwSearchCandidatesMatchTheSeedsTheGameWrites`：直接用这次实测的种子钉死 `out(base+4n)`，并断言旧的漂移值 `A350381E` 不再出现；
- `findThrowsReturnsACountWhoseSeedMatches`：命中的投掷数其种子必须真的满足谓词，找不到返回 -1。

### 4.1 第 2 版修了什么

上一位交接者停下时是**编译不通过**（4 个错误），根因是把“后续条目不该再用 dummy”理解成了“dummy 整个删掉”，只删了一半。

1. `record Plan` 的 `@Nullable` 改成类型注解：`EnchantmentCracker.@Nullable TablePrediction`。
2. `QEDebug.plan` 改用真实的 `placePrediction` / `swapPrediction` 两个组件。
3. `EnchantAutomation` 恢复整条 dummy 状态机（阶段、`dummyItem` 字段与配置、丢弃时的自维护监听器、dummy 的背包/盒子定位、`noDummyItem` / `badDummyItem` 提示）。
4. 接上 `EnchantSolver.solve(..., dummyItem, firstEntry, ...)`；`firstEntry` **只在真实附魔校验通过后**置 false。
5. **修掉一个真实缺陷**：`VERIFY_SWAPPED` 必须转去 `phaseAfterVerify()`（即 `WINDOW_THROWING`），否则窗口投掷永不执行，第一件写不出第二件的种子，第二件必然 `chainBroken`。
6. 恢复 `PRIME_WINDOW_THROWS = 256` 且**只用于窗口投掷**（窗口投掷在已打开的附魔台内，必须短）；开台前投掷仍用用户完整预算。
7. 窗口搜索上提一层（只依赖 `n0`，与 dummy 栏位无关），每个 `n0` 只搜一次；补 `primed` 归因计数。
8. 无解语义分开：首个条目无解 → `run.unsolvable`（**不清种子**，模型没错）；后续条目无解 → `chainBroken` → `abortDesync`（清种子）。两者都终止，都不跳过。
9. 前瞻查找下一步时跳过谓词为 null 的条目（原来直接 `break`，会白白放弃铺路）。
10. 新增 `SeedChain` + 3 个测试（12 个全绿），其中 `dummyIsUsedOnlyForTheFirstEntry` 直接钉死“只有第一个条目可用 dummy”这条规格。

---

## 五、还没做的事（下一步应该做的）

1. **进游戏实测一次完整的多件流程**（≥3 件）。上一轮实测已经证明了第 2 件的 `chainBroken` 根因（见 4.0），但修复后的版本还**没有重新进游戏验证**；重点看：
   - `DUMMY_VERIFY` 是否按预期“三档 cost 全部清零”（服务端 `slotsChanged` 对已附魔物品清零）；
   - 丢弃 dummy 后玩家种子是否**恰好**前进 4 步（`DROP_ITEM` 被标记为 maintained，不触发 reset）；
   - 第一件的 `WINDOW_THROWING` 之后，第二件是否直接走链路径（不再取 dummy）；
   - 三处校验（`VERIFY_FIRST` / `VERIFY_SWAPPED` / `ENCHANT_VERIFY`）是否全部通过。
2. 打开 `debug` 跑一遍，日志里应有 `solver: chain plan` / `solver: dummy plan` 与 `seedChain` 各步的 `xpSeed=` 链路，可作为回归基准。
3. README 已按“只有第一件用 dummy”更新，若实测流程有出入，**以实测为准再改文档**。

---

## 六、不要重复的错误

| 错误 | 后果 |
|---|---|
| 把“后续条目还用 dummy 是不是浪费”理解成“dummy 全部删掉” | 第一件没有种子来源，直接 `chainBroken`（用户报告“无法附魔了”） |
| 把“为下一件铺路”当成 dummy 路径的**硬性条件** | 明明能附魔的物品被判无解 |
| 链路径和窗口投掷用同一个预算 | 窗口投掷在**已打开的界面里**，放长会把一步拖成几十秒 |
| 失败时“跳过该物品”或“换种子重试”作为兜底 | 规格明确要求：失败即终止 |
| 无条件清种子 | 只有**预测不匹配**（模型出错）才清；外部原因要保留，重新破解纯属白费功夫 |
| `forgetSeed()` 触发 `RNG_CALLED_EVENT` | 会重入中止处理 |
| **在一个共享 `Rand` 上循环 `advance(4)` + `nextBits(32)` 搜候选种子** | 探测本身消耗一步，候选变成 `out(base+5n)`；计划用错种子校验，下一件必然 `chainBroken`（第 3 版的真 bug） |
| 把"计划里的投掷数"当成"它写出的种子" | 计划只是**目标**，写出什么种子必须由 `SeedChain.writtenSeed*` 推导后再校验 |

---

## 七、一句话总结

> 流程就是“首次 dummy 建立链条，之后纯链式，每步只扔物给**下一件**铺路，失败即终止”。`SeedChain` 是步数、规则与投掷数搜索的唯一出处，`EnchantSolver.solve(..., firstEntry, ...)` 只在 `firstEntry` 时允许 dummy。改动请先跑 `./gradlew test`，那 14 个测试就是这份规格的锚点。
