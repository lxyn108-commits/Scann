package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R

@Composable
fun CameraPermissionPrompt(
  onRequestPermission: () -> Unit,
  onImportFromGallery: () -> Unit,
  onClose: () -> Unit,
  batchCount: Int,
  onReviewBatch: () -> Unit,
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .statusBarsPadding()
      .navigationBarsPadding()
      .padding(24.dp),
  ) {
    IconButton(
      onClick = onClose,
      modifier = Modifier
        .align(Alignment.TopStart)
        .size(48.dp)
        .clip(CircleShape)
        .background(Color.White.copy(alpha = 0.12f)),
    ) {
      Icon(Icons.Filled.Close, contentDescription = "Tutup kamera", tint = Color.White)
    }

    Card(
      modifier = Modifier.align(Alignment.Center),
      shape = RoundedCornerShape(24.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
      Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primaryContainer,
          modifier = Modifier.size(64.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Filled.CameraAlt,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.size(30.dp),
            )
          }
        }
        Spacer(Modifier.height(16.dp))
        Text(
          text = stringResource(R.string.camera_permission_title),
          style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
          text = stringResource(R.string.camera_permission_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(
          onClick = onRequestPermission,
          modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .testTag("grant_camera_button"),
          shape = RoundedCornerShape(14.dp),
        ) {
          Text(stringResource(R.string.action_grant_camera))
        }
        Spacer(Modifier.height(10.dp))
        FilledTonalButton(
          onClick = onImportFromGallery,
          modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .testTag("permission_fallback_import_button"),
          shape = RoundedCornerShape(14.dp),
        ) {
          Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text(stringResource(R.string.action_import_photos))
        }
        if (batchCount > 0) {
          Spacer(Modifier.height(10.dp))
          FilledTonalButton(
            onClick = onReviewBatch,
            modifier = Modifier
              .fillMaxWidth()
              .height(50.dp),
            shape = RoundedCornerShape(14.dp),
          ) {
            Text("Lanjut ke Edit ($batchCount)")
          }
        }
      }
    }
  }
}
