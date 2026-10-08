// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the two guards in front of a memory write.
 *
 * The contradiction rules are deliberately conservative — a false positive
 * makes the agent interrogate the user about a non-conflict, which is worse
 * than missing one — so the "should NOT fire" cases matter as much as the
 * "should fire" ones.
 */
class MemorySimilarityTest {

    // ── Contradictions: these must fire ───────────────────────────────────────

    @Test
    fun `same sentence different number is a conflict`() {
        assertTrue(
            MemorySimilarity.contradicts(
                "用户每周跑3次，每次5公里。",
                "用户每周跑5次，每次10公里。"
            )
        )
    }

    @Test
    fun `swapped value in a shared frame is a conflict`() {
        assertTrue(
            MemorySimilarity.contradicts("用户目前在杭州出差。", "用户目前在上海出差。")
        )
    }

    @Test
    fun `polarity flip is a conflict`() {
        assertTrue(
            MemorySimilarity.contradicts("用户喜欢用微信聊天。", "用户不喜欢用微信聊天。")
        )
    }

    @Test
    fun `latin model name swap is a conflict`() {
        assertTrue(
            MemorySimilarity.contradicts("用户的手机是 Pixel。", "用户的手机是小米。")
        )
    }

    @Test
    fun `an english sentence with a swapped number is a conflict`() {
        // Regression: latin words used to be stripped as values, which left the
        // skeleton empty and silently disabled the whole check in English.
        assertTrue(
            MemorySimilarity.contradicts(
                "The user drinks 3 coffees daily.",
                "The user drinks 7 coffees daily"
            )
        )
    }

    @Test
    fun `an english sentence with a swapped value at the end is a conflict`() {
        assertTrue(
            MemorySimilarity.contradicts("user uses Pixel", "user uses iPhone")
        )
    }

    @Test
    fun `an english sentence with a changed number and different words is not a conflict`() {
        assertFalse(
            MemorySimilarity.contradicts(
                "The user drinks 3 coffees daily.",
                "The user set an alarm for 7 minutes."
            )
        )
    }

    // ── Contradictions: these must NOT fire ───────────────────────────────────

    @Test
    fun `unrelated memories are not a conflict`() {
        assertFalse(
            MemorySimilarity.contradicts(
                "用户是示例大学的学生。",
                "PokeClaw 的记忆库存放在外部目录。"
            )
        )
    }

    @Test
    fun `an extended sentence is not a conflict`() {
        // "用微信聊天" vs "用微信和QQ聊天" — the second extends the first
        assertFalse(
            MemorySimilarity.contradicts(
                "用户喜欢用微信聊天。",
                "用户喜欢用微信和QQ聊天。"
            )
        )
    }

    @Test
    fun `restating the same fact is not a conflict`() {
        assertFalse(
            MemorySimilarity.contradicts(
                "用户每周跑3次。",
                "用户每周跑3次左右。"
            )
        )
    }

    @Test
    fun `different facts about the same subject are not a conflict`() {
        assertFalse(
            MemorySimilarity.contradicts(
                "用户的手机是 Pixel。",
                "用户的手机电量一般能用一天。"
            )
        )
    }

    @Test
    fun `a short shared prefix alone is not a conflict`() {
        assertFalse(
            MemorySimilarity.contradicts("用户喜欢咖啡。", "用户讨厌咖啡因饮料。")
        )
    }

    // ── Similarity (suppression fingerprints) ────────────────────────────────

    @Test
    fun `similarity is one for identical text`() {
        assertEquals(1.0, MemorySimilarity.similarity("用户喜欢中文界面。", "用户喜欢中文界面。"), 0.001)
    }

    @Test
    fun `similarity is zero for unrelated text`() {
        assertEquals(0.0, MemorySimilarity.similarity("用户喜欢中文界面。", "每周三有实验课。"), 0.001)
    }

    @Test
    fun `a lightly reworded memory is similar enough to be suppressed`() {
        val forgotten = "用户不喜欢在晚上八点以后收到工作消息。"
        val rephrased = "用户不喜欢晚上八点以后收到工作消息。"
        assertTrue(
            MemorySimilarity.similarity(forgotten, rephrased) >=
                MemorySimilarity.SUPPRESS_THRESHOLD
        )
    }

    @Test
    fun `a full paraphrase escapes suppression - the known lexical limit`() {
        // Bigram matching cannot see that these say the same thing. Documented
        // as a limitation rather than papered over: raising the threshold would
        // cost more in over-suppression than it buys.
        val forgotten = "用户不喜欢在晚上八点以后收到工作消息。"
        val paraphrase = "用户不希望被工作上的事打扰，尤其是很晚的时候。"
        assertTrue(
            MemorySimilarity.similarity(forgotten, paraphrase) <
                MemorySimilarity.SUPPRESS_THRESHOLD
        )
    }

    @Test
    fun `a different memory stays below the suppression threshold`() {
        val forgotten = "用户不喜欢在晚上八点以后收到工作消息。"
        val unrelated = "用户的导师姓李，研究方向是动物药理学。"
        assertTrue(
            MemorySimilarity.similarity(forgotten, unrelated) <
                MemorySimilarity.SUPPRESS_THRESHOLD
        )
    }

    @Test
    fun `very short strings do not spuriously match`() {
        assertEquals(0.0, MemorySimilarity.similarity("喝水", "吃饭"), 0.001)
    }

    // ── Trace parsing ─────────────────────────────────────────────────────────

    @Test
    fun `suppression traces survive a hand-edited file`() {
        val json = """
            [
              { "id": "abc", "text": "用户不用微信支付", "at": 100, "blocked": 2 },
              { "text": "缺少 id 的也要能读" },
              { "id": "x" }
            ]
        """.trimIndent()
        val traces = MemorySimilarity.Traces.parse(json)
        assertEquals(2, traces.size)
        assertEquals(2, traces[0].blocked)
        assertTrue(traces[1].id.isNotEmpty())
    }

    @Test
    fun `a corrupt suppression file yields no traces instead of throwing`() {
        assertTrue(MemorySimilarity.Traces.parse("not json at all").isEmpty())
        assertTrue(MemorySimilarity.Traces.parse("").isEmpty())
        assertTrue(MemorySimilarity.Traces.parse("{}").isEmpty())
    }
}