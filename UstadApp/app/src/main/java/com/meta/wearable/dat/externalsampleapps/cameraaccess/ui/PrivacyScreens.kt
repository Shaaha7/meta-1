// PrivacyScreens - first-run consent and the "What left this phone" card
//
// Consent: shown once, before the hub. It says in plain words what Ustad sends, to whom, and what
// never leaves the phone. The log card (Settings tab) lists every request from DataLog, with a
// share button for a supervisor or auditor.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.DataLog

private const val PREFS = "ustad_privacy"

/** Connects DataLog to this phone's storage (once per process). */
internal fun initDataLog(context: Context) {
  val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  DataLog.restore(prefs.getString("log", null))
  DataLog.persist = { json -> prefs.edit().putString("log", json).apply() }
}

internal fun hasPrivacyConsent(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("consent_v1", false)

internal fun givePrivacyConsent(context: Context) =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("consent_v1", true).apply()

private val promises =
    listOf(
        "📸" to "Ustad only takes a photo or records sound when you ask (capture button or your wake word).",
        "🔒" to "Faces and number plates are blurred on this phone first. If the blur fails, nothing is sent.",
        "↗" to "Only the blurred photo and a short text go to the AI (Gemini, or a Llama on your own laptop). Never the video, never your contacts or location.",
        "🗣" to "For the natural voice, only Ustad's reply text goes to ElevenLabs. None of your audio.",
        "🗄" to "Jobs, lessons and the Skill Passport stay on this phone unless you share them.",
        "🧾" to "Every request is listed in Settings → What left this phone, so you can check it.",
    )

@Composable
internal fun PrivacyConsentScreen(onAccept: () -> Unit) {
  UstadPage {
    SectionTitle("BEFORE YOU START")
    Text("Your privacy and the people around you", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
    UstadCard("What Ustad does with data", border = Hud) {
      promises.forEach { (icon, text) ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(icon, fontSize = 18.sp)
          Text(text, color = Color.White, fontSize = 14.sp)
        }
      }
    }
    Text(
        "Tell people nearby when you record (the glasses' light shows it too). In a home or office, ask first. " +
            "For company use, run Ustad through your own backend so the AI keys and logs stay with you.",
        color = Muted,
        fontSize = 12.sp,
    )
    Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) { Text("I understand, start Ustad") }
  }
}

@Composable
internal fun DataLogCard(context: Context) {
  var version by remember { mutableIntStateOf(0) }
  val entries = remember(version) { DataLog.all() }
  UstadCard("🧾 What left this phone", border = Warn) {
    UstadBody(remember(version) { DataLog.summary() })
    if (entries.isEmpty()) {
      Text("Inspect something and every request will be listed here.", color = Muted, fontSize = 12.sp)
    } else {
      entries.take(12).forEach { e ->
        Text(e.line(), color = if (e.leftPhone) Color.White else Ok, fontSize = 12.sp)
      }
      if (entries.size > 12) Text("… and ${entries.size - 12} older", color = Muted, fontSize = 12.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { shareText(context, "Ustad: what left this phone", DataLog.report()) }, enabled = entries.isNotEmpty()) { Text("Share log") }
      OutlinedButton(onClick = { DataLog.clear(); version++ }, enabled = entries.isNotEmpty()) { Text("Clear", color = Color.White) }
      OutlinedButton(onClick = { version++ }) { Text("Refresh", color = Color.White) }
    }
    Text(
        "Requests go straight to the service named, or to your Ustad backend if one is set. " +
            "Note: on Gemini's free tier Google may use requests to improve its products; use a paid key or the backend for real customer jobs.",
        color = Muted,
        fontSize = 11.sp,
    )
  }
}
