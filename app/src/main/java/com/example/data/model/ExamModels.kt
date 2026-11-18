package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class QuestionType(val label: String) {
    OBJECTIVE("Objective"),
    THEORY("Theory");

    companion object {
        fun fromRaw(raw: String?): QuestionType =
            entries.firstOrNull { it.name == raw } ?: OBJECTIVE
    }
}

enum class ExamDifficulty(val value: String, val teacherTone: String) {
    STANDARD("Standard", "balanced classroom test covering core concepts and applications"),
    HONORS("Honors", "rigorous university-level exam emphasizing deep analysis, edge cases, and synthesis"),
    QUICK_REVIEW("Quick Review", "clear foundational checkpoint questions to reinforce key definitions and facts");

    companion object {
        fun fromRaw(raw: String?): ExamDifficulty =
            entries.firstOrNull { it.value == raw } ?: STANDARD
    }
}

enum class TheorySelfGrade(val label: String) {
    UNGRADED("Not Graded"),
    FULL("Full Marks"),
    PARTIAL("Partial Credit"),
    REVIEW("Needs Review");

    companion object {
        fun fromRaw(raw: String?): TheorySelfGrade =
            entries.firstOrNull { it.name == raw } ?: UNGRADED
    }
}

enum class ExamMixPreset(val label: String, val objectiveCount: Int, val theoryCount: Int) {
    BALANCED_60_40("60 Obj / 40 Theory", 60, 40),
    MCQ_HEAVY_70_30("70 Obj / 30 Theory", 70, 30),
    EQUAL_50_50("50 Obj / 50 Theory", 50, 50)
}

@Keep
@IgnoreExtraProperties
data class Exam(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val subject: String = "",
    val difficulty: String = ExamDifficulty.STANDARD.value,
    val teacherOverview: String = "",
    val pageCount: Long = 1L,
    val objectiveCount: Long = 60L,
    val theoryCount: Long = 40L,
    val objectiveAnsweredCount: Long = 0L,
    val objectiveCorrectCount: Long = 0L,
    val theoryMasteredCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ExamQuestion(
    @DocumentId val id: String = "",
    val userId: String = "",
    val examId: String = "",
    val questionNumber: Long = 1L,
    val questionType: String = QuestionType.OBJECTIVE.name,
    val topicTag: String = "",
    val bloomLevel: String = "",
    val promptText: String = "",
    val options: List<String> = emptyList(),
    val correctOptionIndex: Long = -1L,
    val explanation: String = "",
    val marks: Long = 1L,
    val markingPoints: List<String> = emptyList(),
    val modelAnswer: String = "",
    val selectedOptionIndex: Long = -1L,
    val studentTheoryAnswer: String = "",
    val theoryGrade: String = TheorySelfGrade.UNGRADED.name,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun resolvedType(): QuestionType = QuestionType.fromRaw(questionType)
    fun resolvedTheoryGrade(): TheorySelfGrade = TheorySelfGrade.fromRaw(theoryGrade)
}

data class GeneratedExamDraft(
    val title: String,
    val subject: String,
    val teacherOverview: String,
    val difficulty: ExamDifficulty,
    val pageCount: Int,
    val questions: List<GeneratedQuestionDraft>
)

data class GeneratedQuestionDraft(
    val questionNumber: Int,
    val questionType: QuestionType,
    val topicTag: String,
    val bloomLevel: String,
    val promptText: String,
    val options: List<String>,
    val correctOptionIndex: Int,
    val explanation: String,
    val marks: Int,
    val markingPoints: List<String>,
    val modelAnswer: String
)

data class GenerationProgress(
    val stageLabel: String,
    val generatedCount: Int,
    val targetCount: Int
)
