// UstadScreen - Look → Reveal → Confirm → Fix, driven by a photo from the glasses
//
// Shown when a photo is captured from the glasses stream. Faces are blurred on-device first, then
// Gemini identifies the machine and ranks the hidden failing parts. The exploded view highlights
// the suspected part, the technician proves it with a quick test, and the repair coach speaks
// the fix step by step in their language.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.CoachLanguage
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.GeminiClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.Inspection
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.KnowledgeBase
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.MachineKind
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.MachineRouter
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.PrivacyFilter
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.RepairStep
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.Heard
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.VoiceCommand
import kotlinx.coroutines.launch

internal val Bg = Color(0xFF060B16)
internal val CardBg = Color(0xFF0E1729)
internal val Hud = Color(0xFF39E6FF)
internal val Fault = Color(0xFFFF3B30)
internal val Ok = Color(0xFF34D399)
internal val Muted = Color(0xFF8AA0C0)

private sealed interface Phase {
  data object Analyzing : Phase
  data class Failed(val message: String) : Phase
  data object Result : Phase
  data object LoadingSteps : Phase
  data object Coaching : Phase
  data object Done : Phase
}

@Composable
fun UstadScreen(photo: Bitmap, onDismiss: () -> Unit) = UstadTheme { UstadScreenContent(photo, onDismiss) }

