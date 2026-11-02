// UstadHubScreen - Home of Ustad: Inspect · Teach · Learn · Skill Passport
//
// Works with or without glasses. With glasses, photos and recordings come from the glasses stream
// (CameraScreen). Without them, the phone camera or gallery stands in, so the whole flow can be
// built and demoed from Android Studio with just a phone.
//
//   Inspect - photo of a machine → defect → hidden failing part in the 3D exploded view
//   Teach   - expert's recording → AI draft lesson → expert reviews and publishes
//   Learn   - learner is guided step by step, photo checkpoints, quiz → Skill Passport

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.core.content.FileProvider
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.CaseMemory
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.GeminiClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.MachineHistory
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.Lesson
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.LessonStore
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.SkillPassport
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.parseLesson
import java.io.File
import org.json.JSONObject

private sealed interface HubPage {
  data object Home : HubPage
  data class Inspect(val photo: Bitmap) : HubPage
  data class Studio(val video: Uri) : HubPage
  data class Review(val lesson: Lesson) : HubPage
  data class Learn(val lesson: Lesson) : HubPage
  data object Passport : HubPage
}

/** Everything Ustad keeps on the phone. Nothing here is uploaded. */
internal class UstadData(context: Context) {
  private val app = context.applicationContext
  val store = LessonStore(File(app.filesDir, "ustad/lessons"))
  val passport = SkillPassport(File(app.filesDir, "ustad/passport.json"))
  /** Confirmed fixes the brain learns from. */
  val cases = CaseMemory(File(app.filesDir, "ustad/cases.json"))
  /** Every visit per machine, for history and job reports. */
  val history = MachineHistory(File(app.filesDir, "ustad/history.json"))
  private val prefs = app.getSharedPreferences("ustad", Context.MODE_PRIVATE)

  var learnerName: String
    get() = prefs.getString("learner", "").orEmpty()
    set(v) = prefs.edit().putString("learner", v.trim()).apply()

  val builtIns: List<Lesson> by lazy {
    (app.assets.list("ustad/lessons") ?: emptyArray()).filter { it.endsWith(".json") }.mapNotNull { f ->
      runCatching { parseLesson(JSONObject(app.assets.open("ustad/lessons/$f").bufferedReader().use { it.readText() }), builtIn = true) }.getOrNull()
    }
  }
}

@Composable
fun UstadHubScreen(onClose: () -> Unit, initialVideo: Uri? = null) {
  val context = LocalContext.current
  val data = remember { UstadData(context) }
  val client = remember { GeminiClient() }
  var page by remember { mutableStateOf<HubPage>(initialVideo?.let { HubPage.Studio(it) } ?: HubPage.Home) }
  var refresh by remember { mutableIntStateOf(0) }
  val home = { refresh++; page = HubPage.Home }

  BackHandler(enabled = page != HubPage.Home) { home() }
  BackHandler(enabled = page == HubPage.Home) { onClose() }

  when (val p = page) {
    is HubPage.Inspect -> UstadScreen(photo = p.photo, onDismiss = home)
    is HubPage.Studio ->
        LessonStudioScreen(
            video = p.video,
            client = client,
            onBack = { if (initialVideo != null) onClose() else home() },
            onDraft = { lesson -> data.store.save(lesson); page = HubPage.Review(lesson) },
        )
    is HubPage.Review ->
        LessonReviewScreen(
            initial = p.lesson,
            onBack = home,
            onSave = { lesson ->
              data.store.save(lesson)
              Toast.makeText(context, if (lesson.published) "Published for learners" else "Draft saved", Toast.LENGTH_SHORT).show()
              if (initialVideo != null && lesson.published) onClose() else home()
            },
        )
    is HubPage.Learn -> LearnScreen(lesson = p.lesson, client = client, data = data, onExit = home)
    HubPage.Passport -> PassportScreen(data = data, onBack = home)
    HubPage.Home -> {
      val lessons = remember(refresh) { data.builtIns + data.store.list() }
      HubHome(
          data = data,
          lessons = lessons,
          onClose = onClose,
          onInspect = { page = HubPage.Inspect(it) },
          onTeach = { page = HubPage.Studio(it) },
          onOpen = { lesson -> page = if (lesson.published) HubPage.Learn(lesson) else HubPage.Review(lesson) },
          onEdit = { page = HubPage.Review(it) },
          onDelete = { data.store.delete(it.id); refresh++ },
          onPassport = { page = HubPage.Passport },
      )
    }
  }
}

