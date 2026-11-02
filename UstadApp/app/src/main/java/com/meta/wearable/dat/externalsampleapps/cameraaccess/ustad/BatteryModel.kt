// BatteryModel - Lithium-ion battery health: verdict, remaining life and the failing layer
//
// The project plan's battery module (Prompt 6): from measurements and what the camera saw, decide
// OK / WATCH / REPLACE / ISOLATE NOW, which internal layer is most likely degrading, the cause
// chain, and what to do. Rule-based and explainable, no AI in the verdict:
//  - End of life follows the NASA Ames Prognostics Center Li-ion aging experiments (18650 cells,
//    2.0 Ah rated): a cell is at end of life after a 30% capacity fade (2.0 Ah → 1.4 Ah).
//  - When the cycle count is unknown, remaining life uses a fade of ~0.16% of rated capacity per
//    cycle, about what those cells showed in accelerated room-temperature tests (e.g. B0005 lost
//    ~0.5 Ah over ~170 cycles). Real-world use is usually gentler, so the estimate is cautious.
//    This is a calibrated rule, not a model trained on the dataset (that is a planned next step).
//  - Safety signs (swelling, leak, burn marks, overheating, solvent smell) override everything.
//
// Plain Kotlin so it is unit-testable.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import kotlin.math.roundToInt

enum class BatteryLevel(val label: String) {
  OK("OK"),
  WATCH("WATCH"),
  REPLACE("REPLACE"),
  ISOLATE("ISOLATE NOW"),
}

data class BatteryInput(
    /** Rated capacity (Ah or mAh, any unit as long as [measured] uses the same). */
    val rated: Double? = null,
    val measured: Double? = null,
    /** State of health in %, if the charger/BMS reports it directly. */
    val sohPercent: Double? = null,
    val cycles: Int? = null,
    /** Internal resistance rise versus new, %. */
    val resistanceRisePercent: Double? = null,
    val temperatureC: Double? = null,
    val swelling: Boolean = false,
    val leak: Boolean = false,
    val burnMarks: Boolean = false,
    val smell: Boolean = false,
    val corrosion: Boolean = false,
)

data class BatteryVerdict(
    val level: BatteryLevel,
    /** 0..1, null when there is no capacity information. */
    val soh: Double?,
    /** Estimated cycles left until end of life (70%), null when unknown. */
    val remainingCycles: Int?,
    /** Part of the 3D battery model to highlight (assets/ustad/battery.html). */
    val part: String,
    val layerName: String,
    val causeChain: List<String>,
    val actions: List<String>,
    /** Why this verdict, in short phrases (shown and spoken). */
    val reasons: List<String>,
) {
  /** e.g. "REPLACE · health 64% · ~0 cycles left · anode". */
  val summary: String
    get() =
        listOfNotNull(
                level.label,
                soh?.let { "health ${(it * 100).roundToInt()}%" },
                remainingCycles?.let { "about $it cycles left" },
                layerName.takeIf { part != "b-cell" },
            )
            .joinToString(" · ")
}

object BatteryModel {
  /** NASA Ames Li-ion aging tests: end of life at 30% capacity fade. */
  const val END_OF_LIFE = 0.70

  /** Fade per cycle (fraction of rated) used when the battery's own history is unknown. */
  const val DEFAULT_FADE_PER_CYCLE = 0.0016

  val parts =
      linkedMapOf(
          "b-casing" to "Pack casing",
          "b-bms" to "Battery management board (BMS)",
          "b-terminals" to "Terminals & connectors",
          "b-cell" to "Weak cell",
          "b-cathode" to "Cathode",
          "b-separator" to "Separator",
          "b-anode" to "Anode",
          "b-electrolyte" to "Electrolyte",
      )

  fun soh(input: BatteryInput): Double? =
      input.sohPercent?.let { it / 100.0 }
          ?: if (input.rated != null && input.measured != null && input.rated > 0) input.measured / input.rated else null

