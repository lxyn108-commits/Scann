package com.example.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.domain.model.NormalizedPoint
import com.example.domain.model.NormalizedQuad
import com.example.processing.DocumentImageProcessor
import com.example.ui.theme.LocalClearScanColors
import java.io.File
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class QuadHandle {
  TOP_LEFT,
  TOP_RIGHT,
  BOTTOM_RIGHT,
  BOTTOM_LEFT,
}

@Composable
fun InteractivePerspectiveOverlay(
  rawImagePath: String,
  quad: NormalizedQuad,
  onQuadChanged: (NormalizedQuad) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val primaryColor = MaterialTheme.colorScheme.primary
  val scrimColor = LocalClearScanColors.current.viewfinderOverlay

  val rawDims = remember(rawImagePath) {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(rawImagePath, opts)
    val w = opts.outWidth.takeIf { it > 0 } ?: 1000
    val h = opts.outHeight.takeIf { it > 0 } ?: 1400
    w to h
  }
  val loupeBitmap: ImageBitmap? = remember(rawImagePath) {
    runCatching {
      DocumentImageProcessor.decodeSampledBitmap(rawImagePath, 1100).asImageBitmap()
    }.getOrNull()
  }
  val aspectRatio = remember(rawDims) {
    rawDims.first.toFloat() / rawDims.second.toFloat()
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
    val density = LocalDensity.current
    val fitWDp = with(density) { fitW.toDp() }
    val fitHDp = with(density) { fitH.toDp() }

    var activeHandle by remember { mutableStateOf<QuadHandle?>(null) }
    var latestQuad by remember(quad) { mutableStateOf(quad) }

    Box(modifier = Modifier.size(fitWDp, fitHDp)) {
      AsyncImage(
        model = ImageRequest.Builder(context)
          .data(File(rawImagePath))
          .memoryCacheKey("raw_${rawImagePath}_${File(rawImagePath).lastModified()}")
          .build(),
        contentDescription = "Raw document image for perspective straightening",
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
                val handles = listOf(
                  QuadHandle.TOP_LEFT to latestQuad.topLeft,
                  QuadHandle.TOP_RIGHT to latestQuad.topRight,
                  QuadHandle.BOTTOM_RIGHT to latestQuad.bottomRight,
                  QuadHandle.BOTTOM_LEFT to latestQuad.bottomLeft,
                )
                val nearest = handles.minByOrNull { (_, pt) ->
                  hypot(nx - pt.x, ny - pt.y)
                }
                activeHandle = if (nearest != null && hypot(nx - nearest.second.x, ny - nearest.second.y) < 0.16f) {
                  nearest.first
                } else {
                  null
                }
              },
              onDragEnd = { activeHandle = null },
              onDragCancel = { activeHandle = null },
              onDrag = { change, dragAmount ->
                val handle = activeHandle ?: return@detectDragGestures
                change.consume()
                val dx = dragAmount.x / size.width
                val dy = dragAmount.y / size.height

                fun move(pt: NormalizedPoint) = NormalizedPoint(
                  x = (pt.x + dx).coerceIn(0f, 1f),
                  y = (pt.y + dy).coerceIn(0f, 1f),
                )

                val candidate = when (handle) {
                  QuadHandle.TOP_LEFT -> latestQuad.copy(topLeft = move(latestQuad.topLeft))
                  QuadHandle.TOP_RIGHT -> latestQuad.copy(topRight = move(latestQuad.topRight))
                  QuadHandle.BOTTOM_RIGHT -> latestQuad.copy(bottomRight = move(latestQuad.bottomRight))
                  QuadHandle.BOTTOM_LEFT -> latestQuad.copy(bottomLeft = move(latestQuad.bottomLeft))
                }
                if (candidate.isConvexValid(minArea = 0.04f)) {
                  latestQuad = candidate
                  onQuadChanged(candidate)
                }
              },
            )
          }
      ) {
        val w = size.width
        val h = size.height
        val tl = Offset(latestQuad.topLeft.x * w, latestQuad.topLeft.y * h)
        val tr = Offset(latestQuad.topRight.x * w, latestQuad.topRight.y * h)
        val br = Offset(latestQuad.bottomRight.x * w, latestQuad.bottomRight.y * h)
        val bl = Offset(latestQuad.bottomLeft.x * w, latestQuad.bottomLeft.y * h)

        // Dim area outside selected quadrilateral
        val scrimPath = Path().apply {
          fillType = PathFillType.EvenOdd
          addRect(androidx.compose.ui.geometry.Rect(0f, 0f, w, h))
          moveTo(tl.x, tl.y)
          lineTo(tr.x, tr.y)
          lineTo(br.x, br.y)
          lineTo(bl.x, bl.y)
          close()
        }
        drawPath(path = scrimPath, color = scrimColor)

        val borderPath = Path().apply {
          moveTo(tl.x, tl.y)
          lineTo(tr.x, tr.y)
          lineTo(br.x, br.y)
          lineTo(bl.x, bl.y)
          close()
        }
        drawPath(
          path = borderPath,
          color = Color.White,
          style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
        )

        // Perspective grid lines (thirds)
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
        for (i in 1..2) {
          val t = i / 3f
          val topPt = Offset(tl.x + (tr.x - tl.x) * t, tl.y + (tr.y - tl.y) * t)
          val botPt = Offset(bl.x + (br.x - bl.x) * t, bl.y + (br.y - bl.y) * t)
          drawLine(
            color = Color.White.copy(alpha = 0.45f),
            start = topPt,
            end = botPt,
            strokeWidth = 1.dp.toPx(),
            pathEffect = dash,
          )
          val leftPt = Offset(tl.x + (bl.x - tl.x) * t, tl.y + (bl.y - tl.y) * t)
          val rightPt = Offset(tr.x + (br.x - tr.x) * t, tr.y + (br.y - tr.y) * t)
          drawLine(
            color = Color.White.copy(alpha = 0.45f),
            start = leftPt,
            end = rightPt,
            strokeWidth = 1.dp.toPx(),
            pathEffect = dash,
          )
        }

        // Edge midpoints (visual alignment markers)
        val edgeMids = listOf(
          Offset((tl.x + tr.x) * 0.5f, (tl.y + tr.y) * 0.5f),
          Offset((tr.x + br.x) * 0.5f, (tr.y + br.y) * 0.5f),
          Offset((br.x + bl.x) * 0.5f, (br.y + bl.y) * 0.5f),
          Offset((bl.x + tl.x) * 0.5f, (bl.y + tl.y) * 0.5f),
        )
        for (mid in edgeMids) {
          drawCircle(color = Color.White, radius = 4.dp.toPx(), center = mid)
        }

        // Draggable Corner Handles
        val corners = listOf(
          QuadHandle.TOP_LEFT to tl,
          QuadHandle.TOP_RIGHT to tr,
          QuadHandle.BOTTOM_RIGHT to br,
          QuadHandle.BOTTOM_LEFT to bl,
        )
        for ((handle, pt) in corners) {
          val isSelected = activeHandle == handle
          val radius = if (isSelected) 14.dp.toPx() else 11.dp.toPx()
          drawCircle(color = primaryColor, radius = radius, center = pt)
          drawCircle(
            color = Color.White,
            radius = radius,
            center = pt,
            style = Stroke(width = 2.8.dp.toPx()),
          )
        }

        // Live 2.6x Corner Precision Magnifier Loupe when dragging
        val dragging = activeHandle
        if (dragging != null && loupeBitmap != null) {
          val targetNorm = when (dragging) {
            QuadHandle.TOP_LEFT -> latestQuad.topLeft
            QuadHandle.TOP_RIGHT -> latestQuad.topRight
            QuadHandle.BOTTOM_RIGHT -> latestQuad.bottomRight
            QuadHandle.BOTTOM_LEFT -> latestQuad.bottomLeft
          }
          val loupeRadius = 46.dp.toPx()
          val loupeMargin = 14.dp.toPx() + loupeRadius
          val loupeX = if (targetNorm.x < 0.5f) (w - loupeMargin) else loupeMargin
          val loupeCenter = Offset(loupeX.coerceIn(loupeRadius, w - loupeRadius), loupeMargin.coerceAtMost(h * 0.5f))

          val sampleHalfW = (loupeBitmap.width * 0.11f).roundToInt().coerceAtLeast(16)
          val sampleHalfH = (loupeBitmap.height * 0.11f).roundToInt().coerceAtLeast(16)
          val cxPx = (targetNorm.x * loupeBitmap.width).roundToInt().coerceIn(0, loupeBitmap.width - 1)
          val cyPx = (targetNorm.y * loupeBitmap.height).roundToInt().coerceIn(0, loupeBitmap.height - 1)
          val srcLeft = (cxPx - sampleHalfW).coerceIn(0, (loupeBitmap.width - 2 * sampleHalfW).coerceAtLeast(0))
          val srcTop = (cyPx - sampleHalfH).coerceIn(0, (loupeBitmap.height - 2 * sampleHalfH).coerceAtLeast(0))
          val srcW = (sampleHalfW * 2).coerceAtMost(loupeBitmap.width - srcLeft)
          val srcH = (sampleHalfH * 2).coerceAtMost(loupeBitmap.height - srcTop)

          val clipCircle = Path().apply {
            addOval(
              androidx.compose.ui.geometry.Rect(
                loupeCenter.x - loupeRadius,
                loupeCenter.y - loupeRadius,
                loupeCenter.x + loupeRadius,
                loupeCenter.y + loupeRadius,
              )
            )
          }
          clipPath(clipCircle) {
            drawImage(
              image = loupeBitmap,
              srcOffset = IntOffset(srcLeft, srcTop),
              srcSize = IntSize(srcW, srcH),
              dstOffset = IntOffset((loupeCenter.x - loupeRadius).roundToInt(), (loupeCenter.y - loupeRadius).roundToInt()),
              dstSize = IntSize((loupeRadius * 2).roundToInt(), (loupeRadius * 2).roundToInt()),
            )
            // Precision crosshair inside loupe
            val crossHalf = 14.dp.toPx()
            drawLine(
              color = primaryColor,
              start = Offset(loupeCenter.x - crossHalf, loupeCenter.y),
              end = Offset(loupeCenter.x + crossHalf, loupeCenter.y),
              strokeWidth = 2.dp.toPx(),
            )
            drawLine(
              color = primaryColor,
              start = Offset(loupeCenter.x, loupeCenter.y - crossHalf),
              end = Offset(loupeCenter.x, loupeCenter.y + crossHalf),
              strokeWidth = 2.dp.toPx(),
            )
          }
          drawCircle(
            color = Color.White,
            radius = loupeRadius,
            center = loupeCenter,
            style = Stroke(width = 3.dp.toPx()),
          )
        }
      }
    }
  }
}
