package com.example.processing

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.example.domain.model.NormalizedPoint
import com.example.domain.model.NormalizedQuad
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time document quadrilateral detector engineered for patterned backgrounds (floral fabric,
 * embossed mats, rugs) and multi-colored documents/ID cards. Uses crisp 3x3 NMS Sobel edges +
 * tilted rectangle Hough voting (`theta + phi ≈ 0`), alongside multi-patch YUV region fitting with
 * straightness, viewport-clipping, and printed-detail scoring.
 */
object DocumentQuadDetector {

  private const val MIN_ANGLE_DEG = -38
  private const val MAX_ANGLE_DEG = 38
  private const val ANGLE_STEP_DEG = 2
  private val ANGLES_RAD: FloatArray = FloatArray((MAX_ANGLE_DEG - MIN_ANGLE_DEG) / ANGLE_STEP_DEG + 1) { idx ->
    ((MIN_ANGLE_DEG + idx * ANGLE_STEP_DEG) * PI / 180.0).toFloat()
  }
  private val COS_TABLE = FloatArray(ANGLES_RAD.size) { cos(ANGLES_RAD[it]) }
  private val SIN_TABLE = FloatArray(ANGLES_RAD.size) { sin(ANGLES_RAD[it]) }

  private data class LineEq(val a: Float, val b: Float, val c: Float)
  private data class LineCandidate(val theta: Float, val rho: Float, val score: Float, val centerPos: Float)
  private data class YuvSample(val y: Int, val u: Int, val v: Int)

  fun detectFromImageProxy(imageProxy: ImageProxy, targetMaxSide: Int = 160): Pair<NormalizedQuad?, Pair<Int, Int>> {
    val planes = imageProxy.planes
    val yPlane = planes[0]
    val srcW = imageProxy.width
    val srcH = imageProxy.height
    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride
    val yBuf = yPlane.buffer
    val yCap = yBuf.capacity()

    val hasChroma = planes.size >= 3
    val uPlane = if (hasChroma) planes[1] else null
    val vPlane = if (hasChroma) planes[2] else null
    val uBuf = uPlane?.buffer
    val vBuf = vPlane?.buffer
    val uCap = uBuf?.capacity() ?: 0
    val vCap = vBuf?.capacity() ?: 0

    val rotation = imageProxy.imageInfo.rotationDegrees
    val uprightW = if (rotation == 90 || rotation == 270) srcH else srcW
    val uprightH = if (rotation == 90 || rotation == 270) srcW else srcH

    val scale = targetMaxSide.toFloat() / max(uprightW, uprightH).toFloat()
    val gridW = max(48, (uprightW * scale).roundToInt())
    val gridH = max(48, (uprightH * scale).roundToInt())

    val yArr = IntArray(gridW * gridH)
    val uArr = if (hasChroma) IntArray(gridW * gridH) else null
    val vArr = if (hasChroma) IntArray(gridW * gridH) else null

    for (gy in 0 until gridH) {
      val uy = ((gy + 0.5f) / gridH * uprightH).toInt().coerceIn(0, uprightH - 1)
      val rowOffset = gy * gridW
      for (gx in 0 until gridW) {
        val ux = ((gx + 0.5f) / gridW * uprightW).toInt().coerceIn(0, uprightW - 1)
        val (sx, sy) = when (rotation) {
          90 -> uy to (srcH - 1 - ux)
          180 -> (srcW - 1 - ux) to (srcH - 1 - uy)
          270 -> (srcW - 1 - uy) to ux
          else -> ux to uy
        }
        val yIdx = (sy * yRowStride + sx * yPixelStride).coerceIn(0, yCap - 1)
        yArr[rowOffset + gx] = yBuf.get(yIdx).toInt() and 0xFF

        if (uArr != null && vArr != null && uPlane != null && vPlane != null && uBuf != null && vBuf != null) {
          val cx = sx / 2
          val cy = sy / 2
          val uIdx = (cy * uPlane.rowStride + cx * uPlane.pixelStride).coerceIn(0, uCap - 1)
          val vIdx = (cy * vPlane.rowStride + cx * vPlane.pixelStride).coerceIn(0, vCap - 1)
          uArr[rowOffset + gx] = uBuf.get(uIdx).toInt() and 0xFF
          vArr[rowOffset + gx] = vBuf.get(vIdx).toInt() and 0xFF
        }
      }
    }

    val quad = detectFromYuvGrid(yArr, uArr, vArr, gridW, gridH)
    return quad to (uprightW to uprightH)
  }

  fun detectFromBitmap(bitmap: Bitmap, targetMaxSide: Int = 160): NormalizedQuad? {
    val scale = targetMaxSide.toFloat() / max(bitmap.width, bitmap.height).toFloat()
    val gridW = max(48, (bitmap.width * scale).roundToInt())
    val gridH = max(48, (bitmap.height * scale).roundToInt())
    val scaled = if (bitmap.width == gridW && bitmap.height == gridH) {
      bitmap
    } else {
      Bitmap.createScaledBitmap(bitmap, gridW, gridH, true)
    }
    val pixels = IntArray(gridW * gridH)
    scaled.getPixels(pixels, 0, gridW, 0, 0, gridW, gridH)
    if (scaled !== bitmap) scaled.recycle()

    val yArr = IntArray(pixels.size)
    val uArr = IntArray(pixels.size)
    val vArr = IntArray(pixels.size)
    for (i in pixels.indices) {
      val c = pixels[i]
      val r = (c ushr 16) and 0xFF
      val g = (c ushr 8) and 0xFF
      val b = c and 0xFF
      yArr[i] = (77 * r + 150 * g + 29 * b) ushr 8
      uArr[i] = (((-43 * r - 85 * g + 128 * b) shr 8) + 128).coerceIn(0, 255)
      vArr[i] = (((128 * r - 107 * g - 21 * b) shr 8) + 128).coerceIn(0, 255)
    }
    return detectFromYuvGrid(yArr, uArr, vArr, gridW, gridH)
  }

  internal fun detectFromLumaGrid(luma: IntArray, width: Int, height: Int): NormalizedQuad? {
    return detectFromYuvGrid(luma, null, null, width, height)
  }

