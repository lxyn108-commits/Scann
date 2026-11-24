package com.example.processing

import com.example.domain.model.NormalizedQuad

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
    smoothedQuad = if (delta > 0.16f) {
      stableSinceMs = nowMs
      detected
    } else {
      val lerped = prev.lerpTo(detected, 0.38f)
      if (lerped.isConvexValid(minArea = 0.035f)) lerped else detected
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
