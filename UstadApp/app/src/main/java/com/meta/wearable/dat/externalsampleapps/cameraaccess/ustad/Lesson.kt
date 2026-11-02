// Lesson - What an expert's recorded job becomes, and what a learner's progress looks like
//
// Capture → Lesson → Guide:
//  1. An expert wears the glasses (or a head-mounted phone) and does the job normally.
//  2. Ustad turns the recording into a Lesson: ordered steps, the "why" behind each one, safety
//     warnings, photo checkpoints and a short quiz. The expert reviews and publishes it.
//  3. A learner follows the lesson hands-free; the result is written to their Skill Passport.
//
// Plain Kotlin + org.json + java.io so it is unit-testable without a device.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class LessonStep(
    val title: String,
    /** What Ustad says to the learner: one action, short. */
    val say: String,
    /** Why this step matters, the bit an apprentice usually never hears. */
    val why: String = "",
    /** What the expert actually said about this step, in their own words (from the recording). */
    val expertWords: String = "",
    val safety: String = "",
    /** The learner must show a photo of the result before moving on. */
    val checkpoint: Boolean = false,
    val mustShow: String = "",
    /** Part highlighted in the exploded view during this step (e.g. "c-battery12"). */
    val partId: String = "",
    /** Where this step happens in the expert's recording, in seconds (-1 = unknown). */
    val atSec: Int = -1,
)

data class QuizQuestion(val question: String, val options: List<String>, val answer: Int, val explain: String = "")

data class Lesson(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val machine: String,
    val kind: MachineKind = MachineKind.OTHER,
    val summary: String = "",
    val steps: List<LessonStep>,
    val quiz: List<QuizQuestion> = emptyList(),
    /** Internal-parts layout for the generic 3D viewer when [kind] has no hand-built model. */
    val partsJson: String = "[]",
    val author: String = "Expert",
    val createdAt: Long = System.currentTimeMillis(),
    /** Drafts are only visible to the expert; published lessons show up for learners. */
    val published: Boolean = false,
    val builtIn: Boolean = false,
)

private fun JSONArray?.objs(): List<JSONObject> =
    if (this == null) emptyList() else List(length()) { optJSONObject(it) ?: JSONObject() }

private fun JSONArray?.strs(): List<String> =
    if (this == null) emptyList() else List(length()) { optString(it) }.filter { it.isNotBlank() }

fun LessonStep.toJson(): JSONObject =
    JSONObject()
        .put("title", title)
        .put("say", say)
        .put("why", why)
        .put("expert_words", expertWords)
        .put("safety", safety)
        .put("checkpoint", checkpoint)
        .put("must_show", mustShow)
        .put("part_id", partId)
        .put("at_sec", atSec)

fun Lesson.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("title", title)
        .put("machine", machine)
        .put("kind", kind.name)
        .put("summary", summary)
        .put("steps", JSONArray(steps.map { it.toJson() }))
        .put(
            "quiz",
            JSONArray(
                quiz.map {
                  JSONObject()
                      .put("question", it.question)
                      .put("options", JSONArray(it.options))
                      .put("answer", it.answer)
                      .put("explain", it.explain)
                },
            ),
        )
        .put("internal_parts", runCatching { JSONArray(partsJson) }.getOrDefault(JSONArray()))
        .put("author", author)
        .put("created_at", createdAt)
        .put("published", published)

/**
 * Parses a lesson, either one saved by [LessonStore] or the raw answer of the AI lesson prompt.
 * Lenient: missing fields get defaults, steps without text are dropped, quiz answers are clamped.
 */
