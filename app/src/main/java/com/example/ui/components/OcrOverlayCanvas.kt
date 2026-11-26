package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.domain.model.NormalizedCropRect
import com.example.domain.model.OcrLineBox
import com.example.ui.theme.LocalClearScanColors
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

@Composable
fun ScannedPagePreview(
  imagePath: String,
  imageWidth: Int,
  imageHeight: Int,
  ocrLines: List<OcrLineBox>,
  showOcrBoxes: Boolean,
  searchQuery: String,
  contentDescription: String,
  modifier: Modifier = Modifier,
  zoomable: Boolean = false,
) {
  val context = LocalContext.current
  val extendedColors = LocalClearScanColors.current
  val trimmedQuery = searchQuery.trim()
  val aspectRatio = remember(imageWidth, imageHeight) {
    if (imageWidth > 0 && imageHeight > 0) imageWidth.toFloat() / imageHeight.toFloat() else 0.75f
  }
  var isZoomed by remember(imagePath) { mutableStateOf(false) }
  var panOffset by remember(imagePath) { mutableStateOf(Offset.Zero) }
  val animatedScale by animateFloatAsState(targetValue = if (isZoomed) 2.2f else 1f, label = "pageZoom")

  BoxWithConstraints(
    modifier = modifier.clipToBounds(),
    contentAlignment = Alignment.Center,
  ) {
    val maxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
    val maxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
    val boxRatio = maxW / maxH
    val (fitW, fitH) = if (aspectRatio > boxRatio) {
      maxW to (maxW / aspectRatio)
    } else {
      (maxH * aspectRatio) to maxH
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fitWDp = with(density) { fitW.toDp() }
    val fitHDp = with(density) { fitH.toDp() }

    Box(
      modifier = Modifier
        .padding(4.dp)
        .background(MaterialTheme.colorScheme.surface)
        .then(
          if (zoomable) {
            Modifier
              .pointerInput(imagePath) {
                detectTapGestures(
                  onDoubleTap = {
                    isZoomed = !isZoomed
                    if (!isZoomed) panOffset = Offset.Zero
                  }
                )
              }
              .then(
                if (isZoomed) {
                  Modifier.pointerInput(imagePath, fitW, fitH) {
                    detectDragGestures { change, dragAmount ->
                      change.consume()
                      val maxPanX = (fitW * 1.2f) * 0.5f
                      val maxPanY = (fitH * 1.2f) * 0.5f
                      panOffset = Offset(
                        x = (panOffset.x + dragAmount.x).coerceIn(-maxPanX, maxPanX),
                        y = (panOffset.y + dragAmount.y).coerceIn(-maxPanY, maxPanY),
                      )
                    }
                  }
                } else {
                  Modifier
                }
              )
          } else {
            Modifier
          }
        ),
      contentAlignment = Alignment.Center,
    ) {
      Box(
        modifier = Modifier
          .align(Alignment.Center)
          .size(fitWDp, fitHDp)
          .graphicsLayer {
            scaleX = animatedScale
            scaleY = animatedScale
            translationX = if (isZoomed) panOffset.x else 0f
            translationY = if (isZoomed) panOffset.y else 0f
          }
      ) {
        AsyncImage(
          model = ImageRequest.Builder(context)
            .data(File(imagePath))
            .crossfade(true)
            .memoryCacheKey("${imagePath}_${File(imagePath).lastModified()}")
            .build(),
          contentDescription = contentDescription,
          contentScale = ContentScale.FillBounds,
          modifier = Modifier.fillMaxSize(),
        )

        if (showOcrBoxes || trimmedQuery.isNotEmpty()) {
          Canvas(modifier = Modifier.fillMaxSize()) {
            for (line in ocrLines) {
              val isLineMatch = trimmedQuery.isNotEmpty() &&
                line.text.contains(trimmedQuery, ignoreCase = true)

              if (showOcrBoxes && !isLineMatch) {
                val rectLeft = line.left * size.width
                val rectTop = line.top * size.height
                val rectW = (line.right - line.left) * size.width
                val rectH = (line.bottom - line.top) * size.height
                drawRect(
                  color = extendedColors.ocrHighlight,
                  topLeft = Offset(rectLeft, rectTop),
                  size = Size(rectW, rectH),
                )
                drawRect(
                  color = extendedColors.ocrHighlightBorder,
                  topLeft = Offset(rectLeft, rectTop),
                  size = Size(rectW, rectH),
                  style = Stroke(width = 1.5.dp.toPx()),
                )
              }

              if (isLineMatch) {
                val matchingWords = line.words.filter {
                  it.text.contains(trimmedQuery, ignoreCase = true)
                }
                if (matchingWords.isNotEmpty()) {
                  for (word in matchingWords) {
                    val wl = word.left * size.width
                    val wt = word.top * size.height
                    val ww = (word.right - word.left) * size.width
                    val wh = (word.bottom - word.top) * size.height
                    drawRect(
                      color = extendedColors.searchMatchHighlight,
                      topLeft = Offset(wl, wt),
                      size = Size(ww, wh),
                    )
                    drawRect(
                      color = extendedColors.searchMatchBorder,
                      topLeft = Offset(wl, wt),
                      size = Size(ww, wh),
                      style = Stroke(width = 2.dp.toPx()),
                    )
                  }
                } else {
                  val rl = line.left * size.width
                  val rt = line.top * size.height
                  val rw = (line.right - line.left) * size.width
                  val rh = (line.bottom - line.top) * size.height
                  drawRect(
                    color = extendedColors.searchMatchHighlight,
                    topLeft = Offset(rl, rt),
                    size = Size(rw, rh),
                  )
                  drawRect(
                    color = extendedColors.searchMatchBorder,
                    topLeft = Offset(rl, rt),
                    size = Size(rw, rh),
                    style = Stroke(width = 2.dp.toPx()),
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

private enum class CropHandle {
  TOP_LEFT,
  TOP_RIGHT,
  BOTTOM_LEFT,
  BOTTOM_RIGHT,
  BODY,
}

@Composable
fun InteractiveCropPreview(
  imagePath: String,
  imageWidth: Int,
  imageHeight: Int,
  cropRect: NormalizedCropRect,
  onCropChanged: (NormalizedCropRect) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val primaryColor = MaterialTheme.colorScheme.primary
  val scrimColor = LocalClearScanColors.current.viewfinderOverlay
  val aspectRatio = remember(imageWidth, imageHeight) {
    if (imageWidth > 0 && imageHeight > 0) imageWidth.toFloat() / imageHeight.toFloat() else 0.75f
  }

  BoxWithConstraints(
    modifier = modifier,
    contentAlignment = Alignment.Center,
  ) {
    val maxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
    val maxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
    val boxRatio = maxW / maxH
    val (fitW, fitH) = if (aspectRatio > boxRatio) {
      maxW to (maxW / aspectRatio)
    } else {
      (maxH * aspectRatio) to maxH
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fitWDp = with(density) { fitW.toDp() }
    val fitHDp = with(density) { fitH.toDp() }

    var activeHandle by remember { mutableStateOf<CropHandle?>(null) }
    var latestCrop by remember(cropRect) { mutableStateOf(cropRect) }

    Box(
      modifier = Modifier.size(fitWDp, fitHDp)
    ) {
      AsyncImage(
        model = ImageRequest.Builder(context)
          .data(File(imagePath))
          .memoryCacheKey("${imagePath}_${File(imagePath).lastModified()}")
          .build(),
        contentDescription = "Halaman yang akan dipotong",
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.fillMaxSize(),
      )

      Canvas(
        modifier = Modifier
          .fillMaxSize()
          .pointerInput(fitW, fitH) {
            detectDragGestures(
              onDragStart = { offset ->
                val nx = offset.x / size.width
                val ny = offset.y / size.height
                val corners = listOf(
                  CropHandle.TOP_LEFT to Offset(latestCrop.left, latestCrop.top),
                  CropHandle.TOP_RIGHT to Offset(latestCrop.right, latestCrop.top),
                  CropHandle.BOTTOM_LEFT to Offset(latestCrop.left, latestCrop.bottom),
                  CropHandle.BOTTOM_RIGHT to Offset(latestCrop.right, latestCrop.bottom),
                )
                val nearest = corners.minByOrNull { (_, pt) ->
                  hypot(nx - pt.x, ny - pt.y)
                }
                activeHandle = if (nearest != null && hypot(nx - nearest.second.x, ny - nearest.second.y) < 0.14f) {
                  nearest.first
                } else if (nx in latestCrop.left..latestCrop.right && ny in latestCrop.top..latestCrop.bottom) {
                  CropHandle.BODY
                } else {
                  null
                }
              },
              onDragEnd = { activeHandle = null },
              onDragCancel = { activeHandle = null },
              onDrag = { change, dragAmount ->
                change.consume()
                val dx = dragAmount.x / size.width
                val dy = dragAmount.y / size.height
                val minSpan = 0.15f
                val updated = when (activeHandle) {
                  CropHandle.TOP_LEFT -> latestCrop.copy(
                    left = (latestCrop.left + dx).coerceIn(0f, latestCrop.right - minSpan),
                    top = (latestCrop.top + dy).coerceIn(0f, latestCrop.bottom - minSpan),
                  )
                  CropHandle.TOP_RIGHT -> latestCrop.copy(
                    right = (latestCrop.right + dx).coerceIn(latestCrop.left + minSpan, 1f),
                    top = (latestCrop.top + dy).coerceIn(0f, latestCrop.bottom - minSpan),
                  )
                  CropHandle.BOTTOM_LEFT -> latestCrop.copy(
                    left = (latestCrop.left + dx).coerceIn(0f, latestCrop.right - minSpan),
                    bottom = (latestCrop.bottom + dy).coerceIn(latestCrop.top + minSpan, 1f),
                  )
                  CropHandle.BOTTOM_RIGHT -> latestCrop.copy(
                    right = (latestCrop.right + dx).coerceIn(latestCrop.left + minSpan, 1f),
                    bottom = (latestCrop.bottom + dy).coerceIn(latestCrop.top + minSpan, 1f),
                  )
                  CropHandle.BODY -> {
                    val w = latestCrop.right - latestCrop.left
                    val h = latestCrop.bottom - latestCrop.top
                    val nl = (latestCrop.left + dx).coerceIn(0f, 1f - w)
                    val nt = (latestCrop.top + dy).coerceIn(0f, 1f - h)
                    latestCrop.copy(left = nl, top = nt, right = nl + w, bottom = nt + h)
                  }
                  null -> latestCrop
                }
                latestCrop = updated
                onCropChanged(updated)
              },
            )
          }
      ) {
        val l = cropRect.left * size.width
        val t = cropRect.top * size.height
        val r = cropRect.right * size.width
        val b = cropRect.bottom * size.height

        // Dim outside crop rectangle
        drawRect(scrimColor, Offset.Zero, Size(size.width, t))
        drawRect(scrimColor, Offset(0f, b), Size(size.width, size.height - b))
        drawRect(scrimColor, Offset(0f, t), Size(l, b - t))
        drawRect(scrimColor, Offset(r, t), Size(size.width - r, b - t))

        val cropBounds = Rect(l, t, r, b)
        drawRect(
          color = Color.White,
          topLeft = cropBounds.topLeft,
          size = cropBounds.size,
          style = Stroke(width = 2.dp.toPx()),
        )

        // Rule of thirds inside crop
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
        val thirdW = cropBounds.width / 3f
        val thirdH = cropBounds.height / 3f
        for (i in 1..2) {
          drawLine(
            color = Color.White.copy(alpha = 0.5f),
            start = Offset(l + thirdW * i, t),
            end = Offset(l + thirdW * i, b),
            strokeWidth = 1.dp.toPx(),
            pathEffect = dash,
          )
          drawLine(
            color = Color.White.copy(alpha = 0.5f),
            start = Offset(l, t + thirdH * i),
            end = Offset(r, t + thirdH * i),
            strokeWidth = 1.dp.toPx(),
            pathEffect = dash,
          )
        }

        // Corner handles
        val handleRadius = 10.dp.toPx()
        for (pt in listOf(
          Offset(l, t),
          Offset(r, t),
          Offset(l, b),
          Offset(r, b),
        )) {
          drawCircle(color = primaryColor, radius = handleRadius, center = pt)
          drawCircle(
            color = Color.White,
            radius = handleRadius,
            center = pt,
            style = Stroke(width = 2.5.dp.toPx()),
          )
        }
      }
    }
  }
}
