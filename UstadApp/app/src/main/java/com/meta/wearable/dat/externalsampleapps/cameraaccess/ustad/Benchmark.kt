// Benchmark - "How accurate is Ustad?" measured on photos whose real cause you know
//
// For each test photo the technician picks the TRUE cause (the fix that was really done). Ustad
// inspects the photo exactly like a real job and its ranked causes are compared with the truth:
//  - top-1: the first cause Ustad named was right
//  - top-3: the right cause was among the first three
// The report (with each mistake) is what goes on the pitch slide. Plain Kotlin, unit-testable.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import kotlin.math.roundToInt

/** One test photo: the true cause and what Ustad answered (most likely first). */
data class BenchmarkCase(
    val name: String,
    val truth: String,
    val predictions: List<String>,
    val seconds: Double = 0.0,
    val error: String = "",
) {
  /** 1-based position of the true cause in Ustad's answer, null if missing. */
  val rank: Int?
    get() = predictions.indexOfFirst { Benchmark.same(it, truth) }.takeIf { it >= 0 }?.plus(1)
}

data class BenchmarkResult(val cases: List<BenchmarkCase>) {
  private val scored = cases.filter { it.error.isBlank() }
  val total: Int get() = scored.size
  val top1: Int get() = scored.count { it.rank == 1 }
  val top3: Int get() = scored.count { (it.rank ?: 99) <= 3 }
  val failed: Int get() = cases.size - scored.size

  fun percent(n: Int) = if (total == 0) 0 else (100.0 * n / total).roundToInt()

  /** Mean reciprocal rank: 1 = always first, 0.5 = usually second... */
  val mrr: Double
    get() = if (total == 0) 0.0 else scored.sumOf { c -> c.rank?.let { 1.0 / it } ?: 0.0 } / total

  val averageSeconds: Double
    get() = scored.map { it.seconds }.average().takeIf { !it.isNaN() } ?: 0.0

  fun report(title: String = "Ustad accuracy test"): String = buildString {
    appendLine(title.uppercase())
    appendLine("$total photo(s) of known faults${if (failed > 0) " ($failed could not be analysed)" else ""}")
    appendLine("Top-1 correct: $top1/$total (${percent(top1)}%)")
    appendLine("Top-3 correct: $top3/$total (${percent(top3)}%)")
    appendLine("Mean reciprocal rank: ${"%.2f".format(mrr)}")
    appendLine("Average time per photo: ${"%.1f".format(averageSeconds)} s")
    val misses = scored.filter { (it.rank ?: 99) > 1 }
    if (misses.isNotEmpty()) {
      appendLine()
      appendLine("Not first:")
      misses.forEach { c ->
        appendLine("  - ${c.name}: true = ${c.truth}; Ustad said ${c.predictions.take(3).joinToString(" > ").ifBlank { "nothing" }}" +
            (c.rank?.let { " (right answer was #$it)" } ?: " (not in Ustad's list)"))
      }
    }
    cases.filter { it.error.isNotBlank() }.forEach { appendLine("  ! ${it.name}: ${it.error}") }
  }
}

object Benchmark {
  /** Same cause? Exact id/name, or the words of the truth all appear in the prediction. */
  fun same(prediction: String, truth: String): Boolean {
    fun words(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.length > 2 }.toSet()
    if (prediction.equals(truth, ignoreCase = true)) return true
    val t = words(truth)
    return t.isNotEmpty() && words(prediction).containsAll(t)
  }
}
