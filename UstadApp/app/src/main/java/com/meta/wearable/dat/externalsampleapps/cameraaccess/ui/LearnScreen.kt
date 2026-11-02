// LearnScreen - Guide: the learner wears the glasses and Ustad talks them through the job
//
// One action per step, spoken in English, Hindi or Arabic, with the part highlighted in the 3D
// exploded view. "Why?" explains the reasoning. At checkpoints the learner shows a photo and Ustad
// checks it. A short quiz on understanding ends the lesson and the result goes into the learner's
// Skill Passport (stored on the phone only).

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.CoachLanguage
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.GeminiClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.Lesson
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.MachineKind
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.PassportEntry
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.PrivacyFilter
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.StepCheck
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.Heard
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.VoiceCommand
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

private enum class LearnPhase { STEPS, QUIZ, DONE }

/** Translates every learner-facing line of a lesson; blank lines are kept as they are. */
private suspend fun translateLesson(client: GeminiClient, lesson: Lesson, language: CoachLanguage): Lesson {
  if (language == CoachLanguage.ENGLISH) return lesson
  val lines = mutableListOf(lesson.title, lesson.summary)
  lesson.steps.forEach { lines += listOf(it.title, it.say, it.why, it.safety, it.mustShow) }
  lesson.quiz.forEach { lines += it.question; lines += it.options; lines += it.explain }
  val idx = lines.indices.filter { lines[it].isNotBlank() }
  val translated = client.translate(idx.map { lines[it] }, language.promptName)
  if (translated.size != idx.size) return lesson
  val out = lines.toMutableList()
  idx.forEachIndexed { k, i -> out[i] = translated[k] }
  var n = 0
  fun next() = out[n++]
  val title = next()
  val summary = next()
  val steps = lesson.steps.map { it.copy(title = next(), say = next(), why = next(), safety = next(), mustShow = next()) }
  val quiz = lesson.quiz.map { q -> q.copy(question = next(), options = q.options.map { next() }, explain = next()) }
  return lesson.copy(title = title, summary = summary, steps = steps, quiz = quiz)
}

