package com.example.processing

import android.graphics.Bitmap
import com.example.domain.model.OcrLineBox
import com.example.domain.model.OcrPageResult
import com.example.domain.model.OcrWordBox
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max

object OcrEngine {
  private val recognizer by lazy {
    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
  }

  suspend fun recognizeBitmap(bitmap: Bitmap): OcrPageResult = withContext(Dispatchers.Default) {
    val inputImage = InputImage.fromBitmap(bitmap, 0)
    val result = awaitTextRecognition(inputImage)
    formatOcrResult(result, bitmap.width, bitmap.height)
  }

  private suspend fun awaitTextRecognition(image: InputImage): Text =
    suspendCancellableCoroutine { cont ->
      recognizer.process(image)
        .addOnSuccessListener { text ->
          if (cont.isActive) cont.resume(text)
        }
        .addOnFailureListener { error ->
          if (cont.isActive) cont.resumeWithException(error)
        }
    }

  internal fun formatOcrResult(
    visionText: Text,
    imageWidth: Int,
    imageHeight: Int,
  ): OcrPageResult {
    val w = max(1, imageWidth).toFloat()
    val h = max(1, imageHeight).toFloat()

    val rawLines = visionText.textBlocks.flatMap { block ->
      block.lines.mapNotNull { line ->
        val box = line.boundingBox ?: return@mapNotNull null
        val words = line.elements.mapNotNull { element ->
          val eBox = element.boundingBox ?: return@mapNotNull null
          OcrWordBox(
            text = element.text,
            left = (eBox.left / w).coerceIn(0f, 1f),
            top = (eBox.top / h).coerceIn(0f, 1f),
            right = (eBox.right / w).coerceIn(0f, 1f),
            bottom = (eBox.bottom / h).coerceIn(0f, 1f),
          )
        }
        OcrLineBox(
          text = line.text,
          left = (box.left / w).coerceIn(0f, 1f),
          top = (box.top / h).coerceIn(0f, 1f),
          right = (box.right / w).coerceIn(0f, 1f),
          bottom = (box.bottom / h).coerceIn(0f, 1f),
          words = words,
        )
      }
    }

    val formatted = buildLayoutPreservedText(rawLines)
    return OcrPageResult(
      formattedText = formatted,
      lines = rawLines.sortedWith(compareBy({ it.top }, { it.left })),
      imageWidth = imageWidth,
      imageHeight = imageHeight,
    )
  }

  /**
   * Clusters OCR lines by vertical baseline so multi-column tables, invoices, and paragraphs
   * maintain their visual alignment and spacing when exported as plain text or text-only PDFs.
   */
  internal fun buildLayoutPreservedText(lines: List<OcrLineBox>): String {
    if (lines.isEmpty()) return ""
    val sorted = lines.sortedWith(compareBy({ (it.top + it.bottom) / 2f }, { it.left }))
    val avgHeight = sorted.map { max(0.01f, it.bottom - it.top) }.average().toFloat()
    val rowTolerance = avgHeight * 0.55f

    val rows = mutableListOf<MutableList<OcrLineBox>>()
    for (line in sorted) {
      val centerY = (line.top + line.bottom) / 2f
      val currentRow = rows.lastOrNull()
      if (currentRow == null) {
        rows.add(mutableListOf(line))
      } else {
        val rowCenter = currentRow.map { (it.top + it.bottom) / 2f }.average().toFloat()
        if (abs(centerY - rowCenter) <= rowTolerance) {
          currentRow.add(line)
        } else {
          rows.add(mutableListOf(line))
        }
      }
    }

    val sb = StringBuilder()
    var prevRowBottom = 0f
    for ((index, row) in rows.withIndex()) {
      row.sortBy { it.left }
      val rowTop = row.minOf { it.top }
      val rowBottom = row.maxOf { it.bottom }
      if (index > 0) {
        val verticalGap = rowTop - prevRowBottom
        if (verticalGap > avgHeight * 1.35f) {
          sb.append("\n\n")
        } else {
          sb.append('\n')
        }
      }

      var cursorNormalizedX = 0f
      for ((colIdx, line) in row.withIndex()) {
        if (colIdx == 0) {
          // Preserve notable indentation (e.g., centered headings or indented bullets)
          val indentSpaces = ((line.left - 0.05f) * 40f).toInt().coerceIn(0, 16)
          if (indentSpaces >= 3) {
            repeat(indentSpaces) { sb.append(' ') }
          }
        } else {
          val gap = line.left - cursorNormalizedX
          val spaces = if (gap > 0.04f) (gap * 45f).toInt().coerceIn(2, 16) else 2
          repeat(spaces) { sb.append(' ') }
        }
        sb.append(line.text)
        cursorNormalizedX = line.right
      }
      prevRowBottom = rowBottom
    }
    return sb.toString().trim()
  }
}
