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
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class KeyFrame(val atSec: Int, val image: Bitmap)

/** The expert's voice from the recording, as an AAC (ADTS) stream for the AI. */
class ExpertAudio(val bytes: ByteArray, val seconds: Int) {
  val mime = "audio/aac"
}

data class FrameSet(val frames: List<KeyFrame>, val durationSec: Int, val facesBlurred: Int, val platesBlurred: Int = 0)

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
          var plates = 0
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
                plates += safe.platesBlurred
                KeyFrame((tMs / 1000).toInt(), safe.image)
              }
          if (frames.isEmpty()) throw IllegalArgumentException("Couldn't read frames from this video")
          FrameSet(frames, (durationMs / 1000).toInt(), faces, plates)
        } finally {
          runCatching { retriever.release() }
        }
      }

  /**
   * The video's sound track (the expert explaining while working), up to [maxSec] seconds, as
   * ADTS AAC. Null when the video has no AAC sound (e.g. recorded with sound off).
   */
  suspend fun extractAudio(context: Context, uri: Uri, maxSec: Int = 300): ExpertAudio? =
      withContext(Dispatchers.IO) {
        val ex = MediaExtractor()
        try {
          ex.setDataSource(context, uri, null)
          val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
          } ?: return@withContext null
          val format = ex.getTrackFormat(track)
          val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
          val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
          val profile = if (format.containsKey(MediaFormat.KEY_AAC_PROFILE)) format.getInteger(MediaFormat.KEY_AAC_PROFILE) else 2
          ex.selectTrack(track)
          val out = ByteArrayOutputStream()
          val buffer = ByteBuffer.allocate(64 * 1024)
          val frame = ByteArray(64 * 1024)
          var lastUs = 0L
          while (true) {
            val size = ex.readSampleData(buffer, 0)
            if (size < 0) break
            lastUs = ex.sampleTime
            if (lastUs > maxSec * 1_000_000L) break
            buffer.get(frame, 0, size)
            buffer.clear()
            out.write(Adts.header(size, profile, rate, channels))
            out.write(frame, 0, size)
            ex.advance()
          }
          if (out.size() == 0) null else ExpertAudio(out.toByteArray(), (lastUs / 1_000_000).toInt())
        } catch (e: Exception) {
          null
        } finally {
          runCatching { ex.release() }
        }
      }

  /**
   * One image with frames in a grid, each stamped with its time. Used for AI providers that take
   * a single image per request (e.g. a local Llama vision model).
   */
  fun contactSheet(frames: List<KeyFrame>, cellWidth: Int = 384): Bitmap {
    require(frames.isNotEmpty()) { "No frames were found in the video" }
    val cols = if (frames.size <= 4) 2 else 3
    val rows = (frames.size + cols - 1) / cols
    val aspect = frames.first().image.let { it.height.toFloat() / it.width.coerceAtLeast(1) }
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
