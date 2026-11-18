# AI 导入、做题恢复与问 AI 持久化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 A/B/C 三个模块：AI 导入首段 warm-up 与固定 worker 池、当前轮次作答恢复与动画连续性、每道题问 AI 完整对话持久化。

**Architecture:** 复用现有 `attempt_records` 和 `questions`，通过一次 v17 迁移增加 `StudyProgress.roundId`、`Question.askChatJson`、`ImportJob.parseReportJson`。导入侧增加可测试的 warm-up + worker pool；学习侧以 round id 恢复当前轮次；问 AI 侧只持久化完整消息并统一 IME/Sheet 动画源。

**Tech Stack:** Kotlin、Jetpack Compose、Material3、Room、WorkManager、Kotlin Serialization、JUnit/kotlin.test。

**Spec:** `docs/superpowers/specs/2026-11-17-import-study-askai-design.md`

## Global Constraints

- 数据库必须从 v16 正式迁移到 v17，不得依赖 `fallbackToDestructiveMigration`。
- 不新增学习轮次表、作答表或聊天表。
- `AttemptRecord.runId` 在学习模式中保存 `StudyProgress.roundId`。
- 无参考答案的作答写入 `correctKnown = false`，不得进入错题或正确率统计。
- 问 AI 只保存完整助手回答；失败、取消、切题时丢弃半截助手内容。
- 导入第一段必须单独 warm-up；后续 worker 数不超过配置并发数。
- 导入状态只显示已完成段数，不显示“当前第几段”。
- 四位数题号范围为 `1000-9999`，题号徽标必须单行显示。
- RapidOCR 2/3/4 路并发不在本计划内。
- 所有代码任务先写失败测试，再写最小实现。
- 每个任务结束前运行相关测试；全部任务结束后运行全量测试、release 构建和真机迁移验证。

---

## File Structure

**创建**

- `app/src/main/java/com/examhelper/shua/data/parse/ParseReport.kt`：解析失败段、缺失答案 DTO 和审计构建函数。
- `app/src/main/java/com/examhelper/shua/data/parse/ChunkParseScheduler.kt`：warm-up + 固定 worker 池。
- `app/src/main/java/com/examhelper/shua/ui/theme/QuestionNumberBadge.kt`：共享自适应题号徽标。
- `app/src/test/java/com/examhelper/shua/ParseReportTest.kt`
- `app/src/test/java/com/examhelper/shua/ChunkParseSchedulerTest.kt`
- `app/src/test/java/com/examhelper/shua/StudyAnswerRestoreTest.kt`
- `app/src/test/java/com/examhelper/shua/QuestionChatPersistenceTest.kt`
- `app/src/test/java/com/examhelper/shua/StudySavedProgressFlowTest.kt`

**修改**

- `app/src/main/java/com/examhelper/shua/data/db/Entities.kt`
- `app/src/main/java/com/examhelper/shua/data/db/AppDatabase.kt`
- `app/src/main/java/com/examhelper/shua/data/db/Daos.kt`
- `app/src/main/java/com/examhelper/shua/data/model/Models.kt`
- `app/src/main/java/com/examhelper/shua/data/parse/TextQuestionParser.kt`
- `app/src/main/java/com/examhelper/shua/data/importjob/ImportPipelineWorker.kt`
- `app/src/main/java/com/examhelper/shua/data/repo/StudyRepository.kt`
- `app/src/main/java/com/examhelper/shua/ui/import_/ImportViewModel.kt`
- `app/src/main/java/com/examhelper/shua/ui/import_/ImportScreen.kt`
- `app/src/main/java/com/examhelper/shua/ui/study/StudyViewModel.kt`
- `app/src/main/java/com/examhelper/shua/ui/study/StudyScreen.kt`
- `app/src/main/java/com/examhelper/shua/ui/study/StudySetupViewModel.kt`
- `app/src/main/java/com/examhelper/shua/ui/study/AskAiPanel.kt`
- `app/src/main/java/com/examhelper/shua/ui/bank/HomeAskViewModel.kt`
- `app/src/main/java/com/examhelper/shua/ui/editor/EditorScreen.kt`
- `app/src/test/java/com/examhelper/shua/TextQuestionParserTest.kt`

**文档收尾**

- `AGENTS.md`
- `MEMORY.md`

---

### Task 1: 数据库 v17、报告 DTO 与聊天 DTO

**Files:**
- Create: `app/src/main/java/com/examhelper/shua/data/parse/ParseReport.kt`
- Modify: `app/src/main/java/com/examhelper/shua/data/db/Entities.kt`
- Modify: `app/src/main/java/com/examhelper/shua/data/db/AppDatabase.kt`
- Modify: `app/src/main/java/com/examhelper/shua/data/model/Models.kt`
- Modify: `app/src/main/java/com/examhelper/shua/ui/study/StudyViewModel.kt`
- Modify: `app/src/main/java/com/examhelper/shua/ui/study/AskAiPanel.kt`
- Modify: `app/src/main/java/com/examhelper/shua/ui/study/StudyScreen.kt`
- Modify: `app/src/main/java/com/examhelper/shua/ui/bank/HomeAskViewModel.kt`
- Test: `app/src/test/java/com/examhelper/shua/ParseReportTest.kt`
- Test: `app/src/test/java/com/examhelper/shua/QuestionChatPersistenceTest.kt`

