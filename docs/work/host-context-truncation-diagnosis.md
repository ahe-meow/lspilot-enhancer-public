# 宿主上下文截断与工具调用错误诊断

- Status: current
- Updated: 2026-08-18
- Purpose: 记录宿主对话请求因上下文截断产生孤立工具输出的证据、根因和模块修复方案。

## 结论

错误的直接根因是发送给上游的 `messages` 序列包含没有对应 assistant `tool_calls` 的 `tool` 输出。

用户报告的 502 中的 `call_NMvsSJWSiSvOA8T0hs66v4Il` 与当前宿主数据库第一条孤立 `tool` 输出的 `toolCallId` 完全相同。为避免在文档中保存完整调用 ID，证据使用 SHA-256 前 12 位表示：

```text
call_NMvsSJWSiSvOA8T0hs66v4Il -> ddb4f4b1a425
```

上游收到类似下面的非法结构时，会找不到对应的 function/tool call：

```json
[
  {"role":"tool", "tool_call_id":"call_..."},
  {"role":"assistant", "tool_calls":[{"id":"call_other"}]}
]
```

`HTTP 502 No tool call found...` 是结构校验失败后的上游错误；`HTTP 400 Upstream request failed` 很可能是同一类非法请求经过网关的另一种包装。当前现场没有保存 400 对应的完整出站 JSON，因此 400 与该结构问题的对应关系仍标为高概率，不能伪称已有逐请求证明。

## 现场证据

### 宿主身份

通过 KernelSU `RunCommandService` 只读采集：

```text
package: me.yun.lspilot
version: 1.1.0 (11)
APK SHA-256: af2283a2978ea650986988ac3d9c01a39474cdd6410d30b842dd8f15e686149c
```

APK 和数据库都没有被修改。SQLite 一致性备份返回：

```text
integrity_check=ok
```

### 数据库序列

初始 `ai_chat.db` 快照中该会话只有 30 条消息。按 `rowId` 排序后的窗口前 4 条全部是 `role=tool`，并且没有任何 assistant `toolCalls` 声明这些 ID：

```text
row 713058 role=tool tool_id_sha256_12=ddb4f4b1a425
row 713059 role=tool tool_id_sha256_12=1187dd9febb4
row 713060 role=tool tool_id_sha256_12=0cf25b4e4771
row 713061 role=tool tool_id_sha256_12=bcf6fc44cdd0
row 713062 role=assistant tool_calls=6
```

之后的 assistant/tool 组是配对的。只读验证器输出：

```text
messages=30 orphan_tool_outputs=4
VERDICT=RED invalid tool-call sequence; provider rejection is expected
```

最小化验证也成立：只有一个孤立 `tool` 输出的请求为 RED；补上对应 assistant `tool_calls` 后为 GREEN：

```text
minimal orphan:  RED, orphan_tool_outputs=1
paired control:  GREEN, orphan_tool_outputs=0
```

因此这不是单纯的 UI 文本省略；当前发送候选消息本身已经失去工具调用配对。

## 宿主代码链路

对当前宿主 APK 的 DEX/Smali 进行只读分析得到以下链路：

1. `va$f.invokeSuspend` 使用常量 `0x1e`，调用 `repository.b.n(chatId, 30)`。
2. `repository.b.n(chatId, 30)` 调用 DAO `c7.a(chatId, 30)`；DAO SQL 是：

   ```sql
   SELECT * FROM chat_message
   WHERE chat_id = ?
   ORDER BY rowId DESC LIMIT ?
   ```

3. `repository.b.n` 先取 `c7.o(chatId)` 的总数，再取限定行、反转为时间正序，并返回 `(messages, lastTimestamp, hasMore)`。
4. `va$f$a.invokeSuspend` 将返回的最近消息列表写入 `oa` UI 状态的 `messages` 字段。
5. `va.w` 复制这份列表并调用 `va.x`，然后把处理后的列表交给 provider 协程。
6. `va.x` 的实际逻辑是：先收集所有 `role=tool` 的 `toolCallId`；再遍历 assistant 的 `toolCalls`，对缺少输出的调用追加 `[用户中断] 工具调用已被取消` 的 tool 消息。它没有删除没有 assistant 声明的孤立 tool 输出。
7. 当前 provider 的 `zj8.i` 将消息写成 `role=tool`、`tool_call_id`、`content`；`zj8.l` 将 assistant 调用写成 `tool_calls[].id/type/function`；`zj8.p` 将结果放入最终 `messages` JSON。
8. `va$f` 之后将列表交给 `va$f$a` 更新状态；只有在宿主 streaming 状态满足特定条件时才会调用 `repository.b.r(chatId, list)`。该保存路径通过 `c7.b` 先执行 `c7.l(chatId)` 删除会话消息，再执行 `c7.i(list)` 插入。它证明宿主存在整列表替换能力，但本次证据不能把初始 30 条窗口确定归因于某一次保存；修复后的实时验证反而确认模块没有写回数据库。
9. `va$e.invokeSuspend` 是另一条按时间游标加载旧消息的路径，同样以常量 `30` 调用 `repository.b.o(chatId, timestamp, 30)`，不是模块的请求 Hook。

