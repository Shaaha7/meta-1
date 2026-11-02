// SafetyGuard - Checks AI-written repair steps before a technician hears them
//
// For machines outside the knowledge bases the repair steps come from the AI, so they are checked
// by fixed rules that the AI cannot talk its way around (the "Safety Stop" of the project plan):
//  - Work that needs a certified professional (high-voltage EV/hybrid systems, refrigerant, gas
//    supply, airbags, mains wiring inside the panel) → the coaching stops at that step with
//    "STOP: call a certified professional", and nothing after it is read out.
//  - The first step must make the machine safe (power off / isolate); if the AI forgot, Ustad adds it.
//  - Steps that work on something live or running get an explicit warning.
// Lessons drafted by the AI are checked with the same rules and the findings are shown to the
// expert before they publish.
//
// Plain Kotlin so it is unit-testable.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

data class SafetyReview(val steps: List<RepairStep>, val findings: List<String>) {
  val stopped: Boolean
    get() = steps.any { it.safetyStop }
}

object SafetyGuard {
  private class Rule(val name: String, val words: List<String>, val stop: Boolean, val message: String)

  private val rules =
      listOf(
          Rule(
              "high voltage",
              listOf("high voltage", "high-voltage", "hv battery", "hv cable", "orange cable", "traction battery", "service plug", "service disconnect"),
              stop = true,
              message = "High-voltage EV/hybrid system: only a certified EV technician with insulated tools may work on it.",
          ),
          Rule(
              "refrigerant",
              listOf("refrigerant", "regas", "re-gas", "recharge the gas", "recharge gas", "top up gas", "top up the gas", "r32", "r410", "r-410", "r22", "r-22", "r134", "r1234", "braze", "brazing", "vacuum pump", "recover the gas"),
              stop = true,
              message = "Refrigerant work needs a certified AC technician and recovery equipment.",
          ),
          Rule(
              "gas supply",
              listOf("gas line", "gas pipe", "gas valve", "lpg", "natural gas", "gas leak", "burner"),
              stop = true,
              message = "Gas supply work: stop, ventilate, and call a certified gas technician.",
          ),
          Rule(
              "airbag",
              listOf("airbag", "air bag", "srs module", "srs"),
              stop = true,
              message = "Airbag (SRS) parts can fire and injure: certified technician only.",
          ),
          Rule(
              "mains wiring",
              listOf("distribution board", "breaker panel", "fuse board", "mains wiring", "rewire", "rewiring", "live wire", "live terminal"),
              stop = true,
              message = "Mains wiring inside the panel: licensed electrician only.",
          ),
      )

  private val warnings =
      listOf(
          listOf("while running", "while it's running", "engine running", "with the power on", "while on", "power on and") to
              "This step is done with the machine running: keep hands, hair and tools clear of moving parts and fans.",
          listOf("fuel line", "fuel pump", "fuel injector", "petrol", "gasoline", "diesel") to
              "Fuel: no sparks, flames or smoking, work in a ventilated area, have an extinguisher nearby.",
          listOf("capacitor") to
              "Capacitors hold a charge after power-off: discharge safely before touching the terminals.",
          listOf("jack", "lift the car", "raise the car") to
              "Never work under a car held only by a jack: use axle stands on firm ground.",
          listOf("hot", "radiator cap", "coolant cap") to
              "Hot or pressurised: let it cool before opening; never open a hot radiator cap.",
      )

  private val isolation =
      listOf(
          "power off", "switch off", "turn off", "unplug", "isolate", "disconnect", "engine off", "key out", "breaker",
          "make it safe", "make sure it is off", "shut off", "shut down", "stop the engine", "remove the key")

  private fun hits(text: String, words: List<String>): Boolean {
    val t = " ${text.lowercase().replace(Regex("[^a-z0-9\\- ]"), " ")} "
    return words.any { t.contains(" $it ") || t.contains(" $it") && it.length >= 4 }
  }

  /** Reviews AI repair steps; [machine] helps decide what "make it safe" means. */
  fun review(steps: List<RepairStep>, machine: String = ""): SafetyReview {
    val findings = mutableListOf<String>()
    val out = mutableListOf<RepairStep>()
    if (steps.isEmpty()) return SafetyReview(steps, findings)

    if (!steps.first().safetyStop && !hits(steps.first().say, isolation)) {
      val car = MachineRouter.detect(machine) == MachineKind.CAR
      out +=
          RepairStep(
              say =
                  if (car) "Make it safe: engine off, key out, handbrake on, gloves and eye protection."
                  else "Make it safe: switch off and unplug or isolate the power, wear gloves and eye protection.",
              checkpoint = false,
              checkpointMustShow = "",
              safetyStop = false,
          )
      findings += "Added a safety isolation step: the AI's first step didn't make the machine safe."
    }

    val warned = mutableSetOf<String>()
    for (step in steps) {
      if (step.safetyStop) {
        out += step
        break
      }
      val rule = rules.firstOrNull { hits(step.say, it.words) }
      if (rule != null) {
        out += RepairStep(say = "STOP. ${rule.message} Call a certified professional.", checkpoint = false, checkpointMustShow = "", safetyStop = true)
        findings += "Stopped at \"${step.say}\": ${rule.name} work needs a certified professional."
        break
      }
      out += step
      warnings.forEach { (words, message) ->
        if (message !in warned && hits(step.say, words)) {
          warned += message
          out += RepairStep(say = "Careful: $message", checkpoint = false, checkpointMustShow = "", safetyStop = false)
          findings += "Added a warning: $message"
        }
      }
    }
    return SafetyReview(out, findings)
  }

  /** Same rules for an AI-drafted lesson; findings are shown to the expert, nothing is changed. */
  fun reviewLesson(lesson: Lesson): List<String> {
    // Only the instructions: safety notes legitimately mention hazards ("never touch orange cables").
    val steps = lesson.steps.map { RepairStep(it.say, it.checkpoint, it.mustShow, false) }
    val findings = review(steps, "${lesson.machine} ${lesson.title}").findings.toMutableList()
    lesson.steps.forEachIndexed { i, s ->
      rules.firstOrNull { hits("${s.title} ${s.say}", it.words) }?.let {
        val note = "Step ${i + 1} involves ${it.name} work: make sure the lesson says only certified technicians may do it."
        if (note !in findings) findings += note
      }
    }
    return findings.distinct()
  }
}
