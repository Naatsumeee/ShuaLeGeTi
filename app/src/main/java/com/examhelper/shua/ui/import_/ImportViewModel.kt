package com.examhelper.shua.ui.import_

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.ingest.DocumentIngestor
import com.examhelper.shua.data.ingest.IngestProgress
import com.examhelper.shua.data.parse.LlmQuestionParser
import com.examhelper.shua.data.parse.TextQuestionParser
import com.examhelper.shua.data.model.ParsedQuestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImportViewModel(application: Application) : AndroidViewModel(application) {

    enum class Stage { IDLE, INGESTING, TEXT_READY, PARSING, PREVIEW, SAVING }

    data class UiState(
        val stage: Stage = Stage.IDLE,
        val rawText: String = "",
        val sourceName: String = "",
        val progress: IngestProgress? = null,
        val parsed: List<ParsedQuestion>? = null,
        val bankName: String = "",
        val error: String? = null,
        val aiInfo: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val container = (application as ShuaApp).container
    private val ingestor = DocumentIngestor(application)
    private val bankRepo = container.bankRepo
    private val providerDao = container.database.providerDao()

    fun updateBankName(v: String) {
        _state.value = _state.value.copy(bankName = v)
    }

    fun updateRawText(v: String) {
        _state.value = _state.value.copy(rawText = v)
    }

    /** 文件 → 文本 */
    fun ingest(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(stage = Stage.INGESTING, error = null, parsed = null)
            val result = withContext(Dispatchers.IO) {
                runCatching { ingestor.ingest(uri) { p -> _state.value = _state.value.copy(progress = p) } }
            }
            result.fold(
                onSuccess = { text ->
                    val name = _state.value.bankName.ifBlank { defaultBankName(_state.value.sourceName) }
                    _state.value = _state.value.copy(
                        stage = Stage.TEXT_READY,
                        rawText = text,
                        bankName = name
                    )
                },
                onFailure = {
                    _state.value = _state.value.copy(stage = Stage.IDLE, error = "读取失败：${it.message}")
                }
            )
        }
    }

    fun setSourceName(name: String) {
        _state.value = _state.value.copy(sourceName = name)
    }

    /** 导入标准题库 JSON 文件（本 App 导出格式） */
    fun importBankFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText() ?: error("无法读取文件")
                com.examhelper.shua.data.model.JsonCodec.json
                    .decodeFromString<com.examhelper.shua.data.model.ShuaBankFile>(text)
            }.fold(
                onSuccess = { file ->
                    val parsed = file.questions.map { jq ->
                        ParsedQuestion(
                            chapter = jq.chapter,
                            type = runCatching { com.examhelper.shua.data.db.QuestionType.valueOf(jq.type) }
                                .getOrElse { com.examhelper.shua.data.db.QuestionType.SINGLE },
                            stem = jq.stem,
                            options = jq.options.toMutableList(),
                            answer = jq.answer,
                            analysis = jq.analysis
                        )
                    }
                    _state.value = _state.value.copy(
                        stage = Stage.PREVIEW,
                        parsed = parsed,
                        bankName = file.name,
                        sourceName = file.sourceFileName ?: "标准题库文件",
                        aiInfo = "从标准题库文件导入 ${parsed.size} 题"
                    )
                },
                onFailure = {
                    _state.value = _state.value.copy(error = "题库文件解析失败：${it.message}")
                }
            )
        }
    }

    /** 离线正则解析 */
    fun parseOffline() {
        val text = _state.value.rawText
        if (text.isBlank()) return
        val parsed = TextQuestionParser.parse(text)
        _state.value = _state.value.copy(
            stage = Stage.PREVIEW,
            parsed = parsed,
            error = if (parsed.isEmpty()) "离线解析没有识别出题目，建议使用 AI 解析" else null
        )
    }

    /** AI 结构化解析 */
    fun parseAi() {
        val text = _state.value.rawText
        if (text.isBlank()) return
        viewModelScope.launch {
            val provider = withContext(Dispatchers.IO) {
                providerDao.getPrimary() ?: providerDao.observeAll().first().firstOrNull { it.enabled }
            }
            if (provider == null) {
                _state.value = _state.value.copy(error = "尚未配置大模型，请到\"设置\"添加后再用 AI 解析")
                return@launch
            }
            _state.value = _state.value.copy(
                stage = Stage.PARSING,
                error = null,
                aiInfo = "使用模型：${provider.name}"
            )
            val parser = LlmQuestionParser(container.llmClient)
            val result = runCatching {
                parser.parse(provider, text) { done, total ->
                    _state.value = _state.value.copy(
                        progress = IngestProgress("parse", done, total, "AI 解析第 $done/$total 段")
                    )
                }
            }
            result.fold(
                onSuccess = { parsed ->
                    _state.value = _state.value.copy(
                        stage = Stage.PREVIEW,
                        parsed = parsed,
                        aiInfo = "AI 解析完成：${parsed.size} 题 · 模型 ${provider.name}",
                        error = if (parsed.isEmpty()) "AI 没有解析出题目，请检查文本" else null
                    )
                },
                onFailure = {
                    _state.value = _state.value.copy(
                        stage = Stage.TEXT_READY,
                        error = "AI 解析失败：${it.message?.take(150)}"
                    )
                }
            )
        }
    }

    fun backToText() {
        _state.value = _state.value.copy(stage = Stage.TEXT_READY, parsed = null)
    }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        val parsed = s.parsed ?: return
        if (parsed.isEmpty() || s.stage == Stage.SAVING) return
        viewModelScope.launch {
            _state.value = s.copy(stage = Stage.SAVING)
            runCatching { bankRepo.createBank(s.bankName.ifBlank { "导入题库" }, s.sourceName.ifBlank { null }, parsed) }
                .onSuccess {
                    _state.value = _state.value.copy(stage = Stage.PREVIEW)
                    onDone()
                }
                .onFailure {
                    _state.value = _state.value.copy(stage = Stage.PREVIEW, error = "导入失败：${it.message}")
                }
        }
    }

    private fun defaultBankName(sourceName: String): String =
        sourceName.substringBeforeLast('.')?.ifBlank { null } ?: "导入题库"
}