因此，固定 30 条边界和 `va.x` 的“只补缺失输出、不删孤立输出”组合，足以把窗口头部的 4 个历史 tool 输出原样送入 provider。

宿主的 `WebSearchManager` 和 `va` 还存在“内容截断”文本标记。这类截断只改变工具输出的文本长度，不会自动生成合法的 `tool_calls`，也不会修复缺失的调用声明；它会放大上下文不稳定的感受，但不是 502 的直接结构原因。

## 30条截断的触发时机与数据保留

宿主的 `30` 是请求构造阶段的读取上限，不是数据库写入或定时清理操作。已确认的时序是：

```text
用户发起模型请求
  → va$f.invokeSuspend
  → repository.b.n(chatId, 30)
  → c7.a(chatId, 30)
  → SELECT ... ORDER BY rowId DESC LIMIT ?
  → 反转为时间正序并写入消息状态
  → va.x 整理工具调用
  → zj8 序列化 messages
  → 模块出站 JSON Hook
  → provider
```

具体边界如下：

- 每次宿主准备发送模型请求时都会重新执行这次限定查询；消息总行数不超过 30 时没有实际截断。
- 超过 30 行后，只把最新 30 行加载到本次请求；第 31 行及更早记录仍留在 `chat_message`，不会因 `LIMIT 30` 被删除。
- 截断不是“达到第 31 条就删除旧消息”，也不是模型回复完成后的数据库清理；当前证据没有发现宿主因该限制删除历史记录。
- 查询按数据库行截断，而不是按对话轮次或完整 assistant/tool 调用组截断，因此边界可能留下孤立 `role=tool` 输出。
- 模块位于出站 JSON 边界，看到请求时宿主已经完成 30 行查询；模块可以清理非法工具调用结构，但不能从请求 JSON 恢复窗口外的历史。
- 当前已确认的是请求加载路径；聊天界面是否复用同一个 `LIMIT 30`，需要单独定位 UI 的查询/分页路径，不能仅凭请求路径推断。

因此，“被丢弃”需要区分：历史消息没有因该限制从数据库删除，但在默认请求中被排除，模型无法看到这些消息。

## 宿主聊天界面的上拉历史分页

当前宿主的上拉加载是本地数据库分页，不会访问模型接口或远程历史服务。

### 首次进入会话

`va$f.invokeSuspend` 先找到当前 `chatId`，调用 `repository.b.n(chatId, 30)`。该方法：

1. 查询该会话的总行数：`SELECT COUNT(*) FROM chat_message WHERE chat_id = ?`。
2. 查询最新一页：

   ```sql
   SELECT * FROM chat_message
   WHERE chat_id = ?
   ORDER BY rowId DESC
   LIMIT 30
   ```

3. 把数据库倒序结果反转为聊天时间正序。
4. 把每行 JSON 解析成 `u7` 消息；单行解析失败会被跳过。
5. 返回消息列表、当前页最旧行的 `rowId` 和 `hasMoreOlder`。`hasMoreOlder` 是“总行数大于本页原始数据库行数”。

### 用户上拉到顶部

UI 的 `ka$c.invokeSuspend` 监听 LazyList 滚动状态，只有以下条件同时满足才触发：

- `firstVisibleItemIndex == 0`，必须到达列表第一个可见项目，不是“接近顶部”；
- `hasMoreOlder == true`；
- `isLoadingOlder == false`；
- UI 自己的上拉请求标记为空，避免重复触发。

触发后，UI 先保存当前首项位置、偏移量和消息总数，再调用 `va.E()`。`va.E()` 检查当前会话 ID，然后在 IO 协程中执行 `va$e`。

### 游标分页与列表合并

`va$e` 使用状态里的 `oldestRowId` 调用：

```text
repository.b.o(chatId, oldestRowId, 30)
```

DAO 的实际 SQL 是：