@Composable
private fun UstadScreenContent(photo: Bitmap, onDismiss: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val client = remember { GeminiClient() }
  // Voice loop: Ustad speaks (natural ElevenLabs voice or phone voice) and listens back.
  val loop = rememberVoiceLoop()
  val voice = loop.voice
  var asked by remember { mutableStateOf<Pair<String, String>?>(null) }
  // What 🎧 Listen heard (machines outside the knowledge bases).
  var heardReport by remember { mutableStateOf("") }
  var details by remember { mutableStateOf(false) }
  // What the safety rules changed in the AI's repair steps.
  var safetyFindings by remember { mutableStateOf<List<String>>(emptyList()) }
  // Ustad's expert knowledge bases, one per machine kind it knows deeply (car, split AC).
  val kbs = remember {
    MachineKind.entries.mapNotNull { k -> k.kbFile?.let { f -> runCatching { k to KnowledgeBase.load(context, f) }.getOrNull() } }.toMap()
  }

  var phase by remember { mutableStateOf<Phase>(Phase.Analyzing) }
  var safePhoto by remember { mutableStateOf(photo) }
  var privacyNote by remember { mutableStateOf("checking for faces and plates…") }
  var inspection by remember { mutableStateOf<Inspection?>(null) }
  var dxIndex by remember { mutableIntStateOf(0) }
  var confirmed by remember { mutableStateOf(false) }
  var language by remember { mutableStateOf(CoachLanguage.ENGLISH) }
  var steps by remember { mutableStateOf<List<RepairStep>>(emptyList()) }
  var stepIndex by remember { mutableIntStateOf(0) }
  var attempt by remember { mutableIntStateOf(0) }
  var voiceNote by remember { mutableStateOf("") }
  // Machine history / job report for machines outside the knowledge bases.
  val data = remember { UstadData(context) }
  var job by remember { mutableStateOf<com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.JobRecord?>(null) }

  fun say(text: String) {
    loop.language = language
    voiceNote = if (loop.say(text)) "" else "No ${language.promptName} voice installed on this phone"
  }

  loop.language = language
  // Until a diagnosis is on screen, any question is about the photo / machine in general.
  // (When the knowledge-base brain panel is showing, it owns the voice handler.)
  val brainPanel = inspection?.let { !it.needBetterView && kbs[MachineRouter.detect(it)] != null } == true
  if (!brainPanel) loop.onHeard = { heard ->
    when (heard) {
      is Heard.Question -> askUstad(scope, client, loop, heard.text, inspection?.let { "Machine: ${it.machine.type} ${it.machine.brand} ${it.machine.model}. ${it.voiceSummary}" }.orEmpty()) { asked = it }
      is Heard.Command -> if (heard.command == VoiceCommand.STOP) loop.stopAll()
      else -> Unit
    }
  }

  // Privacy first, then diagnosis. Re-runs on Retry.
  var stage by remember { mutableStateOf("") }
  var elapsed by remember { mutableIntStateOf(0) }
  LaunchedEffect(phase) {
    elapsed = 0
    while (phase == Phase.Analyzing) {
      kotlinx.coroutines.delay(1_000)
      elapsed++
    }
  }
  LaunchedEffect(attempt) {
    phase = Phase.Analyzing
    stage = "Blurring faces on the phone…"
    val privacy = PrivacyFilter.blurFaces(photo)
    stage = "Ustad is looking and reasoning…"
    safePhoto = privacy.image
    privacyNote = privacy.summary
    runCatching { client.inspect(privacy.image, "", kbs.values.flatMap { it.symptoms.values }) }
        .onSuccess {
          inspection = it
          dxIndex = 0
          confirmed = false
          phase = Phase.Result
          say(if (it.needBetterView) it.userInstruction else it.voiceSummary)
        }
        .onFailure { phase = Phase.Failed(it.message ?: "Diagnosis failed") }
  }

  Column(
      Modifier.fillMaxSize()
          .background(Bg)
          .statusBarsPadding()
          .navigationBarsPadding()
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("USTAD", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
      Spacer(Modifier.padding(6.dp))
      Text("See inside any machine", color = Muted, fontSize = 13.sp)
      Spacer(Modifier.weight(1f))
      OutlinedButton(onClick = { loop.stopAll(); onDismiss() }) { Text("Close", color = Color.White) }
    }

    Image(
        safePhoto.asImageBitmap(),
        contentDescription = "Glasses photo",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(12.dp)),
    )
    Text(
        "🔒 Captured on your command · $privacyNote · photo not stored",
        color = Ok,
        fontSize = 12.sp,
    )
    VoiceBar(loop, hint = "Talk to Ustad: “why”, “yes”, “no”, “confirmed”, “next”, or ask a question.")
    AskUstadCard(asked)

    when (val p = phase) {
      Phase.Analyzing -> UstadBusy("$stage ${elapsed}s")
      is Phase.Failed ->
          UstadCard("Couldn't diagnose") {
            Text(p.message, color = Fault)
            Text("If the AI service is busy, wait a few seconds and retry.", color = Muted, fontSize = 13.sp)
            Button(onClick = { attempt++ }) { Text("Retry") }
          }
      else -> {
        val insp = inspection ?: return@Column
        if (insp.needBetterView) {
          UstadCard("Need a better view") { UstadBody(insp.userInstruction) }
          return@Column
        }
        // Machines in Ustad's knowledge base are diagnosed by Ustad's own model, not the AI.
        val kind = MachineRouter.detect(insp)
        // Lithium packs: the battery module (rule-based health model + 3D pack with the weak layer).
        if (kind == MachineKind.BATTERY) {
          BatteryPanel(insp, ::say)
          if (voiceNote.isNotBlank()) Text(voiceNote, color = Muted, fontSize = 12.sp)
          return@Column
        }
        val kb = kbs[kind]
        if (kb != null) {
          ExpertPanel(insp, kind, kb, client, language, onLanguage = { language = it }, say = ::say, loop = loop, onAsked = { asked = it })
          if (voiceNote.isNotBlank()) Text(voiceNote, color = Muted, fontSize = 12.sp)
          return@Column
        }
        val dx = insp.diagnoses.getOrNull(dxIndex)
        if (!insp.faultFound || insp.diagnoses.isEmpty()) {
          UstadCard("Machine") {
            Text(listOf(insp.machine.type, insp.machine.brand, insp.machine.model).filter { it.isNotBlank() }.joinToString(" · "),
                color = Color.White, fontWeight = FontWeight.Bold)
            Text(client.lastProvider, color = Muted, fontSize = 12.sp)
          }
          UstadCard("✅ No fault found") { UstadBody(insp.voiceSummary.ifBlank { "Nothing visible looks wrong. Point Ustad at the symptom, like a leak or burn mark." }) }
          if (kind.hasExpertModel) UstadCard("Inside view") { ExplodedView(emptyList(), page = kind.page) }
          return@Column
        }

        UstadCard("Machine") {
          Text(listOf(insp.machine.type, insp.machine.brand, insp.machine.model).filter { it.isNotBlank() }.joinToString(" · "),
              color = Color.White, fontWeight = FontWeight.Bold)
          Text(
              "Identified with ${(insp.machine.confidence * 100).toInt()}% confidence · ${client.lastProvider} · " +
                  "answered in ${"%.1f".format(client.lastSeconds)} s",
              color = Muted,
              fontSize = 12.sp,
          )
        }
        val machineLine = listOf(insp.machine.type, insp.machine.brand, insp.machine.model).filter { it.isNotBlank() }.joinToString(" ")
        // Short by default; what Ustad saw, the sound check and the full cause chain are one tap away.
        DetailsToggle(details, "What Ustad sees, listen to the machine, full cause chain, call an expert") { details = it }
        if (details) {
          if (insp.symptoms.isNotEmpty()) {
            UstadCard("What I see") { insp.symptoms.forEach { UstadBody("• ${it.symptom} — ${it.location}") } }
          }
          ListenCard(machineLine, emptyList(), client, language, loop) { heardReport = it.heard }
        }
        if (insp.safety.hazard.isNotBlank() || insp.safety.stopAndCallPro) {
          UstadCard("⚠ Safety", border = Fault) {
            UstadBody(insp.safety.hazard)
            if (insp.safety.stopAndCallPro) Text("STOP — call a certified professional.", color = Fault, fontWeight = FontWeight.Bold)
          }
        }

        if (dx != null) {
          // Voice for the AI diagnosis path.
          loop.onHeard = { heard ->
            val coaching = phase == Phase.Coaching
            when (heard) {
              is Heard.Question ->
                  askUstad(
                      scope, client, loop, heard.text,
                      "Machine: ${insp.machine.type} ${insp.machine.brand} ${insp.machine.model}. Suspected: ${dx.partName}. " +
                          "Cause chain: ${dx.causeChain.joinToString(" -> ")}. Test: ${dx.confirmTest}. Safety: ${insp.safety.hazard}. " +
                          (if (heardReport.isNotBlank()) "Sound heard: $heardReport. " else "") +
                          (steps.getOrNull(stepIndex)?.let { "Current repair step: ${it.say}" } ?: "")) { asked = it }
              is Heard.Command ->
                  when (heard.command) {
                    VoiceCommand.WHY -> say(dx.causeChain.joinToString(". "))
                    VoiceCommand.REPEAT -> say(if (coaching) steps.getOrNull(stepIndex)?.say.orEmpty() else "${dx.partName}. ${dx.confirmTest}")
                    VoiceCommand.NEXT -> if (coaching && stepIndex < steps.lastIndex) { stepIndex++; say(steps[stepIndex].say) }
                    VoiceCommand.BACK -> if (coaching && stepIndex > 0) { stepIndex--; say(steps[stepIndex].say) }
                    VoiceCommand.NOT_THIS ->
                        if (!confirmed && dxIndex < insp.diagnoses.lastIndex) {
                          dxIndex++
                          insp.diagnoses.getOrNull(dxIndex)?.let { say("Next suspect: ${it.partName}. ${it.confirmTest}") }
                        }
                    VoiceCommand.STOP -> loop.stopAll()
                    VoiceCommand.CALL_EXPERT ->
                        say(callExpert(context, "Machine: $machineLine\nSuspected: ${dx.partName}\nTest: ${dx.confirmTest}"))
                    else -> Unit
                  }
              else -> Unit
            }
          }
          UstadCard("Inside view") {
            if (kind.hasExpertModel) {
              ExplodedView(listOfNotNull(MachineRouter.modelPart(kind, dx.partId, dx.partName)), page = kind.page)
            } else {
              ExplodedView(listOf(dx.partId), partsJson = partsLayout(insp))
            }
          }
          if (details) UstadCard("Why it's failing") {
            dx.causeChain.forEachIndexed { i, step ->
              Text(step, color = Color.White, modifier = Modifier.fillMaxWidth().background(Color(0xFF11203D), RoundedCornerShape(6.dp)).padding(8.dp))
              if (i < dx.causeChain.lastIndex) Text("▼", color = Fault, modifier = Modifier.padding(start = 12.dp))
            }
          }
          UstadCard("Diagnosis") {
            insp.diagnoses.forEachIndexed { i, d ->
              Row { Text(d.partName, color = if (i == dxIndex) Color.White else Muted, modifier = Modifier.weight(1f)); Text("${(d.confidence * 100).toInt()}%", color = Color.White) }
              LinearProgressIndicator(progress = { d.confidence.toFloat() }, color = if (i == dxIndex) Fault else Hud, trackColor = Color(0xFF13213D), modifier = Modifier.fillMaxWidth())
            }
            if (!details && dx.causeChain.isNotEmpty()) Text(dx.causeChain.joinToString("  →  "), color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            UstadBody("🧪 Prove it: ${dx.confirmTest}")
            Text("If true: ${dx.expectedIfTrue}", color = Muted, fontSize = 13.sp)
            if (!confirmed) {
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                      confirmed = true
                      job =
                          com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.JobRecord(
                              machine = listOf(insp.machine.type, insp.machine.brand, insp.machine.model).filter { it.isNotBlank() }.joinToString(" · "),
                              machineKey = com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.machineKey(insp.machine.brand, insp.machine.model),
                              kind = kind.name,
                              evidence = insp.symptoms.map { "Seen: ${it.symptom} (${it.location})" },
                              errorCode = insp.errorCode,
                              cause = dx.partName,
                              causeChain = dx.causeChain,
                              confidence = (dx.confidence * 100).toInt(),
                              test = dx.confirmTest,
                              safety = insp.safety.hazard,
                              sources = listOf("AI diagnosis (${client.lastProvider})"),
                              technician = data.learnerName,
                          ).also { data.history.save(it) }
                      say("Confirmed. ${dx.partName}.")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Ok),
                ) { Text("Confirmed") }
                OutlinedButton(
                    enabled = dxIndex < insp.diagnoses.lastIndex,
                    onClick = { dxIndex++; insp.diagnoses.getOrNull(dxIndex)?.let { say("Next suspect: ${it.partName}. ${it.confirmTest}") } },
                ) { Text("Not this — next", color = Color.White) }
              }
            } else {
              Text("CONFIRMED", color = Ok, fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
          }

          LensMirror(
              if (confirmed) "Fix: ${dx.partName}" else "${dx.partName} · ${(dx.confidence * 100).toInt()}%",
              if (phase == Phase.Coaching) steps.getOrNull(stepIndex)?.say.orEmpty() else "Prove it: ${dx.confirmTest}",
              machineLine)
          if (details) ExpertCallCard("Machine: $machineLine\nSeen: ${insp.symptoms.joinToString { it.symptom }}\nSuspected: ${dx.partName}\nTest: ${dx.confirmTest}") { say(it) }

          if (confirmed) {
            UstadCard("Repair coach") {
              Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CoachLanguage.entries.forEach { lang ->
                  FilterChip(selected = language == lang, onClick = { language = lang }, label = { Text(lang.label) })
                }
              }
              when (phase) {
                Phase.Result ->
                    Button(onClick = {
                      phase = Phase.LoadingSteps
                      scope.launch {
                        runCatching { client.repairSteps(insp, dx, language.promptName) }
                            .onSuccess {
                              // AI steps are checked by fixed safety rules before anyone hears them.
                              val review = com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.SafetyGuard.review(
                                  it, "${insp.machine.type} ${insp.machine.brand} ${insp.machine.model}")
                              steps = review.steps
                              safetyFindings = review.findings
                              stepIndex = 0
                              phase = Phase.Coaching
                              review.steps.firstOrNull()?.let { s -> say(s.say) }
                            }
                            .onFailure { phase = Phase.Result; voiceNote = it.message ?: "Couldn't load steps" }
                      }
                    }) { Text("Start repair coach") }
                Phase.LoadingSteps -> UstadBusy("Preparing steps in ${language.promptName}…")
                Phase.Coaching -> {
                  if (safetyFindings.isNotEmpty()) {
                    Text("🛡 Safety check: " + safetyFindings.joinToString(" "), color = Color(0xFFFBBF24), fontSize = 12.sp)
                  }
                  val s = steps.getOrNull(stepIndex)
                  if (s != null) {
                    Text("Step ${stepIndex + 1} of ${steps.size}", color = Muted, fontSize = 12.sp)
                    Text(s.say, color = if (s.safetyStop) Fault else Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    if (s.checkpoint) Text("📸 Checkpoint: photo must show ${s.checkpointMustShow}", color = Color(0xFFFBBF24), fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                      OutlinedButton(enabled = stepIndex > 0, onClick = { stepIndex--; say(steps[stepIndex].say) }) { Text("Back", color = Color.White) }
                      OutlinedButton(onClick = { say(s.say) }) { Text("Repeat", color = Color.White) }
                      Button(onClick = {
                        if (stepIndex < steps.lastIndex) { stepIndex++; say(steps[stepIndex].say) }
                        else {
                          phase = Phase.Done
                          job = job?.copy(steps = steps.map { it.say }, completed = true)?.also { data.history.save(it) }
                          say("Repair complete. Report saved.")
                        }
                      }) { Text(if (stepIndex < steps.lastIndex) "Next" else "Finish") }
                    }
                  }
                }
                Phase.Done -> {
                  Text("✅ Fixed: ${dx.partName}", color = Ok, fontWeight = FontWeight.Bold)
                  UstadBody("Steps completed: ${steps.size} · Cause: ${dx.causeChain.firstOrNull().orEmpty()}")
                  job?.let { j -> OutlinedButton(onClick = { shareText(context, "Ustad job report", j.report()) }) { Text("Share job report", color = Color.White) } }
                }
                else -> Unit
              }
            }
          }
        }
        if (voiceNote.isNotBlank()) Text(voiceNote, color = Muted, fontSize = 12.sp)
      }
    }
  }
}