**Interfaces:**
- Produces: `Question.askChatJson: String?`
- Produces: `StudyProgress.roundId: String?`
- Produces: `ImportJob.parseReportJson: String?`
- Produces: `AttemptRecord.correctKnown: Boolean = true`
- Produces: `@Serializable data class ChatTurn(val role: String, val text: String, val id: String = UUID.randomUUID().toString(), val createdAt: Long = System.currentTimeMillis())`
- Produces: `JsonCodec.encodeChat(List<ChatTurn>): String`
- Produces: `JsonCodec.decodeChat(String?): List<ChatTurn>`
- Produces: `ParseReport`, `FailedChunk`, `MissingAnswer`

- [ ] **Step 1: Write failing DTO tests**

```kotlin
class ParseReportTest {
    @Test
    fun parseReportRoundTrips() {
        val report = ParseReport(
            failedChunks = listOf(FailedChunk(3, 8, 9, "不可解析")),
            missingAnswers = listOf(
                MissingAnswer(3, listOf(8, 9), "第十章", 1000, "题干摘要")
            )
        )
        assertEquals(report, ParseReport.decode(ParseReport.encode(report)))
    }
}

class QuestionChatPersistenceTest {
    @Test
    fun chatRoundTripsWithStableIdentity() {
        val turns = listOf(
            ChatTurn(role = "user", text = "问", id = "u1", createdAt = 1L),
            ChatTurn(role = "assistant", text = "答", id = "a1", createdAt = 2L)
        )
        assertEquals(turns, JsonCodec.decodeChat(JsonCodec.encodeChat(turns)))
    }
}
```

- [ ] **Step 2: Run tests and verify failure**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*ParseReportTest" --tests "*QuestionChatPersistenceTest" --no-daemon
```

Expected: FAIL because DTOs and codecs do not exist.

- [ ] **Step 3: Add the four entity fields and v17 migration**

```kotlin
const val APP_DATABASE_VERSION = 17

data class Question(
    // existing fields...
    val askChatJson: String? = null
)

data class StudyProgress(
    // existing fields...
    val roundId: String? = null
)

data class ImportJob(
    // existing fields...
    val parseReportJson: String? = null
)

data class AttemptRecord(
    // existing fields...
    val correctKnown: Boolean = true
)
```

Migration:

```kotlin
private val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE study_progress ADD COLUMN roundId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE questions ADD COLUMN askChatJson TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE import_jobs ADD COLUMN parseReportJson TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE attempt_records ADD COLUMN correctKnown INTEGER NOT NULL DEFAULT 1")
    }
}
```

Register `MIGRATION_16_17` in `addMigrations`.

- [ ] **Step 4: Add serializable DTOs**

```kotlin
@Serializable
data class FailedChunk(
    val chunkIndex: Int,
    val pageFrom: Int? = null,
    val pageTo: Int? = null,
    val reason: String
)

@Serializable
data class MissingAnswer(
    val chunkIndex: Int,
    val pages: List<Int> = emptyList(),
    val chapter: String? = null,
    val number: Int? = null,
    val stemPreview: String
)

@Serializable
data class ParseReport(
    val failedChunks: List<FailedChunk> = emptyList(),
    val missingAnswers: List<MissingAnswer> = emptyList()
) {
    fun encode(): String = JsonCodec.json.encodeToString(this)
    companion object {
        fun decode(raw: String?): ParseReport =
            runCatching { JsonCodec.json.decodeFromString<ParseReport>(raw ?: return ParseReport()) }
                .getOrDefault(ParseReport())
    }
}
```

Add encode/decode chat methods to `JsonCodec`.

Move `ChatTurn` from `StudyViewModel.kt` to `data/model/Models.kt` as a serializable data class. Update imports in `StudyViewModel`, `AskAiPanel`, `StudyScreen`, and `HomeAskViewModel`.

- [ ] **Step 5: Run tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*ParseReportTest" --tests "*QuestionChatPersistenceTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/examhelper/shua/data/db/Entities.kt app/src/main/java/com/examhelper/shua/data/db/AppDatabase.kt app/src/main/java/com/examhelper/shua/data/model/Models.kt app/src/main/java/com/examhelper/shua/data/parse/ParseReport.kt app/src/main/java/com/examhelper/shua/ui/study/StudyViewModel.kt app/src/main/java/com/examhelper/shua/ui/study/AskAiPanel.kt app/src/main/java/com/examhelper/shua/ui/study/StudyScreen.kt app/src/main/java/com/examhelper/shua/ui/bank/HomeAskViewModel.kt app/src/test/java/com/examhelper/shua/ParseReportTest.kt app/src/test/java/com/examhelper/shua/QuestionChatPersistenceTest.kt
git commit -m "新增 v17 数据模型与解析报告 DTO"
```

---

### Task 2: 四位数题号与共享题号徽标

**Files:**
- Create: `app/src/main/java/com/examhelper/shua/ui/theme/QuestionNumberBadge.kt`
- Modify: `app/src/main/java/com/examhelper/shua/data/parse/TextQuestionParser.kt`
- Modify: `app/src/main/java/com/examhelper/shua/ui/import_/ImportScreen.kt`
- Modify: `app/src/main/java/com/examhelper/shua/ui/editor/EditorScreen.kt`
- Test: `app/src/test/java/com/examhelper/shua/TextQuestionParserTest.kt`

**Interfaces:**
- Produces: `@Composable fun QuestionNumberBadge(number: Int, compact: Boolean = false, modifier: Modifier = Modifier)`
- Consumes: existing parser test helpers.

- [ ] **Step 1: Add failing parser tests**