```sql
SELECT * FROM chat_message
WHERE chat_id = ? AND rowId < ?
ORDER BY rowId DESC
LIMIT 30
```

结果再次反转为时间正序、解析 JSON，然后以：

```text
olderPage + currentMessages
```

的顺序写回 `AiChatUiState`，同时更新 `hasMoreOlder` 和 `oldestRowId`。`ka$d` 观察到分页完成后，把列表滚动位置向后移动“新增消息数”，使用户仍停留在原来看到的消息附近，再清除 UI 请求标记。

### UI 合并与滚动恢复的隐藏细节

宿主界面在渲染前会从 `AiChatUiState.messages` 过滤掉所有 `role=tool` 消息，再把剩余列表反转用于显示。数据库分页和状态合并却仍按原始 `chat_message` 行、原始 `u7` 列表计数。因此：

- 一页 30 条数据库记录可能包含大量工具输出，最终可见的新气泡远少于 30 条，甚至没有可见文本。
- `ka$c` 保存的是状态列表的原始消息数量；`ka$d` 也用原始列表数量计算新增项，再调用 `LazyListState.scrollToItem`。隐藏的工具行会使恢复位置偏移，表现为列表跳动、看起来没有加载或跳过内容。
- 工具输出跨页时，UI 看不到它们，但它们仍占用数据库分页配额和 `oldestRowId` 游标。

上拉请求还使用一个 UI `Triple(firstIndex, firstOffset, oldMessageCount)` 作为进行中标记。`ka$d` 只以“原始消息数量”和 `isLoadingOlder` 为键观察完成状态：

- 正常返回至少一条可解析消息时，列表数量改变，`ka$d` 恢复位置并清除标记。
- 空页或异常页走 `va$e$a`：消息列表和 `isLoadingOlder` 不变，只更新分页状态/游标；这两个观察键可能都不变，因此静态代码显示该标记可能不会被清除。
- 标记一旦残留，`ka$c` 的“进行中标记为空”条件会一直失败；用户继续上拉时不会再次调用 `va.E()`，直到页面/会话重新创建。这是静态可达的空页/异常页失败模式；本次运行时成功链路没有进入该分支，不能把它作为当前 76 行现场的根因。

此外，已读到的 `va.E()`、`va$e` 上拉路径没有先把 `isLoadingOlder` 置为 true；完成非空页时明确写回 false。当前重复请求保护主要依赖上述 UI 标记，而不是数据库事务或独立分页锁。

### 当前数据库分页重放证据

2026-08-18 15:12（UTC+8）通过 KernelSU 只读复制当前宿主的 `ai_chat.db`、WAL 和 SHM，再从副本分析；副本 `integrity_check=ok`。按最新有消息的会话重放宿主 `rowId DESC LIMIT 30` 分页，得到：

- 初始页：30 个数据库行，7 个 UI 可见非 tool 行，23 个隐藏 tool 行，0 个 JSON 解析失败。
- 第一次上拉页：30 个数据库行，3 个 UI 可见非 tool 行，27 个隐藏 tool 行，0 个 JSON 解析失败。
- 第二次上拉页：16 个数据库行，2 个 UI 可见非 tool 行，14 个隐藏 tool 行，0 个 JSON 解析失败。

对应游标依次为 `1015862`、`1015832`、`1015816`。这证明在当前真实数据上，即使 DAO 成功返回完整 30 行，一次上拉也可能只增加 3 个可见气泡；宿主却在 `ka$d` 中按原始列表差值 `30` 计算滚动目标。因而“查询成功但可见变化很小或滚动位置异常”已经有当前数据证据，不再只是抽象可能性。

采集时设备处于锁屏，前台 Activity 不是宿主；该快照不能证明当时内存中的 `hasMoreOlder`、`isLoadingOlder` 或 UI 请求标记值，也不能证明标记卡死分支已经现场发生。宿主 PID `9774` 保持存活且未重启，便于后续在不清除状态的前提下继续捕获。

### 前台手势现场

2026-08-18 15:31 至 15:34（UTC+8），用户把上述 76 行会话切到前台并停在历史顶部。只读状态确认：