  internal fun detectFromYuvGrid(
    y: IntArray,
    u: IntArray?,
    v: IntArray?,
    width: Int,
    height: Int,
  ): NormalizedQuad? {
    if (width < 24 || height < 24 || y.size < width * height) return null

    // Light 1-pass 3x3 smooth preserves razor-sharp document/plastic borders while taming grain
    val sharpY = boxBlur3x3(y, width, height)
    val sharpU = u?.let { boxBlur3x3(it, width, height) }
    val sharpV = v?.let { boxBlur3x3(it, width, height) }

    // Coarser 5x5 region fields for color sampling & saliency segmentation
    val featY = boxBlur5x5(boxBlur3x3(sharpY, width, height), width, height)
    val featU = sharpU?.let { boxBlur5x5(it, width, height) }
    val featV = sharpV?.let { boxBlur5x5(it, width, height) }

    // High-frequency printed detail map (distinguishes text/cards from flat floral bedsheets)
    val n = width * height
    val detailMap = FloatArray(n) { i -> abs(y[i] - sharpY[i]).toFloat() }

    // Multi-channel Sobel gradient magnitude + direction + 3x3 Non-Maximum Suppression
    val gxArr = FloatArray(n)
    val gyArr = FloatArray(n)
    val magArr = FloatArray(n)

    for (yy in 1 until height - 1) {
      val ym1 = (yy - 1) * width
      val y0 = yy * width
      val yp1 = (yy + 1) * width
      for (xx in 1 until width - 1) {
        val (gxY, gyY) = sobelAt(sharpY, ym1, y0, yp1, xx)
        val magY = sqrt(gxY * gxY + gyY * gyY)

        if (sharpU != null && sharpV != null) {
          val (gxU, gyU) = sobelAt(sharpU, ym1, y0, yp1, xx)
          val (gxV, gyV) = sobelAt(sharpV, ym1, y0, yp1, xx)
          val magU = sqrt(gxU * gxU + gyU * gyU) * 1.85f
          val magV = sqrt(gxV * gxV + gyV * gyV) * 1.85f
          val totalMag = magY + magU + magV

          val (domGx, domGy) = when {
            magU >= magY && magU >= magV -> gxU to gyU
            magV >= magY && magV >= magU -> gxV to gyV
            else -> gxY to gyY
          }
          val sY = if (gxY * domGx + gyY * domGy >= 0f) 1f else -1f
          val sU = if (gxU * domGx + gyU * domGy >= 0f) 1.85f else -1.85f
          val sV = if (gxV * domGx + gyV * domGy >= 0f) 1.85f else -1.85f

          val idx = y0 + xx
          gxArr[idx] = gxY * sY + gxU * sU + gxV * sV
          gyArr[idx] = gyY * sY + gyU * sU + gyV * sV
          magArr[idx] = totalMag
        } else {
          val idx = y0 + xx
          gxArr[idx] = gxY
          gyArr[idx] = gyY
          magArr[idx] = magY
        }
      }
    }

    val nmsArr = nonMaxSuppression(gxArr, gyArr, magArr, width, height)

    // Evaluate BOTH strategies independently & pick the highest true-document score
    val edgeQuad = findOrientedEdgeQuad(featY, featU, featV, detailMap, gxArr, gyArr, magArr, nmsArr, width, height)
    val regionQuad = findRegionDocumentQuad(featY, featU, featV, detailMap, magArr, nmsArr, width, height)

    return when {
      regionQuad != null && edgeQuad != null ->
        if (edgeQuad.confidence >= regionQuad.confidence) edgeQuad else regionQuad
      else -> edgeQuad ?: regionQuad
    }
  }

  private fun sobelAt(arr: IntArray, ym1: Int, y0: Int, yp1: Int, xx: Int): Pair<Float, Float> {
    val tl = arr[ym1 + xx - 1]
    val tc = arr[ym1 + xx]
    val tr = arr[ym1 + xx + 1]
    val ml = arr[y0 + xx - 1]
    val mr = arr[y0 + xx + 1]
    val bl = arr[yp1 + xx - 1]
    val bc = arr[yp1 + xx]
    val br = arr[yp1 + xx + 1]
    val gx = (-tl + tr - 2 * ml + 2 * mr - bl + br).toFloat()
    val gy = (-tl - 2 * tc - tr + bl + 2 * bc + br).toFloat()
    return gx to gy
  }

  private fun nonMaxSuppression(
    gxArr: FloatArray,
    gyArr: FloatArray,
    magArr: FloatArray,
    width: Int,
    height: Int,
  ): FloatArray {
    val out = FloatArray(magArr.size)
    for (y in 1 until height - 1) {
      val row = y * width
      for (x in 1 until width - 1) {
        val idx = row + x
        val m = magArr[idx]
        if (m < 8f) continue
        val ax = abs(gxArr[idx])
        val ay = abs(gyArr[idx])
        val isPeak = if (ax >= ay * 1.4f) {
          m >= magArr[idx - 1] && m >= magArr[idx + 1]
        } else if (ay >= ax * 1.4f) {
          m >= magArr[idx - width] && m >= magArr[idx + width]
        } else if (gxArr[idx] * gyArr[idx] > 0f) {
          m >= magArr[(y - 1) * width + x - 1] && m >= magArr[(y + 1) * width + x + 1]
        } else {
          m >= magArr[(y - 1) * width + x + 1] && m >= magArr[(y + 1) * width + x - 1]
        }
        if (isPeak) out[idx] = m
      }
    }
    return out
  }

