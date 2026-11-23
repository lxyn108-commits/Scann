package com.example.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.domain.model.OcrLineBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.max

data class SearchablePdfPageInput(
  val imagePath: String,
  val imageWidth: Int,
  val imageHeight: Int,
  val ocrLines: List<OcrLineBox>,
  val formattedText: String,
)

/**
 * Generates standards-compliant ISO 32000 PDF 1.4 searchable documents.
 * Each page embeds the high-clarity JPEG scan image plus an invisible (3 Tr)
 * selectable & searchable OCR text layer mapped accurately to every word's bounding box.
 */
object SearchablePdfGenerator {
  private const val PORTRAIT_WIDTH_PT = 595.28f
  private const val LANDSCAPE_WIDTH_PT = 841.89f

  suspend fun generateSearchablePdf(
    pages: List<SearchablePdfPageInput>,
    outputFile: File,
  ): File = withContext(Dispatchers.IO) {
    require(pages.isNotEmpty()) { "Cannot generate PDF from empty page list." }
    outputFile.parentFile?.mkdirs()

    val objects = mutableListOf<ByteArray>()
    // Object 1: Catalog, Object 2: Pages, Object 3: Helvetica Font
    val pageObjectIds = mutableListOf<Int>()
    val pageDefinitions = mutableListOf<ByteArray>()

    // Pre-allocate object IDs starting at 4:
    // For each page: pageObjId = 4 + idx * 3, contentObjId = 5 + idx * 3, imageObjId = 6 + idx * 3
    for ((idx, page) in pages.withIndex()) {
      val pageObjId = 4 + idx * 3
      val contentObjId = 5 + idx * 3
      val imageObjId = 6 + idx * 3
      pageObjectIds.add(pageObjId)

      val jpegData = readOptimizedJpeg(page.imagePath)
      val wPx = max(1, jpegData.width)
      val hPx = max(1, jpegData.height)
      val baseW = if (wPx > hPx) LANDSCAPE_WIDTH_PT else PORTRAIT_WIDTH_PT
      val pageW = baseW
      val pageH = (baseW * hPx.toFloat() / wPx.toFloat()).coerceIn(220f, 1680f)

      val contentStream = buildSearchablePageStream(pageW, pageH, page.ocrLines)
      val contentBytes = contentStream.toByteArray(StandardCharsets.US_ASCII)

      val pageDict = """
        << /Type /Page /Parent 2 0 R /MediaBox [0 0 ${fmt(pageW)} ${fmt(pageH)}]
           /Resources << /Font << /F1 3 0 R >> /XObject << /Im0 $imageObjId 0 R >> >>
           /Contents $contentObjId 0 R >>
      """.trimIndent().toByteArray(StandardCharsets.US_ASCII)

      val contentObj = buildStreamObject(
        dictHeader = "<< /Length ${contentBytes.size} >>",
        streamBytes = contentBytes,
      )

      val imageObj = buildStreamObject(
        dictHeader = "<< /Type /XObject /Subtype /Image /Width $wPx /Height $hPx /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpegData.bytes.size} >>",
        streamBytes = jpegData.bytes,
      )

      pageDefinitions.add(pageDict)
      pageDefinitions.add(contentObj)
      pageDefinitions.add(imageObj)
    }

    val kidsRef = pageObjectIds.joinToString(" ") { "$it 0 R" }
    objects.add("<< /Type /Catalog /Pages 2 0 R >>".toByteArray(StandardCharsets.US_ASCII))
    objects.add("<< /Type /Pages /Kids [$kidsRef] /Count ${pages.size} >>".toByteArray(StandardCharsets.US_ASCII))
    objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>".toByteArray(StandardCharsets.US_ASCII))
    objects.addAll(pageDefinitions)

    writePdfDocument(objects, outputFile)
    outputFile
  }

