package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.domain.model.DraftPage
import com.example.processing.ScanPreset
import com.example.ui.theme.LocalClearScanColors
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

@Composable
fun CameraBatchScreen(
  batchPages: List<DraftPage>,
  activePreset: ScanPreset,
  isProcessingCapture: Boolean,
  onSelectPreset: (ScanPreset) -> Unit,
  onPhotoCaptured: (File) -> Unit,
  onImportPhotos: (List<android.net.Uri>) -> Unit,
  onRemovePage: (Int) -> Unit,
  onReviewBatch: () -> Unit,
  onClose: () -> Unit,
) {
  val context = LocalContext.current
  val extendedColors = LocalClearScanColors.current

  var hasCameraPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    )
  }

  val permissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
    onResult = { granted -> hasCameraPermission = granted },
  )

  val photoPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 25),
    onResult = { uris ->
      if (uris.isNotEmpty()) {
        onImportPhotos(uris)
      }
    },
  )

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(extendedColors.viewfinderBackground),
  ) {
    if (!hasCameraPermission) {
      CameraPermissionPrompt(
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        onImportFromGallery = {
          photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
          )
        },
        onClose = onClose,
        batchCount = batchPages.size,
        onReviewBatch = onReviewBatch,
      )
    } else {
      CameraViewfinderContent(
        batchPages = batchPages,
        activePreset = activePreset,
        isProcessingCapture = isProcessingCapture,
        onSelectPreset = onSelectPreset,
        onPhotoCaptured = onPhotoCaptured,
        onImportClick = {
          photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
          )
        },
        onRemovePage = onRemovePage,
        onReviewBatch = onReviewBatch,
        onClose = onClose,
      )
    }
  }
}

