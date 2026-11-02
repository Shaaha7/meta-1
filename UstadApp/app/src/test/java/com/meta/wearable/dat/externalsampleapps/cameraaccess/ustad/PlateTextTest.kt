// PlateTextTest - Plates are blurred, nameplates and error codes are not

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateTextTest {
  @Test
  fun uaePlates() {
    listOf("A 12345", "N 1234", "12 34567", "AA 5555", "DUBAI", "Abu Dhabi", "دبي", "الشارقة", "SHARJAH 3").forEach {
      assertTrue(it, PlateText.shouldBlur(it))
    }
  }

  @Test
  fun internationalPlates() {
    listOf("AB 123 CD", "ABC-1234", "KA 01 AB 1234", "1ABC234", "٤٥٦٧٨").forEach { assertTrue(it, PlateText.shouldBlur(it)) }
  }

  @Test
  fun machineTextStaysReadable() {
    // Error codes, model numbers, serials and labels the diagnosis needs.
    listOf("E5", "H1", "F0", "Model: AR12TYHYBWKN", "S/N 0123456789", "220-240V", "1.5 TON", "INVERTER", "Samsung", "R410A", "50Hz", "12V 45AH", "2.5 KW", "800 1234")
        .forEach { assertFalse(it, PlateText.shouldBlur(it)) }
  }

  @Test
  fun digitsNextToAnEmirateNameAreBlurred() {
    assertTrue(PlateText.shouldBlur("7", nearEmirate = true))
    assertFalse(PlateText.shouldBlur("7", nearEmirate = false))
  }
}
