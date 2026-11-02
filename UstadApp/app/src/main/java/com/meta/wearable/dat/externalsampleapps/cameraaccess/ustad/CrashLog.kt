// CrashLog - If Ustad ever closes by itself, keep the reason so it can be shared and fixed
//
// Saves the error of a crash on the phone (filesDir/ustad/last_crash.txt). The next time Ustad
// opens, the hub shows it with a "Share crash report" button, so no cable or adb is needed.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLog {
  private fun file(c: Context) = File(c.filesDir, "ustad/last_crash.txt")

  fun install(context: Context, version: String) {
    val app = context.applicationContext
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      runCatching {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        file(app).apply { parentFile?.mkdirs() }.writeText(
            "Ustad $version crashed ${SimpleDateFormat("d MMM yyyy HH:mm:ss", Locale.US).format(Date())}\n" +
                "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "Thread: ${thread.name}\n\n" + trace.take(20_000))
      }
      previous?.uncaughtException(thread, error)
    }
  }

  fun last(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

  fun clear(context: Context) {
    file(context).delete()
  }
}
