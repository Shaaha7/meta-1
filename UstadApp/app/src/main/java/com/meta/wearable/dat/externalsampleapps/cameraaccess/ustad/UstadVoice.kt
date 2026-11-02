// UstadVoice - Speaks Ustad's guidance with a natural ElevenLabs voice
//
// Uses ElevenLabs text-to-speech (multilingual: English, Hindi, Arabic) when ELEVENLABS_API_KEY is
// set, caching each spoken line as an MP3 so repeats cost no credits. Falls back to the phone's
// built-in text-to-speech if there is no key or the request fails. Audio is routed by Android, so
// with glasses connected over Bluetooth the technician hears it in the open-ear speakers.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class CoachLanguage(val label: String, val promptName: String, val locale: Locale) {
  ENGLISH("English", "English", Locale.US),
  HINDI("हिन्दी", "Hindi", Locale("hi", "IN")),
  ARABIC("العربية", "Arabic", Locale("ar")),
  // The UAE's technician workforce: phone voices for these may need installing (Settings → TTS);
  // ElevenLabs multilingual voices cover more of them.
  URDU("اردو", "Urdu", Locale("ur", "PK")),
  MALAYALAM("മലയാളം", "Malayalam", Locale("ml", "IN")),
  TAGALOG("Tagalog", "Tagalog (Filipino)", Locale("fil", "PH")),
}

class UstadVoice(context: Context) {
  companion object {
    private const val TAG = "UstadVoice"
  }

  private val appContext = context.applicationContext
  private val elevenKey = BuildConfig.ELEVENLABS_API_KEY
  private val cacheDir = File(appContext.cacheDir, "ustad-voice").apply { mkdirs() }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var current: Job? = null
  private var player: MediaPlayer? = null

  private var ttsReady = false
  private val tts = TextToSpeech(appContext) { status -> ttsReady = status == TextToSpeech.SUCCESS }

  val usesElevenLabs: Boolean
    get() = elevenKey.isNotBlank()

  /** Speaks [text]. Returns false only when no voice at all is available for this language. */
  fun speak(text: String, language: CoachLanguage): Boolean {
    if (text.isBlank()) return false
    stop()
    if (!usesElevenLabs) return speakOnDevice(text, language)
    current =
        scope.launch {
          val mp3 = runCatching { withContext(Dispatchers.IO) { fetchElevenLabs(text) } }
              .onFailure { Log.w(TAG, "ElevenLabs failed, using phone voice", it) }
              .getOrNull()
          if (mp3 != null) play(mp3) else speakOnDevice(text, language)
        }
    return true
  }

  private fun fetchElevenLabs(text: String): File {
    val id = sha1("${BuildConfig.ELEVENLABS_VOICE_ID}|${BuildConfig.ELEVENLABS_MODEL}|$text")
    val file = File(cacheDir, "$id.mp3")
    if (file.length() > 0) return file // cached: no credits used

    val url =
        URL("https://api.elevenlabs.io/v1/text-to-speech/${BuildConfig.ELEVENLABS_VOICE_ID}?output_format=mp3_44100_128")
    val conn =
        (url.openConnection() as HttpURLConnection).apply {
          requestMethod = "POST"
          connectTimeout = 8_000
          readTimeout = 20_000
          doOutput = true
          setRequestProperty("Content-Type", "application/json")
          setRequestProperty("Accept", "audio/mpeg")
          setRequestProperty("xi-api-key", elevenKey)
        }
    try {
      val body =
          JSONObject()
              .put("text", text)
              .put("model_id", BuildConfig.ELEVENLABS_MODEL)
              .put("voice_settings", JSONObject().put("stability", 0.5).put("similarity_boost", 0.75))
      conn.outputStream.use { it.write(body.toString().toByteArray()) }
      if (conn.responseCode !in 200..299) {
        val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        error("ElevenLabs ${conn.responseCode}: ${err.take(200)}")
      }
      val tmp = File(cacheDir, "$id.part")
      conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
      tmp.renameTo(file)
      return file
    } finally {
      conn.disconnect()
    }
  }

  private fun play(file: File) {
    player =
        MediaPlayer().apply {
          setAudioAttributes(
              AudioAttributes.Builder()
                  .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                  .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                  .build(),
          )
          setDataSource(file.absolutePath)
          setOnCompletionListener { it.release(); if (player === it) player = null }
          prepare()
          start()
        }
  }

  private fun speakOnDevice(text: String, language: CoachLanguage): Boolean {
    if (!ttsReady) return false
    val result = tts.setLanguage(language.locale)
    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) return false
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ustad")
    return true
  }

  fun stop() {
    current?.cancel()
    player?.run { runCatching { stop() }; release() }
    player = null
    tts.stop()
  }

  fun shutdown() {
    stop()
    scope.cancel()
    tts.shutdown()
  }

  private fun sha1(s: String): String =
      MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