  fun assess(input: BatteryInput): BatteryVerdict {
    val soh = soh(input)?.coerceIn(0.0, 1.2)
    val ir = input.resistanceRisePercent ?: 0.0
    val temp = input.temperatureC
    val hot = temp != null && temp >= 60
    val warm = temp != null && temp >= 45
    val reasons = mutableListOf<String>()

    // Remaining life: this battery's own fade rate when the cycle count is known.
    val remaining =
        if (soh == null) null
        else if (soh <= END_OF_LIFE) 0
        else {
          val fade = input.cycles?.takeIf { it > 0 }?.let { (1 - soh) / it }?.takeIf { it > 1e-5 } ?: DEFAULT_FADE_PER_CYCLE
          ((soh - END_OF_LIFE) / fade).roundToInt()
        }

    // Verdict: safety signs first.
    val danger = input.swelling || input.leak || input.burnMarks || hot || input.smell
    val level =
        when {
          danger -> BatteryLevel.ISOLATE
          (soh != null && soh < END_OF_LIFE) || ir >= 100 -> BatteryLevel.REPLACE
          (soh != null && soh < 0.80) || ir >= 50 || warm || input.corrosion -> BatteryLevel.WATCH
          else -> BatteryLevel.OK
        }
    if (input.swelling) reasons += "the pack is swelling"
    if (input.leak) reasons += "it is leaking"
    if (input.burnMarks) reasons += "there are burn or scorch marks"
    if (input.smell) reasons += "there is a sweet or solvent smell"
    if (hot) reasons += "it is very hot (${temp!!.roundToInt()}°C)"
    else if (warm) reasons += "it runs warm (${temp!!.roundToInt()}°C)"
    soh?.let {
      when {
        it < END_OF_LIFE -> reasons += "capacity ${(it * 100).roundToInt()}% is below the 70% end-of-life line"
        it < 0.80 -> reasons += "capacity is down to ${(it * 100).roundToInt()}%"
      }
    }
    if (ir >= 100) reasons += "internal resistance has doubled"
    else if (ir >= 50) reasons += "internal resistance is up ${ir.roundToInt()}%"
    if (input.corrosion) reasons += "the terminals are corroded"
    if (reasons.isEmpty()) reasons += if (soh == null) "no warning signs seen" else "capacity and resistance look healthy"

    // Most likely failing layer and why.
    val (part, chain) =
        when {
          input.burnMarks || hot ->
              "b-separator" to
                  listOf("The separator between anode and cathode breaks down", "Internal short circuit", "Heat builds up fast (thermal runaway risk)")
          input.swelling || input.leak || input.smell ->
              "b-electrolyte" to
                  listOf("The electrolyte decomposes (age, heat or overcharge)", "Gas is produced inside the cell", "The pack swells or leaks")
          ir >= 50 && !warm ->
              "b-anode" to
                  listOf("The SEI layer on the anode keeps growing", "Lithium is trapped and resistance rises", "Less capacity and voltage sag under load")
          warm && soh != null && soh < 0.9 ->
              "b-cathode" to listOf("Heat damages the cathode's crystal structure", "Fewer places to store lithium", "Capacity fades faster")
          soh != null && soh < 0.9 ->
              "b-anode" to listOf("Normal aging: the SEI layer on the anode slowly grows", "Usable lithium is lost each cycle", "Capacity fades")
          input.corrosion ->
              "b-terminals" to listOf("Moisture or electrolyte vapour reaches the terminals", "Corrosion raises contact resistance", "Heat at the connector and voltage drop")
          else -> "b-cell" to listOf("No layer shows a clear problem")
        }

    val actions =
        when (level) {
          BatteryLevel.ISOLATE ->
              listOf(
                  "Stop using and charging it now; disconnect it if that is safe.",
                  "Move it outdoors or into a fire-safe container, away from anything that burns.",
                  "Do not press, puncture or charge it. Call a battery-safety technician.")
          BatteryLevel.REPLACE ->
              listOf(
                  "Plan a replacement: it is past the 70% end-of-life line.",
                  "Until then avoid fast charging and full 100% charges.",
                  "Recycle the old pack at an approved battery recycler, never in household waste.")
          BatteryLevel.WATCH ->
              listOf(
                  "Re-test capacity and resistance in about 20 cycles.",
                  "Keep it cool; charge to 80–90% when you can.",
                  "Check for swelling, heat or smell at every service.")
          BatteryLevel.OK -> listOf("Healthy. Re-check at the next service (about 3 months).")
        }
    return BatteryVerdict(level, soh, remaining, part, parts[part] ?: part, chain, actions, reasons)
  }
}
