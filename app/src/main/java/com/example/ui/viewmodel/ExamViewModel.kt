package com.example.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.NoteQuestionGenerator
import com.example.data.model.Exam
import com.example.data.model.ExamDifficulty
import com.example.data.model.ExamMixPreset
import com.example.data.model.ExamQuestion
import com.example.data.model.GenerationProgress
import com.example.data.model.QuestionType
import com.example.data.model.TheorySelfGrade
import com.example.data.repository.ExamRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

sealed interface GenerationUiState {
    data object Idle : GenerationUiState
    data class Generating(val progress: GenerationProgress) : GenerationUiState
    data class Error(val message: String) : GenerationUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class ExamViewModel(
    private val repository: ExamRepository,
    private val questionGenerator: NoteQuestionGenerator,
    private val currentUserId: String
) : ViewModel() {

    val examsState: StateFlow<UiState<List<Exam>>> = repository.observeExams(currentUserId)
        .map<List<Exam>, UiState<List<Exam>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing exams", rootError)
            emit(UiState.Error(rootError.message ?: "Failed to load saved exams."))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    private val _selectedExamId = MutableStateFlow<String?>(null)
    val selectedExamId: StateFlow<String?> = _selectedExamId.asStateFlow()

    val selectedExamQuestionsState: StateFlow<UiState<List<ExamQuestion>>> = _selectedExamId
        .flatMapLatest { examId ->
            if (examId.isNullOrBlank()) {
                flowOf(UiState.Success(emptyList()))
            } else {
                repository.observeExamQuestions(currentUserId, examId)
                    .map<List<ExamQuestion>, UiState<List<ExamQuestion>>> { UiState.Success(it) }
                    .catch { error ->
                        val firestoreError = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (firestoreError == null && error is CancellationException) throw error
                        val rootError = firestoreError ?: error
                        Log.w(TAG, "Error observing exam questions", rootError)
                        emit(UiState.Error(rootError.message ?: "Failed to load exam questions."))
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    private val _selectedNoteUris = MutableStateFlow<List<Uri>>(emptyList())
    val selectedNoteUris: StateFlow<List<Uri>> = _selectedNoteUris.asStateFlow()

    private val _generationState = MutableStateFlow<GenerationUiState>(GenerationUiState.Idle)
    val generationState: StateFlow<GenerationUiState> = _generationState.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    fun addNoteUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val combined = (_selectedNoteUris.value + uris).distinct().take(10)
        _selectedNoteUris.value = combined
        if (_generationState.value is GenerationUiState.Error) {
            _generationState.value = GenerationUiState.Idle
        }
    }

    fun removeNoteUri(uri: Uri) {
        _selectedNoteUris.value = _selectedNoteUris.value.filterNot { it == uri }
    }

    fun clearNotes() {
        _selectedNoteUris.value = emptyList()
    }

    fun openExam(examId: String) {
        _selectedExamId.value = examId
    }

    fun clearActionError() {
        _actionError.value = null
    }

    fun clearGenerationError() {
        if (_generationState.value is GenerationUiState.Error) {
            _generationState.value = GenerationUiState.Idle
        }
    }

    fun generateAndSaveExam(
        customTitle: String,
        difficulty: ExamDifficulty,
        mixPreset: ExamMixPreset,
        onExamCreated: (String) -> Unit
    ) {
        val uris = _selectedNoteUris.value
        if (uris.isEmpty()) {
            _generationState.value = GenerationUiState.Error("Please take or upload at least one photo of your notes.")
            return
        }
        if (_generationState.value is GenerationUiState.Generating) return

        viewModelScope.launch {
            _generationState.value = GenerationUiState.Generating(
                GenerationProgress("Reading your study notes...", 0, 100)
            )
            try {
                val draft = questionGenerator.generateHundredQuestionExam(
                    noteUris = uris,
                    customTitle = customTitle,
                    difficulty = difficulty,
                    mixPreset = mixPreset,
                    onProgress = { progress ->
                        _generationState.value = GenerationUiState.Generating(progress)
                    }
                )
                val saveResult = repository.saveGeneratedExam(draft)
                saveResult.fold(
                    onSuccess = { examId ->
                        _selectedNoteUris.value = emptyList()
                        _generationState.value = GenerationUiState.Idle
                        _selectedExamId.value = examId
                        onExamCreated(examId)
                    },
                    onFailure = { error ->
                        _generationState.value = GenerationUiState.Error(
                            error.localizedMessage ?: "Generated questions, but failed to save to Firestore."
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Exam generation failed", e)
                _generationState.value = GenerationUiState.Error(
                    e.localizedMessage ?: "Failed to generate exam questions from notes."
                )
            }
        }
    }

    fun selectObjectiveAnswer(
        examId: String,
        question: ExamQuestion,
        optionIndex: Int,
        allQuestions: List<ExamQuestion>
    ) {
        val updatedQuestions = allQuestions.map {
            if (it.id == question.id) it.copy(selectedOptionIndex = optionIndex.toLong()) else it
        }
        val objectiveList = updatedQuestions.filter { it.resolvedType() == QuestionType.OBJECTIVE }
        val answeredCount = objectiveList.count { it.selectedOptionIndex in 0L..3L }
        val correctCount = objectiveList.count {
            it.selectedOptionIndex in 0L..3L && it.selectedOptionIndex == it.correctOptionIndex
        }
        val theoryMasteredCount = updatedQuestions.count {
            it.resolvedType() == QuestionType.THEORY && it.resolvedTheoryGrade() == TheorySelfGrade.FULL
        }

        viewModelScope.launch {
            val result = repository.answerObjectiveQuestion(
                examId = examId,
                questionId = question.id,
                selectedOptionIndex = optionIndex,
                newAnsweredCount = answeredCount,
                newCorrectCount = correctCount,
                theoryMasteredCount = theoryMasteredCount
            )
            result.onFailure {
                _actionError.value = it.localizedMessage ?: "Could not save your answer."
            }
        }
    }

    fun saveTheoryResponse(
        examId: String,
        question: ExamQuestion,
        answerText: String,
        grade: TheorySelfGrade,
        allQuestions: List<ExamQuestion>
    ) {
        val updatedQuestions = allQuestions.map {
            if (it.id == question.id) {
                it.copy(studentTheoryAnswer = answerText, theoryGrade = grade.name)
            } else it
        }
        val objectiveList = updatedQuestions.filter { it.resolvedType() == QuestionType.OBJECTIVE }
        val answeredCount = objectiveList.count { it.selectedOptionIndex in 0L..3L }
        val correctCount = objectiveList.count {
            it.selectedOptionIndex in 0L..3L && it.selectedOptionIndex == it.correctOptionIndex
        }
        val theoryMasteredCount = updatedQuestions.count {
            it.resolvedType() == QuestionType.THEORY && it.resolvedTheoryGrade() == TheorySelfGrade.FULL
        }

        viewModelScope.launch {
            val result = repository.updateTheoryResponse(
                examId = examId,
                questionId = question.id,
                studentAnswer = answerText,
                grade = grade,
                objectiveAnsweredCount = answeredCount,
                objectiveCorrectCount = correctCount,
                newTheoryMasteredCount = theoryMasteredCount
            )
            result.onFailure {
                _actionError.value = it.localizedMessage ?: "Could not save your theory response."
            }
        }
    }

    fun deleteExam(examId: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.deleteExam(examId)
            result.fold(
                onSuccess = {
                    if (_selectedExamId.value == examId) {
                        _selectedExamId.value = null
                    }
                    onDeleted()
                },
                onFailure = {
                    _actionError.value = it.localizedMessage ?: "Failed to delete exam."
                }
            )
        }
    }

    fun formatExamForExport(exam: Exam, questions: List<ExamQuestion>): String {
        val sb = StringBuilder()
        sb.appendLine("========================================")
        sb.appendLine(exam.title.uppercase())
        sb.appendLine("Subject: ${exam.subject} | Difficulty: ${exam.difficulty}")
        sb.appendLine("Questions: ${questions.size} (${exam.objectiveCount} Objective, ${exam.theoryCount} Theory)")
        sb.appendLine("========================================")
        if (exam.teacherOverview.isNotBlank()) {
            sb.appendLine("Teacher Overview: ${exam.teacherOverview}")
            sb.appendLine()
        }

        val obj = questions.filter { it.resolvedType() == QuestionType.OBJECTIVE }
        val theory = questions.filter { it.resolvedType() == QuestionType.THEORY }

        sb.appendLine("SECTION A: OBJECTIVE QUESTIONS (${obj.size})")
        sb.appendLine("----------------------------------------")
        val letters = listOf("A", "B", "C", "D")
        obj.forEach { q ->
            sb.appendLine("${q.questionNumber}. [${q.topicTag}] ${q.promptText}")
            q.options.forEachIndexed { idx, opt ->
                val label = letters.getOrElse(idx) { "${idx + 1}" }
                sb.appendLine("   $label) $opt")
            }
            sb.appendLine()
        }

        sb.appendLine("SECTION B: THEORY & ESSAY QUESTIONS (${theory.size})")
        sb.appendLine("----------------------------------------")
        theory.forEach { q ->
            sb.appendLine("${q.questionNumber}. [${q.marks} marks | ${q.topicTag}] ${q.promptText}")
            sb.appendLine()
        }

        sb.appendLine("========================================")
        sb.appendLine("TEACHER ANSWER KEY & MARKING SCHEME")
        sb.appendLine("========================================")
        obj.forEach { q ->
            val correctLetter = letters.getOrElse(q.correctOptionIndex.toInt()) { "?" }
            sb.appendLine("Q${q.questionNumber}: $correctLetter — ${q.explanation}")
        }
        sb.appendLine()
        theory.forEach { q ->
            sb.appendLine("Q${q.questionNumber} (${q.marks} marks):")
            q.markingPoints.forEach { pt -> sb.appendLine("  • $pt") }
            if (q.modelAnswer.isNotBlank()) {
                sb.appendLine("  Model Answer: ${q.modelAnswer}")
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    private companion object {
        const val TAG = "ExamViewModel"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