/** The AI's internal-part layout, or a minimal one built from the diagnoses if it sent none. */
private fun partsLayout(insp: Inspection): String {
  val raw = runCatching { org.json.JSONArray(insp.internalPartsJson) }.getOrNull()
  if (raw != null && raw.length() > 0) return raw.toString()
  val out = org.json.JSONArray()
  insp.diagnoses.forEachIndexed { i, d ->
    out.put(org.json.JSONObject().put("id", d.partId).put("name", d.partName).put("shape", "box")
        .put("size", org.json.JSONArray(listOf(2.4, 1.0, 1.0))).put("layer", 2 - i * 2).put("color", "#8a96a3"))
  }
  out.put(org.json.JSONObject().put("id", "housing").put("name", insp.machine.type.ifBlank { "Housing" }).put("shape", "box")
      .put("size", org.json.JSONArray(listOf(4.0, 2.0, 2.0))).put("layer", 0).put("color", "#5b6475"))
  return out.toString()
}

@Composable
internal fun ExplodedView(
    partIds: List<String>,
    page: String = MachineKind.SPLIT_AC.page,
    partsJson: String? = null,
    height: androidx.compose.ui.unit.Dp = 400.dp,
) {
  // Known machines (car, AC) use their hand-built 3D model; any other machine is drawn from the
  // AI's part layout by the generic viewer. Changing the page needs a new WebView, hence key().
  val url = if (partsJson == null) page else MachineKind.OTHER.page
  androidx.compose.runtime.key(url) { ExplodedWebView(url, partIds, partsJson, height) }
}

