package com.example

import com.example.data.model.ExamDifficulty
import com.example.data.model.ExamMixPreset
import com.example.data.model.ExamQuestion
import com.example.data.model.QuestionType
import com.example.data.model.TheorySelfGrade
import org.junit.Assert.assertEquals
import org.junit.Test

class ExamModelsUnitTest {

    @Test
    fun `all exam mix presets total 100 questions`() {
        ExamMixPreset.entries.forEach { preset ->
            assertEquals(100, preset.objectiveCount + preset.theoryCount)
        }
    }

    @Test
    fun `safe enum fallbacks handle unknown values without throwing`() {
        assertEquals(QuestionType.OBJECTIVE, QuestionType.fromRaw("UNKNOWN"))
        assertEquals(QuestionType.THEORY, QuestionType.fromRaw("THEORY"))

        assertEquals(ExamDifficulty.STANDARD, ExamDifficulty.fromRaw(null))
        assertEquals(ExamDifficulty.HONORS, ExamDifficulty.fromRaw("Honors"))

        assertEquals(TheorySelfGrade.UNGRADED, TheorySelfGrade.fromRaw("INVALID"))
        assertEquals(TheorySelfGrade.FULL, TheorySelfGrade.fromRaw("FULL"))
    }

    @Test
    fun `exam question resolves defaults safely`() {
        val q = ExamQuestion()
        assertEquals(QuestionType.OBJECTIVE, q.resolvedType())
        assertEquals(TheorySelfGrade.UNGRADED, q.resolvedTheoryGrade())
        assertEquals(-1L, q.selectedOptionIndex)
    }
}
