package com.example.ui.screens

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.local.ScannedDocumentEntity
import com.example.processing.DocumentActionType
import com.example.processing.SmartScanAdvisor
import com.example.ui.theme.LocalClearScanColors
import java.io.File
import java.util.Date

@Composable
fun LibraryScreen(
  documents: List<ScannedDocumentEntity>,
  searchQuery: String,
  isImporting: Boolean,
  snackbarHostState: SnackbarHostState,
  onSearchQueryChange: (String) -> Unit,
  onStartBatchScan: () -> Unit,
  onImportPhotos: (List<android.net.Uri>) -> Unit,
  onOpenDocument: (String) -> Unit,
  onEditDocument: (String) -> Unit,
  onRenameDocument: (String, String) -> Unit,
  onSharePdf: (ScannedDocumentEntity) -> Unit,
  onDeleteDocument: (String) -> Unit,
) {
  var onlyMultiPage by remember { mutableStateOf(false) }
  var renamingDoc by remember { mutableStateOf<ScannedDocumentEntity?>(null) }
  var renameText by remember { mutableStateOf("") }

  val displayedDocuments = remember(documents, onlyMultiPage) {
    if (onlyMultiPage) documents.filter { it.pageCount > 1 } else documents
  }
  val hasMultiPage = remember(documents) { documents.any { it.pageCount > 1 } }
  val photoPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 25),
    onResult = { uris ->
      if (uris.isNotEmpty()) {
        onImportPhotos(uris)
      }
    },
  )

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    floatingActionButton = {
      if (documents.isNotEmpty()) {
        Row(
          modifier = Modifier.navigationBarsPadding(),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          FilledTonalButton(
            onClick = {
              photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
              )
            },
            modifier = Modifier
              .height(52.dp)
              .testTag("import_photos_fab"),
            shape = RoundedCornerShape(16.dp),
          ) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_import_photos))
          }

          ExtendedFloatingActionButton(
            onClick = onStartBatchScan,
            modifier = Modifier.testTag("start_batch_scan_fab"),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(18.dp),
            icon = {
              Icon(Icons.Filled.DocumentScanner, contentDescription = null)
            },
            text = {
              Text(stringResource(R.string.action_start_batch_scan))
            },
          )
        }
      }
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(horizontal = 16.dp)
          .widthIn(max = 960.dp)
          .align(Alignment.TopCenter),
      ) {
        Spacer(Modifier.height(44.dp))

        // Editorial Header
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(R.string.app_name),
              style = MaterialTheme.typography.headlineMedium,
              color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
              text = stringResource(R.string.library_subtitle),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer,
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = Icons.Filled.PictureAsPdf,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(16.dp),
              )
              Spacer(Modifier.width(6.dp))
              Text(
                text = "${documents.size} PDF",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
          }
        }

        Spacer(Modifier.height(16.dp))

        // Searchable OCR Search Bar
        OutlinedTextField(
          value = searchQuery,
          onValueChange = onSearchQueryChange,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("library_search_input"),
          placeholder = {
            Text(
              text = stringResource(R.string.search_placeholder),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          },
          leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = "Cari dokumen")
          },
          trailingIcon = {
            if (searchQuery.isNotEmpty()) {
              IconButton(
                onClick = { onSearchQueryChange("") },
                modifier = Modifier.testTag("clear_search_button"),
              ) {
                Icon(Icons.Filled.Close, contentDescription = "Hapus pencarian")
              }
            }
          },
          singleLine = true,
          shape = RoundedCornerShape(16.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
          ),
        )

        val searchSuggestions = remember(documents) {
          SmartScanAdvisor.suggestLibrarySearchTags(documents.map { it.fullOcrText })
        }
        if (searchSuggestions.isNotEmpty() || hasMultiPage) {
          Spacer(Modifier.height(10.dp))
          LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
          ) {
            if (hasMultiPage) {
              item {
                FilterChip(
                  selected = !onlyMultiPage && searchQuery.isBlank(),
                  onClick = {
                    onlyMultiPage = false
                    onSearchQueryChange("")
                  },
                  label = { Text("Semua") },
                  modifier = Modifier.testTag("library_filter_all"),
                )
              }
              item {
                FilterChip(
                  selected = onlyMultiPage,
                  onClick = { onlyMultiPage = !onlyMultiPage },
                  label = { Text("Multi-hal") },
                  modifier = Modifier.testTag("library_filter_multipage"),
                )
              }
            }
            itemsIndexed(searchSuggestions) { idx, tag ->
              val selected = searchQuery.equals(tag, ignoreCase = true)
              FilterChip(
                selected = selected,
                onClick = { onSearchQueryChange(if (selected) "" else tag) },
                leadingIcon = {
                  Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                  )
                },
                label = { Text(tag) },
                modifier = Modifier.testTag("library_search_suggestion_$idx"),
              )
            }
          }
        }

        Spacer(Modifier.height(12.dp))

        if (documents.isEmpty() && searchQuery.isBlank()) {
          EmptyLibraryState(
            onStartBatchScan = onStartBatchScan,
            onImportClick = {
              photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
              )
            },
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth(),
          )
        } else if (displayedDocuments.isEmpty()) {
          Box(
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth(),
            contentAlignment = Alignment.Center,
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
              )
              Spacer(Modifier.height(12.dp))
              Text(
                text = "Judul atau teks tidak ditemukan",
                style = MaterialTheme.typography.titleMedium,
              )
              Spacer(Modifier.height(4.dp))
              Text(
                text = "Coba kata kunci lain atau nonaktifkan filter yang aktif.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else {
          LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 300.dp),
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            items(displayedDocuments, key = { it.id }) { doc ->
              ScannedDocumentCard(
                doc = doc,
                searchQuery = searchQuery,
                onClick = { onOpenDocument(doc.id) },
                onEdit = { onEditDocument(doc.id) },
                onRename = {
                  renamingDoc = doc
                  renameText = doc.title
                },
                onSharePdf = { onSharePdf(doc) },
                onDelete = { onDeleteDocument(doc.id) },
              )
            }
          }
        }
      }

      if (isImporting) {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background.copy(alpha = 0.78f),
        ) {
          Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
              text = "Mempertajam halaman & memindai teks OCR…",
              style = MaterialTheme.typography.titleMedium,
            )
          }
        }
      }
    }
  }

  renamingDoc?.let { targetDoc ->
    AlertDialog(
      onDismissRequest = { renamingDoc = null },
      title = { Text("Ubah Judul Dokumen") },
      text = {
        OutlinedTextField(
          value = renameText,
          onValueChange = { renameText = it },
          singleLine = true,
          label = { Text("Judul dokumen") },
          modifier = Modifier
            .fillMaxWidth()
            .testTag("library_rename_input"),
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            val clean = renameText.trim()
            if (clean.isNotEmpty()) {
              onRenameDocument(targetDoc.id, clean)
            }
            renamingDoc = null
          },
          enabled = renameText.isNotBlank(),
          modifier = Modifier.testTag("library_rename_confirm"),
        ) {
          Text("Simpan")
        }
      },
      dismissButton = {
        TextButton(onClick = { renamingDoc = null }) {
          Text("Batal")
        }
      },
    )
  }
}