@Composable
private fun ExplodedWebView(page: String, partIds: List<String>, partsJson: String?, height: androidx.compose.ui.unit.Dp) {
  val ids = partIds.joinToString(",", "[", "]") { org.json.JSONObject.quote(it) }
  val js = (if (partsJson == null) "" else "build($partsJson); ") + "highlight($ids); explode(true);"
  // With Ray-Ban Display glasses, the exploded view (failing part in red) also goes to the lens.
  val toLens: (android.graphics.Bitmap) -> Unit = { b -> com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.UstadLens.showImage(b) }
  val lens by com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.UstadLens.connected.collectAsState()
  AssetWebView(
      page, js, Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(10.dp)),
      onSnapshot = if (lens) toLens else null,
  )
}

/**
 * A WebView for Ustad's local pages (assets/ustad) that runs [command] once the page has loaded,
 * and again whenever it changes.
 *
 * Crash fix: the old code remembered "page loaded" with view.setTag(android.R.id.title, ...).
 * Android only accepts app resource ids there and throws IllegalArgumentException for framework
 * ids, so the app closed as soon as the 3D view finished loading (right after a diagnosis showed).
 */
@SuppressLint("SetJavaScriptEnabled")
internal class UstadWebView(context: android.content.Context) : WebView(context) {
  private var loaded = false