```kotlin
@Test
fun fourDigitQuestionNumbersAreQuestions() {
    val qs = TextQuestionParser.parse(
        """
        1000. 四位题号题目
        A. 甲
        B. 乙
        1001、下一题
        A. 丙
        B. 丁
        """.trimIndent()
    )
    assertEquals(listOf(1000, 1001), qs.map { it.sourceNumber })
}

@Test
fun decimalsAreStillNotQuestionNumbers() {
    val qs = TextQuestionParser.parse("2023.1 不是题号\n3.5厘米 也不是题号")
    assertTrue(qs.isEmpty())
}
```

- [ ] **Step 2: Run parser tests and verify failure**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*TextQuestionParserTest.fourDigitQuestionNumbersAreQuestions" --no-daemon
```

Expected: FAIL with zero questions.

- [ ] **Step 3: Fix the number guard**

```kotlin
private fun isLikelyNotQuestion(m: MatchResult): Boolean {
    val number = m.groupValues[1]
    val rest = m.groupValues[2]
    if (number.length > 4) return true
    return rest.isNotEmpty() && rest[0].isDigit()
}
```

- [ ] **Step 4: Add the shared badge**

```kotlin
@Composable
fun QuestionNumberBadge(
    number: Int,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val text = number.toString()
    val minSize = if (compact) 21.dp else 26.dp
    val fontSize = when {
        text.length >= 4 -> 9.sp
        text.length == 3 -> 10.sp
        else -> 11.sp
    }
    Box(
        modifier
            .heightIn(min = minSize)
            .widthIn(min = minSize)
            .clip(CircleShape)
            .background(shuaColors.terracottaSoft)
            .padding(horizontal = if (text.length > 2) 5.dp else 0.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = monoStyle(fontSize, FontWeight.Medium),
            color = shuaColors.terracotta,
            maxLines = 1,
            softWrap = false
        )
    }
}
```

- [ ] **Step 5: Replace fixed badges**

Replace the `Box(size(21.dp)...)` in `ImportScreen.ParsedCard` and `Box(size(26.dp)...)` in `EditorScreen.QuestionRow` with `QuestionNumberBadge`.

For the 38dp answer-sheet grid in `StudyScreen`, retain the fixed box but add:

```kotlin
fontSize = if ("${i + 1}".length >= 4) 9.sp else 12.sp
maxLines = 1
softWrap = false
```

- [ ] **Step 6: Run parser and compile tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*TextQuestionParserTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/examhelper/shua/data/parse/TextQuestionParser.kt app/src/main/java/com/examhelper/shua/ui/theme/QuestionNumberBadge.kt app/src/main/java/com/examhelper/shua/ui/import_/ImportScreen.kt app/src/main/java/com/examhelper/shua/ui/editor/EditorScreen.kt app/src/test/java/com/examhelper/shua/TextQuestionParserTest.kt
git commit -m "支持四位数题号并修复题号徽标"
```

---

### Task 3: 缺失答案与失败段审计

**Files:**
- Modify: `app/src/main/java/com/examhelper/shua/data/parse/ParseReport.kt`
- Test: `app/src/test/java/com/examhelper/shua/ParseReportTest.kt`

**Interfaces:**
- Consumes: `ChunkPlanner.Chunk`, `ParsedLine`
- Produces: `ParseReportBuilder.build(chunks, rawText, lines, failedChunks): ParseReport`

- [ ] **Step 1: Write failing audit tests**

```kotlin
@Test
fun buildReportsMissingAnswersWithPageMarkers() {
    val raw = "[[PG:8]]\n1000. 题目\n[[PG:9]]\n选项"
    val chunk = ChunkPlanner.Chunk(2, raw, 0, 3)
    val lines = listOf(
        ParsedLine(
            chunkIndex = 2,
            indexInChunk = 0,
            chapter = "第十章",
            number = 1000,
            stem = "题干",
            answer = null,
            page = null
        )
    )
    val report = ParseReportBuilder.build(listOf(chunk), raw, lines, emptyList())
    assertEquals(listOf(8, 9), report.missingAnswers.single().pages)
    assertEquals(1000, report.missingAnswers.single().number)
}
```

- [ ] **Step 2: Run test and verify failure**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*ParseReportTest.buildReportsMissingAnswersWithPageMarkers" --no-daemon
```

Expected: FAIL because `ParseReportBuilder` does not exist.

- [ ] **Step 3: Implement audit builder**

```kotlin
object ParseReportBuilder {
    fun build(
        chunks: List<ChunkPlanner.Chunk>,
        rawText: String,
        lines: List<ParsedLine>,
        failedChunks: List<FailedChunk>
    ): ParseReport {
        val pagesByChunk = chunks.associate { chunk ->
            chunk.index to pagesIn(rawText, chunk)
        }
        val missing = lines.mapNotNull { line ->
            if (!line.answer.isNullOrBlank()) return@mapNotNull null
            MissingAnswer(
                chunkIndex = line.chunkIndex,
                pages = listOfNotNull(line.page).ifEmpty {
                    pagesByChunk[line.chunkIndex].orEmpty()
                },
                chapter = line.chapter,
                number = line.number,
                stemPreview = line.stem.take(80)
            )
        }
        return ParseReport(failedChunks, missing)
    }
}
```

`pagesIn` must inspect the chunk's raw line range and return distinct `[[PG:n]]` values in ascending order.

- [ ] **Step 4: Run report tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*ParseReportTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/examhelper/shua/data/parse/ParseReport.kt app/src/test/java/com/examhelper/shua/ParseReportTest.kt
git commit -m "生成缺失答案与失败段审计"
```

