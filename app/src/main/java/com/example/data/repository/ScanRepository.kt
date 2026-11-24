package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.local.DocumentWithPages
import com.example.data.local.ScanDao
import com.example.data.local.ScannedDocumentEntity
import com.example.data.local.ScannedPageEntity
import com.example.domain.model.DraftPage
import com.example.domain.model.EnhancementFilter
import com.example.domain.model.NormalizedCropRect
import com.example.domain.model.NormalizedQuad
import com.example.domain.model.OcrJsonSerializer
import com.example.domain.model.OcrPageResult
import com.example.processing.DocumentImageProcessor
import com.example.processing.DocumentQuadDetector
import com.example.processing.OcrEngine
import com.example.processing.SearchablePdfGenerator
import com.example.processing.SearchablePdfPageInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ScanRepository(
  private val context: Context,
  private val scanDao: ScanDao,
) {
  private val scansDir: File
    get() = File(context.filesDir, "scans").apply { mkdirs() }

  private val pdfsDir: File
    get() = File(context.filesDir, "pdfs").apply { mkdirs() }

  private val exportCacheDir: File
    get() = File(context.cacheDir, "exports").apply { mkdirs() }

  fun observeDocuments(query: String): Flow<List<ScannedDocumentEntity>> {
    val trimmed = query.trim()
    return if (trimmed.isEmpty()) {
      scanDao.observeAllDocuments()
    } else {
      scanDao.searchDocuments(trimmed)
    }
  }

  fun observeDocumentWithPages(documentId: String): Flow<DocumentWithPages?> =
    scanDao.observeDocumentWithPages(documentId)

  suspend fun getDocumentWithPages(documentId: String): DocumentWithPages? =
    scanDao.getDocumentWithPages(documentId)

  suspend fun ingestCameraCapture(
    rawTempFile: File,
    defaultFilter: EnhancementFilter = EnhancementFilter.MAGIC_COLOR,
    hintedQuad: NormalizedQuad? = null,
  ): DraftPage = withContext(Dispatchers.IO) {
    val pageId = UUID.randomUUID().toString()
    val pageDir = File(scansDir, "drafts").apply { mkdirs() }
    val rawFile = File(pageDir, "${pageId}_raw.jpg")
    val origFile = File(pageDir, "${pageId}_orig.jpg")
    val enhFile = File(pageDir, "${pageId}_enh.jpg")

    DocumentImageProcessor.saveCameraCaptureOriented(rawTempFile, rawFile)
    rawTempFile.delete()

    val detectedQuad = hintedQuad?.takeIf { it.isConvexValid() } ?: run {
      val bmp = DocumentImageProcessor.decodeSampledBitmap(rawFile.absolutePath, 320)
      DocumentQuadDetector.detectFromBitmap(bmp).also { bmp.recycle() }
    } ?: NormalizedQuad.FULL

    DocumentImageProcessor.applyPerspectiveCorrection(rawFile, origFile, detectedQuad)

    val enhancedBmp = DocumentImageProcessor.generateEnhancedImage(
      originalFile = origFile,
      destEnhancedFile = enhFile,
      filter = defaultFilter,
      rotationDegrees = 0,
      removeShadowAndGlare = true,
    )
    val ocrResult = runCatching { OcrEngine.recognizeBitmap(enhancedBmp) }.getOrNull()
    val width = enhancedBmp.width
    val height = enhancedBmp.height
    enhancedBmp.recycle()

    DraftPage(
      id = pageId,
      originalImagePath = origFile.absolutePath,
      enhancedImagePath = enhFile.absolutePath,
      filter = defaultFilter,
      rotationDegrees = 0,
      ocrResult = ocrResult ?: OcrPageResult("", emptyList(), width, height),
      isProcessing = false,
      rawImagePath = rawFile.absolutePath,
      quad = detectedQuad,
      removeShadowAndGlare = true,
    )
  }

  suspend fun ingestPhotoPickerUri(
    uri: Uri,
    defaultFilter: EnhancementFilter = EnhancementFilter.MAGIC_COLOR,
  ): DraftPage? = withContext(Dispatchers.IO) {
    val pageId = UUID.randomUUID().toString()
    val pageDir = File(scansDir, "drafts").apply { mkdirs() }
    val rawFile = File(pageDir, "${pageId}_raw.jpg")
    val origFile = File(pageDir, "${pageId}_orig.jpg")
    val enhFile = File(pageDir, "${pageId}_enh.jpg")

    DocumentImageProcessor.importUriToFile(context, uri, rawFile) ?: return@withContext null

    val detectedQuad = run {
      val bmp = DocumentImageProcessor.decodeSampledBitmap(rawFile.absolutePath, 320)
      DocumentQuadDetector.detectFromBitmap(bmp).also { bmp.recycle() }
    } ?: NormalizedQuad.FULL

    DocumentImageProcessor.applyPerspectiveCorrection(rawFile, origFile, detectedQuad)

    val enhancedBmp = DocumentImageProcessor.generateEnhancedImage(
      originalFile = origFile,
      destEnhancedFile = enhFile,
      filter = defaultFilter,
      rotationDegrees = 0,
      removeShadowAndGlare = true,
    )
    val ocrResult = runCatching { OcrEngine.recognizeBitmap(enhancedBmp) }.getOrNull()
    val width = enhancedBmp.width
    val height = enhancedBmp.height
    enhancedBmp.recycle()

    DraftPage(
      id = pageId,
      originalImagePath = origFile.absolutePath,
      enhancedImagePath = enhFile.absolutePath,
      filter = defaultFilter,
      rotationDegrees = 0,
      ocrResult = ocrResult ?: OcrPageResult("", emptyList(), width, height),
      isProcessing = false,
      rawImagePath = rawFile.absolutePath,
      quad = detectedQuad,
      removeShadowAndGlare = true,
    )
  }

  suspend fun reprocessPage(
    page: DraftPage,
    filter: EnhancementFilter = page.filter,
    rotationDegrees: Int = page.rotationDegrees,
    cropRect: NormalizedCropRect? = null,
    quad: NormalizedQuad? = null,
    removeShadowAndGlare: Boolean = page.removeShadowAndGlare,
  ): DraftPage = withContext(Dispatchers.IO) {
    val rawFile = File(page.rawImagePath).takeIf { it.exists() } ?: File(page.originalImagePath)
    val origFile = File(page.originalImagePath)
    val enhFile = File(page.enhancedImagePath)

    var nextRotation = rotationDegrees
    var nextQuad = page.quad

    if (quad != null) {
      DocumentImageProcessor.applyPerspectiveCorrection(rawFile, origFile, quad)
      nextQuad = quad
      nextRotation = 0
    } else if (cropRect != null && !cropRect.isFullImage) {
      if (nextRotation % 360 != 0) {
        val rotatedTemp = DocumentImageProcessor.generateEnhancedImage(
          originalFile = origFile,
          destEnhancedFile = origFile,
          filter = EnhancementFilter.ORIGINAL,
          rotationDegrees = nextRotation,
          removeShadowAndGlare = false,
        )
        rotatedTemp.recycle()
        nextRotation = 0
      }
      DocumentImageProcessor.cropSourceImage(origFile, cropRect)
    }

    val enhancedBmp = DocumentImageProcessor.generateEnhancedImage(
      originalFile = origFile,
      destEnhancedFile = enhFile,
      filter = filter,
      rotationDegrees = nextRotation,
      removeShadowAndGlare = removeShadowAndGlare,
    )
    val ocrResult = runCatching { OcrEngine.recognizeBitmap(enhancedBmp) }.getOrNull()
    val width = enhancedBmp.width
    val height = enhancedBmp.height
    enhancedBmp.recycle()

    page.copy(
      filter = filter,
      rotationDegrees = nextRotation,
      ocrResult = ocrResult ?: OcrPageResult("", emptyList(), width, height),
      isProcessing = false,
      quad = nextQuad,
      removeShadowAndGlare = removeShadowAndGlare,
    )
  }

  suspend fun saveBatchDocument(
    existingDocId: String?,
    title: String,
    draftPages: List<DraftPage>,
  ): String = withContext(Dispatchers.IO) {
    require(draftPages.isNotEmpty()) { "At least one page is required." }

    val docId = existingDocId ?: UUID.randomUUID().toString()
    val docFolder = File(scansDir, docId).apply { mkdirs() }
    val existing = existingDocId?.let { scanDao.getDocumentWithPages(it) }
    val now = System.currentTimeMillis()
    val createdAt = existing?.document?.createdAt ?: now

    val finalizedPages = mutableListOf<ScannedPageEntity>()
    val pdfInputs = mutableListOf<SearchablePdfPageInput>()

    for ((index, draft) in draftPages.withIndex()) {
      val targetOrig = File(docFolder, "${draft.id}_orig.jpg")
      val targetEnh = File(docFolder, "${draft.id}_enh.jpg")

      val srcRaw = File(draft.rawImagePath)
      val srcOrig = File(draft.originalImagePath)
      val srcEnh = File(draft.enhancedImagePath)
      if (srcOrig.absolutePath != targetOrig.absolutePath && srcOrig.exists()) {
        srcOrig.copyTo(targetOrig, overwrite = true)
        srcOrig.delete()
      }
      if (srcEnh.absolutePath != targetEnh.absolutePath && srcEnh.exists()) {
        srcEnh.copyTo(targetEnh, overwrite = true)
        srcEnh.delete()
      }
      if (srcRaw.absolutePath.contains("/drafts/") && srcRaw.exists()) {
        srcRaw.delete()
      }

      val ocr = draft.ocrResult ?: run {
        val bmp = DocumentImageProcessor.decodeSampledBitmap(targetEnh.absolutePath, 2000)
        val res = runCatching { OcrEngine.recognizeBitmap(bmp) }
          .getOrDefault(OcrPageResult("", emptyList(), bmp.width, bmp.height))
        bmp.recycle()
        res
      }

      pdfInputs.add(
        SearchablePdfPageInput(
          imagePath = targetEnh.absolutePath,
          imageWidth = ocr.imageWidth,
          imageHeight = ocr.imageHeight,
          ocrLines = ocr.lines,
          formattedText = ocr.formattedText,
        )
      )

      finalizedPages.add(
        ScannedPageEntity(
          id = draft.id,
          documentId = docId,
          pageOrder = index,
          originalImagePath = targetOrig.absolutePath,
          enhancedImagePath = targetEnh.absolutePath,
          filterName = draft.filter.name,
          rotationDegrees = draft.rotationDegrees,
          ocrText = ocr.formattedText,
          ocrLinesJson = OcrJsonSerializer.encodeLines(ocr.lines),
          imageWidth = ocr.imageWidth,
          imageHeight = ocr.imageHeight,
        )
      )
    }

    val pdfFile = File(pdfsDir, "$docId.pdf")
    SearchablePdfGenerator.generateSearchablePdf(pdfInputs, pdfFile)

    val combinedOcr = finalizedPages
      .mapIndexed { i, p ->
        if (finalizedPages.size > 1) "--- Page ${i + 1} ---\n${p.ocrText}" else p.ocrText
      }
      .joinToString("\n\n")
      .trim()

    val docEntity = ScannedDocumentEntity(
      id = docId,
      title = title.trim().ifBlank { "Scanned Document" },
      createdAt = createdAt,
      updatedAt = now,
      pageCount = finalizedPages.size,
      pdfPath = pdfFile.absolutePath,
      thumbnailPath = finalizedPages.first().enhancedImagePath,
      fullOcrText = combinedOcr,
    )

    scanDao.replaceDocumentAndPages(docEntity, finalizedPages)
    docId
  }

  suspend fun generateTextOnlyPdfFile(docWithPages: DocumentWithPages): File =
    withContext(Dispatchers.IO) {
      val orderedPages = docWithPages.pages.sortedBy { it.pageOrder }
      val inputs = orderedPages.map { p ->
        SearchablePdfPageInput(
          imagePath = p.enhancedImagePath,
          imageWidth = p.imageWidth,
          imageHeight = p.imageHeight,
          ocrLines = OcrJsonSerializer.decodeLines(p.ocrLinesJson),
          formattedText = p.ocrText,
        )
      }
      val out = File(exportCacheDir, "${docWithPages.document.id}_text.pdf")
      SearchablePdfGenerator.generateTextOnlyPdf(inputs, out, docWithPages.document.title)
    }

  suspend fun deleteDocument(documentId: String) = withContext(Dispatchers.IO) {
    val existing = scanDao.getDocumentWithPages(documentId)
    scanDao.deleteDocumentById(documentId)
    if (existing != null) {
      File(existing.document.pdfPath).delete()
      File(scansDir, documentId).deleteRecursively()
    }
  }

  suspend fun discardDraftPages(pages: List<DraftPage>) = withContext(Dispatchers.IO) {
    for (page in pages) {
      if (page.rawImagePath.contains("/drafts/")) {
        File(page.rawImagePath).delete()
      }
      if (page.originalImagePath.contains("/drafts/")) {
        File(page.originalImagePath).delete()
      }
      if (page.enhancedImagePath.contains("/drafts/")) {
        File(page.enhancedImagePath).delete()
      }
    }
  }
}
