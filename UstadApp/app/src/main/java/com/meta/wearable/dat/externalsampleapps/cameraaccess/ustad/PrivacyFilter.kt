// PrivacyFilter - On-device bystander protection before any image leaves the phone
//
// Detects faces with ML Kit's on-device face detector (bundled model: runs offline, nothing is
// uploaded) and pixelates them, so only the machine, not a bystander's face, is sent to the AI.
// ML Kit finds turned, tilted and small faces that the old android.media.FaceDetector missed (it
// only saw frontal faces with both eyes visible); that detector is kept as a fallback.
// Car number plates: ML Kit reads the text in the photo on the phone and lines that look like a
// plate (PlateText: emirate names, plate-style numbers) are pixelated too. Machine nameplates and
// error codes stay readable because the diagnosis needs them.

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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PrivacyResult(val image: Bitmap, val facesBlurred: Int, val platesBlurred: Int = 0) {
  /** "2 faces and 1 plate blurred on device" / "no faces or plates in frame". */
  val summary: String
    get() {
      val parts = listOfNotNull(
          facesBlurred.takeIf { it > 0 }?.let { "$it face${if (it > 1) "s" else ""}" },
          platesBlurred.takeIf { it > 0 }?.let { "$it plate${if (it > 1) "s" else ""}" })
      return if (parts.isEmpty()) "no faces or plates in frame" else parts.joinToString(" and ") + " blurred on device"
    }
}

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

  /** Loads the face model ahead of time so the first inspection doesn't wait for it (~1 s). */
  suspend fun warmUp() =
      withContext(Dispatchers.Default) {
        runCatching { detectMlKit(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)) }
        Unit
      }

  private val textReader by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

  /** Faces and car number plates, pixelated on the phone. */
  suspend fun blurFaces(source: Bitmap): PrivacyResult =
      withContext(Dispatchers.Default) {
        val faces = runCatching { detectMlKit(source) }.onFailure { Log.w(TAG, "ML Kit failed, using fallback", it) }.getOrNull()
            ?: detectLegacy(source)
        val plates = runCatching { detectPlates(source) }.onFailure { Log.w(TAG, "Plate check failed", it) }.getOrDefault(emptyList())
        val boxes = faces + plates
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
        PrivacyResult(out, faces.size, plates.size)
      }

  /** Text lines that look like a number plate, grown a little so the whole plate is covered. */
  private fun detectPlates(source: Bitmap): List<Rect> {
    val text = Tasks.await(textReader.process(InputImage.fromBitmap(source, 0)), 10, TimeUnit.SECONDS)
    val lines = text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
    val emirateLines = lines.filter { PlateText.isEmirate(it.text) }
    return lines
        .filter { line ->
          val box = line.boundingBox!!
          // "Near" an emirate name: within two line-heights of it (plate layout puts them side by side).
          val near = emirateLines.any { e ->
            val b = e.boundingBox!!
            kotlin.math.abs(b.centerY() - box.centerY()) < 2 * maxOf(b.height(), box.height()) &&
                kotlin.math.abs(b.centerX() - box.centerX()) < 6 * maxOf(b.width(), box.width())
          }
          PlateText.shouldBlur(line.text, near)
        }
        .map { line ->
          val b = line.boundingBox!!
          val dx = b.width() / 3
          val dy = b.height() / 2
          Rect(b.left - dx, b.top - dy, b.right + dx, b.bottom + dy)
        }
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
