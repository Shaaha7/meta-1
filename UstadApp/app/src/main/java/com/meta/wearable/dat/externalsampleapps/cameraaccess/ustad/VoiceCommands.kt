// VoiceCommands - What the technician said, as something Ustad can act on
//
// Short utterances are commands ("next", "why", "confirmed", "التالي", "अगला", "bakit"...). Words
// from every language are always accepted because technicians mix languages mid-sentence.
// Anything longer is a free question for "Ask Ustad" ("why do we take the negative off first?").
// A bare number picks a quiz answer ("two", "دو", "dalawa").
// Wake word: the technician's own word (set in the app). In wake-word mode Ustad listens all the
// time but only acts on what follows that word ("<word>, inspect", "<word>, start lesson").
//
// Plain Kotlin so it is unit-testable.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

enum class VoiceCommand { CALL_EXPERT, START_LESSON, STOP_LESSON, INSPECT, NOT_SURE, NOT_THIS, STOP, CHECK, CONFIRMED, REPEAT, BACK, NEXT, WHY, YES, NO }

sealed interface Heard {
  data class Command(val command: VoiceCommand) : Heard

  /** Quiz answer by number, 0-based. */
  data class Option(val index: Int) : Heard

  data class Question(val text: String) : Heard

  data object Nothing : Heard
}

object VoiceCommands {
  /** Longest utterance still treated as a command; longer ones are questions. */
  private const val MAX_COMMAND_WORDS = 5

  // Checked in this order, so "not sure" wins over "no" and "next suspect" over "next".
  private val phrases: List<Pair<VoiceCommand, List<String>>> =
      listOf(
          VoiceCommand.CALL_EXPERT to
              listOf(
                  "call expert", "call the expert", "call senior", "call my senior", "get the expert", "video call expert",
                  "एक्सपर्ट को कॉल", "ماہر کو کال", "اتصل بالخبير", "اتصل بالمشرف", "tawagan ang eksperto", "വിദഗ്ധനെ വിളിക്കൂ"),
          VoiceCommand.START_LESSON to
              listOf(
                  "start lesson", "start the lesson", "start recording", "begin lesson", "record lesson", "record",
                  "रिकॉर्ड करो", "पाठ शुरू", "ریکارڈ کرو", "سبق شروع", "ابدا الدرس", "ابدا التسجيل", "سجل",
                  "simulan ang aralin", "i record", "റെക്കോർഡ് ചെയ്യൂ"),
          VoiceCommand.STOP_LESSON to
              listOf(
                  "stop lesson", "stop the lesson", "stop recording", "end lesson", "end the lesson", "finish lesson", "end recording",
                  "रिकॉर्डिंग बंद", "पाठ खत्म", "ریکارڈنگ بند", "سبق ختم", "اوقف التسجيل", "انه الدرس", "انهاء الدرس",
                  "itigil ang pag record", "tapusin ang aralin", "റെക്കോർഡിംഗ് നിർത്തൂ"),
          VoiceCommand.INSPECT to
              listOf(
                  "inspect", "inspect this", "take a photo", "take photo", "take a picture", "capture", "look at this",
                  "what's wrong", "whats wrong", "फोटो लो", "जांच करो", "تصویر لو", "صور", "التقط صورة", "شو المشكلة",
                  "kuhanan", "litratuhan", "ഫോട്ടോ എടുക്കൂ"),
          VoiceCommand.NOT_SURE to
              listOf(
                  "not sure", "don't know", "dont know", "i don't know", "maybe", "can't see", "cannot see",
                  "पता नहीं", "शायद", "پتہ نہیں", "شاید", "لا اعرف", "مش متاكد", "ما ادري", "مو متاكد", "يمكن",
                  "hindi ko alam", "ewan", "siguro", "അറിയില്ല", "ഉറപ്പില്ല"),
          VoiceCommand.NOT_THIS to
              listOf(
                  "not this", "not it", "not that", "next suspect", "something else", "wrong", "it's not that",
                  "यह नहीं", "ये नहीं", "कुछ और", "یہ نہیں", "کچھ اور", "ليس هذا", "مو هذا", "مش هذا", "غيره",
                  "hindi ito", "hindi yan", "iba", "ഇതല്ല"),
          VoiceCommand.STOP to
              listOf(
                  "stop", "quiet", "be quiet", "pause", "shut up", "रुको", "बस", "رکو", "بس", "توقف", "اسكت", "قف",
                  "tigil", "tama na", "നിർത്തൂ", "നിർത്ത്"),
          VoiceCommand.CHECK to
              listOf(
                  "check", "check my work", "check this", "check it", "चेक", "जाँचो", "जांचो", "چیک", "افحص", "تحقق",
                  "suriin", "പരിശോധിക്കൂ"),
          VoiceCommand.CONFIRMED to
              listOf(
                  "confirmed", "it's confirmed", "its confirmed", "confirm", "that's it", "done", "fixed", "found it",
                  "हो गया", "पक्का", "कन्फर्म", "ہو گیا", "پکا", "کنفرم", "مؤكد", "مءكد", "اكيد", "تم", "خلصت", "خلاص",
                  "kumpirmado", "tapos na", "ayos na", "ശരിയാണ്", "കഴിഞ്ഞു"),
          VoiceCommand.REPEAT to
              listOf(
                  "repeat", "again", "say again", "say that again", "what", "pardon", "sorry", "दोबारा", "फिर से",
                  "دوبارہ", "پھر سے", "كرر", "اعد", "مرة ثانية", "ulitin", "ulit", "വീണ്ടും"),
          VoiceCommand.BACK to
              listOf(
                  "back", "go back", "previous", "पीछे", "वापस", "پیچھے", "واپس", "رجوع", "السابق", "ارجع",
                  "bumalik", "balik", "nakaraan", "പിന്നോട്ട്", "മുമ്പത്തെ"),
          VoiceCommand.NEXT to
              listOf(
                  "next", "ok next", "okay next", "continue", "go on", "next step", "अगला", "आगे", "आगे बढ़ो",
                  "اگلا", "آگے", "التالي", "بعده", "كمل", "استمر", "susunod", "sunod", "tuloy", "അടുത്തത്", "അടുത്ത"),
          VoiceCommand.WHY to
              listOf("why", "क्यों", "کیوں", "لماذا", "ليش", "ليه", "bakit", "എന്തുകൊണ്ട്", "എന്തിന്"),
          VoiceCommand.YES to
              listOf(
                  "yes", "yeah", "yep", "yup", "correct", "right", "हाँ", "हां", "जी", "ہاں", "جی", "نعم", "ايوه", "اي",
                  "oo", "opo", "അതെ", "ഉണ്ട്"),
          VoiceCommand.NO to
              listOf("no", "nope", "nah", "नहीं", "ना", "نہیں", "نا", "لا", "كلا", "hindi", "wala", "ഇല്ല"),
      )