---

### Task 4: Warm-up 与固定 worker 池

**Files:**
- Create: `app/src/main/java/com/examhelper/shua/data/parse/ChunkParseScheduler.kt`
- Test: `app/src/test/java/com/examhelper/shua/ChunkParseSchedulerTest.kt`

**Interfaces:**
- Produces: `data class ChunkWorkResult(val index: Int, val lines: List<ParsedLine>?, val error: String? = null)`
- Produces: `suspend fun runChunkScheduler(indices, concurrency, warmupRequired, parse, onResult)`

- [ ] **Step 1: Write failing concurrency tests**

```kotlin
@Test
fun warmupRunsAloneThenPoolStaysFull() = runBlocking {
    val events = mutableListOf<String>()
    val active = AtomicInteger()
    val maxActive = AtomicInteger()
    runChunkScheduler(
        indices = listOf(0, 1, 2, 3, 4, 5),
        concurrency = 3,
        warmupRequired = true,
        parse = { index ->
            val now = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, now) }
            events += "start$index"
            delay(if (index == 0) 50 else 10)
            active.decrementAndGet()
            ChunkWorkResult(index, emptyList())
        },
        onResult = { events += "done${it.index}" }
    )
    assertEquals("start0", events.first())
    assertEquals("done0", events[1])
    assertEquals(3, maxActive.get())
}
```

- [ ] **Step 2: Run test and verify failure**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*ChunkParseSchedulerTest.warmupRunsAloneThenPoolStaysFull" --no-daemon
```

Expected: FAIL because scheduler does not exist.

- [ ] **Step 3: Implement scheduler**

```kotlin
suspend fun runChunkScheduler(
    indices: List<Int>,
    concurrency: Int,
    warmupRequired: Boolean,
    parse: suspend (Int) -> ChunkWorkResult,
    onResult: suspend (ChunkWorkResult) -> Unit
) = coroutineScope {
    val queue = ArrayDeque(indices)
    if (warmupRequired && queue.isNotEmpty()) {
        onResult(parse(queue.removeFirst()))
    }
    if (queue.isEmpty()) return@coroutineScope

    val jobs = Channel<Int>(Channel.UNLIMITED)
    val results = Channel<ChunkWorkResult>(Channel.UNLIMITED)
    queue.forEach { jobs.send(it) }
    jobs.close()

    repeat(concurrency.coerceAtLeast(1)) {
        launch {
            for (index in jobs) results.send(parse(index))
        }
    }
    repeat(queue.size) {
        onResult(results.receive())
    }
}
```

Ensure cancellation closes channels and propagates `CancellationException`.

- [ ] **Step 4: Add fast-worker refill test**

```kotlin
@Test
fun fastWorkerConsumesNextChunkWithoutWaitingForSlowWorker() = runBlocking {
    val starts = mutableListOf<Int>()
    val slowStillRunning = AtomicBoolean(false)
    runChunkScheduler(
        indices = listOf(1, 2, 3),
        concurrency = 2,
        warmupRequired = false,
        parse = { index ->
            starts += index
            if (index == 1) slowStillRunning.set(true)
            if (index == 3) assertTrue(slowStillRunning.get())
            delay(if (index == 1) 200 else 10)
            if (index == 1) slowStillRunning.set(false)
            ChunkWorkResult(index, emptyList())
        },
        onResult = {}
    )
    assertEquals(listOf(1, 2, 3), starts)
}
```

- [ ] **Step 5: Run scheduler tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*ChunkParseSchedulerTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/examhelper/shua/data/parse/ChunkParseScheduler.kt app/src/test/java/com/examhelper/shua/ChunkParseSchedulerTest.kt
git commit -m "新增导入分段 warmup 与固定并发池"
```

---

### Task 5: 接入导入流水线与解析问题页

**Files:**
- Modify: `app/src/main/java/com/examhelper/shua/data/importjob/ImportPipelineWorker.kt`
- Modify: `app/src/main/java/com/examhelper/shua/ui/import_/ImportViewModel.kt`
- Modify: `app/src/main/java/com/examhelper/shua/ui/import_/ImportScreen.kt`
- Test: `app/src/test/java/com/examhelper/shua/ParseReportTest.kt`

**Interfaces:**
- Consumes: `runChunkScheduler`, `ParseReportBuilder`, `ImportJob.parseReportJson`
- Produces: `ImportViewModel.UiState.parseReport: ParseReport?`

- [ ] **Step 1: Add failing report decode test**

```kotlin
@Test
fun importViewModelStateCarriesParseReport() {
    val job = ImportJob(
        id = "j",
        bankName = "B",
        sourceName = "S",
        parseReportJson = ParseReport(
            missingAnswers = listOf(MissingAnswer(1, listOf(2), null, 1000, "题干"))
        ).encode()
    )
    assertEquals(1, ParseReport.decode(job.parseReportJson).missingAnswers.size)
}
```

- [ ] **Step 2: Replace queued `pending.map { async }` with scheduler**

Production integration:

```kotlin
val warmupRequired = doneChunks.isEmpty()
val failedChunks = mutableListOf<FailedChunk>()
runChunkScheduler(
    indices = pending,
    concurrency = concurrency,
    warmupRequired = warmupRequired,
    parse = { index ->
        parseChunkWithRetry(parser, provider, chunks[index], if (index == pending.first()) pushTail else null)
            .fold(
                onSuccess = { ChunkWorkResult(index, it) },
                onFailure = { ChunkWorkResult(index, null, it.message ?: "解析失败") }
            )
    },
    onResult = { result ->
        if (result.lines == null) {
            failedChunks += FailedChunk(result.index, ...)
        } else {
            persist lines through the single writer
        }
        update doneUnits from successful + failed completed count
        update message "AI 解析：已完成 X/Y 段"
    }
)
```

