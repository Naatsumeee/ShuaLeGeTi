# AI 导入、做题恢复与问 AI 持久化设计

日期：2026-11-17

范围：需求 1-5、7；需求 6（RapidOCR 2/3/4 路并发）在 A/B/C 稳定后单独设计和实施。

## 1. 背景与问题

当前实现存在以下用户可见问题：

1. AI 导入把全部分块同时丢给 `Semaphore`，结果却按分块顺序 `await`。前面的慢分块会阻塞状态更新，造成长时间卡住后才集中出现结果。
2. 文本解析明确拒绝超过 3 位的题号，`1000.` 会被当成非题目。
3. 解析完成页只显示低覆盖章节，无法展开查看缺失答案对应的页码、题号和分块。
4. 做题过程只把本轮作答保存在 ViewModel 内存中。切屏、进程回收或重新进入后，已作答状态会丢失。
5. 题目切换动画期间，邻卡的作答快照不稳定，已显示解析的题会在动画中短暂消失。
6. 章节题型页只在 ViewModel 初始化时读取一次“上次进度”，完成一轮返回后仍可能显示旧进度。
7. 底部控制区在“先作答 / 下一题 / 完成”和正确/错误状态之间切换时没有过渡动画。
8. `ModalBottomSheet` 同时受 Sheet 自动 IME 调整和面板内 inset 影响；流式输出又逐 token 触发滚动动画，导致键盘遮挡和动画混乱。
9. 问 AI 对话只存在内存中，切题即丢失。

## 2. 目标

- 导入首个分段快速返回，后续分段固定并发、空闲即补位。
- 状态显示完成段数，不显示“当前第几段”。
- 支持四位原始题号，并修复题号徽标换行错位。
- 解析完成页可展开查看缺答案与失败分段的具体页码和题目。
- 当前学习轮次可以可靠恢复每次作答、结果和时间戳。
- 已作答题目的解析在切题动画期间持续可见。
- 每道题的问 AI 完整对话持久化到题库数据库。
- 输入法出现时输入框始终可见，流式输出只触发可控的滚动行为。
- 所有新增行为具备自动化回归和真机验证路径。

## 3. 非目标

- 本设计不包含 RapidOCR 2/3/4 路并发；它作为后续 D 模块单独设计。
- 不引入独立的学习轮次表、作答表或聊天表。
- 不改变错题本、统计和现有作答历史的业务含义。
- 不把首页问 AI 会话写入题库；首页没有题目上下文。

## 4. 总体架构决策

采用最小增量方案，复用现有数据模型：

- `study_progress.roundId` 标识当前学习轮次。
- `attempt_records.runId` 复用为学习轮次编号。
- `questions.askChatJson` 保存每道题的完整问 AI 对话。
- `import_jobs.parseReportJson` 保存解析失败段和缺失答案审计。
- 数据库版本从 v16 升级到 v17，并提供正式迁移。

不新增重复作答表或聊天表，避免重新实现现有的错题、统计、导出和清理链路。

## 5. 数据模型

### 5.1 StudyProgress

新增字段：

```text
roundId TEXT DEFAULT NULL
```

规则：

- 新建一轮进度时生成 UUID。
- 旧进度 `roundId = NULL` 时，在原记录上懒生成新 UUID。
- 不尝试把旧 `attempt_records` 反推归属到旧轮次。
- 本轮完成后仍删除 `StudyProgress`，作答历史保留在 `AttemptRecord`。

### 5.2 AttemptRecord

不为学习模式新增字段，复用：

- `id`：自增唯一编号。
- `runId`：学习时为 `StudyProgress.roundId`，测试模式继续用考试 run id。
- `answeredAt`：作答时间戳。
- `durationMs`：作答用时。

同一题在当前轮次存在多条记录时，按 `answeredAt DESC, id DESC` 选择最后一条。

### 5.3 Question

新增字段：

```text
askChatJson TEXT DEFAULT NULL
```

JSON 结构：

```json
[
  {
    "id": "uuid",
    "role": "user",
    "text": "为什么不选 C",
    "createdAt": 1760000000000
  },
  {
    "id": "uuid",
    "role": "assistant",
    "text": "完整回答……",
    "createdAt": 1760000005000
  }
]
```

只保存用户消息和完整助手回答。流式中间结果、失败原因和半截助手回答不落库。

### 5.4 ImportJob

新增字段：

```text
parseReportJson TEXT DEFAULT NULL
```

报告结构：

```json
{
  "failedChunks": [
    {
      "chunkIndex": 3,
      "pageFrom": 8,
      "pageTo": 9,
      "reason": "模型返回不可解析"
    }
  ],
  "missingAnswers": [
    {
      "chunkIndex": 3,
      "pages": [8, 9],
      "chapter": "第十章 多基因病",
      "number": 1000,
      "stemPreview": "题干摘要"
    }
  ]
}
```

