package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.GridView
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.example.domain.model.NormalizedQuad
import com.example.processing.DocumentQuadDetector
import com.example.processing.QuadStabilityTracker
import com.example.processing.ScanPreset
import com.example.ui.theme.LocalClearScanColors
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

@Composable
fun CameraBatchScreen(
  batchPages: List<DraftPage>,
  activePreset: ScanPreset,
  isProcessingCapture: Boolean,
  isRetakingPage: Boolean,
  onSelectPreset: (ScanPreset) -> Unit,
  onPhotoCaptured: (File, NormalizedQuad?, Boolean) -> Unit,
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
        isRetakingPage = isRetakingPage,
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

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraViewfinderContent(
  batchPages: List<DraftPage>,
  activePreset: ScanPreset,
  isProcessingCapture: Boolean,
  isRetakingPage: Boolean,
  onSelectPreset: (ScanPreset) -> Unit,
  onPhotoCaptured: (File, NormalizedQuad?, Boolean) -> Unit,
  onImportClick: () -> Unit,
  onRemovePage: (Int) -> Unit,
  onReviewBatch: () -> Unit,
  onClose: () -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val extendedColors = LocalClearScanColors.current
  val scope = rememberCoroutineScope()

  var isTorchOn by remember { mutableStateOf(false) }
  var showGrid by remember { mutableStateOf(false) }
  var autoCaptureEnabled by remember { mutableStateOf(true) }
  var liveOcrLines by remember { mutableIntStateOf(0) }
  var showShutterFlash by remember { mutableStateOf(false) }

  var detectedQuad by remember { mutableStateOf<NormalizedQuad?>(null) }
  var analysisFrameSize by remember { mutableStateOf(480 to 640) }
  var stabilityProgress by remember { mutableFloatStateOf(0f) }

  val currentAutoEnabled by rememberUpdatedState(autoCaptureEnabled)
  val currentProcessing by rememberUpdatedState(isProcessingCapture)
  val currentRetaking by rememberUpdatedState(isRetakingPage)

  val stabilityTracker = remember { QuadStabilityTracker() }
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
      imageCaptureFlashMode = ImageCapture.FLASH_MODE_AUTO
    }
  }

  val triggerCapture: (NormalizedQuad?, Boolean) -> Unit = remember(cameraController) {
    { quadSnapshot, openStudioImmediately ->
      if (!currentProcessing) {
        stabilityTracker.markManualCapture(SystemClock.elapsedRealtime())
        stabilityProgress = 0f
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
              onPhotoCaptured(tempFile, quadSnapshot, openStudioImmediately)
            }

            override fun onError(exception: ImageCaptureException) {
              tempFile.delete()
            }
          },
        )
      }
    }
  }

  DisposableEffect(lifecycleOwner) {
    var lastOcrMs = 0L
    var isOcrBusy = false
    val mainExecutor = ContextCompat.getMainExecutor(context)

    cameraController.setImageAnalysisAnalyzer(
      analysisExecutor,
      ImageAnalysis.Analyzer { imageProxy ->
        val now = SystemClock.elapsedRealtime()
        val (rawQuad, uprightDims) = runCatching {
          DocumentQuadDetector.detectFromImageProxy(imageProxy)
        }.getOrDefault(null to (imageProxy.width to imageProxy.height))

        val autoReady = stabilityTracker.update(rawQuad, now)
        val smoothed = stabilityTracker.smoothedQuad
        val progress = stabilityTracker.stabilityProgress

        mainExecutor.execute {
          analysisFrameSize = uprightDims
          detectedQuad = smoothed
          stabilityProgress = progress
          if (autoReady && currentAutoEnabled && !currentProcessing) {
            triggerCapture(smoothed, currentRetaking)
          }
        }

        val mediaImg = imageProxy.image
        if (mediaImg != null && !isOcrBusy && now - lastOcrMs > 750L) {
          isOcrBusy = true
          lastOcrMs = now
          val input = InputImage.fromMediaImage(mediaImg, imageProxy.imageInfo.rotationDegrees)
          textRecognizer.process(input)
            .addOnSuccessListener { result ->
              liveOcrLines = result.textBlocks.sumOf { it.lines.size }
            }
            .addOnCompleteListener {
              isOcrBusy = false
              imageProxy.close()
            }
        } else {
          imageProxy.close()
        }
      },
    )

    cameraController.bindToLifecycle(lifecycleOwner)
    onDispose {
      cameraController.enableTorch(false)
      cameraController.clearImageAnalysisAnalyzer()
      textRecognizer.close()
      analysisExecutor.shutdown()
    }
  }

  // Smooth corner animations for Oppo-style white rounded document border
  val activeQuad = detectedQuad
  val animSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f)
  val tlX by animateFloatAsState(activeQuad?.topLeft?.x ?: 0.14f, animSpec, label = "tlX")
  val tlY by animateFloatAsState(activeQuad?.topLeft?.y ?: 0.22f, animSpec, label = "tlY")
  val trX by animateFloatAsState(activeQuad?.topRight?.x ?: 0.86f, animSpec, label = "trX")
  val trY by animateFloatAsState(activeQuad?.topRight?.y ?: 0.22f, animSpec, label = "trY")
  val brX by animateFloatAsState(activeQuad?.bottomRight?.x ?: 0.86f, animSpec, label = "brX")
  val brY by animateFloatAsState(activeQuad?.bottomRight?.y ?: 0.70f, animSpec, label = "brY")
  val blX by animateFloatAsState(activeQuad?.bottomLeft?.x ?: 0.14f, animSpec, label = "blX")
  val blY by animateFloatAsState(activeQuad?.bottomLeft?.y ?: 0.70f, animSpec, label = "blY")

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

    // Real-Time Document Quad Overlay + Optional Alignment Grid
    Canvas(modifier = Modifier.fillMaxSize()) {
      val viewW = size.width
      val viewH = size.height

      if (showGrid) {
        val gridColor = Color.White.copy(alpha = 0.2f)
        for (i in 1..2) {
          drawLine(gridColor, Offset(viewW * i / 3f, 0f), Offset(viewW * i / 3f, viewH), 1.dp.toPx())
          drawLine(gridColor, Offset(0f, viewH * i / 3f), Offset(viewW, viewH * i / 3f), 1.dp.toPx())
        }
      }

      if (activeQuad != null) {
        val frameW = analysisFrameSize.first.toFloat().coerceAtLeast(1f)
        val frameH = analysisFrameSize.second.toFloat().coerceAtLeast(1f)
        val scale = max(viewW / frameW, viewH / frameH)
        val scaledW = frameW * scale
        val scaledH = frameH * scale
        val offsetX = (viewW - scaledW) * 0.5f
        val offsetY = (viewH - scaledH) * 0.5f

        fun toView(nx: Float, ny: Float) = Offset(
          x = offsetX + nx * scaledW,
          y = offsetY + ny * scaledH,
        )

        val pTL = toView(tlX, tlY)
        val pTR = toView(trX, trY)
        val pBR = toView(brX, brY)
        val pBL = toView(blX, blY)

        // Subtle translucent document highlight
        val fillPath = Path().apply {
          moveTo(pTL.x, pTL.y)
          lineTo(pTR.x, pTR.y)
          lineTo(pBR.x, pBR.y)
          lineTo(pBL.x, pBL.y)
          close()
        }
        drawPath(path = fillPath, color = Color.White.copy(alpha = 0.08f))
        drawPath(
          path = fillPath,
          color = Color.White.copy(alpha = 0.38f),
          style = Stroke(width = 1.2.dp.toPx()),
        )

        // Oppo-style thick white rounded corner brackets hugging the quad
        val bracketColor = if (stabilityProgress >= 0.65f && autoCaptureEnabled) {
          Color.White
        } else {
          Color.White.copy(alpha = 0.96f)
        }
        val cornerStroke = Stroke(
          width = 3.5.dp.toPx(),
          cap = StrokeCap.Round,
          join = StrokeJoin.Round,
        )

        fun drawCornerBracket(corner: Offset, adjA: Offset, adjB: Offset) {
          val lenA = hypot(adjA.x - corner.x, adjA.y - corner.y).coerceAtLeast(1f)
          val lenB = hypot(adjB.x - corner.x, adjB.y - corner.y).coerceAtLeast(1f)
          val armA = min(38.dp.toPx(), lenA * 0.28f)
          val armB = min(38.dp.toPx(), lenB * 0.28f)
          val radius = min(14.dp.toPx(), min(armA, armB) * 0.45f)

          val dirAx = (adjA.x - corner.x) / lenA
          val dirAy = (adjA.y - corner.y) / lenA
          val dirBx = (adjB.x - corner.x) / lenB
          val dirBy = (adjB.y - corner.y) / lenB

          val startA = Offset(corner.x + dirAx * armA, corner.y + dirAy * armA)
          val curveStartA = Offset(corner.x + dirAx * radius, corner.y + dirAy * radius)
          val curveEndB = Offset(corner.x + dirBx * radius, corner.y + dirBy * radius)
          val endB = Offset(corner.x + dirBx * armB, corner.y + dirBy * armB)

          val path = Path().apply {
            moveTo(startA.x, startA.y)
            lineTo(curveStartA.x, curveStartA.y)
            quadraticTo(corner.x, corner.y, curveEndB.x, curveEndB.y)
            lineTo(endB.x, endB.y)
          }
          drawPath(path = path, color = bracketColor, style = cornerStroke)
        }

        drawCornerBracket(pTL, pTR, pBL)
        drawCornerBracket(pTR, pTL, pBR)
        drawCornerBracket(pBR, pTR, pBL)
        drawCornerBracket(pBL, pTL, pBR)
      } else {
        // Subtle alignment reticle when searching for document edges
        val padX = viewW * 0.10f
        val left = padX
        val right = viewW - padX
        val top = viewH * 0.20f
        val bottom = viewH * 0.68f
        val cornerLen = 26.dp.toPx()
        val strokeW = 2.5.dp.toPx()
        val guideColor = Color.White.copy(alpha = 0.55f)
        drawLine(guideColor, Offset(left, top), Offset(left + cornerLen, top), strokeW, StrokeCap.Round)
        drawLine(guideColor, Offset(left, top), Offset(left, top + cornerLen), strokeW, StrokeCap.Round)
        drawLine(guideColor, Offset(right, top), Offset(right - cornerLen, top), strokeW, StrokeCap.Round)
        drawLine(guideColor, Offset(right, top), Offset(right, top + cornerLen), strokeW, StrokeCap.Round)
        drawLine(guideColor, Offset(left, bottom), Offset(left + cornerLen, bottom), strokeW, StrokeCap.Round)
        drawLine(guideColor, Offset(left, bottom), Offset(left, bottom - cornerLen), strokeW, StrokeCap.Round)
        drawLine(guideColor, Offset(right, bottom), Offset(right - cornerLen, bottom), strokeW, StrokeCap.Round)
        drawLine(guideColor, Offset(right, bottom), Offset(right, bottom - cornerLen), strokeW, StrokeCap.Round)
      }
    }

    // Shutter Flash
    AnimatedVisibility(visible = showShutterFlash, enter = fadeIn(), exit = fadeOut()) {
      Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.65f)))
    }

    // Top Controls Bar (Close, Auto-Capture Pill, Torch & Grid)
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(horizontal = 14.dp, vertical = 10.dp),
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
        Icon(Icons.Filled.Close, contentDescription = "Tutup kamera", tint = Color.White)
      }

      Surface(
        shape = RoundedCornerShape(50),
        color = extendedColors.viewfinderOverlay,
        border = BorderStroke(
          1.dp,
          when {
            activeQuad != null -> extendedColors.successBadge
            liveOcrLines > 0 -> Color.White.copy(alpha = 0.5f)
            else -> Color.White.copy(alpha = 0.25f)
          },
        ),
        modifier = Modifier
          .clip(RoundedCornerShape(50))
          .clickable { autoCaptureEnabled = !autoCaptureEnabled }
          .testTag("camera_auto_toggle"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = if (autoCaptureEnabled) extendedColors.successBadge else Color.White.copy(alpha = 0.65f),
            modifier = Modifier.size(15.dp),
          )
          Text(
            text = when {
              activeQuad != null && autoCaptureEnabled && stabilityProgress > 0.25f -> "Tahan sebentar…"
              activeQuad != null -> "Dokumen terdeteksi"
              autoCaptureEnabled -> "Auto • Arahkan ke dokumen"
              else -> "Manual • OCR $liveOcrLines baris"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (activeQuad != null) Color.White else Color.White.copy(alpha = 0.9f),
          )
        }
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
            contentDescription = "Toggle grid",
            tint = Color.White,
          )
        }

        IconButton(
          onClick = {
            isTorchOn = !isTorchOn
            cameraController.enableTorch(isTorchOn)
          },
          modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
              if (isTorchOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
              else extendedColors.viewfinderOverlay
            )
            .testTag("camera_flash_toggle"),
        ) {
          Icon(
            imageVector = if (isTorchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
            contentDescription = "Nyalakan senter",
            tint = Color.White,
          )
        }
      }
    }

    // Bottom Floating "Pindai dokumen >" Pill + Presets + Oppo Iridescent Shutter Deck
    Column(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(bottom = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      // Floating "Pindai dokumen >" pill (shown prominently when document detected or batch has pages)
      AnimatedVisibility(
        visible = activeQuad != null || batchPages.isNotEmpty() || isRetakingPage,
        enter = fadeIn(),
        exit = fadeOut(),
      ) {
        Surface(
          shape = RoundedCornerShape(18.dp),
          color = Color.Black.copy(alpha = 0.45f),
          border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
          modifier = Modifier
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = !isProcessingCapture) {
              if (activeQuad != null || batchPages.isEmpty()) {
                triggerCapture(activeQuad, true)
              } else {
                onReviewBatch()
              }
            }
            .testTag("pindai_dokumen_pill"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            val latestThumb = batchPages.lastOrNull()?.enhancedImagePath
            if (latestThumb != null && activeQuad == null) {
              AsyncImage(
                model = ImageRequest.Builder(context).data(File(latestThumb)).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                  .size(width = 28.dp, height = 36.dp)
                  .clip(RoundedCornerShape(6.dp))
                  .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(6.dp)),
              )
            } else {
              Box(
                modifier = Modifier
                  .size(width = 28.dp, height = 34.dp)
                  .clip(RoundedCornerShape(6.dp))
                  .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
              ) {
                Icon(
                  imageVector = Icons.Filled.Description,
                  contentDescription = null,
                  tint = Color.White,
                  modifier = Modifier.size(18.dp),
                )
              }
            }

            Text(
              text = if (isRetakingPage) "Ambil ulang foto" else "Pindai dokumen",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
              color = Color.White,
            )

            Icon(
              imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
              contentDescription = null,
              tint = Color.White,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }

      // Bottom Scrim Container for Preset Chips, Filmstrip & Shutter Controls
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(
            Brush.verticalGradient(
              colors = listOf(
                Color.Transparent,
                extendedColors.viewfinderOverlay.copy(alpha = 0.85f),
              )
            )
          )
          .padding(top = 8.dp),
      ) {
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
              color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.16f),
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

        if (batchPages.isNotEmpty() && !isRetakingPage) {
          LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .padding(bottom = 12.dp),
          ) {
            itemsIndexed(batchPages, key = { _, page -> page.id }) { index, page ->
              Box(
                modifier = Modifier
                  .size(width = 48.dp, height = 64.dp)
                  .clip(RoundedCornerShape(8.dp))
                  .border(1.5.dp, Color.White.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                  .clickable { onReviewBatch() },
              ) {
                AsyncImage(
                  model = ImageRequest.Builder(context)
                    .data(File(page.enhancedImagePath))
                    .build(),
                  contentDescription = "Halaman ${index + 1}",
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.fillMaxSize(),
                )
                Box(
                  modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .clickable { onRemovePage(index) },
                  contentAlignment = Alignment.Center,
                ) {
                  Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Hapus halaman ${index + 1}",
                    tint = Color.White,
                    modifier = Modifier.size(11.dp),
                  )
                }
              }
            }
          }
        }

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // Gallery Import Circular Button
          IconButton(
            onClick = onImportClick,
            modifier = Modifier
              .size(52.dp)
              .clip(CircleShape)
              .background(Color.White.copy(alpha = 0.22f))
              .testTag("camera_import_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.PhotoLibrary,
              contentDescription = "Impor foto dari galeri",
              tint = Color.White,
            )
          }

          // Oppo-Style Iridescent Outer Ring + White Shutter + Auto-Capture Arc
          Box(
            modifier = Modifier
              .size(84.dp)
              .clip(CircleShape)
              .clickable(enabled = !isProcessingCapture) {
                triggerCapture(activeQuad, currentRetaking)
              }
              .testTag("camera_shutter_button"),
            contentAlignment = Alignment.Center,
          ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
              val ringBrush = Brush.sweepGradient(
                colors = listOf(
                  Color(0xFF4ADE80),
                  Color(0xFF38BDF8),
                  Color(0xFFA78BFA),
                  Color(0xFFF472B6),
                  Color(0xFF4ADE80),
                )
              )
              drawCircle(
                brush = ringBrush,
                radius = size.minDimension / 2f - 3.dp.toPx(),
                style = Stroke(width = 4.dp.toPx()),
              )
              if (autoCaptureEnabled && stabilityProgress > 0.05f) {
                drawArc(
                  color = Color.White,
                  startAngle = -90f,
                  sweepAngle = 360f * stabilityProgress,
                  useCenter = false,
                  topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
                  size = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()),
                  style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round),
                )
              }
            }

            Box(
              modifier = Modifier
                .size(66.dp)
                .clip(CircleShape)
                .background(Color.White),
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
          }

          // Review / Edit Studio Circular Grid Button with Page Count Badge
          Box(contentAlignment = Alignment.TopEnd) {
            IconButton(
              onClick = onReviewBatch,
              enabled = batchPages.isNotEmpty() && !isProcessingCapture,
              modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                  if (batchPages.isNotEmpty()) Color.White.copy(alpha = 0.28f)
                  else Color.White.copy(alpha = 0.14f)
                )
                .testTag("review_batch_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.GridView,
                contentDescription = "Buka Edit Studio",
                tint = Color.White,
              )
            }
            if (batchPages.isNotEmpty()) {
              Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp, end = 2.dp),
              ) {
                Text(
                  text = "${batchPages.size}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onPrimary,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
              }
            }
          }
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
            Text("Lanjut ke Edit ($batchCount)")
          }
        }
      }
    }
  }
}
