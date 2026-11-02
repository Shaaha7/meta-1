// UstadEar - Listens to the technician (speech to text)
//
// Android's SpeechRecognizer in the coaching language. EXTRA_PREFER_OFFLINE keeps recognition on
// the phone when that language pack is installed (Settings → Google → Voice → Offline speech).
// Must be used from the main thread. The microphone is whatever Android routes: the phone, a
// headset, or the glasses over Bluetooth.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class UstadEar(context: Context) {
  private val appContext = context.applicationContext
  private var recognizer: SpeechRecognizer? = null

  val available: Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

  /**
   * Listens for one utterance. [onText] gets what was said; [onSilence] runs when nothing was
   * understood; [onError] for real problems (permission, no recognizer, language not supported).
   */
  fun listen(
      language: CoachLanguage,
      onText: (String) -> Unit,
      onSilence: () -> Unit,
      onError: (String) -> Unit,
      onLevel: (Float) -> Unit = {},
  ) {
    if (!available) return onError("Speech recognition isn't available on this phone")
    stop()
    val r = SpeechRecognizer.createSpeechRecognizer(appContext)
    recognizer = r
    r.setRecognitionListener(
        object : RecognitionListener {
          override fun onReadyForSpeech(params: Bundle?) {}

          override fun onBeginningOfSpeech() {}

          override fun onRmsChanged(rmsdB: Float) = onLevel(rmsdB)

          override fun onBufferReceived(buffer: ByteArray?) {}

          override fun onEndOfSpeech() {}

          override fun onPartialResults(partialResults: Bundle?) {}

          override fun onEvent(eventType: Int, params: Bundle?) {}

          override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (recognizer === r) release()
            if (text.isBlank()) onSilence() else onText(text)
          }

          override fun onError(error: Int) {
            if (recognizer === r) release()
            when (error) {
              SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onSilence()
              SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onError("Microphone permission is needed")
              SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                  onError("${language.promptName} speech recognition isn't installed on this phone")
              SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                  onError("Speech recognition needs the network (or the offline ${language.promptName} pack)")
              SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> onSilence()
              else -> onError("Couldn't hear that (error $error)")
            }
          }
        },
    )
    r.startListening(
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.locale.toLanguageTag())
          putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
          putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
          putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        },
    )
  }

  fun stop() {
    recognizer?.run { runCatching { cancel() } }
    release()
  }

  private fun release() {
    recognizer?.run { runCatching { destroy() } }
    recognizer = null
  }
}
