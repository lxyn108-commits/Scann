package com.example

import com.example.domain.model.DraftPage
import com.example.domain.model.EnhancementFilter
import com.example.domain.model.NormalizedCropRect
import com.example.domain.model.OcrJsonSerializer
import com.example.domain.model.OcrLineBox
import com.example.domain.model.OcrPageResult
import com.example.domain.model.OcrWordBox
import com.example.processing.DocumentActionType
import com.example.processing.DocumentImageProcessor
import com.example.processing.OcrEngine
import com.example.processing.PageSuggestion
import com.example.processing.SearchablePdfGenerator
import com.example.processing.SearchablePdfPageInput
import com.example.processing.SmartScanAdvisor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SearchablePdfAndOcrTest {

  @Test
  fun layoutPreservedFormatterKeepsColumnsAndParagraphs() {
    val lines = listOf(
      OcrLineBox(
        text = "INVOICE #1042",
        left = 0.05f,
        top = 0.10f,
        right = 0.40f,
        bottom = 0.14f,
        words = emptyList(),
      ),
      OcrLineBox(
        text = "TOTAL: $240.00",
        left = 0.62f,
        top = 0.105f,
        right = 0.92f,
        bottom = 0.145f,
        words = emptyList(),
      ),
      OcrLineBox(
        text = "Thank you for your business.",
        left = 0.05f,
        top = 0.30f,
        right = 0.55f,
        bottom = 0.34f,
        words = emptyList(),
      ),
    )

    val formatted = OcrEngine.buildLayoutPreservedText(lines)
    assertTrue(formatted.contains("INVOICE #1042"))
    assertTrue(formatted.contains("TOTAL: $240.00"))
    val firstLine = formatted.lines().first()
    assertTrue(firstLine.contains("INVOICE #1042") && firstLine.contains("TOTAL: $240.00"))
    assertTrue(formatted.contains("\n\nThank you for your business."))
  }

  @Test
  fun ocrJsonSerializerRoundTripsCorrectly() {
    val lines = listOf(
      OcrLineBox(
        text = "ClearScan OCR",
        left = 0.1f,
        top = 0.2f,
        right = 0.5f,
        bottom = 0.25f,
        words = listOf(
          OcrWordBox("ClearScan", 0.1f, 0.2f, 0.32f, 0.25f),
          OcrWordBox("OCR", 0.35f, 0.2f, 0.5f, 0.25f),
        ),
      )
    )
    val encoded = OcrJsonSerializer.encodeLines(lines)
    val decoded = OcrJsonSerializer.decodeLines(encoded)
    assertEquals(1, decoded.size)
    assertEquals("ClearScan OCR", decoded[0].text)
    assertEquals(2, decoded[0].words.size)
    assertEquals("OCR", decoded[0].words[1].text)
  }

  @Test
  fun searchablePdfStreamEmbedsInvisible3TrOcrLayerAndImage() {
    val ocrLines = listOf(
      OcrLineBox(
        text = "Quarterly Revenue Report",
        left = 0.1f,
        top = 0.15f,
        right = 0.8f,
        bottom = 0.20f,
        words = listOf(
          OcrWordBox("Quarterly", 0.1f, 0.15f, 0.35f, 0.20f),
          OcrWordBox("Revenue", 0.37f, 0.15f, 0.58f, 0.20f),
          OcrWordBox("Report", 0.60f, 0.15f, 0.80f, 0.20f),
        ),
      )
    )

    val stream = SearchablePdfGenerator.buildSearchablePageStream(
      pageW = 595.28f,
      pageH = 841.89f,
      ocrLines = ocrLines,
    )

    assertTrue(stream.contains("/Im0 Do"))
    assertTrue(stream.contains("BT\n3 Tr"))
    assertTrue(stream.contains("(Quarterly ) Tj"))
    assertTrue(stream.contains("(Revenue ) Tj"))
    assertTrue(stream.contains("(Report) Tj"))
    assertTrue(stream.contains("ET"))
  }

  @Test
  fun textOnlyPdfGeneratesValidPdfStructure() = runTest {
    val tempDir = createTempDirectory("text_pdf_test").toFile()
    try {
      val pdfFile = File(tempDir, "formatted_text.pdf")
      SearchablePdfGenerator.generateTextOnlyPdf(
        pages = listOf(
          SearchablePdfPageInput(
            imagePath = "",
            imageWidth = 1000,
            imageHeight = 1400,
            ocrLines = emptyList(),
            formattedText = "Invoice Header\nLine Item A    $50.00",
          )
        ),
        outputFile = pdfFile,
        documentTitle = "Test Invoice",
      )
      assertTrue(pdfFile.exists() && pdfFile.length() > 200)
      val pdfText = pdfFile.readText(Charsets.ISO_8859_1)
      assertTrue(pdfText.startsWith("%PDF-1.4"))
      assertTrue(pdfText.contains("(Test Invoice"))
      assertTrue(pdfText.contains("(Invoice Header) Tj"))
      assertTrue(pdfText.contains("xref"))
      assertTrue(pdfText.trimEnd().endsWith("%%EOF"))
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun smartScanAdvisorExtractsTitlePageAndActionSuggestions() {
    val draft = DraftPage(
      id = "p1",
      originalImagePath = "",
      enhancedImagePath = "",
      filter = EnhancementFilter.MAGIC_COLOR,
      ocrResult = OcrPageResult(
        formattedText = "ACME Invoice #2026\nContact: billing@acme.com\nhttps://acme.com/pay\nTotal Rp 250.000",
        lines = listOf(
          OcrLineBox(
            text = "ACME Invoice #2026",
            left = 0.1f,
            top = 0.1f,
            right = 0.7f,
            bottom = 0.15f,
            words = listOf(OcrWordBox("ACME", 0.1f, 0.1f, 0.3f, 0.15f)),
          )
        ),
        imageWidth = 1000,
        imageHeight = 1400,
      ),
    )

    val titles = SmartScanAdvisor.suggestTitles(listOf(draft))
    assertTrue(titles.contains("ACME Invoice #2026"))
    assertTrue(titles.any { it.startsWith("Invoice") })

    val pageSuggestions = SmartScanAdvisor.suggestForPage(
      page = draft,
      quality = DocumentImageProcessor.ImageQualityAnalysis(
        meanLuminance = 110f,
        contrastSpread = 70f,
        suggestedTrimRect = NormalizedCropRect(0.08f, 0.08f, 0.92f, 0.92f),
      ),
    )
    assertTrue(pageSuggestions.any { it is PageSuggestion.AutoTrimBorders })
    assertTrue(pageSuggestions.any { it is PageSuggestion.ApplyFilter })
    assertTrue(pageSuggestions.any { it is PageSuggestion.RotatePage })

    val actions = SmartScanAdvisor.extractDocumentActions(draft.ocrResult!!.formattedText)
    assertTrue(actions.any { it.type == DocumentActionType.EMAIL && it.value == "billing@acme.com" })
    assertTrue(actions.any { it.type == DocumentActionType.URL && it.value == "https://acme.com/pay" })
    assertTrue(actions.any { it.type == DocumentActionType.AMOUNT })

    val libraryTags = SmartScanAdvisor.suggestLibrarySearchTags(
      listOf(draft.ocrResult!!.formattedText, "Coffee Shop Receipt")
    )
    assertTrue(libraryTags.contains("Invoice"))
    assertTrue(libraryTags.contains("Receipt"))
  }
}
