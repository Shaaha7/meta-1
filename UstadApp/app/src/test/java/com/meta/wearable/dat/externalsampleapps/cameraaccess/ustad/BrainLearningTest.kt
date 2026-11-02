// BrainLearningTest - Ustad gets smarter from confirmed fixes, explains itself, shows its graph

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainLearningTest {
  private val ac = KnowledgeBase.parse(File("src/main/assets/ustad/kb/split_ac.json").readText())
  private val car = KnowledgeBase.parse(File("src/main/assets/ustad/kb/car.json").readText())
  private val corolla = machineKey("Toyota", "Corolla 2019")

  private fun p(kb: KnowledgeBase, ev: Evidence, id: String) = kb.rank(ev).first { it.failure.id == id }.probability

  @Test
  fun noCasesMeansTheKnowledgeBaseIsUnchanged() {
    assertSame(car, car.learned(emptyList(), corolla))
    // Cases for failures this knowledge base doesn't have are ignored.
    assertSame(car, car.learned(listOf(ConfirmedCase("car", corolla, "not_a_failure", emptyMap())), corolla))
  }

  @Test
  fun aConfirmedFixRaisesThatCauseNextTime() {
    val ev = mapOf("car_no_start" to true)
    val case = ConfirmedCase("car", corolla, "battery_terminals", ev)
    val before = p(car, ev, "battery_terminals")
    val after = p(car.learned(listOf(case), corolla), ev, "battery_terminals")
    assertTrue("$before → $after", after > before)
  }

  @Test
  fun sameModelHistoryCountsMoreThanOtherCars() {
    val ev = mapOf("car_no_start" to true)
    val case = ConfirmedCase("car", corolla, "battery_terminals", ev)
    val here = p(car.learned(listOf(case), corolla), ev, "battery_terminals")
    val elsewhere = p(car.learned(listOf(case), machineKey("Nissan", "Sunny")), ev, "battery_terminals")
    assertTrue("$here vs $elsewhere", here > elsewhere)
  }

  @Test
  fun repeatedFixesOnThisModelPutItFirst() {
    // The pitch promise: "Next time this model leaks, the cause is ranked #1 instantly."
    val ev = mapOf("car_no_start" to true)
    val baseTop = car.rank(ev).first().failure.id
    val target = "battery_terminals"
    assertFalse("needs a cause that is not already #1", baseTop == target)
    val cases = List(3) { ConfirmedCase("car", corolla, target, ev, at = it.toLong()) }
    val brain = car.learned(cases, corolla)
    assertEquals(target, brain.rank(ev).first().failure.id)
    assertEquals(3, brain.learnedCases)
    assertEquals(3, brain.modelCases)
    assertEquals(mapOf(target to 3), brain.modelHistory)
  }

  @Test
  fun learnedBrainIsStillAProbabilityDistribution() {
    val cases = car.failures.take(5).mapIndexed { i, f -> ConfirmedCase("car", "x|$i", f.id, mapOf("car_check_engine" to (i % 2 == 0))) }
    val ranked = car.learned(cases, "x|1").rank(mapOf("car_check_engine" to true))
    assertEquals(1.0, ranked.sumOf { it.probability }, 1e-9)
    car.learned(cases).failures.forEach { f -> f.likelihood.values.forEach { assertTrue(it in 0.01..0.99) } }
  }

  @Test
  fun symptomSeenWithAFixBecomesMoreLikelyForIt() {
    val ev = mapOf("car_sweet_smell" to true)
    val before = car.failures.first { it.id == "coolant_leak" }.likelihood["car_sweet_smell"] ?: 0.04
    val cases = List(4) { ConfirmedCase("car", "", "coolant_leak", ev, at = it.toLong()) }
    val after = car.learned(cases).failures.first { it.id == "coolant_leak" }.likelihood.getValue("car_sweet_smell")
    assertTrue("$before → $after", after > before)
  }

  @Test
  fun explanationShowsWhatSupportsAndWhatArguesAgainst() {
    val ev = mapOf("car_tire_flat" to true, "car_check_engine" to false)
    val why = car.explain("tire_puncture", ev)
    assertEquals(2, why.size)
    val flat = why.first { it.symptom.id == "car_tire_flat" }
    assertTrue(flat.weight > 1.5)
    assertEquals("strongly supports", flat.verdict)
    // A flat tire argues against an engine misfire.
    assertTrue(car.explain("misfire", mapOf("car_tire_flat" to true)).first().weight < 0)
  }

  @Test
  fun reasoningGraphConnectsEvidenceToCausesToParts() {
    val g = car.reasoningGraph(mapOf("car_green_fluid" to true, "car_temp_light" to true), partNames = MachineLibrary.carParts)
    val failures = g.getJSONArray("failures")
    assertEquals("coolant_leak", failures.getJSONObject(0).getString("id"))
    assertTrue(failures.getJSONObject(0).getBoolean("focus"))
    val symptoms = (0 until g.getJSONArray("symptoms").length()).map { g.getJSONArray("symptoms").getJSONObject(it) }
    assertEquals("present", symptoms.first { it.getString("id") == "car_green_fluid" }.getString("state"))
    val parts = (0 until g.getJSONArray("parts").length()).map { g.getJSONArray("parts").getJSONObject(it).getString("id") }
    assertTrue("c-radiator" in parts)
    val edges = (0 until g.getJSONArray("edges").length()).map { g.getJSONArray("edges").getJSONObject(it) }
    assertTrue(edges.any { it.getString("from") == "car_green_fluid" && it.getString("to") == "coolant_leak" })
    assertTrue(edges.any { it.getString("from") == "coolant_leak" && it.getString("to") == "c-radiator" })
    assertTrue(symptoms.size <= 10)
  }

  @Test
  fun acBrainLearnsTooDrainLineStory() {
    val drip = mapOf("water_drip" to true)
    val top = ac.rank(drip).first().failure.id
    val other = ac.rank(drip)[1].failure.id
    val ac1 = machineKey("LG", "S4-Q12JA3WF")
    val brain = ac.learned(List(3) { ConfirmedCase("split_ac", ac1, other, drip, at = it.toLong()) }, ac1)
    assertEquals(other, brain.rank(drip).first().failure.id)
    assertFalse(top == other)
  }

  @Test
  fun machineKeysIgnoreSpellingAndUnknowns() {
    assertEquals(machineKey("TOYOTA", "Corolla-2019"), machineKey("toyota", "corolla 2019"))
    assertEquals("", machineKey("", ""))
    assertEquals("", machineKey("Unknown", "unknown"))
  }

  @Test
  fun caseMemoryAddsUndoesExportsAndImports() {
    val dir = Files.createTempDirectory("brain").toFile()
    val mine = CaseMemory(File(dir, "a.json"))
    mine.add(ConfirmedCase("car", corolla, "battery_terminals", mapOf("car_no_start" to true), at = 1))
    mine.add(ConfirmedCase("car", corolla, "tire_puncture", mapOf("car_tire_flat" to true), at = 2))
    mine.add(ConfirmedCase("split_ac", "", "drain_clog", emptyMap(), at = 3))
    assertEquals(2, mine.cases("car").size)
    mine.undo("car", corolla, "tire_puncture")
    assertEquals(listOf("battery_terminals"), mine.cases("car").map { it.failureId })

    val theirs = CaseMemory(File(dir, "b.json"))
    theirs.add(ConfirmedCase("car", corolla, "coolant_leak", mapOf("car_green_fluid" to true), at = 9))
    assertEquals(2, theirs.import(mine.export()))
    assertEquals(0, theirs.import(mine.export())) // already merged
    assertEquals(3, theirs.all().size)
  }

  @Test
  fun machineHistoryAndJobReport() {
    val history = MachineHistory(File(Files.createTempDirectory("hist").toFile(), "h.json"))
    val job =
        JobRecord(
            machine = "Car · Toyota · Corolla 2019",
            machineKey = corolla,
            kind = "CAR",
            evidence = listOf("Car does not start / no power"),
            cause = "Corroded or loose battery terminals",
            causeChain = listOf("Crust on terminals", "Starter gets too little current", "No start"),
            confidence = 64,
            test = "Wiggle the clamps",
            steps = listOf("Negative off first", "Clean", "Refit"),
        )
    history.save(job)
    history.save(job.copy(completed = true))
    assertEquals(1, history.all().size)
    assertTrue(history.forMachine(corolla).single().completed)
    assertTrue(history.forMachine("").isEmpty())
    val report = history.all().single().report()
    assertTrue(report.contains("Confirmed cause: Corroded or loose battery terminals (64% before the test)"))
    assertTrue(report.contains("Repair steps (completed)"))
    assertTrue(report.contains("Faces were blurred"))
  }
}
