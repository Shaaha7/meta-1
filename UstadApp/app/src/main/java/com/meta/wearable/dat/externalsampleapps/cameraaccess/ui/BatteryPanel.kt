// BatteryPanel - Battery module: lithium pack health, the weak layer in 3D, and what to do
//
// Filled from the photo (swelling, leak, burn marks, corrosion, numbers printed on the label or a
// charger screen) and from what the technician measures (capacity, cycles, resistance, temperature).
// BatteryModel decides OK / WATCH / REPLACE / ISOLATE NOW; no AI is involved in the verdict.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.BatteryInput
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.BatteryLevel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.BatteryModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.Inspection
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.JobRecord
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.MachineKind
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.machineKey

private fun number(text: String?): Double? = text?.let { Regex("""\d+(\.\d+)?""").find(it.replace(',', '.'))?.value?.toDoubleOrNull() }

/** Capacity in Ah whatever the label said ("10Ah", "10000 mAh"). */
private fun ampHours(text: String?): Double? {
  val n = number(text) ?: return null
  return if (text!!.lowercase().contains("mah") || n > 500) n / 1000 else n
}

@Composable
fun BatteryPanel(insp: Inspection?, say: (String) -> Unit) {
  val context = LocalContext.current
  val data = remember { UstadData(context) }
  val seen = insp?.observed.orEmpty()
  val readings = insp?.batteryReadings.orEmpty()
  fun reading(vararg keys: String) = keys.firstNotNullOfOrNull { k -> readings.entries.firstOrNull { it.key.contains(k, ignoreCase = true) }?.value }

  var rated by remember { mutableStateOf(ampHours(reading("rated", "capacity"))?.toString().orEmpty()) }
  var measured by remember { mutableStateOf(ampHours(reading("measured", "actual", "full"))?.toString().orEmpty()) }
  var health by remember { mutableStateOf(number(reading("health", "soh"))?.toString().orEmpty()) }
  var cycles by remember { mutableStateOf(number(reading("cycle"))?.toInt()?.toString().orEmpty()) }
  var resistance by remember { mutableStateOf("") }
  var temperature by remember { mutableStateOf(number(reading("temp"))?.toString().orEmpty()) }
  var swelling by remember { mutableStateOf(seen["battery_swelling"] == true) }
  var leak by remember { mutableStateOf(seen["battery_leak"] == true) }
  var burn by remember { mutableStateOf(seen["battery_burn"] == true) }
  var smell by remember { mutableStateOf(false) }
  var corrosion by remember { mutableStateOf(seen["battery_corrosion"] == true) }

  val verdict =
      BatteryModel.assess(
          BatteryInput(
              rated = rated.toDoubleOrNull(),
              measured = measured.toDoubleOrNull(),
              sohPercent = health.toDoubleOrNull(),
              cycles = cycles.toIntOrNull(),
              resistanceRisePercent = resistance.toDoubleOrNull(),
              temperatureC = temperature.toDoubleOrNull(),
              swelling = swelling,
              leak = leak,
              burnMarks = burn,
              smell = smell,
              corrosion = corrosion,
          ))
  val color =
      when (verdict.level) {
        BatteryLevel.OK -> Ok
        BatteryLevel.WATCH -> Color(0xFFFBBF24)
        BatteryLevel.REPLACE -> Color(0xFFFF8A3D)
        BatteryLevel.ISOLATE -> Fault
      }
  val machine = insp?.let { listOf(it.machine.type, it.machine.brand, it.machine.model).filter { s -> s.isNotBlank() }.joinToString(" · ") }
      ?: "Lithium battery pack"

  LaunchedEffect(verdict.level, verdict.part) {
    say("${verdict.level.label}. ${verdict.reasons.first().replaceFirstChar { it.uppercase() }}. ${verdict.actions.first()}")
  }

  LensMirror("🔋 ${verdict.level.label}", verdict.actions.first(), verdict.summary)

  UstadCard("🔋 Battery health", border = if (verdict.level == BatteryLevel.ISOLATE) Fault else Hud) {
    Text(machine, color = Color.White, fontWeight = FontWeight.Bold)
    Text(verdict.level.label, color = color, fontSize = 28.sp, fontWeight = FontWeight.Black)
    Text(verdict.summary, color = Color.White, fontSize = 14.sp)
    verdict.reasons.forEach { Text("• $it", color = Muted, fontSize = 13.sp) }
  }

  UstadCard("Inside the pack") {
    ExplodedView(
        partIds = if (verdict.part == "b-cell") listOf("b-cell") else listOf("b-cell", verdict.part),
        page = MachineKind.BATTERY.page,
        height = 320.dp,
    )
    Text("Failing layer: ${verdict.layerName}", color = color, fontSize = 13.sp)
  }

  UstadCard("Why") {
    verdict.causeChain.forEachIndexed { i, step ->
      UstadBody(step)
      if (i < verdict.causeChain.lastIndex) Text("▼", color = Fault)
    }
  }

  UstadCard(if (verdict.level == BatteryLevel.ISOLATE) "⚠ Do this now" else "What to do", border = if (verdict.level == BatteryLevel.ISOLATE) Fault else Color(0xFF172A4D)) {
    verdict.actions.forEachIndexed { i, a -> UstadBody("${i + 1}. $a") }
  }

  UstadCard("Measurements") {
    Text("From the label, the charger/BMS app, or a capacity tester. Leave blank what you don't know.", color = Muted, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      NumberField(rated, { rated = it }, "Rated (Ah)", Modifier.weight(1f))
      NumberField(measured, { measured = it }, "Measured (Ah)", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      NumberField(health, { health = it }, "Health %", Modifier.weight(1f))
      NumberField(cycles, { cycles = it }, "Cycles", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      NumberField(resistance, { resistance = it }, "Resistance rise %", Modifier.weight(1f))
      NumberField(temperature, { temperature = it }, "Temperature °C", Modifier.weight(1f))
    }
  }

  UstadCard("What you see / smell") {
    Toggle("Swelling or bulging", swelling) { swelling = it }
    Toggle("Leak or wet residue", leak) { leak = it }
    Toggle("Burn or scorch marks", burn) { burn = it }
    Toggle("Sweet or solvent smell", smell) { smell = it }
    Toggle("Corroded terminals", corrosion) { corrosion = it }
    if (insp != null) Text("Pre-filled from the photo · check and correct", color = Muted, fontSize = 12.sp)
  }

  OutlinedButton(
      onClick = {
        val job =
            JobRecord(
                machine = machine,
                machineKey = insp?.let { machineKey(it.machine.brand, it.machine.model) }.orEmpty(),
                kind = MachineKind.BATTERY.name,
                evidence = verdict.reasons,
                cause = "${verdict.level.label}: ${verdict.layerName}",
                causeChain = verdict.causeChain,
                steps = verdict.actions,
                safety = if (verdict.level == BatteryLevel.ISOLATE) "Isolate the pack: fire risk" else "",
                sources = listOf("Ustad battery model (end-of-life line from the NASA Ames Li-ion aging tests: 30% capacity fade)"),
                technician = data.learnerName,
            )
        data.history.save(job)
        shareText(context, "Ustad battery report", job.report())
      },
  ) { Text("Save & share battery report", color = Color.White) }
  Text(
      "Rule-based model: the end-of-life line (70% capacity) follows NASA's Li-ion aging tests; " +
          "remaining cycles use this pack's own fade when the cycle count is known.",
      color = Muted,
      fontSize = 11.sp,
  )
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
  OutlinedTextField(
      value = value,
      onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' }) },
      label = { Text(label, fontSize = 12.sp) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      modifier = modifier,
      colors =
          OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color.White,
              unfocusedTextColor = Color.White,
              focusedLabelColor = Hud,
              unfocusedLabelColor = Muted,
              focusedBorderColor = Hud,
              unfocusedBorderColor = Color(0xFF2A3D63),
          ),
  )
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
    Text(label, color = Color.White, modifier = Modifier.weight(1f))
    Switch(checked = value, onCheckedChange = onChange)
  }
}
