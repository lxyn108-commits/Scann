package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.DocumentWithPages
import com.example.data.local.ScannedDocumentEntity
import com.example.domain.model.OcrJsonSerializer
import com.example.processing.DocumentActionType
import com.example.processing.SmartScanAdvisor
import com.example.ui.components.ScannedPagePreview
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(
  documentWithPages: DocumentWithPages?,
  snackbarHostState: SnackbarHostState,
  onBack: () -> Unit,
  onEditDocument: (String) -> Unit,
  onRenameDocument: (String, String) -> Unit,
  onDeleteDocument: (String) -> Unit,
  onSaveSearchablePdfUri: (String, Uri) -> Unit,
  onSaveTextPdfUri: (DocumentWithPages, Uri) -> Unit,
  onSharePdf: (ScannedDocumentEntity) -> Unit,
  onExportImagesToGallery: (DocumentWithPages) -> Unit,
  onShareImages: (DocumentWithPages, Int?) -> Unit,
  onCopyText: (String, String) -> Unit,
) {
  val scope = rememberCoroutineScope()
  var selectedTab by remember { mutableIntStateOf(0) } // 0 = Halaman, 1 = Teks OCR
  var inDocSearch by remember { mutableStateOf("") }
  var showOcrBoxes by remember { mutableStateOf(false) }
  var showExportSheet by remember { mutableStateOf(false) }
  var showDeleteDialog by remember { mutableStateOf(false) }
  var showRenameDialog by remember { mutableStateOf(false) }
  var renameText by remember { mutableStateOf("") }

  val saveSearchablePdfLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/pdf"),
    onResult = { uri ->
      val doc = documentWithPages?.document
      if (uri != null && doc != null) {
        onSaveSearchablePdfUri(doc.pdfPath, uri)
      }
    },
  )

  val saveTextPdfLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/pdf"),
    onResult = { uri ->
      if (uri != null && documentWithPages != null) {
        onSaveTextPdfUri(documentWithPages, uri)
      }
    },
  )

  if (documentWithPages == null) {
    Scaffold(
      containerColor = MaterialTheme.colorScheme.background,
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(padding),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator()
      }
    }
    return
  }

  val context = LocalContext.current
  val doc = documentWithPages.document
  val orderedPages = remember(documentWithPages.pages) {
    documentWithPages.pages.sortedBy { it.pageOrder }
  }
  val pagerState = rememberPagerState(pageCount = { orderedPages.size.coerceAtLeast(1) })
  val parsedOcrByPage = remember(orderedPages) {
    orderedPages.map { OcrJsonSerializer.decodeLines(it.ocrLinesJson) }
  }
  val smartActions = remember(doc.fullOcrText) {
    SmartScanAdvisor.extractDocumentActions(doc.fullOcrText)
  }

  val trimmedQuery = inDocSearch.trim()
  val matchCountsPerPage = remember(orderedPages, trimmedQuery) {
    if (trimmedQuery.isEmpty()) {
      emptyList()
    } else {
      orderedPages.mapIndexed { idx, page ->
        val count = Regex(Regex.escape(trimmedQuery), RegexOption.IGNORE_CASE)
          .findAll(page.ocrText)
          .count()
        idx to count
      }.filter { it.second > 0 }
    }
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = doc.title,
              style = MaterialTheme.typography.titleLarge,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = "${orderedPages.size} halaman • PDF Searchable",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.secondary,
            )
          }
        },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("viewer_back_button"),
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali ke daftar")
          }
        },
        actions = {
          IconButton(
            onClick = {
              renameText = doc.title
              showRenameDialog = true
            },
            modifier = Modifier.testTag("viewer_rename_button"),
          ) {
            Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = "Ubah judul")
          }
          IconButton(
            onClick = { onEditDocument(doc.id) },
            modifier = Modifier.testTag("viewer_edit_button"),
          ) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit halaman")
          }
          IconButton(
            onClick = { showDeleteDialog = true },
            modifier = Modifier.testTag("viewer_delete_button"),
          ) {
            Icon(
              Icons.Filled.DeleteOutline,
              contentDescription = "Hapus dokumen",
              tint = MaterialTheme.colorScheme.error,
            )
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
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          FilledTonalButton(
            onClick = { onSharePdf(doc) },
            modifier = Modifier
              .weight(1f)
              .height(50.dp)
              .testTag("viewer_quick_share_pdf"),
            shape = RoundedCornerShape(14.dp),
          ) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_share_pdf))
          }

          Button(
            onClick = { showExportSheet = true },
            modifier = Modifier
              .weight(1.2f)
              .height(50.dp)
              .testTag("viewer_export_sheet_button"),
            shape = RoundedCornerShape(14.dp),
          ) {
            Icon(Icons.Filled.IosShare, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Ekspor PDF / Img")
          }
        }
      }
    },
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .padding(horizontal = 16.dp, vertical = 10.dp)
        .widthIn(max = 900.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Mode Switch & OCR Overlay Toggle
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        SingleChoiceSegmentedButtonRow(
          modifier = Modifier.weight(1f),
        ) {
          SegmentedButton(
            selected = selectedTab == 0,
            onClick = { selectedTab = 0 },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            modifier = Modifier.testTag("viewer_tab_pages"),
          ) {
            Text("Halaman PDF")
          }
          SegmentedButton(
            selected = selectedTab == 1,
            onClick = { selectedTab = 1 },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            modifier = Modifier.testTag("viewer_tab_text"),
          ) {
            Text("Teks OCR")
          }
        }

        if (selectedTab == 0) {
          Spacer(Modifier.width(10.dp))
          FilterChip(
            selected = showOcrBoxes,
            onClick = { showOcrBoxes = !showOcrBoxes },
            label = { Text("OCR") },
            leadingIcon = {
              Icon(
                Icons.Filled.TextFields,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
            },
            modifier = Modifier.testTag("viewer_ocr_toggle"),
          )
        }
      }

      // In-Document Search Bar
      OutlinedTextField(
        value = inDocSearch,
        onValueChange = { inDocSearch = it },
        placeholder = { Text("Cari teks dalam dokumen ini…") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
          if (inDocSearch.isNotEmpty()) {
            IconButton(onClick = { inDocSearch = "" }) {
              Icon(Icons.Filled.Close, contentDescription = "Hapus pencarian")
            }
          }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
          focusedContainerColor = MaterialTheme.colorScheme.surface,
          unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("viewer_search_input"),
      )

      if (smartActions.isNotEmpty() && trimmedQuery.isEmpty()) {
        LazyRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          contentPadding = PaddingValues(horizontal = 2.dp),
        ) {
          itemsIndexed(smartActions) { idx, action ->
            AssistChip(
              onClick = {
                when (action.type) {
                  DocumentActionType.EMAIL -> {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${action.value}"))
                    runCatching { context.startActivity(intent) }
                      .onFailure { onCopyText("Email", action.value) }
                  }
                  DocumentActionType.URL -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(action.value))
                    runCatching { context.startActivity(intent) }
                      .onFailure { onCopyText("URL", action.value) }
                  }
                  DocumentActionType.PHONE -> {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${action.value}"))
                    runCatching { context.startActivity(intent) }
                      .onFailure { onCopyText("Telepon", action.value) }
                  }
                  DocumentActionType.AMOUNT -> {
                    onCopyText("Nominal", action.value)
                  }
                }
              },
              leadingIcon = {
                Icon(
                  imageVector = when (action.type) {
                    DocumentActionType.EMAIL -> Icons.Filled.Email
                    DocumentActionType.URL -> Icons.Filled.Language
                    DocumentActionType.PHONE -> Icons.Filled.Phone
                    DocumentActionType.AMOUNT -> Icons.Filled.AttachMoney
                  },
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
              },
              label = { Text(action.displayLabel) },
              modifier = Modifier.testTag("viewer_smart_action_$idx"),
            )
          }
        }
      }

      if (trimmedQuery.isNotEmpty()) {
        if (matchCountsPerPage.isEmpty()) {
          Text(
            text = "Tidak ada kecocokan untuk \"$trimmedQuery\"",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
          ) {
            itemsIndexed(matchCountsPerPage) { _, (pageIdx, count) ->
              FilterChip(
                selected = pagerState.currentPage == pageIdx,
                onClick = {
                  selectedTab = 0
                  scope.launch { pagerState.animateScrollToPage(pageIdx) }
                },
                label = { Text("Hal ${pageIdx + 1} ($count)") },
              )
            }
          }
        }
      }

      if (selectedTab == 0) {
        Card(
          shape = RoundedCornerShape(20.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        ) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .padding(10.dp),
          ) {
            HorizontalPager(
              state = pagerState,
              modifier = Modifier.fillMaxSize(),
            ) { pageIdx ->
              val page = orderedPages[pageIdx]
              ScannedPagePreview(
                imagePath = page.enhancedImagePath,
                imageWidth = page.imageWidth,
                imageHeight = page.imageHeight,
                ocrLines = parsedOcrByPage.getOrElse(pageIdx) { emptyList() },
                showOcrBoxes = showOcrBoxes,
                searchQuery = trimmedQuery,
                contentDescription = "${doc.title} halaman ${pageIdx + 1}",
                modifier = Modifier.fillMaxSize(),
                zoomable = true,
              )
            }

            Surface(
              shape = RoundedCornerShape(50),
              color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
              modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(8.dp),
            ) {
              Text(
                text = "Hal ${pagerState.currentPage + 1} / ${orderedPages.size}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
              )
            }
          }
        }
      } else {
        Card(
          shape = RoundedCornerShape(20.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        ) {
          Column(modifier = Modifier.fillMaxSize()) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "Teks OCR Sesuai Tata Letak",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
              )
              if (doc.fullOcrText.isNotBlank()) {
                IconButton(
                  onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                      type = "text/plain"
                      putExtra(Intent.EXTRA_SUBJECT, doc.title)
                      putExtra(Intent.EXTRA_TEXT, doc.fullOcrText)
                    }
                    runCatching {
                      context.startActivity(Intent.createChooser(shareIntent, "Bagikan Teks OCR"))
                    }.onFailure {
                      onCopyText(doc.title, doc.fullOcrText)
                    }
                  },
                  modifier = Modifier.testTag("share_ocr_text_button"),
                ) {
                  Icon(
                    Icons.Filled.Share,
                    contentDescription = "Bagikan teks OCR",
                    modifier = Modifier.size(18.dp),
                  )
                }
              }
              TextButton(
                onClick = { onCopyText(doc.title, doc.fullOcrText) },
                modifier = Modifier.testTag("copy_ocr_text_button"),
              ) {
                Icon(
                  Icons.Filled.ContentCopy,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_copy_text))
              }
            }
            HorizontalDivider()
            SelectionContainer {
              Text(
                text = doc.fullOcrText.ifBlank {
                  "Belum ada teks terbaca pada pindaian ini. Coba gunakan filter Hitam Putih atau Warna di menu Edit Halaman."
                },
                style = MaterialTheme.typography.labelMedium.copy(
                  lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                  .fillMaxSize()
                  .verticalScroll(rememberScrollState())
                  .padding(16.dp),
              )
            }
          }
        }
      }
    }
  }

  if (showExportSheet) {
    ModalBottomSheet(
      onDismissRequest = { showExportSheet = false },
    ) {
      val safeFileBase = remember(doc.title) {
        doc.title.replace(Regex("[^A-Za-z0-9_\\- ]"), "").trim().ifBlank { "ClearScan" }
      }
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .navigationBarsPadding()
          .padding(bottom = 20.dp),
      ) {
        Text(
          text = "Ekspor Dokumen",
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        ListItem(
          headlineContent = { Text("Simpan PDF Searchable ke Perangkat") },
          supportingContent = { Text("Halaman pindaian tajam + lapisan teks OCR yang bisa dicari") },
          leadingContent = {
            Icon(
              Icons.Filled.PictureAsPdf,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
            )
          },
          modifier = Modifier
            .clickable {
              showExportSheet = false
              saveSearchablePdfLauncher.launch("$safeFileBase.pdf")
            }
            .testTag("export_save_searchable_pdf"),
        )

        ListItem(
          headlineContent = { Text("Simpan PDF Teks Rapi") },
          supportingContent = { Text("PDF teks vektor ringan dengan tata letak dokumen asli") },
          leadingContent = {
            Icon(
              Icons.Filled.Description,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.secondary,
            )
          },
          modifier = Modifier
            .clickable {
              showExportSheet = false
              saveTextPdfLauncher.launch("${safeFileBase}_text.pdf")
            }
            .testTag("export_save_text_pdf"),
        )

        ListItem(
          headlineContent = { Text("Bagikan PDF Searchable") },
          supportingContent = { Text("Kirim lewat WhatsApp, email, atau Google Drive") },
          leadingContent = { Icon(Icons.Filled.Share, contentDescription = null) },
          modifier = Modifier
            .clickable {
              showExportSheet = false
              onSharePdf(doc)
            }
            .testTag("export_share_pdf"),
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        ListItem(
          headlineContent = { Text("Simpan Semua Gambar ke Galeri") },
          supportingContent = { Text("Ekspor ${orderedPages.size} gambar JPEG tajam ke Pictures/ClearScan") },
          leadingContent = {
            Icon(
              Icons.Filled.Download,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.tertiary,
            )
          },
          modifier = Modifier
            .clickable {
              showExportSheet = false
              onExportImagesToGallery(documentWithPages)
            }
            .testTag("export_save_images_gallery"),
        )

        ListItem(
          headlineContent = { Text("Bagikan Gambar Halaman Ini") },
          supportingContent = { Text("Ekspor halaman ${pagerState.currentPage + 1} sebagai JPEG") },
          leadingContent = { Icon(Icons.Filled.Image, contentDescription = null) },
          modifier = Modifier
            .clickable {
              showExportSheet = false
              onShareImages(documentWithPages, pagerState.currentPage)
            }
            .testTag("export_share_current_image"),
        )

        if (orderedPages.size > 1) {
          ListItem(
            headlineContent = { Text("Bagikan Semua ${orderedPages.size} Gambar") },
            supportingContent = { Text("Ekspor seluruh halaman pindaian sebagai file JPEG") },
            leadingContent = { Icon(Icons.Filled.Image, contentDescription = null) },
            modifier = Modifier
              .clickable {
                showExportSheet = false
                onShareImages(documentWithPages, null)
              }
              .testTag("export_share_all_images"),
          )
        }
      }
    }
  }

  if (showRenameDialog) {
    AlertDialog(
      onDismissRequest = { showRenameDialog = false },
      title = { Text("Ubah Judul Dokumen") },
      text = {
        OutlinedTextField(
          value = renameText,
          onValueChange = { renameText = it },
          singleLine = true,
          label = { Text("Judul dokumen") },
          modifier = Modifier
            .fillMaxWidth()
            .testTag("viewer_rename_input"),
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            val clean = renameText.trim()
            if (clean.isNotEmpty()) {
              onRenameDocument(doc.id, clean)
            }
            showRenameDialog = false
          },
          enabled = renameText.isNotBlank(),
          modifier = Modifier.testTag("viewer_rename_confirm"),
        ) {
          Text("Simpan")
        }
      },
      dismissButton = {
        TextButton(onClick = { showRenameDialog = false }) {
          Text("Batal")
        }
      },
    )
  }

  if (showDeleteDialog) {
    AlertDialog(
      onDismissRequest = { showDeleteDialog = false },
      title = { Text("Hapus Dokumen?") },
      text = { Text("Dokumen \"${doc.title}\" dan file PDF Searchable-nya akan dihapus dari perangkat.") },
      confirmButton = {
        TextButton(
          onClick = {
            showDeleteDialog = false
            onDeleteDocument(doc.id)
          },
        ) {
          Text("Hapus", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteDialog = false }) {
          Text("Batal")
        }
      },
    )
  }
}