页码来源优先级：

1. AI 返回的 `ParsedLine.page`。
2. 当前分块原文范围内出现的 `[[PG:n]]`。
3. 无法确定时省略页码，UI 显示“页码未知”。

## 6. A 模块：AI 解析导入

### 6.1 调度

输入分块保持 `ChunkPlanner.Chunk.index`：

- `pendingChunks` 按 chunk index 升序排列。
- 全新任务先单独执行第一个 pending chunk，作为 warm-up。
- warm-up 成功或失败后，再启动固定大小 worker 池。
- worker 池大小等于 `parseConcurrency`，范围保持 1-8。
- 每个 worker 完成当前分块后立即从队列取下一个。
- 恢复任务如果已有落盘分块，不再 warm-up，直接进入 worker 池。

worker 池必须满足：

- 任意时刻活跃 worker 不超过配置并发数。
- 快分块完成后立即补位，不等待其他分块。
- 单个分块最终失败会记录失败原因，worker 继续处理后续分块。
- 全部新分块均失败时，任务进入 FAILED。

### 6.2 持久化与顺序

- `ParsedLine.chunkIndex` 和 `indexInChunk` 始终保留。
- JSONL 可按完成顺序追加，但读取和 UI 必须按：

```text
chunkIndex * 100000 + indexInChunk
```

排序。
- 并发 worker 不直接竞争写文件；由单一 writer 串行追加，避免 JSONL 行交错。

### 6.3 状态与流式预览

- 状态只显示“已完成 X/Y 段 · 已解析 Z 题”。
- 不使用“正在第 X 段”作为主状态。
- 只有 warm-up 分块允许更新 `streamTail`。
- 进入并发阶段后停止拼接多个模型的流式正文，避免文字交错。
- UI 已解析列表以 chunk 顺序稳定展示，不因完成顺序改变题目位置。

### 6.4 题目编号解析

修复 `TextQuestionParser.isLikelyNotQuestion()`：

- 识别 1-4 位题号。
- `1000. 题干`、`1000、题干` 必须成为题目。
- 继续排除 `2023.1`、`3.5厘米` 等数字误判。

### 6.5 题号徽标

新增共享 `QuestionNumberBadge`：

- 1-2 位：保持圆形。
- 3-4 位：高度固定，横向扩展为胶囊。
- 强制单行：`maxLines = 1`、`softWrap = false`、居中。
- 使用等宽数字样式。
- 应用于导入预览、编辑题库题目列表等题号徽标。
- 答题卡固定 38dp 格保持尺寸；4 位题号自动缩小字号并禁止换行。

### 6.6 解析问题页

解析完成页增加可展开区域：

- 摘要：缺失答案 N 题、失败 M 段。
- 按 chunk 分组。
- 每条缺失答案显示页码、章节、题号、题干摘要。
- 失败段显示页码范围与失败原因。
- 无页码时明确显示“页码未知”。

## 7. B 模块：作答恢复与动画

### 7.1 当前轮次恢复

- `StudySession` 暴露 `roundId`。
- 作答时把 `roundId` 写入 `AttemptRecord.runId`。
- 每次作答立即更新 UI，并异步持久化记录与进度时间戳。
- `reload()` 查询当前 `roundId` 的全部记录，按题目取最新一条。
- 恢复字段包括：
  - 选中项
  - 输入文本
  - 是否正确
  - 已揭晓/解析状态
  - 深度解析状态
  - 本题问 AI 对话
- 历史轮次记录不出现在当前轮次恢复中。

### 7.2 解析连续性

- 翻页前把当前完整 `StudyUiState` 同步写入 `answerMemo[questionId]`。
- `stateFor(target)` 优先读取该题快照。
- 邻卡仍保持 `interactive = false`。
- 解析卡显示条件改为：

```text
revealed && (interactive || answered)
```

- 未作答题和背题模式邻卡不提前泄露解析。
- 已作答题在滑入、滑出和动画结束期间都必须持续显示解析。

### 7.3 底部控制动画

- 正确/错误区域使用 `AnimatedContent`。
- 按钮文案在“先作答 / 下一题 / 完成”之间使用 `AnimatedContent`。
- 底部控制区和结果行使用统一的 `animateContentSize`。
- 禁止同时叠加两套高度动画。
- 倒计时与结果状态共用同一动画容器。

### 7.4 章节题型页实时刷新

- `ProgressDao` 增加按 bank 观察全部进度的 Flow。
- `StudySetupViewModel` 持续订阅该 Flow。
- 新增、更新、删除进度时重新计算“上次进度”列表。
- 已完成轮次返回后立即消失。
- 队尾残留进度继续过滤，并在安全时机清理。

## 8. C 模块：问 AI 持久化与键盘

### 8.1 消息持久化

