package com.examhelper.shua

import com.examhelper.shua.data.model.BankJsonImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宽容 JSON 题库解析：Agent 输出的各种「不标准但合理」写法都要能吃下来。
 */
class BankJsonImportTest {

    @Test
    fun strictAppExportStillWorks() {
        val raw = """
            {
              "version": 3,
              "name": "药理学",
              "exportedAt": 1730000000000,
              "questions": [
                {"type":"SINGLE","stem":"阿莫西林属于哪一类？",
                 "options":[{"key":"A","text":"青霉素类"},{"key":"B","text":"头孢类"}],
                 "answer":"A","analysis":"β-内酰胺类抗生素","chapter":"第三章 抗菌药物"}
              ]
            }
        """.trimIndent()
        val bank = BankJsonImport.parse(raw)
        assertEquals("药理学", bank.name)
        assertEquals(1, bank.questions.size)
        val q = bank.questions.first()
        assertEquals("SINGLE", q.type)
        assertEquals("A", q.answer)
        assertEquals("第三章 抗菌药物", q.chapter)
        assertEquals(2, q.options.size)
        assertEquals("青霉素类", q.options[0].text)
    }

    @Test
    fun codeFenceAndPreambleAreStripped() {
        val raw = """
            ```json
            {"name":"测试","questions":[{"stem":"1+1=?","type":"SINGLE","answer":"B"}]}
            ```
        """.trimIndent()
        val bank = BankJsonImport.parse(raw)
        assertEquals("测试", bank.name)
        assertEquals(1, bank.questions.size)
    }

    @Test
    fun topLevelArrayIsAccepted() {
        val raw = """[{"stem":"第一题","type":"JUDGE","answer":"A"}]"""
        val bank = BankJsonImport.parse(raw, fallbackName = "临时题库")
        assertEquals("临时题库", bank.name)
        assertEquals(1, bank.questions.size)
        assertEquals("JUDGE", bank.questions.first().type)
    }

    @Test
    fun stringOptionsWithPrefixesAreParsed() {
        // Agent 最爱写 ["A. 甲","B. 乙"] 这种
        val raw = """
            {"questions":[{"stem":"选一个","answer":"B",
              "options":["A. 甲","B. 乙","C. 丙","D. 丁"]}]}
        """.trimIndent()
        val q = BankJsonImport.parse(raw).questions.first()
        assertEquals(4, q.options.size)
        assertEquals("A", q.options[0].key)
        assertEquals("乙", q.options[1].text)
        // 没写 type 时按多选项 + 单字母答案推断成单选
        assertEquals("SINGLE", q.type)
    }

    @Test
    fun optionsAsObjectMapAreParsed() {
        val raw = """
            {"questions":[{"stem":"选一个","answer":"A","options":{"A":"甲","B":"乙"}}]}
        """.trimIndent()
        val q = BankJsonImport.parse(raw).questions.first()
        assertEquals(2, q.options.size)
        assertEquals("乙", q.options[1].text)
    }

    @Test
    fun arrayAnswerIsJoined() {
        val raw = """
            {"questions":[{"stem":"多选","type":"MULTIPLE","answer":["A","C"],
              "options":[{"key":"A","text":"甲"},{"key":"B","text":"乙"},{"key":"C","text":"丙"}]}]}
        """.trimIndent()
        val q = BankJsonImport.parse(raw).questions.first()
        assertEquals("AC", q.answer)
    }

    @Test
    fun chineseFieldNamesAreAccepted() {
        val raw = """
            {"name":"中文题目","questions":[
              {"题干":"以下哪个是抗生素？","题型":"单选题","答案":"A",
               "选项":[{"key":"A","text":"青霉素"},{"key":"B","text":"阿司匹林"}]}
            ]}
        """.trimIndent()
        val bank = BankJsonImport.parse(raw)
        assertEquals(1, bank.questions.size)
        assertEquals("以下哪个是抗生素？", bank.questions.first().stem)
        assertEquals("SINGLE", bank.questions.first().type)
        assertEquals(2, bank.questions.first().options.size)
    }

    @Test
    fun judgeQuestionTypeIsInferredFromTrueFalseOptions() {
        val raw = """
            {"questions":[{"stem":"青霉素需要皮试。","answer":"A",
              "options":[{"key":"A","text":"对"},{"key":"B","text":"错"}]}]}
        """.trimIndent()
        assertEquals("JUDGE", BankJsonImport.parse(raw).questions.first().type)
    }

    @Test
    fun missingTypeWithNoOptionsBecomesShortAnswer() {
        val raw = """{"questions":[{"stem":"简述首过效应。","answer":"药物经肝代谢后生物利用度下降"}]}"""
        val q = BankJsonImport.parse(raw).questions.first()
        assertEquals("SHORT_ANSWER", q.type)
        assertTrue(q.options.isEmpty())
    }

    @Test
    fun singleQuestionObjectIsAccepted() {
        val raw = """{"stem":"孤零零一题","type":"TERM","answer":"名词解释内容"}"""
        val bank = BankJsonImport.parse(raw)
        assertEquals(1, bank.questions.size)
        assertEquals("TERM", bank.questions.first().type)
    }

    @Test
    fun dataWrapperIsUnwrapped() {
        val raw = """{"data":{"questions":[{"stem":"包在 data 里","type":"SINGLE"}]}}"""
        assertEquals(1, BankJsonImport.parse(raw).questions.size)
    }

    @Test
    fun questionWithoutStemIsDropped() {
        val raw = """
            {"questions":[
              {"stem":"有效题目","type":"SINGLE"},
              {"type":"SINGLE","answer":"A"},
              {"stem":"   ","type":"SINGLE"}
            ]}
        """.trimIndent()
        val bank = BankJsonImport.parse(raw)
        assertEquals(1, bank.questions.size)
        assertEquals("有效题目", bank.questions.first().stem)
    }

    @Test
    fun latexAndQuotesInsideStringsSurvive() {
        val raw = """
            {"questions":[{"stem":"求 ${'$'}x^2+1${'$'} 的最小值，注意 \"引号\" 与换行\n第二行",
              "type":"SHORT_ANSWER"}]}
        """.trimIndent()
        val q = BankJsonImport.parse(raw).questions.first()
        assertTrue(q.stem.contains("x^2+1"))
        assertTrue(q.stem.contains("\"引号\""))
        assertTrue(q.stem.contains("\n"))
    }

    @Test
    fun emptyQuestionsThrowsReadableMessage() {
        val raw = """{"name":"空","questions":[]}"""
        val err = runCatching { BankJsonImport.parse(raw) }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err is BankJsonImport.ParseException)
        assertTrue(err!!.message!!.contains("没有解析出任何题目"))
    }

    @Test
    fun nonJsonThrowsReadableMessage() {
        val err = runCatching { BankJsonImport.parse("这只是一段普通文字，并不是 JSON") }
            .exceptionOrNull()
        assertTrue(err is BankJsonImport.ParseException)
        assertTrue(err!!.message!!.contains("没有找到 JSON"))
    }

    @Test
    fun oldLearningDataJsonIsRejectedAsBank() {
        val raw = """
            {
              "schema": "shualegeti.export.v1",
              "attempts": [],
              "wrongBook": [],
              "favorites": [],
              "kills": []
            }
        """.trimIndent()
        val err = runCatching { BankJsonImport.parse(raw) }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err is BankJsonImport.ParseException)
        assertTrue(err!!.message!!.contains("旧版学习数据文件"))
    }}
