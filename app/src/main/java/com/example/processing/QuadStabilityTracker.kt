package com.example.processing

import com.example.domain.model.NormalizedPoint
import com.example.domain.model.NormalizedQuad
import kotlin.math.hypot

/**
 * Tracks consecutive detected quads with candidate hysteresis, micro-jitter deadband,
 * and low-pass smoothing so the viewfinder overlay locks firmly ("paten") onto the document
 * without jumping on noisy frames.
 */
class QuadStabilityTracker(
  private val stabilityWindowMs: Long = 1050L,
  private val autoCaptureCooldownMs: Long = 2600L,
  private val maxCornerDrift: Float = 0.028f,
  private val switchConfirmationFrames: Int = 4,
  private val acquireConfirmationFrames: Int = 1,
  private val jitterDeadband: Float = 0.014f,
) {
  var smoothedQuad: NormalizedQuad? = null
    private set
  var stabilityProgress: Float = 0f
    private set

  private var missedFrames = 0
  private var stableSinceMs = 0L
  private var lastAutoCaptureMs = -1L

  private var pendingQuad: NormalizedQuad? = null
  private var pendingFrames = 0

  fun update(detected: NormalizedQuad?, nowMs: Long): Boolean {
    if (detected == null) {
      missedFrames++
      pendingQuad = null
      pendingFrames = 0
      if (missedFrames > 7) {
        smoothedQuad = null
        stableSinceMs = 0L
        stabilityProgress = 0f
      } else {
        stabilityProgress = (stabilityProgress - 0.14f).coerceAtLeast(0f)
      }
      return false
    }

    missedFrames = 0
    val prev = smoothedQuad
    if (prev == null) {
      val cand = pendingQuad
      if (cand != null && cand.maxCornerDelta(detected) <= 0.11f) {
        pendingFrames++
        pendingQuad = cand.lerpTo(detected, 0.5f)
      } else {
        pendingQuad = detected
        pendingFrames = 1
      }
      if (pendingFrames >= acquireConfirmationFrames) {
        smoothedQuad = detected
        pendingQuad = null
        pendingFrames = 0
        stableSinceMs = nowMs
        stabilityProgress = 0.08f
      }
      return false
    }

    val delta = prev.maxCornerDelta(detected)
    if (delta > 0.15f) {
      // Hysteresis: do not yank the lock on 1-3 transient outlier detections
      val cand = pendingQuad
      if (cand != null && cand.maxCornerDelta(detected) <= 0.10f) {
        pendingFrames++
        pendingQuad = cand.lerpTo(detected, 0.5f)
      } else {
        pendingQuad = detected
        pendingFrames = 1
      }

      if (pendingFrames >= switchConfirmationFrames) {
        smoothedQuad = detected
        pendingQuad = null
        pendingFrames = 0
        stableSinceMs = nowMs
        stabilityProgress = 0.06f
      } else {
        stabilityProgress = (stabilityProgress - 0.10f).coerceAtLeast(0f)
      }
      return false
    }

    // Same document tracking
    pendingQuad = null
    pendingFrames = 0

    if (delta <= jitterDeadband) {
      // Deadband freeze: keep corners completely steady against hand micro-tremor
      if (stableSinceMs == 0L) stableSinceMs = nowMs
    } else {
      val blended = blendWithDeadband(prev, detected, alpha = 0.26f, cornerDeadband = 0.008f)
      smoothedQuad = if (blended.isConvexValid(minArea = 0.035f)) blended else prev
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
      stabilityProgress = 0.10f
    }
    return false
  }

  private fun blendWithDeadband(
    from: NormalizedQuad,
    to: NormalizedQuad,
    alpha: Float,
    cornerDeadband: Float,
  ): NormalizedQuad {
    fun blendPt(a: NormalizedPoint, b: NormalizedPoint): NormalizedPoint {
      val d = hypot(b.x - a.x, b.y - a.y)
      return if (d <= cornerDeadband) a else NormalizedPoint(
        x = a.x + (b.x - a.x) * alpha,
        y = a.y + (b.y - a.y) * alpha,
      )
    }
    return NormalizedQuad(
      topLeft = blendPt(from.topLeft, to.topLeft),
      topRight = blendPt(from.topRight, to.topRight),
      bottomRight = blendPt(from.bottomRight, to.bottomRight),
      bottomLeft = blendPt(from.bottomLeft, to.bottomLeft),
      confidence = from.confidence + (to.confidence - from.confidence) * alpha,
    )
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
    pendingQuad = null
    pendingFrames = 0
  }
}
