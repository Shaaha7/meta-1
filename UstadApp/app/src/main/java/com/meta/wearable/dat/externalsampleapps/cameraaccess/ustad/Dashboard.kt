// Dashboard - The supervisor's view: who learned what, skill badges, jobs and what the brain learned
//
// Everything is kept on each phone. A technician shares their records (Skill Passport + job
// history) as a file; the supervisor imports the files from the team and sees everyone together.
// The dashboard can also be shared as one self-contained HTML page for a laptop.
//
// Plain Kotlin + org.json so it is unit-testable.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import org.json.JSONArray
import org.json.JSONObject

/** A skill badge: a lesson passed with a score of 80+ ("Ready to work solo"). */
data class Badge(val learner: String, val lesson: String, val score: Int, val at: Long)

object Dashboard {
  const val BADGE_SCORE = 80

  fun badges(entries: List<PassportEntry>): List<Badge> =
      entries
          .filter { it.score >= BADGE_SCORE }
          .groupBy { it.learner.lowercase() to it.lessonId }
          .map { (_, e) -> e.maxByOrNull { it.score }!!.let { Badge(it.learner, it.lessonTitle, it.score, it.at) } }
          .sortedByDescending { it.at }

  /** The data the dashboard page draws. */
  fun build(entries: List<PassportEntry>, jobs: List<JobRecord>, casesLearned: Int, now: Long = System.currentTimeMillis()): JSONObject {
    val badges = badges(entries)
    val learners =
        entries.groupBy { it.learner.ifBlank { "Learner" } }.map { (name, e) ->
          JSONObject()
              .put("name", name)
              .put("lessons", e.map { it.lessonId }.distinct().size)
              .put("attempts", e.size)
              .put("average", e.map { it.score }.average().toInt())
              .put("best", e.maxOf { it.score })
              .put("badges", JSONArray(badges.filter { it.learner.equals(name, true) }.map { it.lesson }))
              .put("needs_help", JSONArray(e.filter { it.score < 50 }.map { it.lessonTitle }.distinct()))
              .put("last", e.maxOf { it.at })
        }.sortedByDescending { it.getInt("average") }
    val causes = jobs.groupingBy { it.cause }.eachCount().entries.sortedByDescending { it.value }.take(6)
    val kinds = jobs.groupingBy { it.kind.ifBlank { "OTHER" } }.eachCount()
    return JSONObject()
        .put("generated", now)
        .put("totals", JSONObject()
            .put("learners", learners.size)
            .put("lessons_done", entries.size)
            .put("badges", badges.size)
            .put("jobs", jobs.size)
            .put("jobs_fixed", jobs.count { it.completed })
            .put("brain_cases", casesLearned))
        .put("learners", JSONArray(learners))
        .put("badges", JSONArray(badges.map { JSONObject().put("learner", it.learner).put("lesson", it.lesson).put("score", it.score).put("at", it.at) }))
        .put("top_causes", JSONArray(causes.map { JSONObject().put("cause", it.key).put("count", it.value) }))
        .put("machines", JSONObject().apply { kinds.forEach { (k, v) -> put(k, v) } })
        .put("recent_jobs", JSONArray(jobs.sortedByDescending { it.at }.take(10).map {
          JSONObject().put("at", it.at).put("machine", it.machine).put("cause", it.cause).put("fixed", it.completed).put("technician", it.technician)
        }))
  }

  /** The records a technician shares with their supervisor. */
  fun exportRecords(passport: SkillPassport, history: MachineHistory): String =
      JSONObject()
          .put("format", "ustad-records")
          .put("version", 1)
          .put("passport", JSONArray(passport.entries().map { it.toJson() }))
          .put("jobs", JSONArray(history.all().map { it.toJson() }))
          .toString(2)

  /** Merges a technician's records file. Returns (passport entries, jobs) added. */
  fun importRecords(text: String, passport: SkillPassport, history: MachineHistory): Pair<Int, Int> {
    val o = JSONObject(text)
    require(o.optString("format") == "ustad-records") { "Not an Ustad records file" }
    val have = passport.entries().map { Triple(it.learner, it.lessonId, it.at) }.toMutableSet()
    var p = 0
    o.optJSONArray("passport")?.let { a ->
      for (i in 0 until a.length()) {
        val e = PassportEntry.fromJson(a.getJSONObject(i))
        if (have.add(Triple(e.learner, e.lessonId, e.at))) { passport.add(e); p++ }
      }
    }
    val jobIds = history.all().map { it.id }.toSet()
    var j = 0
    o.optJSONArray("jobs")?.let { a ->
      for (i in 0 until a.length()) {
        val r = JobRecord.fromJson(a.getJSONObject(i))
        if (r.id.isNotBlank() && r.id !in jobIds) { history.save(r); j++ }
      }
    }
    return p to j
  }
}