@Composable
private fun CameraViewfinderContent(
  batchPages: List<DraftPage>,
  activePreset: ScanPreset,
  isProcessingCapture: Boolean,
  onSelectPreset: (ScanPreset) -> Unit,
  onPhotoCaptured: (File) -> Unit,
  onImportClick: () -> Unit,
  onRemovePage: (Int) -> Unit,
  onReviewBatch: () -> Unit,
  onClose: () -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val extendedColors = LocalClearScanColors.current
  val scope = rememberCoroutineScope()

  var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
  var showGrid by remember { mutableStateOf(true) }
  var liveOcrLines by remember { mutableIntStateOf(0) }
  var showShutterFlash by remember { mutableStateOf(false) }

  val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
  val textRecognizer = remember {
    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
  }

  val cameraController = remember {
    LifecycleCameraController(context).apply {
      cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
      setEnabledUseCases(
        CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS
      )
      imageCaptureFlashMode = flashMode
      setImageAnalysisAnalyzer(
        analysisExecutor,
        MlKitAnalyzer(
          listOf(textRecognizer),
          CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
          ContextCompat.getMainExecutor(context),
        ) { result ->
          val text = result?.getValue(textRecognizer)
          val count = text?.textBlocks?.sumOf { it.lines.size } ?: 0
          liveOcrLines = count
        },
      )
    }
  }

  DisposableEffect(lifecycleOwner) {
    cameraController.bindToLifecycle(lifecycleOwner)
    onDispose {
      cameraController.clearImageAnalysisAnalyzer()
      textRecognizer.close()
      analysisExecutor.shutdown()
    }
  }

  Box(modifier = Modifier.fillMaxSize()) {
    AndroidView(
      factory = { ctx ->
        PreviewView(ctx).apply {
          scaleType = PreviewView.ScaleType.FILL_CENTER
          implementationMode = PreviewView.ImplementationMode.COMPATIBLE
          controller = cameraController
        }
      },
      modifier = Modifier.fillMaxSize(),
    )

    // Alignment Grid & Document Corner Reticles
    Canvas(modifier = Modifier.fillMaxSize()) {
      val padX = size.width * 0.08f
      val padY = size.height * 0.16f
      val left = padX
      val right = size.width - padX
      val top = padY
      val bottom = size.height * 0.72f
      val guideColor = extendedColors.viewfinderReticle
      val cornerLen = 28.dp.toPx()
      val strokeW = 3.dp.toPx()

      if (showGrid) {
        val gridColor = Color.White.copy(alpha = 0.22f)
        val wSpan = right - left
        val hSpan = bottom - top
        for (i in 1..2) {
          drawLine(
            color = gridColor,
            start = Offset(left + (wSpan / 3f) * i, top),
            end = Offset(left + (wSpan / 3f) * i, bottom),
            strokeWidth = 1.dp.toPx(),
          )
          drawLine(
            color = gridColor,
            start = Offset(left, top + (hSpan / 3f) * i),
            end = Offset(right, top + (hSpan / 3f) * i),
            strokeWidth = 1.dp.toPx(),
          )
        }
      }

      // Top-Left corner
      drawLine(guideColor, Offset(left, top), Offset(left + cornerLen, top), strokeW)
      drawLine(guideColor, Offset(left, top), Offset(left, top + cornerLen), strokeW)
      // Top-Right corner
      drawLine(guideColor, Offset(right, top), Offset(right - cornerLen, top), strokeW)
      drawLine(guideColor, Offset(right, top), Offset(right, top + cornerLen), strokeW)
      // Bottom-Left corner
      drawLine(guideColor, Offset(left, bottom), Offset(left + cornerLen, bottom), strokeW)
      drawLine(guideColor, Offset(left, bottom), Offset(left, bottom - cornerLen), strokeW)
      // Bottom-Right corner
      drawLine(guideColor, Offset(right, bottom), Offset(right - cornerLen, bottom), strokeW)
      drawLine(guideColor, Offset(right, bottom), Offset(right, bottom - cornerLen), strokeW)
    }

    // Shutter Flash Animation
    AnimatedVisibility(
      visible = showShutterFlash,
      enter = fadeIn(),
      exit = fadeOut(),
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.White.copy(alpha = 0.65f))
      )
    }

    // Top Controls Bar
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(
        onClick = onClose,
        modifier = Modifier
          .size(48.dp)
          .clip(CircleShape)
          .background(extendedColors.viewfinderOverlay)
          .testTag("camera_close_button"),
      ) {
        Icon(
          imageVector = Icons.Filled.Close,
          contentDescription = "Close scanner",
          tint = Color.White,
        )
      }

      Surface(
        shape = RoundedCornerShape(50),
        color = extendedColors.viewfinderOverlay,
        border = BorderStroke(
          1.dp,
          if (liveOcrLines > 0) extendedColors.successBadge else Color.White.copy(alpha = 0.3f),
        ),
      ) {
        Text(
          text = if (liveOcrLines > 0) {
            "OCR Live • $liveOcrLines line${if (liveOcrLines == 1) "" else "s"}"
          } else {
            "Align document to scan"
          },
          style = MaterialTheme.typography.labelMedium,
          color = if (liveOcrLines > 0) extendedColors.successBadge else Color.White,
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
      }

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(
          onClick = { showGrid = !showGrid },
          modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(extendedColors.viewfinderOverlay)
            .testTag("camera_grid_toggle"),
        ) {
          Icon(
            imageVector = if (showGrid) Icons.Filled.GridOn else Icons.Filled.GridOff,
            contentDescription = "Toggle alignment grid",
            tint = Color.White,
          )
        }

        IconButton(
          onClick = {
            flashMode = when (flashMode) {
              ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
              ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
              else -> ImageCapture.FLASH_MODE_AUTO
            }
            cameraController.imageCaptureFlashMode = flashMode
          },
          modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(extendedColors.viewfinderOverlay)
            .testTag("camera_flash_toggle"),
        ) {
          val flashIcon = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn
            ImageCapture.FLASH_MODE_OFF -> Icons.Filled.FlashOff
            else -> Icons.Filled.FlashAuto
          }
          Icon(
            imageVector = flashIcon,
            contentDescription = "Cycle flash mode",
            tint = Color.White,
          )
        }
      }
    }

    // Bottom Batch Tray & Shutter Controls
    Column(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .background(extendedColors.viewfinderOverlay)
        .navigationBarsPadding()
        .padding(top = 10.dp, bottom = 16.dp),
    ) {
      // Scan Mode Preset Chips
      LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 10.dp),
      ) {
        itemsIndexed(ScanPreset.entries) { _, preset ->
          val selected = preset == activePreset
          Surface(
            shape = RoundedCornerShape(50),
            color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.14f),
            border = if (selected) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
            modifier = Modifier
              .clip(RoundedCornerShape(50))
              .clickable { onSelectPreset(preset) }
              .testTag("camera_preset_${preset.name.lowercase()}"),
          ) {
            Text(
              text = preset.label,
              style = MaterialTheme.typography.labelMedium,
              color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            )
          }
        }
      }

      if (batchPages.isNotEmpty()) {
        LazyRow(
          contentPadding = PaddingValues(horizontal = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        ) {
          itemsIndexed(batchPages, key = { _, page -> page.id }) { index, page ->
            Box(
              modifier = Modifier
                .size(width = 56.dp, height = 74.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.5.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(10.dp)),
            ) {
              AsyncImage(
                model = ImageRequest.Builder(context)
                  .data(File(page.enhancedImagePath))
                  .build(),
                contentDescription = "Batch page ${index + 1}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
              )

              Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                  .align(Alignment.BottomStart)
                  .padding(3.dp),
              ) {
                Text(
                  text = "${index + 1}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onPrimary,
                  modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                )
              }

              Box(
                modifier = Modifier
                  .align(Alignment.TopEnd)
                  .padding(2.dp)
                  .size(20.dp)
                  .clip(CircleShape)
                  .background(Color.Black.copy(alpha = 0.72f))
                  .clickable { onRemovePage(index) },
                contentAlignment = Alignment.Center,
              ) {
                Icon(
                  imageVector = Icons.Filled.Close,
                  contentDescription = "Remove page ${index + 1}",
                  tint = Color.White,
                  modifier = Modifier.size(12.dp),
                )
              }
            }
          }
        }
      }

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Import from Photo Picker
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          IconButton(
            onClick = onImportClick,
            modifier = Modifier
              .size(52.dp)
              .clip(CircleShape)
              .background(Color.White.copy(alpha = 0.14f))
              .testTag("camera_import_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.PhotoLibrary,
              contentDescription = "Import photos into batch",
              tint = Color.White,
            )
          }
          Spacer(Modifier.height(4.dp))
          Text(
            text = "Import",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f),
          )
        }

        // Precision Shutter Button
        Box(
          modifier = Modifier
            .size(78.dp)
            .clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(enabled = !isProcessingCapture) {
              showShutterFlash = true
              scope.launch {
                delay(140)
                showShutterFlash = false
              }
              val tempFile = File.createTempFile("cap_", ".jpg", context.cacheDir)
              val opts = ImageCapture.OutputFileOptions.Builder(tempFile).build()
              cameraController.takePicture(
                opts,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                  override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    onPhotoCaptured(tempFile)
                  }

                  override fun onError(exception: ImageCaptureException) {
                    tempFile.delete()
                  }
                },
              )
            }
            .testTag("camera_shutter_button"),
          contentAlignment = Alignment.Center,
        ) {
          if (isProcessingCapture) {
            CircularProgressIndicator(
              modifier = Modifier.size(28.dp),
              strokeWidth = 3.dp,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }

        // Review Batch Button
        Button(
          onClick = onReviewBatch,
          enabled = batchPages.isNotEmpty() && !isProcessingCapture,
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier
            .height(50.dp)
            .testTag("review_batch_button"),
        ) {
          Text(
            text = if (batchPages.isEmpty()) "Review" else "Next (${batchPages.size})",
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(Modifier.width(6.dp))
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun CameraPermissionPrompt(
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
      Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
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
            Text("Continue to Batch ($batchCount)")
          }
        }
      }
    }
  }
}
