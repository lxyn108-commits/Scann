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
 * Real-time document quadrilateral detector using smoothed Sobel gradients + oriented Hough
 * line accumulation + boundary edge-support & interior-vs-exterior contrast scoring.
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

  private data class LineCandidate(
    val theta: Float,
    val rho: Float,
    val score: Float,
    val isHorizontal: Boolean,
  )

  fun detectFromImageProxy(imageProxy: ImageProxy, targetMaxSide: Int = 160): Pair<NormalizedQuad?, Pair<Int, Int>> {
    val plane = imageProxy.planes[0]
    val srcW = imageProxy.width
    val srcH = imageProxy.height
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val buffer = plane.buffer
    val rotation = imageProxy.imageInfo.rotationDegrees

    val uprightW = if (rotation == 90 || rotation == 270) srcH else srcW
    val uprightH = if (rotation == 90 || rotation == 270) srcW else srcH

    val scale = targetMaxSide.toFloat() / max(uprightW, uprightH).toFloat()
    val gridW = max(48, (uprightW * scale).roundToInt())
    val gridH = max(48, (uprightH * scale).roundToInt())
    val luma = IntArray(gridW * gridH)

    val bufCap = buffer.capacity()
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
        val idx = (sy * rowStride + sx * pixelStride).coerceIn(0, bufCap - 1)
        luma[rowOffset + gx] = buffer.get(idx).toInt() and 0xFF
      }
    }

    val quad = detectFromLumaGrid(luma, gridW, gridH)
    return quad to (uprightW to uprightH)
  }

  fun detectFromBitmap(bitmap: Bitmap, targetMaxSide: Int = 180): NormalizedQuad? {
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

    val luma = IntArray(pixels.size) { i ->
      val c = pixels[i]
      val r = (c ushr 16) and 0xFF
      val g = (c ushr 8) and 0xFF
      val b = c and 0xFF
      (r * 77 + g * 150 + b * 29) ushr 8
    }
    return detectFromLumaGrid(luma, gridW, gridH)
  }

  /**
   * Core pure-Kotlin document boundary detection on a grayscale luma grid.
   * Exposed as internal for JVM unit testing.
   */
  internal fun detectFromLumaGrid(luma: IntArray, width: Int, height: Int): NormalizedQuad? {
    if (width < 24 || height < 24 || luma.size < width * height) return null

    // 1. 3x3 box smooth to suppress background texture / embossing / paper grain
    val smooth = IntArray(width * height)
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
            sum += luma[ro + xx]
            cnt++
          }
        }
        smooth[y * width + x] = sum / cnt
      }
    }

    // 2. Sobel gradients & magnitude histogram
    val n = width * height
    val gxArr = FloatArray(n)
    val gyArr = FloatArray(n)
    val magArr = FloatArray(n)
    val magHist = IntArray(256)
    var totalInner = 0

    for (y in 2 until height - 2) {
      val ym1 = (y - 1) * width
      val y0 = y * width
      val yp1 = (y + 1) * width
      for (x in 2 until width - 2) {
        val tl = smooth[ym1 + x - 1]
        val tc = smooth[ym1 + x]
        val tr = smooth[ym1 + x + 1]
        val ml = smooth[y0 + x - 1]
        val mr = smooth[y0 + x + 1]
        val bl = smooth[yp1 + x - 1]
        val bc = smooth[yp1 + x]
        val br = smooth[yp1 + x + 1]

        val gx = (-tl + tr - 2 * ml + 2 * mr - bl + br).toFloat()
        val gy = (-tl - 2 * tc - tr + bl + 2 * bc + br).toFloat()
        val mag = sqrt(gx * gx + gy * gy)
        val idx = y0 + x
        gxArr[idx] = gx
        gyArr[idx] = gy
        magArr[idx] = mag
        val bin = (mag / 4f).toInt().coerceIn(0, 255)
        magHist[bin]++
        totalInner++
      }
    }

    if (totalInner == 0) return null

    // Keep top ~14% strongest edges, with minimum floor to reject blank frames
    var acc = 0
    var threshBin = 14
    for (b in 255 downTo 8) {
      acc += magHist[b]
      if (acc >= totalInner * 0.14f) {
        threshBin = b
        break
      }
    }
    val edgeThreshold = max(36f, threshBin * 4f)

    // 3. Oriented Hough voting for horizontal-ish lines (x*sin + y*cos = rho)
    //    and vertical-ish lines (x*cos + y*sin = rho)
    val diag = hypot(width.toFloat(), height.toFloat()).toInt() + 4
    val rhoOffset = diag
    val rhoBins = (diag * 2) / 2 + 2
    val angleCount = ANGLES_RAD.size

    val hAcc = FloatArray(angleCount * rhoBins)
    val vAcc = FloatArray(angleCount * rhoBins)

    for (y in 3 until height - 3) {
      val ro = y * width
      for (x in 3 until width - 3) {
        val mag = magArr[ro + x]
        if (mag < edgeThreshold) continue
        val gx = gxArr[ro + x]
        val gy = gyArr[ro + x]
        val weight = (mag / edgeThreshold).coerceAtMost(3.5f)

        // Horizontal edge check: |gy| > |gx|
        if (abs(gy) >= abs(gx) * 0.85f) {
          val theta = atan2(gx, gy) // range (-PI, PI]
          val foldedTheta = foldAngleAroundZero(theta)
          if (abs(foldedTheta) <= 0.62f) {
            val baseIdx = (((foldedTheta * 180f / PI.toFloat()) - MIN_ANGLE_DEG) / ANGLE_STEP_DEG).roundToInt()
            for (a in max(0, baseIdx - 1)..min(angleCount - 1, baseIdx + 1)) {
              val rho = x * SIN_TABLE[a] + y * COS_TABLE[a]
              val rBin = ((rho + rhoOffset) / 2f).roundToInt().coerceIn(0, rhoBins - 1)
              hAcc[a * rhoBins + rBin] += weight
            }
          }
        }

        // Vertical edge check: |gx| > |gy|
        if (abs(gx) >= abs(gy) * 0.85f) {
          val phi = atan2(gy, gx)
          val foldedPhi = foldAngleAroundZero(phi)
          if (abs(foldedPhi) <= 0.62f) {
            val baseIdx = (((foldedPhi * 180f / PI.toFloat()) - MIN_ANGLE_DEG) / ANGLE_STEP_DEG).roundToInt()
            for (a in max(0, baseIdx - 1)..min(angleCount - 1, baseIdx + 1)) {
              val rho = x * COS_TABLE[a] + y * SIN_TABLE[a]
              val rBin = ((rho + rhoOffset) / 2f).roundToInt().coerceIn(0, rhoBins - 1)
              vAcc[a * rhoBins + rBin] += weight
            }
          }
        }
      }
    }

    // 4. Extract top local peaks in Top/Bottom and Left/Right regions
    val topCandidates = extractPeaks(
      acc = hAcc,
      angleCount = angleCount,
      rhoBins = rhoBins,
      rhoOffset = rhoOffset,
      isHorizontal = true,
      evalLineRhoAtCenter = { theta, rho -> (rho - (width * 0.5f) * sin(theta)) / cos(theta) },
      minCenterCoord = height * 0.03f,
      maxCenterCoord = height * 0.46f,
      maxPeaks = 5,
    )
    val bottomCandidates = extractPeaks(
      acc = hAcc,
      angleCount = angleCount,
      rhoBins = rhoBins,
      rhoOffset = rhoOffset,
      isHorizontal = true,
      evalLineRhoAtCenter = { theta, rho -> (rho - (width * 0.5f) * sin(theta)) / cos(theta) },
      minCenterCoord = height * 0.54f,
      maxCenterCoord = height * 0.97f,
      maxPeaks = 5,
    )
    val leftCandidates = extractPeaks(
      acc = vAcc,
      angleCount = angleCount,
      rhoBins = rhoBins,
      rhoOffset = rhoOffset,
      isHorizontal = false,
      evalLineRhoAtCenter = { phi, rho -> (rho - (height * 0.5f) * sin(phi)) / cos(phi) },
      minCenterCoord = width * 0.03f,
      maxCenterCoord = width * 0.46f,
      maxPeaks = 5,
    )
    val rightCandidates = extractPeaks(
      acc = vAcc,
      angleCount = angleCount,
      rhoBins = rhoBins,
      rhoOffset = rhoOffset,
      isHorizontal = false,
      evalLineRhoAtCenter = { phi, rho -> (rho - (height * 0.5f) * sin(phi)) / cos(phi) },
      minCenterCoord = width * 0.54f,
      maxCenterCoord = width * 0.97f,
      maxPeaks = 5,
    )

    if (topCandidates.isEmpty() || bottomCandidates.isEmpty() ||
      leftCandidates.isEmpty() || rightCandidates.isEmpty()
    ) {
      return null
    }

    // 5. Evaluate combinations to pick the quadrilateral with highest edge coverage + outer contrast
    var bestQuad: NormalizedQuad? = null
    var bestScore = 0f

    for (top in topCandidates) {
      for (bottom in bottomCandidates) {
        if (abs(top.theta - bottom.theta) > 0.36f) continue
        for (left in leftCandidates) {
          for (right in rightCandidates) {
            if (abs(left.theta - right.theta) > 0.36f) continue

            val tl = intersectHV(top, left) ?: continue
            val tr = intersectHV(top, right) ?: continue
            val br = intersectHV(bottom, right) ?: continue
            val bl = intersectHV(bottom, left) ?: continue

            val quad = NormalizedQuad(
              topLeft = NormalizedPoint(tl.x / width, tl.y / height),
              topRight = NormalizedPoint(tr.x / width, tr.y / height),
              bottomRight = NormalizedPoint(br.x / width, br.y / height),
              bottomLeft = NormalizedPoint(bl.x / width, bl.y / height),
            )

            // Ensure corners lie reasonably within frame
            if (quad.points().any { it.x < -0.04f || it.x > 1.04f || it.y < -0.04f || it.y > 1.04f }) {
              continue
            }
            val clamped = NormalizedQuad(
              topLeft = NormalizedPoint(quad.topLeft.x.coerceIn(0f, 1f), quad.topLeft.y.coerceIn(0f, 1f)),
              topRight = NormalizedPoint(quad.topRight.x.coerceIn(0f, 1f), quad.topRight.y.coerceIn(0f, 1f)),
              bottomRight = NormalizedPoint(quad.bottomRight.x.coerceIn(0f, 1f), quad.bottomRight.y.coerceIn(0f, 1f)),
              bottomLeft = NormalizedPoint(quad.bottomLeft.x.coerceIn(0f, 1f), quad.bottomLeft.y.coerceIn(0f, 1f)),
            )

            if (!clamped.isConvexValid(minArea = 0.10f)) continue
            val area = clamped.areaRatio()
            if (area > 0.92f) continue

            // Verify edge support directly along each of the 4 quad sides
            val sTop = measureSegmentEdgeSupport(magArr, width, height, tl, tr, edgeThreshold)
            val sBottom = measureSegmentEdgeSupport(magArr, width, height, bl, br, edgeThreshold)
            val sLeft = measureSegmentEdgeSupport(magArr, width, height, tl, bl, edgeThreshold)
            val sRight = measureSegmentEdgeSupport(magArr, width, height, tr, br, edgeThreshold)

            val minSide = min(min(sTop, sBottom), min(sLeft, sRight))
            val avgSide = (sTop + sBottom + sLeft + sRight) * 0.25f
            if (minSide < 0.16f || avgSide < 0.28f) continue

            // Check luminance contrast between inner quad perimeter vs outside perimeter
            val contrastBonus = measureBoundaryContrast(smooth, width, height, tl, tr, br, bl)

            val totalScore = avgSide * 1.4f + minSide * 0.8f + contrastBonus * 0.5f + area * 0.22f
            if (totalScore > bestScore) {
              bestScore = totalScore
              val conf = (avgSide * 0.7f + minSide * 0.3f + contrastBonus * 0.2f).coerceIn(0f, 1f)
              bestQuad = clamped.copy(confidence = conf)
            }
          }
        }
      }
    }

    return if (bestQuad != null && bestQuad.confidence >= 0.34f) bestQuad else null
  }

  private fun foldAngleAroundZero(angle: Float): Float {
    var a = angle
    while (a > PI / 2) a -= PI.toFloat()
    while (a < -PI / 2) a += PI.toFloat()
    return a
  }

  private fun extractPeaks(
    acc: FloatArray,
    angleCount: Int,
    rhoBins: Int,
    rhoOffset: Int,
    isHorizontal: Boolean,
    evalLineRhoAtCenter: (Float, Float) -> Float,
    minCenterCoord: Float,
    maxCenterCoord: Float,
    maxPeaks: Int,
  ): List<LineCandidate> {
    val all = mutableListOf<LineCandidate>()
    for (a in 0 until angleCount) {
      val theta = ANGLES_RAD[a]
      val row = a * rhoBins
      for (r in 2 until rhoBins - 2) {
        val v = acc[row + r]
        if (v < 6f) continue
        // 3x3 non-max suppression
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
        val centerIntercept = evalLineRhoAtCenter(theta, rho)
        if (centerIntercept in minCenterCoord..maxCenterCoord) {
          all.add(LineCandidate(theta = theta, rho = rho, score = v, isHorizontal = isHorizontal))
        }
      }
    }
    // Keep spatially diverse peaks
    all.sortByDescending { it.score }
    val selected = mutableListOf<LineCandidate>()
    for (cand in all) {
      val tooClose = selected.any {
        abs(it.rho - cand.rho) < 10f && abs(it.theta - cand.theta) < 0.12f
      }
      if (!tooClose) {
        selected.add(cand)
        if (selected.size >= maxPeaks) break
      }
    }
    return selected
  }

  // Horizontal line: x*sin(t) + y*cos(t) = r_h
  // Vertical line:   x*cos(p) + y*sin(p) = r_v
  private fun intersectHV(h: LineCandidate, v: LineCandidate): NormalizedPoint? {
    val a1 = sin(h.theta)
    val b1 = cos(h.theta)
    val c1 = h.rho

    val a2 = cos(v.theta)
    val b2 = sin(v.theta)
    val c2 = v.rho

    val det = a1 * b2 - a2 * b1
    if (abs(det) < 1e-3f) return null
    val x = (c1 * b2 - c2 * b1) / det
    val y = (a1 * c2 - a2 * c1) / det
    return NormalizedPoint(x, y)
  }

  private fun measureSegmentEdgeSupport(
    magArr: FloatArray,
    width: Int,
    height: Int,
    p1: NormalizedPoint,
    p2: NormalizedPoint,
    threshold: Float,
  ): Float {
    val steps = 28
    var hits = 0
    val softThresh = threshold * 0.72f
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
    return (diffSum / 4f / 80f).coerceIn(0f, 1f)
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
