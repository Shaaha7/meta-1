// UstadVoice - Ustad's voice: a natural ElevenLabs voice, or the phone's own voice
//
// Natural voice: ElevenLabs text-to-speech when ELEVENLABS_API_KEY is set. Flash v2.5 (~75 ms model
// latency, 32 languages, recommended by ElevenLabs for conversation) for English, Hindi, Arabic and
// Tagalog; Eleven v3 for Urdu and Malayalam, which Flash doesn't cover. Each spoken line is cached
// as an MP3, so repeats cost no credits and replay instantly.
// Phone voice: Android's built-in text-to-speech (the "robot" voice). Used when the technician picks
// it, when there is no key, or when ElevenLabs fails, so Ustad never goes silent.
//
// speak(..., onDone) reports when the line has finished playing; the hands-free voice loop
// (UstadEar) starts listening only then, so Ustad never hears itself.
// Audio is routed by Android: with glasses connected over Bluetooth it plays in their speakers.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

enum class CoachLanguage(
    val label: String,
    val promptName: String,
    val locale: Locale,
    /** ElevenLabs model for this language (null = the configured default, Flash v2.5). */
    val elevenModel: String? = null,
) {
  ENGLISH("English", "English", Locale.US),
  HINDI("हिन्दी", "Hindi", Locale("hi", "IN")),
  ARABIC("العربية", "Arabic", Locale("ar")),
  // The UAE's technician workforce. Flash v2.5 has no Urdu or Malayalam, so those use Eleven v3.
  URDU("اردو", "Urdu", Locale("ur", "PK"), elevenModel = "eleven_v3"),
  MALAYALAM("മലയാളം", "Malayalam", Locale("ml", "IN"), elevenModel = "eleven_v3"),
  TAGALOG("Tagalog", "Tagalog (Filipino)", Locale("fil", "PH")),
}

class UstadVoice(context: Context) {
  companion object {
    private const val TAG = "UstadVoice"
    private const val PREFS = "ustad"
    private const val KEY_NATURAL = "natural_voice"

    /** Technician's choice, shared by every screen: natural ElevenLabs voice or the phone voice. */
    fun setNatural(context: Context, natural: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NATURAL, natural).apply()

    fun isNatural(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NATURAL, true)

    /** A natural voice is available: an ElevenLabs key, or the Ustad backend (which holds it). */
    val hasElevenLabsKey: Boolean
      get() = BuildConfig.ELEVENLABS_API_KEY.isNotBlank() || BuildConfig.USTAD_BACKEND_URL.isNotBlank()
  }

