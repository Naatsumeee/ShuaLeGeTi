package com.examhelper.shua.ui.bank

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.llm.ChatMessage
import com.examhelper.shua.data.llm.LlmClient
import com.examhelper.shua.data.llm.Prompts
import com.examhelper.shua.ui.sound.Haptics
import com.examhelper.shua.ui.study.AskAiState
import com.examhelper.shua.ui.study.ChatTurn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 首页「问 AI」。
 *
 * 与刷题页的追问面板共用同一个 [com.examhelper.shua.ui.study.AskAiPanel]，
 * 区别只在于这里没有「当前题目」——是一问一答的通用助教，可以问概念、
 * 问复习方法、让模型解释一个看不懂的名词。
 *
 * 对话随面板关闭保留（用户可能只是收起来看看别处再回来），清空由用户主动点。
 */
class HomeAskViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as ShuaApp).container
    private val providerDao = container.database.providerDao()
    private val llmClient = LlmClient()

    private val _state = MutableStateFlow(AskAiState(placeholder = "问点什么…比如「药理学怎么背」"))
    val state: StateFlow<AskAiState> = _state

    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open

    /** 展开 / 收起面板 */
    fun toggle() {
        Haptics.tap()
        val next = !_open.value
        _open.value = next
        if (next && _state.value.askModelName == null) {
            viewModelScope.launch {
                val name = runCatching {
                    val p = resolveProvider()
                    "${p.name} · ${p.model}"
                }.getOrNull()
                if (_state.value.askModelName == null) {
                    _state.value = _state.value.copy(askModelName = name)
                }
            }
        }
    }

    fun ask(userText: String) {
        val text = userText.trim()
        if (text.isEmpty() || _state.value.asking) return
        val history = _state.value.chat + ChatTurn("user", text)
        _state.value = _state.value.copy(chat = history, asking = true, askError = null, askStream = "")
        Haptics.tap()

        viewModelScope.launch {
            val buf = StringBuilder()
            var lastPush = 0L
            fun push(force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastPush < 40L) return
                lastPush = now
                _state.value = _state.value.copy(askStream = buf.toString())
            }
            runCatching {
                val provider = resolveProvider()
                val messages = buildList {
                    add(ChatMessage("system", Prompts.ASK_DEFAULT))
                    history.forEach { add(ChatMessage(it.role, it.text)) }
                }
                llmClient.chatStreamMessages(
                    provider = provider,
                    messages = messages,
                    jsonMode = false,
                    temperature = 0.5,
                    maxTokens = 1500,
                    onDelta = { delta ->
                        buf.append(delta)
                        push()
                    }
                ).content.trim()
            }.fold(
                onSuccess = { reply ->
                    _state.value = _state.value.copy(
                        chat = _state.value.chat + ChatTurn("assistant", reply),
                        asking = false,
                        askError = null,
                        askStream = ""
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        asking = false,
                        askError = e.message?.take(140) ?: "提问失败，请重试",
                        askStream = ""
                    )
                }
            )
        }
    }

    fun clear() {
        Haptics.tap()
        _state.value = _state.value.copy(chat = emptyList(), askError = null)
    }

    private suspend fun resolveProvider(): com.examhelper.shua.data.db.LlmProvider =
        providerDao.getPrimary()
            ?: providerDao.observeAll().first().firstOrNull { it.enabled }
            ?: error("尚未配置 AI 模型，请到设置页添加")
}
