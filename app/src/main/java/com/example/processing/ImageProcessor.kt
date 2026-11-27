package com.example.processing

import android.graphics.Bitmap
import com.example.domain.model.EnhancementFilter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Scanner-grade image processor producing flatbed-quality scans:
 * 1) Robust percentile illumination normalization with interior dark-photo/logo hole inpainting
 *    so uneven lighting lifts cleanly to white without blowing out embedded photos or creating halos.
 * 2) Edge-preserving bright-pixel despeckling to eliminate ISO/JPEG grain on white paper & boxes
 *    while keeping razor-sharp text strokes and hairline rules intact.
 * 3) Local paper-density embedded-photo segmentation so outdoor/dark photographs retain natural
 *    shadow & midtone detail instead of crushing to #000000, while text/receipts stay jet-black
 *    on spotless #FFFFFF paper with balanced header colors.
 */
class ImageProcessor {

  fun applyEnhancement(
    source: Bitmap,
    filter: EnhancementFilter,
    removeShadowAndGlare: Boolean = true,
  ): Bitmap {
    return when (filter) {
      EnhancementFilter.ORIGINAL -> source.copy(Bitmap.Config.ARGB_8888, false)
      EnhancementFilter.MAGIC_COLOR -> applyMagicColor(source, removeShadowAndGlare)
      EnhancementFilter.CRISP_BW -> applyBlackAndWhite(source, removeShadowAndGlare)
      EnhancementFilter.GRAYSCALE -> applyGrayscale(source, removeShadowAndGlare)
    }
  }

