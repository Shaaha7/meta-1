// ListenCard - "🎧 Listen": record the running machine, show what the phone measured, and let the
// AI mark the sound symptoms (rattle, hiss, squeal, slow crank, rough idle...) for the brain.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.CoachLanguage
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.GeminiClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.KbSymptom
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.MachineEar
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.SoundReport
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.SoundStats
import kotlinx.coroutines.launch

@Composable
internal fun ListenCard(
    machine: String,
    symptoms: Collection<KbSymptom>,
    client: GeminiClient,
    language: CoachLanguage,
    /** Free the microphone (stop speech recognition) before recording; [after] gives it back. */
    loop: VoiceLoop?,
    onResult: (SoundReport) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var progress by remember { mutableFloatStateOf(-1f) }
  var thinking by remember { mutableStateOf(false) }
  var stats by remember { mutableStateOf<SoundStats?>(null) }
  var heard by remember { mutableStateOf("") }
  var error by remember { mutableStateOf("") }
  val audible = remember(symptoms) { MachineEar.audible(symptoms) }

  fun start() {
    error = ""
    heard = ""
    loop?.pause()
    scope.launch {
      runCatching {
            progress = 0f
            val clip = MachineEar.record(6) { p -> progress = p }
            progress = -1f
            stats = clip.stats
            if (clip.stats.silent) {
              heard = "I couldn't hear anything. Hold the phone or glasses closer to the running machine."
              return@runCatching
            }
            thinking = true
            val report = client.listen(clip, machine, audible.ifEmpty { symptoms }, language.promptName)
            heard = report.heard.ifBlank { clip.stats.describe }
            onResult(report)
          }
          .onFailure { error = it.message ?: "Couldn't listen" }
      progress = -1f
      thinking = false
      loop?.resume()
      if (heard.isNotBlank()) loop?.say(heard)
    }
  }

  val permission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else error = "Microphone permission is needed to listen"
      }

  UstadCard("🎧 Listen") {
    Text("Run the machine and hold the mic near it (6 seconds).", color = Muted, fontSize = 13.sp)
    when {
      progress >= 0f -> {
        Text("Recording…", color = Ok, fontSize = 13.sp)
        LinearProgressIndicator(progress = { progress }, color = Ok, trackColor = Color(0xFF13213D), modifier = Modifier.fillMaxWidth())
      }
      thinking -> UstadBusy("Ustad is listening…")
      else ->
          Button(onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
          }) { Text(if (stats == null) "🎧 Listen to it" else "🎧 Listen again") }
    }
    stats?.let { s ->
      // Coarse spectrum, low → high pitch.
      Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        s.bands.forEach { b ->
          Box(Modifier.weight(1f).fillMaxHeight((0.08 + 0.92 * b).toFloat()).background(Hud.copy(alpha = 0.4f + 0.6f * b.toFloat()), RoundedCornerShape(2.dp)))
        }
      }
      Text("Measured: ${s.describe}", color = Muted, fontSize = 12.sp)
    }
    if (heard.isNotBlank()) UstadBody("👂 $heard")
    if (error.isNotBlank()) Text(error, color = Fault, fontSize = 12.sp)
  }
}
