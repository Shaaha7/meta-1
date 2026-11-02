// LessonTest - Lessons (built-in and AI-drafted), the lesson store and the Skill Passport

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonTest {
  private val builtIns =
      File("src/main/assets/ustad/lessons").listFiles { f -> f.extension == "json" }!!.map {
        parseLesson(JSONObject(it.readText()), builtIn = true)
      }

  @Test
  fun builtInLessonsAreCompleteAndPointAtRealCarParts() {
    assertTrue(builtIns.size >= 2)
    builtIns.forEach { l ->
      assertEquals(l.title, MachineKind.CAR, l.kind)
      assertTrue(l.published)
      assertTrue(l.steps.size in 4..10)
      assertTrue(l.quiz.size >= 3)
      l.steps.forEach { s ->
        assertTrue("${l.title}: ${s.partId}", s.partId in MachineLibrary.carParts)
        assertTrue(s.say.isNotBlank() && s.why.isNotBlank())
        if (s.checkpoint) assertTrue("checkpoint needs must_show: ${s.say}", s.mustShow.isNotBlank())
      }
      assertTrue(l.steps.any { it.checkpoint })
      l.quiz.forEach { q -> assertTrue(q.answer in q.options.indices) }
    }
  }

  @Test
  fun saveAndLoadKeepsEverything() {
    val l = builtIns.first().copy(builtIn = false, published = false)
    val back = parseLesson(l.toJson())
    assertEquals(l.copy(partsJson = back.partsJson), back)
  }

  @Test
  fun aiDraftIsParsedLeniently() {
    val raw =
        JSONObject(
            """
            {"title":"Replace wiper blade","machine":"Toyota Camry car",
             "steps":[{"say":"Lift the wiper arm","part_id":"c-body","safety":"none"},
                      {"say":""},
                      {"instruction":"Press the release tab","checkpoint":true,"must_show":"tab pressed"}],
             "quiz":[{"question":"Why lift gently?","options":["Arm can snap onto glass","No reason"],"answer":7},
                     {"question":"Bad","options":["only one"]}]}
            """)
    val l = parseLesson(raw)
    assertEquals(MachineKind.CAR, l.kind)
    assertEquals(2, l.steps.size) // blank step dropped, "instruction" accepted as "say"
    assertEquals("", l.steps[0].safety) // "none" means no hazard
    assertEquals(1, l.quiz.size) // question with one option dropped
    assertEquals(1, l.quiz[0].answer) // out-of-range answer clamped
    assertFalse(l.published) // AI drafts are never published until an expert approves
  }

  @Test
  fun lessonStoreSavesListsAndDeletes() {
    val dir = Files.createTempDirectory("lessons").toFile()
    val store = LessonStore(dir)
    val a = builtIns[0].copy(id = "a", builtIn = false, createdAt = 1)
    val b = builtIns[1].copy(id = "b", builtIn = false, createdAt = 2)
    store.save(a)
    store.save(b)
    assertEquals(listOf("b", "a"), store.list().map { it.id }) // newest first
    store.save(a.copy(title = "Edited"))
    assertEquals("Edited", store.list().first { it.id == "a" }.title)
    store.delete("b")
    assertEquals(listOf("a"), store.list().map { it.id })
  }

  @Test
  fun passportScoring() {
    fun entry(cp: Int, ct: Int, qc: Int, qt: Int) = PassportEntry("l", "L", "Ali", 0, 5, cp, ct, 2, qc, qt)
    assertEquals(80, entry(1, 2, 3, 3).score) // 0.6*1 + 0.4*0.5
    assertEquals("Ready to work solo", entry(1, 2, 3, 3).level)
    assertEquals(100, entry(0, 0, 0, 0).score) // nothing to fail
    assertEquals(40, entry(2, 2, 0, 3).score)
    assertEquals("Needs more practice", entry(2, 2, 0, 3).level)
    assertEquals("Competent with guidance", entry(1, 2, 2, 3).level) // 0.4 + 0.2 = 60
  }

  @Test
  fun passportKeepsEntries() {
    val file = File(Files.createTempDirectory("passport").toFile(), "p.json")
    val passport = SkillPassport(file)
    assertTrue(passport.entries().isEmpty())
    passport.add(PassportEntry("l1", "Battery", "Ali", 1, 9, 2, 2, 1, 3, 3))
    passport.add(PassportEntry("l2", "Tires", "Ali", 2, 7, 1, 2, 0, 2, 3))
    assertEquals(listOf("l2", "l1"), passport.entries().map { it.lessonId })
  }
}
