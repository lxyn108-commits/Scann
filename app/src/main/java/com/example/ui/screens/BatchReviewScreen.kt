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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.domain.model.DraftPage
import com.example.domain.model.EnhancementFilter
import com.example.domain.model.NormalizedCropRect
import com.example.processing.PageSuggestion
import com.example.ui.BatchReviewUiState
import com.example.ui.components.InteractiveCropPreview
import com.example.ui.components.ScannedPagePreview
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchReviewScreen(
  state: BatchReviewUiState,
  onTitleChange: (String) -> Unit,
  onSelectPage: (Int) -> Unit,
  onMovePage: (Int, Int) -> Unit,
  onRemovePage: (Int) -> Unit,
  onApplyFilter: (EnhancementFilter) -> Unit,
  onApplyFilterToAll: (EnhancementFilter) -> Unit,
  onApplySuggestion: (PageSuggestion) -> Unit,
  onRotatePage: () -> Unit,
  onCropPage: (NormalizedCropRect) -> Unit,
  onAddMorePages: () -> Unit,
  onSaveSearchablePdf: () -> Unit,
  onBack: () -> Unit,
) {
  BackHandler(enabled = !state.isSavingPdf) {
    onBack()
  }

  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var showOcrBoxes by remember { mutableStateOf(true) }
  var isCropping by remember { mutableStateOf(false) }
  var cropRect by remember { mutableStateOf(NormalizedCropRect(0.05f, 0.05f, 0.95f, 0.95f)) }

  val pages = state.pages
  val currentPage = pages.getOrNull(state.selectedPageIndex)

  val pagerState = rememberPagerState(
    initialPage = state.selectedPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
    pageCount = { pages.size.coerceAtLeast(1) },
  )

  LaunchedEffect(state.selectedPageIndex, pages.size) {
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
    containerColor = MaterialTheme.colorScheme.background,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = stringResource(R.string.batch_review_title),
              style = MaterialTheme.typography.titleLarge,
            )
            if (pages.isNotEmpty()) {
              Text(
                text = "Page ${state.selectedPageIndex + 1} of ${pages.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("batch_back_button"),
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          FilterChip(
            selected = showOcrBoxes,
            onClick = { showOcrBoxes = !showOcrBoxes },
            label = { Text(stringResource(R.string.ocr_overlay_toggle)) },
            leadingIcon = {
              Icon(
                Icons.Filled.TextFields,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
            },
            modifier = Modifier.testTag("batch_ocr_overlay_chip"),
          )
          Spacer(Modifier.width(8.dp))
          IconButton(
            onClick = onAddMorePages,
            modifier = Modifier.testTag("batch_add_page_button"),
          ) {
            Icon(Icons.Filled.AddAPhoto, contentDescription = stringResource(R.string.action_add_page))
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface,
        ),
      )
    },
    bottomBar = {
      Surface(
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface,
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
          if (isCropping) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              OutlinedButton(
                onClick = { isCropping = false },
                modifier = Modifier
                  .weight(1f)
                  .height(50.dp),
                shape = RoundedCornerShape(14.dp),
              ) {
                Text("Cancel")
              }
              Button(
                onClick = {
                  isCropping = false
                  onCropPage(cropRect)
                },
                modifier = Modifier
                  .weight(1f)
                  .height(50.dp)
                  .testTag("apply_crop_button"),
                shape = RoundedCornerShape(14.dp),
              ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Apply Crop")
              }
            }
          } else {
            Button(
              onClick = onSaveSearchablePdf,
              enabled = pages.isNotEmpty() && !state.isSavingPdf && currentPage?.isProcessing != true,
              modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("save_searchable_pdf_button"),
              shape = RoundedCornerShape(16.dp),
            ) {
              if (state.isSavingPdf) {
                CircularProgressIndicator(
                  modifier = Modifier.size(20.dp),
                  strokeWidth = 2.5.dp,
                  color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text(state.processingMessage ?: "Creating Searchable PDF…")
              } else {
                Icon(Icons.Filled.PictureAsPdf, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                  text = "Create Searchable PDF (${pages.size} Page${if (pages.size == 1) "" else "s"})",
                  fontWeight = FontWeight.SemiBold,
                )
              }
            }
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
          )
          Spacer(Modifier.height(12.dp))
          Text("No pages in batch", style = MaterialTheme.typography.titleMedium)
          Spacer(Modifier.height(12.dp))
          Button(onClick = onAddMorePages) {
            Icon(Icons.Filled.AddAPhoto, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Scan Pages")
          }
        }
      }
      return@Scaffold
    }

    BoxWithConstraints(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
    ) {
      val isWide = maxWidth >= 720.dp

      if (isWide) {
        Row(
          modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Box(
            modifier = Modifier
              .weight(1.3f)
              .fillMaxHeight(),
          ) {
            BatchPreviewStage(
              pages = pages,
              pagerState = pagerState,
              currentPage = currentPage,
              isCropping = isCropping,
              cropRect = cropRect,
              showOcrBoxes = showOcrBoxes,
              onCropChanged = { cropRect = it },
            )
          }

          Column(
            modifier = Modifier
              .weight(1f)
              .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            BatchControlsPanel(
              title = state.title,
              suggestedTitles = state.suggestedTitles,
              onTitleChange = onTitleChange,
              pages = pages,
              selectedIndex = state.selectedPageIndex,
              currentPage = currentPage,
              currentPageSuggestions = currentPage?.let { state.pageSuggestions[it.id].orEmpty() }.orEmpty(),
              isCropping = isCropping,
              onSelectPage = { idx ->
                onSelectPage(idx)
                scope.launch { pagerState.animateScrollToPage(idx) }
              },
              onMovePage = onMovePage,
              onRemovePage = onRemovePage,
              onRotatePage = onRotatePage,
              onStartCrop = {
                cropRect = NormalizedCropRect(0.06f, 0.06f, 0.94f, 0.94f)
                isCropping = true
              },
              onApplyFilter = onApplyFilter,
              onApplyFilterToAll = onApplyFilterToAll,
              onApplySuggestion = onApplySuggestion,
            )
          }
        }
      } else {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedTextField(
            value = state.title,
            onValueChange = onTitleChange,
            label = { Text("Document Title") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("batch_title_input"),
          )

          if (state.suggestedTitles.isNotEmpty() && !isCropping) {
            TitleSuggestionRow(
              suggestions = state.suggestedTitles,
              currentTitle = state.title,
              onSelectTitle = onTitleChange,
            )
          }

          Box(
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth(),
          ) {
            BatchPreviewStage(
              pages = pages,
              pagerState = pagerState,
              currentPage = currentPage,
              isCropping = isCropping,
              cropRect = cropRect,
              showOcrBoxes = showOcrBoxes,
              onCropChanged = { cropRect = it },
            )
          }

          if (!isCropping) {
            BatchThumbnailsAndTools(
              pages = pages,
              selectedIndex = state.selectedPageIndex,
              currentPage = currentPage,
              currentPageSuggestions = currentPage?.let { state.pageSuggestions[it.id].orEmpty() }.orEmpty(),
              onSelectPage = { idx ->
                onSelectPage(idx)
                scope.launch { pagerState.animateScrollToPage(idx) }
              },
              onMovePage = onMovePage,
              onRemovePage = onRemovePage,
              onRotatePage = onRotatePage,
              onStartCrop = {
                cropRect = NormalizedCropRect(0.06f, 0.06f, 0.94f, 0.94f)
                isCropping = true
              },
              onApplyFilter = onApplyFilter,
              onApplyFilterToAll = onApplyFilterToAll,
              onApplySuggestion = onApplySuggestion,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun BatchPreviewStage(
  pages: List<DraftPage>,
  pagerState: androidx.compose.foundation.pager.PagerState,
  currentPage: DraftPage?,
  isCropping: Boolean,
  cropRect: NormalizedCropRect,
  showOcrBoxes: Boolean,
  onCropChanged: (NormalizedCropRect) -> Unit,
) {
  Card(
    shape = RoundedCornerShape(20.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    modifier = Modifier.fillMaxSize(),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(10.dp),
      contentAlignment = Alignment.Center,
    ) {
      if (isCropping && currentPage != null) {
        InteractiveCropPreview(
          imagePath = currentPage.enhancedImagePath,
          imageWidth = currentPage.ocrResult?.imageWidth ?: 1000,
          imageHeight = currentPage.ocrResult?.imageHeight ?: 1400,
          cropRect = cropRect,
          onCropChanged = onCropChanged,
          modifier = Modifier.fillMaxSize(),
        )
      } else {
        HorizontalPager(
          state = pagerState,
          modifier = Modifier.fillMaxSize(),
        ) { pageIdx ->
          val page = pages.getOrNull(pageIdx)
          if (page != null) {
            ScannedPagePreview(
              imagePath = page.enhancedImagePath,
              imageWidth = page.ocrResult?.imageWidth ?: 1000,
              imageHeight = page.ocrResult?.imageHeight ?: 1400,
              ocrLines = page.ocrResult?.lines.orEmpty(),
              showOcrBoxes = showOcrBoxes,
              searchQuery = "",
              contentDescription = "Scanned page ${pageIdx + 1}",
              modifier = Modifier.fillMaxSize(),
            )
          }
        }
      }

      if (currentPage?.isProcessing == true) {
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.width(10.dp))
            Text("Enhancing & running OCR…", style = MaterialTheme.typography.labelLarge)
          }
        }
      }
    }
  }
}

@Composable
private fun BatchControlsPanel(
  title: String,
  suggestedTitles: List<String>,
  onTitleChange: (String) -> Unit,
  pages: List<DraftPage>,
  selectedIndex: Int,
  currentPage: DraftPage?,
  currentPageSuggestions: List<PageSuggestion>,
  isCropping: Boolean,
  onSelectPage: (Int) -> Unit,
  onMovePage: (Int, Int) -> Unit,
  onRemovePage: (Int) -> Unit,
  onRotatePage: () -> Unit,
  onStartCrop: () -> Unit,
  onApplyFilter: (EnhancementFilter) -> Unit,
  onApplyFilterToAll: (EnhancementFilter) -> Unit,
  onApplySuggestion: (PageSuggestion) -> Unit,
) {
  OutlinedTextField(
    value = title,
    onValueChange = onTitleChange,
    label = { Text("Document Title") },
    singleLine = true,
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("batch_title_input"),
  )

  if (suggestedTitles.isNotEmpty() && !isCropping) {
    TitleSuggestionRow(
      suggestions = suggestedTitles,
      currentTitle = title,
      onSelectTitle = onTitleChange,
    )
  }

  if (!isCropping) {
    BatchThumbnailsAndTools(
      pages = pages,
      selectedIndex = selectedIndex,
      currentPage = currentPage,
      currentPageSuggestions = currentPageSuggestions,
      onSelectPage = onSelectPage,
      onMovePage = onMovePage,
      onRemovePage = onRemovePage,
      onRotatePage = onRotatePage,
      onStartCrop = onStartCrop,
      onApplyFilter = onApplyFilter,
      onApplyFilterToAll = onApplyFilterToAll,
      onApplySuggestion = onApplySuggestion,
    )
  }
}

@Composable
private fun TitleSuggestionRow(
  suggestions: List<String>,
  currentTitle: String,
  onSelectTitle: (String) -> Unit,
) {
  LazyRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    contentPadding = PaddingValues(horizontal = 2.dp),
  ) {
    itemsIndexed(suggestions) { idx, candidate ->
      if (candidate != currentTitle) {
        SuggestionChip(
          onClick = { onSelectTitle(candidate) },
          icon = {
            Icon(
              imageVector = Icons.Filled.AutoAwesome,
              contentDescription = null,
              modifier = Modifier.size(15.dp),
            )
          },
          label = {
            Text(
              text = candidate,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          },
          modifier = Modifier.testTag("title_suggestion_$idx"),
        )
      }
    }
  }
}

@Composable
private fun BatchThumbnailsAndTools(
  pages: List<DraftPage>,
  selectedIndex: Int,
  currentPage: DraftPage?,
  currentPageSuggestions: List<PageSuggestion>,
  onSelectPage: (Int) -> Unit,
  onMovePage: (Int, Int) -> Unit,
  onRemovePage: (Int) -> Unit,
  onRotatePage: () -> Unit,
  onStartCrop: () -> Unit,
  onApplyFilter: (EnhancementFilter) -> Unit,
  onApplyFilterToAll: (EnhancementFilter) -> Unit,
  onApplySuggestion: (PageSuggestion) -> Unit,
) {
  val context = LocalContext.current

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (currentPageSuggestions.isNotEmpty()) {
      LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
      ) {
        itemsIndexed(currentPageSuggestions) { idx, suggestion ->
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
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp),
              )
            },
            label = { Text(suggestion.label) },
            modifier = Modifier.testTag("page_suggestion_$idx"),
          )
        }
      }
    }
    // Batch Thumbnail Strip
    LazyRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
      itemsIndexed(pages, key = { _, page -> page.id }) { idx, page ->
        val selected = idx == selectedIndex
        Box(
          modifier = Modifier
            .size(width = 48.dp, height = 64.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(
              width = if (selected) 2.5.dp else 1.dp,
              color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
              shape = RoundedCornerShape(8.dp),
            )
            .clickable { onSelectPage(idx) },
        ) {
          AsyncImage(
            model = ImageRequest.Builder(context)
              .data(File(page.enhancedImagePath))
              .memoryCacheKey("${page.enhancedImagePath}_${File(page.enhancedImagePath).lastModified()}")
              .build(),
            contentDescription = "Select page ${idx + 1}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
          Surface(
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            shape = RoundedCornerShape(topEnd = 6.dp),
            modifier = Modifier.align(Alignment.BottomStart),
          ) {
            Text(
              text = "${idx + 1}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.surface,
              modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
          }
        }
      }
    }

    // Page Manipulation Row (Reorder, Crop, Rotate, Delete)
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
          onClick = { onMovePage(selectedIndex, -1) },
          enabled = selectedIndex > 0,
          modifier = Modifier.size(48.dp),
        ) {
          Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Move page left")
        }
        IconButton(
          onClick = { onMovePage(selectedIndex, 1) },
          enabled = selectedIndex < pages.lastIndex,
          modifier = Modifier.size(48.dp),
        ) {
          Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Move page right")
        }
        IconButton(
          onClick = onStartCrop,
          modifier = Modifier
            .size(48.dp)
            .testTag("crop_page_button"),
        ) {
          Icon(Icons.Filled.Crop, contentDescription = "Crop page")
        }
        IconButton(
          onClick = onRotatePage,
          modifier = Modifier
            .size(48.dp)
            .testTag("rotate_page_button"),
        ) {
          Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate 90 degrees")
        }
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        val wordCount = currentPage?.ocrResult?.lines?.sumOf { it.words.size } ?: 0
        Text(
          text = "$wordCount words",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.secondary,
        )
        IconButton(
          onClick = { onRemovePage(selectedIndex) },
          modifier = Modifier
            .size(48.dp)
            .testTag("delete_page_button"),
        ) {
          Icon(
            Icons.Filled.DeleteOutline,
            contentDescription = "Delete page",
            tint = MaterialTheme.colorScheme.error,
          )
        }
      }
    }

    // Clarity Enhancement Filter Row
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Filled.AutoFixHigh,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
          text = "Clarity Filter",
          style = MaterialTheme.typography.labelLarge,
        )
      }
      if (pages.size > 1 && currentPage != null) {
        TextButton(onClick = { onApplyFilterToAll(currentPage.filter) }) {
          Text("Apply to All")
        }
      }
    }

    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      itemsIndexed(EnhancementFilter.entries) { _, filter ->
        val selected = currentPage?.filter == filter
        FilterChip(
          selected = selected,
          onClick = { onApplyFilter(filter) },
          label = { Text(filter.displayName) },
          modifier = Modifier.testTag("filter_chip_${filter.name.lowercase()}"),
        )
      }
    }
  }
}
