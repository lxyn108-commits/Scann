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
 * Real-time document quadrilateral detector engineered for both cluttered/textured backgrounds
 * (rugs, wood, mats) and clean desks. Combines YUV texture-suppressed foreground segmentation +
 * convex-hull rotating-calipers + 4-side trimmed line fitting, with an oriented thin-edge fallback.
 */
object DocumentQuadDetector {

  private const val MIN_ANGLE_DEG = -32
  private const val MAX_ANGLE_DEG = 32
  private const val ANGLE_STEP_DEG = 2
  private val ANGLES_RAD: FloatArray = FloatArray((MAX_ANGLE_DEG - MIN_ANGLE_DEG) / ANGLE_STEP_DEG + 1) { idx ->
    ((MIN_ANGLE_DEG + idx * ANGLE_STEP_DEG) * PI / 180.0).toFloat()
  }
  private val COS_TABLE = FloatArray(ANGLES_RAD.size) { cos(ANGLES_RAD[it]) }
  private val SIN_TABLE = FloatArray(ANGLES_RAD.size) { sin(ANGLES_RAD[it]) }

  private data class LineEq(val a: Float, val b: Float, val c: Float) // a*x + b*y = c, unit normal (a, b)
  private data class LineCandidate(val theta: Float, val rho: Float, val score: Float, val centerPos: Float)

