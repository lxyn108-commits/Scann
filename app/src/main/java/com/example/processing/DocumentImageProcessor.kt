package com.example.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.example.domain.model.EnhancementFilter
import com.example.domain.model.NormalizedCropRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object DocumentImageProcessor {
  private const val MAX_DIMENSION_PX = 2200

  suspend fun saveCameraCaptureOriented(
    sourceFile: File,
    destFile: File,
  ): Pair<Int, Int> = withContext(Dispatchers.Default) {
    val exifOrientation = runCatching {
      ExifInterface(sourceFile.absolutePath).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL,
      )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val bitmap = decodeSampledBitmap(sourceFile.absolutePath, MAX_DIMENSION_PX)
    val oriented = applyExifOrientation(bitmap, exifOrientation)
    if (oriented !== bitmap) bitmap.recycle()

    saveJpeg(oriented, destFile, 92)
    val dims = oriented.width to oriented.height
    oriented.recycle()
    dims
  }

  suspend fun importUriToFile(
    context: Context,
    uri: Uri,
    destFile: File,
  ): Pair<Int, Int>? = withContext(Dispatchers.IO) {
    val tempFile = File.createTempFile("import_", ".tmp", context.cacheDir)
    try {
      context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(tempFile).use { output ->
          input.copyTo(output)
        }
      } ?: return@withContext null

      saveCameraCaptureOriented(tempFile, destFile)
    } finally {
      tempFile.delete()
    }
  }

  suspend fun cropSourceImage(
    sourceFile: File,
    cropRect: NormalizedCropRect,
  ): Pair<Int, Int> = withContext(Dispatchers.Default) {
    val bitmap = decodeSampledBitmap(sourceFile.absolutePath, MAX_DIMENSION_PX)
    val l = (cropRect.left.coerceIn(0f, 0.95f) * bitmap.width).roundToInt()
    val t = (cropRect.top.coerceIn(0f, 0.95f) * bitmap.height).roundToInt()
    val r = (cropRect.right.coerceIn(cropRect.left + 0.05f, 1f) * bitmap.width).roundToInt()
    val b = (cropRect.bottom.coerceIn(cropRect.top + 0.05f, 1f) * bitmap.height).roundToInt()
    val w = max(16, r - l)
    val h = max(16, b - t)
    val cropped = Bitmap.createBitmap(bitmap, l, t, w, h)
    if (cropped !== bitmap) bitmap.recycle()
    saveJpeg(cropped, sourceFile, 94)
    val dims = cropped.width to cropped.height
    cropped.recycle()
    dims
  }

  suspend fun generateEnhancedImage(
    originalFile: File,
    destEnhancedFile: File,
    filter: EnhancementFilter,
    rotationDegrees: Int,
  ): Bitmap = withContext(Dispatchers.Default) {
    val base = decodeSampledBitmap(originalFile.absolutePath, MAX_DIMENSION_PX)
    val rotated = if (rotationDegrees % 360 != 0) {
      val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
      Bitmap.createBitmap(base, 0, 0, base.width, base.height, matrix, true).also {
        if (it !== base) base.recycle()
      }
    } else {
      base
    }

    val enhanced = when (filter) {
      EnhancementFilter.ORIGINAL -> rotated
      EnhancementFilter.MAGIC_COLOR -> applyMagicColorClarity(rotated)
      EnhancementFilter.CRISP_BW -> applyCrispBlackAndWhite(rotated)
      EnhancementFilter.GRAYSCALE -> applyHighContrastGrayscale(rotated)
    }
    if (enhanced !== rotated) rotated.recycle()

    saveJpeg(enhanced, destEnhancedFile, 91)
    enhanced
  }

  private fun applyMagicColorClarity(src: Bitmap): Bitmap {
    val width = src.width
    val height = src.height
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val cm = ColorMatrix().apply {
      // Boost contrast and lift highlights slightly for bright white paper + crisp ink
      val contrast = 1.32f
      val translate = (-0.5f * contrast + 0.5f) * 255f + 18f
      set(
        floatArrayOf(
          contrast, 0f, 0f, 0f, translate,
          0f, contrast, 0f, 0f, translate,
          0f, 0f, contrast, 0f, translate,
          0f, 0f, 0f, 1f, 0f,
        )
      )
      val sat = ColorMatrix().apply { setSaturation(1.18f) }
      postConcat(sat)
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      colorFilter = ColorMatrixColorFilter(cm)
    }
    canvas.drawBitmap(src, 0f, 0f, paint)
    return out
  }

  private fun applyHighContrastGrayscale(src: Bitmap): Bitmap {
    val width = src.width
    val height = src.height
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val desat = ColorMatrix().apply { setSaturation(0f) }
    val contrast = 1.42f
    val translate = (-0.5f * contrast + 0.5f) * 255f + 22f
    val contrastMatrix = ColorMatrix(
      floatArrayOf(
        contrast, 0f, 0f, 0f, translate,
        0f, contrast, 0f, 0f, translate,
        0f, 0f, contrast, 0f, translate,
        0f, 0f, 0f, 1f, 0f,
      )
    )
    desat.postConcat(contrastMatrix)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      colorFilter = ColorMatrixColorFilter(desat)
    }
    canvas.drawBitmap(src, 0f, 0f, paint)
    return out
  }

  private fun applyCrispBlackAndWhite(src: Bitmap): Bitmap {
    val width = src.width
    val height = src.height
    val pixels = IntArray(width * height)
    src.getPixels(pixels, 0, width, 0, 0, width, height)

    // Estimate local paper white level using global luminance histogram (90th percentile)
    val histogram = IntArray(256)
    for (i in pixels.indices step 4) {
      val c = pixels[i]
      val r = (c ushr 16) and 0xFF
      val g = (c ushr 8) and 0xFF
      val b = c and 0xFF
      val lum = (r * 77 + g * 150 + b * 29) ushr 8
      histogram[lum]++
    }
    val totalSampled = pixels.size / 4
    var acc = 0
    var paperWhite = 230
    for (v in 0..255) {
      acc += histogram[v]
      if (acc >= (totalSampled * 0.85).toInt()) {
        paperWhite = max(140, v)
        break
      }
    }
    val inkDark = max(35, (paperWhite * 0.42f).roundToInt())
    val span = max(20, paperWhite - inkDark)

    for (i in pixels.indices) {
      val c = pixels[i]
      val r = (c ushr 16) and 0xFF
      val g = (c ushr 8) and 0xFF
      val b = c and 0xFF
      val lum = (r * 77 + g * 150 + b * 29) ushr 8

      // Soft S-curve binarization preserves anti-aliased glyph edges for high OCR clarity
      val norm = ((lum - inkDark).toFloat() / span.toFloat()).coerceIn(0f, 1f)
      val curved = norm * norm * (3f - 2f * norm)
      val finalVal = (curved * 255f).roundToInt().coerceIn(0, 255)
      pixels[i] = (0xFF shl 24) or (finalVal shl 16) or (finalVal shl 8) or finalVal
    }

    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, width, 0, 0, width, height)
    return out
  }

  fun decodeSampledBitmap(path: String, maxDimensionPx: Int): Bitmap {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, opts)
    val maxSide = max(opts.outWidth, opts.outHeight)
    var sampleSize = 1
    while (maxSide / sampleSize > maxDimensionPx) {
      sampleSize *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeFile(path, decodeOpts)
      ?: throw IllegalStateException("Unable to decode image at $path")

    val currentMax = max(decoded.width, decoded.height)
    if (currentMax <= maxDimensionPx) return decoded

    val scale = maxDimensionPx.toFloat() / currentMax.toFloat()
    val targetW = max(1, (decoded.width * scale).roundToInt())
    val targetH = max(1, (decoded.height * scale).roundToInt())
    val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
    if (scaled !== decoded) decoded.recycle()
    return scaled
  }

  private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.preScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.preScale(-1f, 1f)
      }
      else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
  }

  private fun saveJpeg(bitmap: Bitmap, destFile: File, quality: Int) {
    destFile.parentFile?.mkdirs()
    FileOutputStream(destFile).use { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
    }
  }

  data class ImageQualityAnalysis(
    val meanLuminance: Float,
    val contrastSpread: Float,
    val suggestedTrimRect: NormalizedCropRect?,
  )

  suspend fun analyzePageQualityAndBorders(imagePath: String): ImageQualityAnalysis =
    withContext(Dispatchers.Default) {
      val bmp = decodeSampledBitmap(imagePath, 220)
      try {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val hist = IntArray(256)
        var sumLum = 0L
        for (c in pixels) {
          val r = (c ushr 16) and 0xFF
          val g = (c ushr 8) and 0xFF
          val b = c and 0xFF
          val lum = (r * 77 + g * 150 + b * 29) ushr 8
          hist[lum]++
          sumLum += lum
        }

        val total = pixels.size.coerceAtLeast(1)
        val meanLum = sumLum.toFloat() / total.toFloat()

        var acc = 0
        var p10 = 0
        var p90 = 255
        var found10 = false
        for (v in 0..255) {
          acc += hist[v]
          if (!found10 && acc >= total * 0.10) {
            p10 = v
            found10 = true
          }
          if (acc >= total * 0.90) {
            p90 = v
            break
          }
        }
        val spread = (p90 - p10).toFloat()

        // Compare center document region luminance vs outer margin rows/cols
        val cx0 = (w * 0.25f).roundToInt()
        val cx1 = (w * 0.75f).roundToInt()
        val cy0 = (h * 0.25f).roundToInt()
        val cy1 = (h * 0.75f).roundToInt()
        var centerSum = 0L
        var centerCount = 0
        for (y in cy0 until cy1) {
          val rowOff = y * w
          for (x in cx0 until cx1) {
            val c = pixels[rowOff + x]
            val lum = (((c ushr 16) and 0xFF) * 77 + ((c ushr 8) and 0xFF) * 150 + (c and 0xFF) * 29) ushr 8
            centerSum += lum
            centerCount++
          }
        }
        val centerLum = if (centerCount > 0) centerSum.toFloat() / centerCount else meanLum
        val borderThreshold = centerLum - 38f

        fun rowLum(y: Int): Float {
          var s = 0L
          val off = y * w
          for (x in cx0 until cx1) {
            val c = pixels[off + x]
            s += (((c ushr 16) and 0xFF) * 77 + ((c ushr 8) and 0xFF) * 150 + (c and 0xFF) * 29) ushr 8
          }
          return s.toFloat() / max(1, cx1 - cx0)
        }

        fun colLum(x: Int): Float {
          var s = 0L
          for (y in cy0 until cy1) {
            val c = pixels[y * w + x]
            s += (((c ushr 16) and 0xFF) * 77 + ((c ushr 8) and 0xFF) * 150 + (c and 0xFF) * 29) ushr 8
          }
          return s.toFloat() / max(1, cy1 - cy0)
        }

        var topPx = 0
        while (topPx < (h * 0.22f).toInt() && rowLum(topPx) < borderThreshold) topPx++
        var bottomPx = h - 1
        while (bottomPx > (h * 0.78f).toInt() && rowLum(bottomPx) < borderThreshold) bottomPx--
        var leftPx = 0
        while (leftPx < (w * 0.22f).toInt() && colLum(leftPx) < borderThreshold) leftPx++
        var rightPx = w - 1
        while (rightPx > (w * 0.78f).toInt() && colLum(rightPx) < borderThreshold) rightPx--

        val nLeft = leftPx.toFloat() / w
        val nTop = topPx.toFloat() / h
        val nRight = (rightPx + 1).toFloat() / w
        val nBottom = (bottomPx + 1).toFloat() / h

        val hasNoticeableBorder =
          (nLeft > 0.03f || nTop > 0.03f || nRight < 0.97f || nBottom < 0.97f) &&
            (nRight - nLeft) > 0.50f &&
            (nBottom - nTop) > 0.50f

        ImageQualityAnalysis(
          meanLuminance = meanLum,
          contrastSpread = spread,
          suggestedTrimRect = if (hasNoticeableBorder) {
            NormalizedCropRect(
              left = nLeft.coerceIn(0f, 0.25f),
              top = nTop.coerceIn(0f, 0.25f),
              right = nRight.coerceIn(0.75f, 1f),
              bottom = nBottom.coerceIn(0.75f, 1f),
            )
          } else {
            null
          },
        )
      } finally {
        bmp.recycle()
      }
    }
}
