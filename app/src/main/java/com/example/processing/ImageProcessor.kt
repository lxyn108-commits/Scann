package com.example.processing

import android.graphics.Bitmap
import com.example.domain.model.EnhancementFilter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scanner-grade image processor performing tight local RGB illumination/cast normalization,
 * ink-strength watermark & crease bleaching to spotless #FFFFFF, jet-black text compression,
 * chromatic logo vibrancy boost, 5-tap unsharp sharpening, and 'magic color', 'black and white',
 * and 'grayscale' enhancements.
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
   * watermarks/creases, razor-sharp jet-black text, and vivid colored logos/stamps.
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
    val base = if (removeShadowAndGlare) {
      removeShadowAndGlareBuffer(srcPixels, width, height)
    } else {
      srcPixels.copyOf()
    }

    val lums = computeLuminanceArray(base, width, height)
    val (paperWhite, inkFloor) = estimatePaperAndInkLevels(lums)
    val whitePoint = (paperWhite - 16f).coerceIn(186f, 234f)
    val blackFloor = inkFloor.toFloat().coerceIn(32f, 82f)
    val colorSpan = max(52f, whitePoint - blackFloor)
    val textBlackFloor = max(blackFloor, 46f).coerceAtMost(whitePoint - 56f)

    for (y in 0 until height) {
      val rowOff = y * width
      val upOff = max(0, y - 1) * width
      val downOff = min(height - 1, y + 1) * width

      for (x in 0 until width) {
        val i = rowOff + x
        val c = base[i]
        val r = ((c ushr 16) and 0xFF).toFloat()
        val g = ((c ushr 8) and 0xFF).toFloat()
        val b = (c and 0xFF).toFloat()

        val lum = lums[i].toFloat()
        val minN = min(
          lums[i],
          min(
            min(lums[upOff + x], lums[downOff + x]),
            min(lums[rowOff + max(0, x - 1)], lums[rowOff + min(width - 1, x + 1)]),
          ),
        )

        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val chroma = maxC - minC

        // Ink strength separates faint security watermarks & creases from stamps & crisp text
        val inkStrength = (255f - lum) + chroma * 1.35f
        val watermarkBleach = if (lum >= 178f && minN >= 148) {
          1f - smoothstep(68f, 126f, inkStrength)
        } else {
          0f
        }
        val paperBrightWeight = smoothstep(whitePoint - 28f, whitePoint + 6f, lum)
        val lowChromaWeight = 1f - smoothstep(18f, 52f, chroma)
        val bleachAlpha = max(watermarkBleach, paperBrightWeight * lowChromaWeight).coerceIn(0f, 1f)

        if (bleachAlpha >= 0.995f) {
          base[i] = 0xFFFFFFFF.toInt()
          continue
        }

        // Neutral dark ink compresses firmly to jet black; colored logos stay balanced & rich
        val isAchromaticInk = chroma < 30f
        val targetLum = if (isAchromaticInk) {
          val tn = ((lum - textBlackFloor) / max(44f, whitePoint - textBlackFloor)).coerceIn(0f, 1f)
          // Double S-curve anchors dark gray receipt ink cleanly to #000000 without eroding strokes
          val s = tn * tn * (3f - 2f * tn)
          (s * s * 0.35f + s * 0.65f) * 255f
        } else {
          val tc = ((lum - blackFloor) / colorSpan).coerceIn(0f, 1f)
          val s = tc * tc * (3f - 2f * tc)
          lum * 0.14f + s * 255f * 0.86f
        }

        val gain = if (lum > 1f) (targetLum / lum).coerceIn(0.15f, 1.48f) else 1f
        var nr = r * gain
        var ng = g * gain
        var nb = b * gain

        val newLum = (0.299f * nr + 0.587f * ng + 0.114f * nb).coerceIn(0f, 255f)
        val satBoost = when {
          chroma > 34f -> 1.42f
          chroma > 18f -> 1.15f
          else -> 0.92f // suppress slight color fringe around black body text
        }
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
    }

    return applyUnsharpMask(base, width, height, strength = 0.45f)
  }

  internal fun enhanceGrayscaleBuffer(
    srcPixels: IntArray,
    width: Int,
    height: Int,
    removeShadowAndGlare: Boolean = true,
  ): IntArray {
    if (width <= 0 || height <= 0 || srcPixels.size < width * height) return srcPixels
    val base = if (removeShadowAndGlare) {
      removeShadowAndGlareBuffer(srcPixels, width, height)
    } else {
      srcPixels.copyOf()
    }

    val lums = computeLuminanceArray(base, width, height)
    val (paperWhite, inkFloor) = estimatePaperAndInkLevels(lums)
    val whitePoint = (paperWhite - 14f).coerceIn(186f, 236f)
    val blackFloor = inkFloor.toFloat().coerceIn(32f, 82f)
    val span = max(48f, whitePoint - blackFloor)

    for (y in 0 until height) {
      val rowOff = y * width
      val upOff = max(0, y - 1) * width
      val downOff = min(height - 1, y + 1) * width
      for (x in 0 until width) {
        val i = rowOff + x
        val lum = lums[i].toFloat()
        val minN = min(
          lums[i],
          min(
            min(lums[upOff + x], lums[downOff + x]),
            min(lums[rowOff + max(0, x - 1)], lums[rowOff + min(width - 1, x + 1)]),
          ),
        )

        val watermarkBleach = if (lum >= 182f && minN >= 148) {
          smoothstep(182f, whitePoint, lum)
        } else {
          0f
        }
        val bleachAlpha = max(watermarkBleach, smoothstep(whitePoint - 26f, whitePoint + 4f, lum))
        val t = ((lum - blackFloor) / span).coerceIn(0f, 1f)
        val curved = t * t * (3f - 2f * t) * 255f
        val outLum = (curved * (1f - bleachAlpha) + 255f * bleachAlpha)
          .roundToInt()
          .coerceIn(0, 255)
        base[i] = (0xFF shl 24) or (outLum shl 16) or (outLum shl 8) or outLum
      }
    }

    return applyUnsharpMask(base, width, height, strength = 0.40f)
  }

  internal fun enhanceBlackAndWhiteBuffer(
    srcPixels: IntArray,
    width: Int,
    height: Int,
    removeShadowAndGlare: Boolean = true,
  ): IntArray {
    if (width <= 0 || height <= 0 || srcPixels.size < width * height) return srcPixels
    val base = if (removeShadowAndGlare) {
      removeShadowAndGlareBuffer(srcPixels, width, height)
    } else {
      srcPixels.copyOf()
    }

    val lums = computeLuminanceArray(base, width, height)
    val (paperWhite, _) = estimatePaperAndInkLevels(lums)
    val inkDark = max(62, (paperWhite * 0.56f).roundToInt())
    val bwCeil = max(inkDark + 38, paperWhite - 28)
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
   * Estimates fine local RGB background envelope on a ~128px grid (tight enough to hug narrow
   * paper folds and creases while bridging printed text via 5x5 dilation + 2-pass 3x3 smoothing),
   * then bilinearly normalizes each pixel to remove shadows, ambient tint, and glare.
   */
  internal fun removeShadowAndGlareBuffer(srcPixels: IntArray, w: Int, h: Int): IntArray {
    if (w <= 0 || h <= 0 || srcPixels.size < w * h) return srcPixels
    val gridW = min(w, 128).coerceAtLeast(8)
    val gridH = max(8, (gridW * h.toFloat() / max(1, w).toFloat()).roundToInt().coerceAtMost(min(h, 176)))

    val rGrid = IntArray(gridW * gridH)
    val gGrid = IntArray(gridW * gridH)
    val bGrid = IntArray(gridW * gridH)

    for (gy in 0 until gridH) {
      val sy0 = (gy * h) / gridH
      val sy1 = (((gy + 1) * h) / gridH).coerceAtLeast(sy0 + 1).coerceAtMost(h)
      for (gx in 0 until gridW) {
        val sx0 = (gx * w) / gridW
        val sx1 = (((gx + 1) * w) / gridW).coerceAtLeast(sx0 + 1).coerceAtMost(w)
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var cnt = 0
        for (yy in sy0 until sy1) {
          val ro = yy * w
          for (xx in sx0 until sx1) {
            val c = srcPixels[ro + xx]
            sr += (c ushr 16) and 0xFF
            sg += (c ushr 8) and 0xFF
            sb += c and 0xFF
            cnt++
          }
        }
        val gi = gy * gridW + gx
        val d = max(1, cnt)
        rGrid[gi] = (sr / d).toInt()
        gGrid[gi] = (sg / d).toInt()
        bGrid[gi] = (sb / d).toInt()
      }
    }

    val bgR = smooth3x3Envelope(dilate5x5(rGrid, gridW, gridH), gridW, gridH)
    val bgG = smooth3x3Envelope(dilate5x5(gGrid, gridW, gridH), gridW, gridH)
    val bgB = smooth3x3Envelope(dilate5x5(bGrid, gridW, gridH), gridW, gridH)

    val out = IntArray(w * h)
    val targetWhite = 252f
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

        val br = (bgR[row0 + gx0] * w00 + bgR[row0 + gx1] * w10 + bgR[row1 + gx0] * w01 + bgR[row1 + gx1] * w11).coerceAtLeast(56f)
        val bg = (bgG[row0 + gx0] * w00 + bgG[row0 + gx1] * w10 + bgG[row1 + gx0] * w01 + bgG[row1 + gx1] * w11).coerceAtLeast(56f)
        val bb = (bgB[row0 + gx0] * w00 + bgB[row0 + gx1] * w10 + bgB[row1 + gx0] * w01 + bgB[row1 + gx1] * w11).coerceAtLeast(56f)

        val bgLum = (0.299f * br + 0.587f * bg + 0.114f * bb).coerceAtLeast(56f)
        val bgChroma = max(br, max(bg, bb)) - min(br, min(bg, bb))

        // Neutralize ambient lighting casts (pink/warm/cool tints) on paper while keeping saturated cards intact
        val castNeutralize = 1f - smoothstep(48f, 82f, bgChroma) * 0.80f
        val minAllowed = max(56f, bgLum * 0.72f)
        val effR = max(minAllowed, br * castNeutralize + bgLum * (1f - castNeutralize))
        val effG = max(minAllowed, bg * castNeutralize + bgLum * (1f - castNeutralize))
        val effB = max(minAllowed, bb * castNeutralize + bgLum * (1f - castNeutralize))

        val gainR = (targetWhite / effR).coerceIn(0.84f, 2.55f)
        val gainG = (targetWhite / effG).coerceIn(0.84f, 2.55f)
        val gainB = (targetWhite / effB).coerceIn(0.84f, 2.55f)

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

  private fun computeLuminanceArray(pixels: IntArray, w: Int, h: Int): IntArray {
    val lums = IntArray(w * h)
    for (i in 0 until w * h) {
      val c = pixels[i]
      lums[i] = (((c ushr 16) and 0xFF) * 77 + ((c ushr 8) and 0xFF) * 150 + (c and 0xFF) * 29) ushr 8
    }
    return lums
  }

  private fun dilate5x5(src: IntArray, w: Int, h: Int): IntArray {
    val out = IntArray(w * h)
    for (y in 0 until h) {
      val y0 = max(0, y - 2)
      val y1 = min(h - 1, y + 2)
      for (x in 0 until w) {
        val x0 = max(0, x - 2)
        val x1 = min(w - 1, x + 2)
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

  private fun estimatePaperAndInkLevels(lums: IntArray): Pair<Int, Int> {
    val histogram = IntArray(256)
    var sampled = 0
    val step = if (lums.size > 4096) 4 else 1
    for (i in lums.indices step step) {
      histogram[lums[i].coerceIn(0, 255)]++
      sampled++
    }
    val total = max(1, sampled)
    var acc = 0
    var p04 = 36
    var p70 = 236
    var foundLow = false
    for (v in 0..255) {
      acc += histogram[v]
      if (!foundLow && acc >= (total * 0.04).toInt()) {
        p04 = v.coerceIn(18, 68)
        foundLow = true
      }
      if (acc >= (total * 0.70).toInt()) {
        p70 = v.coerceIn(195, 248)
        break
      }
    }
    return p70 to p04
  }

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

        val n1 = pixels[ym1 + x]
        val n2 = pixels[yp1 + x]
        val n3 = pixels[y0 + x - 1]
        val n4 = pixels[y0 + x + 1]

        val avgR = (((n1 ushr 16) and 0xFF) + ((n2 ushr 16) and 0xFF) + ((n3 ushr 16) and 0xFF) + ((n4 ushr 16) and 0xFF) + r) / 5f
        val avgG = (((n1 ushr 8) and 0xFF) + ((n2 ushr 8) and 0xFF) + ((n3 ushr 8) and 0xFF) + ((n4 ushr 8) and 0xFF) + g) / 5f
        val avgB = ((n1 and 0xFF) + (n2 and 0xFF) + (n3 and 0xFF) + (n4 and 0xFF) + b) / 5f

        val nr = (r + (r - avgR) * strength).roundToInt().coerceIn(0, 255)
        val gi = (g + (g - avgG) * strength).roundToInt().coerceIn(0, 255)
        val bi = (b + (b - avgB) * strength).roundToInt().coerceIn(0, 255)
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