Only the warm-up chunk receives `pushTail`; all concurrent chunks receive `null`.

- [ ] **Step 3: Persist `parseReportJson`**

After `finalizeLines`:

```kotlin
val report = ParseReportBuilder.build(
    chunks = chunkList,
    rawText = text,
    lines = after,
    failedChunks = failedChunks
)
updateJob(jobId) {
    it.copy(
        stage = "DONE",
        parseReportJson = report.encode()
    )
}
```

- [ ] **Step 4: Render expandable report**

Add to `ImportScreen` PREVIEW page:

```kotlin
state.parseReport?.takeIf {
    it.failedChunks.isNotEmpty() || it.missingAnswers.isNotEmpty()
}?.let { report ->
    ParseReportCard(report)
}
```

`ParseReportCard`:

- collapsed summary row
- expandable groups by `chunkIndex`
- missing answer rows with pages/chapter/number/stem preview
- failed chunk rows with pages and reason

- [ ] **Step 5: Run unit tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*ParseReportTest" --tests "*ChunkParseSchedulerTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/examhelper/shua/data/importjob/ImportPipelineWorker.kt app/src/main/java/com/examhelper/shua/ui/import_/ImportViewModel.kt app/src/main/java/com/examhelper/shua/ui/import_/ImportScreen.kt app/src/test/java/com/examhelper/shua/ParseReportTest.kt
git commit -m "接入分段并发调度与解析问题页"
```

---

### Task 6: 当前学习轮次与最新作答选择

**Files:**
- Modify: `app/src/main/java/com/examhelper/shua/data/repo/StudyRepository.kt`
- Modify: `app/src/main/java/com/examhelper/shua/data/db/Daos.kt`
- Test: `app/src/test/java/com/examhelper/shua/StudyAnswerRestoreTest.kt`

**Interfaces:**
- Produces: `data class RoundAnswer(val userAnswer: String, val correct: Boolean?, val answeredAt: Long, val attemptId: Long)`
- Produces: `StudyRepository.latestAnswersByRound(runId: String): Map<Long, RoundAnswer>`
- Produces: `StudySession.roundId: String`

- [ ] **Step 1: Write failing latest-by-question test**

```kotlin
@Test
fun latestAttemptPerQuestionUsesTimeThenId() {
    val answers = listOf(
        AttemptRecord(id = 1, questionId = 7, bankId = 1, mode = StudyMode.SEQUENTIAL, userAnswer = "A", correct = false, runId = "r", answeredAt = 10),
        AttemptRecord(id = 2, questionId = 7, bankId = 1, mode = StudyMode.SEQUENTIAL, userAnswer = "B", correct = true, runId = "r", answeredAt = 10)
    )
    val latest = latestAnswerMap(answers)
    assertEquals("B", latest[7]?.userAnswer)
    assertEquals(2L, latest[7]?.attemptId)
}

@Test
fun answerWithoutReferenceKeepsUnknownResult() {
    val answer = latestAnswerMap(
        listOf(
            AttemptRecord(
                id = 3,
                questionId = 8,
                bankId = 1,
                mode = StudyMode.SEQUENTIAL,
                userAnswer = "C",
                correct = false,
                correctKnown = false,
                runId = "r",
                answeredAt = 20
            )
        )
    )[8]
    assertEquals("C", answer?.userAnswer)
    assertNull(answer?.correct)
}
```

- [ ] **Step 2: Run test and verify failure**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*StudyAnswerRestoreTest" --no-daemon
```

Expected: FAIL because selection helper does not exist.

- [ ] **Step 3: Add round id to ensureProgress**

```kotlin
val existing = progressDao.get(bankId, mode)
if (existing != null && existing.filterJson == filterJson) {
    if (existing.roundId == null) {
        val upgraded = existing.copy(
            roundId = UUID.randomUUID().toString(),
            updatedAt = System.currentTimeMillis()
        )
        progressDao.upsert(upgraded)
        return upgraded
    }
    return existing
}
val fresh = StudyProgress(
    bankId = bankId,
    mode = mode,
    filterJson = filterJson,
    roundId = UUID.randomUUID().toString(),
    randomOrderJson = if (mode == StudyMode.RANDOM) JsonCodec.encodeIdList(questionIds.shuffled()) else null
)
```

- [ ] **Step 4: Add restore query and selection helper**

Use existing `RecordDao.getByRun(runId)`. Add:

```kotlin
suspend fun latestAnswersByRound(runId: String): Map<Long, RoundAnswer> =
    latestAnswerMap(recordDao.getByRun(runId))

internal fun latestAnswerMap(records: List<AttemptRecord>): Map<Long, RoundAnswer> =
    records.groupBy { it.questionId }.mapValues { (_, rows) ->
        val latest = rows.maxWith(compareBy<AttemptRecord> { it.answeredAt }.thenBy { it.id })
        RoundAnswer(
            latest.userAnswer,
            latest.correct.takeIf { latest.correctKnown },
            latest.answeredAt,
            latest.id
        )
    }
```

