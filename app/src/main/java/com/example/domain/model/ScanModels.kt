package com.example.domain.model

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class EnhancementFilter(val displayName: String) {
  ORIGINAL("Original"),
  MAGIC_COLOR("Magic Color"),
  CRISP_BW("Crisp B&W"),
  GRAYSCALE("Grayscale"),
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
