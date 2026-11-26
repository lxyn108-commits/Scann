package com.example.processing

import android.graphics.Bitmap
import com.example.domain.model.EnhancementFilter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Scanner-grade image processor performing percentile bright-paper local illumination & cast
 * normalization (halo-free over dark logos), adaptive paper-white watermark/crease bleaching to
 * #FFFFFF, crisp jet-black text compression, vivid colored ink boost, and 5-tap sharpening.
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
    val whiteCut = (paperWhite - 22f).coerceIn(194f, 232f)
    val colorBlackFloor = inkFloor.toFloat().coerceIn(22f, 58f)
    val textBlackFloor = max(colorBlackFloor + 20f, 68f).coerceIn(58f, whiteCut - 72f)
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

      // After illumination normalization, true paper, fold remnants, & faint security watermarks
      // sit near whiteCut with low-to-moderate chroma, while thin text (<200) and stamps/logos (high chroma) stay intact.
      val lumWeight = smoothstep(whiteCut - 14f, whiteCut + 6f, lum)
      val lowChromaWeight = 1f - smoothstep(26f, 54f, chroma)
      val bleachAlpha = (lumWeight * lowChromaWeight).coerceIn(0f, 1f)

      if (bleachAlpha >= 0.995f) {
        base[i] = 0xFFFFFFFF.toInt()
        continue
      }

      val isAchromatic = chroma < 28f
      val targetLum = if (isAchromatic) {
        val tn = ((lum - textBlackFloor) / textSpan).coerceIn(0f, 1f)
        // Scanner gamma pushes dark/medium receipt ink firmly toward jet-black while preserving antialiasing
        (tn.pow(1.48f) * 255f)
      } else {
        val tc = ((lum - colorBlackFloor) / colorSpan).coerceIn(0f, 1f)
        val s = tc * tc * (3f - 2f * tc)
        lum * 0.14f + s * 255f * 0.86f
      }

      val gain = if (lum > 1f) (targetLum / lum).coerceIn(0.10f, 1.48f) else 1f
      var nr = r * gain
      var ng = g * gain
      var nb = b * gain

      val newLum = (0.299f * nr + 0.587f * ng + 0.114f * nb).coerceIn(0f, 255f)
      val satBoost = when {
        chroma > 32f -> 1.40f
        chroma > 18f -> 1.12f
        else -> 0.85f // eliminate color fringing around black body text
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

    return applyUnsharpMask(base, width, height, strength = 0.46f)
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
    val whiteCut = (paperWhite - 20f).coerceIn(194f, 234f)
    val textBlackFloor = max(inkFloor.toFloat() + 18f, 64f).coerceIn(54f, whiteCut - 72f)
    val span = max(56f, whiteCut - textBlackFloor)

    for (i in 0 until width * height) {
      val lum = lums[i].toFloat()
      val bleachAlpha = smoothstep(whiteCut - 12f, whiteCut + 6f, lum)
      val tn = ((lum - textBlackFloor) / span).coerceIn(0f, 1f)
      val curved = tn.pow(1.42f) * 255f
      val outLum = (curved * (1f - bleachAlpha) + 255f * bleachAlpha)
        .roundToInt()
        .coerceIn(0, 255)
      base[i] = (0xFF shl 24) or (outLum shl 16) or (outLum shl 8) or outLum
    }

    return applyUnsharpMask(base, width, height, strength = 0.42f)
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
    val inkDark = max(72, (paperWhite * 0.56f).roundToInt())
    val bwCeil = max(inkDark + 36, paperWhite - 28)
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
   * Estimates local background paper RGB on an ~84px grid using the BRIGHTEST pixels per cell
   * (immune to dark text/rules dragging the cell mean down and creating halos), followed by 5x5
   * dilation (to bridge bold logos cleanly) and 3x3 smoothing, then normalizes illumination & color cast.
   */
  internal fun removeShadowAndGlareBuffer(srcPixels: IntArray, w: Int, h: Int): IntArray {
    if (w <= 0 || h <= 0 || srcPixels.size < w * h) return srcPixels
    val gridW = min(w, 84).coerceAtLeast(8)
    val gridH = max(8, (gridW * h.toFloat() / max(1, w).toFloat()).roundToInt().coerceAtMost(min(h, 112)))

    val rGrid = IntArray(gridW * gridH)
    val gGrid = IntArray(gridW * gridH)
    val bGrid = IntArray(gridW * gridH)

    for (gy in 0 until gridH) {
      val sy0 = (gy * h) / gridH
      val sy1 = (((gy + 1) * h) / gridH).coerceAtLeast(sy0 + 1).coerceAtMost(h)
      for (gx in 0 until gridW) {
        val sx0 = (gx * w) / gridW
        val sx1 = (((gx + 1) * w) / gridW).coerceAtLeast(sx0 + 1).coerceAtMost(w)

        // First find local max luma in cell, then average top bright pixels (>= maxLum - 18)
        var maxLum = -1
        for (yy in sy0 until sy1) {
          val ro = yy * w
          for (xx in sx0 until sx1) {
            val c = srcPixels[ro + xx]
            val lum = (((c ushr 16) and 0xFF) * 77 + ((c ushr 8) and 0xFF) * 150 + (c and 0xFF) * 29) ushr 8
            if (lum > maxLum) maxLum = lum
          }
        }
        val threshLum = max(0, maxLum - 18)
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
            if (lum >= threshLum) {
              sr += r
              sg += g
              sb += b
              cnt++
            }
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

        val br = (bgR[row0 + gx0] * w00 + bgR[row0 + gx1] * w10 + bgR[row1 + gx0] * w01 + bgR[row1 + gx1] * w11).coerceAtLeast(64f)
        val bg = (bgG[row0 + gx0] * w00 + bgG[row0 + gx1] * w10 + bgG[row1 + gx0] * w01 + bgG[row1 + gx1] * w11).coerceAtLeast(64f)
        val bb = (bgB[row0 + gx0] * w00 + bgB[row0 + gx1] * w10 + bgB[row1 + gx0] * w01 + bgB[row1 + gx1] * w11).coerceAtLeast(64f)

        val bgLum = (0.299f * br + 0.587f * bg + 0.114f * bb).coerceAtLeast(64f)
        val bgChroma = max(br, max(bg, bb)) - min(br, min(bg, bb))

        val castNeutralize = 1f - smoothstep(46f, 80f, bgChroma) * 0.80f
        val minAllowed = max(64f, bgLum * 0.78f)
        val effR = max(minAllowed, br * castNeutralize + bgLum * (1f - castNeutralize))
        val effG = max(minAllowed, bg * castNeutralize + bgLum * (1f - castNeutralize))
        val effB = max(minAllowed, bb * castNeutralize + bgLum * (1f - castNeutralize))

        val gainR = (targetWhite / effR).coerceIn(0.85f, 2.55f)
        val gainG = (targetWhite / effG).coerceIn(0.85f, 2.55f)
        val gainB = (targetWhite / effB).coerceIn(0.85f, 2.55f)

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
    var p78 = 242
    var foundLow = false
    for (v in 0..255) {
      acc += histogram[v]
      if (!foundLow && acc >= (total * 0.04).toInt()) {
        p04 = v.coerceIn(18, 68)
        foundLow = true
      }
      if (acc >= (total * 0.78).toInt()) {
        p78 = v.coerceIn(202, 252)
        break
      }
    }
    return p78 to p04
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
