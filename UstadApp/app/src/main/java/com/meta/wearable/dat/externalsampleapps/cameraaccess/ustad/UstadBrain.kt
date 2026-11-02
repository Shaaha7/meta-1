// UstadBrain - Ustad's own diagnosis model for machines in the knowledge base
//
// The knowledge base (assets/ustad/kb/*.json) lists each machine's failure modes, how likely each
// symptom is under each failure, a confirmation test, repair steps and sources. The AI only
// reports which symptoms it can see; this class decides the cause:
//  - Bayesian scoring: P(failure | evidence) ∝ prior × Π P(symptom state | failure)
//  - Next question: the unanswered symptom whose answer is expected to reduce uncertainty most
//    (information gain), like an expert technician narrowing things down.
// No LLM is involved in ranking causes, so every answer is traceable to the knowledge base.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import android.content.Context
import kotlin.math.exp
import kotlin.math.ln
import org.json.JSONObject

data class KbSymptom(val id: String, val label: String, val question: String, val visual: Boolean)

data class KbSource(val name: String, val url: String)

data class KbFailure(
    val id: String,
    val part: String,
    val name: String,
    val prior: Double,
    val likelihood: Map<String, Double>,
    val causeChain: List<String>,
    val test: String,
    val expected: String,
    val fix: List<String>,
    val safety: String,
    val sources: List<KbSource>,
)

data class RankedFailure(val failure: KbFailure, val probability: Double)

/** What we know about each symptom: true = present, false = absent, missing = unknown. */
typealias Evidence = Map<String, Boolean>

class KnowledgeBase(val title: String, val symptoms: Map<String, KbSymptom>, val failures: List<KbFailure>) {

  companion object {
    /** Probability a symptom shows up even though this failure doesn't list it (noise/other causes). */
    private const val LEAK = 0.04

    fun load(context: Context, machine: String): KnowledgeBase =
        parse(context.assets.open("ustad/kb/$machine.json").bufferedReader().use { it.readText() })

    /** Parses a knowledge-base JSON file (kept separate from [load] so it can be unit-tested). */
    fun parse(text: String): KnowledgeBase {
      val json = JSONObject(text)
      val src = json.getJSONObject("sources")
      val sources = src.keys().asSequence().associateWith { k ->
        src.getJSONObject(k).let { KbSource(it.getString("name"), it.getString("url")) }
      }
      val sym = json.getJSONObject("symptoms")
      val symptoms = sym.keys().asSequence().associateWith { k ->
        sym.getJSONObject(k).let { KbSymptom(k, it.getString("label"), it.getString("question"), it.optBoolean("visual")) }
      }
      val arr = json.getJSONArray("failures")
      val failures = List(arr.length()) { i ->
        val f = arr.getJSONObject(i)
        val lk = f.getJSONObject("symptoms")
        KbFailure(
            id = f.getString("id"),
            part = f.getString("part"),
            name = f.getString("name"),
            prior = f.getDouble("prior"),
            likelihood = lk.keys().asSequence().associateWith { lk.getDouble(it) },
            causeChain = f.getJSONArray("cause_chain").let { a -> List(a.length()) { a.getString(it) } },
            test = f.getString("test"),
            expected = f.getString("expected"),
            fix = f.getJSONArray("fix").let { a -> List(a.length()) { a.getString(it) } },
            safety = f.optString("safety"),
            sources = f.getJSONArray("sources").let { a -> List(a.length()) { sources.getValue(a.getString(it)) } },
        )
      }
      return KnowledgeBase(json.getString("title"), symptoms, failures)
    }
  }

  private fun p(f: KbFailure, symptom: String) = f.likelihood[symptom] ?: LEAK

  /** Ranks every failure mode given the evidence, excluding ones already ruled out by a test. */
  fun rank(evidence: Evidence, ruledOut: Set<String> = emptySet()): List<RankedFailure> {
    val candidates = failures.filter { it.id !in ruledOut }
    val logs = candidates.map { f ->
      evidence.entries.fold(ln(f.prior)) { acc, (s, present) ->
        acc + ln(if (present) p(f, s) else 1 - p(f, s))
      }
    }
    val max = logs.maxOrNull() ?: return emptyList()
    val weights = logs.map { exp(it - max) }
    val total = weights.sum()
    return candidates.zip(weights).map { (f, w) -> RankedFailure(f, w / total) }.sortedByDescending { it.probability }
  }

  /** The unanswered symptom that best splits the remaining causes (max expected information gain). */
  fun nextQuestion(evidence: Evidence, ruledOut: Set<String> = emptySet(), skip: Set<String> = emptySet()): KbSymptom? {
    val current = rank(evidence, ruledOut)
    if (current.isEmpty() || current.first().probability > 0.9) return null
    val h0 = entropy(current.map { it.probability })
    return symptoms.values
        .filter { it.id !in evidence && it.id !in skip }
        .map { s ->
          val pYes = current.sumOf { it.probability * p(it.failure, s.id) }
          val hYes = entropy(rank(evidence + (s.id to true), ruledOut).map { it.probability })
          val hNo = entropy(rank(evidence + (s.id to false), ruledOut).map { it.probability })
          s to h0 - (pYes * hYes + (1 - pYes) * hNo)
        }
        .filter { it.second > 0.05 }
        .maxByOrNull { it.second }
        ?.first
  }

  private fun entropy(ps: List<Double>) = -ps.filter { it > 0 }.sumOf { it * ln(it) }
}