- 用户消息发送后立即追加并写 `Question.askChatJson`。
- 助手回答仅在完整结束后写入。
- 失败、取消或切题时：
  - 用户消息保留。
  - 半截助手回答丢弃。
  - UI 可显示本次错误，但错误不写入对话 JSON。
- 清空对话写入空 JSON。
- 重新进入题目时从 `Question.askChatJson` 恢复。

### 8.2 请求保护

- 请求开始时记录 `askedQuestionId`。
- 流式增量只更新当前仍匹配的题目。
- 完整回答按原题目 id 写回，不能落入切题后的新题。
- 翻页快照保留该题 chat。

### 8.3 键盘与 Sheet

- 使用单一 IME inset 来源：`ModalBottomSheet` 完整展开并显式消费 `WindowInsets.ime`。
- 面板内部不再叠加第二份 `imePadding`。
- 输入区固定在面板底部，对话区使用 `weight(1f)`。
- 输入框聚焦时使用 `BringIntoViewRequester` 保证可见。
- 新消息完成时执行一次 `animateScrollToItem`。
- 流式输出期间使用节流后的 `scrollToItem`，不逐 token 启动动画。
- 滚动触发条件只使用稳定消息 id、消息数量和 `asking`，不使用流式字符串长度。
- 关闭操作由一个协程串行执行：先 `hide()`，再更新 `askOpen`。
- 按钮、下滑和“关闭”不能同时启动多个关闭动画。

## 9. 数据库迁移 v16 -> v17

迁移必须使用正式 SQL：

```sql
ALTER TABLE study_progress ADD COLUMN roundId TEXT DEFAULT NULL;
ALTER TABLE questions ADD COLUMN askChatJson TEXT DEFAULT NULL;
ALTER TABLE import_jobs ADD COLUMN parseReportJson TEXT DEFAULT NULL;
```

迁移要求：

- 不删除表，不重建旧数据。
- 不修改 `fallbackToDestructiveMigration` 的行为。
- 真机升级验证必须确认题库、作答、错题和设置均保留。

## 10. 错误处理

- 导入单段失败不阻塞队列；失败原因进入 `parseReportJson`。
- 数据库写入失败时保留 UI 错误提示，不谎报已持久化。
- 问 AI 失败保留用户问题，不写半截助手回答。
- 当前题目切换后收到的旧请求结果直接丢弃。
- 旧进度没有 round id 时创建新轮次，不读取历史作答。

## 11. 测试策略

### 11.1 单元测试

- 首段单独执行，后续固定并发且空闲立即补位。
- 快段不等待慢段才补位。
- 失败段不阻塞队列。
- 四位数题号和数字误判边界。
- parse report JSON 编解码与分组。
- 当前轮次只恢复本轮作答。
- 同题多记录按时间戳和 id 取最新。
- 问 AI 只持久化完整回答。
- chat JSON 编解码和清空。
- 章节题型页 progress Flow 删除后不再返回旧进度。

### 11.2 Compose/UI 验证

- 题号徽标 `1 / 99 / 100 / 1000` 不换行、不撑高。
- 字体缩放 1.0 / 1.5 下题号徽标稳定。
- 已作答邻卡解析框在动画全程存在。
- 未作答邻卡不显示解析。
- 底部控制文案和高度切换有动画。
- 问 AI 空态、短对话、长对话下键盘不遮挡输入框。
- 流式输出不连续启动滚动动画。

### 11.3 真机验证

- 覆盖安装 v16 数据库版本，确认 v17 迁移成功。
- 导入包含四位数题号和缺答案段的真实题库。
- 导入中切出应用，回来确认完成段数与进度不倒退。
- 做几题后强制停止应用，重新进入恢复选中项、结果和解析。
- 完成一轮后返回章节题型页，确认旧进度消失。
- 对同一题追问多轮，切题后返回，确认对话恢复。
- 键盘弹出时发送、流式输出、滚动到底和关闭面板。

## 12. 验收标准

- 首批解析结果不再长期等待所有前置分块。
- 并发数固定，空闲 worker 立即接下一段。
- 状态使用完成段数，文件名和题目顺序稳定。
- `1000-9999` 的四位题号可解析和正确显示。
- 解析完成页可展开查看具体页码与题目。
- 当前轮次作答可跨切屏、重进和进程重启恢复。
- 解析框在切题动画中不消失。
- 完成轮次不再出现在上次进度。
- 每题问 AI 对话可持久恢复，键盘不被遮挡。
- 全量单测、release 构建、v16->v17 真机迁移通过。

## 13. 后续模块

D 模块在 A/B/C 发布后单独执行：

- RapidOCR 1/2/3/4 路实验开关。
- 2 路以上性能警告。
- native 引擎实例池和安全销毁。
- 崩溃或 OOM 自动回退 1 路。
- 真机吞吐、内存和温控压测。