  private fun buildBorderPalette(
    featY: IntArray,
    featU: IntArray?,
    featV: IntArray?,
    width: Int,
    height: Int,
  ): Pair<List<YuvSample>, Int> {
    val palette = ArrayList<YuvSample>(32)
    val mx = max(2, (width * 0.045f).roundToInt())
    val my = max(2, (height * 0.045f).roundToInt())

    fun addSample(x: Int, y: Int) {
      val ix = x.coerceIn(0, width - 1)
      val iy = y.coerceIn(0, height - 1)
      val idx = iy * width + ix
      val sy = featY[idx]
      val su = featU?.get(idx) ?: 128
      val sv = featV?.get(idx) ?: 128
      val duplicate = palette.any {
        abs(it.y - sy) < 10 && abs(it.u - su) < 6 && abs(it.v - sv) < 6
      }
      if (!duplicate) {
        palette.add(YuvSample(sy, su, sv))
      }
    }

    var sumY = 0L
    var count = 0
    for (ry in listOf(my, height - 1 - my)) {
      for (step in 0..8) {
        val x = (width * (0.06f + 0.88f * step / 8f)).roundToInt()
        addSample(x, ry)
        sumY += featY[ry.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
        count++
      }
    }
    for (rx in listOf(mx, width - 1 - mx)) {
      for (step in 1..7) {
        val y = (height * (0.12f + 0.76f * step / 8f)).roundToInt()
        addSample(rx, y)
        sumY += featY[y.coerceIn(0, height - 1) * width + rx.coerceIn(0, width - 1)]
        count++
      }
    }
    return palette to (sumY / max(1, count)).toInt()
  }

  private fun findRegionDocumentQuad(
    featY: IntArray,
    featU: IntArray?,
    featV: IntArray?,
    detailMap: FloatArray,
    magArr: FloatArray,
    nmsArr: FloatArray,
    width: Int,
    height: Int,
  ): NormalizedQuad? {
    val n = width * height
    val (palette, bgMeanY) = buildBorderPalette(featY, featU, featV, width, height)

    val distMap = IntArray(n)
    val chromaDistMap = if (featU != null && featV != null) IntArray(n) else null

    for (i in 0 until n) {
      val py = featY[i]
      val pu = featU?.get(i) ?: 128
      val pv = featV?.get(i) ?: 128

      var minYuvDist = Int.MAX_VALUE
      var minChromaDist = Int.MAX_VALUE
      for (p in palette) {
        val dy = abs(py - p.y)
        val dc = abs(pu - p.u) + abs(pv - p.v)
        val combined = if (featU != null) (dy * 0.55f + dc * 1.65f).toInt() else dy
        if (combined < minYuvDist) minYuvDist = combined
        if (dc < minChromaDist) minChromaDist = dc
      }
      distMap[i] = minYuvDist.coerceIn(0, 255)
      if (chromaDistMap != null) {
        chromaDistMap[i] = (minChromaDist * 2).coerceIn(0, 255)
      }
    }

    val masks = ArrayList<BooleanArray>(3)
    val distThresh = max(24, otsuThreshold(distMap))
    masks.add(morphologicalClose3x3(BooleanArray(n) { distMap[it] >= distThresh }, width, height))

    if (chromaDistMap != null) {
      val chromaThresh = max(20, otsuThreshold(chromaDistMap))
      masks.add(morphologicalClose3x3(BooleanArray(n) { chromaDistMap[it] >= chromaThresh }, width, height))
    }

    val yThresh = max(otsuThreshold(featY), bgMeanY + 18).coerceAtMost(232)
    masks.add(morphologicalClose3x3(BooleanArray(n) { featY[it] >= yThresh }, width, height))

    var bestQuad: NormalizedQuad? = null
    for (mask in masks) {
      val candidate = extractBestQuadFromMask(mask, featY, featU, featV, detailMap, magArr, nmsArr, width, height)
      if (candidate != null && (bestQuad == null || candidate.confidence > bestQuad.confidence)) {
        bestQuad = candidate
      }
    }
    return bestQuad
  }

  private fun morphologicalClose3x3(src: BooleanArray, width: Int, height: Int): BooleanArray {
    val dilated = BooleanArray(src.size)
    for (y in 0 until height) {
      val y0 = max(0, y - 1)
      val y1 = min(height - 1, y + 1)
      val row = y * width
      for (x in 0 until width) {
        val x0 = max(0, x - 1)
        val x1 = min(width - 1, x + 1)
        var any = false
        loop@ for (yy in y0..y1) {
          val ro = yy * width
          for (xx in x0..x1) {
            if (src[ro + xx]) {
              any = true
              break@loop
            }
          }
        }
        dilated[row + x] = any
      }
    }
    val eroded = BooleanArray(src.size)
    for (y in 0 until height) {
      val y0 = max(0, y - 1)
      val y1 = min(height - 1, y + 1)
      val row = y * width
      for (x in 0 until width) {
        val x0 = max(0, x - 1)
        val x1 = min(width - 1, x + 1)
        var all = true
        loop@ for (yy in y0..y1) {
          val ro = yy * width
          for (xx in x0..x1) {
            if (!dilated[ro + xx]) {
              all = false
              break@loop
            }
          }
        }
        eroded[row + x] = all
      }
    }
    return eroded
  }

  private fun extractBestQuadFromMask(
    mask: BooleanArray,
    featY: IntArray,
    featU: IntArray?,
    featV: IntArray?,
    detailMap: FloatArray,
    magArr: FloatArray,
    nmsArr: FloatArray,
    width: Int,
    height: Int,
  ): NormalizedQuad? {
    val n = width * height
    val visited = BooleanArray(n)
    val queue = IntArray(n)
    val compPixels = IntArray(n)

    var bestQuad: NormalizedQuad? = null
    val minCompArea = (n * 0.042f).toInt()
    val maxCompArea = (n * 0.88f).toInt()

    for (start in 0 until n) {
      if (!mask[start] || visited[start]) continue
      var head = 0
      var tail = 0
      queue[tail++] = start
      visited[start] = true

      var compCount = 0
      var minX = width
      var maxX = 0
      var minY = height
      var maxY = 0
      var sumX = 0L
      var sumY = 0L
      var borderTouches = 0

      while (head < tail) {
        val idx = queue[head++]
        compPixels[compCount++] = idx
        val px = idx % width
        val py = idx / width
        if (px < minX) minX = px
        if (px > maxX) maxX = px
        if (py < minY) minY = py
        if (py > maxY) maxY = py
        sumX += px
        sumY += py
        if (px <= 1 || px >= width - 2 || py <= 1 || py >= height - 2) borderTouches++

        if (px > 0) {
          val nb = idx - 1
          if (mask[nb] && !visited[nb]) { visited[nb] = true; queue[tail++] = nb }
        }
        if (px + 1 < width) {
          val nb = idx + 1
          if (mask[nb] && !visited[nb]) { visited[nb] = true; queue[tail++] = nb }
        }
        if (py > 0) {
          val nb = idx - width
          if (mask[nb] && !visited[nb]) { visited[nb] = true; queue[tail++] = nb }
        }
        if (py + 1 < height) {
          val nb = idx + width
          if (mask[nb] && !visited[nb]) { visited[nb] = true; queue[tail++] = nb }
        }
      }

      if (compCount !in minCompArea..maxCompArea) continue
      if (borderTouches > compCount * 0.20f && (maxX - minX) > width * 0.90f && (maxY - minY) > height * 0.90f) {
        continue
      }

      // Collect interior boundary points (ignore artificial image-viewport clips so curved/clipped blobs fail straightness)
      val boundary = ArrayList<NormalizedPoint>(compCount / 4 + 16)
      for (k in 0 until compCount) {
        val idx = compPixels[k]
        val px = idx % width
        val py = idx / width
        if (px <= 1 || px >= width - 2 || py <= 1 || py >= height - 2) continue
        val isBoundary = !mask[idx - 1] || !mask[idx + 1] || !mask[idx - width] || !mask[idx + width]
        if (isBoundary) {
          boundary.add(NormalizedPoint(px.toFloat(), py.toFloat()))
        }
      }
      if (boundary.size < 16) continue

      val hull = computeConvexHull(boundary)
      if (hull.size < 4) continue

      val minRect = computeMinAreaRotatedRect(hull) ?: continue
      val rectArea = (minRect.halfW * 2f) * (minRect.halfH * 2f)
      if (rectArea <= 1f) continue
      val rectangularity = compCount.toFloat() / rectArea
      if (rectangularity < 0.72f) continue

      val aspect = max(minRect.halfW, minRect.halfH) / max(1f, min(minRect.halfW, minRect.halfH))
      if (aspect > 4.0f) continue

      val topPts = ArrayList<NormalizedPoint>()
      val rightPts = ArrayList<NormalizedPoint>()
      val bottomPts = ArrayList<NormalizedPoint>()
      val leftPts = ArrayList<NormalizedPoint>()

      for (pt in boundary) {
        val dx = pt.x - minRect.cx
        val dy = pt.y - minRect.cy
        val lx = dx * minRect.ux + dy * minRect.uy
        val ly = dx * minRect.vx + dy * minRect.vy

        if (ly < -minRect.halfH * 0.65f && abs(lx) < minRect.halfW * 0.85f) topPts.add(pt)
        if (ly > minRect.halfH * 0.65f && abs(lx) < minRect.halfW * 0.85f) bottomPts.add(pt)
        if (lx < -minRect.halfW * 0.65f && abs(ly) < minRect.halfH * 0.85f) leftPts.add(pt)
        if (lx > minRect.halfW * 0.65f && abs(ly) < minRect.halfH * 0.85f) rightPts.add(pt)
      }

      val defaultTop = LineEq(minRect.vx, minRect.vy, minRect.cx * minRect.vx + minRect.cy * minRect.vy - minRect.halfH)
      val defaultBot = LineEq(minRect.vx, minRect.vy, minRect.cx * minRect.vx + minRect.cy * minRect.vy + minRect.halfH)
      val defaultLeft = LineEq(minRect.ux, minRect.uy, minRect.cx * minRect.ux + minRect.cy * minRect.uy - minRect.halfW)
      val defaultRight = LineEq(minRect.ux, minRect.uy, minRect.cx * minRect.ux + minRect.cy * minRect.uy + minRect.halfW)

      val lTop = fitRobustLine(topPts) ?: defaultTop
      val lBot = fitRobustLine(bottomPts) ?: defaultBot
      val lLeft = fitRobustLine(leftPts) ?: defaultLeft
      val lRight = fitRobustLine(rightPts) ?: defaultRight

      val rawCorners = listOfNotNull(
        intersectLines(lTop, lLeft),
        intersectLines(lTop, lRight),
        intersectLines(lBot, lRight),
        intersectLines(lBot, lLeft),
      )
      if (rawCorners.size != 4) continue

      val ordered = orderCornersClockwise(rawCorners)
      val normQuad = NormalizedQuad(
        topLeft = NormalizedPoint((ordered[0].x / width).coerceIn(0f, 1f), (ordered[0].y / height).coerceIn(0f, 1f)),
        topRight = NormalizedPoint((ordered[1].x / width).coerceIn(0f, 1f), (ordered[1].y / height).coerceIn(0f, 1f)),
        bottomRight = NormalizedPoint((ordered[2].x / width).coerceIn(0f, 1f), (ordered[2].y / height).coerceIn(0f, 1f)),
        bottomLeft = NormalizedPoint((ordered[3].x / width).coerceIn(0f, 1f), (ordered[3].y / height).coerceIn(0f, 1f)),
      )
      if (!normQuad.isConvexValid(minArea = 0.042f)) continue
      if (normQuad.areaRatio() > 0.88f) continue

      // Reject curved organic blobs (e.g. flower petals) whose boundaries do not closely fit 4 straight lines
      val straightness = measureLineStraightness(boundary, listOf(lTop, lRight, lBot, lLeft), tolPx = 2.1f)
      if (straightness < 0.72f) continue

      // Measure real image edge continuity along all 4 sides (zero-clamping guard prevents fake border edges!)
      val sideGradients = listOf(
        measureSegmentGradient(magArr, width, height, ordered[0], ordered[1]),
        measureSegmentGradient(magArr, width, height, ordered[1], ordered[2]),
        measureSegmentGradient(magArr, width, height, ordered[2], ordered[3]),
        measureSegmentGradient(magArr, width, height, ordered[3], ordered[0]),
      )
      val minSideGrad = sideGradients.minOrNull() ?: 0f
      val avgSideGrad = sideGradients.average().toFloat()
      val sharpNmsSupport = listOf(
        measureSegmentEdgeSupport(nmsArr, width, height, ordered[0], ordered[1], 18f),
        measureSegmentEdgeSupport(nmsArr, width, height, ordered[1], ordered[2], 18f),
        measureSegmentEdgeSupport(nmsArr, width, height, ordered[2], ordered[3], 18f),
        measureSegmentEdgeSupport(nmsArr, width, height, ordered[3], ordered[0], 18f),
      ).average().toFloat()

      val sideContrasts = measurePerSideBoundaryContrast(
        featY, featU, featV, width, height,
        ordered[0], ordered[1], ordered[2], ordered[3],
      )
      val contrastScore = sideContrasts.average().toFloat()
      if (sideContrasts.count { it >= 0.22f } < 3 && contrastScore < 0.30f) continue

      // Penalize medium/small shapes that clip against the viewport boundary (flower petals / shadows)
      val clipPenalty = computeViewportClipPenalty(normQuad)
      if (minSideGrad < 0.14f && normQuad.areaRatio() < 0.60f) continue

      val centroidX = (sumX.toFloat() / compCount) / width
      val centroidY = (sumY.toFloat() / compCount) / height
      val centerScore = computeViewfinderCenterScore(centroidX, centroidY)
      val detailBonus = measureInteriorDetail(detailMap, width, height, normQuad)

      val confidence = (
        rectangularity * 0.26f +
          straightness * 0.24f +
          contrastScore * 0.22f +
          avgSideGrad * 0.14f +
          sharpNmsSupport * 0.08f +
          centerScore * 0.10f +
          detailBonus * 0.08f -
          clipPenalty
        ).coerceIn(0f, 1f)

      if (bestQuad == null || confidence > bestQuad.confidence) {
        bestQuad = normQuad.copy(confidence = confidence)
      }
    }
    return bestQuad
  }

  private fun findOrientedEdgeQuad(
    featY: IntArray,
    featU: IntArray?,
    featV: IntArray?,
    detailMap: FloatArray,
    gxArr: FloatArray,
    gyArr: FloatArray,
    magArr: FloatArray,
    nmsArr: FloatArray,
    width: Int,
    height: Int,
  ): NormalizedQuad? {
    val n = width * height
    val magHist = IntArray(256)
    var activeCount = 0
    for (i in 0 until n) {
      val v = nmsArr[i]
      if (v > 0f) {
        magHist[(v / 5f).toInt().coerceIn(0, 255)]++
        activeCount++
      }
    }
    if (activeCount < 20) return null

    var acc = 0
    var threshBin = 10
    val targetCount = (n * 0.045f).toInt().coerceIn(20, activeCount)
    for (b in 255 downTo 5) {
      acc += magHist[b]
      if (acc >= targetCount) {
        threshBin = b
        break
      }
    }
    val edgeThreshold = max(24f, threshBin * 5f)

    val diag = hypot(width.toFloat(), height.toFloat()).toInt() + 4
    val rhoOffset = diag
    val rhoBins = (diag * 2) / 2 + 2
    val angleCount = ANGLES_RAD.size

    val hAcc = FloatArray(angleCount * rhoBins)
    val vAcc = FloatArray(angleCount * rhoBins)

    for (y in 2 until height - 2) {
      val ro = y * width
      for (x in 2 until width - 2) {
        val mag = nmsArr[ro + x]
        if (mag < edgeThreshold) continue
        val gx = gxArr[ro + x]
        val gy = gyArr[ro + x]
        val weight = (mag / edgeThreshold).coerceIn(0.6f, 3.2f)

        if (abs(gy) >= abs(gx) * 0.85f) {
          val theta = foldAngleAroundZero(atan2(gx, gy))
          if (abs(theta) <= 0.68f) {
            val baseIdx = (((theta * 180f / PI.toFloat()) - MIN_ANGLE_DEG) / ANGLE_STEP_DEG).roundToInt()
            for (a in max(0, baseIdx - 1)..min(angleCount - 1, baseIdx + 1)) {
              val rho = x * SIN_TABLE[a] + y * COS_TABLE[a]
              val rBin = ((rho + rhoOffset) / 2f).roundToInt().coerceIn(0, rhoBins - 1)
              hAcc[a * rhoBins + rBin] += weight
            }
          }
        }
        if (abs(gx) >= abs(gy) * 0.85f) {
          val phi = foldAngleAroundZero(atan2(gy, gx))
          if (abs(phi) <= 0.68f) {
            val baseIdx = (((phi * 180f / PI.toFloat()) - MIN_ANGLE_DEG) / ANGLE_STEP_DEG).roundToInt()
            for (a in max(0, baseIdx - 1)..min(angleCount - 1, baseIdx + 1)) {
              val rho = x * COS_TABLE[a] + y * SIN_TABLE[a]
              val rBin = ((rho + rhoOffset) / 2f).roundToInt().coerceIn(0, rhoBins - 1)
              vAcc[a * rhoBins + rBin] += weight
            }
          }
        }
      }
    }

    val hPeaks = extractHoughPeaks(hAcc, angleCount, rhoBins, rhoOffset, maxPeaks = 16) { t, r ->
      (r - (width * 0.5f) * sin(t)) / cos(t)
    }.filter { it.centerPos in (height * 0.03f)..(height * 0.96f) }

    val vPeaks = extractHoughPeaks(vAcc, angleCount, rhoBins, rhoOffset, maxPeaks = 16) { p, r ->
      (r - (height * 0.5f) * sin(p)) / cos(p)
    }.filter { it.centerPos in (width * 0.03f)..(width * 0.97f) }

    if (hPeaks.size < 2 || vPeaks.size < 2) return null

    var bestQuad: NormalizedQuad? = null
    var bestScore = 0f

    for (i in hPeaks.indices) {
      for (j in i + 1 until hPeaks.size) {
        val h1 = hPeaks[i]
        val h2 = hPeaks[j]
        val top = if (h1.centerPos < h2.centerPos) h1 else h2
        val bot = if (h1.centerPos < h2.centerPos) h2 else h1
        if (bot.centerPos - top.centerPos < height * 0.12f) continue
        if (abs(top.theta - bot.theta) > 0.24f) continue
        val avgHTheta = (top.theta + bot.theta) * 0.5f

        for (k in vPeaks.indices) {
          for (l in k + 1 until vPeaks.size) {
            val v1 = vPeaks[k]
            val v2 = vPeaks[l]
            val left = if (v1.centerPos < v2.centerPos) v1 else v2
            val right = if (v1.centerPos < v2.centerPos) v2 else v1
            if (right.centerPos - left.centerPos < width * 0.14f) continue
            if (abs(left.theta - right.theta) > 0.24f) continue
            val avgVPhi = (left.theta + right.theta) * 0.5f

            // Orthogonality constraint: for true perspective rectangles, sin(theta + phi) ≈ 0 => avgHTheta ≈ -avgVPhi
            if (abs(avgHTheta + avgVPhi) > 0.30f) continue

            val tl = intersectHV(top, left) ?: continue
            val tr = intersectHV(top, right) ?: continue
            val br = intersectHV(bot, right) ?: continue
            val bl = intersectHV(bot, left) ?: continue

            val rawPts = listOf(tl, tr, br, bl)
            if (rawPts.any { it.x < -width * 0.03f || it.x > width * 1.03f || it.y < -height * 0.03f || it.y > height * 1.03f }) {
              continue
            }

            val normQuad = NormalizedQuad(
              topLeft = NormalizedPoint((tl.x / width).coerceIn(0f, 1f), (tl.y / height).coerceIn(0f, 1f)),
              topRight = NormalizedPoint((tr.x / width).coerceIn(0f, 1f), (tr.y / height).coerceIn(0f, 1f)),
              bottomRight = NormalizedPoint((br.x / width).coerceIn(0f, 1f), (br.y / height).coerceIn(0f, 1f)),
              bottomLeft = NormalizedPoint((bl.x / width).coerceIn(0f, 1f), (bl.y / height).coerceIn(0f, 1f)),
            )
            if (!normQuad.isConvexValid(minArea = 0.045f) || normQuad.areaRatio() > 0.88f) continue

            // Score crispness along all 4 sides using NMS peaks + raw gradient magnitude
            val sTop = measureSegmentEdgeSupport(nmsArr, width, height, tl, tr, edgeThreshold * 0.7f)
            val sBot = measureSegmentEdgeSupport(nmsArr, width, height, bl, br, edgeThreshold * 0.7f)
            val sLeft = measureSegmentEdgeSupport(nmsArr, width, height, tl, bl, edgeThreshold * 0.7f)
            val sRight = measureSegmentEdgeSupport(nmsArr, width, height, tr, br, edgeThreshold * 0.7f)
            val minSide = min(min(sTop, sBot), min(sLeft, sRight))
            val avgSide = (sTop + sBot + sLeft + sRight) * 0.25f
            if (minSide < 0.28f || avgSide < 0.44f) continue

            val sideContrasts = measurePerSideBoundaryContrast(featY, featU, featV, width, height, tl, tr, br, bl)
            val avgContrast = sideContrasts.average().toFloat()
            val minContrast = sideContrasts.minOrNull() ?: 0f
            if (sideContrasts.count { it >= 0.16f } < 3 && avgContrast < 0.22f) continue

            val bisectionPenalty = measureInteriorBisectionPenalty(
              nmsArr, width, height, tl, tr, br, bl, edgeThreshold * 0.75f,
            )
            val clipPenalty = computeViewportClipPenalty(normQuad)

            val cx = (normQuad.topLeft.x + normQuad.topRight.x + normQuad.bottomRight.x + normQuad.bottomLeft.x) * 0.25f
            val cy = (normQuad.topLeft.y + normQuad.topRight.y + normQuad.bottomRight.y + normQuad.bottomLeft.y) * 0.25f
            val centerScore = computeViewfinderCenterScore(cx, cy)
            val detailBonus = measureInteriorDetail(detailMap, width, height, normQuad)

            val score = avgSide * 0.42f +
              minSide * 0.20f +
              avgContrast * 0.20f +
              minContrast * 0.08f +
              centerScore * 0.10f +
              detailBonus * 0.08f -
              bisectionPenalty -
              clipPenalty

            if (score > bestScore) {
              bestScore = score
              bestQuad = normQuad.copy(confidence = score.coerceIn(0f, 1f))
            }
          }
        }
      }
    }
    return if (bestQuad != null && bestQuad.confidence >= 0.44f) bestQuad else null
  }

  private fun computeViewportClipPenalty(quad: NormalizedQuad): Float {
    val pts = quad.points()
    var clippedCorners = 0
    for (p in pts) {
      if (p.x <= 0.02f || p.x >= 0.98f || p.y <= 0.03f || p.y >= 0.94f) {
        clippedCorners++
      }
    }
    val area = quad.areaRatio()
    return when {
      area < 0.55f && clippedCorners >= 2 -> 0.28f
      area < 0.55f && clippedCorners == 1 -> 0.14f
      else -> 0f
    }
  }

  private fun computeViewfinderCenterScore(cx: Float, cy: Float): Float {
    val dist = hypot(cx - 0.5f, (cy - 0.46f) * 1.1f)
    val hudPenalty = if (cy > 0.70f) (cy - 0.70f) * 1.6f else 0f
    return (1f - dist * 1.5f - hudPenalty).coerceIn(0f, 1f)
  }

  private fun measureInteriorDetail(
    detailMap: FloatArray,
    width: Int,
    height: Int,
    quad: NormalizedQuad,
  ): Float {
    val minX = (min(quad.topLeft.x, quad.bottomLeft.x) * width).roundToInt().coerceIn(1, width - 2)
    val maxX = (max(quad.topRight.x, quad.bottomRight.x) * width).roundToInt().coerceIn(minX + 1, width - 1)
    val minY = (min(quad.topLeft.y, quad.topRight.y) * height).roundToInt().coerceIn(1, height - 2)
    val maxY = (max(quad.bottomLeft.y, quad.bottomRight.y) * height).roundToInt().coerceIn(minY + 1, height - 1)

    val insetX = ((maxX - minX) * 0.15f).toInt()
    val insetY = ((maxY - minY) * 0.15f).toInt()
    var hits = 0
    var samples = 0
    val stepY = max(1, (maxY - minY - 2 * insetY) / 10)
    val stepX = max(1, (maxX - minX - 2 * insetX) / 10)
    var y = minY + insetY
    while (y <= maxY - insetY) {
      val row = y * width
      var x = minX + insetX
      while (x <= maxX - insetX) {
        if (detailMap[row + x] >= 7f) hits++
        samples++
        x += stepX
      }
      y += stepY
    }
    return if (samples == 0) 0f else (hits.toFloat() / samples.toFloat() * 2.2f).coerceIn(0f, 1f)
  }

  private fun measureLineStraightness(
    boundary: List<NormalizedPoint>,
    lines: List<LineEq>,
    tolPx: Float,
  ): Float {
    if (boundary.isEmpty()) return 0f
    var closeCount = 0
    for (p in boundary) {
      var minDist = Float.MAX_VALUE
      for (l in lines) {
        val d = abs(l.a * p.x + l.b * p.y - l.c)
        if (d < minDist) minDist = d
      }
      if (minDist <= tolPx) closeCount++
    }
    return (closeCount.toFloat() / boundary.size.toFloat()).coerceIn(0f, 1f)
  }

  private fun measureInteriorBisectionPenalty(
    edgeArr: FloatArray,
    width: Int,
    height: Int,
    tl: NormalizedPoint,
    tr: NormalizedPoint,
    br: NormalizedPoint,
    bl: NormalizedPoint,
    edgeThreshold: Float,
  ): Float {
    var maxSplit = 0f
    for (frac in listOf(0.28f, 0.50f, 0.72f)) {
      val leftPt = NormalizedPoint(tl.x + (bl.x - tl.x) * frac, tl.y + (bl.y - tl.y) * frac)
      val rightPt = NormalizedPoint(tr.x + (br.x - tr.x) * frac, tr.y + (br.y - tr.y) * frac)
      val s = measureSegmentEdgeSupport(edgeArr, width, height, leftPt, rightPt, edgeThreshold)
      if (s > maxSplit) maxSplit = s
    }
    for (frac in listOf(0.28f, 0.50f, 0.72f)) {
      val topPt = NormalizedPoint(tl.x + (tr.x - tl.x) * frac, tl.y + (tr.y - tl.y) * frac)
      val botPt = NormalizedPoint(bl.x + (br.x - bl.x) * frac, bl.y + (br.y - bl.y) * frac)
      val s = measureSegmentEdgeSupport(edgeArr, width, height, topPt, botPt, edgeThreshold)
      if (s > maxSplit) maxSplit = s
    }
    return ((maxSplit - 0.50f).coerceAtLeast(0f) * 0.45f)
  }

  private fun boxBlur3x3(src: IntArray, width: Int, height: Int): IntArray {
    val dst = IntArray(width * height)
    for (y in 0 until height) {
      val y0 = max(0, y - 1)
      val y1 = min(height - 1, y + 1)
      for (x in 0 until width) {
        val x0 = max(0, x - 1)
        val x1 = min(width - 1, x + 1)
        var sum = 0
        var cnt = 0
        for (yy in y0..y1) {
          val ro = yy * width
          for (xx in x0..x1) {
            sum += src[ro + xx]
            cnt++
          }
        }
        dst[y * width + x] = sum / cnt
      }
    }
    return dst
  }

  private fun boxBlur5x5(src: IntArray, width: Int, height: Int): IntArray {
    val dst = IntArray(width * height)
    for (y in 0 until height) {
      val y0 = max(0, y - 2)
      val y1 = min(height - 1, y + 2)
      for (x in 0 until width) {
        val x0 = max(0, x - 2)
        val x1 = min(width - 1, x + 2)
        var sum = 0
        var cnt = 0
        for (yy in y0..y1) {
          val ro = yy * width
          for (xx in x0..x1) {
            sum += src[ro + xx]
            cnt++
          }
        }
        dst[y * width + x] = sum / cnt
      }
    }
    return dst
  }

  private fun otsuThreshold(values: IntArray): Int {
    val hist = IntArray(256)
    for (v in values) hist[v.coerceIn(0, 255)]++
    val total = values.size
    var sumAll = 0L
    for (i in 0..255) sumAll += i.toLong() * hist[i]

    var sumB = 0L
    var wB = 0
    var maxVar = -1.0
    var bestThresh = 128

    for (t in 0..255) {
      wB += hist[t]
      if (wB == 0) continue
      val wF = total - wB
      if (wF == 0) break
      sumB += t.toLong() * hist[t]
      val mB = sumB.toDouble() / wB.toDouble()
      val mF = (sumAll - sumB).toDouble() / wF.toDouble()
      val diff = mB - mF
      val betweenVar = wB.toDouble() * wF.toDouble() * diff * diff
      if (betweenVar > maxVar) {
        maxVar = betweenVar
        bestThresh = t
      }
    }
    return bestThresh
  }

  private fun computeConvexHull(points: List<NormalizedPoint>): List<NormalizedPoint> {
    if (points.size <= 3) return points
    val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
    fun cross(o: NormalizedPoint, a: NormalizedPoint, b: NormalizedPoint): Float =
      (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    val lower = ArrayList<NormalizedPoint>()
    for (p in sorted) {
      while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) {
        lower.removeAt(lower.size - 1)
      }
      lower.add(p)
    }
    val upper = ArrayList<NormalizedPoint>()
    for (i in sorted.indices.reversed()) {
      val p = sorted[i]
      while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) {
        upper.removeAt(upper.size - 1)
      }
      upper.add(p)
    }
    if (lower.isNotEmpty()) lower.removeAt(lower.size - 1)
    if (upper.isNotEmpty()) upper.removeAt(upper.size - 1)
    lower.addAll(upper)
    return lower
  }

