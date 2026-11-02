// MachineRouter - Decides which Ustad expert handles a machine
//
// Each kind of machine Ustad knows deeply has a knowledge base (assets/ustad/kb/<kbFile>.json) and
// a hand-built 3D exploded model (assets/ustad/<page>). Anything else falls back to the AI's own
// diagnosis drawn by the generic 3D viewer.
//
// Matching is on whole words only: the old check matched the letters "ac" anywhere, so a "washing
// machine" or a "vacuum" was diagnosed as an air conditioner.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

enum class MachineKind(val kbFile: String?, val page: String, val parts: Map<String, String>) {
  CAR("car", "car.html", MachineLibrary.carParts),
  SPLIT_AC("split_ac", "split_ac_indoor.html", MachineLibrary.acParts),
  /** Lithium-ion packs: diagnosed by BatteryModel (measurements + what the camera saw). */
  BATTERY(null, "battery.html", BatteryModel.parts),
  OTHER(null, "generic.html", emptyMap());

  val hasExpertModel: Boolean
    get() = kbFile != null
}

object MachineRouter {
  private val carWords =
      Regex(
          """\b(car|cars|sedan|suv|hatchback|coupe|vehicle|automobile|pickup|truck|van|minivan|""" +
              """jeep|taxi|ev|electric vehicle|tire|tyre|engine bay|bonnet|bumper|""" +
              """toyota|nissan|honda|hyundai|kia|ford|chevrolet|mitsubishi|mazda|lexus|bmw|""" +
              """mercedes|audi|volkswagen|tesla|byd|mg|geely|suzuki|land cruiser|patrol)\b""",
          RegexOption.IGNORE_CASE,
      )

  // Lithium packs (e-bike, scooter, power bank, tool, storage). A car's 12V battery stays with the car.
  private val batteryWords =
      Regex(
          """\b(battery pack|lithium|li-ion|li ion|lion battery|lipo|li-po|lifepo4|power ?bank|18650|21700|""" +
              """e-?bike battery|scooter battery|battery module|battery cell|cell pack|drone battery|tool battery|""" +
              """ups battery|solar battery|storage battery)\b""",
          RegexOption.IGNORE_CASE,
      )

  private val acWords =
      Regex(
          """\b(air[ -]?con(ditioner|ditioning)?|aircon|a/c|ac|hvac|split (unit|ac|system)|""" +
              """indoor unit|mini[ -]split)\b""",
          RegexOption.IGNORE_CASE,
      )

  /** Kind from the machine description (type / brand / model text) and the part ids the AI used. */
  fun detect(machineText: String, partIds: Collection<String> = emptyList()): MachineKind {
    if (partIds.any { it in MachineLibrary.carParts }) return MachineKind.CAR
    if (partIds.any { it in MachineLibrary.acParts }) return MachineKind.SPLIT_AC
    if (partIds.any { it in BatteryModel.parts }) return MachineKind.BATTERY
    if (batteryWords.containsMatchIn(machineText) && !Regex("""\b(12v|car battery)\b""", RegexOption.IGNORE_CASE).containsMatchIn(machineText)) {
      return MachineKind.BATTERY
    }
    // Car first: car text often mentions "A/C" (the car's own air conditioning).
    if (carWords.containsMatchIn(machineText)) return MachineKind.CAR
    if (acWords.containsMatchIn(machineText)) return MachineKind.SPLIT_AC
    return MachineKind.OTHER
  }

  fun detect(inspection: Inspection): MachineKind =
      detect(
          "${inspection.machine.type} ${inspection.machine.brand} ${inspection.machine.model}",
          inspection.diagnoses.map { it.partId },
      )

  /** Maps an AI part (id or free-text name) to a part of the hand-built 3D model, if any. */
  fun modelPart(kind: MachineKind, partId: String, partName: String = ""): String? {
    if (partId in kind.parts) return partId
    val n = "$partId $partName".lowercase()
    val keywords =
        when (kind) {
          MachineKind.CAR ->
              listOf(
                  "c-hvbattery" to listOf("high-voltage", "high voltage", "hv battery", "traction", "ev battery"),
                  "c-battery12" to listOf("12v", "battery", "terminal"),
                  "c-radiator" to listOf("radiator", "coolant", "cooling", "hose", "thermostat", "water pump"),
                  "c-brake" to listOf("brake", "pad", "rotor", "disc", "caliper"),
                  "c-suspension" to listOf("suspension", "strut", "shock", "alignment", "steering", "spring"),
                  "c-gearbox" to listOf("gearbox", "transmission", "clutch"),
                  "c-exhaust" to listOf("exhaust", "muffler", "catalytic"),
                  "c-fuel-tank" to listOf("fuel tank", "fuel pump", "petrol tank"),
                  "c-wheel-fl" to listOf("tire", "tyre", "wheel", "tread", "sidewall"),
                  "c-bumper" to listOf("bumper", "grille", "collision", "crash"),
                  "c-hood" to listOf("hood", "bonnet"),
                  "c-engine" to listOf("engine", "spark", "coil", "oil", "gasket", "piston", "alternator", "belt", "injector", "misfire"),
                  "c-body" to listOf("body", "door", "panel", "paint", "dent", "scratch"),
              )
          MachineKind.SPLIT_AC ->
              listOf(
                  "p-pipe" to listOf("pipe", "hose", "line", "outlet"),
                  "p-pan" to listOf("pan", "tray", "drain", "condensate"),
                  "p-filter" to listOf("filter", "dust"),
                  "p-coil" to listOf("coil", "evaporator", "fin", "refrigerant", "copper"),
                  "p-fan" to listOf("fan", "blower", "motor", "bearing"),
                  "p-pcb" to listOf("board", "pcb", "control", "sensor", "electr", "wiring"),
                  "p-louver" to listOf("louver", "louvre", "vane", "flap", "swing"),
                  "p-cover" to listOf("cover", "panel", "casing", "housing"),
              )
          MachineKind.BATTERY ->
              listOf(
                  "b-separator" to listOf("separator", "short", "thermal"),
                  "b-electrolyte" to listOf("electrolyte", "swell", "gas", "leak"),
                  "b-anode" to listOf("anode", "sei", "plating", "graphite"),
                  "b-cathode" to listOf("cathode"),
                  "b-bms" to listOf("bms", "board", "protection", "balanc"),
                  "b-terminals" to listOf("terminal", "connector", "wire", "corros"),
                  "b-casing" to listOf("casing", "case", "housing", "cover"),
                  "b-cell" to listOf("cell", "capacity", "degrad"),
              )
          MachineKind.OTHER -> emptyList()
        }
    return keywords.firstOrNull { (_, words) -> words.any { it in n } }?.first
  }
}
