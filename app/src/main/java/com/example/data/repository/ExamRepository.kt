package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.Exam
import com.example.data.model.ExamQuestion
import com.example.data.model.GeneratedExamDraft
import com.example.data.model.QuestionType
import com.example.data.model.TheorySelfGrade
import com.example.firebase.OperationType
import com.example.firebase.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class ExamRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeExams(userId: String): Flow<List<Exam>> = flow {
        val path = "users/$userId/exams"
        emitAll(
            db.collection("users").document(userId).collection("exams")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(Exam::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeExamQuestions(userId: String, examId: String): Flow<List<ExamQuestion>> = flow {
        val path = "users/$userId/exams/$examId/questions"
        emitAll(
            db.collection("users").document(userId)
                .collection("exams").document(examId)
                .collection("questions")
                .orderBy("questionNumber", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(ExamQuestion::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun saveGeneratedExam(draft: GeneratedExamDraft): Result<String> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/exams"
        return try {
            val uid = requireUserId()
            val examsCol = db.collection("users").document(uid).collection("exams")
            val examRef = examsCol.document()
            val examId = examRef.id
            docPath = examRef.path

            val safeTitle = draft.title.trim().ifBlank { "Study Notes Exam" }.take(140)
            val safeSubject = draft.subject.trim().ifBlank { "General Studies" }.take(80)
            val safeOverview = draft.teacherOverview.trim().take(1000)
            val objectiveCount = draft.questions.count { it.questionType == QuestionType.OBJECTIVE }
            val theoryCount = draft.questions.count { it.questionType == QuestionType.THEORY }

            val examPayload = mapOf(
                "userId" to uid,
                "title" to safeTitle,
                "subject" to safeSubject,
                "difficulty" to draft.difficulty.value,
                "teacherOverview" to safeOverview,
                "pageCount" to draft.pageCount.coerceIn(1, 10).toLong(),
                "objectiveCount" to objectiveCount.toLong(),
                "theoryCount" to theoryCount.toLong(),
                "objectiveAnsweredCount" to 0L,
                "objectiveCorrectCount" to 0L,
                "theoryMasteredCount" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )

            // Write parent exam + up to 100 questions atomically in a single Firestore WriteBatch (limit 500 ops)
            val batch = db.batch()
            batch.set(examRef, examPayload)

            draft.questions.take(100).forEachIndexed { index, q ->
                val qNum = (index + 1).coerceIn(1, 100)
                val qId = "q_${qNum.toString().padStart(3, '0')}"
                val qRef = examRef.collection("questions").document(qId)
                val isObjective = q.questionType == QuestionType.OBJECTIVE
                val qPayload = mapOf(
                    "userId" to uid,
                    "examId" to examId,
                    "questionNumber" to qNum.toLong(),
                    "questionType" to q.questionType.name,
                    "topicTag" to q.topicTag.trim().ifBlank { "Concept" }.take(80),
                    "bloomLevel" to q.bloomLevel.trim().ifBlank { "Understand" }.take(40),
                    "promptText" to q.promptText.trim().ifBlank { "Question $qNum" }.take(1500),
                    "options" to if (isObjective) q.options.take(4) else emptyList<String>(),
                    "correctOptionIndex" to if (isObjective) q.correctOptionIndex.coerceIn(0, 3).toLong() else -1L,
                    "explanation" to if (isObjective) q.explanation.trim().take(1500) else "",
                    "marks" to q.marks.coerceIn(1, 30).toLong(),
                    "markingPoints" to if (isObjective) emptyList<String>() else q.markingPoints.take(8),
                    "modelAnswer" to if (isObjective) "" else q.modelAnswer.trim().take(2500),
                    "selectedOptionIndex" to -1L,
                    "studentTheoryAnswer" to "",
                    "theoryGrade" to TheorySelfGrade.UNGRADED.name,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                batch.set(qRef, qPayload)
            }

            batch.commit().await()
            Result.success(examId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun answerObjectiveQuestion(
        examId: String,
        questionId: String,
        selectedOptionIndex: Int,
        newAnsweredCount: Int,
        newCorrectCount: Int,
        theoryMasteredCount: Int
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/exams/$examId/questions/$questionId"
        return try {
            val uid = requireUserId()
            require(examId.isNotBlank() && questionId.isNotBlank()) { "Exam and Question IDs must not be blank." }
            val examRef = db.collection("users").document(uid).collection("exams").document(examId)
            val qRef = examRef.collection("questions").document(questionId)
            docPath = qRef.path

            val batch = db.batch()
            batch.update(
                qRef,
                mapOf(
                    "selectedOptionIndex" to selectedOptionIndex.coerceIn(0, 3).toLong(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.update(
                examRef,
                mapOf(
                    "objectiveAnsweredCount" to newAnsweredCount.coerceAtLeast(0).toLong(),
                    "objectiveCorrectCount" to newCorrectCount.coerceAtLeast(0).toLong(),
                    "theoryMasteredCount" to theoryMasteredCount.coerceAtLeast(0).toLong(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateTheoryResponse(
        examId: String,
        questionId: String,
        studentAnswer: String,
        grade: TheorySelfGrade,
        objectiveAnsweredCount: Int,
        objectiveCorrectCount: Int,
        newTheoryMasteredCount: Int
    ): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/exams/$examId/questions/$questionId"
        return try {
            val uid = requireUserId()
            require(examId.isNotBlank() && questionId.isNotBlank()) { "Exam and Question IDs must not be blank." }
            val examRef = db.collection("users").document(uid).collection("exams").document(examId)
            val qRef = examRef.collection("questions").document(questionId)
            docPath = qRef.path

            val batch = db.batch()
            batch.update(
                qRef,
                mapOf(
                    "studentTheoryAnswer" to studentAnswer.take(3000),
                    "theoryGrade" to grade.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.update(
                examRef,
                mapOf(
                    "objectiveAnsweredCount" to objectiveAnsweredCount.coerceAtLeast(0).toLong(),
                    "objectiveCorrectCount" to objectiveCorrectCount.coerceAtLeast(0).toLong(),
                    "theoryMasteredCount" to newTheoryMasteredCount.coerceAtLeast(0).toLong(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteExam(examId: String): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/exams/$examId"
        return try {
            val uid = requireUserId()
            require(examId.isNotBlank()) { "Exam ID must not be blank." }
            val examRef = db.collection("users").document(uid).collection("exams").document(examId)
            docPath = examRef.path

            val questionsSnap = examRef.collection("questions").get().await()
            val batch = db.batch()
            questionsSnap.documents.forEach { batch.delete(it.reference) }
            batch.delete(examRef)
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }
}
