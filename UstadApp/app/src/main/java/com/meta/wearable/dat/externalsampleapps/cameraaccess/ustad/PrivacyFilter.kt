// PrivacyFilter - On-device bystander protection before any image leaves the phone
//
// Detects faces with ML Kit's on-device face detector (bundled model: runs offline, nothing is
// uploaded) and pixelates them, so only the machine, not a bystander's face, is sent to the AI.
// ML Kit finds turned, tilted and small faces that the old android.media.FaceDetector missed (it
// only saw frontal faces with both eyes visible); that detector is kept as a fallback.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.Rect
import android.media.FaceDetector
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PrivacyResult(val image: Bitmap, val facesBlurred: Int)

object PrivacyFilter {
  private const val TAG = "PrivacyFilter"
  private const val MAX_FACES = 8
  private const val PIXEL_BLOCKS = 12

  private val mlKit by lazy {
    FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.05f) // small faces in the background count too
            .build(),
    )
  }

  suspend fun blurFaces(source: Bitmap): PrivacyResult =
      withContext(Dispatchers.Default) {
        val boxes = runCatching { detectMlKit(source) }.onFailure { Log.w(TAG, "ML Kit failed, using fallback", it) }.getOrNull()
            ?: detectLegacy(source)
        if (boxes.isEmpty()) return@withContext PrivacyResult(source, 0)

        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        boxes.forEach { r ->
          val box = Rect(r.left.coerceAtLeast(0), r.top.coerceAtLeast(0), r.right.coerceAtMost(out.width), r.bottom.coerceAtMost(out.height))
          if (box.width() > 1 && box.height() > 1) {
            val crop = Bitmap.createBitmap(out, box.left, box.top, box.width(), box.height())
            val tiny = Bitmap.createScaledBitmap(crop, PIXEL_BLOCKS, PIXEL_BLOCKS, false)
            val pixelated = Bitmap.createScaledBitmap(tiny, box.width(), box.height(), false)
            canvas.drawBitmap(pixelated, box.left.toFloat(), box.top.toFloat(), null)
            crop.recycle(); tiny.recycle(); pixelated.recycle()
          }
        }
        PrivacyResult(out, boxes.size)
      }

  /** Face boxes from ML Kit, grown by 25% so hair and ears are covered too. */
  private fun detectMlKit(source: Bitmap): List<Rect> {
    val faces = Tasks.await(mlKit.process(InputImage.fromBitmap(source, 0)), 10, TimeUnit.SECONDS)
    return faces.map { f ->
      val b = f.boundingBox
      val dx = b.width() / 4
      val dy = b.height() / 4
      Rect(b.left - dx, b.top - dy * 2, b.right + dx, b.bottom + dy)
    }
  }

  /** Fallback: the platform detector (frontal faces only). */
  private fun detectLegacy(source: Bitmap): List<Rect> {
    // FaceDetector needs an RGB_565 bitmap with an even width.
    val evenWidth = source.width - (source.width % 2)
    val detectInput = Bitmap.createBitmap(source, 0, 0, evenWidth, source.height).copy(Bitmap.Config.RGB_565, false)
    val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
    val found = runCatching { FaceDetector(evenWidth, source.height, MAX_FACES).findFaces(detectInput, faces) }.getOrDefault(0)
    detectInput.recycle()
    val mid = PointF()
    return faces.take(found).filterNotNull().map { face ->
      face.getMidPoint(mid)
      // Eye distance → generous box covering the whole head.
      val r = (face.eyesDistance() * 1.8f).toInt()
      Rect((mid.x - r).toInt(), (mid.y - r * 1.2f).toInt(), (mid.x + r).toInt(), (mid.y + r * 1.4f).toInt())
    }
  }
}