- `topResumedActivity`、`mCurrentFocus` 和 `mFocusedApp` 都是 `me.yun.lspilot/.ui.MainActivity`；进程仍为原 PID `9774`，没有重启。
- UI 标题 `请阅读项目各模块和文档，接受该项目。当前...` 与数据库会话 `2dd8efb9-...` 的标题完全一致，确认测试的是 76 行会话。
- 在顶部执行一次向更早消息方向的受控拖动后，UI XML SHA-256 仍为 `3f71d5ba...d37971`，没有可见页面变化，也没有当前宿主的分页/SQLite/JSON/索引异常日志。
- 反向拖动后 UI XML 变为 `9aa2dd3f...4221c`，文本节点从 23 增至 28；再拖回顶部后精确恢复为原哈希和 23 个文本节点。这证明列表本身能接收手势和滚动，不是整个 Compose 列表冻结。

本轮外部只读采集当时仍不能读取 Compose 内存状态。随后经用户明确授权，仅在模块 APK 中临时加入只读分页遥测；宿主 APK、宿主数据库和 UI 状态均未修改。临时构建未发布，采集完成后源码和设备都恢复到稳定版 `1.7.5 (67)`。

### 临时遥测运行时确认

2026-08-18 16:41（UTC+8），临时遥测在宿主 PID `32576` 中完整捕获了同一 76 行会话从首次加载到末页的链路：

```text
repo.n(chatId, 30)
  → messages=30, hasMoreOlder=true, oldestRowId=1015862

guard: firstIndex=0, messages=30, hasMoreOlder=true,
       isLoadingOlder=false, marker=null
  → va.E()
  → repo.o(chatId, 1015862, 30)
  → returned=30, hasMoreOlder=true, oldestRowId=1015832
  → messages=60
  → scroll restored to firstIndex=9
  → marker=null

guard: firstIndex=0, messages=60, hasMoreOlder=true,
       isLoadingOlder=false, marker=null
  → va.E()
  → repo.o(chatId, 1015832, 30)
  → returned=16, hasMoreOlder=false, oldestRowId=1015816
  → messages=76
  → scroll restored to firstIndex=10
  → marker=null
```

两次旧页查询分别约 3 ms 和 2 ms 返回；没有分页、SQLite、JSON 或索引异常。两次触发前 guard 都满足，`va.E()` 都执行，完成观察者也都在滚动恢复后清除了请求标记。因而本次现场明确排除：guard 错误拒绝、数据库加载失败、空页以及 stale in-flight marker。

本次“像没有加载”的具体原因是可见层与分页层计数不同，并叠加了锚点恢复：

- 第一旧页实际增加 30 个原始消息，但只有 3 个非 tool 气泡可显示；UI 随后把视口从顶部恢复到 `firstIndex=9`，继续显示加载前附近的内容。
- 最后一页实际增加 16 个原始消息，但只有 2 个非 tool 气泡可显示；UI 随后恢复到 `firstIndex=10`。
- 加载结束后 `hasMoreOlder=false`；继续在顶部拖动会被正常拒绝，不再发起数据库查询。

因此，对当前 76 行现场，“历史加载不出来”不是数据没加载，而是绝大多数新行被 `role=tool` 过滤，剩余少量气泡又被滚动锚点恢复留在当前视口上方。静态分析发现的空页/异常页 marker 残留仍是代码上存在的独立风险，但本次成功链路没有发生该分支。

### 为什么经常表现为“加载不出来”

当前静态和运行时证据支持以下原因，按确定程度排序：

1. **当前现场已确认是隐藏行与滚动恢复共同掩盖成功加载**：两次 `repository.b.o` 分别成功返回 30 行和 16 行，状态从 30 增至 60、再增至 76；但对应页面只有 3 个和 2 个可见非 tool 气泡，`ka$d` 又把视口恢复到 `firstIndex=9/10`。这会让用户继续看到加载前附近的内容。
2. **末页后的顶部拖动本来就不会查询**：当前现场加载完 76 行后 `hasMoreOlder=false`，后续 guard 正常返回；这不是失败。
3. **触发条件仍然很窄**：其他会话如果没有到达 `firstVisibleItemIndex == 0`，或者 `hasMoreOlder` 已是 false，UI 不会执行查询。
4. **空页/异常页 marker 残留仍是独立静态风险**：如果查询异常、游标无效或整页 JSON 都解析失败，列表数量和 `isLoadingOlder` 可能不变，`ka$d` 可能无法清除进行中标记；本次运行时没有进入该分支。
5. **本地异步链路没有错误反馈**：数据库/单行 JSON 错误被静默处理，宿主不会向用户区分“没有更多”“解析失败”和“查询失败”。

当前 76 行现场已经同时捕获顶部索引、`hasMoreOlder`、`isLoadingOlder`、`oldestRowId`、查询返回数量和 marker 生命周期，具体原因已闭环。对其他会话若再次出现首次查询后永久失效，再优先捕获空页/异常页分支，而不是扩大或移除分页限制。

