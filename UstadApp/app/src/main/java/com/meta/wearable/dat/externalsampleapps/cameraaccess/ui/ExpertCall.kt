// ExpertCall - "Live expert": one tap to get a senior technician on the job
//
// Ray-Ban Meta glasses already do WhatsApp video calls that show the glasses' view, so Ustad
// doesn't need its own video server: it sends the expert the job summary (machine, evidence,
// suspected cause, test) on WhatsApp, and tells the technician how to start the call from the
// glasses. A normal phone call is the fallback. The expert's name and number are set in the hub.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object ExpertContact {
  private fun prefs(c: Context) = c.getSharedPreferences("ustad", Context.MODE_PRIVATE)

  fun name(c: Context): String = prefs(c).getString("expert_name", "").orEmpty()

  fun number(c: Context): String = prefs(c).getString("expert_number", "").orEmpty()

  fun set(c: Context, name: String, number: String) =
      prefs(c).edit().putString("expert_name", name.trim()).putString("expert_number", number.trim()).apply()

  /** Digits only, international format without "+" (what wa.me expects). */
  fun waNumber(raw: String): String = raw.filter { it.isDigit() }.removePrefix("00")

  /** Opens WhatsApp (or the wa.me page) with the job summary ready to send. */
  fun message(c: Context, summary: String): Boolean {
    val n = waNumber(number(c))
    if (n.length < 8) return false
    val text = "Ustad: I need your eyes on this job. I'll video call you from my Meta glasses.\n\n$summary"
    safeStart(c, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$n?text=${Uri.encode(text)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
  }

  fun dial(c: Context): Boolean {
    val n = number(c).filter { it.isDigit() || it == '+' }
    if (n.length < 6) return false
    safeStart(c, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
  }
}

/** Calls up the expert; returns a spoken line for the technician. */
fun callExpert(context: Context, summary: String): String {
  val name = ExpertContact.name(context).ifBlank { "your expert" }
  return if (ExpertContact.message(context, summary)) {
    "Sending the job to $name on WhatsApp. Then say: Hey Meta, video call $name on WhatsApp."
  } else {
    Toast.makeText(context, "Add your expert's WhatsApp number in the Ustad hub", Toast.LENGTH_LONG).show()
    "Add your expert's WhatsApp number in the Ustad hub first."
  }
}

@Composable
fun ExpertCallCard(summary: String, say: (String) -> Unit) {
  val context = LocalContext.current
  val name = remember { ExpertContact.name(context).ifBlank { "Expert" } }
  UstadCard("📞 Live expert") {
    UstadBody("Stuck? Get $name on a video call through your glasses, seeing what you see.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { say(callExpert(context, summary)) }) { Text("WhatsApp $name") }
      OutlinedButton(onClick = { if (!ExpertContact.dial(context)) say("Add your expert's number in the Ustad hub first.") }) {
        Text("Phone", color = Color.White)
      }
    }
    Text(
        "On Ray-Ban Meta: “Hey Meta, video call $name on WhatsApp”, then double-press the capture button to show your glasses view. " +
            "Or say “call expert” to Ustad.",
        color = Muted,
        fontSize = 12.sp,
    )
  }
}
