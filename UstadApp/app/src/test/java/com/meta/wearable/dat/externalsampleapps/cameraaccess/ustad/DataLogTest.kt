package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataLogTest {
  @Test fun describesPhotosAndSoundWithoutContent() {
    val body = """{"contents":[{"parts":[{"text":"x"},{"inline_data":{"mime_type":"image/jpeg","data":"AAA"}},{"inline_data":{"mime_type":"image/jpeg","data":"BBB"}},{"inline_data":{"mime_type":"audio/wav","data":"CCC"}}]}]}"""
    assertEquals("2 blurred photos + a sound clip + text prompt", DataLog.describeAiRequest(body))
    assertEquals("text prompt", DataLog.describeAiRequest("""{"text":"hi"}"""))
    assertEquals("1 blurred photo + text prompt", DataLog.describeAiRequest("""{"url":"data:image/jpeg;base64,AAA"}"""))
  }

  @Test fun recordsPersistsAndRestores() {
    var saved = ""
    DataLog.clear()
    DataLog.persist = { saved = it }
    DataLog.record(DataLog.ON_PHONE, "Blurred 1 face(s)", at = 1000)
    DataLog.record("Gemini", "1 blurred photo + text prompt", 52_000, at = 2000)
    assertTrue(DataLog.summary().startsWith("1 request(s), 50 KB"))
    assertFalse(DataLog.all().last().leftPhone)
    val json = saved
    DataLog.persist = {}
    DataLog.clear()
    DataLog.restore(json)
    assertEquals(2, DataLog.all().size)
    assertEquals("Gemini", DataLog.all().first().destination)
    assertTrue(DataLog.report().contains("WHAT LEFT THIS PHONE"))
    DataLog.clear()
    assertEquals("Nothing has left this phone yet.", DataLog.summary())
  }
}
