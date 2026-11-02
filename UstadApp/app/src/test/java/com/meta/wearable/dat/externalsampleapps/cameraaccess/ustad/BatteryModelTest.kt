// BatteryModelTest - Verdicts, remaining life and the failing layer

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryModelTest {
  @Test
  fun healthyBattery() {
    val v = BatteryModel.assess(BatteryInput(rated = 2.0, measured = 1.94, cycles = 40))
    assertEquals(BatteryLevel.OK, v.level)
    assertEquals(0.97, v.soh!!, 1e-9)
    // Its own fade: 3% in 40 cycles → 0.075%/cycle → (0.97-0.70)/0.00075 = 360 cycles left.
    assertEquals(360, v.remainingCycles)
  }

  @Test
  fun nasaEndOfLifeLine() {
    // NASA B0005-like: 2.0 Ah rated, 1.33 Ah measured → 66.5%: below the 70% end-of-life line.
    val v = BatteryModel.assess(BatteryInput(rated = 2.0, measured = 1.33, cycles = 168))
    assertEquals(BatteryLevel.REPLACE, v.level)
    assertEquals(0, v.remainingCycles)
    assertEquals("b-anode", v.part)
    assertTrue(v.reasons.any { it.contains("70%") })
  }

  @Test
  fun unknownHistoryUsesTheDefaultFade() {
    val v = BatteryModel.assess(BatteryInput(sohPercent = 78.0))
    assertEquals(BatteryLevel.WATCH, v.level)
    assertEquals(50, v.remainingCycles) // (0.78 - 0.70) / 0.0016
  }

  @Test
  fun swellingOrHeatMeansIsolateNow() {
    val swollen = BatteryModel.assess(BatteryInput(sohPercent = 95.0, swelling = true))
    assertEquals(BatteryLevel.ISOLATE, swollen.level)
    assertEquals("b-electrolyte", swollen.part)
    assertTrue(swollen.actions.first().startsWith("Stop"))
    val hot = BatteryModel.assess(BatteryInput(temperatureC = 72.0))
    assertEquals(BatteryLevel.ISOLATE, hot.level)
    assertEquals("b-separator", hot.part)
  }

  @Test
  fun risingResistancePointsAtTheAnode() {
    val v = BatteryModel.assess(BatteryInput(sohPercent = 88.0, resistanceRisePercent = 60.0, temperatureC = 28.0))
    assertEquals(BatteryLevel.WATCH, v.level)
    assertEquals("b-anode", v.part)
    assertEquals(BatteryLevel.REPLACE, BatteryModel.assess(BatteryInput(resistanceRisePercent = 120.0)).level)
  }

  @Test
  fun heatWithFadePointsAtTheCathode() {
    assertEquals("b-cathode", BatteryModel.assess(BatteryInput(sohPercent = 82.0, temperatureC = 50.0)).part)
  }

  @Test
  fun noDataNoGuesses() {
    val v = BatteryModel.assess(BatteryInput())
    assertEquals(BatteryLevel.OK, v.level)
    assertNull(v.soh)
    assertNull(v.remainingCycles)
    assertEquals("b-cell", v.part)
  }
}

class BatteryRoutingTest {
  @Test
  fun lithiumPacksGoToTheBatteryModule() {
    listOf("E-bike battery pack 36V", "Xiaomi power bank", "18650 cell", "Lithium-ion scooter battery", "LiFePO4 solar storage battery")
        .forEach { assertEquals(it, MachineKind.BATTERY, MachineRouter.detect(it)) }
  }

  @Test
  fun carBatteryStaysWithTheCar() {
    assertEquals(MachineKind.CAR, MachineRouter.detect("Car 12V battery"))
    assertEquals(MachineKind.BATTERY, MachineRouter.detect("Unknown", listOf("b-anode")))
  }
}
