// BenchmarkTest - accuracy scoring for the pitch slide

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkTest {
  @Test
  fun scoresTopOneTopThreeAndMrr() {
    val r = BenchmarkResult(listOf(
        BenchmarkCase("a.jpg", "battery_terminals", listOf("battery_terminals", "battery12_weak"), 3.0),
        BenchmarkCase("b.jpg", "coolant_leak", listOf("head_gasket", "coolant_leak"), 5.0),
        BenchmarkCase("c.jpg", "tire_puncture", listOf("tire_worn", "alignment", "brake_pads_worn"), 4.0),
        BenchmarkCase("d.jpg", "misfire", emptyList(), error = "network"),
    ))
    assertEquals(3, r.total); assertEquals(1, r.failed)
    assertEquals(1, r.top1); assertEquals(2, r.top3)
    assertEquals(33, r.percent(r.top1)); assertEquals(67, r.percent(r.top3))
    assertEquals((1.0 + 0.5 + 0.0) / 3, r.mrr, 1e-9)
    assertEquals(4.0, r.averageSeconds, 1e-9)
    val report = r.report()
    assertTrue(report.contains("Top-3 correct: 2/3 (67%)"))
    assertTrue(report.contains("b.jpg: true = coolant_leak") && report.contains("(right answer was #2)"))
    assertTrue(report.contains("d.jpg: network"))
  }

  @Test
  fun freeTextMatchesForAiDiagnoses() {
    assertTrue(Benchmark.same("Clogged drain line / hose", "drain line"))
    assertTrue(Benchmark.same("Worn brake pads", "brake pads"))
    assertTrue(!Benchmark.same("Brake fluid low", "brake pads"))
  }
}
