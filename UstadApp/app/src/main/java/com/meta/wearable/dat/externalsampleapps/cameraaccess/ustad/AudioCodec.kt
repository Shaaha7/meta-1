// AudioCodec - Small audio helpers with no Android dependencies (unit-testable)
//
//  - Adts: wraps raw AAC frames (as stored in an MP4 video) in ADTS headers, giving an .aac stream
//    Gemini accepts as audio/aac. Used to send the expert's voice from a lesson recording.
//  - Wav: 16-bit PCM → .wav (audio/wav) for "Listen to the machine".
//  - SoundStats: loudness, dominant pitch and a coarse spectrum, so Ustad can show what it heard
//    and catch obvious things (a loud high-pitched squeal) even before the AI answers.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

object Adts {
  private val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

  /** 7-byte ADTS header for one AAC frame of [payloadLength] bytes. [profile]: AAC object type (2 = LC). */
  fun header(payloadLength: Int, profile: Int, sampleRate: Int, channels: Int): ByteArray {
    val freq = rates.indexOf(sampleRate).let { if (it < 0) 4 else it } // default 44.1 kHz
    val len = payloadLength + 7
    return byteArrayOf(
        0xFF.toByte(),
        0xF1.toByte(), // MPEG-4, layer 0, no CRC
        (((profile - 1) shl 6) or (freq shl 2) or (channels shr 2)).toByte(),
        (((channels and 3) shl 6) or (len shr 11)).toByte(),
        ((len and 0x7FF) shr 3).toByte(),
        (((len and 7) shl 5) or 0x1F).toByte(),
        0xFC.toByte(),
    )
  }
}

object Wav {
  /** Mono 16-bit PCM → WAV file bytes. */
  fun encode(samples: ShortArray, sampleRate: Int): ByteArray {
    val data = samples.size * 2
    val out = ByteArrayOutputStream(44 + data)
    fun int(v: Int) = repeat(4) { out.write((v shr (8 * it)) and 0xFF) }
    fun short(v: Int) = repeat(2) { out.write((v shr (8 * it)) and 0xFF) }
    out.write("RIFF".toByteArray()); int(36 + data); out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(sampleRate); int(sampleRate * 2); short(2); short(16)
    out.write("data".toByteArray()); int(data)
    samples.forEach { short(it.toInt()) }
    return out.toByteArray()
  }
}

data class SoundStats(
    /** Loudness, dBFS (0 = maximum, about -60 = near silence). */
    val loudnessDb: Double,
    /** Strongest frequency, Hz. */
    val dominantHz: Double,
    /** Energy per band (low → high, 0..1 relative), for a small spectrum bar chart. */
    val bands: List<Double>,
) {
  val silent: Boolean
    get() = loudnessDb < -55

  /** A short human description, e.g. "loud, high-pitched (3.2 kHz)". */
  val describe: String
    get() {
      val loud = when { silent -> "almost silent"; loudnessDb > -15 -> "very loud"; loudnessDb > -30 -> "loud"; else -> "quiet" }
      val pitch = when { dominantHz < 250 -> "low hum/rumble"; dominantHz < 2000 -> "mid-pitched"; else -> "high-pitched (squeal/whine range)" }
      val hz = if (dominantHz >= 1000) "%.1f kHz".format(dominantHz / 1000) else "${dominantHz.toInt()} Hz"
      return if (silent) loud else "$loud, $pitch, strongest at $hz"
    }

  companion object {
    private const val N = 4096

    fun analyze(samples: ShortArray, sampleRate: Int, bandCount: Int = 12): SoundStats {
      if (samples.isEmpty()) return SoundStats(-90.0, 0.0, List(bandCount) { 0.0 })
      val rms = sqrt(samples.sumOf { (it / 32768.0) * (it / 32768.0) } / samples.size)
      val db = if (rms <= 1e-9) -90.0 else 20 * log10(rms)
      // Average magnitude spectrum over windows of N samples (Hann window).
      val spectrum = DoubleArray(N / 2)
      var windows = 0
      var start = 0
      while (start + N <= samples.size) {
        val re = DoubleArray(N) { i -> samples[start + i] / 32768.0 * (0.5 - 0.5 * cos(2 * PI * i / (N - 1))) }
        val im = DoubleArray(N)
        fft(re, im)
        for (k in 0 until N / 2) spectrum[k] += hypot(re[k], im[k])
        windows++
        start += N
      }
      if (windows == 0) return SoundStats(db, 0.0, List(bandCount) { 0.0 })
      val minBin = (60.0 * N / sampleRate).toInt().coerceAtLeast(1) // ignore DC and mains-rumble below 60 Hz
      val peak = (minBin until N / 2).maxByOrNull { spectrum[it] } ?: minBin
      val dominant = peak.toDouble() * sampleRate / N
      // Log-spaced bands from 60 Hz to Nyquist.
      val lo = log10(60.0)
      val hi = log10(sampleRate / 2.0)
      val energy = DoubleArray(bandCount)
      for (k in minBin until N / 2) {
        val f = k.toDouble() * sampleRate / N
        val b = (((log10(f) - lo) / (hi - lo)) * bandCount).toInt().coerceIn(0, bandCount - 1)
        energy[b] += spectrum[k] * spectrum[k]
      }
      val max = energy.maxOrNull()?.takeIf { it > 0 } ?: 1.0
      return SoundStats(db, dominant, energy.map { it / max })
    }

    /** In-place radix-2 FFT (N must be a power of two). */
    private fun fft(re: DoubleArray, im: DoubleArray) {
      val n = re.size
      var j = 0
      for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
        j = j xor bit
        if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
      }
      var len = 2
      while (len <= n) {
        val ang = -2 * PI / len
        val wr = cos(ang)
        val wi = sin(ang)
        var i = 0
        while (i < n) {
          var cr = 1.0
          var ci = 0.0
          for (k in 0 until len / 2) {
            val ur = re[i + k]; val ui = im[i + k]
            val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
            val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
            re[i + k] = ur + vr; im[i + k] = ui + vi
            re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
            val nr = cr * wr - ci * wi
            ci = cr * wi + ci * wr
            cr = nr
          }
          i += len
        }
        len = len shl 1
      }
    }
  }
}
