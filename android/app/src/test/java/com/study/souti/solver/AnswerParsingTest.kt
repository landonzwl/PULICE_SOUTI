package com.study.souti.solver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class AnswerParsingTest {
    @Test
    fun keepsAnswersUpToHAndDropsInvalidItems() {
        val answers = parseAnswers(
            """
            ```json
            [
              {"no":"2","stem":"在?处答案为","answer":"f"},
              {"no":"3","stem":"某校辩论队","answer":"D"},
              {"no":"4","stem":"国产灯","answer":"Z"},
              {"no":"5","stem":"多选","answer":"AB"},
              {"no":"6","stem":"缺答案"}
            ]
            ```
            """.trimIndent(),
            "dual_deepseek",
        )

        assertEquals(listOf("2", "3"), answers.map { it.no })
        assertEquals(listOf("F", "D"), answers.map { it.answer })
        assertEquals("dual_deepseek", answers.first().method)
        assertEquals(1.0, answers.first().confidence, 0.0)
    }

    @Test
    fun parsesTextAroundJsonArray() {
        val answers = parseAnswers(
            "推理过程省略。\n[{\"no\":\"1\",\"stem\":\"题干\",\"answer\":\"B\"}]\n以上。",
            "dual_qwen",
        )
        assertEquals(1, answers.size)
        assertEquals("B", answers.first().answer)
        assertEquals(0.8, answers.first().confidence, 0.0)
    }

    @Test
    fun rejectsResponseWithoutJsonArray() {
        assertThrows(IOException::class.java) {
            parseAnswers("抱歉，我无法识别这张图片。", "dual_qwen")
        }
    }

    @Test
    fun deepseekOverridesSameQuestionAndQwenOnlyIsRetained() {
        val qwen = listOf(
            answer("1", "A", "dual_qwen"),
            answer("2", "B", "dual_qwen"),
            answer("3", "C", "dual_qwen"),
        )
        val deepseek = listOf(
            answer("1", "D", "dual_deepseek"),
            answer("2", "C", "dual_deepseek"),
        )

        val merged = mergeAnswers(qwen, deepseek)

        assertEquals(listOf("D", "C", "C"), merged.map { it.answer })
        assertEquals(
            listOf("dual_deepseek", "dual_deepseek", "dual_qwen"),
            merged.map { it.method },
        )
    }

    @Test
    fun fallbackKeepsQwenWhenDeepseekHasNoAnswers() {
        val merged = mergeAnswers(listOf(answer("1", "A", "dual_qwen")), null)
        assertEquals(1, merged.size)
        assertEquals("A", merged.first().answer)
    }

    private fun answer(no: String, letter: String, method: String) = QuestionAnswer(
        no = no,
        stem = "题干$no",
        answer = letter,
        method = method,
        confidence = 1.0,
    )
}
