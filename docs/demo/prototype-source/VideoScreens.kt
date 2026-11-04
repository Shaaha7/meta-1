// Screens for the prototype walkthrough video, built from the app's design components
// (UstadDesign.kt copy + Shared.kt) with the app's own texts. The 3D views are composited later
// from the app's real three.js files; the magenta box marks where the WebView sits.
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.*

private const val VERSION = "2.7 (privacy consent + data log)"
private val Magenta = Color(0xFFFF00FF)

@Composable
private fun Hub(tab: HubTab, body: @Composable ColumnScope.() -> Unit) {
  UstadTheme {
    Column(Modifier.fillMaxSize().background(Bg)) {
      Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HeroHeader("See inside any machine. Learn by doing.", VERSION,
            listOf("🗣 Natural voice" to Hud, "🧠 7 fixes learned" to Accent, "📚 2 lessons" to Ok, "🔒 On-device privacy" to Warn)) {}
        body()
      }
      UstadNavBar(tab) {}
    }
  }
}

@Composable
private fun Page(body: @Composable ColumnScope.() -> Unit) {
  UstadTheme { Column(Modifier.fillMaxSize().background(Bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { body() } }
}

@Composable
private fun Picture(path: String, height: Int, crop: Boolean = true) {
  val img = org.jetbrains.skia.Image.makeFromEncoded(java.io.File(path).readBytes()).toComposeImageBitmap()
  Image(img, null, modifier = Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(10.dp)),
      contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
}

@Composable
private fun Causes() = UstadCard("Most likely causes") {
  listOf("Corroded or loose battery terminals" to .71f, "12V battery weak or failing" to .17f, "Starter motor" to .07f).forEachIndexed { i, (n, p) ->
    Row { Text(n, color = if (i == 0) Color.White else Muted, modifier = Modifier.weight(1f)); Text("${(p * 100).toInt()}%", color = Color.White) }
    LinearProgressIndicator(progress = p, color = if (i == 0) Fault else Hud, trackColor = Color(0xFF13213D), modifier = Modifier.fillMaxWidth())
  }
  Text("Acid vapour  →  white crust on the terminal  →  high resistance  →  no crank", color = Muted, fontSize = 13.sp)
}

fun renderVideoScreens() {
  render("v_consent") {
    Page {
      SectionTitle("Before you start")
      Text("Your privacy and the people around you", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
      UstadCard("What Ustad does with data", border = Hud) {
        listOf(
            "📸" to "Ustad only takes a photo or records sound when you ask (capture button or your wake word).",
            "🔒" to "Faces and number plates are blurred on this phone first. If the blur fails, nothing is sent.",
            "↗" to "Only the blurred photo and a short text go to the AI (Gemini, or a Llama on your own laptop). Never the video, never your contacts or location.",
            "🗣" to "For the natural voice, only Ustad's reply text goes to ElevenLabs. None of your audio.",
            "🗄" to "Jobs, lessons and the Skill Passport stay on this phone unless you share them.",
            "🧾" to "Every request is listed in Settings → What left this phone, so you can check it.",
        ).forEach { (icon, text) ->
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Text(icon, fontSize = 18.sp); Text(text, color = Color.White, fontSize = 14.sp) }
        }
      }
      Text("Tell people nearby when you record (the glasses' light shows it too). In a home or office, ask first. For company use, run Ustad through your own backend so the AI keys and logs stay with you.",
          color = Muted, fontSize = 12.sp)
      Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("I understand, start Ustad") }
    }
  }
  render("v_inspect") {
    Hub(HubTab.INSPECT) {
      SectionTitle("Find the fault")
      ActionTile(Icons.Default.PhotoCamera, "Take a photo", "Car, engine bay, AC or any machine → the failing part in 3D") {}
      ActionTile(Icons.Default.PhotoLibrary, "From gallery", "Use a photo you already took", Accent) {}
      ActionTile(Icons.Default.BatteryChargingFull, "Battery check", "Lithium packs: health, life left, the weak layer, when to isolate", Ok) {}
      SectionTitle("Prove it")
      ActionTile(Icons.Default.Insights, "Accuracy test", "Score Ustad on photos with a known cause, for your pitch", Warn) {}
      Text("With glasses: start a session and say “Hey Mentor, inspect” or tap capture; the photo opens here.", color = Muted, fontSize = 12.sp)
    }
  }
  render("v_busy") {
    Page {
      UstadCard("Glasses photo") { Picture("/tmp/claude-0/film/test_16.jpg", 230) }
      UstadCard("Ustad is working", border = Hud) {
        Text("🔒 Checked for faces and number plates on this phone", color = Ok, fontSize = 14.sp)
        Text("Ustad is looking and reasoning…", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(color = Hud, trackColor = Color(0xFF13213D), modifier = Modifier.fillMaxWidth())
        Text("Only the blurred photo and a short text prompt are sent.", color = Muted, fontSize = 12.sp)
      }
    }
  }
  render("v_result") {
    Page {
      UstadCard("Machine") {
        Text("Car · Toyota · Corolla 2019", color = Color.White, fontWeight = FontWeight.Bold)
        Text("Expert mode · causes ranked by Ustad's model, not AI guesses", color = Ok, fontSize = 12.sp)
      }
      DetailsToggle(false, "Evidence, brain & history, listen to the machine, why Ustad thinks so") {}
      UstadCard("Inside view") { Box(Modifier.fillMaxWidth().height(250.dp).background(Magenta)) }
      Causes()
    }
  }
  render("v_details") {
    Page {
      DetailsToggle(true, "Evidence, brain & history, listen to the machine, why Ustad thinks so") {}
      UstadCard("How Ustad decided") {
        Picture("/tmp/claude-0/shots/graph.png", 300, crop = false)
        Text("White crust on the terminal: strong for terminals · Battery light on: for terminals and battery · Won't crank: for all three", color = Muted, fontSize = 12.sp)
      }
      UstadCard("Check in this order") {
        listOf(Triple("Wiggle each terminal clamp by hand (engine off).", "Corroded or loose battery terminals · 71%", "1 min"),
               Triple("Measure battery voltage with the engine off.", "12V battery weak or failing · 17%", "2 min")).forEachIndexed { i, (t, n, m) ->
          Row { Text("${i + 1}", color = Hud, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp))
            Column(Modifier.weight(1f)) { Text(t, color = Color.White, fontSize = 14.sp); Text("$n · ~$m", color = Muted, fontSize = 12.sp) } }
        }
        Text("Ordered by likelihood per minute: about 1 min expected to find it.", color = Muted, fontSize = 11.sp)
      }
    }
  }
  render("v_coach") {
    Page {
      UstadCard("Machine") { Text("Car · Toyota · Corolla 2019 · Corroded or loose battery terminals", color = Color.White, fontWeight = FontWeight.Bold) }
      UstadCard("Repair coach · English", border = Hud) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          StatusChip("⚠ SAFETY STOP", Warn); Text("Step 1/4", color = Muted, fontSize = 12.sp)
        }
        Text("Ignition off. Disconnect the negative (−) terminal first.", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Why: removing − first means a slipping spanner can't short the battery to the body.", color = Muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = {}) { Text("Back", color = Color.White) }; OutlinedButton(onClick = {}) { Text("Repeat", color = Color.White) }; Button(onClick = {}) { Text("Next") }
        }
      }
      UstadCard("Voice") {
        Text("🎙 Hands-free · say “next”, “back”, “repeat”, “why”, “check”", color = Color.White, fontSize = 14.sp)
        Text("🕶 Lens: the step and the part are on the Ray-Ban Display", color = Hud, fontSize = 13.sp)
      }
      UstadCard("Prove it") {
        UstadBody("Clean both terminals, then show Ustad.")
        Button(onClick = {}) { Text("📸 Show Ustad the result") }
      }
    }
  }
  render("v_lesson") {
    Hub(HubTab.LEARN) {
      UstadCard("Lesson · Clean corroded 12V battery terminals", border = Hud) {
        Text("Step 3 of 7 · checkpoint", color = Muted, fontSize = 12.sp)
        Text("Brush both terminals with baking-soda paste until the metal shines.", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Expert: “Don't let the paste run into the cells — use a small brush.”", color = Accent, fontSize = 13.sp)
        Button(onClick = {}) { Text("📸 Check my work") }
      }
      UstadCard("Quiz") {
        UstadBody("Which terminal do you disconnect first?")
        listOf("1 · Positive (+)", "2 · Negative (−)  ✓", "3 · Either").forEach { Text(it, color = if ("✓" in it) Ok else Color.White, fontSize = 14.sp) }
      }
    }
  }
  render("v_passport") {
    Page {
      ScreenHeader("SKILL PASSPORT", "Joseph M.") {}
      UstadCard("Latest result", border = Ok) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { BigStat("92", "score", Ok); BigStat("3/3", "quiz", Hud); BigStat("2/2", "checkpoints", Color.White) }
        Text("Score = 60% quiz + 40% checkpoints passed first time", color = Muted, fontSize = 12.sp)
      }
      UstadCard("Badges") {
        Text("★ 12V battery service · 92", color = Warn, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("★ Tyre check · 88", color = Warn, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Badges at 80+ · shown on the team dashboard", color = Muted, fontSize = 12.sp)
      }
    }
  }
  render("v_datalog") {
    Hub(HubTab.SETTINGS) {
      UstadCard("🧾 What left this phone", border = Warn) {
        UstadBody("3 request(s), 61 KB in total, to: Gemini, ElevenLabs. No video and no unblurred photo was ever sent.")
        listOf("4 Nov 09:41:07  🔒 On this phone: Blurred 0 face(s) and 1 number plate(s) before anything was sent" to Ok,
               "4 Nov 09:41:08  ↗ Gemini: 1 blurred photo + text prompt · 58 KB" to Color.White,
               "4 Nov 09:41:12  ↗ ElevenLabs: Ustad's reply as text to be spoken (61 characters); none of your audio" to Color.White,
               "4 Nov 09:42:30  ↗ Gemini: 1 blurred photo + text prompt · 2 KB" to Color.White).forEach { (l, c) -> Text(l, color = c, fontSize = 12.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = {}) { Text("Share log") }; OutlinedButton(onClick = {}) { Text("Clear", color = Color.White) } }
      }
    }
  }
}
