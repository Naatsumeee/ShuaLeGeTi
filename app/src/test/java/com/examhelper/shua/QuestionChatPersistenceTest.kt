package com.examhelper.shua

import com.examhelper.shua.data.model.ChatTurn
import com.examhelper.shua.data.model.JsonCodec
import org.junit.Assert.assertEquals
import org.junit.Test

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