  /**
   * Generates a crisp, pure vector text-only PDF preserving layout and paragraph breaks.
   */
  suspend fun generateTextOnlyPdf(
    pages: List<SearchablePdfPageInput>,
    outputFile: File,
    documentTitle: String,
  ): File = withContext(Dispatchers.IO) {
    require(pages.isNotEmpty()) { "Cannot generate text PDF from empty page list." }
    outputFile.parentFile?.mkdirs()

    val pageW = 595.28f
    val pageH = 841.89f
    val marginX = 48f
    val topY = pageH - 54f
    val bottomY = 52f
    val lineHeight = 14.5f
    val maxCharsPerLine = 84

    val textPages = mutableListOf<List<String>>()
    for ((idx, page) in pages.withIndex()) {
      val header = "${documentTitle.ifBlank { "ClearScan Document" }} — Page ${idx + 1}"
      val rawLines = page.formattedText.ifBlank { "(No OCR text detected on this page)" }.lines()
      val wrapped = mutableListOf(header, "")
      for (line in rawLines) {
        if (line.length <= maxCharsPerLine) {
          wrapped.add(line)
        } else {
          var remaining = line
          while (remaining.length > maxCharsPerLine) {
            val splitAt = remaining.lastIndexOf(' ', maxCharsPerLine).let { if (it <= 12) maxCharsPerLine else it }
            wrapped.add(remaining.substring(0, splitAt).trimEnd())
            remaining = remaining.substring(splitAt).trimStart()
          }
          if (remaining.isNotEmpty()) wrapped.add(remaining)
        }
      }

      val maxLinesPerPdfPage = ((topY - bottomY) / lineHeight).toInt().coerceAtLeast(10)
      var offset = 0
      while (offset < wrapped.size) {
        val end = minOf(wrapped.size, offset + maxLinesPerPdfPage)
        textPages.add(wrapped.subList(offset, end))
        offset = end
      }
    }

    val pageObjectIds = mutableListOf<Int>()
    val pageDefinitions = mutableListOf<ByteArray>()
    for ((idx, lines) in textPages.withIndex()) {
      val pageObjId = 4 + idx * 2
      val contentObjId = 5 + idx * 2
      pageObjectIds.add(pageObjId)

      val streamSb = StringBuilder()
      streamSb.append("BT\n0 Tr\n")
      var y = topY
      for ((lineIdx, textLine) in lines.withIndex()) {
        val fontSize = if (lineIdx == 0) 12.5f else 10.5f
        streamSb.append("/F1 ${fmt(fontSize)} Tf\n")
        streamSb.append("1 0 0 1 ${fmt(marginX)} ${fmt(y)} Tm\n")
        streamSb.append("(${escapePdfString(textLine)}) Tj\n")
        y -= lineHeight
      }
      streamSb.append("ET\n")

      val streamBytes = streamSb.toString().toByteArray(StandardCharsets.US_ASCII)
      val pageDict = """
        << /Type /Page /Parent 2 0 R /MediaBox [0 0 ${fmt(pageW)} ${fmt(pageH)}]
           /Resources << /Font << /F1 3 0 R >> >>
           /Contents $contentObjId 0 R >>
      """.trimIndent().toByteArray(StandardCharsets.US_ASCII)

      pageDefinitions.add(pageDict)
      pageDefinitions.add(buildStreamObject("<< /Length ${streamBytes.size} >>", streamBytes))
    }

    val kidsRef = pageObjectIds.joinToString(" ") { "$it 0 R" }
    val objects = mutableListOf<ByteArray>()
    objects.add("<< /Type /Catalog /Pages 2 0 R >>".toByteArray(StandardCharsets.US_ASCII))
    objects.add("<< /Type /Pages /Kids [$kidsRef] /Count ${textPages.size} >>".toByteArray(StandardCharsets.US_ASCII))
    objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>".toByteArray(StandardCharsets.US_ASCII))
    objects.addAll(pageDefinitions)

    writePdfDocument(objects, outputFile)
    outputFile
  }

  internal fun buildSearchablePageStream(
    pageW: Float,
    pageH: Float,
    ocrLines: List<OcrLineBox>,
  ): String {
    val sb = StringBuilder()
    // Render scanned page image full-bleed onto the PDF page
    sb.append("q\n")
    sb.append("${fmt(pageW)} 0 0 ${fmt(pageH)} 0 0 cm\n")
    sb.append("/Im0 Do\n")
    sb.append("Q\n")

    if (ocrLines.isEmpty()) return sb.toString()

    // Invisible OCR text layer (Render Mode 3 = neither fill nor stroke -> searchable & selectable)
    sb.append("BT\n")
    sb.append("3 Tr\n")

    for (line in ocrLines) {
      val words = line.words
      if (words.isEmpty()) {
        val boxW = ((line.right - line.left) * pageW).coerceAtLeast(4f)
        val boxH = ((line.bottom - line.top) * pageH).coerceIn(5f, 72f)
        val fontSize = (boxH * 0.85f).coerceIn(4f, 64f)
        val x = (line.left * pageW).coerceIn(0f, pageW - 2f)
        val y = ((1f - line.bottom) * pageH).coerceIn(0f, pageH - 2f)
        val estWidth = max(1f, line.text.length * fontSize * 0.52f)
        val hScale = ((boxW / estWidth) * 100f).coerceIn(40f, 220f)
        sb.append("/F1 ${fmt(fontSize)} Tf\n")
        sb.append("${fmt(hScale)} Tz\n")
        sb.append("1 0 0 1 ${fmt(x)} ${fmt(y)} Tm\n")
        sb.append("(${escapePdfString(line.text)}) Tj\n")
      } else {
        for ((wordIdx, word) in words.withIndex()) {
          val textOut = if (wordIdx < words.lastIndex) "${word.text} " else word.text
          val boxW = ((word.right - word.left) * pageW).coerceAtLeast(2f)
          val boxH = ((word.bottom - word.top) * pageH).coerceIn(5f, 72f)
          val fontSize = (boxH * 0.85f).coerceIn(4f, 64f)
          val x = (word.left * pageW).coerceIn(0f, pageW - 2f)
          val y = ((1f - word.bottom) * pageH).coerceIn(0f, pageH - 2f)
          val estWidth = max(1f, word.text.length * fontSize * 0.52f)
          val hScale = ((boxW / estWidth) * 100f).coerceIn(40f, 220f)
          sb.append("/F1 ${fmt(fontSize)} Tf\n")
          sb.append("${fmt(hScale)} Tz\n")
          sb.append("1 0 0 1 ${fmt(x)} ${fmt(y)} Tm\n")
          sb.append("(${escapePdfString(textOut)}) Tj\n")
        }
      }
    }

    sb.append("ET\n")
    return sb.toString()
  }

