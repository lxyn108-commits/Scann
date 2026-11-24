package com.example

import com.example.domain.model.DraftPage
import com.example.domain.model.EnhancementFilter
import com.example.domain.model.NormalizedCropRect
import com.example.domain.model.OcrJsonSerializer
import com.example.domain.model.OcrLineBox
import com.example.domain.model.OcrPageResult
import com.example.domain.model.OcrWordBox
import com.example.domain.model.NormalizedPoint
import com.example.domain.model.NormalizedQuad
import com.example.processing.DocumentActionType
import com.example.processing.DocumentImageProcessor
import com.example.processing.DocumentQuadDetector
import com.example.processing.OcrEngine
import com.example.processing.PageSuggestion
import com.example.processing.QuadStabilityTracker
import com.example.processing.SearchablePdfGenerator
import com.example.processing.SearchablePdfPageInput
import com.example.processing.SmartScanAdvisor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs

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
      listOf(draft.ocrResult.formattedText, "Coffee Shop Receipt")
    )
    assertTrue(libraryTags.contains("Invoice"))
    assertTrue(libraryTags.contains("Receipt"))
  }

  @Test
  fun documentQuadDetectorLocatesTiltedDocumentRectangle() {
    val w = 120
    val h = 160
    val luma = IntArray(w * h) { 55 } // dark desk background

    // Draw a bright paper document with slight perspective tilt inside [0.15..0.85, 0.18..0.82]
    for (y in 0 until h) {
      val ny = y.toFloat() / h
      if (ny in 0.20f..0.78f) {
        val t = (ny - 0.20f) / 0.58f
        val leftX = ((0.18f - 0.04f * t) * w).toInt()
        val rightX = ((0.80f + 0.05f * t) * w).toInt()
        for (x in leftX..rightX) {
          luma[y * w + x] = 232
        }
      }
    }

    val detected = DocumentQuadDetector.detectFromLumaGrid(luma, w, h)
    assertNotNull("Expected tilted document quad to be detected", detected)
    val quad = detected!!
    assertTrue(quad.isConvexValid())
    assertTrue("Detected TL.x=${quad.topLeft.x} should be near 0.18", abs(quad.topLeft.x - 0.18f) < 0.09f)
    assertTrue("Detected TR.x=${quad.topRight.x} should be near 0.80", abs(quad.topRight.x - 0.80f) < 0.09f)
    assertTrue("Detected TL.y=${quad.topLeft.y} should be near 0.20", abs(quad.topLeft.y - 0.20f) < 0.09f)
    assertTrue("Detected BL.y=${quad.bottomLeft.y} should be near 0.78", abs(quad.bottomLeft.y - 0.78f) < 0.09f)
  }

  @Test
  fun quadStabilityTrackerTriggersAutoCaptureOnlyAfterSteadyHold() {
    val tracker = QuadStabilityTracker(
      stabilityWindowMs = 800L,
      autoCaptureCooldownMs = 2000L,
      maxCornerDrift = 0.03f,
    )
    val docQuad = NormalizedQuad(
      topLeft = NormalizedPoint(0.15f, 0.20f),
      topRight = NormalizedPoint(0.85f, 0.20f),
      bottomRight = NormalizedPoint(0.85f, 0.75f),
      bottomLeft = NormalizedPoint(0.15f, 0.75f),
      confidence = 0.9f,
    )

    assertFalse(tracker.update(docQuad, 1000L))
    assertFalse(tracker.update(docQuad, 1400L))
    assertTrue(tracker.stabilityProgress in 0.4f..0.6f)
    assertTrue(tracker.update(docQuad, 1900L))
    // Cooldown prevents double trigger immediately after auto-capture
    assertFalse(tracker.update(docQuad, 2200L))
  }

  @Test
  fun documentQuadDetectorHandlesNoisyPatternedCarpetAndOffCenterCard() {
    val w = 120
    val h = 160
    val y = IntArray(w * h)
    val u = IntArray(w * h)
    val v = IntArray(w * h)

    // Simulate noisy speckled pink/black patterned carpet (high local speckle variance)
    for (py in 0 until h) {
      for (px in 0 until w) {
        val speckle = ((px * 17 + py * 31) % 7) < 3
        val idx = py * w + px
        y[idx] = if (speckle) 24 else 118
        u[idx] = 114
        v[idx] = if (speckle) 128 else 166
      }
    }

    // Draw an off-center tilted light-blue/white ID card (like KTP in user's screenshot: x=0.33..0.92, y=0.36..0.56)
    for (py in 0 until h) {
      val ny = py.toFloat() / h
      for (px in 0 until w) {
        val nx = px.toFloat() / w
        val t = (nx - 0.34f) / 0.56f
        val topEdge = 0.35f + 0.04f * t
        val botEdge = 0.54f + 0.04f * t
        val uSlope = (ny - 0.35f) / 0.19f
        val leftEdge = 0.34f - 0.02f * uSlope
        val rightEdge = 0.90f - 0.02f * uSlope
        if (nx in leftEdge..rightEdge && ny in topEdge..botEdge) {
          val idx = py * w + px
          y[idx] = 178
          u[idx] = 144
          v[idx] = 112
        }
      }
    }

    val detected = DocumentQuadDetector.detectFromYuvGrid(y, u, v, w, h)
    assertNotNull("Expected tilted off-center card on patterned rug to be detected", detected)
    val quad = detected!!
    assertTrue("Quad must be convex", quad.isConvexValid(minArea = 0.05f))
    assertTrue("Card left edge should stay near 0.33 (was ${quad.topLeft.x})", abs(quad.topLeft.x - 0.34f) < 0.08f)
    assertTrue("Card right edge should stay near 0.90 (was ${quad.topRight.x})", abs(quad.topRight.x - 0.89f) < 0.08f)
    assertTrue("Card top edge should stay near 0.37 (was ${quad.topLeft.y})", abs(quad.topLeft.y - 0.35f) < 0.08f)
    assertTrue("Card bottom edge should stay near 0.56 (was ${quad.bottomRight.y})", abs(quad.bottomRight.y - 0.58f) < 0.08f)
  }

  @Test
  fun documentQuadDetectorLocksOntoHorizontalBlueCardOnGreenWhiteEmbossedMat() {
    val w = 120
    val h = 160
    val y = IntArray(w * h)
    val u = IntArray(w * h)
    val v = IntArray(w * h)

    // Green playmat with embossed square grid grooves and crisp white leaf stripes
    for (py in 0 until h) {
      val ny = py.toFloat() / h
      for (px in 0 until w) {
        val nx = px.toFloat() / w
        val idx = py * w + px

        val isWhiteLeaf = (abs((nx * 3.5f + ny * 2.1f) % 1.0f - 0.5f) < 0.08f) ||
          (abs((nx * 2.2f - ny * 3.0f + 4f) % 1.0f - 0.5f) < 0.06f)
        // Strong vertical & horizontal embossed tile seams along x=0.30, x=0.72 and y=0.26, y=0.73
        val isEmbossedGroove = (abs(nx - 0.30f) < 0.015f || abs(nx - 0.72f) < 0.015f ||
          abs(ny - 0.26f) < 0.015f || abs(ny - 0.73f) < 0.015f)

        when {
          isWhiteLeaf -> {
            y[idx] = 228
            u[idx] = 128
            v[idx] = 128
          }
          isEmbossedGroove -> {
            y[idx] = 88
            u[idx] = 104
            v[idx] = 120
          }
          else -> {
            y[idx] = 142
            u[idx] = 100
            v[idx] = 118
          }
        }
      }
    }

    // Horizontal blue KTP card (x=0.13..0.87, y=0.28..0.48) with near-iso-luminant Y, dark text, and red portrait box
    for (py in 0 until h) {
      val ny = py.toFloat() / h
      for (px in 0 until w) {
        val nx = px.toFloat() / w
        if (nx in 0.13f..0.87f && ny in 0.28f..0.48f) {
          val idx = py * w + px
          val inRedPortrait = nx in 0.69f..0.83f && ny in 0.33f..0.44f
          val inDarkText = !inRedPortrait && ny in 0.31f..0.45f && nx in 0.17f..0.64f && (py % 3 == 0)
          when {
            inRedPortrait -> {
              y[idx] = 84
              u[idx] = 116
              v[idx] = 162
            }
            inDarkText -> {
              y[idx] = 52
              u[idx] = 136
              v[idx] = 120
            }
            else -> {
              y[idx] = 152
              u[idx] = 150
              v[idx] = 112
            }
          }
        }
      }
    }

    val detected = DocumentQuadDetector.detectFromYuvGrid(y, u, v, w, h)
    assertNotNull("Expected horizontal blue ID card on patterned mat to be detected", detected)
    val quad = detected!!
    assertTrue("Quad must be convex", quad.isConvexValid(minArea = 0.045f))
    assertTrue("Top-left X=${quad.topLeft.x} should be near 0.13", abs(quad.topLeft.x - 0.13f) < 0.07f)
    assertTrue("Top-right X=${quad.topRight.x} should be near 0.87", abs(quad.topRight.x - 0.87f) < 0.07f)
    assertTrue("Top-left Y=${quad.topLeft.y} should be near 0.28", abs(quad.topLeft.y - 0.28f) < 0.07f)
    assertTrue("Bottom-right Y=${quad.bottomRight.y} should be near 0.48 (not tall mat seam!)", abs(quad.bottomRight.y - 0.48f) < 0.07f)
  }
}
