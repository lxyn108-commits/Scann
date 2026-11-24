package com.example.domain.model

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class EnhancementFilter(val displayName: String) {
  ORIGINAL("Tidak ada"),
  MAGIC_COLOR("Warna"),
  CRISP_BW("Hitam putih"),
  GRAYSCALE("Abu-abu"),
}

data class NormalizedPoint(
  val x: Float,
  val y: Float,
)

data class NormalizedQuad(
  val topLeft: NormalizedPoint,
  val topRight: NormalizedPoint,
  val bottomRight: NormalizedPoint,
  val bottomLeft: NormalizedPoint,
  val confidence: Float = 1f,
) {
  val isFullImage: Boolean
    get() = topLeft.x <= 0.015f && topLeft.y <= 0.015f &&
      topRight.x >= 0.985f && topRight.y <= 0.015f &&
      bottomRight.x >= 0.985f && bottomRight.y >= 0.985f &&
      bottomLeft.x <= 0.015f && bottomLeft.y >= 0.985f

  fun points(): List<NormalizedPoint> = listOf(topLeft, topRight, bottomRight, bottomLeft)

  fun areaRatio(): Float {
    val pts = points()
    var sum = 0f
    for (i in pts.indices) {
      val a = pts[i]
      val b = pts[(i + 1) % pts.size]
      sum += a.x * b.y - b.x * a.y
    }
    return kotlin.math.abs(sum) * 0.5f
  }

  fun isConvexValid(minArea: Float = 0.06f): Boolean {
    if (areaRatio() < minArea) return false
    val pts = points()
    var sign = 0f
    for (i in pts.indices) {
      val p0 = pts[i]
      val p1 = pts[(i + 1) % 4]
      val p2 = pts[(i + 2) % 4]
      val cross = (p1.x - p0.x) * (p2.y - p1.y) - (p1.y - p0.y) * (p2.x - p1.x)
      if (kotlin.math.abs(cross) < 1e-5f) return false
      if (sign == 0f) {
        sign = cross
      } else if (sign * cross <= 0f) {
        return false
      }
    }
    return true
  }

  fun maxCornerDelta(other: NormalizedQuad): Float {
    val a = points()
    val b = other.points()
    var maxD = 0f
    for (i in 0..3) {
      val d = kotlin.math.hypot(a[i].x - b[i].x, a[i].y - b[i].y)
      if (d > maxD) maxD = d
    }
    return maxD
  }

  fun lerpTo(target: NormalizedQuad, factor: Float): NormalizedQuad {
    val t = factor.coerceIn(0f, 1f)
    fun lp(a: NormalizedPoint, b: NormalizedPoint) = NormalizedPoint(
      x = a.x + (b.x - a.x) * t,
      y = a.y + (b.y - a.y) * t,
    )
    return NormalizedQuad(
      topLeft = lp(topLeft, target.topLeft),
      topRight = lp(topRight, target.topRight),
      bottomRight = lp(bottomRight, target.bottomRight),
      bottomLeft = lp(bottomLeft, target.bottomLeft),
      confidence = confidence + (target.confidence - confidence) * t,
    )
  }

  companion object {
    val FULL = NormalizedQuad(
      topLeft = NormalizedPoint(0f, 0f),
      topRight = NormalizedPoint(1f, 0f),
      bottomRight = NormalizedPoint(1f, 1f),
      bottomLeft = NormalizedPoint(0f, 1f),
      confidence = 1f,
    )
  }
}

data class NormalizedCropRect(
  val left: Float = 0f,
  val top: Float = 0f,
  val right: Float = 1f,
  val bottom: Float = 1f,
) {
  val isFullImage: Boolean
    get() = left <= 0.005f && top <= 0.005f && right >= 0.995f && bottom >= 0.995f
}

data class OcrWordBox(
  val text: String,
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
)

data class OcrLineBox(
  val text: String,
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
  val words: List<OcrWordBox>,
)

data class OcrPageResult(
  val formattedText: String,
  val lines: List<OcrLineBox>,
  val imageWidth: Int,
  val imageHeight: Int,
)

data class DraftPage(
  val id: String,
  val originalImagePath: String,
  val enhancedImagePath: String,
  val filter: EnhancementFilter = EnhancementFilter.MAGIC_COLOR,
  val rotationDegrees: Int = 0,
  val ocrResult: OcrPageResult? = null,
  val isProcessing: Boolean = false,
  val rawImagePath: String = originalImagePath,
  val quad: NormalizedQuad = NormalizedQuad.FULL,
  val removeShadowAndGlare: Boolean = true,
)

object OcrJsonSerializer {
  private fun enc(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())

  private fun dec(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())

  fun encodeLines(lines: List<OcrLineBox>): String {
    return lines.joinToString("\n") { line ->
      val wordsPart = line.words.joinToString(",") { w ->
        "${enc(w.text)}:${w.left}:${w.top}:${w.right}:${w.bottom}"
      }
      "${enc(line.text)}|${line.left}|${line.top}|${line.right}|${line.bottom}|$wordsPart"
    }
  }

  fun decodeLines(encoded: String): List<OcrLineBox> {
    if (encoded.isBlank()) return emptyList()
    return runCatching {
      encoded.split('\n').filter { it.isNotBlank() }.mapNotNull { row ->
        val parts = row.split('|')
        if (parts.size < 5) return@mapNotNull null
        val wordsRaw = parts.getOrNull(5).orEmpty()
        val words = if (wordsRaw.isBlank()) {
          emptyList()
        } else {
          wordsRaw.split(',').mapNotNull { token ->
            val wp = token.split(':')
            if (wp.size < 5) return@mapNotNull null
            OcrWordBox(
              text = dec(wp[0]),
              left = wp[1].toFloatOrNull() ?: 0f,
              top = wp[2].toFloatOrNull() ?: 0f,
              right = wp[3].toFloatOrNull() ?: 0f,
              bottom = wp[4].toFloatOrNull() ?: 0f,
            )
          }
        }
        OcrLineBox(
          text = dec(parts[0]),
          left = parts[1].toFloatOrNull() ?: 0f,
          top = parts[2].toFloatOrNull() ?: 0f,
          right = parts[3].toFloatOrNull() ?: 0f,
          bottom = parts[4].toFloatOrNull() ?: 0f,
          words = words,
        )
      }
    }.getOrDefault(emptyList())
  }
}