  /** After each command, a still of the 3D view goes to this (used for the Ray-Ban Display). */
  var onSnapshot: ((android.graphics.Bitmap) -> Unit)? = null

  var command: String = ""
    set(value) {
      if (field == value) return
      field = value
      if (loaded && value.isNotBlank()) exec(value)
    }

  private fun exec(js: String) {
    evaluateJavascript(js, null)
    // Let the explode animation finish, then take a still.
    if (onSnapshot != null) postDelayed({ snapshot() }, 1_800)
  }

  private fun snapshot() {
    val cb = onSnapshot ?: return
    evaluateJavascript("window.snapshot ? window.snapshot() : ''") { quoted ->
      val data = quoted?.trim('"')?.substringAfter("base64,", "").orEmpty()
      if (data.isBlank()) return@evaluateJavascript
      runCatching {
            val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
          }
          .getOrNull()
          ?.let(cb)
    }
  }

  init {
    settings.javaScriptEnabled = true
    setBackgroundColor(android.graphics.Color.rgb(7, 12, 24))
    webChromeClient =
        object : android.webkit.WebChromeClient() {
          override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
            android.util.Log.d("Ustad3D", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
            return true
          }
        }
    webViewClient =
        object : WebViewClient() {
          override fun onPageFinished(view: WebView, url: String?) {
            loaded = true
            if (command.isNotBlank()) exec(command)
          }

          // If the graphics process dies (low memory, GPU driver), drop this view instead of
          // letting Android close the whole app.
          override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            android.util.Log.w("Ustad3D", "3D view renderer gone (crash=${detail.didCrash()})")
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
            return true
          }
        }
  }
}

