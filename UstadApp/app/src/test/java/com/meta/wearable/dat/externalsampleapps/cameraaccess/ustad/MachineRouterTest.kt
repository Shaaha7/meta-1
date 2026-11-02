// MachineRouterTest - Which expert handles which machine (regression test for the "ac" bug)

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import org.junit.Assert.assertEquals
import org.junit.Test

class MachineRouterTest {
  @Test
  fun lettersACInsideAWordAreNotAnAirConditioner() {
    // The old check matched "ac" anywhere, so all of these got the AC diagnosis and AC 3D model.
    listOf("Washing machine", "Vacuum cleaner", "Tractor engine", "Black & Decker drill", "Espresso machine", "Space heater")
        .forEach { assertEquals(it, MachineKind.OTHER, MachineRouter.detect(it)) }
  }

  @Test
  fun airConditioners() {
    listOf("Split AC indoor unit", "Air conditioner", "LG A/C wall unit", "Samsung aircon", "Mini-split indoor unit", "AC unit")
        .forEach { assertEquals(it, MachineKind.SPLIT_AC, MachineRouter.detect(it)) }
  }

  @Test
  fun cars() {
    listOf("Car", "Toyota Corolla 2019", "SUV engine bay", "Tesla Model 3", "Nissan Patrol", "Pickup truck", "Car tire")
        .forEach { assertEquals(it, MachineKind.CAR, MachineRouter.detect(it)) }
  }

  @Test
  fun carWithAirConditioningIsStillACar() {
    assertEquals(MachineKind.CAR, MachineRouter.detect("Hyundai Elantra car, A/C blowing warm"))
  }

  @Test
  fun partIdsDecideFirst() {
    assertEquals(MachineKind.CAR, MachineRouter.detect("Unknown", listOf("c-radiator")))
    assertEquals(MachineKind.SPLIT_AC, MachineRouter.detect("Unknown", listOf("p-coil")))
  }

  @Test
  fun freeTextPartsMapOntoThe3dModel() {
    assertEquals("c-engine", MachineRouter.modelPart(MachineKind.CAR, "alternator", "Alternator"))
    assertEquals("c-brake", MachineRouter.modelPart(MachineKind.CAR, "front-brake-pads", "Brake pads"))
    assertEquals("c-hvbattery", MachineRouter.modelPart(MachineKind.CAR, "traction-pack", "High-voltage battery"))
    assertEquals("c-battery12", MachineRouter.modelPart(MachineKind.CAR, "battery", "12V battery"))
    assertEquals("c-radiator", MachineRouter.modelPart(MachineKind.CAR, "upper-hose", "Radiator hose"))
    assertEquals("p-filter", MachineRouter.modelPart(MachineKind.SPLIT_AC, "dust-filter", "Air filter"))
    assertEquals(null, MachineRouter.modelPart(MachineKind.OTHER, "x", "y"))
  }
}
