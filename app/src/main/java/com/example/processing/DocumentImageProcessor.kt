package com.example.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object DocumentImageProcessor {
  private const val MAX_DIMENSION_PX = 2200
  private const val MESH_DIVS = 16
  private val imageProcessor = ImageProcessor()

  internal data class CurvedBorderMargins(
    val topFrac: FloatArray,
    val bottomFrac: FloatArray,
    val leftFrac: FloatArray,
    val rightFrac: FloatArray,
    val hasAdjustment: Boolean,
  )

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
      val flattened = straightenCurvedBordersIfNeeded(src)
      if (flattened !== src) src.recycle()
      if (rawFile.absolutePath != destFile.absolutePath || flattened !== src) {
        saveJpeg(flattened, destFile, 94)
      }
      val dims = flattened.width to flattened.height
      flattened.recycle()
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

    val straightened = straightenCurvedBordersIfNeeded(warped)
    if (straightened !== warped) warped.recycle()

    saveJpeg(straightened, destFile, 94)
    val dims = straightened.width to straightened.height
    straightened.recycle()
    dims
  }

  /**
   * Straightens remaining 3D paper curve/sag along top, bottom, left, and right borders
   * (common on folded receipts photographed on tables) using a 16x16 Canvas bitmap mesh.
   */
  private fun straightenCurvedBordersIfNeeded(warped: Bitmap): Bitmap {
    val gw = 128
    val gh = max(64, (gw * warped.height.toFloat() / max(1, warped.width)).roundToInt().coerceAtMost(192))
    val thumb = Bitmap.createScaledBitmap(warped, gw, gh, true)
    val pixels = IntArray(gw * gh)
    thumb.getPixels(pixels, 0, gw, 0, 0, gw, gh)
    if (thumb !== warped) thumb.recycle()

    val margins = detectCurvedEdgeMargins(pixels, gw, gh, meshDivs = MESH_DIVS)
    if (!margins.hasAdjustment) return warped

    val w = warped.width.toFloat()
    val h = warped.height.toFloat()
    val count = (MESH_DIVS + 1) * (MESH_DIVS + 1)
    val verts = FloatArray(count * 2)

    var vIdx = 0
    for (row in 0..MESH_DIVS) {
      val v = row.toFloat() / MESH_DIVS.toFloat()
      val xL = margins.leftFrac[row] * w
      val xR = (1f - margins.rightFrac[row]) * w
      val xSpan = max(w * 0.65f, xR - xL)

      for (col in 0..MESH_DIVS) {
        val u = col.toFloat() / MESH_DIVS.toFloat()
        val yT = margins.topFrac[col] * h
        val yB = (1f - margins.bottomFrac[col]) * h
        val ySpan = max(h * 0.65f, yB - yT)

        val srcX = u * w
        val srcY = v * h
        verts[vIdx++] = ((srcX - xL) / xSpan) * w
        verts[vIdx++] = ((srcY - yT) / ySpan) * h
      }
    }

    val out = Bitmap.createBitmap(warped.width, warped.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    canvas.drawBitmapMesh(warped, MESH_DIVS, MESH_DIVS, verts, 0, null, 0, paint)
    return out
  }

  internal fun detectCurvedEdgeMargins(
    argbPixels: IntArray,
    width: Int,
    height: Int,
    meshDivs: Int = MESH_DIVS,
  ): CurvedBorderMargins {
    val zero = FloatArray(meshDivs + 1)
    if (width < 32 || height < 32 || argbPixels.size < width * height) {
      return CurvedBorderMargins(zero, zero, zero, zero, false)
    }

    // Estimate interior document color from center 35%..65% using median-like channel averages
    val x0 = (width * 0.30f).roundToInt()
    val x1 = (width * 0.70f).roundToInt()
    val y0 = (height * 0.30f).roundToInt()
    val y1 = (height * 0.70f).roundToInt()
    var sumR = 0L
    var sumG = 0L
    var sumB = 0L
    var brightCount = 0
    for (yy in y0 until y1) {
      val ro = yy * width
      for (xx in x0 until x1) {
        val c = argbPixels[ro + xx]
        val r = (c ushr 16) and 0xFF
        val g = (c ushr 8) and 0xFF
        val b = c and 0xFF
        val lum = (r * 77 + g * 150 + b * 29) ushr 8
        if (lum >= 110) { // avoid dark printed text lowering paper estimate
          sumR += r
          sumG += g
          sumB += b
          brightCount++
        }
      }
    }
    if (brightCount < 16) {
      return CurvedBorderMargins(zero, zero, zero, zero, false)
    }
    val paperR = (sumR / brightCount).toInt()
    val paperG = (sumG / brightCount).toInt()
    val paperB = (sumB / brightCount).toInt()
    val paperLum = (paperR * 77 + paperG * 150 + paperB * 29) ushr 8

    fun isDeskPixel(c: Int): Boolean {
      val r = (c ushr 16) and 0xFF
      val g = (c ushr 8) and 0xFF
      val b = c and 0xFF
      val lum = (r * 77 + g * 150 + b * 29) ushr 8
      val colorDist = abs(r - paperR) + abs(g - paperG) + abs(b - paperB)
      val chromaDiff = abs((b - r) - (paperB - paperR)) + abs((g - r) - (paperG - paperR))
      // Desk pixels differ in BOTH color/hue or clearly sit darker than bright interior paper
      return (colorDist > 75 && chromaDiff > 28) || (paperLum - lum > 44 && lum < 120)
    }

    val topArr = FloatArray(meshDivs + 1)
    val botArr = FloatArray(meshDivs + 1)
    val leftArr = FloatArray(meshDivs + 1)
    val rightArr = FloatArray(meshDivs + 1)

    val maxScanY = (height * 0.11f).roundToInt().coerceAtLeast(3)
    val maxScanX = (width * 0.11f).roundToInt().coerceAtLeast(3)

    for (col in 0..meshDivs) {
      val px = (col.toFloat() / meshDivs.toFloat() * (width - 1)).roundToInt().coerceIn(0, width - 1)
      if (isDeskPixel(argbPixels[px])) {
        var y = 0
        while (y < maxScanY && isDeskPixel(argbPixels[y * width + px])) y++
        if (y < maxScanY) topArr[col] = (y + 0.5f) / height.toFloat()
      }
      if (isDeskPixel(argbPixels[(height - 1) * width + px])) {
        var step = 0
        while (step < maxScanY && isDeskPixel(argbPixels[(height - 1 - step) * width + px])) step++
        if (step < maxScanY) botArr[col] = (step + 0.5f) / height.toFloat()
      }
    }

    for (row in 0..meshDivs) {
      val py = (row.toFloat() / meshDivs.toFloat() * (height - 1)).roundToInt().coerceIn(0, height - 1)
      val ro = py * width
      if (isDeskPixel(argbPixels[ro])) {
        var x = 0
        while (x < maxScanX && isDeskPixel(argbPixels[ro + x])) x++
        if (x < maxScanX) leftArr[row] = (x + 0.5f) / width.toFloat()
      }
      if (isDeskPixel(argbPixels[ro + width - 1])) {
        var step = 0
        while (step < maxScanX && isDeskPixel(argbPixels[ro + width - 1 - step])) step++
        if (step < maxScanX) rightArr[row] = (step + 0.5f) / width.toFloat()
      }
    }

    fun smooth1D(arr: FloatArray): FloatArray {
      val res = arr.copyOf()
      for (i in 1 until arr.size - 1) {
        res[i] = (arr[i - 1] + 2f * arr[i] + arr[i + 1]) * 0.25f
      }
      return res
    }

    val sTop = smooth1D(topArr)
    val sBot = smooth1D(botArr)
    val sLeft = smooth1D(leftArr)
    val sRight = smooth1D(rightArr)

    val maxShift = maxOf(
      sTop.maxOrNull() ?: 0f,
      sBot.maxOrNull() ?: 0f,
      sLeft.maxOrNull() ?: 0f,
      sRight.maxOrNull() ?: 0f,
    )
    return CurvedBorderMargins(
      topFrac = sTop,
      bottomFrac = sBot,
      leftFrac = sLeft,
      rightFrac = sRight,
      hasAdjustment = maxShift >= 0.008f,
    )
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

    val enhanced = imageProcessor.applyEnhancement(
      source = rotated,
      filter = filter,
      removeShadowAndGlare = removeShadowAndGlare,
    )
    if (enhanced !== rotated) rotated.recycle()

    saveJpeg(enhanced, destEnhancedFile, 92)
    enhanced
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