@Composable
private fun HubHome(
    data: UstadData,
    lessons: List<Lesson>,
    onClose: () -> Unit,
    onInspect: (Bitmap) -> Unit,
    onTeach: (Uri) -> Unit,
    onOpen: (Lesson) -> Unit,
    onEdit: (Lesson) -> Unit,
    onDelete: (Lesson) -> Unit,
    onPassport: () -> Unit,
) {
  val context = LocalContext.current
  val takePhoto = rememberPhotoCapture(onInspect)
  val pickPhoto =
      rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { decodeBitmap(context, it) }?.let(onInspect)
      }
  val recordVideo = rememberVideoCapture(onTeach)
  val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(onTeach) }
  var name by remember { mutableStateOf(data.learnerName) }

  UstadPage {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("USTAD", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
      Spacer(Modifier.padding(6.dp))
      Text("See inside. Learn by doing.", color = Muted, fontSize = 13.sp)
      Spacer(Modifier.weight(1f))
      OutlinedButton(onClick = onClose) { Text("Close", color = Color.White) }
    }
    Text(
        "Expert does the job → Ustad turns it into a lesson → learner is guided step by step. " +
            "Point at a machine to see the hidden failing part.",
        color = Muted,
        fontSize = 13.sp,
    )

    UstadCard("🔍 Inspect a machine") {
      UstadBody("Photo of a car, engine bay or machine → defect, cause chain, and the hidden part in 3D.")
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = takePhoto) { Text("Take photo") }
        OutlinedButton(onClick = { pickPhoto.launch("image/*") }) { Text("From gallery", color = Color.White) }
      }
      Text("With glasses: start a session and tap capture; the photo opens here.", color = Muted, fontSize = 12.sp)
    }

    UstadCard("🎓 Teach: expert records a job") {
      UstadBody("Record the expert doing the job (glasses or head-mounted phone). Ustad drafts the lesson; the expert approves it.")
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = recordVideo) { Text("Record") }
        OutlinedButton(onClick = { pickVideo.launch("video/*") }) { Text("Pick video", color = Color.White) }
      }
    }

    UstadCard("📚 Lessons") {
      if (lessons.isEmpty()) UstadBody("No lessons yet.")
      lessons.forEach { l ->
        Column(
            Modifier.fillMaxWidth().background(Color(0xFF11203D), RoundedCornerShape(8.dp)).clickable { onOpen(l) }.padding(10.dp),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(l.title, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                when { l.builtIn -> "BUILT-IN"; l.published -> "PUBLISHED"; else -> "DRAFT" },
                color = if (l.published) Ok else Color(0xFFFBBF24),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
          }
          Text("${l.machine} · ${l.steps.size} steps · ${l.quiz.size} quiz · by ${l.author}", color = Muted, fontSize = 12.sp)
          if (!l.builtIn) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Text("Edit", color = Hud, fontSize = 13.sp, modifier = Modifier.clickable { onEdit(l) }.padding(top = 4.dp))
              Text("Delete", color = Fault, fontSize = 13.sp, modifier = Modifier.clickable { onDelete(l) }.padding(top = 4.dp))
            }
          }
        }
      }
    }

    UstadCard("🪪 Skill Passport") {
      UstadField(name, { name = it; data.learnerName = it }, "Learner name", singleLine = true)
      val entries = remember { data.passport.entries() }
      UstadBody(
          if (entries.isEmpty()) "No lessons completed yet."
          else "${entries.size} lesson(s) completed · average score ${entries.map { it.score }.average().toInt()}")
      OutlinedButton(onClick = onPassport) { Text("Open passport", color = Color.White) }
    }

    UstadCard("🧠 Brain & machine history") {
      var version by remember { mutableIntStateOf(0) }
      val cases = remember(version) { data.cases.all() }
      val jobs = remember(version) { data.history.all() }
      val importer =
          rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val added =
                runCatching { context.contentResolver.openInputStream(uri)!!.bufferedReader().use { data.cases.import(it.readText()) } }
            Toast.makeText(
                    context,
                    added.fold({ "Learned $it new confirmed fix(es)" }, { "Not an Ustad knowledge file" }),
                    Toast.LENGTH_SHORT)
                .show()
            version++
          }
      UstadBody(
          "Every fix confirmed with a test teaches Ustad. ${cases.size} confirmed fix(es) learned · " +
              "${jobs.map { it.machineKey }.filter { it.isNotBlank() }.distinct().size} machine(s) in history.")
      jobs.take(5).forEach { j ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(j.cause, color = Color.White, fontSize = 14.sp)
            Text(
                "${j.machine} · ${java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(j.at))}" +
                    if (j.completed) " · fixed" else "",
                color = Muted,
                fontSize = 12.sp,
            )
          }
          Text("Report", color = Hud, fontSize = 13.sp, modifier = Modifier.clickable { shareText(context, "Ustad job report", j.report()) }.padding(8.dp))
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = cases.isNotEmpty(), onClick = { shareFile(context, "ustad-knowledge.json", "application/json", data.cases.export()) }) {
          Text("Share knowledge", color = Color.White)
        }
        OutlinedButton(onClick = { importer.launch("*/*") }) { Text("Import", color = Color.White) }
      }
      Text("Share knowledge sends what this phone learned to another technician; Import merges theirs.", color = Muted, fontSize = 12.sp)
    }

    UstadCard("🗣 Ustad's voice") {
      var natural by remember { mutableStateOf(com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.UstadVoice.isNatural(context)) }
      val hasKey = com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.UstadVoice.hasElevenLabsKey
      UstadBody(
          if (!hasKey) "Phone voice. Add ELEVENLABS_API_KEY to local.properties for Ustad's natural ElevenLabs voice."
          else if (natural) "Natural ElevenLabs voice (Flash v2.5; Eleven v3 for Urdu and Malayalam)."
          else "Phone voice (robotic, works offline).")
      if (hasKey) {
        OutlinedButton(onClick = {
          natural = !natural
          com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.UstadVoice.setNatural(context, natural)
        }) { Text(if (natural) "Use phone voice" else "Use natural voice", color = Color.White) }
      }
      var wake by remember { mutableStateOf(WakeWord.get(context)) }
      UstadField(wake, { wake = it; if (it.isNotBlank()) WakeWord.set(context, it) }, "Your wake word", singleLine = true)
      Text(
          "Pick a word that's easy to say and unusual in normal talk (two words work best, in any language). " +
              "With the wake word on, Ustad listens all the time and acts only on “$wake, …”: " +
              "“$wake, inspect”, “$wake, start lesson”, “$wake, stop lesson”, “$wake, why?”.",
          color = Muted,
          fontSize = 12.sp,
      )
      Text(
          "Talk back: tap 🎙, Hands-free, or the wake word. Say “next”, “back”, “repeat”, “why”, “yes”, “no”, “not sure”, " +
              "“confirmed”, “not this”, “check”, “stop”, in any of the six languages, or ask any question about the job.",
          color = Muted,
          fontSize = 12.sp,
      )
    }

    UstadCard("🔒 What leaves this phone") {
      Text(
          "• Photos and video frames are taken only when you tap.\n" +
              "• Faces are blurred ON the phone before anything is sent.\n" +
              "• For lessons, only ~10 blurred still frames are sent, never the video or its audio.\n" +
              "• The diagnosis model for cars and ACs runs on the phone; the AI only reports what it sees.\n" +
              "• Lessons, the Skill Passport, machine history and what the brain learned stay on this phone (shared only when you tap Share).\n" +
              "• Voice: what you say is recognised on the phone when the offline language pack is installed; Ustad's spoken lines go to ElevenLabs only with the natural voice on.\n" +
              "• Offline mode: a local Llama model on the laptop, nothing goes to the internet.",
          color = Color.White,
          fontSize = 13.sp,
      )
    }
  }
}

