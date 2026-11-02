// AudioCodecTest - ADTS headers, WAV files and the sound analysis behind "Listen"

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCodecTest {
  @Test
  fun adtsHeaderForAacLc() {
    // 100-byte payload, AAC-LC, 44.1 kHz stereo → frame length 107.
    val h = Adts.header(100, 2, 44100, 2)
    assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), 0x0D, 0x7F, 0xFC.toByte()), h)
    // 48 kHz mono: frequency index 3, channel config 1.
    val m = Adts.header(1000, 2, 48000, 1)
    assertEquals(0x4C, m[2].toInt() and 0xFF)
    assertEquals(0x40, m[3].toInt() and 0xC0)
  }

  @Test
  fun wavHeaderAndLength() {
    val wav = Wav.encode(ShortArray(1600) { 1000 }, 16000)
    assertEquals(44 + 3200, wav.size)
    assertEquals("RIFF", String(wav, 0, 4))
    assertEquals("WAVE", String(wav, 8, 4))
    assertEquals(16000, (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8))
  }

  private fun tone(hz: Double, amp: Double, rate: Int = 16000, seconds: Double = 1.0) =
      ShortArray((rate * seconds).toInt()) { (amp * 32767 * sin(2 * PI * hz * it / rate)).toInt().toShort() }

  @Test
  fun findsTheSquealFrequency() {
    val s = SoundStats.analyze(tone(3000.0, 0.5), 16000)
    assertTrue("${s.dominantHz}", abs(s.dominantHz - 3000) < 10)
    assertTrue(s.describe, s.describe.contains("high-pitched"))
    assertEquals(1.0, s.bands.max(), 1e-9)
  }

  @Test
  fun lowHumAndSilence() {
    assertTrue(SoundStats.analyze(tone(120.0, 0.3), 16000).describe.contains("low hum"))
    val quiet = SoundStats.analyze(ShortArray(16000), 16000)
    assertTrue(quiet.silent)
    assertEquals("almost silent", quiet.describe)
  }
}

class MachineEarTest {
  @Test
  fun onlySoundSymptomsAreAskedOfTheEar() {
    val car = KnowledgeBase.parse(java.io.File("src/main/assets/ustad/kb/car.json").readText())
    val ac = KnowledgeBase.parse(java.io.File("src/main/assets/ustad/kb/split_ac.json").readText())
    val carIds = MachineEar.audible(car.symptoms.values).map { it.id }.toSet()
    assertTrue(carIds.containsAll(setOf("car_brake_squeal", "car_slow_crank", "car_rough_idle")))
    assertTrue("car_tire_flat" !in carIds && "car_green_fluid" !in carIds)
    val acIds = MachineEar.audible(ac.symptoms.values).map { it.id }.toSet()
    assertTrue(acIds.containsAll(setOf("noise_rattle", "noise_hiss")))
    assertTrue("water_drip" !in acIds)
  }
}
