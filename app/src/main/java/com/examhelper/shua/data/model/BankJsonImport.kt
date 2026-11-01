package com.examhelper.shua.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 宽容解析「让 Agent 生成」的题库 JSON。
 *
 * 提示词已经尽量把格式写死，但真实世界里 Agent 的输出永远带着自己的习惯：
 * 包一层 ```json 代码块、顶层直接给数组、字段名写成中文、选项写成
 * `["A. 甲","B. 乙"]` 而不是对象数组、多选题答案写成 `["A","B"]`……
 * 这些用户完全无从判断「为什么导不进去」，所以这里尽量猜，猜不出来才报错。
 *
 * 与 [ShuaBankFile] 的严格反序列化分开：那条路是给「本 App 自己导出的文件」用的，
 * 结构必然正确；这里是给「外部生成的文件」用的，必须宽容。
 */
object BankJsonImport {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    class ParseException(message: String) : Exception(message)

    /** 中文/英文题型名 → 内部枚举名 */
    private val typeAliases: Map<String, String> = mapOf(
        "单选" to "SINGLE", "单选题" to "SINGLE", "单项选择" to "SINGLE",
        "单项选择题" to "SINGLE", "single" to "SINGLE", "radio" to "SINGLE",
        "多选" to "MULTIPLE", "多选题" to "MULTIPLE", "多项选择" to "MULTIPLE",
        "多项选择题" to "MULTIPLE", "multiple" to "MULTIPLE", "checkbox" to "MULTIPLE",
        "判断" to "JUDGE", "判断题" to "JUDGE", "judge" to "JUDGE",
        "truefalse" to "JUDGE", "boolean" to "JUDGE",
        "填空" to "FILL", "填空题" to "FILL", "fill" to "FILL", "blank" to "FILL",
        "简答" to "SHORT_ANSWER", "简答题" to "SHORT_ANSWER", "问答" to "SHORT_ANSWER",
        "问答题" to "SHORT_ANSWER", "shortanswer" to "SHORT_ANSWER", "essay" to "SHORT_ANSWER",
        "名词解释" to "TERM", "名词解释题" to "TERM", "术语解释" to "TERM", "term" to "TERM"
    )

    /** 题型推断：没写 type 时按有没有选项、答案像不像判断来猜 */
    private fun inferType(options: List<QuestionOption>, answer: String?): String = when {
        options.isEmpty() && !answer.isNullOrBlank() && answer.contains("___") -> "FILL"
        options.isEmpty() -> "SHORT_ANSWER"
        options.size == 2 && options.map { it.text.trim() }
            .let { it[0].startsWith("对") && it[1].startsWith("错") } -> "JUDGE"
        !answer.isNullOrBlank() && answer.filter { it.isLetter() }.length > 1 -> "MULTIPLE"
        else -> "SINGLE"
    }

    private fun normalizeType(raw: String?, options: List<QuestionOption>, answer: String?): String {
        val key = raw?.trim()?.lowercase()?.replace(" ", "")?.replace("型", "") ?: ""
        if (key.isNotEmpty()) {
            typeAliases[key]?.let { return it }
            // 已经是枚举名（SINGLE/MULTIPLE/...）
            val upper = key.uppercase()
            if (upper in setOf("SINGLE", "MULTIPLE", "JUDGE", "FILL", "SHORT_ANSWER", "TERM")) {
                return upper
            }
            // 形如 "single_choice" / "multiple-choice"
            val compact = upper.replace("_", "").replace("-", "")
            when {
                compact.startsWith("SINGLE") || compact.startsWith("RADIO") -> return "SINGLE"
                compact.startsWith("MULTIPLE") || compact.startsWith("CHECKBOX") -> return "MULTIPLE"
                compact.startsWith("JUDGE") || compact.startsWith("TRUEFALSE") -> return "JUDGE"
                compact.startsWith("FILL") || compact.startsWith("BLANK") -> return "FILL"
                compact.startsWith("TERM") || compact.startsWith("GLOSSARY") -> return "TERM"
                compact.startsWith("SHORT") || compact.startsWith("ESSAY") -> return "SHORT_ANSWER"
            }
        }
        return inferType(options, answer)
    }

    private fun JsonElement?.asTextOrNull(): String? = when (this) {
        null -> null
        is JsonPrimitive -> if (this.isString) content else content
        else -> null
    }

    private fun JsonElement?.asIntOrNull(): Int? {
        val p = this as? JsonPrimitive ?: return null
        return p.content.trim().toIntOrNull()
    }

    private fun JsonElement?.asBoolOrNull(): Boolean? =
        (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }

    /**
     * 选项的四种常见写法都要能吃：
     * 1) `[{"key":"A","text":"甲"}]` —— 本 App 原生格式
     * 2) `["A. 甲","B. 乙"]` —— 最简单，Agent 最爱
     * 3) `{"A":"甲","B":"乙"}` —— 对象映射
     * 4) `[{"A":"甲"}]` / `[{"label":"A","content":"甲"}]` —— 变体
     */
    private fun parseOptions(el: JsonElement?): MutableList<QuestionOption> {
        val out = mutableListOf<QuestionOption>()
        when (el) {
            null -> Unit
            is JsonArray -> el.forEach { item ->
                when (item) {
                    is JsonPrimitive -> {
                        val raw = item.content.trim()
                        out += splitKeyedOption(raw, ('A' + out.size))
                    }
                    is JsonObject -> {
                        val key = item["key"].asTextOrNull()
                            ?: item["label"].asTextOrNull()
                            ?: item["letter"].asTextOrNull()
                            ?: item.keys.firstOrNull { it.length == 1 }
                                ?.let { item[it].asTextOrNull()?.let { _ -> it } }
                        val text = item["text"].asTextOrNull()
                            ?: item["content"].asTextOrNull()
                            ?: item["value"].asTextOrNull()
                            ?: item["option"].asTextOrNull()
                            ?: item.entries.firstOrNull { it.key.length > 1 }?.value.asTextOrNull()
                        if (text != null) {
                            val k = key?.trim()?.uppercase()?.takeIf { it.length <= 2 }
                                ?: ('A' + out.size).toString()
                            out += QuestionOption(k, text.trim())
                        }
                    }
                    else -> Unit
                }
            }
            is JsonObject -> el.forEach { (k, v) ->
                val text = v.asTextOrNull() ?: return@forEach
                out += QuestionOption(k.trim().uppercase().take(2), text.trim())
            }
            else -> Unit
        }
        return out
    }

    /** "A. 内容" / "A、内容" / "A) 内容" / 纯内容 → (key, text) */
    private fun splitKeyedOption(raw: String, fallbackKey: Char): QuestionOption {
        val m = Regex("""^\s*([A-Za-z])\s*[.、．)）:：]\s*(.+)$""").find(raw)
        return if (m != null) {
            QuestionOption(m.groupValues[1].uppercase(), m.groupValues[2].trim())
        } else {
            QuestionOption(fallbackKey.toString(), raw.trim())
        }
    }

    /** 答案的三种写法：字符串 / 字母数组 / 单个对象 */
    private fun parseAnswer(el: JsonElement?): String? = when (el) {
        null -> null
        is JsonPrimitive -> el.content.trim().takeIf { it.isNotEmpty() }
        is JsonArray -> el.mapNotNull { it.asTextOrNull()?.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("")
            .takeIf { it.isNotEmpty() }
        is JsonObject -> el["answer"].asTextOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        else -> null
    }

    private fun parseQuestion(el: JsonElement): ShuaQuestionJson? {
        val obj = el as? JsonObject ?: return null
        val stem = obj["stem"].asTextOrNull()
            ?: obj["question"].asTextOrNull()
            ?: obj["content"].asTextOrNull()
            ?: obj["title"].asTextOrNull()
            ?: obj["题干"].asTextOrNull()
            ?: return null
        if (stem.isBlank()) return null

        val options = parseOptions(
            obj["options"] ?: obj["option"] ?: obj["choices"] ?: obj["选项"]
        )
        val answer = parseAnswer(
            obj["answer"] ?: obj["answers"] ?: obj["correct"] ?: obj["key"] ?: obj["答案"]
        )
        val typeRaw = obj["type"].asTextOrNull()
            ?: obj["questionType"].asTextOrNull()
            ?: obj["题型"].asTextOrNull()

        return ShuaQuestionJson(
            chapter = obj["chapter"].asTextOrNull()
                ?: obj["section"].asTextOrNull()
                ?: obj["章节"].asTextOrNull(),
            type = normalizeType(typeRaw, options, answer),
            subtype = obj["subtype"].asTextOrNull() ?: obj["subType"].asTextOrNull(),
            stem = stem.trim(),
            sharedStem = obj["sharedStem"].asTextOrNull()
                ?: obj["shared_stem"].asTextOrNull()
                ?: obj["material"].asTextOrNull()
                ?: obj["共用题干"].asTextOrNull(),
            sharedOptions = parseOptions(obj["sharedOptions"] ?: obj["共用选项"]),
            options = options,
            answer = answer,
            analysis = obj["analysis"].asTextOrNull()
                ?: obj["explanation"].asTextOrNull()
                ?: obj["解析"].asTextOrNull(),
            sourcePage = obj["sourcePage"].asIntOrNull() ?: obj["page"].asIntOrNull(),
            aiGenerated = obj["aiGenerated"].asBoolOrNull() ?: false,
            confidence = obj["confidence"].asIntOrNull(),
            agreement = obj["agreement"].asTextOrNull()
        )
    }

    /**
     * 从任意文本里抽出题库。
     *
     * @throws ParseException 完全找不到题目时抛出，消息直接面向用户。
     */
    fun parse(raw: String, fallbackName: String? = null): ShuaBankFile {
        val text = raw.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (text.isEmpty()) throw ParseException("文件是空的")

        val start = text.indexOfFirst { it == '{' || it == '[' }
        val end = maxOf(text.lastIndexOf('}'), text.lastIndexOf(']'))
        if (start < 0 || end <= start) {
            throw ParseException("没有找到 JSON 内容。请确认让 AI 输出的是 JSON 而不是纯文字。")
        }
        val body = text.substring(start, end + 1)
        val root = runCatching { json.parseToJsonElement(body) }
            .getOrElse {
                throw ParseException("JSON 格式有误（${it.message?.take(60)}）。可以让 AI 重新输出一次。")
            }

        // 顶层可能是：对象（含 questions）/ 直接是题目数组 / 单个题目对象
        val questionsEl: JsonArray = when {
            root is JsonArray -> root
            root is JsonObject -> {
                val arr = root["questions"] ?: root["items"] ?: root["data"] ?: root["题目"]
                when {
                    arr is JsonArray -> arr
                    arr is JsonObject -> (arr["questions"] as? JsonArray) ?: JsonArray(emptyList())
                    root.containsKey("stem") || root.containsKey("question") || root.containsKey("题干") ->
                        JsonArray(listOf(root))
                    else -> JsonArray(emptyList())
                }
            }
            else -> JsonArray(emptyList())
        }

        val questions = questionsEl.mapNotNull { parseQuestion(it) }
        if (questions.isEmpty()) {
            throw ParseException("JSON 里没有解析出任何题目。请检查题目字段是否包含题干（stem / question / 题干）。")
        }

        val name = (root as? JsonObject)?.get("name").asTextOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: fallbackName?.trim()?.takeIf { it.isNotEmpty() }
            ?: "AI 生成题库"

        val exportedAt = (root as? JsonObject)?.get("exportedAt").asTextOrNull()
            ?.toLongOrNull() ?: System.currentTimeMillis()

        return ShuaBankFile(
            version = (root as? JsonObject)?.get("version").asIntOrNull() ?: 3,
            name = name,
            sourceFileName = (root as? JsonObject)?.get("sourceFileName").asTextOrNull(),
            exportedAt = exportedAt,
            includeAiGenerated = true,
            questions = questions
        )
    }
}
