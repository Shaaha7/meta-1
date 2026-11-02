// PlateText - Does a piece of text read in a photo look like a car number plate?
//
// ML Kit has no plate detector, so PrivacyFilter reads the text in the photo on the phone and asks
// this class which lines are plates. UAE plates show the emirate (DUBAI, ABU DHABI, دبي…) and a code
// letter plus up to 5 digits; other countries mostly use letters and digits in short groups.
// Machine nameplates and error codes must NOT be blurred (the diagnosis needs them), so plain short
// codes like "E5" or long model numbers are kept.
//
// Plain Kotlin so it is unit-testable.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

object PlateText {
  private val emirates =
      listOf(
          "dubai", "abu dhabi", "abudhabi", "sharjah", "ajman", "fujairah", "ras al khaimah", "rak", "umm al quwain", "uaq",
          "u.a.e", "uae", "دبي", "ابوظبي", "أبوظبي", "ابو ظبي", "أبو ظبي", "الشارقة", "عجمان", "الفجيرة", "رأس الخيمة", "راس الخيمة",
          "ام القيوين", "أم القيوين", "الامارات", "الإمارات")

  // UAE: optional code (letter(s) or 1-2 digits) then 1-5 digits, e.g. "A 12345", "N 1234", "12 34567"
  private val uae = Regex("""^(?:[A-Z]{1,2}|\d{1,2})?[\s\-]?\d{3,5}$""")

  // Arabic-Indic digits on Arabic plates
  private val arabicDigits = Regex("""^[٠-٩۰-۹\s]{3,7}$""")

  // Common international formats: "AB 123 CD", "ABC-1234", "1ABC234", "KA 01 AB 1234"
  private val international =
      Regex("""^(?=.*\d{2})(?=.*[A-Z])[A-Z0-9]{1,4}([\s\-][A-Z0-9]{1,4}){1,3}$|^\d[A-Z]{2,3}\d{2,4}$""")

  // Ratings on nameplates ("220-240V", "12V 45AH", "2.5KW", "50HZ") are never plates.
  private val units = Regex("""\d\s?(V|VAC|VDC|HZ|W|KW|A|AH|MAH|TON|BTU|KG|MM|CM|PSI|BAR|RPM|L)\b""")

  fun isEmirate(text: String): Boolean {
    val t = text.lowercase().replace(Regex("\\s+"), " ").trim()
    return emirates.any { e -> t == e || t.startsWith("$e ") || t.endsWith(" $e") || (e.length > 4 && t.contains(e)) }
  }

  /** True when [text] (one line read from the photo) looks like a plate's number line. */
  fun isPlateNumber(text: String): Boolean {
    val t = text.uppercase().replace(Regex("[·•.]"), " ").replace(Regex("\\s+"), " ").trim()
    if (t.length < 3 || t.length > 14) return false
    val digits = t.count { it.isDigit() }
    if (digits < 3) return false // error codes like "E5", "H1" stay readable
    if (units.containsMatchIn(t)) return false
    // Long digit-only strings are serial / phone numbers on nameplates, not plates.
    if (t.all { it.isDigit() } && t.length > 5) return false
    return uae.matches(t) || arabicDigits.matches(t) || international.matches(t)
  }

  /** Should this line be blurred? [nearEmirate]: an emirate name was read right next to it. */
  fun shouldBlur(text: String, nearEmirate: Boolean = false): Boolean =
      isEmirate(text) || isPlateNumber(text) || (nearEmirate && text.count { it.isDigit() } >= 1 && text.length <= 8)
}
