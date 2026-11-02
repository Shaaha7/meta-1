// UstadBrain - Ustad's own diagnosis model for machines in the knowledge base
//
// The knowledge base (assets/ustad/kb/*.json) lists each machine's failure modes, how likely each
// symptom is under each failure, a confirmation test, repair steps and sources. The AI only
// reports which symptoms it can see; this class decides the cause:
//  - Bayesian scoring: P(failure | evidence) ∝ prior × Π P(symptom state | failure)
//  - Next question: the unanswered symptom whose answer is expected to reduce uncertainty most
//    (information gain), like an expert technician narrowing things down.
//  - Learning: every confirmed fix is a case. Following decision-theoretic troubleshooting
//    (Heckerman, Breese & Rommelse, Microsoft Research), the knowledge base counts as
//    KB_STRENGTH cases' worth of prior knowledge (Dirichlet / Beta pseudo-counts) and each confirmed
//    case adds to it. Fixes on the same make and model count MODEL_WEIGHT times, so "next time this
//    model leaks, the drain line is ranked #1".
//  - Explanation: how much each piece of evidence pushed a cause up or down (log-likelihood ratio).
// No LLM is involved in ranking causes, so every answer is traceable to the knowledge base and to
// the confirmed fixes it learned from.

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

/** How much one piece of evidence supports (weight > 0) or argues against (< 0) a cause. */
data class EvidenceWeight(val symptom: KbSymptom, val present: Boolean, val weight: Double) {
  val verdict: String
    get() =
        when {
          weight >= 1.5 -> "strongly supports"
          weight >= 0.4 -> "supports"
          weight > -0.4 -> "neutral"
          weight > -1.5 -> "argues against"
          else -> "strongly against"
        }
}

/** What we know about each symptom: true = present, false = absent, missing = unknown. */
typealias Evidence = Map<String, Boolean>

class KnowledgeBase(
    val title: String,
    val symptoms: Map<String, KbSymptom>,
    val failures: List<KbFailure>,
    /** Confirmed fixes this brain has learned from (0 = knowledge base only). */
    val learnedCases: Int = 0,
    /** How many of those were on the machine being inspected (same make and model). */
    val modelCases: Int = 0,
    /** Failure id → times confirmed on this exact make and model. */
    val modelHistory: Map<String, Int> = emptyMap(),
) {

  companion object {
    /** Probability a symptom shows up even though this failure doesn't list it (noise/other causes). */
    private const val LEAK = 0.04

    /** The knowledge base is worth this many confirmed cases (failure priors). */
    const val KB_STRENGTH = 20.0

    /** Pseudo-cases behind each symptom likelihood in the knowledge base. */
    const val LIKELIHOOD_STRENGTH = 5.0

    /** A confirmed fix on the same make and model counts this many times. */
    const val MODEL_WEIGHT = 4.0

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

  val sourceCount: Int
    get() = failures.flatMap { it.sources }.map { it.name }.distinct().size

  /**
   * A brain that has learned from [cases] (confirmed fixes for this knowledge base). The knowledge
   * base itself is never changed; learning is recomputed from the case list, so it can be audited,
   * shared between technicians, or undone.
   */
  fun learned(cases: List<ConfirmedCase>, machineKey: String = ""): KnowledgeBase {
    val known = cases.filter { c -> failures.any { it.id == c.failureId } }
    if (known.isEmpty()) return this
    val sameModel: (ConfirmedCase) -> Boolean = { machineKey.isNotBlank() && it.machineKey == machineKey }
    val weight: (ConfirmedCase) -> Double = { if (sameModel(it)) MODEL_WEIGHT else 1.0 }
    val totalPrior = failures.sumOf { it.prior }
    val totalWeight = known.sumOf(weight)
    val updated =
        failures.map { f ->
          val mine = known.filter { it.failureId == f.id }
          // Dirichlet update of the failure prior.
          val prior = (KB_STRENGTH * f.prior / totalPrior + mine.sumOf(weight)) / (KB_STRENGTH + totalWeight)
          // Beta update of P(symptom | failure) from what was observed when this failure was confirmed.
          val observed = mine.flatMap { it.evidence.keys }.toSet()
          val likelihood =
              (f.likelihood.keys + observed).associateWith { s ->
                val seen = mine.filter { s in it.evidence }
                val present = seen.count { it.evidence[s] == true }
                ((LIKELIHOOD_STRENGTH * p(f, s) + present) / (LIKELIHOOD_STRENGTH + seen.size)).coerceIn(0.01, 0.99)
              }
          f.copy(prior = prior, likelihood = likelihood)
        }
    return KnowledgeBase(
        title = title,
        symptoms = symptoms,
        failures = updated,
        learnedCases = known.size,
        modelCases = known.count(sameModel),
        modelHistory = known.filter(sameModel).groupingBy { it.failureId }.eachCount(),
    )
  }

  /**
   * Why [failureId] is ranked where it is: for each piece of evidence, the log-likelihood ratio of
   * that observation under this cause versus under the other causes (weighted by their priors).
   */
  fun explain(failureId: String, evidence: Evidence, ruledOut: Set<String> = emptySet()): List<EvidenceWeight> {
    val f = failures.firstOrNull { it.id == failureId } ?: return emptyList()
    val others = failures.filter { it.id != failureId && it.id !in ruledOut }
    val otherPrior = others.sumOf { it.prior }
    if (others.isEmpty() || otherPrior <= 0) return emptyList()
    return evidence.mapNotNull { (s, present) ->
          val symptom = symptoms[s] ?: return@mapNotNull null
          val pf = if (present) p(f, s) else 1 - p(f, s)
          val po = others.sumOf { o -> o.prior * (if (present) p(o, s) else 1 - p(o, s)) } / otherPrior
          EvidenceWeight(symptom, present, ln(pf / po))
        }
        .sortedByDescending { kotlin.math.abs(it.weight) }
  }

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
