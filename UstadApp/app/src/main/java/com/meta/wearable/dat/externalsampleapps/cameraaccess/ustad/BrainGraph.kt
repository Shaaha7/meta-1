// BrainGraph - The knowledge graph behind a diagnosis, for the "Ustad's brain" view
//
// symptom ──likelihood──▶ failure mode ──▶ internal part (the one that glows in the 3D view)
//
// Only the part of the graph that matters right now is returned: the evidence, the leading causes
// and the symptoms that best tell them apart. assets/ustad/kb_graph.html draws it.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import org.json.JSONArray
import org.json.JSONObject

fun KnowledgeBase.reasoningGraph(
    evidence: Evidence,
    ruledOut: Set<String> = emptySet(),
    partNames: Map<String, String> = emptyMap(),
    focusId: String? = null,
    maxFailures: Int = 5,
    maxSymptoms: Int = 10,
): JSONObject {
  // Leading causes; ones the evidence has all but ruled out (<1%) are left off, keeping at least 2.
  val ranked = rank(evidence, ruledOut).take(maxFailures).filterIndexed { i, r -> i < 2 || r.probability >= 0.01 }
  val top = ranked.map { it.failure }
  val evidenceIds = evidence.keys.filter { it in symptoms }
  val strong =
      top.flatMap { f -> f.likelihood.filter { it.value >= 0.5 && it.key in symptoms }.map { it.key to it.value } }
          .sortedByDescending { it.second }
          .map { it.first }
          .filter { it !in evidenceIds }
          .distinct()
  val symptomIds = (evidenceIds + strong).take(maxOf(maxSymptoms, evidenceIds.size))
  val focus = focusId ?: top.firstOrNull()?.id

  val sNodes =
      JSONArray(
          symptomIds.map { id ->
            JSONObject()
                .put("id", id)
                .put("label", symptoms.getValue(id).label)
                .put("state", when (evidence[id]) { true -> "present"; false -> "absent"; null -> "unknown" })
          })
  val fNodes =
      JSONArray(
          ranked.map { r ->
            JSONObject()
                .put("id", r.failure.id)
                .put("name", r.failure.name)
                .put("p", r.probability)
                .put("part", r.failure.part)
                .put("focus", r.failure.id == focus)
                .put("seen_on_model", modelHistory[r.failure.id] ?: 0)
          })
  val parts = top.map { it.part }.distinct()
  val pNodes = JSONArray(parts.map { JSONObject().put("id", it).put("name", partNames[it] ?: it) })
  val edges = JSONArray()
  symptomIds.forEach { s ->
    top.forEach { f ->
      val w = f.likelihood[s]
      if (w != null && w >= 0.2) {
        edges.put(JSONObject().put("from", s).put("to", f.id).put("w", w))
      }
    }
  }
  top.forEach { f -> edges.put(JSONObject().put("from", f.id).put("to", f.part).put("w", 1.0)) }
  return JSONObject()
      .put("title", title)
      .put("learned", learnedCases)
      .put("model_cases", modelCases)
      .put("symptoms", sNodes)
      .put("failures", fNodes)
      .put("parts", pNodes)
      .put("edges", edges)
}
