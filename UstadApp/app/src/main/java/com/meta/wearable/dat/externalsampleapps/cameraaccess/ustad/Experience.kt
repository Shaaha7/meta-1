// Experience - What Ustad remembers: confirmed fixes, machine history and job reports
//
//  - CaseMemory: every fix a technician CONFIRMED with the prove-it test. UstadBrain learns from
//    these (KnowledgeBase.learned). It can be exported and imported as a JSON file so technicians
//    and teams share what they learned, which is the "shared knowledge base" without a server.
//  - MachineHistory: every visit per machine (make + model): what was seen, what was confirmed,
//    whether the repair was finished. Feeds the job report for the manager.
//
// Plain Kotlin + org.json + java.io so it is unit-testable without a device. Stored on the phone.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Same make and model → same key, however the AI spelled it ("TOYOTA", "Corolla 2019" …). */
fun machineKey(brand: String, model: String): String {
  val b = brand.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
  val m = model.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
  if (b.isBlank() && m.isBlank() || b in setOf("unknown", "n a", "none") && m in setOf("", "unknown", "n a", "none")) return ""
  return "$b|$m"
}

/** A fix confirmed by a technician's test: the ground truth Ustad learns from. */
data class ConfirmedCase(
    val kb: String,
    val machineKey: String,
    val failureId: String,
    val evidence: Map<String, Boolean>,
    val at: Long = System.currentTimeMillis(),
    val by: String = "",
) {
  fun toJson(): JSONObject =
      JSONObject()
          .put("kb", kb)
          .put("machine_key", machineKey)
          .put("failure_id", failureId)
          .put("evidence", JSONObject().apply { evidence.forEach { (k, v) -> put(k, v) } })
          .put("at", at)
          .put("by", by)

  companion object {
    fun fromJson(o: JSONObject): ConfirmedCase? {
      val kb = o.optString("kb")
      val failure = o.optString("failure_id")
      if (kb.isBlank() || failure.isBlank()) return null
      val ev = o.optJSONObject("evidence") ?: JSONObject()
      return ConfirmedCase(
          kb = kb,
          machineKey = o.optString("machine_key"),
          failureId = failure,
          evidence = ev.keys().asSequence().associateWith { ev.optBoolean(it) },
          at = o.optLong("at"),
          by = o.optString("by"),
      )
    }
  }
}

private fun JSONArray.objects(): List<JSONObject> = List(length()) { optJSONObject(it) ?: JSONObject() }

/** The brain's memory of confirmed fixes. */
class CaseMemory(private val file: File) {
  fun all(): List<ConfirmedCase> =
      runCatching { JSONArray(file.readText()).objects().mapNotNull { ConfirmedCase.fromJson(it) } }.getOrDefault(emptyList())

  fun cases(kb: String): List<ConfirmedCase> = all().filter { it.kb == kb }

  fun add(case: ConfirmedCase) = write(all() + case)

  /** Undo a confirmation made by mistake (removes the most recent matching case). */
  fun undo(kb: String, machineKey: String, failureId: String) {
    val all = all()
    val i = all.indexOfLast { it.kb == kb && it.machineKey == machineKey && it.failureId == failureId }
    if (i >= 0) write(all.filterIndexed { k, _ -> k != i })
  }

  /** Shareable file with everything this phone learned. */
  fun export(): String =
      JSONObject()
          .put("format", "ustad-experience")
          .put("version", 1)
          .put("cases", JSONArray(all().map { it.toJson() }))
          .toString(2)

  /** Merges another technician's export. Returns how many new cases were added. */
  fun import(text: String): Int {
    val incoming =
        JSONObject(text).optJSONArray("cases")?.objects()?.mapNotNull { ConfirmedCase.fromJson(it) }
            ?: throw IllegalArgumentException("Not an Ustad experience file")
    val all = all()
    val seen = all.map { Triple(it.at, it.failureId, it.machineKey) }.toMutableSet()
    val fresh = incoming.filter { seen.add(Triple(it.at, it.failureId, it.machineKey)) }
    if (fresh.isNotEmpty()) write(all + fresh)
    return fresh.size
  }

  private fun write(cases: List<ConfirmedCase>) {
    file.parentFile?.mkdirs()
    val tmp = File(file.path + ".tmp")
    tmp.writeText(JSONArray(cases.map { it.toJson() }).toString())
    tmp.renameTo(file)
  }
}