fun parseLesson(json: JSONObject, builtIn: Boolean = false): Lesson {
  val machine = json.optString("machine")
  val steps =
      json.optJSONArray("steps").objs().mapNotNull {
        val say = it.optString("say").ifBlank { it.optString("instruction") }
        if (say.isBlank()) null
        else
            LessonStep(
                title = it.optString("title").ifBlank { say.take(40) },
                say = say,
                why = it.optString("why"),
                expertWords = it.optString("expert_words"),
                safety = it.optString("safety").takeUnless { s -> s.trim().lowercase() in setOf("none", "n/a", "") }.orEmpty(),
                checkpoint = it.optBoolean("checkpoint", false),
                mustShow = it.optString("must_show"),
                partId = it.optString("part_id"),
                atSec = it.optInt("at_sec", -1),
            )
      }
  val quiz =
      json.optJSONArray("quiz").objs().mapNotNull {
        val options = it.optJSONArray("options").strs()
        val q = it.optString("question")
        if (q.isBlank() || options.size < 2) null
        else QuizQuestion(q, options, it.optInt("answer", 0).coerceIn(0, options.lastIndex), it.optString("explain"))
      }
  val partIds = steps.map { it.partId }.filter { it.isNotBlank() }
  val kind =
      runCatching { MachineKind.valueOf(json.optString("kind")) }.getOrNull()
          ?: MachineRouter.detect("$machine ${json.optString("title")}", partIds)
  return Lesson(
      id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
      title = json.optString("title").ifBlank { "Lesson: $machine" },
      machine = machine,
      kind = kind,
      summary = json.optString("summary"),
      steps = steps,
      quiz = quiz,
      partsJson = json.optJSONArray("internal_parts")?.toString() ?: "[]",
      author = json.optString("author").ifBlank { "Expert" },
      createdAt = json.optLong("created_at", System.currentTimeMillis()),
      published = builtIn || json.optBoolean("published", false),
      builtIn = builtIn,
  )
}

/** Lessons made on this phone live in [dir] as one JSON file each; built-in lessons ship as assets. */
class LessonStore(private val dir: File) {
  init {
    dir.mkdirs()
  }

  fun list(): List<Lesson> =
      (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
          .mapNotNull { f -> runCatching { parseLesson(JSONObject(f.readText())) }.getOrNull() }
          .sortedByDescending { it.createdAt }

  fun save(lesson: Lesson) {
    val tmp = File(dir, "${lesson.id}.tmp")
    tmp.writeText(lesson.toJson().toString(2))
    tmp.renameTo(File(dir, "${lesson.id}.json"))
  }

  fun delete(id: String) {
    File(dir, "$id.json").delete()
  }
}

/** One completed lesson in a learner's Skill Passport. */
data class PassportEntry(
    val lessonId: String,
    val lessonTitle: String,
    val learner: String,
    val at: Long,
    val steps: Int,
    val checkpointsPassed: Int,
    val checkpointsTotal: Int,
    val whyAsked: Int,
    val quizCorrect: Int,
    val quizTotal: Int,
) {
  /**
   * 0–100. Quiz (understanding) 60%, photo checkpoints passed first time (doing it right) 40%.
   * Asking "why?" is never penalised; it is how apprentices learn.
   */
  val score: Int
    get() {
      val quiz = if (quizTotal == 0) 1.0 else quizCorrect.toDouble() / quizTotal
      val checks = if (checkpointsTotal == 0) 1.0 else checkpointsPassed.toDouble() / checkpointsTotal
      return Math.round((0.6 * quiz + 0.4 * checks) * 100).toInt()
    }

  val level: String
    get() =
        when {
          score >= 80 -> "Ready to work solo"
          score >= 50 -> "Competent with guidance"
          else -> "Needs more practice"
        }

  fun toJson(): JSONObject =
      JSONObject()
          .put("lesson_id", lessonId)
          .put("lesson_title", lessonTitle)
          .put("learner", learner)
          .put("at", at)
          .put("steps", steps)
          .put("checkpoints_passed", checkpointsPassed)
          .put("checkpoints_total", checkpointsTotal)
          .put("why_asked", whyAsked)
          .put("quiz_correct", quizCorrect)
          .put("quiz_total", quizTotal)

  companion object {
    fun fromJson(o: JSONObject) =
        PassportEntry(
            lessonId = o.optString("lesson_id"),
            lessonTitle = o.optString("lesson_title"),
            learner = o.optString("learner", "Learner"),
            at = o.optLong("at"),
            steps = o.optInt("steps"),
            checkpointsPassed = o.optInt("checkpoints_passed"),
            checkpointsTotal = o.optInt("checkpoints_total"),
            whyAsked = o.optInt("why_asked"),
            quizCorrect = o.optInt("quiz_correct"),
            quizTotal = o.optInt("quiz_total"),
        )
  }
}

/** The learner's record of completed lessons, kept on the phone only. */
class SkillPassport(private val file: File) {
  fun entries(): List<PassportEntry> =
      runCatching { JSONArray(file.readText()).objs().map { PassportEntry.fromJson(it) } }
          .getOrDefault(emptyList())
          .sortedByDescending { it.at }

  fun add(entry: PassportEntry) {
    file.parentFile?.mkdirs()
    val all = JSONArray(entries().map { it.toJson() }).put(entry.toJson())
    file.writeText(all.toString(2))
  }
}
