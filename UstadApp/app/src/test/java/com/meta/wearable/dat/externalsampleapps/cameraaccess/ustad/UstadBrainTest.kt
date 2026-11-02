// UstadBrainTest - The knowledge-base diagnosis model and the data it runs on

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UstadBrainTest {
  // Gradle runs unit tests with the module (app/) as working directory.
  private fun kb(name: String) = KnowledgeBase.parse(File("src/main/assets/ustad/kb/$name.json").readText())

  private val car = kb("car")
  private val ac = kb("split_ac")

  @Test
  fun carKnowledgeBaseLoads() {
    assertEquals(25, car.symptoms.size)
    assertEquals(17, car.failures.size)
  }

  @Test
  fun everyFailurePointsAtAPartOfIts3dModel() {
    car.failures.forEach { assertTrue("${it.id} → ${it.part}", it.part in MachineLibrary.carParts) }
    ac.failures.forEach { assertTrue("${it.id} → ${it.part}", it.part in MachineLibrary.acParts) }
  }

  @Test
  fun everySymptomUsedByAFailureIsDefined() {
    listOf(car, ac).forEach { k ->
      k.failures.forEach { f -> f.likelihood.keys.forEach { s -> assertTrue("${f.id} uses unknown $s", s in k.symptoms) } }
    }
  }

  @Test
  fun everyFailureHasATestAFixAndASource() {
    (car.failures + ac.failures).forEach {
      assertTrue(it.id, it.test.isNotBlank() && it.fix.isNotEmpty() && it.sources.isNotEmpty() && it.causeChain.isNotEmpty())
    }
  }

  @Test
  fun rankingIsAProbabilityDistribution() {
    val ranked = car.rank(emptyMap())
    assertEquals(1.0, ranked.sumOf { it.probability }, 1e-9)
    assertEquals(ranked.sortedByDescending { it.probability }, ranked)
  }

  @Test
  fun flatTireEvidencePointsAtThePuncture() {
    val top = car.rank(mapOf("car_tire_flat" to true, "car_pulls_side" to true)).first()
    assertEquals("tire_puncture", top.failure.id)
    assertTrue(top.probability > 0.5)
  }

  @Test
  fun greenFluidAndTemperatureLightPointAtCoolant() {
    val top = car.rank(mapOf("car_green_fluid" to true, "car_temp_light" to true, "car_fluid_puddle" to true)).first()
    assertEquals("coolant_leak", top.failure.id)
  }

  @Test
  fun absentSymptomLowersAFailure() {
    val base = car.rank(emptyMap()).first { it.failure.id == "tire_puncture" }.probability
    val without = car.rank(mapOf("car_tire_flat" to false)).first { it.failure.id == "tire_puncture" }.probability
    assertTrue(without < base)
  }

  @Test
  fun ruledOutFailuresDisappear() {
    val ranked = car.rank(mapOf("car_tire_flat" to true), ruledOut = setOf("tire_puncture"))
    assertFalse(ranked.any { it.failure.id == "tire_puncture" })
    assertEquals(1.0, ranked.sumOf { it.probability }, 1e-9)
  }

  @Test
  fun nextQuestionAsksSomethingNew() {
    val evidence = mapOf("car_no_start" to true)
    val q = car.nextQuestion(evidence)
    assertNotNull(q)
    assertFalse(q!!.id in evidence)
    // Skipping it gives a different question.
    assertNotEquals(q.id, car.nextQuestion(evidence, skip = setOf(q.id))?.id)
  }

  @Test
  fun noQuestionOnceUstadIsSure() {
    var evidence = mapOf("car_tire_flat" to true, "car_pulls_side" to true)
    // Answer questions until the model is confident; it must stop asking.
    repeat(car.symptoms.size) {
      val q = car.nextQuestion(evidence) ?: return
      evidence = evidence + (q.id to ((car.failures.first { it.id == "tire_puncture" }.likelihood[q.id] ?: 0.0) > 0.5))
    }
    assertNull(car.nextQuestion(evidence))
  }

  private fun assertNotEquals(a: Any?, b: Any?) = assertFalse("$a == $b", a == b)
}
