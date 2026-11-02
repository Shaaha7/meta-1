// UstadLens - Ustad in the lens of Ray-Ban Display glasses
//
// Uses the Wearables Device Access Toolkit display module (mwdat-display): when a device session
// starts, Ustad asks for the glasses' display. Glasses without a display simply say no and nothing
// changes. When it is available, Ustad mirrors what matters into the lens: a short title (the cause
// or the step), the exploded-view image with the failing part, and one line of instruction.
//
// Screens call show()/showImage(); updates are debounced so fast changes don't flood Bluetooth.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import android.graphics.Bitmap
import android.util.Log
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayConfiguration
import com.meta.wearable.dat.display.views.Direction
import com.meta.wearable.dat.display.views.ImageSize
import com.meta.wearable.dat.display.views.TextColor
import com.meta.wearable.dat.display.views.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object UstadLens {
  private const val TAG = "UstadLens"

  private data class Card(val title: String, val body: String, val meta: String, val image: Bitmap?)

  private val scope =
      CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.w(TAG, "Lens error", e) })
  private val _connected = MutableStateFlow(false)
  private var display: Display? = null
  private var session: DeviceSession? = null
  private var card = Card("USTAD", "Look at a machine and say inspect.", "Ustad", null)
  private var pending: Job? = null

  /** True while Ustad is drawing in the lens (Ray-Ban Display connected). */
  val connected: StateFlow<Boolean> = _connected

  /** Called when the glasses session has started. Glasses without a display just decline. */
  @Synchronized
  fun attach(deviceSession: DeviceSession) {
    if (display != null && session === deviceSession) return
    deviceSession
        .addDisplay(DisplayConfiguration())
        .onSuccess { d ->
          display = d
          session = deviceSession
          _connected.value = true
          Log.i(TAG, "Display attached")
          push()
        }
        .onFailure { error, _ -> Log.i(TAG, "No display on these glasses: ${error.description}") }
  }

  @Synchronized
  fun detach() {
    pending?.cancel()
    display?.let { runCatching { it.stop() }; runCatching { it.close() } }
    session?.let { runCatching { it.removeDisplay() } }
    display = null
    session = null
    _connected.value = false
  }

  /** Title (cause, step...), one line of instruction, and a small status line. */
  fun show(title: String, body: String = "", meta: String = "Ustad") {
    card = card.copy(title = title.take(60), body = body.take(140), meta = meta.take(60))
    push()
  }

  /** The exploded view with the failing part (or null to drop the image). */
  fun showImage(image: Bitmap?) {
    card = card.copy(image = image?.let { scaleForLens(it) })
    push()
  }

  private fun scaleForLens(b: Bitmap): Bitmap {
    val max = 480f
    val s = max / maxOf(b.width, b.height)
    return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
  }

  private fun push() {
    val d = display ?: return
    val c = card
    pending?.cancel()
    pending =
        scope.launch {
          delay(150) // debounce
          d.sendContent {
                flexBox(Direction.COLUMN) {
                  text(c.title, TextStyle.HEADING)
                  c.image?.let { image("ustad-view", it, ImageSize.FILL) }
                  if (c.body.isNotBlank()) text(c.body, TextStyle.BODY)
                  text(c.meta, TextStyle.META, TextColor.SECONDARY)
                }
              }
              .onFailure { error, _ -> Log.w(TAG, "Couldn't update the lens: ${error.description}") }
        }
  }
}
