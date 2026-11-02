// ExpertPanel - Expert diagnosis for machines in Ustad's knowledge base (car, split AC, ...)
//
// The AI only reported what it can see; UstadBrain ranks the causes, asks the most useful next
// question, and the fix steps come from the knowledge base with their sources. The AI is used
// here only to translate those steps.
//
// The brain learns: every CONFIRMED fix is saved as a case (with what was seen) and to the
// machine's history. Next time, the same make and model ranks that cause higher. The screen shows
// what the brain learned from, why it ranks the leading cause first, and its reasoning graph.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.CoachLanguage
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.GeminiClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.Inspection
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.KnowledgeBase
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.ConfirmedCase
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.Heard
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.VoiceCommand
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.JobRecord
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.MachineKind
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.machineKey
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.reasoningGraph
import androidx.compose.ui.platform.LocalContext
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
    loop: VoiceLoop? = null,
    onAsked: (Pair<String, String>) -> Unit = {},
) {
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val data = remember { UstadData(context) }
  val kbId = kind.kbFile.orEmpty()
  val key = machineKey(insp.machine.brand, insp.machine.model)
  val machineName = listOf(insp.machine.type, insp.machine.brand, insp.machine.model).filter { it.isNotBlank() }.joinToString(" · ")
  // Re-learn whenever a fix is confirmed or undone on this screen.
  var brainVersion by remember { mutableIntStateOf(0) }
  val brain = remember(brainVersion) { kb.learned(data.cases.cases(kbId), key) }
  val pastVisits = remember(brainVersion) { data.history.forMachine(key) }
  var job by remember { mutableStateOf<JobRecord?>(null) }
  // Evidence: camera observations first, then the technician's answers. Error codes count as a symptom.
  var evidence by remember {
    // An error code on an AC display, or a fault code / warning on a car dashboard.
    val codeSymptom = listOf("display_error", "car_check_engine").firstOrNull { it in kb.symptoms }
    mutableStateOf(
        insp.observed.filterKeys { it in kb.symptoms } +
            (if (insp.errorCode.isNotBlank() && codeSymptom != null) mapOf(codeSymptom to true) else emptyMap()))
  }
  var answeredByUser by remember { mutableStateOf(setOf<String>()) }
  // Symptoms judged by ear (🎧 Listen).
  var heardByEar by remember { mutableStateOf(setOf<String>()) }
  var skipped by remember { mutableStateOf(setOf<String>()) }
  var ruledOut by remember { mutableStateOf(setOf<String>()) }
  var confirmedId by remember { mutableStateOf<String?>(null) }
  var steps by remember { mutableStateOf<List<String>>(emptyList()) }
  var stepIndex by remember { mutableIntStateOf(-1) }
  var loadingSteps by remember { mutableStateOf(false) }

  val ranked = brain.rank(evidence, ruledOut)
  val top = ranked.firstOrNull() ?: return
  val confirmed = confirmedId?.let { id -> brain.failures.first { it.id == id } }
  val focus = confirmed ?: top.failure
  val question = if (confirmed == null) brain.nextQuestion(evidence, ruledOut, skipped) else null

  // Repair finished → the visit is marked complete in the machine history.
  LaunchedEffect(stepIndex, steps) {
    val j = job
    if (j != null && steps.isNotEmpty() && stepIndex >= steps.size && !j.completed) {
      job = j.copy(steps = steps, completed = true).also { data.history.save(it) }
    }
  }

  fun expertSummary() =
      "Machine: $machineName\n" +
          "Seen: " + evidence.entries.joinToString(", ") { (id, p) -> "${if (p) "" else "no "}${kb.symptoms[id]?.label ?: id}" } + "\n" +
          "Ustad thinks: ${focus.name} (${(top.probability * 100).toInt()}%)\n" +
          "Test: ${focus.test}"

  /** The prove-it test passed: Ustad learns from this case and the visit goes into the history. */
  fun confirmTop() {
    if (confirmedId != null) return
    val f = top.failure
    confirmedId = f.id
    // The brain learns from this case, and the visit goes into the machine's history.
    data.cases.add(ConfirmedCase(kbId, key, f.id, evidence, by = data.learnerName))
    job =
        JobRecord(
            machine = machineName,
            machineKey = key,
            kind = kind.name,
            evidence = evidence.mapNotNull { (id, present) -> kb.symptoms[id]?.let { "${if (present) "Seen" else "Not seen"}: ${it.label}" } },
            errorCode = insp.errorCode,
            cause = f.name,
            causeChain = f.causeChain,
            confidence = (top.probability * 100).toInt(),
            test = f.test,
            steps = f.fix,
            safety = f.safety,
            sources = f.sources.map { "${it.name} (${it.url})" },
            technician = data.learnerName,
        ).also { data.history.save(it) }
    brainVersion++
    say("Confirmed. ${f.name}. Ustad will remember this.")
  }

  // Prove it with a photo of the test result (project plan, Prompt 3).
  var verdict by remember { mutableStateOf<com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.TestVerdict?>(null) }
  var verifying by remember { mutableStateOf(false) }
  var verifyError by remember { mutableStateOf("") }
  val testPhoto = rememberPhotoCapture { photo ->
    val f = top.failure
    verifying = true
    verifyError = ""
    verdict = null
    scope.launch {
      runCatching {
            client.verifyTest(
                com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.PrivacyFilter.blurFaces(photo).image,
                machineName, f.name, f.test, f.expected, language.promptName)
          }
          .onSuccess { v ->
            verdict = v
            when {
              v.confirmed -> confirmTop()
              v.notConfirmed -> {
                if (ranked.size > 1) ruledOut = ruledOut + f.id
                say("${v.reason} Not ${f.name}. Moving to the next cause.")
              }
              else -> say(v.nextAction.ifBlank { v.reason })
            }
          }
          .onFailure { verifyError = it.message ?: "Couldn't check the photo" }
      verifying = false
    }
  }


  // Voice: answer Ustad's question, prove it, coach the repair, or ask anything.
  if (loop != null) loop.onHeard = { heard ->
    val q = question
    when (heard) {
      is Heard.Question ->
          askUstad(
              scope, client, loop, heard.text,
              "Machine: $machineName. Leading cause: ${focus.name} " +
                  "(${(top.probability * 100).toInt()}%). Cause chain: ${focus.causeChain.joinToString(" -> ")}. " +
                  "Test: ${focus.test} Expected: ${focus.expected} Safety: ${focus.safety} " +
                  "Fix: ${focus.fix.joinToString(" ")} Sources: ${focus.sources.joinToString { it.name }}",
              onAsked)
      is Heard.Command ->
          when (heard.command) {
            VoiceCommand.YES -> if (q != null) { evidence = evidence + (q.id to true); answeredByUser = answeredByUser + q.id }
            VoiceCommand.NO -> if (q != null) { evidence = evidence + (q.id to false); answeredByUser = answeredByUser + q.id }
            VoiceCommand.NOT_SURE -> if (q != null) skipped = skipped + q.id
            VoiceCommand.WHY -> say(focus.causeChain.joinToString(". "))
            VoiceCommand.NOT_THIS -> if (confirmed == null && ranked.size > 1) ruledOut = ruledOut + top.failure.id
            VoiceCommand.CONFIRMED ->
                if (confirmed == null) confirmTop()
                else if (stepIndex in 0 until steps.size) { stepIndex++; say(if (stepIndex < steps.size) steps[stepIndex] else "Repair complete.") }
            VoiceCommand.NEXT -> if (stepIndex in 0 until steps.size) { stepIndex++; say(if (stepIndex < steps.size) steps[stepIndex] else "Repair complete.") }
            VoiceCommand.BACK -> if (stepIndex in 1 until steps.size) { stepIndex--; say(steps[stepIndex]) }
            VoiceCommand.REPEAT ->
                say(if (stepIndex in steps.indices) steps[stepIndex] else q?.question ?: "${top.failure.name}. ${top.failure.test}")
            VoiceCommand.STOP -> loop.stopAll()
            VoiceCommand.CHECK, VoiceCommand.INSPECT -> if (confirmed == null && !verifying) testPhoto()
            VoiceCommand.CALL_EXPERT -> say(callExpert(context, expertSummary()))
            VoiceCommand.START_LESSON, VoiceCommand.STOP_LESSON -> Unit
          }
      else -> Unit
    }
  }

  // Speak a short summary whenever the leading cause or the question changes.
  LaunchedEffect(top.failure.id, question?.id, confirmedId) {
    if (confirmed == null) say("Most likely: ${top.failure.name}, ${(top.probability * 100).toInt()} percent. ${question?.question ?: top.failure.test}")
  }

  UstadCard("Machine") {
    Text(listOf(insp.machine.type, insp.machine.brand, insp.machine.model).filter { it.isNotBlank() }.joinToString(" · "), color = Color.White, fontWeight = FontWeight.Bold)
    Text("Expert mode · ${kb.title} knowledge base · causes ranked by Ustad's model, not AI guesses", color = Ok, fontSize = 12.sp)
    if (insp.errorCode.isNotBlank()) Text("Error code read: ${insp.errorCode}", color = Color(0xFFFBBF24), fontSize = 13.sp)
  }

  // Short by default: the answer, the 3D part, what to do. The reasoning is one tap away.
  var details by remember { mutableStateOf(false) }
  DetailsToggle(details, "Evidence, brain & history, listen to the machine, why Ustad thinks so") { details = it }

  if (details) {
    UstadCard("🧠 Ustad's brain") {
      UstadBody(
          "${kb.failures.size} failure modes · ${kb.symptoms.size} symptoms · ${kb.sourceCount} sources" +
              if (brain.learnedCases == 0) " · no confirmed fixes yet"
              else " · learned from ${brain.learnedCases} confirmed fix(es), ${brain.modelCases} on this model")
      if (brain.modelHistory.isNotEmpty()) {
        Text(
            "This model before: " + brain.modelHistory.entries.joinToString(", ") { (id, n) ->
              "${brain.failures.firstOrNull { it.id == id }?.name ?: id} ×$n"
            },
            color = Color(0xFFFBBF24),
            fontSize = 13.sp,
        )
      }
      pastVisits.take(3).forEach { v ->
        Text(
            "• ${java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(v.at))}: ${v.cause}" +
                if (v.completed) " (fixed)" else "",
            color = Muted,
            fontSize = 12.sp,
        )
      }
    }

    UstadCard("Evidence") {
      if (evidence.isEmpty()) UstadBody("Nothing conclusive seen yet. Answer the questions below.")
      evidence.forEach { (id, present) ->
        val s = kb.symptoms[id] ?: return@forEach
        Text("${if (present) "✓" else "✗"} ${s.label}  ·  ${when (id) { in answeredByUser -> "you"; in heardByEar -> "ears"; else -> "camera" }}",
            color = if (present) Color.White else Muted, fontSize = 14.sp)
      }
    }

    if (confirmed == null) {
      ListenCard(machineName, kb.symptoms.values, client, language, loop) { report ->
        // What was heard goes into the evidence (answers the technician gave by hand win).
        val fromEar = report.symptoms.filterKeys { it in kb.symptoms && it !in answeredByUser }
        evidence = evidence + fromEar
        heardByEar = heardByEar + fromEar.keys
      }
    }
  }

  UstadCard("Inside view") { ExplodedView(listOf(focus.part), page = kind.page) }

  UstadCard(if (confirmed != null) "Confirmed cause" else "Most likely causes") {
    (if (confirmed != null) listOf(top.copy(failure = confirmed, probability = 1.0)) else ranked.take(3)).forEachIndexed { i, r ->
      Row { Text(r.failure.name, color = if (i == 0) Color.White else Muted, modifier = Modifier.weight(1f)); Text("${(r.probability * 100).toInt()}%", color = Color.White) }
      LinearProgressIndicator(progress = { r.probability.toFloat() }, color = if (i == 0) Fault else Hud, trackColor = Color(0xFF13213D), modifier = Modifier.fillMaxWidth())
    }
    // The cause chain in one line; the full chain and sources are in the details.
    if (!details && focus.causeChain.isNotEmpty()) Text(focus.causeChain.joinToString("  →  "), color = Muted, fontSize = 13.sp)
  }

  // The brain's reasoning as a graph: what was seen → which causes → which part. Always visible.
  UstadCard("🧠 How Ustad decided") {
    BrainGraphView(brain.reasoningGraph(evidence, ruledOut, kind.parts, focusId = focus.id).toString())
    Text(
        if (brain.learnedCases > 0) "Green = seen, grey = not seen. Includes ${brain.learnedCases} confirmed fix(es) Ustad learned from."
        else "Green = seen, grey = not seen, red = the leading cause and its part.",
        color = Muted,
        fontSize = 11.sp,
    )
  }

  if (details) {
    val weights = remember(brain, evidence, ruledOut, focus.id) { brain.explain(focus.id, evidence, ruledOut) }
    if (weights.isNotEmpty() && confirmed == null) {
      UstadCard("Why Ustad ranks this first") {
        weights.take(5).forEach { w ->
          Row {
            Text("${if (w.present) "✓" else "✗"} ${w.symptom.label}", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(w.verdict, color = if (w.weight > 0.4) Ok else if (w.weight < -0.4) Fault else Muted, fontSize = 12.sp)
          }
        }
        val seen = brain.modelHistory[focus.id] ?: 0
        if (seen > 0) Text("+ confirmed $seen× before on this model", color = Color(0xFFFBBF24), fontSize = 13.sp)
      }
    }

    UstadCard("Why it's happening") {
      focus.causeChain.forEachIndexed { i, step ->
        Text(step, color = Color.White, modifier = Modifier.fillMaxWidth().background(Color(0xFF11203D), RoundedCornerShape(6.dp)).padding(8.dp))
        if (i < focus.causeChain.lastIndex) Text("▼", color = Fault, modifier = Modifier.padding(start = 12.dp))
      }
      Text("Sources: " + focus.sources.joinToString(" · ") { it.name }, color = Muted, fontSize = 12.sp)
    }
  }

  if (focus.safety.isNotBlank()) UstadCard("⚠ Safety", border = Fault) { UstadBody(focus.safety) }

  if (details) ExpertCallCard(expertSummary(), say)

  LensMirror(
      if (confirmed != null) "Fix: ${focus.name}" else "${focus.name} · ${(top.probability * 100).toInt()}%",
      when {
        stepIndex in steps.indices -> "Step ${stepIndex + 1}/${steps.size}: ${steps[stepIndex]}"
        question != null -> "Ustad asks: ${question.question}"
        else -> "Prove it: ${focus.test}"
      },
      machineName)

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
    // Quickest likely causes first (probability per minute of testing).
    val order = remember(brain, evidence, ruledOut) { brain.checkOrder(evidence, ruledOut) }
    if (details && order.size > 1) {
      UstadCard("Check in this order") {
        order.forEachIndexed { i, r ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}", color = Hud, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp))
            Column(Modifier.weight(1f)) {
              Text(r.failure.test, color = Color.White, fontSize = 14.sp)
              Text("${r.failure.name} · ${(r.probability * 100).toInt()}% · ~${r.failure.testMinutes.toInt().coerceAtLeast(1)} min", color = Muted, fontSize = 12.sp)
            }
          }
        }
        Text(
            "Ordered by likelihood per minute: about ${"%.0f".format(brain.expectedMinutes(order))} min expected to find it.",
            color = Muted,
            fontSize = 11.sp,
        )
      }
    }
    UstadCard("Prove it") {
      UstadBody("🧪 ${top.failure.test}")
      Text("If it's this: ${top.failure.expected}", color = Muted, fontSize = 13.sp)
      when {
        verifying -> UstadBusy("Ustad is checking the test result…")
        verifyError.isNotBlank() -> Text("Couldn't check: $verifyError", color = Muted, fontSize = 12.sp)
        verdict != null && verdict?.confirmed != true ->
            Text(
                (if (verdict?.notConfirmed == true) "❌ " else "❓ ") + listOf(verdict?.reason, verdict?.nextAction).filter { !it.isNullOrBlank() }.joinToString(" "),
                color = if (verdict?.notConfirmed == true) Fault else Color(0xFFFBBF24),
                fontSize = 14.sp,
            )
      }
      Button(onClick = { testPhoto() }, enabled = !verifying) { Text("📸 Show Ustad the result") }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { confirmTop() },
            colors = ButtonDefaults.buttonColors(containerColor = Ok),
        ) { Text("Confirmed") }
        OutlinedButton(enabled = ranked.size > 1, onClick = { ruledOut = ruledOut + top.failure.id }) { Text("Not this, next", color = Color.White) }
      }
    }
  } else {
    UstadCard("Learned") {
      UstadBody("Saved to Ustad's brain and this machine's history. Next time, this cause ranks higher for ${insp.machine.brand.ifBlank { "this machine" }} ${insp.machine.model}.")
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        job?.let { j -> OutlinedButton(onClick = { shareText(context, "Ustad job report", j.report()) }) { Text("Share job report", color = Color.White) } }
        Text(
            "Undo",
            color = Fault,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 12.dp).clickable {
              data.cases.undo(kbId, key, confirmed.id)
              job?.let { data.history.delete(it.id) }
              job = null
              confirmedId = null
              stepIndex = -1
              brainVersion++
            },
        )
      }
    }
    UstadCard("Repair coach") {
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CoachLanguage.entries.forEach { lang -> FilterChip(selected = language == lang, onClick = { onLanguage(lang); stepIndex = -1 }, label = { Text(lang.label) }) }
      }
      when {
        loadingSteps -> UstadBusy("Translating steps to ${language.promptName}…")
        stepIndex < 0 -> Button(onClick = {
          loadingSteps = true
          scope.launch {
            steps = runCatching { client.translate(confirmed.fix, language.promptName) }.getOrDefault(confirmed.fix).ifEmpty { confirmed.fix }
            loadingSteps = false; stepIndex = 0; steps.firstOrNull()?.let { say(it) }
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
