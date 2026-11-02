// BenchmarkScreen - Accuracy test: "how often is Ustad right?" on photos with a known cause
//
// Pick photos of jobs where you KNOW what the fault was, choose the true cause for each, run.
// Every photo goes through the real pipeline (face/plate blur → AI looks → for cars and ACs the
// knowledge-base brain ranks the causes). The brain is used WITHOUT what it learned on this phone,
// so the score is fair. Result: top-1 / top-3 accuracy and a shareable report for the pitch.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.BenchmarkCase
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.BenchmarkResult
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.GeminiClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.KnowledgeBase
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.MachineKind
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.MachineRouter
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.PrivacyFilter
import kotlinx.coroutines.launch

private class TestPhoto(val uri: Uri, val name: String, val thumb: Bitmap?) {
  var truth by mutableStateOf("")
  var truthLabel by mutableStateOf("Choose the real cause")
}

/** Ustad's ranked answer for one photo (failure ids for car/AC, part names otherwise). */
private suspend fun predict(client: GeminiClient, kbs: Map<MachineKind, KnowledgeBase>, photo: Bitmap): List<String> {
  val safe = PrivacyFilter.blurFaces(photo).image
  val insp = client.inspect(safe, "", kbs.values.flatMap { it.symptoms.values })
  val kb = kbs[MachineRouter.detect(insp)] ?: return insp.diagnoses.map { it.partName }
  val code = listOf("display_error", "car_check_engine").firstOrNull { it in kb.symptoms }
  val evidence = insp.observed.filterKeys { it in kb.symptoms } +
      (if (insp.errorCode.isNotBlank() && code != null) mapOf(code to true) else emptyMap())
  return kb.rank(evidence).take(5).map { it.failure.id }
}

@Composable
internal fun BenchmarkScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val client = remember { GeminiClient() }
  val kbs = remember {
    MachineKind.entries.mapNotNull { k -> k.kbFile?.let { f -> runCatching { k to KnowledgeBase.load(context, f) }.getOrNull() } }.toMap()
  }
  val causes = remember { kbs.flatMap { (k, kb) -> kb.failures.map { Triple(k, it.id, it.name) } } }
  var photos by remember { mutableStateOf<List<TestPhoto>>(emptyList()) }
  var running by remember { mutableStateOf(false) }
  var done by remember { mutableIntStateOf(0) }
  var result by remember { mutableStateOf<BenchmarkResult?>(null) }

  val picker =
      rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        photos = photos + uris.take(60).map { u ->
          TestPhoto(u, u.lastPathSegment?.substringAfterLast('/') ?: "photo", decodeBitmap(context, u, 256))
        }
        result = null
      }

  UstadPage {
    ScreenHeader("ACCURACY TEST", "How often is Ustad right?", onBack)
    UstadCard("How it works") {
      UstadBody("1. Add photos of jobs where you KNOW the real cause.  2. Choose that cause for each.  3. Run. Ustad answers each photo like a real job.")
      Text("Aim for 30+ photos for a number you can trust. The brain's learning on this phone is switched off for the test, so the score is fair.", color = Muted, fontSize = 12.sp)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { picker.safeLaunch(context, "image/*") }, enabled = !running) { Text("Add photos") }
        if (photos.isNotEmpty()) OutlinedButton(onClick = { photos = emptyList(); result = null }, enabled = !running) { Text("Clear", color = Color.White) }
      }
    }

    photos.forEachIndexed { i, p ->
      var open by remember(p) { mutableStateOf(false) }
      Row(
          Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(14.dp)).padding(10.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        p.thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp))) }
        Spacer(Modifier.padding(6.dp))
        Column(Modifier.weight(1f)) {
          Text("${i + 1}. ${p.name}", color = Muted, fontSize = 11.sp)
          Box {
            Text(
                p.truthLabel,
                color = if (p.truth.isBlank()) Color(0xFFFBBF24) else Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(enabled = !running) { open = true }.padding(vertical = 6.dp),
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
              causes.forEach { (kind, id, name) ->
                DropdownMenuItem(
                    text = { Text("${if (kind == MachineKind.CAR) "Car" else "AC"} · $name") },
                    onClick = { p.truth = id; p.truthLabel = name; open = false },
                )
              }
            }
          }
        }
        result?.cases?.getOrNull(i)?.let { c ->
          val r = c.rank
          Text(
              when { c.error.isNotBlank() -> "!"; r == 1 -> "✓"; r != null -> "#$r"; else -> "✗" },
              color = when { r == 1 -> Ok; r != null && r <= 3 -> Color(0xFFFBBF24); else -> Fault },
              fontWeight = FontWeight.Black,
              fontSize = 18.sp,
          )
        }
      }
    }

    val ready = photos.isNotEmpty() && photos.all { it.truth.isNotBlank() }
    if (photos.isNotEmpty()) {
      if (running) {
        Text("Testing photo ${done + 1} of ${photos.size}…", color = Ok)
        LinearProgressIndicator(progress = { done.toFloat() / photos.size }, color = Ok, trackColor = Color(0xFF13213D), modifier = Modifier.fillMaxWidth())
      } else {
        Button(
            enabled = ready,
            onClick = {
              running = true
              done = 0
              result = null
              scope.launch {
                val cases = mutableListOf<BenchmarkCase>()
                for (p in photos) {
                  val started = System.nanoTime()
                  val case =
                      runCatching {
                            val bmp = decodeBitmap(context, p.uri) ?: error("Couldn't open the photo")
                            BenchmarkCase(p.name, p.truth, predict(client, kbs, bmp), (System.nanoTime() - started) / 1e9)
                          }
                          .getOrElse { BenchmarkCase(p.name, p.truth, emptyList(), error = it.message ?: "failed") }
                  cases += case
                  done++
                }
                result = BenchmarkResult(cases)
                running = false
              }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (ready) "Run accuracy test (${photos.size} photos)" else "Choose the real cause for every photo") }
      }
    }

    result?.let { r ->
      UstadCard("Result", border = Hud) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          BigStat("${r.percent(r.top1)}%", "top-1 right", Ok)
          BigStat("${r.percent(r.top3)}%", "in top 3", Hud)
          BigStat("%.1fs".format(r.averageSeconds), "per photo", Color.White)
        }
        Text("${r.top1}/${r.total} first answer right · ${r.top3}/${r.total} in the top 3" + if (r.failed > 0) " · ${r.failed} not analysed" else "",
            color = Muted, fontSize = 12.sp)
        Button(onClick = { shareText(context, "Ustad accuracy test", r.report()) }) { Text("Share report") }
      }
    }
  }
}

@Composable
internal fun BigStat(value: String, label: String, color: Color) {
  Column {
    Text(value, color = color, fontSize = 30.sp, fontWeight = FontWeight.Black)
    Text(label, color = Muted, fontSize = 12.sp)
  }
}
