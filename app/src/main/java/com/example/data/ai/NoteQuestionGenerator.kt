package com.example.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.data.model.ExamDifficulty
import com.example.data.model.ExamMixPreset
import com.example.data.model.GeneratedExamDraft
import com.example.data.model.GeneratedQuestionDraft
import com.example.data.model.GenerationProgress
import com.example.data.model.QuestionType
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "NoteQuestionGenerator"
private const val MODEL_NAME = "gemini-3.8-flash"
private const val BATCH_TIMEOUT_MS = 60_000L
private const val MAX_IMAGE_EDGE_PX = 1280

class NoteQuestionGenerator(private val appContext: Context) {

    private val objectiveSchema = Schema.obj(
        properties = mapOf(
            "suggestedTitle" to Schema.string(description = "Short exam title based on the notes"),
            "suggestedSubject" to Schema.string(description = "Academic subject name"),
            "teacherOverview" to Schema.string(description = "2-sentence teacher summary of the notes"),
            "questions" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "promptText" to Schema.string(description = "Clear multiple-choice question stem"),
                        "topicTag" to Schema.string(description = "Short topic label from the notes"),
                        "bloomLevel" to Schema.string(description = "One of: Remember, Understand, Apply, Analyze"),
                        "options" to Schema.array(
                            items = Schema.string(),
                            description = "Exactly 4 plausible answer choices without A/B/C/D prefixes"
                        ),
                        "correctOptionIndex" to Schema.integer(description = "Zero-based index (0..3) of the correct option"),
                        "explanation" to Schema.string(description = "Concise teacher explanation of why the answer is right")
                    )
                ),
                description = "List of objective multiple-choice questions"
            )
        )
    )

    private val theorySchema = Schema.obj(
        properties = mapOf(
            "questions" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "promptText" to Schema.string(description = "Structured theory/essay exam question"),
                        "topicTag" to Schema.string(description = "Short topic label from the notes"),
                        "bloomLevel" to Schema.string(description = "One of: Explain, Compare, Apply, Analyze, Evaluate"),
                        "marks" to Schema.integer(description = "Marks allocated between 3 and 15"),
                        "markingPoints" to Schema.array(
                            items = Schema.string(),
                            description = "3 to 5 concise teacher marking rubric points"
                        ),
                        "modelAnswer" to Schema.string(description = "Comprehensive model answer a teacher expects")
                    )
                ),
                description = "List of theory/essay exam questions"
            )
        )
    )

    private val objectiveModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = objectiveSchema
                temperature = 0.5f
            }
        )
    }

    private val theoryModel by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = theorySchema
                temperature = 0.5f
            }
        )
    }

    suspend fun generateHundredQuestionExam(
        noteUris: List<Uri>,
        customTitle: String,
        difficulty: ExamDifficulty,
        mixPreset: ExamMixPreset,
        onProgress: (GenerationProgress) -> Unit
    ): GeneratedExamDraft {
        require(noteUris.isNotEmpty()) { "Please capture or select at least one note image." }

        onProgress(GenerationProgress("Preparing ${noteUris.size} note page(s)...", 0, 100))
        val bitmaps = loadScaledBitmaps(noteUris)
        if (bitmaps.isEmpty()) {
            throw IllegalStateException("Could not decode the selected note images.")
        }

        val targetObj = mixPreset.objectiveCount
        val targetTheory = mixPreset.theoryCount
        val totalTarget = targetObj + targetTheory

        val objectiveDrafts = mutableListOf<GeneratedQuestionDraft>()
        val theoryDrafts = mutableListOf<GeneratedQuestionDraft>()

        var extractedTitle = customTitle.trim()
        var extractedSubject = "General Studies"
        var teacherOverview = "Teacher-prepared 100-question assessment generated directly from your scanned notes."

        val usedPrompts = mutableListOf<String>()

        // Split objective generation into 2 reliable batches (+ optional top-up if needed)
        val objBatch1Size = (targetObj + 1) / 2
        val objBatch2Size = targetObj - objBatch1Size

        val objPlan = listOf(
            objBatch1Size to "Part A (Set 1 of 2): Focus on core definitions, facts, mechanisms, and comprehension.",
            objBatch2Size to "Part A (Set 2 of 2): Focus on applied scenarios, edge cases, comparisons, and deeper synthesis."
        )

        for ((index, spec) in objPlan.withIndex()) {
            val countWanted = spec.first
            if (countWanted <= 0) continue
            onProgress(
                GenerationProgress(
                    "Drafting Objective Questions (${objectiveDrafts.size + 1}–${objectiveDrafts.size + countWanted} of $targetObj)...",
                    objectiveDrafts.size,
                    totalTarget
                )
            )
            val result = requestObjectiveBatch(
                bitmaps = bitmaps,
                count = countWanted,
                difficulty = difficulty,
                focusInstruction = spec.second,
                avoidPrompts = usedPrompts.takeLast(35)
            )
            if (index == 0) {
                if (extractedTitle.isBlank()) {
                    extractedTitle = result.title.ifBlank { "Study Notes Assessment" }
                }
                extractedSubject = result.subject.ifBlank { "Study Notes" }
                teacherOverview = result.overview.ifBlank { teacherOverview }
            }
            for (q in result.questions) {
                if (objectiveDrafts.size < targetObj) {
                    objectiveDrafts.add(q)
                    usedPrompts.add(q.promptText.take(80))
                }
            }
        }

        // Top-up objective if model returned slightly fewer than targetObj
        var objTopUpAttempts = 0
        while (objectiveDrafts.size < targetObj && objTopUpAttempts < 2) {
            objTopUpAttempts++
            val remaining = targetObj - objectiveDrafts.size
            onProgress(
                GenerationProgress(
                    "Completing remaining $remaining Objective Question(s)...",
                    objectiveDrafts.size,
                    totalTarget
                )
            )
            val result = requestObjectiveBatch(
                bitmaps = bitmaps,
                count = remaining,
                difficulty = difficulty,
                focusInstruction = "Generate exactly $remaining additional unique multiple-choice questions covering untested details from the notes.",
                avoidPrompts = usedPrompts.takeLast(45)
            )
            for (q in result.questions) {
                if (objectiveDrafts.size < targetObj) {
                    objectiveDrafts.add(q)
                    usedPrompts.add(q.promptText.take(80))
                }
            }
        }

        // Split theory generation into 2 reliable batches (+ optional top-up)
        val theoryBatch1Size = (targetTheory + 1) / 2
        val theoryBatch2Size = targetTheory - theoryBatch1Size

        val theoryPlan = listOf(
            theoryBatch1Size to "Part B (Section 1): Short-answer and structured conceptual theory questions (3-6 marks each).",
            theoryBatch2Size to "Part B (Section 2): Extended essay, comparison, derivation, and real-world application theory questions (6-12 marks each)."
        )

        for (spec in theoryPlan) {
            val countWanted = spec.first
            if (countWanted <= 0) continue
            onProgress(
                GenerationProgress(
                    "Drafting Theory Questions (${objectiveDrafts.size + theoryDrafts.size + 1}–${objectiveDrafts.size + theoryDrafts.size + countWanted} of $totalTarget)...",
                    objectiveDrafts.size + theoryDrafts.size,
                    totalTarget
                )
            )
            val questions = requestTheoryBatch(
                bitmaps = bitmaps,
                count = countWanted,
                difficulty = difficulty,
                focusInstruction = spec.second,
                avoidPrompts = usedPrompts.takeLast(35)
            )
            for (q in questions) {
                if (theoryDrafts.size < targetTheory) {
                    theoryDrafts.add(q)
                    usedPrompts.add(q.promptText.take(80))
                }
            }
        }

        var theoryTopUpAttempts = 0
        while (theoryDrafts.size < targetTheory && theoryTopUpAttempts < 2) {
            theoryTopUpAttempts++
            val remaining = targetTheory - theoryDrafts.size
            onProgress(
                GenerationProgress(
                    "Completing remaining $remaining Theory Question(s)...",
                    objectiveDrafts.size + theoryDrafts.size,
                    totalTarget
                )
            )
            val questions = requestTheoryBatch(
                bitmaps = bitmaps,
                count = remaining,
                difficulty = difficulty,
                focusInstruction = "Generate exactly $remaining additional unique theory questions with full marking schemes.",
                avoidPrompts = usedPrompts.takeLast(45)
            )
            for (q in questions) {
                if (theoryDrafts.size < targetTheory) {
                    theoryDrafts.add(q)
                    usedPrompts.add(q.promptText.take(80))
                }
            }
        }

        // Renumber sequentially 1..N
        val allQuestions = mutableListOf<GeneratedQuestionDraft>()
        objectiveDrafts.forEachIndexed { idx, q ->
            allQuestions.add(q.copy(questionNumber = idx + 1))
        }
        theoryDrafts.forEachIndexed { idx, q ->
            allQuestions.add(q.copy(questionNumber = objectiveDrafts.size + idx + 1))
        }

        onProgress(GenerationProgress("Finalizing & saving exam...", allQuestions.size, totalTarget))

        return GeneratedExamDraft(
            title = extractedTitle.ifBlank { "Teacher Exam from Notes" }.take(140),
            subject = extractedSubject.ifBlank { "General Studies" }.take(80),
            teacherOverview = teacherOverview.take(1000),
            difficulty = difficulty,
            pageCount = bitmaps.size.coerceIn(1, 10),
            questions = allQuestions
        )
    }

    private data class ObjectiveBatchResponse(
        val title: String,
        val subject: String,
        val overview: String,
        val questions: List<GeneratedQuestionDraft>
    )

    private suspend fun requestObjectiveBatch(
        bitmaps: List<Bitmap>,
        count: Int,
        difficulty: ExamDifficulty,
        focusInstruction: String,
        avoidPrompts: List<String>
    ): ObjectiveBatchResponse {
        val avoidText = if (avoidPrompts.isEmpty()) "" else {
            "\nDo NOT repeat or rephrase any of these already-generated questions:\n- " +
                avoidPrompts.joinToString("\n- ")
        }
        val prompt = """
            You are an experienced school/university teacher creating an authentic exam directly from the student's uploaded note page(s).
            Difficulty profile: ${difficulty.value} (${difficulty.teacherTone}).
            Task: Carefully read all handwritten and printed content, diagrams, formulas, and definitions in the attached note image(s), and generate EXACTLY $count high-quality OBJECTIVE multiple-choice questions.
            $focusInstruction
            Requirements:
            - Return exactly $count items in the "questions" array.
            - Every question must have exactly 4 plausible options (no "A)", "B)" prefixes) and correctOptionIndex between 0 and 3.
            - Include a concise, helpful teacher explanation for each correct answer.
            - Ground every question strictly in the concepts shown in or directly implied by the notes.$avoidText
        """.trimIndent()

        val rawJson = try {
            withTimeout(BATCH_TIMEOUT_MS) {
                objectiveModel.generateContent(
                    content {
                        bitmaps.forEach { image(it) }
                        text(prompt)
                    }
                ).text ?: throw IllegalStateException("AI returned an empty objective batch response.")
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Objective batch timed out", e)
            throw IllegalStateException("Question generation timed out. Please check your connection and try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Objective batch failed", e)
            throw IllegalStateException(e.localizedMessage ?: "Failed to generate objective questions from notes.")
        }

        return parseObjectiveBatch(rawJson)
    }

    private suspend fun requestTheoryBatch(
        bitmaps: List<Bitmap>,
        count: Int,
        difficulty: ExamDifficulty,
        focusInstruction: String,
        avoidPrompts: List<String>
    ): List<GeneratedQuestionDraft> {
        val avoidText = if (avoidPrompts.isEmpty()) "" else {
            "\nDo NOT repeat these earlier questions:\n- " + avoidPrompts.joinToString("\n- ")
        }
        val prompt = """
            You are an experienced school/university teacher writing Section B (Theory & Essay Questions) of an exam directly from the student's note image(s).
            Difficulty profile: ${difficulty.value} (${difficulty.teacherTone}).
            Task: Generate EXACTLY $count unique THEORY / STRUCTURED ESSAY questions based strictly on the attached notes.
            $focusInstruction
            Requirements:
            - Return exactly $count items in the "questions" array.
            - Allocate realistic marks (between 3 and 12 marks per question).
            - Provide 3 to 5 clear teacher marking scheme bullet points in "markingPoints".
            - Provide a complete, clear "modelAnswer" showing what earns full marks.$avoidText
        """.trimIndent()

        val rawJson = try {
            withTimeout(BATCH_TIMEOUT_MS) {
                theoryModel.generateContent(
                    content {
                        bitmaps.forEach { image(it) }
                        text(prompt)
                    }
                ).text ?: throw IllegalStateException("AI returned an empty theory batch response.")
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Theory batch timed out", e)
            throw IllegalStateException("Theory question generation timed out. Please try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Theory batch failed", e)
            throw IllegalStateException(e.localizedMessage ?: "Failed to generate theory questions from notes.")
        }

        return parseTheoryBatch(rawJson)
    }

    private fun parseObjectiveBatch(rawJson: String): ObjectiveBatchResponse {
        val root = JSONObject(rawJson)
        val title = root.optString("suggestedTitle", "Study Notes Exam").take(140)
        val subject = root.optString("suggestedSubject", "General Studies").take(80)
        val overview = root.optString("teacherOverview", "").take(1000)
        val arr = root.optJSONArray("questions") ?: return ObjectiveBatchResponse(title, subject, overview, emptyList())

        val parsed = mutableListOf<GeneratedQuestionDraft>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val promptText = obj.optString("promptText", "").trim().take(1500)
            if (promptText.isBlank()) continue
            val optsArr = obj.optJSONArray("options")
            val rawOptions = mutableListOf<String>()
            if (optsArr != null) {
                for (j in 0 until optsArr.length()) {
                    val opt = optsArr.optString(j, "").trim()
                    if (opt.isNotEmpty()) rawOptions.add(opt)
                }
            }
            while (rawOptions.size < 4) {
                rawOptions.add("None of the above")
            }
            val options = rawOptions.take(4)
            val correctIdx = obj.optInt("correctOptionIndex", 0).coerceIn(0, 3)

            parsed.add(
                GeneratedQuestionDraft(
                    questionNumber = i + 1,
                    questionType = QuestionType.OBJECTIVE,
                    topicTag = obj.optString("topicTag", "Key Concept").trim().ifBlank { "Key Concept" }.take(80),
                    bloomLevel = obj.optString("bloomLevel", "Understand").trim().ifBlank { "Understand" }.take(40),
                    promptText = promptText,
                    options = options,
                    correctOptionIndex = correctIdx,
                    explanation = obj.optString("explanation", "").trim().take(1500),
                    marks = 1,
                    markingPoints = emptyList(),
                    modelAnswer = ""
                )
            )
        }
        return ObjectiveBatchResponse(title, subject, overview, parsed)
    }

    private fun parseTheoryBatch(rawJson: String): List<GeneratedQuestionDraft> {
        val root = JSONObject(rawJson)
        val arr = root.optJSONArray("questions") ?: return emptyList()

        val parsed = mutableListOf<GeneratedQuestionDraft>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val promptText = obj.optString("promptText", "").trim().take(1500)
            if (promptText.isBlank()) continue
            val mpArr = obj.optJSONArray("markingPoints")
            val points = mutableListOf<String>()
            if (mpArr != null) {
                for (j in 0 until minOf(mpArr.length(), 8)) {
                    val p = mpArr.optString(j, "").trim()
                    if (p.isNotEmpty()) points.add(p)
                }
            }
            parsed.add(
                GeneratedQuestionDraft(
                    questionNumber = i + 1,
                    questionType = QuestionType.THEORY,
                    topicTag = obj.optString("topicTag", "Theory").trim().ifBlank { "Theory" }.take(80),
                    bloomLevel = obj.optString("bloomLevel", "Analyze").trim().ifBlank { "Analyze" }.take(40),
                    promptText = promptText,
                    options = emptyList(),
                    correctOptionIndex = -1,
                    explanation = "",
                    marks = obj.optInt("marks", 5).coerceIn(1, 30),
                    markingPoints = points,
                    modelAnswer = obj.optString("modelAnswer", "").trim().take(2500)
                )
            )
        }
        return parsed
    }

    private suspend fun loadScaledBitmaps(uris: List<Uri>): List<Bitmap> = withContext(Dispatchers.IO) {
        uris.take(10).mapNotNull { uri ->
            try {
                val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(appContext.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val w = info.size.width
                        val h = info.size.height
                        val longest = max(w, h)
                        if (longest > MAX_IMAGE_EDGE_PX) {
                            val ratio = MAX_IMAGE_EDGE_PX.toFloat() / longest.toFloat()
                            decoder.setTargetSize(
                                (w * ratio).roundToInt().coerceAtLeast(1),
                                (h * ratio).roundToInt().coerceAtLeast(1)
                            )
                        }
                        decoder.isMutableRequired = false
                    }
                } else {
                    appContext.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }?.let { bmp ->
                        val longest = max(bmp.width, bmp.height)
                        if (longest > MAX_IMAGE_EDGE_PX) {
                            val ratio = MAX_IMAGE_EDGE_PX.toFloat() / longest.toFloat()
                            val scaled = Bitmap.createScaledBitmap(
                                bmp,
                                (bmp.width * ratio).roundToInt().coerceAtLeast(1),
                                (bmp.height * ratio).roundToInt().coerceAtLeast(1),
                                true
                            )
                            if (scaled != bmp) bmp.recycle()
                            scaled
                        } else {
                            bmp
                        }
                    }
                }
                decoded
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode note image: $uri", e)
                null
            }
        }
    }
}