@Composable
internal fun LearnScreen(lesson: Lesson, client: GeminiClient, data: UstadData, onExit: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  // Voice loop: Ustad speaks (ElevenLabs or phone voice), then listens for "next", "why", a quiz
  // answer, or any question about the job.
  val loop = rememberVoiceLoop()

  var language by remember { mutableStateOf(CoachLanguage.ENGLISH) }
  var shown by remember { mutableStateOf(lesson) }
  var translating by remember { mutableStateOf(false) }
  val cache = remember { mutableMapOf(CoachLanguage.ENGLISH to lesson) }

  var phase by remember { mutableStateOf(LearnPhase.STEPS) }
  var stepIndex by remember { mutableIntStateOf(0) }
  var showWhy by remember { mutableStateOf(false) }
  var whyAsked by remember { mutableIntStateOf(0) }
  var checking by remember { mutableStateOf(false) }
  var checks by remember { mutableStateOf(mapOf<Int, StepCheck>()) }
  var firstTryPassed by remember { mutableStateOf(setOf<Int>()) }
  var attempted by remember { mutableStateOf(setOf<Int>()) }
  var checkError by remember { mutableStateOf("") }
  var quizIndex by remember { mutableIntStateOf(0) }
  var chosen by remember { mutableStateOf<Int?>(null) }
  var quizCorrect by remember { mutableIntStateOf(0) }
  var result by remember { mutableStateOf<PassportEntry?>(null) }
  var voiceNote by remember { mutableStateOf("") }
  var asked by remember { mutableStateOf<Pair<String, String>?>(null) }

  fun say(text: String) {
    loop.language = language
    loop.say(text)
  }

  val step = shown.steps.getOrNull(stepIndex)
  val checkpoints = lesson.steps.indices.filter { lesson.steps[it].checkpoint }

  fun finishSteps() {
    if (shown.quiz.isEmpty()) phase = LearnPhase.DONE
    else { phase = LearnPhase.QUIZ; say(shown.quiz.first().question) }
  }

  fun nextStep() {
    showWhy = false
    if (stepIndex >= shown.steps.lastIndex) finishSteps() else stepIndex++
  }

  fun explainStep() {
    val s = shown.steps.getOrNull(stepIndex) ?: return
    if (s.why.isBlank() && s.expertWords.isBlank()) { say("There's no extra note for this step. Ask me anything about it."); return }
    if (!showWhy) whyAsked++
    showWhy = true
    // The expert's own words first: that's what the apprentice would have heard on the job.
    say(listOfNotNull(s.expertWords.takeIf { it.isNotBlank() }?.let { "The expert says: $it." }, s.why.takeIf { it.isNotBlank() }).joinToString(" "))
  }

  fun pickOption(oi: Int) {
    val q = shown.quiz.getOrNull(quizIndex) ?: return
    if (chosen != null || oi !in q.options.indices) return
    val answerIndex = lesson.quiz.getOrNull(quizIndex)?.answer ?: 0
    chosen = oi
    if (oi == answerIndex) quizCorrect++
    say((if (oi == answerIndex) "Correct. " else "Not quite. ") + q.explain)
  }

  fun nextQuestion() {
    if (chosen == null) return
    chosen = null
    if (quizIndex < shown.quiz.lastIndex) { quizIndex++; say(shown.quiz[quizIndex].question) }
    else phase = LearnPhase.DONE
  }

  /** Ask Ustad: a free spoken question, answered from this lesson and the current step. */
  fun ask(question: String) {
    val s = lesson.steps.getOrNull(stepIndex)
    val facts =
        buildString {
          appendLine("Lesson: ${lesson.title} (${lesson.machine}). ${lesson.summary}")
          if (s != null && phase == LearnPhase.STEPS) {
            appendLine("Current step ${stepIndex + 1}: ${s.say}")
            if (s.why.isNotBlank()) appendLine("Why (expert's note): ${s.why}")
            if (s.expertWords.isNotBlank()) appendLine("The expert said, in their own words: \"${s.expertWords}\"")
            if (s.safety.isNotBlank()) appendLine("Safety: ${s.safety}")
            if (s.checkpoint) appendLine("Checkpoint: the photo must show ${s.mustShow}")
          }
          appendLine("All steps: " + lesson.steps.mapIndexed { i, x -> "${i + 1}. ${x.say}" }.joinToString(" "))
        }
    loop.language = language
    askUstad(scope, client, loop, question, facts) { asked = it }
  }

  // What the learner said → what Ustad does.
  loop.language = language
  loop.onHeard = { heard ->
    when (heard) {
      is Heard.Question -> ask(heard.text)
      is Heard.Option -> if (phase == LearnPhase.QUIZ) pickOption(heard.index) else ask(loop.heard)
      is Heard.Command ->
          when (phase) {
            LearnPhase.STEPS ->
                when (heard.command) {
                  VoiceCommand.NEXT, VoiceCommand.CONFIRMED, VoiceCommand.YES -> nextStep()
                  VoiceCommand.BACK -> if (stepIndex > 0) { stepIndex--; showWhy = false }
                  VoiceCommand.REPEAT -> shown.steps.getOrNull(stepIndex)?.let { say(it.say) }
                  VoiceCommand.WHY -> explainStep()
                  VoiceCommand.STOP -> loop.stopAll()
                  else -> Unit
                }
            LearnPhase.QUIZ ->
                when (heard.command) {
                  VoiceCommand.NEXT, VoiceCommand.CONFIRMED -> nextQuestion()
                  VoiceCommand.REPEAT -> shown.quiz.getOrNull(quizIndex)?.let { q ->
                    say(q.question + " " + q.options.mapIndexed { i, o -> "${i + 1}: $o." }.joinToString(" "))
                  }
                  VoiceCommand.STOP -> loop.stopAll()
                  else -> Unit
                }
            LearnPhase.DONE -> if (heard.command == VoiceCommand.STOP) loop.stopAll()
          }
      Heard.Nothing -> Unit
    }
  }

  val checkPhoto = rememberPhotoCapture { photo ->
    val i = stepIndex
    val original = lesson.steps[i]
    checking = true
    checkError = ""
    scope.launch {
      runCatching { client.checkStep(PrivacyFilter.blurFaces(photo).image, lesson.machine, original, language.promptName) }
          .onSuccess { r ->
            checks = checks + (i to r)
            if (i !in attempted && r.pass) firstTryPassed = firstTryPassed + i
            say(r.feedback.ifBlank { if (r.pass) "Good." else "Not yet. Try again." })
          }
          .onFailure { checkError = it.message ?: "Couldn't check the photo" }
      attempted = attempted + i
      checking = false
    }
  }

  // Speak each new step.
  LaunchedEffect(stepIndex, phase, shown) {
    if (phase == LearnPhase.STEPS) shown.steps.getOrNull(stepIndex)?.let { say(it.say) }
  }

  // Save the result to the Skill Passport once, when the lesson is done.
  LaunchedEffect(phase) {
    if (phase == LearnPhase.DONE && result == null) {
      val entry =
          PassportEntry(
              lessonId = lesson.id,
              lessonTitle = lesson.title,
              learner = data.learnerName.ifBlank { "Learner" },
              at = System.currentTimeMillis(),
              steps = lesson.steps.size,
              checkpointsPassed = firstTryPassed.size,
              checkpointsTotal = checkpoints.size,
              whyAsked = whyAsked,
              quizCorrect = quizCorrect,
              quizTotal = lesson.quiz.size,
          )
      data.passport.add(entry)
      result = entry
      say("Lesson complete. Score ${entry.score}. ${entry.level}.")
    }
  }

  UstadPage {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("LEARN", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
      Spacer(Modifier.weight(1f))
      OutlinedButton(onClick = { loop.stopAll(); onExit() }) { Text("Exit", color = Color.White) }
    }
    Text(shown.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    if (shown.summary.isNotBlank()) Text(shown.summary, color = Muted, fontSize = 13.sp)

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      CoachLanguage.entries.forEach { lang ->
        FilterChip(
            selected = language == lang,
            enabled = !translating,
            onClick = {
              language = lang
              val cached = cache[lang]
              if (cached != null) shown = cached
              else {
                translating = true
                scope.launch {
                  val t = runCatching { translateLesson(client, lesson, lang) }.getOrDefault(lesson)
                  if (t !== lesson) cache[lang] = t else voiceNote = "Translation unavailable, showing English"
                  shown = t
                  translating = false
                }
              }
            },
            label = { Text(lang.label) },
        )
      }
    }
    if (translating) UstadBusy("Translating to ${language.promptName}…")

    VoiceBar(loop, hint = "Say “next”, “why”, “repeat”, or ask anything about this step.")
    AskUstadCard(asked)
    LensMirror(
        when (phase) { LearnPhase.STEPS -> "Step ${stepIndex + 1}/${shown.steps.size}"; LearnPhase.QUIZ -> "Quiz ${quizIndex + 1}/${shown.quiz.size}"; LearnPhase.DONE -> "Lesson complete" },
        when (phase) {
          LearnPhase.STEPS -> shown.steps.getOrNull(stepIndex)?.say.orEmpty()
          LearnPhase.QUIZ -> shown.quiz.getOrNull(quizIndex)?.let { q -> q.question + " " + q.options.mapIndexed { i, o -> "${i + 1}) $o" }.joinToString(" ") }.orEmpty()
          LearnPhase.DONE -> result?.let { "Score ${it.score} · ${it.level}" }.orEmpty()
        },
        shown.title)

    UstadCard("Inside view") {
      ExplodedView(
          partIds = listOfNotNull(lesson.steps.getOrNull(stepIndex)?.partId?.takeIf { it.isNotBlank() && phase == LearnPhase.STEPS }),
          page = lesson.kind.page,
          partsJson = if (lesson.kind == MachineKind.OTHER) lesson.partsJson else null,
          height = 260.dp,
      )
    }

    when (phase) {
      LearnPhase.STEPS ->
          if (step != null) {
            UstadCard("Step ${stepIndex + 1} of ${shown.steps.size}") {
              LinearProgressIndicator(
                  progress = { (stepIndex + 1f) / shown.steps.size },
                  color = Hud,
                  trackColor = Color(0xFF13213D),
                  modifier = Modifier.fillMaxWidth(),
              )
              Text(step.say, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
              if (step.safety.isNotBlank()) Text("⚠ ${step.safety}", color = Fault, fontWeight = FontWeight.SemiBold)
              if (showWhy && step.expertWords.isNotBlank()) {
                Text("🎙 Expert: “${step.expertWords}”", color = Color.White, modifier = Modifier.background(Color(0xFF1A2A1A), RoundedCornerShape(8.dp)).padding(8.dp))
              }
              if (showWhy && step.why.isNotBlank()) {
                Text("💡 ${step.why}", color = Color(0xFFFBBF24), modifier = Modifier.background(Color(0xFF11203D), RoundedCornerShape(8.dp)).padding(8.dp))
              }
              if (step.checkpoint) {
                val c = checks[stepIndex]
                Text("📸 Checkpoint: show ${step.mustShow.ifBlank { "your work" }}", color = Hud, fontSize = 14.sp)
                when {
                  checking -> UstadBusy("Ustad is checking your work…")
                  c != null -> Text((if (c.pass) "✅ " else "❌ ") + c.feedback, color = if (c.pass) Ok else Fault)
                }
                if (checkError.isNotBlank()) {
                  Text("Couldn't check: $checkError", color = Muted, fontSize = 12.sp)
                  OutlinedButton(onClick = {
                    checks = checks + (stepIndex to StepCheck(true, "Confirmed by trainer"))
                    checkError = ""
                  }) { Text("Trainer confirms ✓", color = Color.White) }
                }
                if (!checking && c?.pass != true) {
                  Button(onClick = checkPhoto) { Text(if (c == null) "Check my work" else "Try again") }
                }
              }
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = stepIndex > 0, onClick = { stepIndex--; showWhy = false }) { Text("Back", color = Color.White) }
                OutlinedButton(onClick = { say(step.say) }) { Text("Repeat", color = Color.White) }
                OutlinedButton(enabled = step.why.isNotBlank() || step.expertWords.isNotBlank(), onClick = { explainStep() }) { Text("Why?", color = Color.White) }
              }
              val last = stepIndex == shown.steps.lastIndex
              Button(
                  onClick = { nextStep() },
                  modifier = Modifier.fillMaxWidth(),
              ) {
                Text(
                    when {
                      step.checkpoint && checks[stepIndex]?.pass != true -> "Skip check & continue"
                      last -> "Finish steps"
                      else -> "Next step"
                    })
              }
            }
          }
      LearnPhase.QUIZ -> {
        val q = shown.quiz.getOrNull(quizIndex)
        val answerIndex = lesson.quiz.getOrNull(quizIndex)?.answer ?: 0
        if (q != null) {
          UstadCard("Check your understanding · ${quizIndex + 1} of ${shown.quiz.size}") {
            Text(q.question, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            q.options.forEachIndexed { oi, option ->
              val picked = chosen
              val colors =
                  when {
                    picked == null -> ButtonDefaults.outlinedButtonColors()
                    oi == answerIndex -> ButtonDefaults.outlinedButtonColors(containerColor = Ok.copy(alpha = .25f))
                    oi == picked -> ButtonDefaults.outlinedButtonColors(containerColor = Fault.copy(alpha = .25f))
                    else -> ButtonDefaults.outlinedButtonColors()
                  }
              OutlinedButton(
                  onClick = { pickOption(oi) },
                  colors = colors,
                  modifier = Modifier.fillMaxWidth(),
              ) { Text(option, color = Color.White) }
            }
            if (chosen != null) {
              if (q.explain.isNotBlank()) Text("💡 ${q.explain}", color = Color(0xFFFBBF24))
              Button(onClick = { nextQuestion() }) { Text(if (quizIndex < shown.quiz.lastIndex) "Next question" else "See my result") }
            }
          }
        }
      }
      LearnPhase.DONE -> {
        val r = result
        if (r != null) PassportCard(r) else UstadBusy("Saving to your Skill Passport…")
        Button(onClick = { loop.stopAll(); onExit() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
      }
    }
    if (voiceNote.isNotBlank()) Text(voiceNote, color = Muted, fontSize = 12.sp)
  }
}

@Composable
private fun PassportCard(e: PassportEntry) {
  UstadCard("🪪 ${e.lessonTitle}") {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("${e.score}", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
      Spacer(Modifier.padding(6.dp))
      Column {
        Text(e.level, color = if (e.score >= 80) Ok else if (e.score >= 50) Color(0xFFFBBF24) else Fault, fontWeight = FontWeight.Bold)
        Text("${e.learner} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.at))}", color = Muted, fontSize = 12.sp)
      }
    }
    LinearProgressIndicator(
        progress = { e.score / 100f },
        color = if (e.score >= 80) Ok else Hud,
        trackColor = Color(0xFF13213D),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Steps ${e.steps} · checkpoints passed first time ${e.checkpointsPassed}/${e.checkpointsTotal} · " +
            "quiz ${e.quizCorrect}/${e.quizTotal} · asked “why?” ${e.whyAsked}×",
        color = Muted,
        fontSize = 13.sp,
    )
  }
}

@Composable
internal fun PassportScreen(data: UstadData, onBack: () -> Unit) {
  val entries = remember { data.passport.entries() }
  UstadPage {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("SKILL PASSPORT", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
      Spacer(Modifier.weight(1f))
      OutlinedButton(onClick = onBack) { Text("Back", color = Color.White) }
    }
    Text(
        "${data.learnerName.ifBlank { "Learner" }} · ${entries.size} lesson(s)" +
            if (entries.isEmpty()) "" else " · average ${entries.map { it.score }.average().toInt()}",
        color = Muted,
    )
    Text("Score = 60% quiz (understands why) + 40% checkpoints passed first time (does it right).", color = Muted, fontSize = 12.sp)
    if (entries.isEmpty()) UstadCard("Nothing yet") { UstadBody("Complete a lesson to start your passport.") }
    entries.forEach { PassportCard(it) }
  }
}