## 修复后真实验证

### 第一阶段：坏窗口现场验证

修复验证版 `1.7.5-preview.1 (67)` 先安装并完成了请求级现场验证；当时受影响会话的 30 条窗口仍以 4 个孤立 `tool` 输出开头。用户发送 `ping` 后成功返回，实时 Xposed 日志记录：

```text
Tool-call context repaired changes=4
OpenAI cache usage: input_tokens=85640, output_tokens=1655, total_tokens=87295, cached_tokens=81263
Enhanced OpenAI request: model=gpt-5.6-sol, reasoning=max, explicitBreakpoints=4, usage=true
```

同一时间窗没有 `HTTP 400`、`HTTP 502`、`No tool call found`、`Upstream request failed` 或请求增强异常。数据库只读快照仍为 `integrity_check=ok`，原始 4 个孤立输出仍存在，证明模块只改了出站 JSON 副本，没有偷偷修改宿主历史。

### 第二阶段：连续工具调用组加固

最终稳定版 `1.7.5 (67)` 在上述修复基础上增加了更严格的连续组约束：

- assistant 的 `tool_calls` 必须有对应输出，且输出必须紧跟在该 assistant 后面的连续 `role=tool` 组内。
- 删除孤立、迟到、重复、跨普通消息边界的 tool 输出。
- 删除重复或无 ID 的 tool call；相同 ID 不在历史中重复保留。
- assistant 只保留已经完成的调用；没有有意义文本的残缺 assistant 碎片一并删除。
- 普通 user/assistant 文本保留；宿主数据库、UI、repository 和自动重试行为不触碰。
- 可选缓存/reasoning/usage 策略失败时，仍保留已完成的结构修复；只有没有结构变化时才回退原始请求。

新增回归场景先在旧实现上变红：延迟输出样本仍残留 5 条消息；改为连续组修复后 Dalvik 检查变绿。`ToolCallSanitizerCheck` 当前覆盖孤立输出、缺失输出、重复输出、延迟输出、普通文本和现场 4+6+8+8 窗口重放。

### 第三阶段：最终稳定 APK 验证

最终 APK 已重新构建、安装并通过四阶段哈希校验：

```text
version: 1.7.5 (67)
size: 679,443 bytes
SHA-256: 5d8d3a50c8d21148fbb79e397d30490a58bd955d79096b649e37dd06aa9d9e01
host ABI: provider=zj8, requestBody=true, sseUsage=true
```

在明确加载最终模块的宿主进程 PID 9774 中，用户再次在原始故障对话发送 `ping` 并成功返回。请求前后日志明确记录：

```text
Tool-call context repaired changes=12
Reasoning effort applied=max
Enhanced OpenAI request: model=gpt-5.6-sol, reasoning=max, explicitBreakpoints=4, usage=true
OpenAI cache usage: input_tokens=35374, output_tokens=742, total_tokens=36116, cached_tokens=34304
```

该窗口没有 `HTTP 400`、`HTTP 502`、`No tool call found`、`Upstream request failed` 或 request-enhancement exception。最新只读 SQLite 快照仍为 `integrity_check=ok`；模块没有写回宿主数据库。`changes=12` 表明最终严格版实际删除了当前历史窗口中的残缺/非连续工具调用片段，而不是只完成启动检查。

## 模块修复位置

修复位于模块唯一的出站请求 JSON 边界：`LSPilotEnhancerModule` 的两条 request-body ABI hook 都调用 `ToolCallSanitizer.repair(body)`。

修复后的请求会在发送给 provider 前满足：所有保留的 tool 输出属于紧邻的 assistant 工具调用组；所有保留的 assistant 工具调用都有输出；普通文本消息不被删除。它不能凭空恢复宿主已经从 30 条窗口之外删除的旧上下文；旧上下文恢复需要另一个宿主数据加载策略，不能通过猜测补造。

## 验证结论

- 502 的 `call_id` 与数据库孤立输出精确匹配；红/绿结构验证证明该序列本身非法。
- 宿主固定 `LIMIT 30` 和 `va.x` 的“只补缺失输出、不删孤立输出”组合是根因链。
- 400 的完整出站 JSON 没有被现场持久化，因此文档仍把它标为同类 malformed request 经网关包装的高概率表现，不伪称逐请求证明。
- 最终模块已安装、模块进程已实际执行 `changes=12` 修复、请求成功返回、SSE usage 正常、数据库保持完整；当前目标要求的中文诊断与修复证据已闭环。