@Composable
private fun EmptyLibraryState(
  onStartBatchScan: () -> Unit,
  onImportClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .verticalScroll(rememberScrollState())
      .navigationBarsPadding()
      .padding(bottom = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Card(
      shape = RoundedCornerShape(24.dp),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = 520.dp),
    ) {
      Image(
        painter = painterResource(id = R.drawable.img_scanner_hero),
        contentDescription = "Ilustrasi pemindaian dokumen dan OCR",
        contentScale = ContentScale.Crop,
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(16f / 9f),
      )

      Column(modifier = Modifier.padding(20.dp)) {
        Text(
          text = stringResource(R.string.empty_library_title),
          style = MaterialTheme.typography.headlineSmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
          text = stringResource(R.string.empty_library_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Button(
            onClick = onStartBatchScan,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            modifier = Modifier
              .weight(1.1f)
              .height(52.dp)
              .testTag("empty_start_scan_button"),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primary,
            ),
          ) {
            Icon(Icons.Filled.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
              text = stringResource(R.string.action_start_batch_scan),
              maxLines = 1,
              softWrap = false,
              overflow = TextOverflow.Ellipsis,
            )
          }

          FilledTonalButton(
            onClick = onImportClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            modifier = Modifier
              .weight(0.9f)
              .height(52.dp)
              .testTag("empty_import_button"),
            shape = RoundedCornerShape(14.dp),
          ) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
              text = stringResource(R.string.action_import_photos),
              maxLines = 1,
              softWrap = false,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ScannedDocumentCard(
  doc: ScannedDocumentEntity,
  searchQuery: String,
  onClick: () -> Unit,
  onEdit: () -> Unit,
  onRename: () -> Unit,
  onSharePdf: () -> Unit,
  onDelete: () -> Unit,
) {
  val context = LocalContext.current
  val extendedColors = LocalClearScanColors.current
  var menuExpanded by remember { mutableStateOf(false) }

  val snippet = remember(doc.fullOcrText, searchQuery) {
    buildSnippet(doc.fullOcrText, searchQuery)
  }
  val highlightAction = remember(doc.fullOcrText) {
    val actions = SmartScanAdvisor.extractDocumentActions(doc.fullOcrText)
    actions.firstOrNull { it.type == DocumentActionType.AMOUNT } ?: actions.firstOrNull()
  }
  val dateText = remember(doc.updatedAt) {
    DateFormat.format("d MMM yyyy • HH:mm", Date(doc.updatedAt)).toString()
  }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(18.dp))
      .clickable(onClick = onClick)
      .testTag("document_card_${doc.id}"),
    shape = RoundedCornerShape(18.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.Top,
    ) {
      // Page Thumbnail with Batch Count Badge
      Box(
        modifier = Modifier
          .width(76.dp)
          .height(102.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant),
      ) {
        AsyncImage(
          model = ImageRequest.Builder(context)
            .data(File(doc.thumbnailPath))
            .crossfade(true)
            .build(),
          contentDescription = "Pratinjau ${doc.title}",
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
        Surface(
          modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(4.dp),
          shape = RoundedCornerShape(6.dp),
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        ) {
          Text(
            text = "${doc.pageCount} hal",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }
      }

      Spacer(Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = doc.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )

          Box {
            IconButton(
              onClick = { menuExpanded = true },
              modifier = Modifier
                .size(48.dp)
                .testTag("doc_menu_${doc.id}"),
            ) {
              Icon(Icons.Filled.MoreVert, contentDescription = "Opsi dokumen")
            }
            DropdownMenu(
              expanded = menuExpanded,
              onDismissRequest = { menuExpanded = false },
            ) {
              DropdownMenuItem(
                text = { Text("Edit Halaman") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                  menuExpanded = false
                  onEdit()
                },
              )
              DropdownMenuItem(
                text = { Text("Ubah Judul") },
                leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                onClick = {
                  menuExpanded = false
                  onRename()
                },
              )
              DropdownMenuItem(
                text = { Text("Bagikan PDF Searchable") },
                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                onClick = {
                  menuExpanded = false
                  onSharePdf()
                },
              )
              DropdownMenuItem(
                text = { Text("Hapus Dokumen") },
                leadingIcon = {
                  Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                  )
                },
                onClick = {
                  menuExpanded = false
                  onDelete()
                },
              )
            }
          }
        }

        Text(
          text = dateText,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(6.dp))

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = Icons.Filled.Verified,
                contentDescription = null,
                tint = extendedColors.successBadge,
                modifier = Modifier.size(13.dp),
              )
              Spacer(Modifier.width(4.dp))
              Text(
                text = if (doc.fullOcrText.isNotBlank()) "PDF Searchable" else "PDF Pindaian",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
              )
            }
          }

          if (highlightAction != null) {
            Surface(
              shape = CircleShape,
              color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector = when (highlightAction.type) {
                    DocumentActionType.EMAIL -> Icons.Filled.Email
                    DocumentActionType.URL -> Icons.Filled.Language
                    DocumentActionType.PHONE -> Icons.Filled.Phone
                    DocumentActionType.AMOUNT -> Icons.Filled.AttachMoney
                  },
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onPrimaryContainer,
                  modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                  text = highlightAction.displayLabel,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
        }

        if (snippet.isNotBlank()) {
          Spacer(Modifier.height(8.dp))
          Text(
            text = snippet,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

private fun buildSnippet(fullText: String, query: String): String {
  val cleaned = fullText.replace(Regex("\\s+"), " ").trim()
  if (cleaned.isEmpty()) return ""
  val q = query.trim()
  if (q.isEmpty()) return cleaned.take(110)
  val idx = cleaned.indexOf(q, ignoreCase = true)
  if (idx < 0) return cleaned.take(110)
  val start = (idx - 24).coerceAtLeast(0)
  val end = (idx + q.length + 60).coerceAtMost(cleaned.length)
  val prefix = if (start > 0) "…" else ""
  val suffix = if (end < cleaned.length) "…" else ""
  return prefix + cleaned.substring(start, end) + suffix
}