  private val options: List<List<String>> =
      listOf(
          listOf("1", "one", "first", "एक", "पहला", "ایک", "پہلا", "واحد", "الاول", "١", "isa", "una", "ഒന്ന്"),
          listOf("2", "two", "second", "दो", "दूसरा", "دو", "دوسرا", "اثنين", "اثنان", "الثاني", "٢", "dalawa", "pangalawa", "രണ്ട്"),
          listOf("3", "three", "third", "तीन", "तीसरा", "تین", "تیسرا", "ثلاثة", "ثلاث", "الثالث", "٣", "tatlo", "pangatlo", "മൂന്ന്"),
      )

  /**
   * If [raw] starts with [wakeWord] (within the first words; "hey"/"ok" before it is fine),
   * returns what was said after it ("" if only the word). Null when the wake word wasn't said.
   */
  fun afterWakeWord(raw: String, wakeWord: String): String? {
    val wake = normalize(wakeWord)
    if (wake.isBlank()) return null
    val text = normalize(raw)
    val words = text.split(' ')
    // Allow one filler word before it ("hey", "ok", "يا").
    for (skip in 0..1) {
      val rest = words.drop(skip).joinToString(" ")
      if (rest == wake) return ""
      if (rest.startsWith("$wake ")) return rest.removePrefix(wake).trim()
    }
    return null
  }

  /** Lower case, Arabic letter variants unified, punctuation removed, single spaces. */
  fun normalize(text: String): String =
      text.lowercase()
          .replace(Regex("[ً-ْ]"), "") // Arabic diacritics
          .replace(Regex("[أإآ]"), "ا")
          .replace('ى', 'ي')
          .replace('’', '\'')
          .replace(Regex("[^\\p{L}\\p{M}\\p{N}' ]+"), " ")
          .replace(Regex("\\s+"), " ")
          .trim()

  private fun has(text: String, phrase: String) = " $text ".contains(" ${normalize(phrase)} ")

  /** [wakeWord], if given and said first, is ignored ("<word>, next" means "next"). */
  fun parse(raw: String, wakeWord: String = ""): Heard {
    val afterWake = afterWakeWord(raw, wakeWord)
    val text = afterWake ?: normalize(raw)
    if (text.isBlank()) return Heard.Nothing
    val words = text.split(' ').size
    val isQuestion = raw.contains('?') || raw.contains('؟')
    if (words <= MAX_COMMAND_WORDS && !(isQuestion && words > 2)) {
      phrases.forEach { (command, list) -> if (list.any { has(text, it) }) return Heard.Command(command) }
      if (words <= 3) {
        options.forEachIndexed { i, list -> if (list.any { has(text, it) }) return Heard.Option(i) }
      }
    }
    return if (words >= 2 || isQuestion) Heard.Question(afterWake ?: raw.trim()) else Heard.Nothing
  }
}