// ---------- shared helpers for the Ustad screens ----------

/** Opens the system share sheet with plain text (job reports). */
internal fun shareText(context: Context, subject: String, text: String) {
  val send =
      android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
        putExtra(android.content.Intent.EXTRA_TEXT, text)
      }
  context.startActivity(android.content.Intent.createChooser(send, subject).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Shares a file from Ustad's cache folder (exported brain knowledge). */
internal fun shareFile(context: Context, name: String, mime: String, text: String) {
  val dir = File(context.cacheDir, "ustad").apply { mkdirs() }
  val file = File(dir, name).apply { writeText(text) }
  val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
  val send =
      android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mime
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
  context.startActivity(android.content.Intent.createChooser(send, name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Ustad's reasoning graph (assets/ustad/kb_graph.html), drawn from BrainGraph.reasoningGraph. */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BrainGraphView(graphJson: String) {
  val rows = remember(graphJson) {
    runCatching {
      val o = JSONObject(graphJson)
      maxOf(o.getJSONArray("symptoms").length(), o.getJSONArray("failures").length(), o.getJSONArray("parts").length(), 1)
    }.getOrDefault(6)
  }
  val js = "graph($graphJson);"
  androidx.compose.ui.viewinterop.AndroidView(
      factory = { ctx ->
        android.webkit.WebView(ctx).apply {
          settings.javaScriptEnabled = true
          setBackgroundColor(android.graphics.Color.rgb(7, 12, 24))
          webViewClient =
              object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                  view.setTag(android.R.id.title, true)
                  (view.tag as? String)?.let { view.evaluateJavascript(it, null) }
                }
              }
          tag = js
          loadUrl("file:///android_asset/ustad/kb_graph.html")
        }
      },
      update = { view ->
        if (view.tag != js) {
          view.tag = js
          if (view.getTag(android.R.id.title) == true) view.evaluateJavascript(js, null)
        }
      },
      // Same aspect ratio as the SVG (400 wide, 24 + 40 per row tall) plus the legend.
      modifier = Modifier.fillMaxWidth().aspectRatio(400f / (24 + rows * 40 + 44)),
  )
}

/** Full-screen dark scrolling page used by all Ustad screens. */
@Composable
internal fun UstadPage(content: @Composable () -> Unit) {
  Column(
      Modifier.fillMaxSize()
          .background(Bg)
          .statusBarsPadding()
          .navigationBarsPadding()
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    content()
  }
}

@Composable
internal fun UstadField(value: String, onChange: (String) -> Unit, label: String, singleLine: Boolean = false) {
  OutlinedTextField(
      value = value,
      onValueChange = onChange,
      label = { Text(label) },
      singleLine = singleLine,
      modifier = Modifier.fillMaxWidth(),
      colors =
          OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color.White,
              unfocusedTextColor = Color.White,
              focusedLabelColor = Hud,
              unfocusedLabelColor = Muted,
              focusedBorderColor = Hud,
              unfocusedBorderColor = Color(0xFF2A3D63),
              cursorColor = Hud,
          ),
  )
}

