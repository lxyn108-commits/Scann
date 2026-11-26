package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.ClearScanDatabase
import com.example.data.local.DocumentWithPages
import com.example.data.local.ScannedDocumentEntity
import com.example.data.repository.ScanRepository
import com.example.domain.model.DraftPage
import com.example.domain.model.EnhancementFilter
import com.example.domain.model.NormalizedCropRect
import com.example.domain.model.NormalizedQuad
import com.example.domain.model.OcrJsonSerializer
import com.example.domain.model.OcrPageResult
import com.example.processing.DocumentImageProcessor
import com.example.processing.ExportManager
import com.example.processing.PageSuggestion
import com.example.processing.ScanPreset
import com.example.processing.SmartScanAdvisor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BatchReviewUiState(
  val editingDocumentId: String? = null,
  val title: String = "",
  val activePreset: ScanPreset = ScanPreset.DOCUMENT,
  val pages: List<DraftPage> = emptyList(),
  val selectedPageIndex: Int = 0,
  val retakingPageIndex: Int? = null,
  val suggestedTitles: List<String> = emptyList(),
  val pageSuggestions: Map<String, List<PageSuggestion>> = emptyMap(),
  val isImportingOrCapturing: Boolean = false,
  val isSavingPdf: Boolean = false,
  val processingMessage: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
  private val repository: ScanRepository

  init {
    val db = ClearScanDatabase.getInstance(application)
    repository = ScanRepository(application.applicationContext, db.scanDao())
  }

  private val _searchQuery = MutableStateFlow("")
  val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

  @OptIn(ExperimentalCoroutinesApi::class)
  val documents: StateFlow<List<ScannedDocumentEntity>> =
    _searchQuery
      .flatMapLatest { query -> repository.observeDocuments(query) }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

  private val _batchState = MutableStateFlow(BatchReviewUiState())
  val batchState: StateFlow<BatchReviewUiState> = _batchState.asStateFlow()

  private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
  val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

  fun updateSearchQuery(query: String) {
    _searchQuery.value = query
  }

  fun startNewBatchSession() {
    val defaultTitle = "Scan ${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())}"
    viewModelScope.launch {
      repository.discardDraftPages(_batchState.value.pages)
      _batchState.value = BatchReviewUiState(title = defaultTitle)
    }
  }

  fun selectScanPreset(preset: ScanPreset) {
    _batchState.update { state ->
      val updatedTitle = if (state.pages.isEmpty() && state.title.startsWith("Scan ")) {
        "${preset.titlePrefix} ${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())}"
      } else {
        state.title
      }
      state.copy(activePreset = preset, title = updatedTitle)
    }
  }

  fun openDocumentForEditing(documentId: String, onReady: () -> Unit) {
    viewModelScope.launch {
      val doc = repository.getDocumentWithPages(documentId) ?: return@launch
      val drafts = doc.pages.sortedBy { it.pageOrder }.map { p ->
        DraftPage(
          id = p.id,
          originalImagePath = p.originalImagePath,
          enhancedImagePath = p.enhancedImagePath,
          filter = runCatching { EnhancementFilter.valueOf(p.filterName) }
            .getOrDefault(EnhancementFilter.MAGIC_COLOR),
          rotationDegrees = p.rotationDegrees,
          ocrResult = OcrPageResult(
            formattedText = p.ocrText,
            lines = OcrJsonSerializer.decodeLines(p.ocrLinesJson),
            imageWidth = p.imageWidth,
            imageHeight = p.imageHeight,
          ),
          isProcessing = false,
        )
      }
      _batchState.value = BatchReviewUiState(
        editingDocumentId = doc.document.id,
        title = doc.document.title,
        pages = drafts,
        selectedPageIndex = 0,
        suggestedTitles = SmartScanAdvisor.suggestTitles(drafts),
      )
      drafts.forEach { refreshSuggestionsForPage(it) }
      onReady()
    }
  }

  fun prepareRetakePage(index: Int) {
    _batchState.update { state ->
      if (index in state.pages.indices) state.copy(retakingPageIndex = index) else state
    }
  }

  fun clearRetakePage() {
    _batchState.update { it.copy(retakingPageIndex = null) }
  }

  fun onPhotoCaptured(
    tempFile: File,
    hintedQuad: NormalizedQuad? = null,
    onCompleted: (() -> Unit)? = null,
  ) {
    val stateSnapshot = _batchState.value
    val presetFilter = stateSnapshot.activePreset.filter
    val retakeIdx = stateSnapshot.retakingPageIndex
    viewModelScope.launch {
      _batchState.update {
        it.copy(
          isImportingOrCapturing = true,
          processingMessage = "Meluruskan & memindai OCR…",
        )
      }
      runCatching {
        repository.ingestCameraCapture(
          rawTempFile = tempFile,
          defaultFilter = presetFilter,
          hintedQuad = hintedQuad,
        )
      }.onSuccess { draftPage ->
        _batchState.update { current ->
          val nextPages = if (retakeIdx != null && retakeIdx in current.pages.indices) {
            current.pages.toMutableList().apply { set(retakeIdx, draftPage) }
          } else {
            current.pages + draftPage
          }
          val nextSelected = if (retakeIdx != null && retakeIdx in nextPages.indices) {
            retakeIdx
          } else {
            nextPages.lastIndex
          }
          val suggestedTitle = suggestTitleIfDefault(current.title, nextPages)
          current.copy(
            title = suggestedTitle,
            pages = nextPages,
            selectedPageIndex = nextSelected,
            retakingPageIndex = null,
            suggestedTitles = SmartScanAdvisor.suggestTitles(nextPages),
            isImportingOrCapturing = false,
            processingMessage = null,
          )
        }
        refreshSuggestionsForPage(draftPage)
        onCompleted?.invoke()
      }.onFailure { err ->
        _batchState.update {
          it.copy(isImportingOrCapturing = false, processingMessage = null, retakingPageIndex = null)
        }
        _snackbarMessages.tryEmit("Gagal memotret: ${err.localizedMessage ?: "Terjadi kesalahan"}")
      }
    }
  }

  fun onImportPhotoUris(uris: List<Uri>, onImported: (() -> Unit)? = null) {
    if (uris.isEmpty()) return
    val presetFilter = _batchState.value.activePreset.filter
    viewModelScope.launch {
      _batchState.update {
        it.copy(
          isImportingOrCapturing = true,
          processingMessage = "Mengimpor ${uris.size} halaman & memindai OCR…",
        )
      }
      val newPages = mutableListOf<DraftPage>()
      for (uri in uris) {
        val page = runCatching {
          repository.ingestPhotoPickerUri(uri, defaultFilter = presetFilter)
        }.getOrNull()
        if (page != null) newPages.add(page)
      }
      if (newPages.isEmpty()) {
        _batchState.update { it.copy(isImportingOrCapturing = false, processingMessage = null) }
        _snackbarMessages.tryEmit("Tidak dapat membaca gambar yang dipilih.")
        return@launch
      }
      _batchState.update { current ->
        val nextPages = current.pages + newPages
        val baseTitle = current.title.ifBlank {
          "Scan ${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())}"
        }
        val suggestedTitle = suggestTitleIfDefault(baseTitle, nextPages)
        current.copy(
          title = suggestedTitle,
          pages = nextPages,
          selectedPageIndex = (nextPages.size - newPages.size).coerceAtLeast(0),
          suggestedTitles = SmartScanAdvisor.suggestTitles(nextPages),
          isImportingOrCapturing = false,
          processingMessage = null,
        )
      }
      newPages.forEach { refreshSuggestionsForPage(it) }
      onImported?.invoke()
    }
  }

  fun updateDraftTitle(title: String) {
    _batchState.update { it.copy(title = title) }
  }

  fun selectBatchPage(index: Int) {
    _batchState.update { state ->
      if (index in state.pages.indices) state.copy(selectedPageIndex = index) else state
    }
  }

  fun removeBatchPage(index: Int) {
    _batchState.update { state ->
      if (index !in state.pages.indices) return@update state
      val next = state.pages.toMutableList().apply { removeAt(index) }
      val nextIndex = state.selectedPageIndex.coerceAtMost((next.size - 1).coerceAtLeast(0))
      state.copy(pages = next, selectedPageIndex = nextIndex)
    }
  }

  fun moveBatchPage(fromIndex: Int, delta: Int) {
    _batchState.update { state ->
      val toIndex = fromIndex + delta
      if (fromIndex !in state.pages.indices || toIndex !in state.pages.indices) return@update state
      val next = state.pages.toMutableList()
      val item = next.removeAt(fromIndex)
      next.add(toIndex, item)
      state.copy(pages = next, selectedPageIndex = toIndex)
    }
  }

  fun applySuggestion(suggestion: PageSuggestion) {
    when (suggestion) {
      is PageSuggestion.ApplyFilter -> applyFilterToSelectedPage(suggestion.filter)
      is PageSuggestion.AutoTrimBorders -> cropSelectedPage(suggestion.cropRect)
      is PageSuggestion.AutoStraighten -> autoStraightenSelectedPage()
      is PageSuggestion.EnableShadowRemoval -> {
        val target = _batchState.value.pages.getOrNull(_batchState.value.selectedPageIndex)
        if (target != null && !target.removeShadowAndGlare) {
          toggleShadowRemovalForSelectedPage()
        }
      }
      is PageSuggestion.RotatePage -> rotateSelectedPage(suggestion.quarterTurns)
    }
  }

  fun applyAllSuggestionsForSelectedPage() {
    val state = _batchState.value
    val target = state.pages.getOrNull(state.selectedPageIndex) ?: return
    val suggestions = state.pageSuggestions[target.id].orEmpty()
    if (suggestions.isEmpty() || target.isProcessing) return

    viewModelScope.launch {
      setPageProcessing(target.id, true)
      var working = target
      var nextFilter = target.filter
      var nextRotation = target.rotationDegrees
      var nextShadowRemoval = target.removeShadowAndGlare
      var trimRect: com.example.domain.model.NormalizedCropRect? = null
      var redetectQuad: NormalizedQuad? = null

      for (suggestion in suggestions) {
        when (suggestion) {
          is PageSuggestion.AutoTrimBorders -> trimRect = suggestion.cropRect
          is PageSuggestion.AutoStraighten -> {
            val rawFile = File(target.rawImagePath).takeIf { it.exists() } ?: File(target.originalImagePath)
            redetectQuad = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
              runCatching {
                val bmp = DocumentImageProcessor.decodeSampledBitmap(rawFile.absolutePath, 480)
                com.example.processing.DocumentQuadDetector.detectFromBitmap(bmp, 320).also { bmp.recycle() }
              }.getOrNull()
            }
          }
          is PageSuggestion.EnableShadowRemoval -> nextShadowRemoval = true
          is PageSuggestion.RotatePage -> nextRotation = (nextRotation + 90 * suggestion.quarterTurns) % 360
          is PageSuggestion.ApplyFilter -> nextFilter = suggestion.filter
        }
      }

      if (nextShadowRemoval && nextFilter == EnhancementFilter.ORIGINAL) {
        nextFilter = EnhancementFilter.MAGIC_COLOR
      }

      working = runCatching {
        repository.reprocessPage(
          page = working,
          filter = nextFilter,
          rotationDegrees = nextRotation,
          cropRect = if (redetectQuad == null) trimRect else null,
          quad = redetectQuad,
          removeShadowAndGlare = nextShadowRemoval,
        )
      }.getOrElse { working.copy(isProcessing = false) }

      replacePageById(working)
      refreshSuggestionsForPage(working)
    }
  }

  private fun autoStraightenSelectedPage() {
    val state = _batchState.value
    val target = state.pages.getOrNull(state.selectedPageIndex) ?: return
    if (target.isProcessing) return

    viewModelScope.launch {
      setPageProcessing(target.id, true)
      val rawFile = File(target.rawImagePath).takeIf { it.exists() } ?: File(target.originalImagePath)
      val redetected = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        runCatching {
          val bmp = DocumentImageProcessor.decodeSampledBitmap(rawFile.absolutePath, 480)
          com.example.processing.DocumentQuadDetector.detectFromBitmap(bmp, 320).also { bmp.recycle() }
        }.getOrNull()
      }
      val updated = if (redetected != null && redetected.isConvexValid(minArea = 0.04f)) {
        runCatching { repository.reprocessPage(target, quad = redetected) }
          .getOrElse { target.copy(isProcessing = false) }
      } else {
        target.copy(isProcessing = false)
      }
      replacePageById(updated)
      refreshSuggestionsForPage(updated)
    }
  }

  fun applyFilterToSelectedPage(filter: EnhancementFilter) {
    val state = _batchState.value
    val idx = state.selectedPageIndex
    val target = state.pages.getOrNull(idx) ?: return
    if (target.filter == filter || target.isProcessing) return

    viewModelScope.launch {
      setPageProcessing(target.id, true)
      val updated = runCatching {
        repository.reprocessPage(target, filter = filter)
      }.getOrElse { target.copy(isProcessing = false) }
      replacePageById(updated)
      refreshSuggestionsForPage(updated)
    }
  }

  fun applyFilterToAllPages(filter: EnhancementFilter) {
    val state = _batchState.value
    if (state.pages.isEmpty()) return
    viewModelScope.launch {
      _batchState.update {
        it.copy(isImportingOrCapturing = true, processingMessage = "Menerapkan ${filter.displayName} ke semua halaman…")
      }
      val updatedList = state.pages.map { page ->
        runCatching { repository.reprocessPage(page, filter = filter) }.getOrDefault(page)
      }
      _batchState.update {
        it.copy(
          pages = updatedList,
          suggestedTitles = SmartScanAdvisor.suggestTitles(updatedList),
          isImportingOrCapturing = false,
          processingMessage = null,
        )
      }
      updatedList.forEach { refreshSuggestionsForPage(it) }
    }
  }

  fun rotateSelectedPage(quarterTurns: Int = 1) {
    val state = _batchState.value
    val target = state.pages.getOrNull(state.selectedPageIndex) ?: return
    if (target.isProcessing) return

    viewModelScope.launch {
      setPageProcessing(target.id, true)
      val nextRot = (target.rotationDegrees + 90 * quarterTurns) % 360
      val updated = runCatching {
        repository.reprocessPage(target, rotationDegrees = nextRot)
      }.getOrElse { target.copy(isProcessing = false) }
      replacePageById(updated)
      refreshSuggestionsForPage(updated)
    }
  }

  fun cropSelectedPage(cropRect: NormalizedCropRect) {
    val state = _batchState.value
    val target = state.pages.getOrNull(state.selectedPageIndex) ?: return
    if (target.isProcessing || cropRect.isFullImage) return

    viewModelScope.launch {
      setPageProcessing(target.id, true)
      val updated = runCatching {
        repository.reprocessPage(target, cropRect = cropRect)
      }.getOrElse { target.copy(isProcessing = false) }
      replacePageById(updated)
      refreshSuggestionsForPage(updated)
    }
  }

  fun applyPerspectiveToSelectedPage(quad: NormalizedQuad) {
    val state = _batchState.value
    val target = state.pages.getOrNull(state.selectedPageIndex) ?: return
    if (target.isProcessing || !quad.isConvexValid(minArea = 0.04f)) return

    viewModelScope.launch {
      setPageProcessing(target.id, true)
      val updated = runCatching {
        repository.reprocessPage(target, quad = quad)
      }.getOrElse { target.copy(isProcessing = false) }
      replacePageById(updated)
      refreshSuggestionsForPage(updated)
    }
  }

  fun toggleShadowRemovalForSelectedPage() {
    val state = _batchState.value
    val target = state.pages.getOrNull(state.selectedPageIndex) ?: return
    if (target.isProcessing) return

    val nextShadowMode = !target.removeShadowAndGlare
    val nextFilter = if (nextShadowMode && target.filter == EnhancementFilter.ORIGINAL) {
      EnhancementFilter.MAGIC_COLOR
    } else {
      target.filter
    }

    viewModelScope.launch {
      setPageProcessing(target.id, true)
      val updated = runCatching {
        repository.reprocessPage(
          page = target,
          filter = nextFilter,
          removeShadowAndGlare = nextShadowMode,
        )
      }.getOrElse { target.copy(isProcessing = false) }
      replacePageById(updated)
      refreshSuggestionsForPage(updated)
    }
  }

  fun saveBatchAsSearchablePdf(onSaved: (String) -> Unit) {
    val state = _batchState.value
    if (state.pages.isEmpty() || state.isSavingPdf) return

    viewModelScope.launch {
      _batchState.update {
        it.copy(isSavingPdf = true, processingMessage = "Menyematkan lapisan OCR ke PDF…")
      }
      runCatching {
        repository.saveBatchDocument(
          existingDocId = state.editingDocumentId,
          title = state.title,
          draftPages = state.pages,
        )
      }.onSuccess { docId ->
        _batchState.value = BatchReviewUiState()
        _snackbarMessages.tryEmit("PDF Searchable tersimpan (${state.pages.size} halaman)")
        onSaved(docId)
      }.onFailure { err ->
        _batchState.update { it.copy(isSavingPdf = false, processingMessage = null) }
        _snackbarMessages.tryEmit("Gagal membuat PDF: ${err.localizedMessage ?: "Error"}")
      }
    }
  }

  fun observeDocument(documentId: String): Flow<DocumentWithPages?> =
    repository.observeDocumentWithPages(documentId)

  fun renameDocument(documentId: String, newTitle: String) {
    val clean = newTitle.trim()
    if (clean.isEmpty()) return
    viewModelScope.launch {
      repository.renameDocument(documentId, clean)
      _snackbarMessages.tryEmit("Judul dokumen diperbarui")
    }
  }

  fun deleteDocument(documentId: String, onDeleted: (() -> Unit)? = null) {
    viewModelScope.launch {
      repository.deleteDocument(documentId)
      _snackbarMessages.tryEmit("Dokumen dihapus")
      onDeleted?.invoke()
    }
  }

  fun exportPdfToUri(pdfPath: String, destinationUri: Uri) {
    viewModelScope.launch {
      val file = File(pdfPath)
      if (!file.exists()) {
        _snackbarMessages.tryEmit("File PDF tidak ditemukan.")
        return@launch
      }
      ExportManager.writeFileToUri(getApplication(), file, destinationUri)
        .onSuccess { _snackbarMessages.tryEmit("PDF Searchable tersimpan ke perangkat") }
        .onFailure { _snackbarMessages.tryEmit("Gagal menyimpan PDF") }
    }
  }

  fun exportTextOnlyPdfToUri(docWithPages: DocumentWithPages, destinationUri: Uri) {
    viewModelScope.launch {
      runCatching {
        val textPdf = repository.generateTextOnlyPdfFile(docWithPages)
        ExportManager.writeFileToUri(getApplication(), textPdf, destinationUri).getOrThrow()
      }.onSuccess {
        _snackbarMessages.tryEmit("PDF Teks Rapi tersimpan")
      }.onFailure {
        _snackbarMessages.tryEmit("Gagal mengekspor PDF Teks")
      }
    }
  }

  fun shareSearchablePdf(doc: ScannedDocumentEntity) {
    val file = File(doc.pdfPath)
    if (!file.exists()) {
      _snackbarMessages.tryEmit("File PDF tidak tersedia")
      return
    }
    ExportManager.sharePdf(getApplication(), file, doc.title)
  }

  fun exportImagesToGallery(docWithPages: DocumentWithPages) {
    viewModelScope.launch {
      val files = docWithPages.pages.sortedBy { it.pageOrder }.map { File(it.enhancedImagePath) }
      ExportManager.exportImagesToGallery(getApplication(), files, docWithPages.document.title)
        .onSuccess { count ->
          _snackbarMessages.tryEmit("$count gambar tersimpan ke Pictures/ClearScan")
        }
        .onFailure {
          _snackbarMessages.tryEmit("Tidak dapat menyimpan gambar ke Galeri")
        }
    }
  }

  fun sharePageImages(docWithPages: DocumentWithPages, singlePageIndex: Int? = null) {
    val ordered = docWithPages.pages.sortedBy { it.pageOrder }
    val files = if (singlePageIndex != null && singlePageIndex in ordered.indices) {
      listOf(File(ordered[singlePageIndex].enhancedImagePath))
    } else {
      ordered.map { File(it.enhancedImagePath) }
    }
    ExportManager.shareImages(getApplication(), files, docWithPages.document.title)
  }

  fun copyOcrText(title: String, text: String) {
    if (text.isBlank()) {
      _snackbarMessages.tryEmit("Tidak ada teks OCR untuk disalin")
      return
    }
    ExportManager.copyTextToClipboard(getApplication(), title, text)
    _snackbarMessages.tryEmit("Teks OCR disalin ke papan klip")
  }

  fun notifyMessage(message: String) {
    _snackbarMessages.tryEmit(message)
  }

  private fun setPageProcessing(pageId: String, processing: Boolean) {
    _batchState.update { state ->
      state.copy(
        pages = state.pages.map { if (it.id == pageId) it.copy(isProcessing = processing) else it }
      )
    }
  }

  private fun replacePageById(updated: DraftPage) {
    _batchState.update { state ->
      val nextPages = state.pages.map { if (it.id == updated.id) updated else it }
      state.copy(
        pages = nextPages,
        suggestedTitles = SmartScanAdvisor.suggestTitles(nextPages),
      )
    }
  }

  private fun refreshSuggestionsForPage(page: DraftPage) {
    viewModelScope.launch {
      val quality = runCatching {
        DocumentImageProcessor.analyzePageQualityAndBorders(page.originalImagePath)
      }.getOrNull()
      val suggestions = SmartScanAdvisor.suggestForPage(page, quality)
      _batchState.update { state ->
        state.copy(pageSuggestions = state.pageSuggestions + (page.id to suggestions))
      }
    }
  }

  private fun suggestTitleIfDefault(currentTitle: String, pages: List<DraftPage>): String {
    val isDefaultPrefix = currentTitle.startsWith("Scan ") ||
      ScanPreset.entries.any { currentTitle.startsWith("${it.titlePrefix} ") }
    if (!isDefaultPrefix) return currentTitle
    val cleanLine = pages.firstOrNull()?.ocrResult?.lines
      ?.map { it.text.replace(Regex("\\s+"), " ").trim() }
      ?.firstOrNull { SmartScanAdvisor.isCleanTitleCandidate(it) }
    return cleanLine ?: currentTitle
  }
}
