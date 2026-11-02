// DashboardTest - Badges, the supervisor's numbers, and sharing records between phones

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardTest {
  private fun e(learner: String, lesson: String, quiz: Int, at: Long) = PassportEntry(lesson, "Lesson $lesson", learner, at, 5, 1, 1, 0, quiz, 3)

  @Test
  fun badgesNeedEightyAndCountOncePerLesson() {
    // quiz 3/3 + checkpoint 1/1 = 100; 2/3 + 1/1 = 80; 1/3 + 1/1 = 60
    val entries = listOf(e("Ali", "battery", 1, 1), e("Ali", "battery", 3, 2), e("Ali", "battery", 3, 3), e("Sara", "tires", 2, 4), e("Raj", "tires", 1, 5))
    val badges = Dashboard.badges(entries)
    assertEquals(listOf("Sara" to "Lesson tires", "Ali" to "Lesson battery"), badges.map { it.learner to it.lesson })
  }

  @Test
  fun dashboardNumbers() {
    val entries = listOf(e("Ali", "battery", 3, 1), e("Raj", "tires", 0, 2))
    val jobs = listOf(
        JobRecord(machine = "Corolla", machineKey = "k", kind = "CAR", cause = "Battery terminals", completed = true),
        JobRecord(machine = "Corolla", machineKey = "k", kind = "CAR", cause = "Battery terminals"),
        JobRecord(machine = "LG AC", machineKey = "a", kind = "SPLIT_AC", cause = "Drain clogged", completed = true))
    val d = Dashboard.build(entries, jobs, casesLearned = 7, now = 0)
    val t = d.getJSONObject("totals")
    assertEquals(2, t.getInt("learners")); assertEquals(1, t.getInt("badges")); assertEquals(3, t.getInt("jobs"))
    assertEquals(2, t.getInt("jobs_fixed")); assertEquals(7, t.getInt("brain_cases"))
    assertEquals("Ali", d.getJSONArray("learners").getJSONObject(0).getString("name")) // best average first
    assertEquals("Lesson tires", d.getJSONArray("learners").getJSONObject(1).getJSONArray("needs_help").getString(0))
    assertEquals("Battery terminals", d.getJSONArray("top_causes").getJSONObject(0).getString("cause"))
    assertEquals(2, d.getJSONObject("machines").getInt("CAR"))
  }

  @Test
  fun recordsTravelFromTechnicianToSupervisor() {
    val dir = Files.createTempDirectory("dash").toFile()
    val techPassport = SkillPassport(File(dir, "p1.json")); val techJobs = MachineHistory(File(dir, "h1.json"))
    techPassport.add(e("Ali", "battery", 3, 1)); techJobs.save(JobRecord(machine = "Corolla", machineKey = "k", kind = "CAR", cause = "X"))
    val supPassport = SkillPassport(File(dir, "p2.json")); val supJobs = MachineHistory(File(dir, "h2.json"))
    val file = Dashboard.exportRecords(techPassport, techJobs)
    assertEquals(1 to 1, Dashboard.importRecords(file, supPassport, supJobs))
    assertEquals(0 to 0, Dashboard.importRecords(file, supPassport, supJobs)) // no duplicates
  }
}