  /**
   * Produces a flatbed-scanner look ("Warna"): pure #FFFFFF paper, bleached faint background
   * watermarks/creases, razor-sharp jet-black text, balanced colored headers, and natural photos.
   */
  fun applyMagicColor(
    source: Bitmap,
    removeShadowAndGlare: Boolean = true,
  ): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val enhanced = enhanceMagicColorBuffer(pixels, width, height, removeShadowAndGlare)
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(enhanced, 0, width, 0, 0, width, height)
    return out
  }

  /**
   * Produces a clean monochrome grayscale scan with pure white background and crisp dark ink.
   */
  fun applyGrayscale(
    source: Bitmap,
    removeShadowAndGlare: Boolean = true,
  ): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val enhanced = enhanceGrayscaleBuffer(pixels, width, height, removeShadowAndGlare)
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(enhanced, 0, width, 0, 0, width, height)
    return out
  }

  /**
   * Produces high-contrast black-and-white scan output where shadows/watermarks disappear
   * cleanly and printed strokes remain crisp without eroding thin lines.
   */
  fun applyBlackAndWhite(
    source: Bitmap,
    removeShadowAndGlare: Boolean = true,
  ): Bitmap {
    val width = source.width
    val height = source.height
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val enhanced = enhanceBlackAndWhiteBuffer(pixels, width, height, removeShadowAndGlare)
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(enhanced, 0, width, 0, 0, width, height)
    return out
  }

  /**
   * Removes uneven shadows, paper creases, ambient lighting color casts, and glare.
   */
  fun removeShadowAndGlare(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)
    val normalized = removeShadowAndGlareBuffer(pixels, w, h)
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(normalized, 0, w, 0, 0, w, h)
    return out
  }

  internal fun enhanceMagicColorBuffer(
    srcPixels: IntArray,
    width: Int,
    height: Int,
    removeShadowAndGlare: Boolean = true,
  ): IntArray {
    if (width <= 0 || height <= 0 || srcPixels.size < width * height) return srcPixels
    val normalized = if (removeShadowAndGlare) {
      removeShadowAndGlareBuffer(srcPixels, width, height)
    } else {
      srcPixels.copyOf()
    }

    // Smooth camera ISO grain on paper & light fills while leaving crisp text edges untouched
    val base = despeckleBrightPaper(normalized, width, height)
    val lums = computeLuminanceArray(base, width, height)
    val photoMask = computePhotoRegionMask(base, lums, width, height)

    val (paperWhite, inkFloor) = estimatePaperAndInkLevels(lums, photoMask)
    val whiteCut = (paperWhite - 24f).coerceIn(196f, 229f)
    val colorBlackFloor = inkFloor.toFloat().coerceIn(14f, 44f)
    val textBlackFloor = max(colorBlackFloor + 6f, 42f).coerceIn(36f, whiteCut - 84f)
    val textSpan = max(56f, whiteCut - textBlackFloor)
    val colorSpan = max(64f, whiteCut - colorBlackFloor)

    for (i in 0 until width * height) {
      val c = base[i]
      val r = ((c ushr 16) and 0xFF).toFloat()
      val g = ((c ushr 8) and 0xFF).toFloat()
      val b = (c and 0xFF).toFloat()

      val lum = lums[i].toFloat()
      val maxC = max(r, max(g, b))
      val minC = min(r, min(g, b))
      val chroma = maxC - minC
      val pWeight = photoMask[i]

      // Bleaching applies strongly to neutral paper & grain; suppressed inside embedded photographs
      val lumWeight = smoothstep(whiteCut - 16f, whiteCut + 3f, lum)
      val lowChromaWeight = 1f - smoothstep(26f, 54f, chroma)
      val bleachAlpha = (lumWeight * lowChromaWeight * (1f - pWeight * 0.94f)).coerceIn(0f, 1f)

      if (bleachAlpha >= 0.995f) {
        base[i] = 0xFFFFFFFF.toInt()
        continue
      }

      // Document text/logo/banner tone curve
      val isAchromatic = chroma < 28f
      val docTargetLum = if (isAchromatic) {
        val tn = ((lum - textBlackFloor) / textSpan).coerceIn(0f, 1f)
        // Firm ink compression on dark/medium receipt strokes (<0.80) while keeping light gray boxes soft (>=0.84)
        val gamma = 1.64f - smoothstep(0.74f, 0.90f, tn) * 0.42f
        (tn.pow(gamma) * 255f)
      } else {
        val tc = ((lum - colorBlackFloor) / colorSpan).coerceIn(0f, 1f)
        val s = tc * tc * (3f - 2f * tc)
        lum * 0.22f + s * 255f * 0.78f
      }

      // Embedded natural photo tone curve: lift crushed shadows cleanly without black clipping
      val photoTargetLum = if (pWeight > 0.01f) {
        val norm = (lum / 255f).coerceIn(0f, 1f)
        val lifted = norm.pow(0.84f) * 255f
        val gentleS = (norm * norm * (3f - 2f * norm)) * 255f
        lifted * 0.78f + gentleS * 0.22f
      } else {
        docTargetLum
      }

      val targetLum = docTargetLum * (1f - pWeight) + photoTargetLum * pWeight
      val maxGain = if (pWeight > 0.3f) 1.55f else 1.42f
      val minGain = if (pWeight > 0.3f) 0.72f else 0.12f
      val gain = if (lum > 1f) (targetLum / lum).coerceIn(minGain, maxGain) else 1f
      var nr = r * gain
      var ng = g * gain
      var nb = b * gain

      val newLum = (0.299f * nr + 0.587f * ng + 0.114f * nb).coerceIn(0f, 255f)
      val docSatBoost = when {
        chroma > 95f && lum > 170f -> 1.10f // Keep bright yellow/pastel banners natural, not neon
        chroma > 32f -> 1.22f
        chroma > 18f -> 1.08f
        else -> 0.84f // Eliminate color fringing around black body text
      }
      val photoSatBoost = 1.12f
      val satBoost = docSatBoost * (1f - pWeight) + photoSatBoost * pWeight

      nr = newLum + (nr - newLum) * satBoost
      ng = newLum + (ng - newLum) * satBoost
      nb = newLum + (nb - newLum) * satBoost

      nr = nr * (1f - bleachAlpha) + 255f * bleachAlpha
      ng = ng * (1f - bleachAlpha) + 255f * bleachAlpha
      nb = nb * (1f - bleachAlpha) + 255f * bleachAlpha

      val ri = nr.roundToInt().coerceIn(0, 255)
      val gi = ng.roundToInt().coerceIn(0, 255)
      val bi = nb.roundToInt().coerceIn(0, 255)
      base[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    return applyUnsharpMask(base, width, height, strength = 0.26f)
  }

  internal fun enhanceGrayscaleBuffer(
    srcPixels: IntArray,
    width: Int,
    height: Int,
    removeShadowAndGlare: Boolean = true,
  ): IntArray {
    if (width <= 0 || height <= 0 || srcPixels.size < width * height) return srcPixels
    val normalized = if (removeShadowAndGlare) {
      removeShadowAndGlareBuffer(srcPixels, width, height)
    } else {
      srcPixels.copyOf()
    }

    val base = despeckleBrightPaper(normalized, width, height)
    val lums = computeLuminanceArray(base, width, height)
    val photoMask = computePhotoRegionMask(base, lums, width, height)
    val (paperWhite, inkFloor) = estimatePaperAndInkLevels(lums, photoMask)
    val whiteCut = (paperWhite - 23f).coerceIn(194f, 229f)
    val textBlackFloor = max(inkFloor.toFloat() + 6f, 40f).coerceIn(34f, whiteCut - 84f)
    val span = max(56f, whiteCut - textBlackFloor)

    for (i in 0 until width * height) {
      val lum = lums[i].toFloat()
      val pWeight = photoMask[i]
      val bleachAlpha = smoothstep(whiteCut - 16f, whiteCut + 3f, lum) * (1f - pWeight * 0.94f)
      val tn = ((lum - textBlackFloor) / span).coerceIn(0f, 1f)
      val gamma = 1.56f - smoothstep(0.74f, 0.90f, tn) * 0.36f
      val docCurved = tn.pow(gamma) * 255f
      val photoCurved = ((lum / 255f).coerceIn(0f, 1f)).pow(0.86f) * 255f
      val curved = docCurved * (1f - pWeight) + photoCurved * pWeight
      val outLum = (curved * (1f - bleachAlpha) + 255f * bleachAlpha)
        .roundToInt()
        .coerceIn(0, 255)
      base[i] = (0xFF shl 24) or (outLum shl 16) or (outLum shl 8) or outLum
    }

    return applyUnsharpMask(base, width, height, strength = 0.24f)
  }

  internal fun enhanceBlackAndWhiteBuffer(
    srcPixels: IntArray,
    width: Int,
    height: Int,
    removeShadowAndGlare: Boolean = true,
  ): IntArray {
    if (width <= 0 || height <= 0 || srcPixels.size < width * height) return srcPixels
    val normalized = if (removeShadowAndGlare) {
      removeShadowAndGlareBuffer(srcPixels, width, height)
    } else {
      srcPixels.copyOf()
    }

    val base = despeckleBrightPaper(normalized, width, height)
    val lums = computeLuminanceArray(base, width, height)
    val (paperWhite, _) = estimatePaperAndInkLevels(lums, null)
    val inkDark = max(72, (paperWhite * 0.56f).roundToInt())
    val bwCeil = max(inkDark + 36, paperWhite - 26)
    val span = max(28, bwCeil - inkDark)

    for (i in base.indices) {
      val lum = lums[i]
      val norm = ((lum - inkDark).toFloat() / span.toFloat()).coerceIn(0f, 1f)
      val curved = norm * norm * (3f - 2f * norm)
      val finalVal = (curved * 255f).roundToInt().coerceIn(0, 255)
      base[i] = (0xFF shl 24) or (finalVal shl 16) or (finalVal shl 8) or finalVal
    }

    return base
  }

  /**
   * Estimates local background paper RGB on an adaptive grid using trimmed upper-percentile
   * sampling (~p72..p84 of non-saturated pixels, immune to both dark text AND hot ISO noise spikes).
   * Interior dark/colorful regions (embedded photos, bold brand logos, colored banners) are marked
   * as non-paper holes and cleanly inpainted from surrounding paper illumination so embedded photos
   * never blow out or crush, while shaded outer paper margins lift smoothly to white.
   */
  internal fun removeShadowAndGlareBuffer(srcPixels: IntArray, w: Int, h: Int): IntArray {
    if (w <= 0 || h <= 0 || srcPixels.size < w * h) return srcPixels
    val gridW = min(w, 64).coerceAtLeast(8)
    val gridH = max(8, (gridW * h.toFloat() / max(1, w).toFloat()).roundToInt().coerceAtMost(min(h, 88)))
    val totalCells = gridW * gridH

    // Quick global bright-paper baseline
    val globalHist = IntArray(256)
    var globalChromaSum = 0L
    var globalChromaCnt = 0
    val sampleStep = if (w * h > 16384) 4 else 1
    for (i in 0 until w * h step sampleStep) {
      val c = srcPixels[i]
      val r = (c ushr 16) and 0xFF
      val g = (c ushr 8) and 0xFF
      val b = c and 0xFF
      val lum = (r * 77 + g * 150 + b * 29) ushr 8
      globalHist[lum]++
      if (lum >= 160) {
        globalChromaSum += max(r, max(g, b)) - min(r, min(g, b))
        globalChromaCnt++
      }
    }
    var globalPaperLum = 220
    var acc = 0
    val targetCount = ((w * h) / sampleStep * 0.80).toInt().coerceAtLeast(1)
    for (v in 0..255) {
      acc += globalHist[v]
      if (acc >= targetCount) {
        globalPaperLum = v.coerceIn(130, 250)
        break
      }
    }
    val globalPaperNeutral = (if (globalChromaCnt > 0) globalChromaSum.toFloat() / globalChromaCnt else 20f) < 34f

    val rGrid = IntArray(totalCells)
    val gGrid = IntArray(totalCells)
    val bGrid = IntArray(totalCells)
    val cellLum = IntArray(totalCells)
    val cellChroma = IntArray(totalCells)
    val validPaper = BooleanArray(totalCells)

    val lumaHist = IntArray(256)
    for (gy in 0 until gridH) {
      val sy0 = (gy * h) / gridH
      val sy1 = (((gy + 1) * h) / gridH).coerceAtLeast(sy0 + 1).coerceAtMost(h)
      for (gx in 0 until gridW) {
        val sx0 = (gx * w) / gridW
        val sx1 = (((gx + 1) * w) / gridW).coerceAtLeast(sx0 + 1).coerceAtMost(w)

        lumaHist.fill(0)
        var cellPixels = 0
        var neutralPixels = 0
        for (yy in sy0 until sy1) {
          val ro = yy * w
          for (xx in sx0 until sx1) {
            val c = srcPixels[ro + xx]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val lum = (r * 77 + g * 150 + b * 29) ushr 8
            val chroma = max(r, max(g, b)) - min(r, min(g, b))
            if (!globalPaperNeutral || chroma <= 48) {
              lumaHist[lum]++
              neutralPixels++
            }
            cellPixels++
          }
        }

        val gi = gy * gridW + gx
        if (neutralPixels < max(1, (cellPixels * 0.16f).roundToInt())) {
          validPaper[gi] = false
          continue
        }

        // Trimmed upper band [p62..p88] tracks true smooth paper brightness,
        // ignoring both black ink strokes and single bright ISO noise speckles!
        val loTarget = (neutralPixels * 0.62f).toInt()
        val hiTarget = (neutralPixels * 0.88f).toInt().coerceAtLeast(loTarget + 1)
        var cAcc = 0
        var pLo = 0
        var pHi = 255
        var gotLo = false
        for (v in 0..255) {
          cAcc += lumaHist[v]
          if (!gotLo && cAcc >= loTarget) {
            pLo = v
            gotLo = true
          }
          if (cAcc >= hiTarget) {
            pHi = v
            break
          }
        }

        var sr = 0L
        var sg = 0L
        var sb = 0L
        var cnt = 0
        for (yy in sy0 until sy1) {
          val ro = yy * w
          for (xx in sx0 until sx1) {
            val c = srcPixels[ro + xx]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            val lum = (r * 77 + g * 150 + b * 29) ushr 8
            val chroma = max(r, max(g, b)) - min(r, min(g, b))
            if (lum in pLo..pHi && (!globalPaperNeutral || chroma <= 48)) {
              sr += r
              sg += g
              sb += b
              cnt++
            }
          }
        }

        val d = max(1, cnt)
        val mr = (sr / d).toInt()
        val mg = (sg / d).toInt()
        val mb = (sb / d).toInt()
        val mLum = (mr * 77 + mg * 150 + mb * 29) ushr 8
        val mChroma = max(mr, max(mg, mb)) - min(mr, min(mg, mb))

        rGrid[gi] = mr
        gGrid[gi] = mg
        bGrid[gi] = mb
        cellLum[gi] = mLum
        cellChroma[gi] = mChroma
        // Exclude very dark photo interiors (< 92) and colored headers/stamps
        validPaper[gi] = mLum >= 92 && (!globalPaperNeutral || mChroma <= 46)
      }
    }

    // Mark interior midtone patches (surrounded on >= 3 sides within ~5 cells by much brighter paper)
    // as non-paper holes (embedded photos) while keeping outer shaded document margins intact!
    val interiorHole = BooleanArray(totalCells)
    val brightThreshold = (globalPaperLum * 0.84f).roundToInt()
    val darkCut = (globalPaperLum * 0.72f).roundToInt()
    val probeRadius = max(3, min(gridW, gridH) / 10)
    for (gy in 0 until gridH) {
      for (gx in 0 until gridW) {
        val gi = gy * gridW + gx
        if (!validPaper[gi] || cellLum[gi] >= darkCut) continue
        var sidesWithBrightPaper = 0
        // Left
        for (dx in 1..probeRadius) {
          val nx = gx - dx
          if (nx < 0) break
          val idx = gy * gridW + nx
          if (validPaper[idx] && cellLum[idx] >= brightThreshold) {
            sidesWithBrightPaper++
            break
          }
        }
        // Right
        for (dx in 1..probeRadius) {
          val nx = gx + dx
          if (nx >= gridW) break
          val idx = gy * gridW + nx
          if (validPaper[idx] && cellLum[idx] >= brightThreshold) {
            sidesWithBrightPaper++
            break
          }
        }
        // Top
        for (dy in 1..probeRadius) {
          val ny = gy - dy
          if (ny < 0) break
          val idx = ny * gridW + gx
          if (validPaper[idx] && cellLum[idx] >= brightThreshold) {
            sidesWithBrightPaper++
            break
          }
        }
        // Bottom
        for (dy in 1..probeRadius) {
          val ny = gy + dy
          if (ny >= gridH) break
          val idx = ny * gridW + gx
          if (validPaper[idx] && cellLum[idx] >= brightThreshold) {
            sidesWithBrightPaper++
            break
          }
        }
        if (sidesWithBrightPaper >= 3) {
          interiorHole[gi] = true
        }
      }
    }
    for (i in 0 until totalCells) {
      if (interiorHole[i]) validPaper[i] = false
    }

    // Fill hole cells (embedded photos, logos, colored banners) smoothly from nearest valid paper cells
    inpaintGridHoles(rGrid, gGrid, bGrid, validPaper, gridW, gridH, globalPaperLum)

    // Gentle 3x3 morphological closing + smoothing removes remaining stroke dips cleanly without halos
    val bgR = smooth3x3Envelope(erode3x3(dilate3x3(rGrid, gridW, gridH), gridW, gridH), gridW, gridH)
    val bgG = smooth3x3Envelope(erode3x3(dilate3x3(gGrid, gridW, gridH), gridW, gridH), gridW, gridH)
    val bgB = smooth3x3Envelope(erode3x3(dilate3x3(bGrid, gridW, gridH), gridW, gridH), gridW, gridH)

    val out = IntArray(w * h)
    val targetWhite = 250f
    val xScale = (gridW - 1).toFloat() / max(1, w - 1).toFloat()
    val yScale = (gridH - 1).toFloat() / max(1, h - 1).toFloat()

    for (y in 0 until h) {
      val fy = (y * yScale).coerceIn(0f, (gridH - 1).toFloat())
      val gy0 = fy.toInt().coerceIn(0, gridH - 1)
      val gy1 = (gy0 + 1).coerceAtMost(gridH - 1)
      val wy = fy - gy0
      val row0 = gy0 * gridW
      val row1 = gy1 * gridW
      val dstRow = y * w

      for (x in 0 until w) {
        val fx = (x * xScale).coerceIn(0f, (gridW - 1).toFloat())
        val gx0 = fx.toInt().coerceIn(0, gridW - 1)
        val gx1 = (gx0 + 1).coerceAtMost(gridW - 1)
        val wx = fx - gx0

        val w00 = (1f - wx) * (1f - wy)
        val w10 = wx * (1f - wy)
        val w01 = (1f - wx) * wy
        val w11 = wx * wy

        val br = (bgR[row0 + gx0] * w00 + bgR[row0 + gx1] * w10 + bgR[row1 + gx0] * w01 + bgR[row1 + gx1] * w11).coerceAtLeast(64f)
        val bg = (bgG[row0 + gx0] * w00 + bgG[row0 + gx1] * w10 + bgG[row1 + gx0] * w01 + bgG[row1 + gx1] * w11).coerceAtLeast(64f)
        val bb = (bgB[row0 + gx0] * w00 + bgB[row0 + gx1] * w10 + bgB[row1 + gx0] * w01 + bgB[row1 + gx1] * w11).coerceAtLeast(64f)

        val bgLum = (0.299f * br + 0.587f * bg + 0.114f * bb).coerceAtLeast(64f)
        val bgChroma = max(br, max(bg, bb)) - min(br, min(bg, bb))

        val castNeutralize = 1f - smoothstep(40f, 75f, bgChroma) * 0.85f
        val minAllowed = max(64f, bgLum * 0.80f)
        val effR = max(minAllowed, br * castNeutralize + bgLum * (1f - castNeutralize))
        val effG = max(minAllowed, bg * castNeutralize + bgLum * (1f - castNeutralize))
        val effB = max(minAllowed, bb * castNeutralize + bgLum * (1f - castNeutralize))

        val gainR = (targetWhite / effR).coerceIn(0.88f, 2.55f)
        val gainG = (targetWhite / effG).coerceIn(0.88f, 2.55f)
        val gainB = (targetWhite / effB).coerceIn(0.88f, 2.55f)

        val c = srcPixels[dstRow + x]
        val r = ((c ushr 16) and 0xFF).toFloat()
        val g = ((c ushr 8) and 0xFF).toFloat()
        val b = (c and 0xFF).toFloat()

        val ri = (r * gainR).roundToInt().coerceIn(0, 255)
        val gi = (g * gainG).roundToInt().coerceIn(0, 255)
        val bi = (b * gainB).roundToInt().coerceIn(0, 255)
        out[dstRow + x] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
      }
    }

    return out
  }

  private fun inpaintGridHoles(
    rGrid: IntArray,
    gGrid: IntArray,
    bGrid: IntArray,
    valid: BooleanArray,
    w: Int,
    h: Int,
    fallbackLum: Int,
  ) {
    val total = w * h
    var hasValid = false
    for (i in 0 until total) {
      if (valid[i]) {
        hasValid = true
        break
      }
    }
    if (!hasValid) {
      val v = fallbackLum.coerceIn(120, 245)
      rGrid.fill(v)
      gGrid.fill(v)
      bGrid.fill(v)
      return
    }

    var curValid = valid.copyOf()
    val maxPasses = max(w, h)
    for (pass in 0 until maxPasses) {
      var changed = false
      val nextValid = curValid.copyOf()
      val nextR = rGrid.copyOf()
      val nextG = gGrid.copyOf()
      val nextB = bGrid.copyOf()
      for (y in 0 until h) {
        val y0 = max(0, y - 1)
        val y1 = min(h - 1, y + 1)
        for (x in 0 until w) {
          val idx = y * w + x
          if (curValid[idx]) continue
          val x0 = max(0, x - 1)
          val x1 = min(w - 1, x + 1)
          var sr = 0
          var sg = 0
          var sb = 0
          var cnt = 0
          for (yy in y0..y1) {
            val ro = yy * w
            for (xx in x0..x1) {
              val nIdx = ro + xx
              if (curValid[nIdx]) {
                sr += rGrid[nIdx]
                sg += gGrid[nIdx]
                sb += bGrid[nIdx]
                cnt++
              }
            }
          }
          if (cnt > 0) {
            nextR[idx] = sr / cnt
            nextG[idx] = sg / cnt
            nextB[idx] = sb / cnt
            nextValid[idx] = true
            changed = true
          }
        }
      }
      nextR.copyInto(rGrid)
      nextG.copyInto(gGrid)
      nextB.copyInto(bGrid)
      curValid = nextValid
      if (!changed) break
    }

    // Extra Jacobi smoothing inside originally-hole cells for seamless illumination transition
    repeat(4) {
      val nextR = rGrid.copyOf()
      val nextG = gGrid.copyOf()
      val nextB = bGrid.copyOf()
      for (y in 1 until h - 1) {
        val ro = y * w
        for (x in 1 until w - 1) {
          val idx = ro + x
          if (valid[idx]) continue
          nextR[idx] = (rGrid[idx - 1] + rGrid[idx + 1] + rGrid[idx - w] + rGrid[idx + w]) ushr 2
          nextG[idx] = (gGrid[idx - 1] + gGrid[idx + 1] + gGrid[idx - w] + gGrid[idx + w]) ushr 2
          nextB[idx] = (bGrid[idx - 1] + bGrid[idx + 1] + bGrid[idx - w] + bGrid[idx + w]) ushr 2
        }
      }
      nextR.copyInto(rGrid)
      nextG.copyInto(gGrid)
      nextB.copyInto(bGrid)
    }
  }

  /**
   * Edge-preserving selective 3x3 smoothing for bright paper, creases, and light fills (>= 145 lum):
   * averages neighboring pixels within +-28 luma and +-24 chroma so ISO sensor noise & mottled paper
   * grain become silky-uniform while black text edges & thin rules remain untouched.
   */
  private fun despeckleBrightPaper(pixels: IntArray, w: Int, h: Int): IntArray {
    if (w < 3 || h < 3) return pixels
    val out = pixels.copyOf()
    val lumArr = IntArray(w * h)
    val chrArr = IntArray(w * h)
    for (i in 0 until w * h) {
      val c = pixels[i]
      val r = (c ushr 16) and 0xFF
      val g = (c ushr 8) and 0xFF
      val b = c and 0xFF
      lumArr[i] = (r * 77 + g * 150 + b * 29) ushr 8
      chrArr[i] = max(r, max(g, b)) - min(r, min(g, b))
    }

    for (y in 1 until h - 1) {
      val ym1 = (y - 1) * w
      val y0 = y * w
      val yp1 = (y + 1) * w
      for (x in 1 until w - 1) {
        val idx = y0 + x
        val lc = lumArr[idx]
        if (lc < 168) continue
        val cc = chrArr[idx]

        var sr = 0
        var sg = 0
        var sb = 0
        var cnt = 0
        for (yy in 0..2) {
          val rowOff = when (yy) {
            0 -> ym1
            1 -> y0
            else -> yp1
          }
          for (dx in -1..1) {
            val nIdx = rowOff + x + dx
            if (abs(lumArr[nIdx] - lc) <= 28 && abs(chrArr[nIdx] - cc) <= 24) {
              val px = pixels[nIdx]
              sr += (px ushr 16) and 0xFF
              sg += (px ushr 8) and 0xFF
              sb += px and 0xFF
              cnt++
            }
          }
        }
        if (cnt >= 3) {
          val ri = sr / cnt
          val gi = sg / cnt
          val bi = sb / cnt
          out[idx] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
      }
    }
    return out
  }

  /**
   * Identifies embedded photo/graphic regions (`0f..1f`) on the page by measuring local neutral
   * white-paper fraction (`lum >= 198 && chroma <= 38`) over coarse blocks. Printed text lines &
   * tables always sit inside abundant surrounding white paper (`paperFrac >> 0.35`), whereas interior
   * 2D blocks of outdoor/indoor photographs have almost zero white paper (`paperFrac < 0.14`).
   */
  private fun computePhotoRegionMask(
    pixels: IntArray,
    lums: IntArray,
    w: Int,
    h: Int,
  ): FloatArray {
    val mask = FloatArray(w * h)
    if (w < 24 || h < 24) return mask

    val blockSize = (min(w, h) / 20).coerceIn(5, 32)
    val bw = max(3, (w + blockSize - 1) / blockSize)
    val bh = max(3, (h + blockSize - 1) / blockSize)
    val paperFrac = FloatArray(bw * bh)

    for (by in 0 until bh) {
      val py0 = by * blockSize
      val py1 = min(h, (by + 1) * blockSize)
      for (bx in 0 until bw) {
        val px0 = bx * blockSize
        val px1 = min(w, (bx + 1) * blockSize)
        var paperCnt = 0
        var total = 0
        for (yy in py0 until py1) {
          val ro = yy * w
          for (xx in px0 until px1) {
            val idx = ro + xx
            val lum = lums[idx]
            if (lum >= 198) {
              val c = pixels[idx]
              val r = (c ushr 16) and 0xFF
              val g = (c ushr 8) and 0xFF
              val b = c and 0xFF
              val chroma = max(r, max(g, b)) - min(r, min(g, b))
              if (chroma <= 38) paperCnt++
            }
            total++
          }
        }
        paperFrac[by * bw + bx] = paperCnt.toFloat() / max(1, total).toFloat()
      }
    }

    // A block belongs to a continuous photo only if part of a >= 2x2 low-paper block group
    // (ignores 1-block-tall bold titles, table bars, or individual letters)
    val rawPhoto = BooleanArray(bw * bh) { paperFrac[it] < 0.16f }
    val corePhoto = FloatArray(bw * bh)
    for (by in 0 until bh - 1) {
      for (bx in 0 until bw - 1) {
        val i00 = by * bw + bx
        val i10 = i00 + 1
        val i01 = i00 + bw
        val i11 = i01 + 1
        if (rawPhoto[i00] && rawPhoto[i10] && rawPhoto[i01] && rawPhoto[i11]) {
          corePhoto[i00] = 1f
          corePhoto[i10] = 1f
          corePhoto[i01] = 1f
          corePhoto[i11] = 1f
        }
      }
    }

    // Gently dilate 1 block (to cover high-contrast rim of photo boxes) and smooth for zero seam
    val dilated = FloatArray(bw * bh)
    for (by in 0 until bh) {
      val y0 = max(0, by - 1)
      val y1 = min(bh - 1, by + 1)
      for (bx in 0 until bw) {
        val x0 = max(0, bx - 1)
        val x1 = min(bw - 1, bx + 1)
        var maxV = 0f
        var sumV = 0f
        var cnt = 0
        for (yy in y0..y1) {
          val ro = yy * bw
          for (xx in x0..x1) {
            val v = corePhoto[ro + xx]
            if (v > maxV) maxV = v
            sumV += v
            cnt++
          }
        }
        val self = corePhoto[by * bw + bx]
        dilated[by * bw + bx] = if (self > 0.5f) {
          1f
        } else {
          // Rim block: blend only if strongly adjacent to photo interior AND itself low in white paper
          val adj = sumV / max(1, cnt)
          (maxV * adj * 1.4f * (1f - smoothstep(0.15f, 0.55f, paperFrac[by * bw + bx]))).coerceIn(0f, 1f)
        }
      }
    }

    // Bilinear interpolation to full resolution
    for (y in 0 until h) {
      val fy = ((y + 0.5f) / blockSize - 0.5f).coerceIn(0f, (bh - 1).toFloat())
      val by0 = fy.toInt().coerceIn(0, bh - 1)
      val by1 = (by0 + 1).coerceAtMost(bh - 1)
      val wy = fy - by0
      val r0 = by0 * bw
      val r1 = by1 * bw
      val dstRow = y * w
      for (x in 0 until w) {
        val fx = ((x + 0.5f) / blockSize - 0.5f).coerceIn(0f, (bw - 1).toFloat())
        val bx0 = fx.toInt().coerceIn(0, bw - 1)
        val bx1 = (bx0 + 1).coerceAtMost(bw - 1)
        val wx = fx - bx0
        val v = dilated[r0 + bx0] * (1f - wx) * (1f - wy) +
          dilated[r0 + bx1] * wx * (1f - wy) +
          dilated[r1 + bx0] * (1f - wx) * wy +
          dilated[r1 + bx1] * wx * wy
        mask[dstRow + x] = v.coerceIn(0f, 1f)
      }
    }
    return mask
  }

  private fun computeLuminanceArray(pixels: IntArray, w: Int, h: Int): IntArray {
    val lums = IntArray(w * h)
    for (i in 0 until w * h) {
      val c = pixels[i]
      lums[i] = (((c ushr 16) and 0xFF) * 77 + ((c ushr 8) and 0xFF) * 150 + (c and 0xFF) * 29) ushr 8
    }
    return lums
  }

  private fun dilate3x3(src: IntArray, w: Int, h: Int): IntArray {
    val out = IntArray(w * h)
    for (y in 0 until h) {
      val y0 = max(0, y - 1)
      val y1 = min(h - 1, y + 1)
      for (x in 0 until w) {
        val x0 = max(0, x - 1)
        val x1 = min(w - 1, x + 1)
        var maxV = 0
        for (yy in y0..y1) {
          val ro = yy * w
          for (xx in x0..x1) {
            val v = src[ro + xx]
            if (v > maxV) maxV = v
          }
        }
        out[y * w + x] = maxV
      }
    }
    return out
  }

  private fun erode3x3(src: IntArray, w: Int, h: Int): IntArray {
    val out = IntArray(w * h)
    for (y in 0 until h) {
      val y0 = max(0, y - 1)
      val y1 = min(h - 1, y + 1)
      for (x in 0 until w) {
        val x0 = max(0, x - 1)
        val x1 = min(w - 1, x + 1)
        var minV = 255
        for (yy in y0..y1) {
          val ro = yy * w
          for (xx in x0..x1) {
            val v = src[ro + xx]
            if (v < minV) minV = v
          }
        }
        out[y * w + x] = minV
      }
    }
    return out
  }

  private fun smooth3x3Envelope(src: IntArray, w: Int, h: Int): IntArray {
    var cur = src
    repeat(2) {
      val next = IntArray(w * h)
      for (y in 0 until h) {
        val y0 = max(0, y - 1)
        val y1 = min(h - 1, y + 1)
        for (x in 0 until w) {
          val x0 = max(0, x - 1)
          val x1 = min(w - 1, x + 1)
          var sum = 0
          var cnt = 0
          for (yy in y0..y1) {
            val ro = yy * w
            for (xx in x0..x1) {
              sum += cur[ro + xx]
              cnt++
            }
          }
          next[y * w + x] = sum / max(1, cnt)
        }
      }
      cur = next
    }
    return cur
  }

  private fun estimatePaperAndInkLevels(lums: IntArray, photoMask: FloatArray?): Pair<Int, Int> {
    val histogram = IntArray(256)
    var sampled = 0
    val step = if (lums.size > 4096) 4 else 1
    for (i in lums.indices step step) {
      if (photoMask != null && photoMask[i] > 0.35f) continue
      histogram[lums[i].coerceIn(0, 255)]++
      sampled++
    }
    if (sampled < 32) {
      histogram.fill(0)
      sampled = 0
      for (i in lums.indices step step) {
        histogram[lums[i].coerceIn(0, 255)]++
        sampled++
      }
    }
    val total = max(1, sampled)
    var acc = 0
    var p04 = 32
    var p80 = 246
    var foundLow = false
    for (v in 0..255) {
      acc += histogram[v]
      if (!foundLow && acc >= (total * 0.04).toInt()) {
        p04 = v.coerceIn(14, 58)
        foundLow = true
      }
      if (acc >= (total * 0.80).toInt()) {
        p80 = v.coerceIn(206, 253)
        break
      }
    }
    return p80 to p04
  }

  /**
   * Noise-gated, overshoot-clamped luminance unsharp mask: sharpens crisp ink & logo strokes
   * while ignoring flat paper/banner texture and preventing white/dark ringing halos.
   */
  private fun applyUnsharpMask(
    pixels: IntArray,
    width: Int,
    height: Int,
    strength: Float,
  ): IntArray {
    if (width < 3 || height < 3) return pixels
    val out = pixels.copyOf()
    for (y in 1 until height - 1) {
      val ym1 = (y - 1) * width
      val y0 = y * width
      val yp1 = (y + 1) * width
      for (x in 1 until width - 1) {
        val c = pixels[y0 + x]
        if (c == 0xFFFFFFFF.toInt()) continue
        val r = (c ushr 16) and 0xFF
        val g = (c ushr 8) and 0xFF
        val b = c and 0xFF

        val lumC = (r * 77 + g * 150 + b * 29) ushr 8
        val n1 = pixels[ym1 + x]
        val n2 = pixels[yp1 + x]
        val n3 = pixels[y0 + x - 1]
        val n4 = pixels[y0 + x + 1]

        fun luma(px: Int) = (((px ushr 16) and 0xFF) * 77 + ((px ushr 8) and 0xFF) * 150 + (px and 0xFF) * 29) ushr 8
        val l1 = luma(n1)
        val l2 = luma(n2)
        val l3 = luma(n3)
        val l4 = luma(n4)
        val avgLum = (l1 + l2 + l3 + l4 + lumC) / 5f
        val detail = lumC - avgLum

        // Strong coring on bright areas suppresses residual grain on yellow banners & gray boxes
        val gate = if (lumC > 185) 16f else 11f
        if (abs(detail) < gate) continue

        val minN = min(min(l1, l2), min(l3, l4))
        val maxN = max(max(l1, l2), max(l3, l4))
        val targetLum = (lumC + detail * strength).coerceIn(max(0f, minN - 4f), min(255f, maxN + 4f))
        val deltaLum = targetLum - lumC

        val nr = (r + deltaLum).roundToInt().coerceIn(0, 255)
        val gi = (g + deltaLum).roundToInt().coerceIn(0, 255)
        val bi = (b + deltaLum).roundToInt().coerceIn(0, 255)
        out[y0 + x] = (0xFF shl 24) or (nr shl 16) or (gi shl 8) or bi
      }
    }
    return out
  }

  private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }
}