  private data class RotatedRect(
    val cx: Float,
    val cy: Float,
    val ux: Float,
    val uy: Float,
    val vx: Float,
    val vy: Float,
    val halfW: Float,
    val halfH: Float,
  )

  private fun computeMinAreaRotatedRect(hull: List<NormalizedPoint>): RotatedRect? {
    if (hull.size < 3) return null
    var bestArea = Float.MAX_VALUE
    var bestRect: RotatedRect? = null

    for (i in hull.indices) {
      val a = hull[i]
      val b = hull[(i + 1) % hull.size]
      val ex = b.x - a.x
      val ey = b.y - a.y
      val len = hypot(ex, ey)
      if (len < 1e-3f) continue
      val ux = ex / len
      val uy = ey / len
      val vx = -uy
      val vy = ux

      var minU = Float.MAX_VALUE
      var maxU = -Float.MAX_VALUE
      var minV = Float.MAX_VALUE
      var maxV = -Float.MAX_VALUE
      for (p in hull) {
        val pu = p.x * ux + p.y * uy
        val pv = p.x * vx + p.y * vy
        if (pu < minU) minU = pu
        if (pu > maxU) maxU = pu
        if (pv < minV) minV = pv
        if (pv > maxV) maxV = pv
      }
      val w = maxU - minU
      val h = maxV - minV
      val area = w * h
      if (area < bestArea) {
        bestArea = area
        val cu = (minU + maxU) * 0.5f
        val cv = (minV + maxV) * 0.5f
        val cx = cu * ux + cv * vx
        val cy = cu * uy + cv * vy
        bestRect = RotatedRect(cx, cy, ux, uy, vx, vy, w * 0.5f, h * 0.5f)
      }
    }
    return bestRect
  }