  private val appContext = context.applicationContext
  private val elevenKey = BuildConfig.ELEVENLABS_API_KEY
  private val cacheDir = File(appContext.cacheDir, "ustad-voice").apply { mkdirs() }
  // A failure while fetching or playing a line must never close the app.
  private val scope =
      CoroutineScope(SupervisorJob() + Dispatchers.Main + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.w(TAG, "Voice error", e) })
  private val main = Handler(Looper.getMainLooper())
  private var current: Job? = null
  private var player: MediaPlayer? = null
  private var pendingDone: (() -> Unit)? = null
  private var utterance = 0

  private var ttsReady = false
  private val tts = TextToSpeech(appContext) { status -> ttsReady = status == TextToSpeech.SUCCESS }

  init {
    tts.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(id: String?) {}

          override fun onDone(id: String?) {
            main.post { finished(id) }
          }

          @Deprecated("Deprecated in Java")
          override fun onError(id: String?) {
            main.post { finished(id) }
          }
        },
    )
  }

  private val backend = BuildConfig.USTAD_BACKEND_URL.trimEnd('/')

  /** Speaking with the natural ElevenLabs voice right now (key or backend, and the technician chose it). */
  val usesElevenLabs: Boolean
    get() = hasElevenLabsKey && isNatural(appContext)

  /** True while a line is being fetched or played. */
  var isSpeaking: Boolean = false
    private set

  /**
   * Speaks [text]; [onDone] runs on the main thread when it has finished (or was replaced/stopped:
   * then it does not run). Returns false only when no voice at all is available for this language.
   */
  fun speak(text: String, language: CoachLanguage, onDone: (() -> Unit)? = null): Boolean {
    if (text.isBlank()) return false
    stop()
    val id = ++utterance
    pendingDone = onDone
    isSpeaking = true
    if (!usesElevenLabs) return speakOnDevice(text, language, id)
    current =
        scope.launch {
          val model = language.elevenModel ?: BuildConfig.ELEVENLABS_MODEL
          val mp3 =
              runCatching { withContext(Dispatchers.IO) { fetchElevenLabs(text, model) } }
                  .onFailure { Log.w(TAG, "ElevenLabs failed, using phone voice", it) }
                  .getOrNull()
          if (id != utterance) return@launch
          if (mp3 == null || !play(mp3, id)) {
            if (!speakOnDevice(text, language, id)) finished(id.toString())
          }
        }
    return true
  }

  private fun fetchElevenLabs(text: String, model: String): File {
    val id = sha1("${BuildConfig.ELEVENLABS_VOICE_ID}|$model|$text")
    val file = File(cacheDir, "$id.mp3")
    if (file.length() > 0) return file // cached: no credits used

    val url =
        if (backend.isNotBlank()) URL("$backend/v1/tts/${BuildConfig.ELEVENLABS_VOICE_ID}?output_format=mp3_44100_128")
        else URL("https://api.elevenlabs.io/v1/text-to-speech/${BuildConfig.ELEVENLABS_VOICE_ID}?output_format=mp3_44100_128")
    val conn =
        (url.openConnection() as HttpURLConnection).apply {
          requestMethod = "POST"
          connectTimeout = 8_000
          readTimeout = 20_000
          doOutput = true
          setRequestProperty("Content-Type", "application/json")
          setRequestProperty("Accept", "audio/mpeg")
          if (backend.isNotBlank()) setRequestProperty("X-Ustad-Token", BuildConfig.USTAD_APP_TOKEN)
          else setRequestProperty("xi-api-key", elevenKey)
        }
    DataLog.record(
        if (backend.isNotBlank()) "Ustad backend (voice)" else "ElevenLabs",
        "Ustad's reply as text to be spoken (${text.length} characters); none of your audio",
        text.length,
    )
    try {
      val body =
          JSONObject()
              .put("text", text)
              .put("model_id", model)
              // A calm, steady teacher: stable delivery, close to the chosen voice.
              .put("voice_settings", JSONObject().put("stability", 0.55).put("similarity_boost", 0.8))
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

  private fun play(file: File, id: Int): Boolean =
      runCatching {
            player =
                MediaPlayer().apply {
                  setAudioAttributes(
                      AudioAttributes.Builder()
                          .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                          .build(),
                  )
                  setDataSource(file.absolutePath)
                  setOnCompletionListener {
                    it.release()
                    if (player === it) player = null
                    finished(id.toString())
                  }
                  prepare()
                  start()
                }
          }
          .onFailure { Log.w(TAG, "Couldn't play voice", it); file.delete() }
          .isSuccess

  private fun speakOnDevice(text: String, language: CoachLanguage, id: Int): Boolean {
    if (!ttsReady) { isSpeaking = false; return false }
    val result = tts.setLanguage(language.locale)
    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
      isSpeaking = false
      return false
    }
    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id.toString())
    return true
  }

  /** Runs the onDone callback of utterance [id] if it is still the current one. */
  private fun finished(id: String?) {
    if (id != utterance.toString()) return
    isSpeaking = false
    val done = pendingDone
    pendingDone = null
    done?.invoke()
  }

  fun stop() {
    utterance++ // anything still in flight is now stale
    pendingDone = null
    isSpeaking = false
    current?.cancel()
    player?.run { runCatching { stop() }; release() }
    player = null
    if (ttsReady) tts.stop()
  }

  fun shutdown() {
    stop()
    scope.cancel()
    tts.shutdown()
  }

  private fun sha1(s: String): String =
      MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
