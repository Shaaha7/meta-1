// LessonStudioScreen - Teach: an expert's recording becomes a lesson
//
// 1. Key frames are pulled from the video and faces are blurred on the phone.
// 2. The AI drafts the lesson (steps, why, safety, checkpoints, quiz, parts for the 3D view).
// 3. The expert reviews every step, edits, reorders or deletes, then publishes. Learners only
//    ever see lessons a human expert approved.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.FrameSet
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.GeminiClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.Lesson
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.LessonStep
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.MachineKind
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.VideoFrames
import kotlinx.coroutines.launch

@Composable
fun LessonStudioScreen(video: Uri, client: GeminiClient, onBack: () -> Unit, onDraft: (Lesson) -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var frames by remember { mutableStateOf<FrameSet?>(null) }
  var error by remember { mutableStateOf("") }
  var building by remember { mutableStateOf(false) }
  var note by remember { mutableStateOf("") }

  LaunchedEffect(video) {
    runCatching { VideoFrames.extract(context, video) }
        .onSuccess { frames = it }
        .onFailure { error = it.message ?: "Couldn't read this video" }
  }

  UstadPage {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("TEACH", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
      Spacer(Modifier.padding(6.dp))
      Text("Expert video → lesson", color = Muted, fontSize = 13.sp)
      Spacer(Modifier.weight(1f))
      OutlinedButton(onClick = onBack) { Text("Back", color = Color.White) }
    }

    val fs = frames
    if (fs == null) {
      if (error.isBlank()) UstadBusy("Reading the video and blurring faces…")
      else UstadCard("Couldn't read the video", border = Fault) { Text(error, color = Fault) }
    } else {
      run {
        UstadCard("Key frames (${fs.frames.size} of ${fs.durationSec}s)") {
          Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            fs.frames.forEach { f ->
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    f.image.asImageBitmap(),
                    contentDescription = "Frame at ${f.atSec}s",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)),
                )
                Text("${f.atSec / 60}:${"%02d".format(f.atSec % 60)}", color = Muted, fontSize = 11.sp)
              }
            }
          }
          Text(
              "🔒 ${if (fs.facesBlurred > 0) "${fs.facesBlurred} face(s) blurred on the phone" else "No faces found"} · " +
                  "only these still frames are sent to the AI, never the video or its audio",
              color = Ok,
              fontSize = 12.sp,
          )
        }
        UstadCard("Expert's note (optional)") {
          UstadField(note, { note = it }, "e.g. Replacing the 12V battery on a 2019 Corolla")
          Text("A one-line note makes the lesson much more accurate.", color = Muted, fontSize = 12.sp)
        }
        if (error.isNotBlank()) UstadCard("Couldn't build the lesson", border = Fault) { Text(error, color = Fault) }
        if (building) {
          UstadBusy("Ustad is watching the expert and writing the lesson…")
        } else {
          Button(
              onClick = {
                building = true
                error = ""
                scope.launch {
                  runCatching { client.buildLesson(fs, note) }
                      .onSuccess { lesson ->
                        if (lesson.steps.isEmpty()) error = lesson.summary.ifBlank { "Ustad couldn't see a clear job in this video." }
                        else onDraft(lesson.copy(published = false))
                      }
                      .onFailure { error = it.message ?: "Lesson failed" }
                  building = false
                }
              },
              modifier = Modifier.fillMaxWidth(),
          ) {
            Text("Build lesson")
          }
        }
      }
    }
  }
}