  private fun fitRobustLine(points: List<NormalizedPoint>): LineEq? {
    if (points.size < 4) return null
    fun fitTls(pts: List<NormalizedPoint>): LineEq? {
      if (pts.size < 3) return null
      var mx = 0f
      var my = 0f
      for (p in pts) { mx += p.x; my += p.y }
      mx /= pts.size
      my /= pts.size

      var sxx = 0f
      var syy = 0f
      var sxy = 0f
      for (p in pts) {
        val dx = p.x - mx
        val dy = p.y - my
        sxx += dx * dx
        syy += dy * dy
        sxy += dx * dy
      }
      val theta = 0.5f * atan2(2f * sxy, sxx - syy)
      val tx = cos(theta)
      val ty = sin(theta)
      val nx = -ty
      val ny = tx
      val c = nx * mx + ny * my
      return LineEq(nx, ny, c)
    }

    val pass1 = fitTls(points) ?: return null
    val inliers = points.filter { p ->
      abs(pass1.a * p.x + pass1.b * p.y - pass1.c) <= 1.8f
    }
    return if (inliers.size >= 4) fitTls(inliers) ?: pass1 else pass1
  }

  private fun intersectLines(l1: LineEq, l2: LineEq): NormalizedPoint? {
    val det = l1.a * l2.b - l2.a * l1.b
    if (abs(det) < 1e-3f) return null
    val x = (l1.c * l2.b - l2.c * l1.b) / det
    val y = (l1.a * l2.c - l2.a * l1.c) / det
    return NormalizedPoint(x, y)
  }

