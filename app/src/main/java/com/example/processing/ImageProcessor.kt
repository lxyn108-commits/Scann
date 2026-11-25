package com.example.processing

import android.graphics.Bitmap
import com.example.domain.model.EnhancementFilter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scanner-grade image processor performing local RGB illumination/cast normalization,
 * adaptive white-paper watermark bleaching, black-point ink compression, 3x3 unsharp sharpening,
 * and 'magic color', 'black and white', and 'grayscale' document enhancements.
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
   * Produces a flatbed-scanner look ("Warna"): pure white paper, bleached faint background
   * watermarks/stains, razor-sharp deep black text, and vivid colored logos/stamps.
   */
  fun applyMagicColor(
    source: Bitmap,
    removeShadowAndGlare: Boolean = true,
  ): Bitmap {
    val base = if (removeShadowAndGlare) removeShadowAndGlare(source) else source
    val width = base.width
    val height = base.height
    val pixels = IntArray(width * height)
    base.getPixels(pixels, 0, width, 0, 0, width, height)
    if (base !== source) base.recycle()

    // Estimate dynamic paper white & ink black floor from normalized buffer
    val (paperWhite, inkFloor) = estimatePaperAndInkLevels(pixels)
    val span = max(48f, (paperWhite - inkFloor).toFloat())

    for (i in pixels.indices) {
      val c = pixels[i]
      val r = ((c ushr 16) and 0xFF).toFloat()
      val g = ((c ushr 8) and 0xFF).toFloat()
      val b = (c and 0xFF).toFloat()

      val lum = 0.299f * r + 0.587f * g + 0.114f * b
      val maxC = max(r, max(g, b))
      val minC = min(r, min(g, b))
      val chroma = maxC - minC

      // 1. Bleach faint off-white watermarks / low-saturation stains smoothly to pure white
      val paperBrightWeight = smoothstep(paperWhite - 46f, paperWhite - 10f, lum)
      val lowChromaWeight = 1f - smoothstep(18f, 54f, chroma)
      val bleachAlpha = paperBrightWeight * lowChromaWeight

      // 2. Contrast tone curve anchoring ink cleanly to black and mid-tones crisp
      val t = ((lum - inkFloor) / span).coerceIn(0f, 1f)
      val contrastGain = if (lum > 1f) {
        val targetLum = (t * t * (3f - 2f * t) * 255f)
        // Mild blend keeps natural antialiasing while sharpening text darkness
        val desiredLum = lum * 0.22f + targetLum * 0.78f
        (desiredLum / lum).coerceIn(0.25f, 1.45f)
      } else {
        1f
      }

      var nr = r * contrastGain
      var ng = g * contrastGain
      var nb = b * contrastGain

      // 3. Preserve & boost colored ink (blue stamps, logos, portraits) relative to new luminance
      val newLum = (0.299f * nr + 0.587f * ng + 0.114f * nb).coerceIn(0f, 255f)
      val satBoost = if (chroma > 22f) 1.32f else 1.08f
      nr = newLum + (nr - newLum) * satBoost
      ng = newLum + (ng - newLum) * satBoost
      nb = newLum + (nb - newLum) * satBoost

      // 4. Blend bleached paper regions to spotless #FFFFFF
      nr = nr * (1f - bleachAlpha) + 255f * bleachAlpha
      ng = ng * (1f - bleachAlpha) + 255f * bleachAlpha
      nb = nb * (1f - bleachAlpha) + 255f * bleachAlpha

      val ri = nr.roundToInt().coerceIn(0, 255)
      val gi = ng.roundToInt().coerceIn(0, 255)
      val bi = nb.roundToInt().coerceIn(0, 255)
      pixels[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    val sharpened = applyUnsharpMask(pixels, width, height, strength = 0.40f)
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(sharpened, 0, width, 0, 0, width, height)
    return out
  }

  /**
   * Produces a clean monochrome grayscale scan with pure white background and crisp dark ink.
   */
  fun applyGrayscale(
    source: Bitmap,
    removeShadowAndGlare: Boolean = true,
  ): Bitmap {
    val base = if (removeShadowAndGlare) removeShadowAndGlare(source) else source
    val width = base.width
    val height = base.height
    val pixels = IntArray(width * height)
    base.getPixels(pixels, 0, width, 0, 0, width, height)
    if (base !== source) base.recycle()

    val (paperWhite, inkFloor) = estimatePaperAndInkLevels(pixels)
    val span = max(48f, (paperWhite - inkFloor).toFloat())

    for (i in pixels.indices) {
      val c = pixels[i]
      val r = (c ushr 16) and 0xFF
      val g = (c ushr 8) and 0xFF
      val b = c and 0xFF
      val lum = (r * 77 + g * 150 + b * 29) ushr 8

      val bleachAlpha = smoothstep(paperWhite - 42f, paperWhite - 8f, lum.toFloat())
      val t = ((lum - inkFloor) / span).coerceIn(0f, 1f)
      val curved = t * t * (3f - 2f * t) * 255f
      val outLum = (curved * (1f - bleachAlpha) + 255f * bleachAlpha).roundToInt().coerceIn(0, 255)
      pixels[i] = (0xFF shl 24) or (outLum shl 16) or (outLum shl 8) or outLum
    }

    val sharpened = applyUnsharpMask(pixels, width, height, strength = 0.36f)
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(sharpened, 0, width, 0, 0, width, height)
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
    val base = if (removeShadowAndGlare) removeShadowAndGlare(source) else source
    val width = base.width
    val height = base.height
    val pixels = IntArray(width * height)
    base.getPixels(pixels, 0, width, 0, 0, width, height)
    if (base !== source) base.recycle()

    val (paperWhite, _) = estimatePaperAndInkLevels(pixels)
    val inkDark = max(56, (paperWhite * 0.56f).roundToInt())
    val bwCeil = max(inkDark + 36, paperWhite - 24)
    val span = max(28, bwCeil - inkDark)

    for (i in pixels.indices) {
      val c = pixels[i]
      val r = (c ushr 16) and 0xFF
      val g = (c ushr 8) and 0xFF
      val b = c and 0xFF
      val lum = (r * 77 + g * 150 + b * 29) ushr 8

      val norm = ((lum - inkDark).toFloat() / span.toFloat()).coerceIn(0f, 1f)
      val curved = norm * norm * (3f - 2f * norm)
      val finalVal = (curved * 255f).roundToInt().coerceIn(0, 255)
      pixels[i] = (0xFF shl 24) or (finalVal shl 16) or (finalVal shl 8) or finalVal
    }

    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, width, 0, 0, width, height)
    return out
  }

  /**
   * Estimates smooth local per-channel (R, G, B) background envelope via 5x5 max dilation +
   * box smoothing, removing uneven shadows, ambient lighting casts (pink/cyan tint), and
   * specular glare while keeping saturated card hues (e.g. blue ID cards) intact.
   */
  fun removeShadowAndGlare(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val gridW = 64
    val gridH = max(32, (gridW * h.toFloat() / max(1, w).toFloat()).roundToInt().coerceAtMost(96))

    val small = Bitmap.createScaledBitmap(src, gridW, gridH, true)
    val smallPixels = IntArray(gridW * gridH)
    small.getPixels(smallPixels, 0, gridW, 0, 0, gridW, gridH)
    if (small !== src) small.recycle()

    val rGrid = IntArray(gridW * gridH)
    val gGrid = IntArray(gridW * gridH)
    val bGrid = IntArray(gridW * gridH)
    for (i in smallPixels.indices) {
      val c = smallPixels[i]
      rGrid[i] = (c ushr 16) and 0xFF
      gGrid[i] = (c ushr 8) and 0xFF
      bGrid[i] = c and 0xFF
    }

    val bgR = smoothEnvelope(dilate5x5(rGrid, gridW, gridH), gridW, gridH)
    val bgG = smoothEnvelope(dilate5x5(gGrid, gridW, gridH), gridW, gridH)
    val bgB = smoothEnvelope(dilate5x5(bGrid, gridW, gridH), gridW, gridH)

    val bgSmallPixels = IntArray(gridW * gridH) { i ->
      val rr = bgR[i].coerceIn(48, 255)
      val gg = bgG[i].coerceIn(48, 255)
      val bb = bgB[i].coerceIn(48, 255)
      (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }
    val bgSmallBmp = Bitmap.createBitmap(gridW, gridH, Bitmap.Config.ARGB_8888)
    bgSmallBmp.setPixels(bgSmallPixels, 0, gridW, 0, 0, gridW, gridH)
    val bgFullBmp = Bitmap.createScaledBitmap(bgSmallBmp, w, h, true)
    bgSmallBmp.recycle()

    val srcPixels = IntArray(w * h)
    val bgFullPixels = IntArray(w * h)
    src.getPixels(srcPixels, 0, w, 0, 0, w, h)
    bgFullBmp.getPixels(bgFullPixels, 0, w, 0, 0, w, h)
    bgFullBmp.recycle()

    val targetWhite = 250f
    for (i in srcPixels.indices) {
      val c = srcPixels[i]
      val r = ((c ushr 16) and 0xFF).toFloat()
      val g = ((c ushr 8) and 0xFF).toFloat()
      val b = (c and 0xFF).toFloat()

      val bgC = bgFullPixels[i]
      val br = ((bgC ushr 16) and 0xFF).toFloat().coerceAtLeast(52f)
      val bg = ((bgC ushr 8) and 0xFF).toFloat().coerceAtLeast(52f)
      val bb = (bgC and 0xFF).toFloat().coerceAtLeast(52f)

      val bgLum = (0.299f * br + 0.587f * bg + 0.114f * bb).coerceAtLeast(52f)
      val bgChroma = max(br, max(bg, bb)) - min(br, min(bg, bb))

      // Neutralize ambient paper color cast on white/tinted paper while preserving saturated colored cards
      val castNeutralize = 1f - smoothstep(46f, 76f, bgChroma) * 0.78f
      val effR = br * castNeutralize + bgLum * (1f - castNeutralize)
      val effG = bg * castNeutralize + bgLum * (1f - castNeutralize)
      val effB = bb * castNeutralize + bgLum * (1f - castNeutralize)

      val gainR = (targetWhite / effR).coerceIn(0.82f, 2.55f)
      val gainG = (targetWhite / effG).coerceIn(0.82f, 2.55f)
      val gainB = (targetWhite / effB).coerceIn(0.82f, 2.55f)

      val ri = (r * gainR).roundToInt().coerceIn(0, 255)
      val gi = (g * gainG).roundToInt().coerceIn(0, 255)
      val bi = (b * gainB).roundToInt().coerceIn(0, 255)
      srcPixels[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(srcPixels, 0, w, 0, 0, w, h)
    return out
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

  private fun smoothEnvelope(src: IntArray, w: Int, h: Int): IntArray {
    var cur = src
    repeat(2) {
      val next = IntArray(w * h)
      for (y in 0 until h) {
        val y0 = max(0, y - 2)
        val y1 = min(h - 1, y + 2)
        for (x in 0 until w) {
          val x0 = max(0, x - 2)
          val x1 = min(w - 1, x + 2)
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

  private fun estimatePaperAndInkLevels(pixels: IntArray): Pair<Int, Int> {
    val histogram = IntArray(256)
    var sampled = 0
    for (i in pixels.indices step 4) {
      val c = pixels[i]
      val lum = (((c ushr 16) and 0xFF) * 77 + ((c ushr 8) and 0xFF) * 150 + (c and 0xFF) * 29) ushr 8
      histogram[lum]++
      sampled++
    }
    val total = max(1, sampled)
    var acc = 0
    var p10 = 36
    var p75 = 236
    var found10 = false
    for (v in 0..255) {
      acc += histogram[v]
      if (!found10 && acc >= (total * 0.04).toInt()) {
        p10 = v.coerceIn(18, 68)
        found10 = true
      }
      if (acc >= (total * 0.72).toInt()) {
        p75 = v.coerceIn(195, 248)
        break
      }
    }
    return p75 to p10
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
        val r = (c ushr 16) and 0xFF
        val g = (c ushr 8) and 0xFF
        val b = c and 0xFF

        // Cross 5-tap blur approximation (fast & crisp)
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
