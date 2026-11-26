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
import com.example.processing.ImageProcessor
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

  @Test
  fun documentQuadDetectorLocksOntoTiltedIdBadgeOnFloralTealSheetAndIgnoresCreamPetal() {
    val w = 120
    val h = 160
    val y = IntArray(w * h)
    val u = IntArray(w * h)
    val v = IntArray(w * h)

    // Teal & cream floral bedsheet with curved bottom-right cream petal reaching x=1.0
    for (py in 0 until h) {
      val ny = py.toFloat() / h
      for (px in 0 until w) {
        val nx = px.toFloat() / w
        val idx = py * w + px
        val petalDist = kotlin.math.hypot((nx - 0.85f) * 1.1f, (ny - 0.73f) * 1.4f)
        val inBottomRightPetal = petalDist < 0.28f && nx >= 0.56f && ny >= 0.56f
        val inTopLeftPetal = kotlin.math.hypot((nx - 0.14f), (ny - 0.55f)) < 0.16f
        when {
          inBottomRightPetal || inTopLeftPetal -> {
            y[idx] = 188
            u[idx] = 124
            v[idx] = 126
          }
          else -> {
            y[idx] = 92
            u[idx] = 142
            v[idx] = 104
          }
        }
      }
    }

    // Black lanyard above card
    for (py in 0..(0.31f * h).toInt()) {
      for (px in (0.55f * w).toInt()..(0.66f * w).toInt()) {
        val idx = py * w + px
        y[idx] = 30
        u[idx] = 128
        v[idx] = 128
      }
    }

    // Tilted (-10 deg) ID badge in center: gray plastic frame, sky-blue top, white photo diamond, navy bottom
    val angle = -0.17f
    val cosA = kotlin.math.cos(angle)
    val sinA = kotlin.math.sin(angle)
    val cx = 0.51f
    val cy = 0.46f
    val halfW = 0.21f
    val halfH = 0.16f
    for (py in 0 until h) {
      val ny = py.toFloat() / h
      for (px in 0 until w) {
        val nx = px.toFloat() / w
        val dx = nx - cx
        val dy = (ny - cy) * (h.toFloat() / w.toFloat())
        val lx = dx * cosA + dy * sinA
        val ly = -dx * sinA + dy * cosA
        val normY = ly / (h.toFloat() / w.toFloat())

        if (abs(lx) <= halfW && abs(normY) <= halfH) {
          val idx = py * w + px
          val isPlasticRim = abs(lx) > halfW - 0.025f || abs(normY) > halfH - 0.022f
          val inDiamond = abs(lx) + abs(normY) < 0.09f
          val inBottomNavy = normY > 0.02f
          when {
            isPlasticRim -> {
              y[idx] = 152
              u[idx] = 126
              v[idx] = 127
            }
            inDiamond -> {
              y[idx] = 205
              u[idx] = 128
              v[idx] = 129
            }
            inBottomNavy -> {
              y[idx] = 44
              u[idx] = 148
              v[idx] = 118
            }
            else -> {
              y[idx] = 114
              u[idx] = 156
              v[idx] = 104
            }
          }
        }
      }
    }

    val detected = DocumentQuadDetector.detectFromYuvGrid(y, u, v, w, h)
    assertNotNull("Expected tilted ID badge on floral teal sheet to be detected", detected)
    val quad = detected!!
    val qCenterX = (quad.topLeft.x + quad.topRight.x + quad.bottomRight.x + quad.bottomLeft.x) * 0.25f
    val qCenterY = (quad.topLeft.y + quad.topRight.y + quad.bottomRight.y + quad.bottomLeft.y) * 0.25f
    assertTrue("Detected centerX=$qCenterX should be on center ID badge (~0.51), not floral edge", abs(qCenterX - 0.51f) < 0.09f)
    assertTrue("Detected centerY=$qCenterY should be on center ID badge (~0.46), not bottom petal", abs(qCenterY - 0.46f) < 0.09f)
  }

  @Test
  fun quadStabilityTrackerHysteresisPreventsJumpingOnOutliers() {
    val tracker = QuadStabilityTracker(
      stabilityWindowMs = 800L,
      autoCaptureCooldownMs = 2000L,
      maxCornerDrift = 0.03f,
      switchConfirmationFrames = 4,
      acquireConfirmationFrames = 1,
      jitterDeadband = 0.015f,
    )
    val lockedDoc = NormalizedQuad(
      topLeft = NormalizedPoint(0.25f, 0.28f),
      topRight = NormalizedPoint(0.75f, 0.28f),
      bottomRight = NormalizedPoint(0.75f, 0.64f),
      bottomLeft = NormalizedPoint(0.25f, 0.64f),
      confidence = 0.8f,
    )
    val outlierPetal = NormalizedQuad(
      topLeft = NormalizedPoint(0.58f, 0.56f),
      topRight = NormalizedPoint(0.98f, 0.58f),
      bottomRight = NormalizedPoint(0.98f, 0.85f),
      bottomLeft = NormalizedPoint(0.55f, 0.80f),
      confidence = 0.7f,
    )

    tracker.update(lockedDoc, 1000L)
    assertNotNull(tracker.smoothedQuad)

    // Micro-jitter inside deadband freezes exact coordinates
    val microJitter = lockedDoc.copy(topLeft = NormalizedPoint(0.255f, 0.285f))
    tracker.update(microJitter, 1040L)
    assertEquals(0.25f, tracker.smoothedQuad!!.topLeft.x, 0.0001f)

    // 2 outlier frames should NOT yank lock away from lockedDoc
    tracker.update(outlierPetal, 1080L)
    tracker.update(outlierPetal, 1120L)
    assertEquals(0.25f, tracker.smoothedQuad!!.topLeft.x, 0.0001f)
  }

  @Test
  fun documentImageProcessorDetectsCurvedDeskDipWithoutCuttingFlatTextMargin() {
    val w = 80
    val h = 100
    val paperWhite = (0xFF shl 24) or (235 shl 16) or (232 shl 8) or 236
    val blueDesk = (0xFF shl 24) or (30 shl 16) or (135 shl 8) or 225
    val blueLogoInk = (0xFF shl 24) or (25 shl 16) or (95 shl 8) or 185

    // Case 1: Bowed/curled paper where top middle dips 7% showing blue desk
    val curvedPixels = IntArray(w * h) { paperWhite }
    for (x in 0 until w) {
      val nx = x.toFloat() / (w - 1)
      val sagFrac = 0.07f * kotlin.math.sin(nx * Math.PI).toFloat()
      val sagPx = (sagFrac * h).toInt()
      for (y in 0 until sagPx) {
        curvedPixels[y * w + x] = blueDesk
      }
    }
    val curvedMargins = DocumentImageProcessor.detectCurvedEdgeMargins(curvedPixels, w, h, meshDivs = 8)
    assertTrue("Curved dip should trigger mesh straightening", curvedMargins.hasAdjustment)
    assertTrue("Center top column should trim ~0.06..0.08 (was ${curvedMargins.topFrac[4]})", curvedMargins.topFrac[4] in 0.04f..0.095f)
    assertTrue("Outer edge column should stay near 0 (was ${curvedMargins.topFrac[0]})", curvedMargins.topFrac[0] < 0.025f)

    // Case 2: Clean flat paper with blue logo inside top margin
    val flatPixels = IntArray(w * h) { paperWhite }
    for (y in 6..18) {
      for (x in 20..60) {
        flatPixels[y * w + x] = blueLogoInk
      }
    }
    val flatMargins = DocumentImageProcessor.detectCurvedEdgeMargins(flatPixels, w, h, meshDivs = 8)
    assertFalse("Clean flat sheet should not trigger spurious border warp", flatMargins.hasAdjustment)
  }

  @Test
  fun imageProcessorBleachesWatermarksAndPinkCreasesWhilePreservingBlackTextAndBlueLogos() {
    val w = 96
    val h = 96
    val pixels = IntArray(w * h)

    for (y in 0 until h) {
      for (x in 0 until w) {
        // Paper with pink/gray fold gradient on left/middle
        val crease = if (x in 18..36) 24 else 0
        var r = 236 - crease / 2
        var g = 224 - crease
        var b = 232 - crease / 2

        // Faint repeating light-blue security watermark across paper
        if ((x + y) % 14 in 0..2 && y in 12..84) {
          r = 212
          g = 228
          b = 244
        }
        pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      }
    }

    // Jet-black body text strokes
    for (x in 15..75) {
      pixels[62 * w + x] = (0xFF shl 24) or (54 shl 16) or (54 shl 8) or 58
      pixels[63 * w + x] = (0xFF shl 24) or (60 shl 16) or (60 shl 8) or 62
    }

    // Vivid blue brand logo block
    for (y in 20..30) {
      for (x in 20..50) {
        pixels[y * w + x] = (0xFF shl 24) or (34 shl 16) or (106 shl 8) or 208
      }
    }

    // Thin medium-light gray thermal receipt stroke must stay clearly dark & legible (not erased!)
    for (x in 25..65) {
      pixels[72 * w + x] = (0xFF shl 24) or (162 shl 16) or (162 shl 8) or 165
    }

    val out = ImageProcessor().enhanceMagicColorBuffer(pixels, w, h, removeShadowAndGlare = true)

    // Paper & watermark region should bleach to pure #FFFFFF
    val wmPixel = out[45 * w + 45]
    assertEquals("Faint watermark should bleach to pure white paper", 0xFFFFFF, wmPixel and 0xFFFFFF)

    // Pink crease region should normalize & bleach cleanly to near/pure white
    val creasePixel = out[40 * w + 26]
    val cr = (creasePixel ushr 16) and 0xFF
    val cg = (creasePixel ushr 8) and 0xFF
    val cb = creasePixel and 0xFF
    assertTrue("Crease paper must be clean white (RGB=$cr,$cg,$cb)", cr >= 245 && cg >= 245 && cb >= 245)

    // Black text must compress firmly dark
    val textPixel = out[62 * w + 40]
    val textLum = (((textPixel ushr 16) and 0xFF) + ((textPixel ushr 8) and 0xFF) + (textPixel and 0xFF)) / 3
    assertTrue("Body text must stay dark & sharp (lum=$textLum)", textLum < 50)

    // Thin thermal receipt text stroke must remain clearly legible & darkened
    val thinPixel = out[72 * w + 45]
    val thinLum = (((thinPixel ushr 16) and 0xFF) + ((thinPixel ushr 8) and 0xFF) + (thinPixel and 0xFF)) / 3
    assertTrue("Thin gray text stroke must NOT be erased (lum=$thinLum)", thinLum < 150)

    // Blue logo must stay vividly blue (B much stronger than R)
    val logoPixel = out[25 * w + 35]
    val lr = (logoPixel ushr 16) and 0xFF
    val lb = logoPixel and 0xFF
    assertTrue("Blue logo must remain vivid blue (R=$lr, B=$lb)", lb - lr >= 110)
  }

  @Test
  fun documentImageProcessorDoesNotBendStraightEdgesOnSubtlePaperShadow() {
    val w = 96
    val h = 120
    val paperWhite = (0xFF shl 24) or (242 shl 16) or (242 shl 8) or 244
    val mildCornerShadow = (0xFF shl 24) or (132 shl 16) or (134 shl 8) or 136
    val pixels = IntArray(w * h) { paperWhite }
    // Subtle gray shadow along top-left corner (still paper, NOT colored desk)
    for (y in 0 until 8) {
      for (x in 0 until 20) {
        pixels[y * w + x] = mildCornerShadow
      }
    }
    val margins = DocumentImageProcessor.detectCurvedEdgeMargins(pixels, w, h, meshDivs = 8)
    assertFalse("Subtle corner shadow must NOT bend straight paper borders", margins.hasAdjustment)
  }

  @Test
  fun documentImageProcessorCleansDeepDeskMarginsAndCornerTriangles() {
    val w = 96
    val h = 120
    val paperWhite = (0xFF shl 24) or (242 shl 16) or (242 shl 8) or 244
    val greenDesk = (0xFF shl 24) or (78 shl 16) or (122 shl 8) or 86

    // Simulate 14% curved dip along top
    val deepCurve = IntArray(w * h) { paperWhite }
    for (x in 0 until w) {
      val nx = x.toFloat() / (w - 1)
      val dipPx = (0.14f * kotlin.math.sin(nx * Math.PI).toFloat() * h).toInt()
      for (y in 0 until dipPx) {
        deepCurve[y * w + x] = greenDesk
      }
    }
    val margins = DocumentImageProcessor.detectCurvedEdgeMargins(deepCurve, w, h, meshDivs = 8)
    assertTrue("14% curved dip should be detected", margins.hasAdjustment)
    assertTrue("Center column top margin=${margins.topFrac[4]} should detect >0.11", margins.topFrac[4] > 0.11f)

    // Simulate tiny residual green corner triangle inside outer 3% perimeter
    val cornerImg = IntArray(w * h) { paperWhite }
    cornerImg[0] = greenDesk
    cornerImg[1] = greenDesk
    cornerImg[w] = greenDesk
    val cleaned = DocumentImageProcessor.cleanResidualDeskCornersBuffer(cornerImg, w, h)
    assertTrue("Residual corner triangle should be cleaned", cleaned)
    assertEquals(0xFFFFFFFF.toInt(), cornerImg[0])
    assertEquals(0xFFFFFFFF.toInt(), cornerImg[1])
  }

  @Test
  fun smartScanAdvisorRejectsMaskedOcrNoiseSuggestsShadowRemovalAndExtractsIndonesianPhones() {
    val draft = DraftPage(
      id = "p_receipt",
      originalImagePath = "",
      enhancedImagePath = "",
      filter = EnhancementFilter.MAGIC_COLOR,
      removeShadowAndGlare = false,
      ocrResult = OcrPageResult(
        formattedText = "a***ka b***y\naca *y\nSEMAR Nusantara\nTELP. (0272) 897479\nTOTAL Rp 575,500.00",
        lines = listOf(
          OcrLineBox("a***ka b***y", 0.6f, 0.05f, 0.9f, 0.09f, emptyList()),
          OcrLineBox("aca *y", 0.6f, 0.10f, 0.8f, 0.13f, emptyList()),
          OcrLineBox("Rec", 0.1f, 0.12f, 0.2f, 0.15f, emptyList()),
          OcrLineBox("SEMAR Nusantara", 0.2f, 0.14f, 0.5f, 0.20f, emptyList()),
        ),
        imageWidth = 1000,
        imageHeight = 1400,
      ),
    )

    val titles = SmartScanAdvisor.suggestTitles(listOf(draft))
    assertFalse("Masked PII must not appear in title suggestions: $titles", titles.any { it.contains("*") })
    assertFalse("Tiny fragment 'Rec' must not appear in title suggestions: $titles", titles.contains("Rec"))
    assertTrue("Clean store title should be promoted: $titles", titles.contains("SEMAR Nusantara"))

    val pageSuggestions = SmartScanAdvisor.suggestForPage(
      page = draft,
      quality = null,
    )
    assertTrue(
      "Should suggest enabling shadow removal when disabled",
      pageSuggestions.any { it is PageSuggestion.EnableShadowRemoval },
    )

    val actions = SmartScanAdvisor.extractDocumentActions(draft.ocrResult!!.formattedText)
    assertTrue(
      "Should extract Indonesian landline (0272) 897479",
      actions.any { it.type == DocumentActionType.PHONE && it.value.contains("0272") },
    )
    assertTrue(
      "Should extract Rp 575,500.00",
      actions.any { it.type == DocumentActionType.AMOUNT && it.value.contains("575,500.00") },
    )
  }
}
