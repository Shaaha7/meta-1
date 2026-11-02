// ExpertPanel - Expert diagnosis for machines in Ustad's knowledge base (car, split AC, ...)
//
// The AI only reported what it can see; UstadBrain ranks the causes, asks the most useful next
// question, and the fix steps come from the knowledge base with their sources. The AI is used
// here only to translate those steps into Hindi or Arabic.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.CoachLanguage
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.GeminiClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.Inspection
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.KnowledgeBase
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.MachineKind
import kotlinx.coroutines.launch

@Composable
fun ExpertPanel(
    insp: Inspection,
    kind: MachineKind,
    kb: KnowledgeBase,
    client: GeminiClient,
    language: CoachLanguage,
    onLanguage: (CoachLanguage) -> Unit,
    say: (String) -> Unit,
) {
  val scope = rememberCoroutineScope()
  // Evidence: camera observations first, then the technician's answers. Error codes count as a symptom.
  var evidence by remember {
    // An error code on an AC display, or a fault code / warning on a car dashboard.
    val codeSymptom = listOf("display_error", "car_check_engine").firstOrNull { it in kb.symptoms }
    mutableStateOf(
        insp.observed.filterKeys { it in kb.symptoms } +
            (if (insp.errorCode.isNotBlank() && codeSymptom != null) mapOf(codeSymptom to true) else emptyMap()))
  }
  var answeredByUser by remember { mutableStateOf(setOf<String>()) }
  var skipped by remember { mutableStateOf(setOf<String>()) }
  var ruledOut by remember { mutableStateOf(setOf<String>()) }
  var confirmedId by remember { mutableStateOf<String?>(null) }
  var steps by remember { mutableStateOf<List<String>>(emptyList()) }
  var stepIndex by remember { mutableIntStateOf(-1) }
  var loadingSteps by remember { mutableStateOf(false) }

  val ranked = kb.rank(evidence, ruledOut)
  val top = ranked.firstOrNull() ?: return
  val confirmed = confirmedId?.let { id -> kb.failures.first { it.id == id } }
  val focus = confirmed ?: top.failure
  val question = if (confirmed == null) kb.nextQuestion(evidence, ruledOut, skipped) else null

  // Speak a short summary whenever the leading cause or the question changes.
  LaunchedEffect(top.failure.id, question?.id, confirmedId) {
    if (confirmed == null) say("Most likely: ${top.failure.name}, ${(top.probability * 100).toInt()} percent. ${question?.question ?: top.failure.test}")
  }

  UstadCard("Machine") {
    Text(listOf(insp.machine.type, insp.machine.brand, insp.machine.model).filter { it.isNotBlank() }.joinToString(" · "), color = Color.White, fontWeight = FontWeight.Bold)
    Text("Expert mode · ${kb.title} knowledge base · causes ranked by Ustad's model, not AI guesses", color = Ok, fontSize = 12.sp)
    if (insp.errorCode.isNotBlank()) Text("Error code read: ${insp.errorCode}", color = Color(0xFFFBBF24), fontSize = 13.sp)
  }

  UstadCard("Evidence") {
    if (evidence.isEmpty()) UstadBody("Nothing conclusive seen yet. Answer the questions below.")
    evidence.forEach { (id, present) ->
      val s = kb.symptoms[id] ?: return@forEach
      Text("${if (present) "✓" else "✗"} ${s.label}  ·  ${if (id in answeredByUser) "you" else "camera"}",
          color = if (present) Color.White else Muted, fontSize = 14.sp)
    }
  }

  UstadCard("Inside view") { ExplodedView(listOf(focus.part), page = kind.page) }

  UstadCard(if (confirmed != null) "Confirmed cause" else "Most likely causes") {
    (if (confirmed != null) listOf(top.copy(failure = confirmed, probability = 1.0)) else ranked.take(3)).forEachIndexed { i, r ->
      Row { Text(r.failure.name, color = if (i == 0) Color.White else Muted, modifier = Modifier.weight(1f)); Text("${(r.probability * 100).toInt()}%", color = Color.White) }
      LinearProgressIndicator(progress = { r.probability.toFloat() }, color = if (i == 0) Fault else Hud, trackColor = Color(0xFF13213D), modifier = Modifier.fillMaxWidth())
    }
  }

  UstadCard("Why it's happening") {
    focus.causeChain.forEachIndexed { i, step ->
      Text(step, color = Color.White, modifier = Modifier.fillMaxWidth().background(Color(0xFF11203D), RoundedCornerShape(6.dp)).padding(8.dp))
      if (i < focus.causeChain.lastIndex) Text("▼", color = Fault, modifier = Modifier.padding(start = 12.dp))
    }
    Text("Sources: " + focus.sources.joinToString(" · ") { it.name }, color = Muted, fontSize = 12.sp)
  }

  if (focus.safety.isNotBlank()) UstadCard("⚠ Safety", border = Fault) { UstadBody(focus.safety) }

  if (question != null) {
    UstadCard("Ustad asks") {
      Text(question.question, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { evidence = evidence + (question.id to true); answeredByUser = answeredByUser + question.id }) { Text("Yes") }
        Button(onClick = { evidence = evidence + (question.id to false); answeredByUser = answeredByUser + question.id }) { Text("No") }
        OutlinedButton(onClick = { skipped = skipped + question.id }) { Text("Not sure", color = Color.White) }
      }
    }
  }

  if (confirmed == null) {
    UstadCard("Prove it") {
      UstadBody("🧪 ${top.failure.test}")
      Text("If it's this: ${top.failure.expected}", color = Muted, fontSize = 13.sp)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { confirmedId = top.failure.id; say("Confirmed. ${top.failure.name}.") }, colors = ButtonDefaults.buttonColors(containerColor = Ok)) { Text("Confirmed") }
        OutlinedButton(enabled = ranked.size > 1, onClick = { ruledOut = ruledOut + top.failure.id }) { Text("Not this, next", color = Color.White) }
      }
    }
  } else {
    UstadCard("Repair coach") {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CoachLanguage.entries.forEach { lang -> FilterChip(selected = language == lang, onClick = { onLanguage(lang); stepIndex = -1 }, label = { Text(lang.label) }) }
      }
      when {
        loadingSteps -> UstadBusy("Translating steps to ${language.promptName}…")
        stepIndex < 0 -> Button(onClick = {
          loadingSteps = true
          scope.launch {
            steps = runCatching { client.translate(confirmed.fix, language.promptName) }.getOrDefault(confirmed.fix)
            loadingSteps = false; stepIndex = 0; say(steps.first())
          }
        }) { Text("Start repair coach") }
        stepIndex < steps.size -> {
          Text("Step ${stepIndex + 1} of ${steps.size}", color = Muted, fontSize = 12.sp)
          val s = steps[stepIndex]
          Text(s, color = if (s.startsWith("STOP")) Fault else Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = stepIndex > 0, onClick = { stepIndex--; say(steps[stepIndex]) }) { Text("Back", color = Color.White) }
            OutlinedButton(onClick = { say(s) }) { Text("Repeat", color = Color.White) }
            Button(onClick = { stepIndex++; say(if (stepIndex < steps.size) steps[stepIndex] else "Repair complete.") }) { Text(if (stepIndex < steps.lastIndex) "Next" else "Finish") }
          }
        }
        else -> {
          Text("✅ Done: ${confirmed.name}", color = Ok, fontWeight = FontWeight.Bold)
          UstadBody("Steps from: ${confirmed.sources.joinToString(" · ") { it.name }}")
        }
      }
    }
  }
}
