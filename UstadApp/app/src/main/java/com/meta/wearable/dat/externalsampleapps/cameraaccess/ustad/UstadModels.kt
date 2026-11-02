// UstadModels - Diagnosis and repair data returned by the AI
//
// Mirrors the JSON contracts of the Ustad prompts (Look & Listen + Diagnose & Reveal, and the
// Repair Coach). Parsing is lenient: missing fields fall back to empty values so a partial answer
// still renders.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import org.json.JSONArray
import org.json.JSONObject

data class Machine(val type: String, val brand: String, val model: String, val confidence: Double)

data class Symptom(val symptom: String, val location: String, val confidence: Double)

data class Diagnosis(
    val rank: Int,
    val partId: String,
    val partName: String,
    val confidence: Double,
    val causeChain: List<String>,
    val confirmTest: String,
    val expectedIfTrue: String,
)

data class Safety(val hazard: String, val stopAndCallPro: Boolean)

data class Inspection(
    val machine: Machine,
    val symptoms: List<Symptom>,
    val diagnoses: List<Diagnosis>,
    val safety: Safety,
    val voiceSummary: String,
    val needBetterView: Boolean,
    val userInstruction: String,
    val faultFound: Boolean,
    /** Raw JSON layout of the machine's internal parts, drawn by the generic 3D viewer. */
    val internalPartsJson: String,
    /** Visual symptoms the AI reported for knowledge-base machines: id → present/absent. */
    val observed: Map<String, Boolean>,
    val errorCode: String,
    /** Numbers read off a battery label or charger screen, e.g. rated → "10Ah", health → "82%". */
    val batteryReadings: Map<String, String> = emptyMap(),
)

data class RepairStep(
    val say: String,
    val checkpoint: Boolean,
    val checkpointMustShow: String,
    val safetyStop: Boolean,
)

/** Models write "none", "N/A" or "no hazard" instead of leaving a field empty. */
private fun String.isNoneLike(): Boolean =
    trim().lowercase().trimEnd('.') in setOf("", "none", "n/a", "na", "no", "no hazard", "no hazards", "null")

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else List(length()) { optString(it) }.filter { it.isNotBlank() }

private inline fun <T> JSONArray?.objects(map: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else List(length()) { map(optJSONObject(it) ?: JSONObject()) }

fun parseInspection(json: JSONObject): Inspection {
  val m = json.optJSONObject("machine") ?: JSONObject()
  val s = json.optJSONObject("safety") ?: JSONObject()
  return Inspection(
      machine =
          Machine(
              type = m.optString("type"),
              brand = m.optString("brand"),
              model = m.optString("model"),
              confidence = m.optDouble("confidence", 0.0),
          ),
      symptoms =
          json.optJSONArray("symptoms").objects {
            Symptom(it.optString("symptom"), it.optString("location"), it.optDouble("confidence", 0.0))
          },
      diagnoses =
          json
              .optJSONArray("diagnoses")
              .objects {
                Diagnosis(
                    rank = it.optInt("rank"),
                    partId = it.optString("part_id"),
                    partName = it.optString("part_name"),
                    confidence = it.optDouble("confidence", 0.0),
                    causeChain = it.optJSONArray("cause_chain").strings(),
                    confirmTest = it.optString("confirm_test"),
                    expectedIfTrue = it.optString("expected_result_if_true"),
                )
              }
              .sortedBy { it.rank },
      safety = Safety(s.optString("hazard").takeUnless { it.isNoneLike() }.orEmpty(), s.optBoolean("stop_and_call_pro", false)),
      voiceSummary = json.optString("voice_summary"),
      needBetterView = json.optBoolean("need_better_view", false),
      userInstruction = json.optString("user_instruction"),
      faultFound = json.optBoolean("fault_found", true),
      internalPartsJson = json.optJSONArray("internal_parts")?.toString() ?: "[]",
      observed = json.optJSONObject("kb_symptoms")?.let { o ->
        o.keys().asSequence().mapNotNull { k ->
          when (o.optString(k).lowercase()) { "present" -> k to true; "absent" -> k to false; else -> null }
        }.toMap()
      } ?: emptyMap(),
      errorCode = json.optString("error_code").takeUnless { it.isNoneLike() }.orEmpty(),
      batteryReadings =
          json.optJSONObject("battery_readings")?.let { o ->
            o.keys().asSequence().associateWith { o.optString(it) }.filterValues { !it.isNoneLike() }
          } ?: emptyMap(),
  )
}

fun parseRepairSteps(json: JSONObject): List<RepairStep> =
    json.optJSONArray("steps").objects {
      RepairStep(
          say = it.optString("say"),
          checkpoint = it.optBoolean("checkpoint", false),
          checkpointMustShow = it.optString("checkpoint_must_show"),
          safetyStop = it.optBoolean("safety_stop", false),
      )
    }
