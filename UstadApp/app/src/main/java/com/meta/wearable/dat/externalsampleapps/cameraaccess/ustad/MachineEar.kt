// MachineEar - "Listen": records the running machine and hears what's wrong
//
// Records a few seconds with the microphone (phone, headset, or glasses over Bluetooth), measures
// loudness, pitch and a coarse spectrum on the phone (SoundStats), and gives the clip to the AI,
// which reports only the sound-related symptoms of the knowledge base (rattle, hiss, squeal, slow
// crank, rough idle...). Those go into the brain's evidence like what the camera saw.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A recorded clip: WAV bytes for the AI plus what the phone measured. */
class WavClip(val bytes: ByteArray, val stats: SoundStats, val seconds: Int)

/** What the AI heard. [symptoms]: symptom id → present (true) / absent (false). */
data class SoundReport(val heard: String, val abnormal: Boolean, val symptoms: Map<String, Boolean>)

object MachineEar {
  const val RATE = 16_000

  private val soundWords =
      listOf("noise", "sound", "squeal", "grind", "rattl", "hiss", "bubbl", "crank", "idle", "shak", "knock", "click", "hum", "whin", "running", "not start", "does not start", "turn on")

  /** Knowledge-base symptoms that can be judged by ear. */
  fun audible(symptoms: Collection<KbSymptom>): List<KbSymptom> =
      symptoms.filter { s -> "${s.label} ${s.question}".lowercase().let { t -> soundWords.any { it in t } } }

  /** Records [seconds] of sound. Needs RECORD_AUDIO; [onProgress] gets 0..1. */
  @SuppressLint("MissingPermission")
  suspend fun record(seconds: Int = 6, onProgress: (Float) -> Unit = {}): WavClip =
      withContext(Dispatchers.IO) {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // UNPROCESSED keeps the machine's real sound (no noise suppression), where the phone supports it.
        val source = runCatching {
          AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
              .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        }.getOrNull()
            ?: AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        check(source.state == AudioRecord.STATE_INITIALIZED) { "Microphone is busy or not allowed" }
        val samples = ShortArray(RATE * seconds)
        try {
          source.startRecording()
          var read = 0
          while (read < samples.size) {
            val n = source.read(samples, read, minOf(2048, samples.size - read))
            if (n <= 0) break
            read += n
            onProgress(read.toFloat() / samples.size)
          }
          val clip = samples.copyOf(read)
          WavClip(Wav.encode(clip, RATE), SoundStats.analyze(clip, RATE), read / RATE)
        } finally {
          runCatching { source.stop() }
          source.release()
        }
      }
}
