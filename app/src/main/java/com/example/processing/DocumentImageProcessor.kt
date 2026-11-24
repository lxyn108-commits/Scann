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
import com.example.domain.model.NormalizedQuad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
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

    saveJpeg(oriented, destFile, 93)
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

  suspend fun applyPerspectiveCorrection(
    rawFile: File,
    destFile: File,
    quad: NormalizedQuad,
  ): Pair<Int, Int> = withContext(Dispatchers.Default) {
    val src = decodeSampledBitmap(rawFile.absolutePath, MAX_DIMENSION_PX)
    if (quad.isFullImage || !quad.isConvexValid(minArea = 0.04f)) {
      if (rawFile.absolutePath != destFile.absolutePath) {
        saveJpeg(src, destFile, 94)
      }
      val dims = src.width to src.height
      src.recycle()
      return@withContext dims
    }

    val sw = src.width.toFloat()
    val sh = src.height.toFloat()
    val tlX = quad.topLeft.x.coerceIn(0f, 1f) * sw
    val tlY = quad.topLeft.y.coerceIn(0f, 1f) * sh
    val trX = quad.topRight.x.coerceIn(0f, 1f) * sw
    val trY = quad.topRight.y.coerceIn(0f, 1f) * sh
    val brX = quad.bottomRight.x.coerceIn(0f, 1f) * sw
    val brY = quad.bottomRight.y.coerceIn(0f, 1f) * sh
    val blX = quad.bottomLeft.x.coerceIn(0f, 1f) * sw
    val blY = quad.bottomLeft.y.coerceIn(0f, 1f) * sh

    val topW = hypot(trX - tlX, trY - tlY)
    val botW = hypot(brX - blX, brY - blY)
    val leftH = hypot(blX - tlX, blY - tlY)
    val rightH = hypot(brX - trX, brY - trY)

    var outW = max(topW, botW).coerceAtLeast(120f)
    var outH = max(leftH, rightH).coerceAtLeast(120f)
    val scaleUp = (MAX_DIMENSION_PX.toFloat() / max(outW, outH)).coerceIn(0.4f, 1.35f)
    if (max(outW, outH) > MAX_DIMENSION_PX || max(outW, outH) < 900f) {
      outW *= scaleUp
      outH *= scaleUp
    }
    val targetW = outW.roundToInt().coerceIn(200, MAX_DIMENSION_PX)
    val targetH = outH.roundToInt().coerceIn(200, MAX_DIMENSION_PX)

    val srcPts = floatArrayOf(tlX, tlY, trX, trY, brX, brY, blX, blY)
    val dstPts = floatArrayOf(
      0f, 0f,
      targetW.toFloat(), 0f,
      targetW.toFloat(), targetH.toFloat(),
      0f, targetH.toFloat(),
    )

    val matrix = Matrix()
    val mapped = matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
    if (!mapped) {
      saveJpeg(src, destFile, 94)
      val dims = src.width to src.height
      src.recycle()
      return@withContext dims
    }

    val warped = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(warped)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    canvas.drawBitmap(src, matrix, paint)
    src.recycle()

    saveJpeg(warped, destFile, 94)
    val dims = warped.width to warped.height
    warped.recycle()
    dims
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
    removeShadowAndGlare: Boolean = true,
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

    val shadowNormalized = if (removeShadowAndGlare && filter != EnhancementFilter.ORIGINAL) {
      removeShadowAndGlare(rotated).also {
        if (it !== rotated) rotated.recycle()
      }
    } else {
      rotated
    }

    val enhanced = when (filter) {
      EnhancementFilter.ORIGINAL -> {
        if (removeShadowAndGlare) {
          shadowNormalized
        } else {
          shadowNormalized
        }
      }
      EnhancementFilter.MAGIC_COLOR -> applyMagicColorClarity(shadowNormalized)
      EnhancementFilter.CRISP_BW -> applyCrispBlackAndWhite(shadowNormalized)
      EnhancementFilter.GRAYSCALE -> applyHighContrastGrayscale(shadowNormalized)
    }
    if (enhanced !== shadowNormalized) shadowNormalized.recycle()

    saveJpeg(enhanced, destEnhancedFile, 92)
    enhanced
  }

  /**
   * Estimates local uneven paper illumination using a morphological max-dilation + box smoothing
   * background model, then normalizes pixel brightness to suppress shadows and specular glare.
   */
  private fun removeShadowAndGlare(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val gridW = 64
    val gridH = max(32, (gridW * h.toFloat() / max(1, w).toFloat()).roundToInt().coerceAtMost(96))

    val small = Bitmap.createScaledBitmap(src, gridW, gridH, true)
    val smallPixels = IntArray(gridW * gridH)
    small.getPixels(smallPixels, 0, gridW, 0, 0, gridW, gridH)
    if (small !== src) small.recycle()

    val lumGrid = IntArray(gridW * gridH) { i ->
      val c = smallPixels[i]
      val r = (c ushr 16) and 0xFF
      val g = (c ushr 8) and 0xFF
      val b = c and 0xFF
      (r * 77 + g * 150 + b * 29) ushr 8
    }

    // Dilation (radius 2) extracts local background paper luminance while ignoring dark text strokes
    val dilated = IntArray(gridW * gridH)
    for (y in 0 until gridH) {
      val y0 = max(0, y - 2)
      val y1 = min(gridH - 1, y + 2)
      for (x in 0 until gridW) {
        val x0 = max(0, x - 2)
        val x1 = min(gridW - 1, x + 2)
        var maxVal = 0
        for (yy in y0..y1) {
          val ro = yy * gridW
          for (xx in x0..x1) {
            val v = lumGrid[ro + xx]
            if (v > maxVal) maxVal = v
          }
        }
        dilated[y * gridW + x] = maxVal
      }
    }

    // Two-pass box smooth to ensure seamless transitions with zero halo artifacts
    var bgSmooth = dilated
    repeat(2) {
      val next = IntArray(gridW * gridH)
      for (y in 0 until gridH) {
        val y0 = max(0, y - 2)
        val y1 = min(gridH - 1, y + 2)
        for (x in 0 until gridW) {
          val x0 = max(0, x - 2)
          val x1 = min(gridW - 1, x + 2)
          var sum = 0
          var cnt = 0
          for (yy in y0..y1) {
            val ro = yy * gridW
            for (xx in x0..x1) {
              sum += bgSmooth[ro + xx]
              cnt++
            }
          }
          next[y * gridW + x] = sum / max(1, cnt)
        }
      }
      bgSmooth = next
    }

    val bgPixels = IntArray(gridW * gridH) { i ->
      val v = bgSmooth[i].coerceIn(60, 255)
      (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    val bgSmallBmp = Bitmap.createBitmap(gridW, gridH, Bitmap.Config.ARGB_8888)
    bgSmallBmp.setPixels(bgPixels, 0, gridW, 0, 0, gridW, gridH)
    val bgFullBmp = Bitmap.createScaledBitmap(bgSmallBmp, w, h, true)
    bgSmallBmp.recycle()

    val srcPixels = IntArray(w * h)
    val bgFullPixels = IntArray(w * h)
    src.getPixels(srcPixels, 0, w, 0, 0, w, h)
    bgFullBmp.getPixels(bgFullPixels, 0, w, 0, 0, w, h)
    bgFullBmp.recycle()

    val targetWhite = 246f
    for (i in srcPixels.indices) {
      val c = srcPixels[i]
      val r = (c ushr 16) and 0xFF
      val g = (c ushr 8) and 0xFF
      val b = c and 0xFF
      val bgLum = (bgFullPixels[i] and 0xFF).toFloat().coerceAtLeast(52f)

      // Illumination equalization lifts shadows and cools down hot glare spots
      val gain = (targetWhite / bgLum).coerceIn(0.76f, 2.35f)
      var nr = (r * gain)
      var ng = (g * gain)
      var nb = (b * gain)

      // Gentle highlight glare suppression + dark ink preservation
      val pixelLum = (nr * 0.299f + ng * 0.587f + nb * 0.114f)
      if (bgLum > 235f && pixelLum < 210f) {
        // Ink inside a glare zone: deepen ink contrast slightly
        nr *= 0.88f
        ng *= 0.88f
        nb *= 0.88f
      }

      val ri = nr.roundToInt().coerceIn(0, 255)
      val gi = ng.roundToInt().coerceIn(0, 255)
      val bi = nb.roundToInt().coerceIn(0, 255)
      srcPixels[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(srcPixels, 0, w, 0, 0, w, h)
    return out
  }

  private fun applyMagicColorClarity(src: Bitmap): Bitmap {
    val width = src.width
    val height = src.height
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val cm = ColorMatrix().apply {
      // Clean white paper + punchy readable text & natural ID card color
      val contrast = 1.24f
      val translate = (-0.5f * contrast + 0.5f) * 255f + 12f
      set(
        floatArrayOf(
          contrast, 0f, 0f, 0f, translate,
          0f, contrast, 0f, 0f, translate,
          0f, 0f, contrast, 0f, translate,
          0f, 0f, 0f, 1f, 0f,
        )
      )
      val sat = ColorMatrix().apply { setSaturation(1.16f) }
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
    val contrast = 1.36f
    val translate = (-0.5f * contrast + 0.5f) * 255f + 14f
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

    val histogram = IntArray(256)
    for (i in pixels.indices step 4) {
      val c = pixels[i]
      val r = (c ushr 16) and 0xFF
      val g = (c ushr 8) and 0xFF
      val b = c and 0xFF
      val lum = (r * 77 + g * 150 + b * 29) ushr 8
      histogram[lum]++
    }
    val totalSampled = max(1, pixels.size / 4)
    var acc = 0
    var paperWhite = 228
    for (v in 0..255) {
      acc += histogram[v]
      if (acc >= (totalSampled * 0.80).toInt()) {
        paperWhite = max(150, v)
        break
      }
    }
    val inkDark = max(40, (paperWhite * 0.48f).roundToInt())
    val span = max(24, paperWhite - inkDark)

    for (i in pixels.indices) {
      val c = pixels[i]
      val r = (c ushr 16) and 0xFF
      val g = (c ushr 8) and 0xFF
      val b = c and 0xFF
      val lum = (r * 77 + g * 150 + b * 29) ushr 8

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
