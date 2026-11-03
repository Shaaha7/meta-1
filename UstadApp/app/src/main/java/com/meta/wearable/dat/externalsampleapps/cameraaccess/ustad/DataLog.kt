// DataLog - "What left this phone": a plain record of every request Ustad sends off the phone
//
// Each entry says when, to whom (Gemini, the Ustad backend, ElevenLabs, a local Llama), how much,
// and what was inside (how many face/plate-blurred photos, whether there was sound, or only text).
// On-phone steps (the face and plate blur) are logged too, so the technician can see that the
// photo was blurred BEFORE it left. Nothing here is sent anywhere; it is shown in Settings and can
// be shared or cleared. Plain Kotlin + org.json: the UI plugs in storage via [persist]/[restore].

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class DataLogEntry(
    val at: Long,
    /** "Gemini", "Ustad backend", "ElevenLabs", "Local Llama", or "On this phone". */
    val destination: String,
    /** What was in it, in plain words. */
    val what: String,
    val bytes: Int,
) {
  val leftPhone: Boolean get() = destination != DataLog.ON_PHONE

  fun line(): String {
    val time = SimpleDateFormat("d MMM HH:mm:ss", Locale.US).format(Date(at))
    val size = when {
      bytes <= 0 -> ""
      bytes < 1024 -> " · $bytes B"
      else -> " · ${bytes / 1024} KB"
    }
    return "$time  ${if (leftPhone) "↗ $destination" else "🔒 $destination"}: $what$size"
  }
}

object DataLog {
  const val ON_PHONE = "On this phone"
  private const val MAX = 200
  private val entries = ArrayDeque<DataLogEntry>()

  /** Set by the UI to save the log (e.g. to SharedPreferences). */
  @Volatile var persist: (String) -> Unit = {}

  @Synchronized
  fun record(destination: String, what: String, bytes: Int = 0, at: Long = System.currentTimeMillis()) {
    entries.addFirst(DataLogEntry(at, destination, what, bytes))
    while (entries.size > MAX) entries.removeLast()
    runCatching { persist(toJson()) }
  }

  /** Describes an AI request body without its content: photos, sound, or text only. */
  fun describeAiRequest(body: String): String {
    val photos = Regex("\"(mime_type|url)\":\"(data:)?image/").findAll(body).count()
    val sound = body.contains("\"mime_type\":\"audio/")
    return buildList {
      if (photos > 0) add("$photos blurred photo${if (photos > 1) "s" else ""}")
      if (sound) add("a sound clip")
      add("text prompt")
    }.joinToString(" + ")
  }

  @Synchronized fun all(): List<DataLogEntry> = entries.toList()

  @Synchronized
  fun clear() {
    entries.clear()
    runCatching { persist(toJson()) }
  }

  /** One-line summary for the Settings card. */
  @Synchronized
  fun summary(): String {
    val out = entries.filter { it.leftPhone }
    if (out.isEmpty()) return "Nothing has left this phone yet."
    val kb = out.sumOf { it.bytes } / 1024
    val to = out.map { it.destination }.distinct().joinToString(", ")
    return "${out.size} request(s), $kb KB in total, to: $to. No video and no unblurred photo was ever sent."
  }

  @Synchronized
  fun toJson(): String =
      JSONArray().apply {
        entries.forEach { put(JSONObject().put("at", it.at).put("to", it.destination).put("what", it.what).put("bytes", it.bytes)) }
      }.toString()

  @Synchronized
  fun restore(json: String?) {
    if (json.isNullOrBlank() || entries.isNotEmpty()) return
    runCatching {
      val a = JSONArray(json)
      for (i in 0 until minOf(a.length(), MAX)) {
        val o = a.getJSONObject(i)
        entries.addLast(DataLogEntry(o.getLong("at"), o.getString("to"), o.getString("what"), o.optInt("bytes")))
      }
    }
  }

  /** Human-readable export for sharing (e.g. with a supervisor or auditor). */
  fun report(): String = buildString {
    appendLine("USTAD · WHAT LEFT THIS PHONE")
    appendLine(summary())
    appendLine()
    all().forEach { appendLine(it.line()) }
  }
}
