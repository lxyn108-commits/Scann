package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.DraftPage
import com.example.domain.model.EnhancementFilter

@Composable
fun EditStudioActionStrip(
  shadowRemovalEnabled: Boolean,
  onRetakePage: () -> Unit,
  onRotatePage: () -> Unit,
  onStartStraighten: () -> Unit,
  onExtractText: () -> Unit,
  onToggleShadowRemoval: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.Top,
  ) {
    StudioToolItem(
      icon = Icons.Filled.CameraAlt,
      label = "Ambil\nulang",
      isActive = false,
      testTag = "tool_retake_button",
      onClick = onRetakePage,
      modifier = Modifier.weight(1f),
    )
    StudioToolItem(
      icon = Icons.AutoMirrored.Filled.RotateRight,
      label = "Putar",
      isActive = false,
      testTag = "rotate_page_button",
      onClick = onRotatePage,
      modifier = Modifier.weight(1f),
    )
    StudioToolItem(
      icon = Icons.Filled.CropFree,
      label = "Luruskan",
      isActive = false,
      testTag = "crop_page_button",
      onClick = onStartStraighten,
      modifier = Modifier.weight(1f),
    )
    StudioToolItem(
      icon = Icons.Filled.TextFields,
      label = "Teks\nOCR",
      isActive = false,
      testTag = "tool_extract_text_button",
      onClick = onExtractText,
      modifier = Modifier.weight(1f),
    )
    StudioToolItem(
      icon = Icons.Filled.AutoFixHigh,
      label = "Hapus\nbayang",
      isActive = shadowRemovalEnabled,
      testTag = "tool_shadow_removal_button",
      onClick = onToggleShadowRemoval,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun StudioToolItem(
  icon: ImageVector,
  label: String,
  isActive: Boolean,
  testTag: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val tint = if (isActive) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.9f)
  Column(
    modifier = modifier
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .padding(vertical = 6.dp, horizontal = 2.dp)
      .testTag(testTag),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier = Modifier
        .size(36.dp)
        .clip(CircleShape)
        .background(
          if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent
        ),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label.replace('\n', ' '),
        tint = tint,
        modifier = Modifier.size(22.dp),
      )
    }
    Spacer(Modifier.height(3.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall.copy(
        fontSize = androidx.compose.ui.unit.TextUnit(10.5f, androidx.compose.ui.unit.TextUnitType.Sp),
        lineHeight = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp),
      ),
      color = tint,
      textAlign = TextAlign.Center,
      maxLines = 2,
      softWrap = true,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
fun CircularFilterSelectorRow(
  currentPage: DraftPage?,
  pageCount: Int,
  onApplyFilter: (EnhancementFilter) -> Unit,
  onApplyFilterToAll: (EnhancementFilter) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
      ) {
        EnhancementFilter.entries.forEach { filter ->
          val selected = currentPage?.filter == filter
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(14.dp))
              .clickable { onApplyFilter(filter) }
              .padding(vertical = 4.dp)
              .testTag("filter_chip_${filter.name.lowercase()}"),
          ) {
            Box(
              modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .then(
                  if (selected) {
                    Modifier.border(2.5.dp, Color.White, CircleShape).padding(3.dp)
                  } else {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                  }
                )
                .clip(CircleShape),
              contentAlignment = Alignment.Center,
            ) {
              when (filter) {
                EnhancementFilter.ORIGINAL -> {
                  Box(
                    modifier = Modifier
                      .matchParentSize()
                      .background(Color.White.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                  ) {
                    Icon(
                      imageVector = Icons.Filled.Block,
                      contentDescription = filter.displayName,
                      tint = Color.White,
                      modifier = Modifier.size(24.dp),
                    )
                  }
                }
                EnhancementFilter.MAGIC_COLOR -> {
                  Box(
                    modifier = Modifier
                      .matchParentSize()
                      .background(
                        Brush.sweepGradient(
                          colors = listOf(
                            Color(0xFFEC4899),
                            Color(0xFFF59E0B),
                            Color(0xFF10B981),
                            Color(0xFF38BDF8),
                            Color(0xFF8B5CF6),
                            Color(0xFFEC4899),
                          )
                        )
                      )
                  )
                }
                EnhancementFilter.CRISP_BW -> {
                  Box(
                    modifier = Modifier
                      .matchParentSize()
                      .background(
                        Brush.linearGradient(
                          colors = listOf(Color(0xFF1F2937), Color(0xFFD1D5DB), Color(0xFF111827)),
                          start = Offset.Zero,
                          end = Offset(120f, 120f),
                        )
                      )
                  )
                }
                EnhancementFilter.GRAYSCALE -> {
                  Box(
                    modifier = Modifier
                      .matchParentSize()
                      .background(
                        Brush.radialGradient(
                          colors = listOf(Color.White, Color(0xFF9CA3AF), Color(0xFF374151)),
                        )
                      )
                  )
                }
              }
            }

            Spacer(Modifier.height(6.dp))
            Text(
              text = filter.displayName,
              style = MaterialTheme.typography.labelSmall,
              fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
              color = if (selected) Color.White else Color.White.copy(alpha = 0.72f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }

    if (pageCount > 1 && currentPage != null) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
      ) {
        TextButton(
          onClick = { onApplyFilterToAll(currentPage.filter) },
          modifier = Modifier.testTag("apply_filter_all_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp),
          )
          Spacer(Modifier.width(6.dp))
          Text(
            text = "Terapkan \"${currentPage.filter.displayName}\" ke semua ($pageCount hal.)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractedTextModalSheet(
  pageNumber: Int,
  extractedText: String,
  onCopyText: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(horizontal = 20.dp)
        .padding(bottom = 24.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Teks Hasil Ekstraksi (Hal. $pageNumber)",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Button(
          onClick = { onCopyText(extractedText) },
          enabled = extractedText.isNotBlank(),
          shape = RoundedCornerShape(12.dp),
        ) {
          Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text("Salin")
        }
      }
      Spacer(Modifier.height(10.dp))
      HorizontalDivider()
      Spacer(Modifier.height(12.dp))
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
          .fillMaxWidth()
          .height(260.dp),
      ) {
        SelectionContainer {
          Text(
            text = extractedText.ifBlank {
              "Belum ada teks yang terbaca di halaman ini. Coba gunakan filter Hitam putih atau rapikan sudut dengan Meluruskan."
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
              .verticalScroll(rememberScrollState())
              .padding(14.dp),
          )
        }
      }
    }
  }
}