  private fun orderCornersClockwise(pts: List<NormalizedPoint>): List<NormalizedPoint> {
    val tl = pts.minBy { it.x + it.y }
    val br = pts.maxBy { it.x + it.y }
    val remaining = pts.filter { it !== tl && it !== br }
    if (remaining.size != 2) return pts
    val tr = if (remaining[0].x - remaining[0].y >= remaining[1].x - remaining[1].y) remaining[0] else remaining[1]
    val bl = if (tr === remaining[0]) remaining[1] else remaining[0]
    return listOf(tl, tr, br, bl)
  }

  private fun foldAngleAroundZero(angle: Float): Float {
    var a = angle
    while (a > PI / 2) a -= PI.toFloat()
    while (a < -PI / 2) a += PI.toFloat()
    return a
  }

  private fun extractHoughPeaks(
    acc: FloatArray,
    angleCount: Int,
    rhoBins: Int,
    rhoOffset: Int,
    maxPeaks: Int,
    evalLineRhoAtCenter: (Float, Float) -> Float,
  ): List<LineCandidate> {
    val all = mutableListOf<LineCandidate>()
    for (a in 0 until angleCount) {
      val theta = ANGLES_RAD[a]
      val row = a * rhoBins
      for (r in 2 until rhoBins - 2) {
        val v = acc[row + r]
        if (v < 5f) continue
        var isMax = true
        for (da in max(0, a - 1)..min(angleCount - 1, a + 1)) {
          val ar = da * rhoBins
          for (dr in (r - 2)..(r + 2)) {
            if (acc[ar + dr] > v) {
              isMax = false
              break
            }
          }
          if (!isMax) break
        }
        if (!isMax) continue
        val rho = r * 2f - rhoOffset
        val centerPos = evalLineRhoAtCenter(theta, rho)
        all.add(LineCandidate(theta, rho, v, centerPos))
      }
    }
    all.sortByDescending { it.score }
    val selected = mutableListOf<LineCandidate>()
    for (cand in all) {
      if (selected.none { abs(it.centerPos - cand.centerPos) < 9f && abs(it.theta - cand.theta) < 0.14f }) {
        selected.add(cand)
        if (selected.size >= maxPeaks) break
      }
    }
    return selected
  }