- [ ] **Step 5: Run restore tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*StudyAnswerRestoreTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/examhelper/shua/data/repo/StudyRepository.kt app/src/main/java/com/examhelper/shua/data/db/Daos.kt app/src/test/java/com/examhelper/shua/StudyAnswerRestoreTest.kt
git commit -m "按学习轮次恢复最新作答"
```

---

### Task 7: StudyViewModel 实时持久化与恢复

**Files:**
- Modify: `app/src/main/java/com/examhelper/shua/ui/study/StudyViewModel.kt`
- Modify: `app/src/main/java/com/examhelper/shua/ui/study/StudyScreen.kt`
- Test: `app/src/test/java/com/examhelper/shua/StudyAnswerRestoreTest.kt`

**Interfaces:**
- Consumes: `StudySession.roundId`, `StudyRepository.latestAnswersByRound`
- Produces: no additional state field; `StudySession.roundId` is the single round identity.

- [ ] **Step 1: Add failing restore-state test**

```kotlin
@Test
fun restoredChoiceDecodesLettersAndCorrectness() {
    val restored = restoreStudyAnswer(
        questionType = QuestionType.MULTIPLE,
        answer = RoundAnswer("ABD", true, 1L, 9L)
    )
    assertEquals(setOf("A", "B", "D"), restored.selected)
    assertTrue(restored.answered)
    assertEquals(true, restored.correct)
}
```

- [ ] **Step 2: Run test and verify failure**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*StudyAnswerRestoreTest.restoredChoiceDecodesLettersAndCorrectness" --no-daemon
```

Expected: FAIL because restore helper does not exist.

- [ ] **Step 3: Persist every answer with roundId**

In `submitChoice` and `selfRate`:

```kotlin
studyRepo.submitAnswer(
    question = q,
    mode = mode,
    userAnswer = answerStr,
    correct = correct == true,
    correctKnown = correct != null,
    runId = s.session?.roundId,
    durationMs = duration
)
```

Keep immediate state update before launching the write.

- [ ] **Step 4: Restore on every reload**

```kotlin
val roundAnswersFromDb = session.roundId
    ?.let { studyRepo.latestAnswersByRound(it) }
    .orEmpty()
roundAnswers.clear()
roundAnswers.putAll(roundAnswersFromDb.mapValues { (_, a) -> a.userAnswer to a.correct })
```

Decode letters for SINGLE/MULTIPLE/JUDGE; restore text for FILL/SHORT_ANSWER/TERM.
When `correct == null`, restore `answered = true` but keep `correct = null`.

- [ ] **Step 5: Run restore and navigation tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*StudyAnswerRestoreTest" --tests "*StudyNavigationTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/examhelper/shua/ui/study/StudyViewModel.kt app/src/main/java/com/examhelper/shua/ui/study/StudyScreen.kt app/src/test/java/com/examhelper/shua/StudyAnswerRestoreTest.kt
git commit -m "实时保存并恢复当前学习轮次作答"
```

Before this commit, update `StudyRepository.submitAnswer` and `RecordDao.submitAnswer`:

```kotlin
suspend fun submitAnswer(
    question: Question,
    mode: StudyMode,
    userAnswer: String,
    correct: Boolean,
    correctKnown: Boolean = true,
    selfRated: Boolean = false,
    runId: String? = null,
    durationMs: Long? = null
)
```

`RecordDao.submitAnswer` creates a `WrongRecord` only when `record.correctKnown && !record.correct`. Update today/total accuracy and `statsByBank` queries to exclude `correctKnown = 0` from the denominator and correct numerator.

---

### Task 8: 解析连续性与底部控制动画

**Files:**
- Modify: `app/src/main/java/com/examhelper/shua/ui/study/StudyScreen.kt`
- Test: `app/src/test/java/com/examhelper/shua/StudyNavigationTest.kt`

**Interfaces:**
- Consumes: `answerMemo: SnapshotStateMap<Long, StudyUiState>`
- Produces: no public API; UI behavior only.

- [ ] **Step 1: Add failing answer-card visibility test**

Extract pure predicate:

```kotlin
internal fun shouldShowAnswerCard(
    interactive: Boolean,
    answered: Boolean,
    revealed: Boolean
): Boolean = revealed && (interactive || answered)

@Test
fun answeredNeighborKeepsAnswerCardVisible() {
    assertTrue(shouldShowAnswerCard(interactive = false, answered = true, revealed = true))
}

@Test
fun unansweredNeighborDoesNotShowAnswerCard() {
    assertFalse(shouldShowAnswerCard(interactive = false, answered = false, revealed = true))
}
```

- [ ] **Step 2: Run test and verify failure**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*StudyNavigationTest.answeredNeighborKeepsAnswerCardVisible" --no-daemon
```

Expected: FAIL because predicate does not exist.

- [ ] **Step 3: Capture snapshot before all flips**

Before `vm.next()`/`vm.prev()` in button and gesture paths:

```kotlin
session?.question?.let { current ->
    answerMemo[current.id] = state
}
```

Do this before the slide animation starts.

- [ ] **Step 4: Use the predicate in QuestionBody**

Replace the answer-card condition with:

```kotlin
if (shouldShowAnswerCard(interactive, state.answered, state.revealed)) {
    AnswerCard(q, state)
}
```

- [ ] **Step 5: Add text/height animations**

Use `AnimatedContent` for result row and button label. Add `animateContentSize()` to the bottom control `Column` and result container. Use stable targets:

```kotlin
targetState = when {
    state.answered && state.correct == true -> "correct"
    state.answered && state.correct == false -> "wrong"
    else -> "none"
}
```

Button:

