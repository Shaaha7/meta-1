// VoiceBar - Talk to Ustad, hands-free
//
// The voice loop: Ustad speaks (natural ElevenLabs voice or the phone voice) → when it finishes,
// the microphone opens → what the technician says becomes a command ("next", "why", "confirmed",
// "التالي", "अगला"...) or a question for Ask Ustad → Ustad acts and speaks again. Push-to-talk
// works too. Ustad never listens while it is speaking, so it doesn't hear itself.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.CoachLanguage
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.Heard
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.UstadEar
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.UstadVoice
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.VoiceCommands
import kotlinx.coroutines.launch

@Stable
class VoiceLoop(private val context: Context) {
  val voice = UstadVoice(context)
  private val ear = UstadEar(context)
  private var silentTries = 0

  /** Keep listening after every line Ustad speaks. */
  var handsFree by mutableStateOf(false)
  var listening by mutableStateOf(false)
    private set
  /** Waiting for Ask Ustad's answer (don't open the mic meanwhile). */
  var thinking by mutableStateOf(false)
  var heard by mutableStateOf("")
    private set
  var status by mutableStateOf("")
  var level by mutableFloatStateOf(0f)
    private set
  var natural by mutableStateOf(UstadVoice.isNatural(context))
    private set

  /** Set by the screen on every composition. */
  var language: CoachLanguage = CoachLanguage.ENGLISH
  var onHeard: (Heard) -> Unit = {}
  internal var requestPermission: () -> Unit = {}

  private fun hasMic() =
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  /** Speaks, then (hands-free) listens. Returns false when no voice is available for the language. */
  fun say(text: String): Boolean {
    ear.stop()
    listening = false
    thinking = false
    val ok = voice.speak(text, language) { if (handsFree) listen() }
    status = if (ok) "" else "No ${language.promptName} voice on this phone (install it in Settings → Text-to-speech)"
    if (!ok && handsFree) listen()
    return ok
  }

  fun listen() {
    if (!hasMic()) {
      requestPermission()
      return
    }
    voice.stop()
    listening = true
    status = ""
    ear.listen(
        language,
        onText = { text ->
          listening = false
          silentTries = 0
          heard = text
          val h = VoiceCommands.parse(text)
          if (h != Heard.Nothing) onHeard(h)
          // Commands that don't make Ustad speak (or noise) go straight back to listening.
          if (handsFree && !voice.isSpeaking && !thinking && !listening) listen()
        },
        onSilence = {
          listening = false
          if (handsFree && ++silentTries < 3) listen()
          else if (handsFree) {
            handsFree = false
            silentTries = 0
            status = "Hands-free paused (no speech). Tap 🎙 to talk."
          }
        },
        onError = { message ->
          listening = false
          handsFree = false
          status = message
        },
        onLevel = { level = it },
    )
  }

  fun toggleHandsFree(on: Boolean) {
    handsFree = on
    silentTries = 0
    if (on && !voice.isSpeaking) listen() else if (!on) { ear.stop(); listening = false }
  }

  fun toggleNatural() {
    natural = !natural
    UstadVoice.setNatural(context, natural)
  }

  fun stopAll() {
    handsFree = false
    ear.stop()
    voice.stop()
    listening = false
  }

  fun dispose() {
    ear.stop()
    voice.shutdown()
  }
}

@Composable
fun rememberVoiceLoop(): VoiceLoop {
  val context = LocalContext.current
  val loop = remember { VoiceLoop(context) }
  DisposableEffect(loop) { onDispose { loop.dispose() } }
  return loop
}

/** Mic, hands-free switch, what Ustad heard, and which voice is speaking. */
@Composable
fun VoiceBar(loop: VoiceLoop, hint: String) {
  val permission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) loop.listen() else loop.status = "Microphone permission is needed to talk to Ustad"
      }
  SideEffect { loop.requestPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) } }

  Column(
      Modifier.fillMaxWidth().background(Color(0xFF0B1730), RoundedCornerShape(12.dp)).padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Button(
          onClick = { if (loop.listening) loop.stopAll() else loop.listen() },
          colors = ButtonDefaults.buttonColors(containerColor = if (loop.listening) Fault else Hud),
      ) {
        Text(if (loop.listening) "■ Stop" else "🎙 Talk", color = Color(0xFF001018), fontWeight = FontWeight.Bold)
      }
      Spacer(Modifier.padding(4.dp))
      Text("Hands-free", color = Color.White, fontSize = 13.sp)
      Switch(checked = loop.handsFree, onCheckedChange = { loop.toggleHandsFree(it) }, modifier = Modifier.padding(start = 4.dp))
      Spacer(Modifier.weight(1f))
      if (UstadVoice.hasElevenLabsKey) {
        Text(
            if (loop.natural) "🗣 Natural" else "🤖 Phone",
            color = Hud,
            fontSize = 12.sp,
            modifier = Modifier.clickable { loop.toggleNatural() }.padding(6.dp),
        )
      }
    }
    val line =
        when {
          loop.listening -> "Listening" + ".".repeat(((loop.level.coerceIn(0f, 10f) / 3.4f).toInt() + 1).coerceIn(1, 3))
          loop.thinking -> "Ustad is thinking…"
          loop.status.isNotBlank() -> loop.status
          loop.heard.isNotBlank() -> "Heard: “${loop.heard}”"
          else -> hint
        }
    Text(line, color = if (loop.listening) Ok else Muted, fontSize = 12.sp)
  }
}

/**
 * Ask Ustad: answers a spoken question from [facts] about the current job, shows it via [onUpdate]
 * (question, answer; answer is "" while thinking) and speaks it.
 */
fun askUstad(
    scope: kotlinx.coroutines.CoroutineScope,
    client: com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.GeminiClient,
    loop: VoiceLoop,
    question: String,
    facts: String,
    onUpdate: (Pair<String, String>) -> Unit,
) {
  onUpdate(question to "")
  loop.thinking = true
  val language = loop.language
  scope.launch {
    val answer =
        runCatching { client.answer(question, facts, language.promptName) }
            .getOrElse { "I couldn't reach the AI right now. Ask your trainer, and don't guess on anything electrical." }
    onUpdate(question to answer)
    loop.say(answer)
  }
}

@Composable
fun AskUstadCard(asked: Pair<String, String>?) {
  val (q, a) = asked ?: return
  UstadCard("🗣 Ask Ustad") {
    Text("“$q”", color = Muted, fontSize = 13.sp)
    if (a.isBlank()) UstadBusy("Ustad is thinking…") else UstadBody(a)
  }
}