  private fun intersectHV(h: LineCandidate, v: LineCandidate): NormalizedPoint? {
    val a1 = sin(h.theta)
    val b1 = cos(h.theta)
    val c1 = h.rho
    val a2 = cos(v.theta)
    val b2 = sin(v.theta)
    val c2 = v.rho
    val det = a1 * b2 - a2 * b1
    if (abs(det) < 1e-3f) return null
    return NormalizedPoint((c1 * b2 - c2 * b1) / det, (a1 * c2 - a2 * c1) / det)
  }

  private fun measureSegmentEdgeSupport(
    edgeArr: FloatArray,
    width: Int,
    height: Int,
    p1: NormalizedPoint,
    p2: NormalizedPoint,
    threshold: Float,
  ): Float {
    val steps = 24
    var hits = 0
    val softThresh = threshold * 0.65f
    for (i in 1 until steps) {
      val t = i.toFloat() / steps.toFloat()
      val cx = (p1.x + (p2.x - p1.x) * t).roundToInt()
      val cy = (p1.y + (p2.y - p1.y) * t).roundToInt()
      if (cx !in 1 until width - 1 || cy !in 1 until height - 1) continue
      var hit = false
      for (dy in -2..2) {
        val yy = cy + dy
        if (yy !in 1 until height - 1) continue
        val ro = yy * width
        for (dx in -2..2) {
          val xx = cx + dx
          if (xx in 1 until width - 1 && edgeArr[ro + xx] >= softThresh) {
            hit = true
            break
          }
        }
        if (hit) break
      }
      if (hit) hits++
    }
    return hits.toFloat() / (steps - 1).toFloat()
  }

