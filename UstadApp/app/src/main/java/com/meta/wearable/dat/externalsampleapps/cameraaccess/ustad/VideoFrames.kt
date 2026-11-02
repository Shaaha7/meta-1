// VideoFrames - Turns an expert's recording into a few key frames for the lesson builder
//
// Samples evenly spaced frames with MediaMetadataRetriever (works for the glasses recordings and
// any phone video), downsizes them and blurs faces on the device. Only these blurred stills are
// ever sent to the AI, never the video or its audio.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ustad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class KeyFrame(val atSec: Int, val image: Bitmap)

data class FrameSet(val frames: List<KeyFrame>, val durationSec: Int, val facesBlurred: Int)

object VideoFrames {
  private const val MAX_SIDE = 768

  suspend fun extract(context: Context, uri: Uri, count: Int = 10): FrameSet =
      withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
          retriever.setDataSource(context, uri)
          val durationMs =
              retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
          if (durationMs <= 0) throw IllegalArgumentException("Couldn't read this video")
          val n = count.coerceIn(2, 16)
          var faces = 0
          val frames =
              (0 until n).mapNotNull { i ->
                // Evenly spaced, skipping the very first/last moments (usually setup and stop).
                val tMs = durationMs * (i + 1) / (n + 1)
                val raw =
                    retriever.getScaledFrameAtTime(
                        tMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAX_SIDE, MAX_SIDE)
                        ?: return@mapNotNull null
                val safe = PrivacyFilter.blurFaces(raw)
                faces += safe.facesBlurred
                KeyFrame((tMs / 1000).toInt(), safe.image)
              }
          if (frames.isEmpty()) throw IllegalArgumentException("Couldn't read frames from this video")
          FrameSet(frames, (durationMs / 1000).toInt(), faces)
        } finally {
          runCatching { retriever.release() }
        }
      }

  /**
   * One image with frames in a grid, each stamped with its time. Used for AI providers that take
   * a single image per request (e.g. a local Llama vision model).
   */
  fun contactSheet(frames: List<KeyFrame>, cellWidth: Int = 384): Bitmap {
    val cols = if (frames.size <= 4) 2 else 3
    val rows = (frames.size + cols - 1) / cols
    val aspect = frames.first().image.let { it.height.toFloat() / it.width }
    val cellHeight = (cellWidth * aspect).toInt()
    val sheet = Bitmap.createBitmap(cols * cellWidth, rows * cellHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sheet)
    canvas.drawColor(Color.BLACK)
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; textSize = cellWidth / 12f; isFakeBoldText = true }
    val bg = Paint().apply { color = Color.argb(170, 0, 0, 0) }
    frames.forEachIndexed { i, f ->
      val x = (i % cols) * cellWidth
      val y = (i / cols) * cellHeight
      canvas.drawBitmap(Bitmap.createScaledBitmap(f.image, cellWidth, cellHeight, true), x.toFloat(), y.toFloat(), null)
      val text = "#${i + 1}  ${f.atSec / 60}:${"%02d".format(f.atSec % 60)}"
      canvas.drawRect(x.toFloat(), y.toFloat(), x + label.measureText(text) + 16, y + label.textSize * 1.4f, bg)
      canvas.drawText(text, x + 8f, y + label.textSize * 1.1f, label)
    }
    return sheet
  }
}
