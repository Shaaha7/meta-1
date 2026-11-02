// VoiceCommandsTest - Hands-free commands in every coaching language, and free questions

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.VoiceCommand.BACK
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.VoiceCommand.CHECK
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.VoiceCommand.CONFIRMED
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.VoiceCommand.NEXT
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.VoiceCommand.NO
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.VoiceCommand.NOT_SURE
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.VoiceCommand.NOT_THIS
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.VoiceCommand.REPEAT
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.VoiceCommand.STOP
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.VoiceCommand.WHY
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad.VoiceCommand.YES
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandsTest {
  private fun cmd(text: String) = VoiceCommands.parse(text)

  private fun assertCommand(expected: VoiceCommand, vararg said: String) =
      said.forEach { assertEquals("\"$it\"", Heard.Command(expected), cmd(it)) }

  @Test
  fun english() {
    assertCommand(NEXT, "Next", "next step", "OK next.")
    assertCommand(BACK, "go back", "previous")
    assertCommand(REPEAT, "repeat", "say that again", "What?")
    assertCommand(WHY, "why", "Why is that")
    assertCommand(YES, "yes", "Yeah")
    assertCommand(NO, "no", "nope")
    assertCommand(CONFIRMED, "confirmed", "It's confirmed!", "done")
    assertCommand(CHECK, "check my work")
    assertCommand(STOP, "stop")
  }

  @Test
  fun longerPhrasesWinOverTheWordsInside() {
    assertCommand(NOT_SURE, "not sure", "I don't know")
    assertCommand(NOT_THIS, "not this", "next suspect")
  }

  @Test
  fun arabic() {
    assertCommand(NEXT, "التالي")
    assertCommand(WHY, "لماذا", "ليش")
    assertCommand(YES, "نعم")
    assertCommand(NO, "لا")
    assertCommand(NOT_SURE, "لا أعرف") // "no" + "know" → not sure, not "no"
    assertCommand(CONFIRMED, "مؤكد", "أكيد") // alef variants unified
    assertCommand(REPEAT, "أعد")
    assertCommand(NOT_THIS, "ليس هذا")
  }

  @Test
  fun hindiAndUrdu() {
    assertCommand(NEXT, "अगला", "اگلا")
    assertCommand(WHY, "क्यों", "کیوں")
    assertCommand(YES, "हाँ", "ہاں")
    assertCommand(NO, "नहीं", "نہیں")
    assertCommand(NOT_SURE, "पता नहीं", "پتہ نہیں")
    assertCommand(CONFIRMED, "हो गया", "ہو گیا")
    assertCommand(BACK, "वापस", "واپس")
  }

  @Test
  fun tagalogAndMalayalam() {
    assertCommand(NEXT, "susunod", "അടുത്തത്")
    assertCommand(WHY, "bakit", "എന്തുകൊണ്ട്")
    assertCommand(YES, "oo", "അതെ")
    assertCommand(NO, "hindi", "ഇല്ല")
    assertCommand(NOT_SURE, "hindi ko alam") // contains "hindi" (no)
    assertCommand(NOT_THIS, "hindi ito")
    assertCommand(REPEAT, "ulitin", "വീണ്ടും")
  }

  @Test
  fun quizAnswersByNumber() {
    assertEquals(Heard.Option(0), cmd("one"))
    assertEquals(Heard.Option(1), cmd("the second"))
    assertEquals(Heard.Option(1), cmd("دو"))
    assertEquals(Heard.Option(2), cmd("tatlo"))
    assertEquals(Heard.Option(2), cmd("3"))
  }

  @Test
  fun longerSentencesAreQuestions() {
    val q = "Why do we take the negative clamp off first?"
    assertEquals(Heard.Question(q), cmd(q))
    assertEquals(Heard.Question("What size spanner do I need for the terminal?"), cmd("What size spanner do I need for the terminal?"))
    assertEquals(Heard.Question("لماذا نفصل السالب أولا؟"), cmd("لماذا نفصل السالب أولا؟"))
  }

  @Test
  fun noiseIsIgnored() {
    assertEquals(Heard.Nothing, cmd(""))
    assertEquals(Heard.Nothing, cmd("hmm"))
  }

  private val wake = "Hey Mentor"

  @Test
  fun customWakeWord() {
    assertEquals("inspect", VoiceCommands.afterWakeWord("Hey mentor, inspect!", wake))
    assertEquals("", VoiceCommands.afterWakeWord("hey Mentor", wake))
    assertEquals("start lesson", VoiceCommands.afterWakeWord("OK Hey Mentor start lesson", wake)) // one filler word allowed
    assertEquals(null, VoiceCommands.afterWakeWord("next", wake)) // not said → ignored in wake-word mode
    assertEquals(null, VoiceCommands.afterWakeWord("the mentor said next", wake))
    assertEquals("التالي", VoiceCommands.afterWakeWord("يا معلم التالي", "معلم"))
    assertEquals(Heard.Command(NEXT), VoiceCommands.parse("Hey Mentor next", wake))
    assertEquals(Heard.Nothing, VoiceCommands.parse("Hey Mentor", wake))
    assertEquals(Heard.Question("why do we take the negative off first"), VoiceCommands.parse("Hey Mentor, why do we take the negative off first?", wake))
  }

  @Test
  fun glassesCommands() {
    assertCommand(VoiceCommand.INSPECT, "inspect", "take a photo", "what's wrong")
    assertCommand(VoiceCommand.START_LESSON, "start lesson", "start recording", "ابدأ الدرس")
    assertCommand(VoiceCommand.STOP_LESSON, "stop lesson", "stop recording", "end the lesson") // not plain "stop"
    assertCommand(STOP, "stop")
  }
}