  private fun buildStreamObject(dictHeader: String, streamBytes: ByteArray): ByteArray {
    val head = "$dictHeader\nstream\n".toByteArray(StandardCharsets.US_ASCII)
    val tail = "\nendstream".toByteArray(StandardCharsets.US_ASCII)
    val out = ByteArrayOutputStream(head.size + streamBytes.size + tail.size)
    out.write(head)
    out.write(streamBytes)
    out.write(tail)
    return out.toByteArray()
  }

  private fun writePdfDocument(objects: List<ByteArray>, outputFile: File) {
    FileOutputStream(outputFile).use { out ->
      // PDF 1.4 binary header
      out.write("%PDF-1.4\n".toByteArray(StandardCharsets.US_ASCII))
      out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

      var currentOffset = 15L // 9 bytes (%PDF-1.4\n) + 6 bytes binary comment
      val offsets = LongArray(objects.size)

      for ((idx, body) in objects.withIndex()) {
        offsets[idx] = currentOffset
        val objHeader = "${idx + 1} 0 obj\n".toByteArray(StandardCharsets.US_ASCII)
        val objFooter = "\nendobj\n".toByteArray(StandardCharsets.US_ASCII)
        out.write(objHeader)
        out.write(body)
        out.write(objFooter)
        currentOffset += objHeader.size + body.size + objFooter.size
      }

      val xrefStart = currentOffset
      val xrefSb = StringBuilder()
      xrefSb.append("xref\n")
      xrefSb.append("0 ${objects.size + 1}\n")
      xrefSb.append("0000000000 65535 f \n")
      for (offset in offsets) {
        xrefSb.append(String.format(Locale.US, "%010d 00000 n \n", offset))
      }
      xrefSb.append("trailer\n")
      xrefSb.append("<< /Size ${objects.size + 1} /Root 1 0 R >>\n")
      xrefSb.append("startxref\n")
      xrefSb.append("$xrefStart\n")
      xrefSb.append("%%EOF\n")
      out.write(xrefSb.toString().toByteArray(StandardCharsets.US_ASCII))
      out.flush()
    }
  }

  private data class JpegImage(val bytes: ByteArray, val width: Int, val height: Int)

  private fun readOptimizedJpeg(path: String): JpegImage {
    val file = File(path)
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, opts)
    if (opts.outWidth > 0 && opts.outHeight > 0 && file.exists()) {
      return JpegImage(file.readBytes(), opts.outWidth, opts.outHeight)
    }
    val bmp = DocumentImageProcessor.decodeSampledBitmap(path, 2000)
    val baos = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 90, baos)
    val result = JpegImage(baos.toByteArray(), bmp.width, bmp.height)
    bmp.recycle()
    return result
  }

  internal fun escapePdfString(input: String): String {
    val sb = StringBuilder(input.length)
    for (ch in input) {
      val mapped = when (ch) {
        '\u2018', '\u2019' -> '\''
        '\u201C', '\u201D' -> '"'
        '\u2013', '\u2014' -> '-'
        '\u2022' -> '*'
        else -> ch
      }
      when {
        mapped == '\\' -> sb.append("\\\\")
        mapped == '(' -> sb.append("\\(")
        mapped == ')' -> sb.append("\\)")
        mapped == '\r' || mapped == '\n' -> sb.append(' ')
        mapped.code in 32..126 -> sb.append(mapped)
        mapped.code in 160..255 -> sb.append(String.format(Locale.US, "\\%03o", mapped.code))
        else -> sb.append('?')
      }
    }
    return sb.toString()
  }

  private fun fmt(value: Float): String = String.format(Locale.US, "%.2f", value)
}