@Composable
fun LessonReviewScreen(initial: Lesson, onBack: () -> Unit, onSave: (Lesson) -> Unit) {
  var lesson by remember { mutableStateOf(initial) }
  var selected by remember { mutableIntStateOf(0) }

  fun setStep(i: Int, step: LessonStep) {
    lesson = lesson.copy(steps = lesson.steps.toMutableList().also { it[i] = step })
  }

  fun move(i: Int, by: Int) {
    val j = i + by
    if (j !in lesson.steps.indices) return
    lesson = lesson.copy(steps = lesson.steps.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t })
    selected = j
  }

  UstadPage {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("REVIEW", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
      Spacer(Modifier.padding(6.dp))
      Text("Expert approves before learners see it", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
      OutlinedButton(onClick = onBack) { Text("Back", color = Color.White) }
    }

    val step = lesson.steps.getOrNull(selected)
    UstadCard("Inside view · step ${selected + 1}") {
      ExplodedView(
          partIds = listOfNotNull(step?.partId?.takeIf { it.isNotBlank() }),
          page = lesson.kind.page,
          partsJson = if (lesson.kind == MachineKind.OTHER) lesson.partsJson else null,
          height = 260.dp,
      )
    }

    UstadCard("Lesson") {
      UstadField(lesson.title, { lesson = lesson.copy(title = it) }, "Title", singleLine = true)
      UstadField(lesson.machine, { lesson = lesson.copy(machine = it) }, "Machine", singleLine = true)
      UstadField(lesson.summary, { lesson = lesson.copy(summary = it) }, "Summary")
    }

    lesson.steps.forEachIndexed { i, s ->
      val open = i == selected
      Column(
          Modifier.fillMaxWidth()
              .background(CardBg, RoundedCornerShape(12.dp))
              .border(1.dp, if (open) Hud else Color.Transparent, RoundedCornerShape(12.dp))
              .clickable { selected = i }
              .padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("STEP ${i + 1}${if (s.checkpoint) " · 📸" else ""}${if (s.atSec >= 0) " · ${s.atSec}s" else ""}",
              color = Hud, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
          Text("↑", color = Color.White, fontSize = 18.sp, modifier = Modifier.clickable { move(i, -1) }.padding(horizontal = 8.dp))
          Text("↓", color = Color.White, fontSize = 18.sp, modifier = Modifier.clickable { move(i, 1) }.padding(horizontal = 8.dp))
          Text("✕", color = Fault, fontSize = 18.sp, modifier = Modifier.clickable {
            lesson = lesson.copy(steps = lesson.steps.filterIndexed { k, _ -> k != i })
            selected = selected.coerceAtMost((lesson.steps.size - 1).coerceAtLeast(0))
          }.padding(horizontal = 8.dp))
        }
        if (!open) {
          UstadBody(s.say)
        } else {
          UstadField(s.say, { setStep(i, s.copy(say = it)) }, "Ustad says (one action)")
          UstadField(s.why, { setStep(i, s.copy(why = it)) }, "Why it matters")
          UstadField(s.safety, { setStep(i, s.copy(safety = it)) }, "Safety (leave empty if none)")
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Photo checkpoint", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = s.checkpoint, onCheckedChange = { setStep(i, s.copy(checkpoint = it)) })
          }
          if (s.checkpoint) UstadField(s.mustShow, { setStep(i, s.copy(mustShow = it)) }, "The photo must show…")
          val parts = lesson.kind.parts
          if (parts.isNotEmpty()) {
            Text("Part in 3D view: ${parts[s.partId] ?: "none"}", color = Muted, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              parts.forEach { (id, name) ->
                Text(
                    name,
                    color = if (id == s.partId) Color(0xFF001018) else Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.background(if (id == s.partId) Hud else Color(0xFF11203D), RoundedCornerShape(12.dp))
                        .clickable { setStep(i, s.copy(partId = id)) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
              }
            }
          }
        }
      }
    }
    OutlinedButton(onClick = {
      lesson = lesson.copy(steps = lesson.steps + LessonStep(title = "New step", say = "New step"))
      selected = lesson.steps.lastIndex
    }) { Text("+ Add step", color = Color.White) }

    if (lesson.quiz.isNotEmpty()) {
      UstadCard("Quiz") {
        lesson.quiz.forEachIndexed { qi, q ->
          Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
              Text("${qi + 1}. ${q.question}", color = Color.White, fontWeight = FontWeight.SemiBold)
              q.options.forEachIndexed { oi, o -> Text("${if (oi == q.answer) "✓" else "·"} $o", color = if (oi == q.answer) Ok else Muted, fontSize = 13.sp) }
            }
            Text("✕", color = Fault, fontSize = 18.sp, modifier = Modifier.clickable {
              lesson = lesson.copy(quiz = lesson.quiz.filterIndexed { k, _ -> k != qi })
            }.padding(horizontal = 8.dp))
          }
        }
      }
    }

    val valid = lesson.steps.isNotEmpty() && lesson.steps.all { it.say.isNotBlank() }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = { onSave(lesson.copy(published = false)) }) { Text("Save draft", color = Color.White) }
      Button(
          enabled = valid,
          onClick = { onSave(lesson.copy(published = true)) },
          colors = ButtonDefaults.buttonColors(containerColor = Ok),
      ) { Text("Approve & publish") }
    }
    Spacer(Modifier.height(24.dp))
  }
}
