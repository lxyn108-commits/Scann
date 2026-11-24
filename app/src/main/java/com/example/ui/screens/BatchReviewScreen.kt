package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.domain.model.DraftPage
import com.example.domain.model.EnhancementFilter
import com.example.domain.model.NormalizedQuad
import com.example.processing.PageSuggestion
import com.example.ui.BatchReviewUiState
import com.example.ui.components.CircularFilterSelectorRow
import com.example.ui.components.EditStudioActionStrip
import com.example.ui.components.ExtractedTextModalSheet
import com.example.ui.components.InteractivePerspectiveOverlay
import com.example.ui.components.ScannedPagePreview
import com.example.ui.theme.LocalClearScanColors
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun BatchReviewScreen(
  state: BatchReviewUiState,
  onTitleChange: (String) -> Unit,
  onSelectPage: (Int) -> Unit,
  onMovePage: (Int, Int) -> Unit,
  onRemovePage: (Int) -> Unit,
  onRetakePage: (Int) -> Unit,
  onApplyFilter: (EnhancementFilter) -> Unit,
  onApplyFilterToAll: (EnhancementFilter) -> Unit,
  onApplySuggestion: (PageSuggestion) -> Unit,
  onRotatePage: () -> Unit,
  onApplyPerspectiveQuad: (NormalizedQuad) -> Unit,
  onToggleShadowRemoval: () -> Unit,
  onCopyText: (String, String) -> Unit,
  onAddMorePages: () -> Unit,
  onSaveSearchablePdf: () -> Unit,
  onBack: () -> Unit,
) {
  BackHandler(enabled = !state.isSavingPdf) {
    onBack()
  }

  val extendedColors = LocalClearScanColors.current
  val scope = rememberCoroutineScope()
  var showOcrBoxes by remember { mutableStateOf(false) }
  var compareOriginal by remember { mutableStateOf(false) }
  var isStraightening by remember { mutableStateOf(false) }
  var editingQuad by remember { mutableStateOf(NormalizedQuad.FULL) }
  var showExtractedTextSheet by remember { mutableStateOf(false) }

  val pages = state.pages
  val currentPage = pages.getOrNull(state.selectedPageIndex)

  val pagerState = rememberPagerState(
    initialPage = state.selectedPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
    pageCount = { pages.size.coerceAtLeast(1) },
  )

  LaunchedEffect(state.selectedPageIndex, pages.size) {
    compareOriginal = false
    if (pages.isNotEmpty() && pagerState.currentPage != state.selectedPageIndex) {
      pagerState.animateScrollToPage(state.selectedPageIndex.coerceIn(0, pages.lastIndex))
    }
  }

  LaunchedEffect(pagerState.currentPage) {
    if (pages.isNotEmpty() && pagerState.currentPage != state.selectedPageIndex) {
      onSelectPage(pagerState.currentPage)
    }
  }

  Scaffold(
    containerColor = extendedColors.viewfinderBackground,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = {
      // Oppo-style Studio Header: [Terus memindai]   Edit   [Simpan]
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          IconButton(
            onClick = onBack,
            modifier = Modifier
              .size(44.dp)
              .clip(CircleShape)
              .background(Color.White.copy(alpha = 0.12f))
              .testTag("batch_back_button"),
          ) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Kembali",
              tint = Color.White,
            )
          }

          Surface(
            shape = RoundedCornerShape(50),
            color = Color.White.copy(alpha = 0.16f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
            modifier = Modifier
              .clip(RoundedCornerShape(50))
              .clickable(enabled = !state.isSavingPdf) { onAddMorePages() }
              .testTag("batch_add_page_button"),
          ) {
            Text(
              text = "Terus memindai",
              style = MaterialTheme.typography.labelLarge,
              color = Color.White,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            )
          }
        }

        Text(
          text = "Edit",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
          color = Color.White,
        )

        Button(
          onClick = onSaveSearchablePdf,
          enabled = pages.isNotEmpty() && !state.isSavingPdf && currentPage?.isProcessing != true && !isStraightening,
          shape = RoundedCornerShape(50),
          colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color.White,
          ),
          contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
          modifier = Modifier.testTag("save_searchable_pdf_button"),
        ) {
          if (state.isSavingPdf) {
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
              color = Color.White,
            )
            Spacer(Modifier.width(6.dp))
          }
          Text(
            text = "Simpan",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
          )
        }
      }
    },
    bottomBar = {
      Surface(
        color = extendedColors.viewfinderBackground,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          if (isStraightening) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              OutlinedButton(
                onClick = { isStraightening = false },
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                modifier = Modifier
                  .weight(1f)
                  .height(48.dp),
                shape = RoundedCornerShape(14.dp),
              ) {
                Text("Batal")
              }
              Button(
                onClick = {
                  isStraightening = false
                  onApplyPerspectiveQuad(editingQuad)
                },
                modifier = Modifier
                  .weight(1f)
                  .height(48.dp)
                  .testTag("apply_crop_button"),
                shape = RoundedCornerShape(14.dp),
              ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Terapkan")
              }
            }
          } else if (pages.isNotEmpty()) {
            EditStudioActionStrip(
              shadowRemovalEnabled = currentPage?.removeShadowAndGlare == true,
              onRetakePage = { onRetakePage(state.selectedPageIndex) },
              onRotatePage = onRotatePage,
              onStartStraighten = {
                editingQuad = currentPage?.quad ?: NormalizedQuad.FULL
                isStraightening = true
              },
              onExtractText = { showExtractedTextSheet = true },
              onToggleShadowRemoval = onToggleShadowRemoval,
            )

            CircularFilterSelectorRow(
              currentPage = currentPage,
              pageCount = pages.size,
              onApplyFilter = onApplyFilter,
              onApplyFilterToAll = onApplyFilterToAll,
            )
          }
        }
      }
    },
  ) { innerPadding ->
    if (pages.isEmpty()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
        contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(
            imageVector = Icons.Filled.DocumentScanner,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(48.dp),
          )
          Spacer(Modifier.height(12.dp))
          Text(
            text = "Belum ada halaman dipindai",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
          )
          Spacer(Modifier.height(12.dp))
          Button(onClick = onAddMorePages) {
            Icon(Icons.Filled.AddAPhoto, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Pindai Dokumen")
          }
        }
      }
      return@Scaffold
    }

    BoxWithConstraints(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .padding(horizontal = 14.dp),
    ) {
      val isWide = maxWidth >= 720.dp
      val currentSuggestions = currentPage?.let { state.pageSuggestions[it.id].orEmpty() }.orEmpty()

      Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // Compact Document Title + OCR Overlay Toggle
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedTextField(
            value = state.title,
            onValueChange = onTitleChange,
            placeholder = { Text("Judul dokumen PDF…", color = Color.White.copy(alpha = 0.5f)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
              focusedTextColor = Color.White,
              unfocusedTextColor = Color.White,
              focusedBorderColor = MaterialTheme.colorScheme.primary,
              unfocusedBorderColor = Color.White.copy(alpha = 0.22f),
              focusedContainerColor = Color.White.copy(alpha = 0.06f),
              unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
            ),
            modifier = Modifier
              .weight(1f)
              .testTag("batch_title_input"),
          )

          FilterChip(
            selected = showOcrBoxes,
            onClick = { showOcrBoxes = !showOcrBoxes },
            label = { Text("OCR") },
            leadingIcon = {
              Icon(Icons.Filled.TextFields, contentDescription = null, modifier = Modifier.size(15.dp))
            },
            colors = FilterChipDefaults.filterChipColors(
              labelColor = Color.White,
              iconColor = Color.White,
              selectedContainerColor = MaterialTheme.colorScheme.primary,
              selectedLabelColor = Color.White,
              selectedLeadingIconColor = Color.White,
            ),
            modifier = Modifier.testTag("batch_ocr_overlay_chip"),
          )
        }

        // Smart Title or Quality Suggestions Row
        if ((state.suggestedTitles.isNotEmpty() || currentSuggestions.isNotEmpty()) && !isStraightening) {
          LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
          ) {
            itemsIndexed(currentSuggestions) { idx, suggestion ->
              AssistChip(
                onClick = { onApplySuggestion(suggestion) },
                leadingIcon = {
                  Icon(
                    imageVector = when (suggestion) {
                      is PageSuggestion.AutoTrimBorders -> Icons.Filled.Crop
                      is PageSuggestion.RotatePage -> Icons.AutoMirrored.Filled.RotateRight
                      is PageSuggestion.ApplyFilter -> Icons.Filled.AutoAwesome
                    },
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                  )
                },
                label = { Text(suggestion.label) },
                colors = AssistChipDefaults.assistChipColors(
                  containerColor = Color.White.copy(alpha = 0.1f),
                  labelColor = Color.White,
                  leadingIconContentColor = MaterialTheme.colorScheme.secondary,
                ),
                modifier = Modifier.testTag("page_suggestion_$idx"),
              )
            }
            itemsIndexed(state.suggestedTitles) { idx, candidate ->
              if (candidate != state.title) {
                SuggestionChip(
                  onClick = { onTitleChange(candidate) },
                  icon = {
                    Icon(
                      Icons.Filled.AutoAwesome,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(14.dp),
                    )
                  },
                  label = {
                    Text(candidate, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                  },
                  modifier = Modifier.testTag("title_suggestion_$idx"),
                )
              }
            }
          }
        }

        // Main Document Preview Stage (with 1/N pill, red delete badge, before/after compare)
        Box(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .then(if (isWide) Modifier.fillMaxHeight() else Modifier),
          contentAlignment = Alignment.Center,
        ) {
          if (isStraightening && currentPage != null) {
            val rawPath = File(currentPage.rawImagePath).takeIf { it.exists() }?.absolutePath
              ?: currentPage.originalImagePath
            InteractivePerspectiveOverlay(
              rawImagePath = rawPath,
              quad = editingQuad,
              onQuadChanged = { editingQuad = it },
              modifier = Modifier.fillMaxSize(),
            )
          } else {
            Box(modifier = Modifier.fillMaxSize()) {
              HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
              ) { pageIdx ->
                val page = pages.getOrNull(pageIdx)
                if (page != null) {
                  val previewPath = if (compareOriginal && pageIdx == state.selectedPageIndex) {
                    page.originalImagePath
                  } else {
                    page.enhancedImagePath
                  }
                  ScannedPagePreview(
                    imagePath = previewPath,
                    imageWidth = page.ocrResult?.imageWidth ?: 1000,
                    imageHeight = page.ocrResult?.imageHeight ?: 1400,
                    ocrLines = if (compareOriginal) emptyList() else page.ocrResult?.lines.orEmpty(),
                    showOcrBoxes = showOcrBoxes && !compareOriginal,
                    searchQuery = "",
                    contentDescription = "Halaman pindaian ${pageIdx + 1}",
                    modifier = Modifier.fillMaxSize(),
                  )
                }
              }

              // Top-Left Page Counter Pill (1/N)
              Surface(
                shape = RoundedCornerShape(50),
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                  .align(Alignment.TopStart)
                  .padding(12.dp),
              ) {
                Text(
                  text = "${state.selectedPageIndex + 1}/${pages.size}",
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.SemiBold,
                  color = Color.Black,
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                )
              }

              // Top-Right Red Delete Circular Button (matches Oppo screenshot)
              IconButton(
                onClick = { onRemovePage(state.selectedPageIndex) },
                modifier = Modifier
                  .align(Alignment.TopEnd)
                  .padding(10.dp)
                  .size(44.dp)
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.error)
                  .testTag("delete_page_button"),
              ) {
                Icon(
                  imageVector = Icons.Filled.Delete,
                  contentDescription = "Hapus halaman",
                  tint = Color.White,
                )
              }

              // Bottom-Right Before/After Compare Button (matches Oppo screenshot)
              Row(
                modifier = Modifier
                  .align(Alignment.BottomEnd)
                  .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                if (compareOriginal) {
                  Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.65f),
                  ) {
                    Text(
                      text = "Sebelum",
                      style = MaterialTheme.typography.labelSmall,
                      color = Color.White,
                      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                  }
                }
                IconButton(
                  onClick = { compareOriginal = !compareOriginal },
                  modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                      if (compareOriginal) MaterialTheme.colorScheme.primary
                      else Color.Black.copy(alpha = 0.58f)
                    )
                    .testTag("compare_original_button"),
                ) {
                  Icon(
                    imageVector = Icons.Filled.Compare,
                    contentDescription = "Bandingkan asli dan hasil edit",
                    tint = Color.White,
                  )
                }
              }
            }
          }

          if (currentPage?.isProcessing == true) {
            Surface(
              shape = RoundedCornerShape(16.dp),
              color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(10.dp))
                Text("Meningkatkan kejelasan & OCR…", style = MaterialTheme.typography.labelLarge)
              }
            }
          }
        }

        // Batch Thumbnails & Reorder Controls (when multi-page)
        if (pages.size > 1 && !isStraightening) {
          val context = LocalContext.current
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            IconButton(
              onClick = { onMovePage(state.selectedPageIndex, -1) },
              enabled = state.selectedPageIndex > 0,
              modifier = Modifier.size(36.dp),
            ) {
              Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Geser kiri",
                tint = Color.White,
              )
            }

            LazyRow(
              modifier = Modifier.weight(1f),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              itemsIndexed(pages, key = { _, page -> page.id }) { idx, page ->
                val selected = idx == state.selectedPageIndex
                Box(
                  modifier = Modifier
                    .size(width = 42.dp, height = 54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                      width = if (selected) 2.dp else 1.dp,
                      color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.25f),
                      shape = RoundedCornerShape(8.dp),
                    )
                    .clickable {
                      onSelectPage(idx)
                      scope.launch { pagerState.animateScrollToPage(idx) }
                    },
                ) {
                  AsyncImage(
                    model = ImageRequest.Builder(context)
                      .data(File(page.enhancedImagePath))
                      .memoryCacheKey("${page.enhancedImagePath}_${File(page.enhancedImagePath).lastModified()}")
                      .build(),
                    contentDescription = "Pilih halaman ${idx + 1}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                  )
                }
              }
            }

            IconButton(
              onClick = { onMovePage(state.selectedPageIndex, 1) },
              enabled = state.selectedPageIndex < pages.lastIndex,
              modifier = Modifier.size(36.dp),
            ) {
              Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Geser kanan",
                tint = Color.White,
              )
            }
          }
        }
      }
    }
  }

  if (showExtractedTextSheet && currentPage != null) {
    ExtractedTextModalSheet(
      pageNumber = state.selectedPageIndex + 1,
      extractedText = currentPage.ocrResult?.formattedText.orEmpty(),
      onCopyText = { text ->
        onCopyText(state.title.ifBlank { "OCR Hal ${state.selectedPageIndex + 1}" }, text)
      },
      onDismiss = { showExtractedTextSheet = false },
    )
  }
}