```kotlin
targetState = when {
    state.session?.nextQuestion == null && (state.answered || state.revealed) -> "finish"
    state.answered || state.revealed -> "next"
    else -> "answer"
}
```

- [ ] **Step 6: Run navigation tests and compile**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*StudyNavigationTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/examhelper/shua/ui/study/StudyScreen.kt app/src/test/java/com/examhelper/shua/StudyNavigationTest.kt
git commit -m "保持已答题解析并补齐答题状态动画"
```

---

### Task 9: 章节题型页进度 Flow 刷新

**Files:**
- Modify: `app/src/main/java/com/examhelper/shua/data/db/Daos.kt`
- Modify: `app/src/main/java/com/examhelper/shua/data/repo/StudyRepository.kt`
- Modify: `app/src/main/java/com/examhelper/shua/ui/study/StudySetupViewModel.kt`
- Test: `app/src/test/java/com/examhelper/shua/StudySavedProgressFlowTest.kt`

**Interfaces:**
- Produces: `ProgressDao.observeAllByBank(bankId): Flow<List<StudyProgress>>`
- Produces: `StudyRepository.observeSavedProgress(bankId): Flow<List<SavedProgress>>`

- [ ] **Step 1: Write failing visibility test**

```kotlin
@Test
fun finishedProgressIsNotVisible() {
    val visible = filterVisibleProgress(
        rows = listOf(
            StudyProgress(1, StudyMode.SEQUENTIAL, currentIndex = 10, roundId = "r"),
            StudyProgress(1, StudyMode.SPEED, currentIndex = 2, roundId = "s")
        ),
        totalFor = { 10 }
    )
    assertEquals(listOf(StudyMode.SPEED), visible.map { it.mode })
}
```

- [ ] **Step 2: Run test and verify failure**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*StudySavedProgressFlowTest" --no-daemon
```

Expected: FAIL because helper/flow does not exist.

- [ ] **Step 3: Add DAO flow**

```kotlin
@Query("SELECT * FROM study_progress WHERE bankId = :bankId ORDER BY updatedAt DESC")
fun observeAllByBank(bankId: Long): Flow<List<StudyProgress>>
```

- [ ] **Step 4: Observe in StudySetupViewModel**

In `init`:

```kotlin
viewModelScope.launch {
    container.studyRepo.observeSavedProgress(bankId).collect { saved ->
        _state.value = _state.value.copy(saved = saved)
    }
}
```

Keep the initial bank/chapter setup load separate.

- [ ] **Step 5: Run saved-progress tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*StudySavedProgressFlowTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/examhelper/shua/data/db/Daos.kt app/src/main/java/com/examhelper/shua/data/repo/StudyRepository.kt app/src/main/java/com/examhelper/shua/ui/study/StudySetupViewModel.kt app/src/test/java/com/examhelper/shua/StudySavedProgressFlowTest.kt
git commit -m "实时刷新章节题型页学习进度"
```

---

### Task 10: 问 AI 完整消息持久化

**Files:**
- Modify: `app/src/main/java/com/examhelper/shua/data/db/Daos.kt`
- Modify: `app/src/main/java/com/examhelper/shua/data/repo/StudyRepository.kt`
- Modify: `app/src/main/java/com/examhelper/shua/ui/study/StudyViewModel.kt`
- Test: `app/src/test/java/com/examhelper/shua/QuestionChatPersistenceTest.kt`

**Interfaces:**
- Produces: `QuestionDao.updateAskChat(questionId: Long, json: String?)`
- Produces: `StudyRepository.saveChat(questionId: Long, turns: List<ChatTurn>)`
- Produces: `StudyRepository.loadChat(question: Question): List<ChatTurn>`

- [ ] **Step 1: Write failing persistence semantics test**

```kotlin
@Test
fun failedRequestKeepsUserAndDropsPartialAssistant() {
    val next = chatAfterFailure(
        existing = emptyList(),
        user = ChatTurn("user", "问题", "u1", 1L),
        partial = "半截"
    )
    assertEquals(listOf("u1"), next.map { it.id })
}
```

- [ ] **Step 2: Run test and verify failure**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*QuestionChatPersistenceTest.failedRequestKeepsUserAndDropsPartialAssistant" --no-daemon
```

Expected: FAIL because helper does not exist.

- [ ] **Step 3: Add DAO and repository methods**

```kotlin
@Query("UPDATE questions SET askChatJson = :json WHERE id = :questionId")
suspend fun updateAskChat(questionId: Long, json: String?)
```

Repository:

```kotlin
suspend fun saveChat(questionId: Long, turns: List<ChatTurn>) {
    questionDao.updateAskChat(
        questionId,
        turns.takeIf { it.isNotEmpty() }?.let(JsonCodec::encodeChat)
    )
}

fun loadChat(question: Question): List<ChatTurn> =
    JsonCodec.decodeChat(question.askChatJson)
```

- [ ] **Step 4: Persist user and completed assistant**

In `StudyViewModel.ask()`:

1. create `userTurn = ChatTurn("user", text)`
2. update state
3. `studyRepo.saveChat(q.id, state.chat + userTurn)`
4. stream into `askStream`
5. on success append `assistantTurn` and save
6. on failure keep user only and clear partial

`clearAsk()` saves an empty list.

- [ ] **Step 5: Load chat on reload and remove unconditional clears**

In `reload()`:

```kotlin
chat = q?.let(studyRepo::loadChat).orEmpty()
```