/** Decodes a photo into a mutable, software bitmap no larger than [maxSide] px. */
internal fun decodeBitmap(context: Context, uri: Uri, maxSide: Int = 1600): Bitmap? =
    runCatching {
          ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = maxSide.toFloat() / maxOf(info.size.width, info.size.height)
            if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
          }
        }
        .getOrNull()

private fun newCaptureUri(context: Context, ext: String): Uri {
  val dir = File(context.cacheDir, "ustad").apply { mkdirs() }
  val file = File(dir, "capture_${System.currentTimeMillis()}.$ext")
  return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/**
 * Phone-camera photo (stand-in for the glasses). The app declares CAMERA, so the system camera
 * intent needs the permission granted first.
 */
@Composable
internal fun rememberPhotoCapture(onPhoto: (Bitmap) -> Unit): () -> Unit {
  val context = LocalContext.current
  var pending by remember { mutableStateOf<Uri?>(null) }
  val take =
      rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pending
        if (ok && uri != null) decodeBitmap(context, uri)?.let(onPhoto)
      }
  val launch = { newCaptureUri(context, "jpg").also { pending = it }.let { take.launch(it) } }
  val permission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launch() else Toast.makeText(context, "Camera permission is needed", Toast.LENGTH_SHORT).show()
      }
  return {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launch()
    else permission.launch(Manifest.permission.CAMERA)
  }
}

/** Phone-camera video of the expert doing the job (stand-in for a glasses recording). */
@Composable
internal fun rememberVideoCapture(onVideo: (Uri) -> Unit): () -> Unit {
  val context = LocalContext.current
  var pending by remember { mutableStateOf<Uri?>(null) }
  val record =
      rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val uri = pending
        if (ok && uri != null) onVideo(uri)
      }
  val launch = { newCaptureUri(context, "mp4").also { pending = it }.let { record.launch(it) } }
  val permission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launch() else Toast.makeText(context, "Camera permission is needed", Toast.LENGTH_SHORT).show()
      }
  return {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launch()
    else permission.launch(Manifest.permission.CAMERA)
  }
}