  private fun measureSegmentGradient(
    magArr: FloatArray,
    width: Int,
    height: Int,
    p1: NormalizedPoint,
    p2: NormalizedPoint,
  ): Float {
    val steps = 20
    var sum = 0f
    for (i in 1 until steps) {
      val t = i.toFloat() / steps.toFloat()
      val cx = (p1.x + (p2.x - p1.x) * t).roundToInt()
      val cy = (p1.y + (p2.y - p1.y) * t).roundToInt()
      if (cx !in 2 until width - 2 || cy !in 2 until height - 2) continue
      var localMax = 0f
      for (dy in -2..2) {
        val yy = (cy + dy).coerceIn(1, height - 2)
        val ro = yy * width
        for (dx in -2..2) {
          val xx = (cx + dx).coerceIn(1, width - 2)
          if (magArr[ro + xx] > localMax) localMax = magArr[ro + xx]
        }
      }
      sum += (localMax / 130f).coerceIn(0f, 1f)
    }
    return sum / (steps - 1).toFloat()
  }

  private fun measurePerSideBoundaryContrast(
    featY: IntArray,
    featU: IntArray?,
    featV: IntArray?,
    width: Int,
    height: Int,
    tl: NormalizedPoint,
    tr: NormalizedPoint,
    br: NormalizedPoint,
    bl: NormalizedPoint,
  ): List<Float> {
    val cx = (tl.x + tr.x + br.x + bl.x) * 0.25f
    val cy = (tl.y + tr.y + br.y + bl.y) * 0.25f

    val sides = listOf(tl to tr, tr to br, br to bl, bl to tl)
    val tSamples = listOf(0.22f, 0.36f, 0.50f, 0.64f, 0.78f)

    return sides.map { (a, b) ->
      var sideSum = 0f
      for (t in tSamples) {
        val px = a.x + (b.x - a.x) * t
        val py = a.y + (b.y - a.y) * t
        val dx = px - cx
        val dy = py - cy
        val len = max(1f, hypot(dx, dy))
        val nx = dx / len
        val ny = dy / len

        val inX = (px - nx * 5f).roundToInt()
        val inY = (py - ny * 5f).roundToInt()
        val outX = (px + nx * 5f).roundToInt()
        val outY = (py + ny * 5f).roundToInt()
        if (inX !in 0 until width || inY !in 0 until height || outX !in 0 until width || outY !in 0 until height) {
          continue
        }
        val iIn = inY * width + inX
        val iOut = outY * width + outX
        val dLum = abs(featY[iIn] - featY[iOut]).toFloat()
        val dChroma = if (featU != null && featV != null) {
          (abs(featU[iIn] - featU[iOut]) + abs(featV[iIn] - featV[iOut])) * 1.65f
        } else {
          0f
        }
        sideSum += ((dLum * 0.65f + dChroma) / 68f).coerceIn(0f, 1f)
      }
      sideSum / tSamples.size.toFloat()
    }
  }
}
