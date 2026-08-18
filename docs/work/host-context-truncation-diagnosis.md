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
