// VoiceBar - Talk to Ustad, hands-free
//
// The voice loop: Ustad speaks (natural ElevenLabs voice or the phone voice) → when it finishes,
// the microphone opens → what the technician says becomes a command ("next", "why", "confirmed",
// "التالي", "अगला"...) or a question for Ask Ustad → Ustad acts and speaks again. Push-to-talk
// works too. Ustad never listens while it is speaking, so it doesn't hear itself.
//
// Wake-word mode: Ustad listens all the time but only acts when the technician starts with their
// own wake word (set in the Ustad hub, e.g. "Hey Mentor, inspect"). Saying only the word makes
// Ustad answer "Yes?" and take the next sentence as the command.

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
import androidx.compose.runtime.collectAsState
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

/** The technician's own wake word, kept on the phone. */
object WakeWord {
  const val DEFAULT = "Hey Mentor"

  fun get(context: Context): String =
      context.getSharedPreferences("ustad", Context.MODE_PRIVATE).getString("wake_word", DEFAULT).orEmpty().ifBlank { DEFAULT }

  fun set(context: Context, word: String) =
      context.getSharedPreferences("ustad", Context.MODE_PRIVATE).edit().putString("wake_word", word.trim()).apply()
}

@Stable
class VoiceLoop(private val context: Context) {
  val voice = UstadVoice(context)
  private val ear = UstadEar(context)
  private var silentTries = 0

  /** Keep listening after every line Ustad speaks. */
  var handsFree by mutableStateOf(false)
  /** Always listening, acting only on sentences that start with the wake word. */
  var wakeMode by mutableStateOf(false)
    private set
  /** The wake word was said on its own: the next sentence is the command. */
  private var awaitingCommand = false
  /** Paused by the screen (e.g. another screen is showing); wake mode resumes after. */
  private var paused = false
  private val main = android.os.Handler(android.os.Looper.getMainLooper())
  val wakeWord: String
    get() = WakeWord.get(context)
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
    val ok = voice.speak(text, language) { if (handsFree || wakeMode) listen() }
    status = if (ok) "" else "No ${language.promptName} voice on this phone (install it in Settings → Text-to-speech)"
    if (!ok && (handsFree || wakeMode)) listen()
    return ok
  }

  private val keepListening: Boolean
    get() = (handsFree || wakeMode) && !paused

  fun listen() {
    if (paused) return
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
          val wake = wakeWord
          val command: String? =
              when {
                !wakeMode || awaitingCommand -> text
                else -> VoiceCommands.afterWakeWord(text, wake) // null: not for Ustad
              }
          awaitingCommand = false
          if (command != null) {
            heard = text
            if (wakeMode && command.isBlank()) {
              awaitingCommand = true
              say("Yes?")
            } else {
              val h = VoiceCommands.parse(command, wake)
              if (h != Heard.Nothing) onHeard(h)
            }
          }
          // Commands that don't make Ustad speak (or noise) go straight back to listening.
          if (keepListening && !voice.isSpeaking && !thinking && !listening) listen()
        },
        onSilence = {
          listening = false
          awaitingCommand = false
          when {
            wakeMode && !paused -> listen() // always on: keep waiting for the wake word
            handsFree && ++silentTries < 3 -> listen()
            handsFree -> {
              handsFree = false
              silentTries = 0
              status = "Hands-free paused (no speech). Tap 🎙 to talk."
            }
          }
        },
        onError = { message ->
          listening = false
          if (wakeMode && !paused && hasMic()) {
            // Mic busy (e.g. recording) or a network blip: try again shortly.
            status = message
            main.postDelayed({ if (wakeMode && !listening && !voice.isSpeaking) listen() }, 2_000)
          } else {
            handsFree = false
            wakeMode = false
            status = message
          }
        },
        onLevel = { level = it },
        quiet = wakeMode,
    )
  }

  fun toggleHandsFree(on: Boolean) {
    handsFree = on
    silentTries = 0
    if (on && !voice.isSpeaking) listen() else if (!on && !wakeMode) { ear.stop(); listening = false }
  }

  fun toggleWake(on: Boolean) {
    wakeMode = on
    awaitingCommand = false
    if (on) {
      status = "Say “$wakeWord” then a command."
      if (!voice.isSpeaking && !listening) listen()
    } else if (!handsFree) {
      ear.stop()
      listening = false
    }
  }

  /** Another screen took over: stop the mic and voice, but remember the modes. */
  fun pause() {
    paused = true
    ear.stop()
    voice.stop()
    listening = false
  }

  fun resume() {
    paused = false
    if (wakeMode || handsFree) listen()
  }

  fun toggleNatural() {
    natural = !natural
    UstadVoice.setNatural(context, natural)
  }

  /** "Stop": Ustad goes quiet. Hands-free ends; wake-word mode keeps waiting for the word. */
  fun stopAll() {
    handsFree = false
    awaitingCommand = false
    ear.stop()
    voice.stop()
    listening = false
    if (wakeMode && !paused) listen()
  }

  fun dispose() {
    main.removeCallbacksAndMessages(null)
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
      val lens by com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.UstadLens.connected.collectAsState()
      if (lens) Text("🕶 Lens", color = Ok, fontSize = 12.sp, modifier = Modifier.padding(6.dp))
      if (UstadVoice.hasElevenLabsKey) {
        Text(
            if (loop.natural) "🗣 Natural" else "🤖 Phone",
            color = Hud,
            fontSize = 12.sp,
            modifier = Modifier.clickable { loop.toggleNatural() }.padding(6.dp),
        )
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Wake word “${loop.wakeWord}”", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
      Switch(checked = loop.wakeMode, onCheckedChange = { loop.toggleWake(it) })
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
