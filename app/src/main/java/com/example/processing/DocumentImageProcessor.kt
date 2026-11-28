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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

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
      val deskewed = deskewFineAngleIfNeeded(flattened)
      if (deskewed !== flattened) flattened.recycle()
      if (rawFile.absolutePath != destFile.absolutePath || deskewed !== src) {
        saveJpeg(deskewed, destFile, 94)
      }
      val dims = deskewed.width to deskewed.height
      deskewed.recycle()
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
    val deskewed = deskewFineAngleIfNeeded(straightened)
    if (deskewed !== straightened) straightened.recycle()

    saveJpeg(deskewed, destFile, 94)
    val dims = deskewed.width to deskewed.height
    deskewed.recycle()
    dims
  }

  /**
   * Snaps residual slight rotational skew (-12..+12 deg) dead-level using horizontal text & table
   * edge row-projection variance, preventing subtly crooked scans when margins are uneven.
   */
  private fun deskewFineAngleIfNeeded(bitmap: Bitmap): Bitmap {
    val gw = min(220, bitmap.width).coerceAtLeast(32)
    val gh = max(32, (gw * bitmap.height.toFloat() / max(1, bitmap.width)).roundToInt().coerceAtMost(320))
    val thumb = if (bitmap.width == gw && bitmap.height == gh) {
      bitmap
    } else {
      Bitmap.createScaledBitmap(bitmap, gw, gh, true)
    }
    val pixels = IntArray(gw * gh)
    thumb.getPixels(pixels, 0, gw, 0, 0, gw, gh)
    if (thumb !== bitmap) thumb.recycle()

    val rotateDegrees = estimateFineSkewAngleDegrees(pixels, gw, gh)
    if (abs(rotateDegrees) < 0.55f) return bitmap

    val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(android.graphics.Color.WHITE)
    val matrix = Matrix().apply {
      postRotate(rotateDegrees, bitmap.width * 0.5f, bitmap.height * 0.5f)
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    canvas.drawBitmap(bitmap, matrix, paint)
    return out
  }

  /**
   * Estimates the clockwise `Matrix.postRotate` correction angle (in [-12, +12] degrees) that aligns
   * horizontal text rows & form rules level. Returns 0f if already level or lacking horizontal lines.
   */
  internal fun estimateFineSkewAngleDegrees(
    argbPixels: IntArray,
    width: Int,
    height: Int,
  ): Float {
    if (width < 24 || height < 24 || argbPixels.size < width * height) return 0f

    val lums = IntArray(width * height)
    for (i in 0 until width * height) {
      val c = argbPixels[i]
      lums[i] = (((c ushr 16) and 0xFF) * 77 + ((c ushr 8) and 0xFF) * 150 + (c and 0xFF) * 29) ushr 8
    }

    val maxPts = 6000
    val ptsDx = FloatArray(maxPts)
    val ptsDy = FloatArray(maxPts)
    val ptsW = FloatArray(maxPts)
    var ptCount = 0

    val cx = width * 0.5f
    val cy = height * 0.5f
    // Ignore outer 4% border frame so edge cutoffs don't bias skew toward frame angle
    val xStart = max(2, (width * 0.04f).roundToInt())
    val xEnd = min(width - 2, (width * 0.96f).roundToInt())
    val yStart = max(2, (height * 0.04f).roundToInt())
    val yEnd = min(height - 2, (height * 0.96f).roundToInt())
    val stride = if ((xEnd - xStart) * (yEnd - yStart) > 52000) 2 else 1

    for (y in yStart until yEnd step stride) {
      val ro = y * width
      for (x in xStart until xEnd step stride) {
        val dy = abs(lums[ro + width + x] - lums[ro - width + x])
        val dx = abs(lums[ro + x + 1] - lums[ro + x - 1])
        val hStrength = dy - (dx ushr 1)
        if (hStrength >= 14 && ptCount < maxPts) {
          ptsDx[ptCount] = x - cx
          ptsDy[ptCount] = y - cy
          ptsW[ptCount] = min(90, hStrength).toFloat()
          ptCount++
        }
      }
    }

    if (ptCount < max(16, min(width, height) / 3)) return 0f

    val binCount = height + 32
    val padOffset = cy + 16f
    val bins = FloatArray(binCount)
    val smoothBins = FloatArray(binCount)

    fun projectionScore(deg: Float): Float {
      bins.fill(0f)
      val rad = (deg * PI / 180.0).toFloat()
      val sinA = sin(rad)
      val cosA = cos(rad)
      for (i in 0 until ptCount) {
        val yProj = ptsDx[i] * sinA + ptsDy[i] * cosA + padOffset
        val y0 = yProj.toInt()
        if (y0 in 0 until binCount - 1) {
          val frac = yProj - y0
          val w = ptsW[i]
          bins[y0] += w * (1f - frac)
          bins[y0 + 1] += w * frac
        }
      }
      // Smooth projection profile to eliminate 0-deg integer grid quantization bias
      for (i in 1 until binCount - 1) {
        smoothBins[i] = (bins[i - 1] + 2f * bins[i] + bins[i + 1]) * 0.25f
      }
      var score = 0f
      for (i in 1 until binCount - 1) {
        val b = (smoothBins[i - 1] + 2f * smoothBins[i] + smoothBins[i + 1]) * 0.25f
        score += b * b
      }
      return score
    }

    val scoreZero = projectionScore(0f).coerceAtLeast(1f)
    var bestDeg = 0f
    var bestScore = scoreZero

    var deg = -12f
    while (deg <= 12.01f) {
      val s = projectionScore(deg)
      if (s > bestScore) {
        bestScore = s
        bestDeg = deg
      }
      deg += 1f
    }

    val fineCenter = bestDeg
    var fDeg = (fineCenter - 1.2f).coerceAtLeast(-12f)
    val fEnd = (fineCenter + 1.2f).coerceAtMost(12f)
    while (fDeg <= fEnd + 0.01f) {
      val s = projectionScore(fDeg)
      if (s > bestScore) {
        bestScore = s
        bestDeg = fDeg
      }
      fDeg += 0.2f
    }

    return if (abs(bestDeg) >= 0.50f && bestScore >= scoreZero * 1.05f) bestDeg else 0f
  }

  /**
   * Straightens remaining 3D paper curve/sag along top, bottom, left, and right borders
   * (common on folded receipts photographed on tables) using a 16x16 Canvas bitmap mesh.
   */
  private fun straightenCurvedBordersIfNeeded(warped: Bitmap): Bitmap {
    val gw = 160
    val gh = max(64, (gw * warped.height.toFloat() / max(1, warped.width)).roundToInt().coerceAtMost(240))
    val thumb = Bitmap.createScaledBitmap(warped, gw, gh, true)
    val thumbPixels = IntArray(gw * gh)
    thumb.getPixels(thumbPixels, 0, gw, 0, 0, gw, gh)
    if (thumb !== warped) thumb.recycle()

    val margins = detectCurvedEdgeMargins(thumbPixels, gw, gh, meshDivs = MESH_DIVS)
    if (!margins.hasAdjustment) return warped

    val w = warped.width
    val h = warped.height
    val wf = w.toFloat()
    val hf = h.toFloat()
    val count = (MESH_DIVS + 1) * (MESH_DIVS + 1)
    val verts = FloatArray(count * 2)

    var vIdx = 0
    for (row in 0..MESH_DIVS) {
      val v = row.toFloat() / MESH_DIVS.toFloat()
      val xL = margins.leftFrac[row] * wf
      val xR = (1f - margins.rightFrac[row]) * wf
      val xSpan = max(wf * 0.55f, xR - xL)

      for (col in 0..MESH_DIVS) {
        val u = col.toFloat() / MESH_DIVS.toFloat()
        val yT = margins.topFrac[col] * hf
        val yB = (1f - margins.bottomFrac[col]) * hf
        val ySpan = max(hf * 0.55f, yB - yT)

        val srcX = u * wf
        val srcY = v * hf
        verts[vIdx++] = ((srcX - xL) / xSpan) * wf
        verts[vIdx++] = ((srcY - yT) / ySpan) * hf
      }
    }

    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
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

    val (paperR, paperG, paperB, paperLum) = estimateInteriorPaperColor(argbPixels, width, height)
      ?: return CurvedBorderMargins(zero, zero, zero, zero, false)

    val topArr = FloatArray(meshDivs + 1)
    val botArr = FloatArray(meshDivs + 1)
    val leftArr = FloatArray(meshDivs + 1)
    val rightArr = FloatArray(meshDivs + 1)

    val maxScanY = (height * 0.16f).roundToInt().coerceAtLeast(4)
    val maxScanX = (width * 0.16f).roundToInt().coerceAtLeast(4)

    for (col in 0..meshDivs) {
      val px = (col.toFloat() / meshDivs.toFloat() * (width - 1)).roundToInt().coerceIn(0, width - 1)

      if (isDeskPixel(argbPixels[px], paperR, paperG, paperB, paperLum) ||
        isDeskPixel(argbPixels[width + px], paperR, paperG, paperB, paperLum)
      ) {
        var y = 0
        var consecutivePaper = 0
        var lastDeskY = -1
        while (y < maxScanY) {
          if (isDeskPixel(argbPixels[y * width + px], paperR, paperG, paperB, paperLum)) {
            lastDeskY = y
            consecutivePaper = 0
          } else {
            consecutivePaper++
            if (consecutivePaper >= 2 && lastDeskY >= 0) break
          }
          y++
        }
        if (lastDeskY in 0 until (maxScanY - 1)) {
          topArr[col] = (lastDeskY + 1.2f) / height.toFloat()
        }
      }

      if (isDeskPixel(argbPixels[(height - 1) * width + px], paperR, paperG, paperB, paperLum) ||
        isDeskPixel(argbPixels[(height - 2) * width + px], paperR, paperG, paperB, paperLum)
      ) {
        var step = 0
        var consecutivePaper = 0
        var lastDeskStep = -1
        while (step < maxScanY) {
          if (isDeskPixel(argbPixels[(height - 1 - step) * width + px], paperR, paperG, paperB, paperLum)) {
            lastDeskStep = step
            consecutivePaper = 0
          } else {
            consecutivePaper++
            if (consecutivePaper >= 2 && lastDeskStep >= 0) break
          }
          step++
        }
        if (lastDeskStep in 0 until (maxScanY - 1)) {
          botArr[col] = (lastDeskStep + 1.2f) / height.toFloat()
        }
      }
    }

    for (row in 0..meshDivs) {
      val py = (row.toFloat() / meshDivs.toFloat() * (height - 1)).roundToInt().coerceIn(0, height - 1)
      val ro = py * width

      if (isDeskPixel(argbPixels[ro], paperR, paperG, paperB, paperLum) ||
        isDeskPixel(argbPixels[ro + 1], paperR, paperG, paperB, paperLum)
      ) {
        var x = 0
        var consecutivePaper = 0
        var lastDeskX = -1
        while (x < maxScanX) {
          if (isDeskPixel(argbPixels[ro + x], paperR, paperG, paperB, paperLum)) {
            lastDeskX = x
            consecutivePaper = 0
          } else {
            consecutivePaper++
            if (consecutivePaper >= 2 && lastDeskX >= 0) break
          }
          x++
        }
        if (lastDeskX in 0 until (maxScanX - 1)) {
          leftArr[row] = (lastDeskX + 1.2f) / width.toFloat()
        }
      }

      if (isDeskPixel(argbPixels[ro + width - 1], paperR, paperG, paperB, paperLum) ||
        isDeskPixel(argbPixels[ro + width - 2], paperR, paperG, paperB, paperLum)
      ) {
        var step = 0
        var consecutivePaper = 0
        var lastDeskStep = -1
        while (step < maxScanX) {
          if (isDeskPixel(argbPixels[ro + width - 1 - step], paperR, paperG, paperB, paperLum)) {
            lastDeskStep = step
            consecutivePaper = 0
          } else {
            consecutivePaper++
            if (consecutivePaper >= 2 && lastDeskStep >= 0) break
          }
          step++
        }
        if (lastDeskStep in 0 until (maxScanX - 1)) {
          rightArr[row] = (lastDeskStep + 1.2f) / width.toFloat()
        }
      }
    }

    fun smooth1D(arr: FloatArray): FloatArray {
      var cur = arr.copyOf()
      repeat(2) {
        val next = cur.copyOf()
        for (i in 1 until cur.size - 1) {
          next[i] = (cur[i - 1] + 2f * cur[i] + cur[i + 1]) * 0.25f
        }
        cur = next
      }
      return cur
    }

    val sTop = smooth1D(topArr)
    val sBot = smooth1D(botArr)
    val sLeft = smooth1D(leftArr)
    val sRight = smooth1D(rightArr)

    fun edgeNeedsMesh(arr: FloatArray): Boolean {
      val nonZero = arr.count { it >= 0.014f }
      val peak = arr.maxOrNull() ?: 0f
      return peak >= 0.016f && nonZero >= 2
    }

    val hasRealCurve = edgeNeedsMesh(sTop) || edgeNeedsMesh(sBot) ||
      edgeNeedsMesh(sLeft) || edgeNeedsMesh(sRight)

    return CurvedBorderMargins(
      topFrac = if (edgeNeedsMesh(sTop)) sTop else zero,
      bottomFrac = if (edgeNeedsMesh(sBot)) sBot else zero,
      leftFrac = if (edgeNeedsMesh(sLeft)) sLeft else zero,
      rightFrac = if (edgeNeedsMesh(sRight)) sRight else zero,
      hasAdjustment = hasRealCurve,
    )
  }

  private data class PaperColorEstimate(val r: Int, val g: Int, val b: Int, val lum: Int)

  private fun estimateInteriorPaperColor(
    argbPixels: IntArray,
    width: Int,
    height: Int,
  ): PaperColorEstimate? {
    val x0 = (width * 0.25f).roundToInt()
    val x1 = (width * 0.75f).roundToInt()
    val y0 = (height * 0.25f).roundToInt()
    val y1 = (height * 0.75f).roundToInt()

    var sumR = 0L
    var sumG = 0L
    var sumB = 0L
    var count = 0

    for (yy in y0 until y1) {
      val ro = yy * width
      for (xx in x0 until x1) {
        val c = argbPixels[ro + xx]
        val r = (c ushr 16) and 0xFF
        val g = (c ushr 8) and 0xFF
        val b = c and 0xFF
        val lum = (r * 77 + g * 150 + b * 29) ushr 8
        val chroma = max(r, max(g, b)) - min(r, min(g, b))
        if (lum >= 125 && chroma <= 50) {
          sumR += r
          sumG += g
          sumB += b
          count++
        }
      }
    }

    if (count < 16) {
      sumR = 0L
      sumG = 0L
      sumB = 0L
      count = 0
      for (yy in y0 until y1) {
        val ro = yy * width
        for (xx in x0 until x1) {
          val c = argbPixels[ro + xx]
          val r = (c ushr 16) and 0xFF
          val g = (c ushr 8) and 0xFF
          val b = c and 0xFF
          val lum = (r * 77 + g * 150 + b * 29) ushr 8
          if (lum >= 110) {
            sumR += r
            sumG += g
            sumB += b
            count++
          }
        }
      }
    }

    if (count < 16) return null
    val pr = (sumR / count).toInt()
    val pg = (sumG / count).toInt()
    val pb = (sumB / count).toInt()
    val pLum = (pr * 77 + pg * 150 + pb * 29) ushr 8
    return PaperColorEstimate(pr, pg, pb, pLum)
  }

  private fun isDeskPixel(
    c: Int,
    paperR: Int,
    paperG: Int,
    paperB: Int,
    paperLum: Int,
  ): Boolean {
    val r = (c ushr 16) and 0xFF
    val g = (c ushr 8) and 0xFF
    val b = c and 0xFF
    val lum = (r * 77 + g * 150 + b * 29) ushr 8
    val colorDist = abs(r - paperR) + abs(g - paperG) + abs(b - paperB)
    val chromaDiff = abs((b - r) - (paperB - paperR)) + abs((g - r) - (paperG - paperR))
    val sat = max(r, max(g, b)) - min(r, min(g, b))
    return (colorDist > 76 && chromaDiff > 28) ||
      (sat > 52 && colorDist > 62 && lum < paperLum - 22)
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