  fun detectFromImageProxy(imageProxy: ImageProxy, targetMaxSide: Int = 128): Pair<NormalizedQuad?, Pair<Int, Int>> {
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

  fun detectFromBitmap(bitmap: Bitmap, targetMaxSide: Int = 144): NormalizedQuad? {
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

    // 1. Suppress high-frequency background textures (rugs, tiles, embossing) with 2x 3x3 + 1x 5x5 blur
    val smoothY = boxBlur3x3(boxBlur3x3(y, width, height), width, height)
    val featY = boxBlur5x5(smoothY, width, height)
    val featU = u?.let { boxBlur5x5(boxBlur3x3(it, width, height), width, height) }
    val featV = v?.let { boxBlur5x5(boxBlur3x3(it, width, height), width, height) }

    // 2. Gradient magnitude on texture-suppressed Y
    val n = width * height
    val gxArr = FloatArray(n)
    val gyArr = FloatArray(n)
    val magArr = FloatArray(n)
    for (yy in 1 until height - 1) {
      val ym1 = (yy - 1) * width
      val y0 = yy * width
      val yp1 = (yy + 1) * width
      for (xx in 1 until width - 1) {
        val tl = smoothY[ym1 + xx - 1]
        val tc = smoothY[ym1 + xx]
        val tr = smoothY[ym1 + xx + 1]
        val ml = smoothY[y0 + xx - 1]
        val mr = smoothY[y0 + xx + 1]
        val bl = smoothY[yp1 + xx - 1]
        val bc = smoothY[yp1 + xx]
        val br = smoothY[yp1 + xx + 1]
        val gx = (-tl + tr - 2 * ml + 2 * mr - bl + br).toFloat()
        val gy = (-tl - 2 * tc - tr + bl + 2 * bc + br).toFloat()
        val idx = y0 + xx
        gxArr[idx] = gx
        gyArr[idx] = gy
        magArr[idx] = sqrt(gx * gx + gy * gy)
      }
    }

    // 3. Primary strategy: Region-based segmentation + Convex Hull + 4-side trimmed line fitting
    val regionQuad = findRegionDocumentQuad(featY, featU, featV, magArr, width, height)
    if (regionQuad != null && regionQuad.confidence >= 0.45f) {
      return regionQuad
    }

    // 4. Fallback strategy: Oriented thin-edge Hough quad (for low-contrast paper on light desk)
    val edgeQuad = findOrientedEdgeQuad(smoothY, gxArr, gyArr, magArr, width, height)
    return when {
      regionQuad != null && edgeQuad != null ->
        if (regionQuad.confidence >= edgeQuad.confidence) regionQuad else edgeQuad
      else -> regionQuad ?: edgeQuad
    }
  }

  private fun findRegionDocumentQuad(
    featY: IntArray,
    featU: IntArray?,
    featV: IntArray?,
    magArr: FloatArray,
    width: Int,
    height: Int,
  ): NormalizedQuad? {
    val n = width * height

    // Sample outer frame border ring to model background color & brightness
    val borderIndices = ArrayList<Int>(width * 4 + height * 4)
    val marginX = max(2, (width * 0.06f).toInt())
    val marginY = max(2, (height * 0.06f).toInt())
    for (yy in 0 until height) {
      val ro = yy * width
      for (xx in 0 until width) {
        if (yy < marginY || yy >= height - marginY || xx < marginX || xx >= width - marginX) {
          borderIndices.add(ro + xx)
        }
      }
    }
    var sumY = 0L
    var sumU = 0L
    var sumV = 0L
    for (idx in borderIndices) {
      sumY += featY[idx]
      if (featU != null && featV != null) {
        sumU += featU[idx]
        sumV += featV[idx]
      }
    }
    val bCount = max(1, borderIndices.size)
    val bgY = (sumY / bCount).toInt()
    val bgU = (sumU / bCount).toInt()
    val bgV = (sumV / bCount).toInt()

    // Mask A: Background-distance saliency (Y + chroma difference from outer border)
    val distMap = IntArray(n)
    for (i in 0 until n) {
      val dy = abs(featY[i] - bgY)
      val dChroma = if (featU != null && featV != null) {
        ((abs(featU[i] - bgU) + abs(featV[i] - bgV)) * 0.9f).toInt()
      } else {
        0
      }
      distMap[i] = (dy + dChroma).coerceIn(0, 255)
    }
    val distThresh = max(26, otsuThreshold(distMap))
    val maskA = BooleanArray(n) { distMap[it] >= distThresh }

    // Mask B: Bright foreground via Otsu on luminance (handles bright paper filling most of frame)
    val yThresh = max(otsuThreshold(featY), bgY + 18).coerceAtMost(230)
    val maskB = BooleanArray(n) { featY[it] >= yThresh }

    var bestQuad: NormalizedQuad? = null
    for (mask in listOf(maskA, maskB)) {
      val candidate = extractBestQuadFromMask(mask, featY, magArr, width, height)
      if (candidate != null && (bestQuad == null || candidate.confidence > bestQuad.confidence)) {
        bestQuad = candidate
      }
    }
    return bestQuad
  }

  private fun extractBestQuadFromMask(
    mask: BooleanArray,
    featY: IntArray,
    magArr: FloatArray,
    width: Int,
    height: Int,
  ): NormalizedQuad? {
    val n = width * height
    val visited = BooleanArray(n)
    val queue = IntArray(n)
    val compPixels = IntArray(n)

    var bestQuad: NormalizedQuad? = null
    val minCompArea = (n * 0.055f).toInt()
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
        if (px == 0 || px == width - 1 || py == 0 || py == height - 1) borderTouches++

        // 4-neighbors
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
      // Skip frame-border background leak
      if (borderTouches > compCount * 0.25f && (maxX - minX) > width * 0.92f && (maxY - minY) > height * 0.92f) {
        continue
      }

      // Collect boundary pixels of this component
      val boundary = ArrayList<NormalizedPoint>(compCount / 4 + 16)
      for (k in 0 until compCount) {
        val idx = compPixels[k]
        val px = idx % width
        val py = idx / width
        val isBoundary = px == 0 || px == width - 1 || py == 0 || py == height - 1 ||
          !mask[idx - 1] || !mask[idx + 1] || !mask[idx - width] || !mask[idx + width]
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
      if (aspect > 3.8f) continue

      // Partition boundary pixels into Top, Right, Bottom, Left sides & fit robust lines
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
      if (!normQuad.isConvexValid(minArea = 0.055f)) continue
      if (normQuad.areaRatio() > 0.88f) continue

      // Measure actual image gradient & luminance contrast across the fitted 4 sides
      val contrastScore = measureBoundaryContrast(
        featY, width, height,
        ordered[0], ordered[1], ordered[2], ordered[3],
      )
      val edgeSupport = (
        measureSegmentGradient(magArr, width, height, ordered[0], ordered[1]) +
          measureSegmentGradient(magArr, width, height, ordered[1], ordered[2]) +
          measureSegmentGradient(magArr, width, height, ordered[2], ordered[3]) +
          measureSegmentGradient(magArr, width, height, ordered[3], ordered[0])
        ) * 0.25f

      // Bonus for staying centered rather than hugging frame corners
      val centroidX = (sumX.toFloat() / compCount) / width
      val centroidY = (sumY.toFloat() / compCount) / height
      val centerBonus = (1f - hypot(centroidX - 0.5f, centroidY - 0.5f) * 1.4f).coerceIn(0f, 1f)

      val confidence = (rectangularity * 0.45f + contrastScore * 0.30f + edgeSupport * 0.15f + centerBonus * 0.10f)
        .coerceIn(0f, 1f)

      if (bestQuad == null || confidence > bestQuad.confidence) {
        bestQuad = normQuad.copy(confidence = confidence)
      }
    }
    return bestQuad
  }

  private fun findOrientedEdgeQuad(
    smoothY: IntArray,
    gxArr: FloatArray,
    gyArr: FloatArray,
    magArr: FloatArray,
    width: Int,
    height: Int,
  ): NormalizedQuad? {
    val n = width * height
    val magHist = IntArray(256)
    for (i in 0 until n) {
      val b = (magArr[i] / 4f).toInt().coerceIn(0, 255)
      magHist[b]++
    }
    var acc = 0
    var threshBin = 14
    for (b in 255 downTo 8) {
      acc += magHist[b]
      if (acc >= n * 0.11f) {
        threshBin = b
        break
      }
    }
    val edgeThreshold = max(32f, threshBin * 4f)

    val diag = hypot(width.toFloat(), height.toFloat()).toInt() + 4
    val rhoOffset = diag
    val rhoBins = (diag * 2) / 2 + 2
    val angleCount = ANGLES_RAD.size

    val hAcc = FloatArray(angleCount * rhoBins)
    val vAcc = FloatArray(angleCount * rhoBins)

    for (y in 2 until height - 2) {
      val ro = y * width
      for (x in 2 until width - 2) {
        val mag = magArr[ro + x]
        if (mag < edgeThreshold) continue
        val gx = gxArr[ro + x]
        val gy = gyArr[ro + x]
        val weight = (mag / edgeThreshold).coerceAtMost(3f)

        if (abs(gy) >= abs(gx) * 0.9f) {
          val theta = foldAngleAroundZero(atan2(gx, gy))
          if (abs(theta) <= 0.56f) {
            val baseIdx = (((theta * 180f / PI.toFloat()) - MIN_ANGLE_DEG) / ANGLE_STEP_DEG).roundToInt()
            for (a in max(0, baseIdx - 1)..min(angleCount - 1, baseIdx + 1)) {
              val rho = x * SIN_TABLE[a] + y * COS_TABLE[a]
              val rBin = ((rho + rhoOffset) / 2f).roundToInt().coerceIn(0, rhoBins - 1)
              hAcc[a * rhoBins + rBin] += weight
            }
          }
        }
        if (abs(gx) >= abs(gy) * 0.9f) {
          val phi = foldAngleAroundZero(atan2(gy, gx))
          if (abs(phi) <= 0.56f) {
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

    val hPeaks = extractHoughPeaks(hAcc, angleCount, rhoBins, rhoOffset, maxPeaks = 8) { t, r ->
      (r - (width * 0.5f) * sin(t)) / cos(t)
    }.filter { it.centerPos in (height * 0.02f)..(height * 0.98f) }

    val vPeaks = extractHoughPeaks(vAcc, angleCount, rhoBins, rhoOffset, maxPeaks = 8) { p, r ->
      (r - (height * 0.5f) * sin(p)) / cos(p)
    }.filter { it.centerPos in (width * 0.02f)..(width * 0.98f) }

    if (hPeaks.size < 2 || vPeaks.size < 2) return null

    var bestQuad: NormalizedQuad? = null
    var bestScore = 0f

    for (i in hPeaks.indices) {
      for (j in i + 1 until hPeaks.size) {
        val h1 = hPeaks[i]
        val h2 = hPeaks[j]
        val top = if (h1.centerPos < h2.centerPos) h1 else h2
        val bot = if (h1.centerPos < h2.centerPos) h2 else h1
        if (bot.centerPos - top.centerPos < height * 0.18f) continue
        if (abs(top.theta - bot.theta) > 0.34f) continue

        for (k in vPeaks.indices) {
          for (l in k + 1 until vPeaks.size) {
            val v1 = vPeaks[k]
            val v2 = vPeaks[l]
            val left = if (v1.centerPos < v2.centerPos) v1 else v2
            val right = if (v1.centerPos < v2.centerPos) v2 else v1
            if (right.centerPos - left.centerPos < width * 0.18f) continue
            if (abs(left.theta - right.theta) > 0.34f) continue

            val tl = intersectHV(top, left) ?: continue
            val tr = intersectHV(top, right) ?: continue
            val br = intersectHV(bot, right) ?: continue
            val bl = intersectHV(bot, left) ?: continue

            val rawPts = listOf(tl, tr, br, bl)
            if (rawPts.any { it.x < -width * 0.04f || it.x > width * 1.04f || it.y < -height * 0.04f || it.y > height * 1.04f }) {
              continue
            }

            val normQuad = NormalizedQuad(
              topLeft = NormalizedPoint((tl.x / width).coerceIn(0f, 1f), (tl.y / height).coerceIn(0f, 1f)),
              topRight = NormalizedPoint((tr.x / width).coerceIn(0f, 1f), (tr.y / height).coerceIn(0f, 1f)),
              bottomRight = NormalizedPoint((br.x / width).coerceIn(0f, 1f), (br.y / height).coerceIn(0f, 1f)),
              bottomLeft = NormalizedPoint((bl.x / width).coerceIn(0f, 1f), (bl.y / height).coerceIn(0f, 1f)),
            )
            if (!normQuad.isConvexValid(minArea = 0.07f) || normQuad.areaRatio() > 0.90f) continue

            val sTop = measureSegmentEdgeSupport(magArr, width, height, tl, tr, edgeThreshold)
            val sBot = measureSegmentEdgeSupport(magArr, width, height, bl, br, edgeThreshold)
            val sLeft = measureSegmentEdgeSupport(magArr, width, height, tl, bl, edgeThreshold)
            val sRight = measureSegmentEdgeSupport(magArr, width, height, tr, br, edgeThreshold)
            val minSide = min(min(sTop, sBot), min(sLeft, sRight))
            val avgSide = (sTop + sBot + sLeft + sRight) * 0.25f
            if (minSide < 0.28f || avgSide < 0.40f) continue

            val contrast = measureBoundaryContrast(smoothY, width, height, tl, tr, br, bl)
            val score = avgSide * 0.55f + minSide * 0.25f + contrast * 0.20f
            if (score > bestScore) {
              bestScore = score
              bestQuad = normQuad.copy(confidence = score.coerceIn(0f, 1f))
            }
          }
        }
      }
    }
    return if (bestQuad != null && bestQuad.confidence >= 0.42f) bestQuad else null
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
      // Normal perpendicular to direction (tx, ty)
      val nx = -ty
      val ny = tx
      val c = nx * mx + ny * my
      return LineEq(nx, ny, c)
    }

    val pass1 = fitTls(points) ?: return null
    val inliers = points.filter { p ->
      abs(pass1.a * p.x + pass1.b * p.y - pass1.c) <= 2.4f
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
    // Top-Left has smallest (x + y), Bottom-Right has largest (x + y)
    // Top-Right has largest (x - y), Bottom-Left has smallest (x - y)
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
        if (v < 6f) continue
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
      if (selected.none { abs(it.centerPos - cand.centerPos) < 10f && abs(it.theta - cand.theta) < 0.14f }) {
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
    magArr: FloatArray,
    width: Int,
    height: Int,
    p1: NormalizedPoint,
    p2: NormalizedPoint,
    threshold: Float,
  ): Float {
    val steps = 24
    var hits = 0
    val softThresh = threshold * 0.7f
    for (i in 1 until steps) {
      val t = i.toFloat() / steps.toFloat()
      val cx = (p1.x + (p2.x - p1.x) * t).roundToInt()
      val cy = (p1.y + (p2.y - p1.y) * t).roundToInt()
      var hit = false
      for (dy in -2..2) {
        val yy = cy + dy
        if (yy !in 1 until height - 1) continue
        val ro = yy * width
        for (dx in -2..2) {
          val xx = cx + dx
          if (xx in 1 until width - 1 && magArr[ro + xx] >= softThresh) {
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
      val cx = (p1.x + (p2.x - p1.x) * t).roundToInt().coerceIn(1, width - 2)
      val cy = (p1.y + (p2.y - p1.y) * t).roundToInt().coerceIn(1, height - 2)
      var localMax = 0f
      for (dy in -2..2) {
        val yy = (cy + dy).coerceIn(1, height - 2)
        val ro = yy * width
        for (dx in -2..2) {
          val xx = (cx + dx).coerceIn(1, width - 2)
          if (magArr[ro + xx] > localMax) localMax = magArr[ro + xx]
        }
      }
      sum += (localMax / 110f).coerceIn(0f, 1f)
    }
    return sum / (steps - 1).toFloat()
  }

  private fun measureBoundaryContrast(
    smooth: IntArray,
    width: Int,
    height: Int,
    tl: NormalizedPoint,
    tr: NormalizedPoint,
    br: NormalizedPoint,
    bl: NormalizedPoint,
  ): Float {
    val cx = (tl.x + tr.x + br.x + bl.x) * 0.25f
    val cy = (tl.y + tr.y + br.y + bl.y) * 0.25f

    fun sampleAt(x: Float, y: Float): Int {
      val ix = x.roundToInt().coerceIn(0, width - 1)
      val iy = y.roundToInt().coerceIn(0, height - 1)
      return smooth[iy * width + ix]
    }

    val mids = listOf(
      NormalizedPoint((tl.x + tr.x) * 0.5f, (tl.y + tr.y) * 0.5f),
      NormalizedPoint((tr.x + br.x) * 0.5f, (tr.y + br.y) * 0.5f),
      NormalizedPoint((br.x + bl.x) * 0.5f, (br.y + bl.y) * 0.5f),
      NormalizedPoint((bl.x + tl.x) * 0.5f, (bl.y + tl.y) * 0.5f),
    )

    var diffSum = 0f
    for (m in mids) {
      val dx = m.x - cx
      val dy = m.y - cy
      val len = max(1f, hypot(dx, dy))
      val nx = dx / len
      val ny = dy / len
      val inside = sampleAt(m.x - nx * 5f, m.y - ny * 5f)
      val outside = sampleAt(m.x + nx * 5f, m.y + ny * 5f)
      diffSum += abs(inside - outside)
    }
    return (diffSum / 4f / 65f).coerceIn(0f, 1f)
  }
}

/**
 * Tracks consecutive detected quads to smooth jitter and emit auto-capture readiness
 * when the user holds the document steady.
 */
class QuadStabilityTracker(
  private val stabilityWindowMs: Long = 950L,
  private val autoCaptureCooldownMs: Long = 2400L,
  private val maxCornerDrift: Float = 0.032f,
) {
  var smoothedQuad: NormalizedQuad? = null
    private set
  var stabilityProgress: Float = 0f
    private set

  private var missedFrames = 0
  private var stableSinceMs = 0L
  private var lastAutoCaptureMs = -1L

  fun update(detected: NormalizedQuad?, nowMs: Long): Boolean {
    if (detected == null) {
      missedFrames++
      if (missedFrames > 5) {
        smoothedQuad = null
        stableSinceMs = 0L
        stabilityProgress = 0f
      } else {
        stabilityProgress = (stabilityProgress - 0.18f).coerceAtLeast(0f)
      }
      return false
    }

    missedFrames = 0
    val prev = smoothedQuad
    if (prev == null) {
      smoothedQuad = detected
      stableSinceMs = nowMs
      stabilityProgress = 0.08f
      return false
    }

    val delta = prev.maxCornerDelta(detected)
    smoothedQuad = if (delta > 0.18f) {
      stableSinceMs = nowMs
      detected
    } else {
      prev.lerpTo(detected, 0.35f)
    }

    if (delta <= maxCornerDrift) {
      if (stableSinceMs == 0L) stableSinceMs = nowMs
      val elapsed = (nowMs - stableSinceMs).coerceAtLeast(0L)
      stabilityProgress = (elapsed.toFloat() / stabilityWindowMs.toFloat()).coerceIn(0f, 1f)
      val cooldownOk = lastAutoCaptureMs < 0L || (nowMs - lastAutoCaptureMs >= autoCaptureCooldownMs)
      if (elapsed >= stabilityWindowMs && cooldownOk) {
        lastAutoCaptureMs = nowMs
        stableSinceMs = nowMs
        stabilityProgress = 0f
        return true
      }
    } else {
      stableSinceMs = nowMs
      stabilityProgress = 0.1f
    }
    return false
  }

  fun markManualCapture(nowMs: Long) {
    lastAutoCaptureMs = nowMs
    stableSinceMs = nowMs
    stabilityProgress = 0f
  }

  fun reset() {
    smoothedQuad = null
    missedFrames = 0
    stableSinceMs = 0L
    stabilityProgress = 0f
  }
}