Remove `chat = emptyList()` from `QuestionBody.clean` and `stateFor` clean-state copies. For snapshot state, preserve the per-question chat.

- [ ] **Step 6: Run chat tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*QuestionChatPersistenceTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/examhelper/shua/data/db/Daos.kt app/src/main/java/com/examhelper/shua/data/repo/StudyRepository.kt app/src/main/java/com/examhelper/shua/ui/study/StudyViewModel.kt app/src/test/java/com/examhelper/shua/QuestionChatPersistenceTest.kt
git commit -m "持久化每道题问 AI 完整对话"
```

---

### Task 11: 问 AI Sheet 的 IME 与滚动动画

**Files:**
- Modify: `app/src/main/java/com/examhelper/shua/ui/study/AskAiPanel.kt`
- Modify: `app/src/main/java/com/examhelper/shua/ui/study/StudyScreen.kt`

**Interfaces:**
- Consumes: persisted `state.chat`, `state.asking`, `state.askStream`
- Produces: single-source sheet close behavior and IME layout.

- [ ] **Step 1: Add a pure scroll-decision helper and failing test**

```kotlin
internal fun shouldAnimateToNewMessage(
    oldCount: Int,
    newCount: Int,
    asking: Boolean
): Boolean = newCount > oldCount && !asking

@Test
fun streamingDoesNotStartRepeatedScrollAnimations() {
    assertFalse(shouldAnimateToNewMessage(2, 2, asking = true))
}
```

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*QuestionChatPersistenceTest.streamingDoesNotStartRepeatedScrollAnimations" --no-daemon
```

Expected: FAIL before helper exists.

- [ ] **Step 2: Remove duplicate IME inset**

In `StudyScreen.ModalBottomSheet`, configure one IME window inset source. In `AskAiPanel`, remove content-level `imePadding`; keep a single bottom spacing value derived from the Sheet content area.

- [ ] **Step 3: Anchor the input**

The root `Column` must fill the available sheet height. The chat area uses `weight(1f)`. Attach `BringIntoViewRequester` to the input row and request bring-into-view on focus.

- [ ] **Step 4: Replace token-driven scroll animations**

Use:

```kotlin
LaunchedEffect(state.chat.lastOrNull()?.id, state.asking) {
    if (state.chat.isNotEmpty()) {
        listState.animateScrollToItem(state.chat.lastIndex)
    }
}
LaunchedEffect(state.askStream.length / 80) {
    if (state.asking && state.askStream.isNotBlank()) {
        listState.scrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }
}
```

Do not key scrolling on every `askStream.length` character.

- [ ] **Step 5: Serialize close behavior**

Replace separate `askClosing` paths with one `closeAskSheet()` function using a guarded `Job`, so toolbar, scrim, and “关闭” share one hide transaction.

- [ ] **Step 6: Compile and run related tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "*QuestionChatPersistenceTest" --tests "*StudyNavigationTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/examhelper/shua/ui/study/AskAiPanel.kt app/src/main/java/com/examhelper/shua/ui/study/StudyScreen.kt app/src/test/java/com/examhelper/shua/QuestionChatPersistenceTest.kt
git commit -m "修复问 AI 键盘遮挡与滚动动画"
```

---

### Task 12: 集成验证、迁移验证与文档收尾

**Files:**
- Modify: `AGENTS.md`
- Modify: `MEMORY.md`

**Interfaces:**
- Consumes all previous tasks.
- Produces release-ready A/B/C implementation and handoff memory.

- [ ] **Step 1: Run full unit suite**

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
.\gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon
```

Expected: all tests pass; no failures/errors.

- [ ] **Step 2: Build release with R8**

```powershell
.\gradlew.bat assembleRelease --no-daemon
```

Expected: release APK generated under `app/build/outputs/apk/release/`.

- [ ] **Step 3: Install over the v16 device database**

```powershell
$adb='C:\Users\xutia\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s e87a3fe4 install -r app\build\outputs\apk\release\Shualegeti-arm64-v8a-1.2.0.apk
```

Expected: `Success`; existing banks, attempts, wrong records, providers remain.

- [ ] **Step 4: Real-device A verification**

- Import a document producing at least 4 chunks.
- Confirm warm-up result appears before the remaining chunks start.
- Confirm status shows `已完成 X/Y 段`.
- Include a question numbered `1000`; confirm parse and badge display.
- Confirm expandable report lists page/chapter/number/reason.

- [ ] **Step 5: Real-device B verification**

- Answer several questions across types.
- Switch away and back; confirm answers/result/analysis restore.
- Force-stop and reopen; confirm current round restores.
- Slide between an answered and unanswered question; confirm answered analysis never disappears.
- Complete a round, return to setup; confirm old progress disappears.

- [ ] **Step 6: Real-device C verification**

- Ask multiple AI questions on one question.
- Switch question and return; confirm complete history.
- Interrupt a streamed answer; confirm user message remains and partial answer is absent.
- Open keyboard in empty/short/long chat; confirm input remains visible.
- Confirm streaming scroll has no repeated bounce.

- [ ] **Step 7: Record verification and update memory**

In `AGENTS.md`, add:

- v17 schema and migration purpose.
- warm-up/worker-pool behavior.
- current-round restore contract.
- ask-chat persistence rule.

In `MEMORY.md`, record:

- files changed
- tests count/result
- release APK path/hash
- device migration result
- any remaining manual test

- [ ] **Step 8: Commit**

```powershell
git add AGENTS.md MEMORY.md
git commit -m "记录 A/B/C 导入做题与问 AI 改造"
```