@Composable
internal fun AssetWebView(page: String, command: String, modifier: Modifier, onSnapshot: ((android.graphics.Bitmap) -> Unit)? = null) {
  AndroidView(
      factory = { ctx ->
        UstadWebView(ctx).apply {
          this.onSnapshot = onSnapshot
          this.command = command
          loadUrl("file:///android_asset/ustad/$page")
        }
      },
      update = {
        it.onSnapshot = onSnapshot
        it.command = command
      },
      modifier = modifier,
  )
}

/** Mirrors what matters on this screen into the Ray-Ban Display lens (no-op without one). */
@Composable
internal fun LensMirror(title: String, body: String, meta: String = "Ustad") {
  LaunchedEffect(title, body, meta) { com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.UstadLens.show(title, body, meta) }
}

@Composable
internal fun UstadCard(title: String, border: Color = CardBorder, content: @Composable () -> Unit) {
  val accent = when (border) { Fault -> Fault; Ok -> Ok; else -> Hud }
  val edge = when (border) { CardBorder, Color(0xFF172A4D) -> CardBorder; else -> border.copy(alpha = 0.55f) }
  Column(
      Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(18.dp))
          .background(CardBg)
          .border(1.dp, edge, RoundedCornerShape(18.dp))
          .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.width(3.dp).height(14.dp).background(accent, RoundedCornerShape(2.dp)))
      Spacer(Modifier.width(8.dp))
      Text(title.uppercase(), color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
    }
    content()
  }
}

@Composable
internal fun UstadBody(text: String) = Text(text, color = Color.White, fontSize = 15.sp)

@Composable
internal fun UstadBusy(text: String) {
  Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
      CircularProgressIndicator(color = Hud)
      Text(text, color = Muted)
    }
  }
}
