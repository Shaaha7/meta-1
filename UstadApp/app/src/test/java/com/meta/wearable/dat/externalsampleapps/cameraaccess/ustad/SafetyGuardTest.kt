// SafetyGuardTest - AI repair steps are checked by fixed safety rules

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyGuardTest {
  private fun steps(vararg say: String) = say.map { RepairStep(it, false, "", false) }

  @Test
  fun safeStepsPassUnchanged() {
    val s = steps("Switch off and unplug the washing machine.", "Remove the filter cover.", "Clean the pump filter.")
    val r = SafetyGuard.review(s, "Washing machine")
    assertEquals(s, r.steps)
    assertTrue(r.findings.isEmpty())
    assertFalse(r.stopped)
  }

  @Test
  fun missingIsolationIsAdded() {
    val r = SafetyGuard.review(steps("Remove the front panel.", "Clean the coil."), "Fridge")
    assertTrue(r.steps.first().say.startsWith("Make it safe"))
    assertEquals(3, r.steps.size)
    val car = SafetyGuard.review(steps("Open the hood."), "Toyota Corolla car")
    assertTrue(car.steps.first().say.contains("engine off"))
  }

  @Test
  fun certifiedWorkStopsTheCoaching() {
    val r = SafetyGuard.review(
        steps("Turn off the AC at the breaker.", "Remove the cover.", "Recharge the gas with R410A.", "Close the cover."), "Split AC")
    assertTrue(r.stopped)
    assertTrue(r.steps.last().safetyStop)
    assertTrue(r.steps.last().say.startsWith("STOP"))
    assertFalse(r.steps.any { it.say.contains("Close the cover") }) // nothing after the stop is read out
    assertEquals(3, r.steps.size)
  }

  @Test
  fun evHighVoltageAndAirbagsStop() {
    assertTrue(SafetyGuard.review(steps("Key out.", "Unplug the orange cable from the motor."), "Tesla").stopped)
    assertTrue(SafetyGuard.review(steps("Disconnect the battery.", "Remove the airbag module."), "car").stopped)
  }

  @Test
  fun inverterAcIsNotHighVoltageWork() {
    assertFalse(SafetyGuard.review(steps("Switch off the inverter AC.", "Wash the filter."), "Inverter AC").stopped)
  }

  @Test
  fun hazardsGetOneWarningEach() {
    val r = SafetyGuard.review(
        steps("Engine off.", "Jack up the car.", "Remove the wheel.", "Jack it down."), "car")
    assertEquals(1, r.steps.count { it.say.startsWith("Careful") && it.say.contains("axle stands") })
  }

  @Test
  fun builtInLessonsAreClean() {
    File("src/main/assets/ustad/lessons").listFiles()!!.forEach {
      val lesson = parseLesson(JSONObject(it.readText()), builtIn = true)
      assertEquals(it.name, emptyList<String>(), SafetyGuard.reviewLesson(lesson).filter { f -> f.contains("certified") })
    }
  }

  @Test
  fun aiLessonWithRefrigerantWorkIsFlaggedForTheExpert() {
    val lesson = Lesson(title = "AC regas", machine = "Split AC", steps = listOf(
        LessonStep("Off", "Switch off at the breaker."), LessonStep("Gas", "Connect the gauges and recharge the gas.")))
    assertTrue(SafetyGuard.reviewLesson(lesson).any { it.contains("refrigerant") })
  }
}