/** One visit to one machine. */
data class JobRecord(
    val id: String = UUID.randomUUID().toString(),
    val at: Long = System.currentTimeMillis(),
    val machine: String,
    val machineKey: String,
    val kind: String,
    val evidence: List<String> = emptyList(),
    val errorCode: String = "",
    val cause: String,
    val causeChain: List<String> = emptyList(),
    val confidence: Int = 0,
    val test: String = "",
    val steps: List<String> = emptyList(),
    val completed: Boolean = false,
    val safety: String = "",
    val sources: List<String> = emptyList(),
    val technician: String = "",
) {
  fun toJson(): JSONObject =
      JSONObject()
          .put("id", id)
          .put("at", at)
          .put("machine", machine)
          .put("machine_key", machineKey)
          .put("kind", kind)
          .put("evidence", JSONArray(evidence))
          .put("error_code", errorCode)
          .put("cause", cause)
          .put("cause_chain", JSONArray(causeChain))
          .put("confidence", confidence)
          .put("test", test)
          .put("steps", JSONArray(steps))
          .put("completed", completed)
          .put("safety", safety)
          .put("sources", JSONArray(sources))
          .put("technician", technician)

  /** Plain-text job report for the manager (shared from the app). */
  fun report(): String = buildString {
    val date = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date(at))
    appendLine("USTAD JOB REPORT")
    appendLine("Date: $date")
    if (technician.isNotBlank()) appendLine("Technician: $technician")
    appendLine("Machine: $machine")
    if (errorCode.isNotBlank()) appendLine("Error code: $errorCode")
    if (evidence.isNotEmpty()) {
      appendLine()
      appendLine("Evidence:")
      evidence.forEach { appendLine("  - $it") }
    }
    appendLine()
    appendLine("Confirmed cause: $cause${if (confidence > 0) " ($confidence% before the test)" else ""}")
    if (causeChain.isNotEmpty()) appendLine("Cause chain: ${causeChain.joinToString(" -> ")}")
    if (test.isNotBlank()) appendLine("Proved by: $test")
    if (safety.isNotBlank()) appendLine("Safety: $safety")
    if (steps.isNotEmpty()) {
      appendLine()
      appendLine("Repair steps${if (completed) " (completed)" else " (not completed)"}:")
      steps.forEachIndexed { i, s -> appendLine("  ${i + 1}. $s") }
    }
    if (sources.isNotEmpty()) {
      appendLine()
      appendLine("Sources: ${sources.joinToString("; ")}")
    }
    appendLine()
    append("Faces were blurred on the device; no video was stored.")
  }

  companion object {
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { optString(it) }

    fun fromJson(o: JSONObject) =
        JobRecord(
            id = o.optString("id"),
            at = o.optLong("at"),
            machine = o.optString("machine"),
            machineKey = o.optString("machine_key"),
            kind = o.optString("kind"),
            evidence = o.optJSONArray("evidence").strings(),
            errorCode = o.optString("error_code"),
            cause = o.optString("cause"),
            causeChain = o.optJSONArray("cause_chain").strings(),
            confidence = o.optInt("confidence"),
            test = o.optString("test"),
            steps = o.optJSONArray("steps").strings(),
            completed = o.optBoolean("completed"),
            safety = o.optString("safety"),
            sources = o.optJSONArray("sources").strings(),
            technician = o.optString("technician"),
        )
  }
}

/** Every visit, newest first, per phone. */
class MachineHistory(private val file: File) {
  fun all(): List<JobRecord> =
      runCatching { JSONArray(file.readText()).objects().map { JobRecord.fromJson(it) } }
          .getOrDefault(emptyList())
          .sortedByDescending { it.at }

  fun forMachine(machineKey: String): List<JobRecord> =
      if (machineKey.isBlank()) emptyList() else all().filter { it.machineKey == machineKey }

  fun delete(id: String) {
    val all = all()
    if (all.none { it.id == id }) return
    file.writeText(JSONArray(all.filter { it.id != id }.map { it.toJson() }).toString())
  }

  /** Adds the record, or replaces the one with the same id (e.g. when the repair is finished). */
  fun save(record: JobRecord) {
    file.parentFile?.mkdirs()
    val all = all().filter { it.id != record.id } + record
    val tmp = File(file.path + ".tmp")
    tmp.writeText(JSONArray(all.map { it.toJson() }).toString())
    tmp.renameTo(file)
  }
}
