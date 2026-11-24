package com.example.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.example.domain.model.EnhancementFilter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scanner image processor applying Android Canvas ColorMatrix filters and luminance-normalized
 * bitmap buffer operations for 'magic color', 'black and white', and 'grayscale' enhancements.
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

  fun applyMagicColor(
    source: Bitmap,
    removeShadowAndGlare: Boolean = true,
  ): Bitmap {
    val base = if (removeShadowAndGlare) removeShadowAndGlare(source) else source
    val width = base.width
    val height = base.height
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)

    val cm = ColorMatrix().apply {
      val contrast = 1.24f
      val translate = (-0.5f * contrast + 0.5f) * 255f + 12f
      set(
        floatArrayOf(
          contrast, 0f, 0f, 0f, translate,
          0f, contrast, 0f, 0f, translate,
          0f, 0f, contrast, 0f, translate,
          0f, 0f, 0f, 1f, 0f,
        )
      )
      val sat = ColorMatrix().apply { setSaturation(1.16f) }
      postConcat(sat)
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      colorFilter = ColorMatrixColorFilter(cm)
    }
    canvas.drawBitmap(base, 0f, 0f, paint)
    if (base !== source) base.recycle()
    return out
  }

  fun applyGrayscale(
    source: Bitmap,
    removeShadowAndGlare: Boolean = true,
  ): Bitmap {
    val base = if (removeShadowAndGlare) removeShadowAndGlare(source) else source
    val width = base.width
    val height = base.height
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)

    val desat = ColorMatrix().apply { setSaturation(0f) }
    val contrast = 1.36f
    val translate = (-0.5f * contrast + 0.5f) * 255f + 14f
    val contrastMatrix = ColorMatrix(
      floatArrayOf(
        contrast, 0f, 0f, 0f, translate,
        0f, contrast, 0f, 0f, translate,
        0f, 0f, contrast, 0f, translate,
        0f, 0f, 0f, 1f, 0f,
      )
    )
    desat.postConcat(contrastMatrix)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      colorFilter = ColorMatrixColorFilter(desat)
    }
    canvas.drawBitmap(base, 0f, 0f, paint)
    if (base !== source) base.recycle()
    return out
  }

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

    val histogram = IntArray(256)
    for (i in pixels.indices step 4) {
      val c = pixels[i]
      val r = (c ushr 16) and 0xFF
      val g = (c ushr 8) and 0xFF
      val b = c and 0xFF
      val lum = (r * 77 + g * 150 + b * 29) ushr 8
      histogram[lum]++
    }
    val totalSampled = max(1, pixels.size / 4)
    var acc = 0
    var paperWhite = 228
    for (v in 0..255) {
      acc += histogram[v]
      if (acc >= (totalSampled * 0.80).toInt()) {
        paperWhite = max(150, v)
        break
      }
    }
    val inkDark = max(40, (paperWhite * 0.48f).roundToInt())
    val span = max(24, paperWhite - inkDark)

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
   * Estimates local uneven paper illumination using max-dilation + box smoothing,
   * then normalizes pixel brightness to suppress shadows and specular glare.
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

    val lumGrid = IntArray(gridW * gridH) { i ->
      val c = smallPixels[i]
      val r = (c ushr 16) and 0xFF
      val g = (c ushr 8) and 0xFF
      val b = c and 0xFF
      (r * 77 + g * 150 + b * 29) ushr 8
    }

    val dilated = IntArray(gridW * gridH)
    for (y in 0 until gridH) {
      val y0 = max(0, y - 2)
      val y1 = min(gridH - 1, y + 2)
      for (x in 0 until gridW) {
        val x0 = max(0, x - 2)
        val x1 = min(gridW - 1, x + 2)
        var maxVal = 0
        for (yy in y0..y1) {
          val ro = yy * gridW
          for (xx in x0..x1) {
            val v = lumGrid[ro + xx]
            if (v > maxVal) maxVal = v
          }
        }
        dilated[y * gridW + x] = maxVal
      }
    }

    var bgSmooth = dilated
    repeat(2) {
      val next = IntArray(gridW * gridH)
      for (y in 0 until gridH) {
        val y0 = max(0, y - 2)
        val y1 = min(gridH - 1, y + 2)
        for (x in 0 until gridW) {
          val x0 = max(0, x - 2)
          val x1 = min(gridW - 1, x + 2)
          var sum = 0
          var cnt = 0
          for (yy in y0..y1) {
            val ro = yy * gridW
            for (xx in x0..x1) {
              sum += bgSmooth[ro + xx]
              cnt++
            }
          }
          next[y * gridW + x] = sum / max(1, cnt)
        }
      }
      bgSmooth = next
    }

    val bgPixels = IntArray(gridW * gridH) { i ->
      val v = bgSmooth[i].coerceIn(60, 255)
      (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    val bgSmallBmp = Bitmap.createBitmap(gridW, gridH, Bitmap.Config.ARGB_8888)
    bgSmallBmp.setPixels(bgPixels, 0, gridW, 0, 0, gridW, gridH)
    val bgFullBmp = Bitmap.createScaledBitmap(bgSmallBmp, w, h, true)
    bgSmallBmp.recycle()

    val srcPixels = IntArray(w * h)
    val bgFullPixels = IntArray(w * h)
    src.getPixels(srcPixels, 0, w, 0, 0, w, h)
    bgFullBmp.getPixels(bgFullPixels, 0, w, 0, 0, w, h)
    bgFullBmp.recycle()

    val targetWhite = 246f
    for (i in srcPixels.indices) {
      val c = srcPixels[i]
      val r = (c ushr 16) and 0xFF
      val g = (c ushr 8) and 0xFF
      val b = c and 0xFF
      val bgLum = (bgFullPixels[i] and 0xFF).toFloat().coerceAtLeast(52f)

      val gain = (targetWhite / bgLum).coerceIn(0.76f, 2.35f)
      var nr = r * gain
      var ng = g * gain
      var nb = b * gain

      val pixelLum = nr * 0.299f + ng * 0.587f + nb * 0.114f
      if (bgLum > 235f && pixelLum < 210f) {
        nr *= 0.88f
        ng *= 0.88f
        nb *= 0.88f
      }

      val ri = nr.roundToInt().coerceIn(0, 255)
      val gi = ng.roundToInt().coerceIn(0, 255)
      val bi = nb.roundToInt().coerceIn(0, 255)
      srcPixels[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(srcPixels, 0, w, 0, 0, w, h)
    return out
  }
}
