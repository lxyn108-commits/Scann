package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.domain.model.NormalizedQuad
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Renders the live white document corner brackets over the camera PreviewView using
 * critically-damped snap-on-lock animations and convexity guards to prevent crossed lines.
 */
@Composable
fun DocumentQuadOverlay(
  activeQuad: NormalizedQuad?,
  analysisFrameSize: Pair<Int, Int>,
  showGrid: Boolean,
  stabilityProgress: Float,
  autoCaptureEnabled: Boolean,
  modifier: Modifier = Modifier,
) {
  val tlX = remember { Animatable(0.14f) }
  val tlY = remember { Animatable(0.22f) }
  val trX = remember { Animatable(0.86f) }
  val trY = remember { Animatable(0.22f) }
  val brX = remember { Animatable(0.86f) }
  val brY = remember { Animatable(0.70f) }
  val blX = remember { Animatable(0.14f) }
  val blY = remember { Animatable(0.70f) }
  var hadQuad by remember { mutableStateOf(false) }

  LaunchedEffect(activeQuad) {
    if (activeQuad == null) {
      hadQuad = false
      return@LaunchedEffect
    }
    val maxDelta = maxOf(
      hypot(tlX.value - activeQuad.topLeft.x, tlY.value - activeQuad.topLeft.y),
      hypot(trX.value - activeQuad.topRight.x, trY.value - activeQuad.topRight.y),
      hypot(brX.value - activeQuad.bottomRight.x, brY.value - activeQuad.bottomRight.y),
      hypot(blX.value - activeQuad.bottomLeft.x, blY.value - activeQuad.bottomLeft.y),
    )

    if (!hadQuad || maxDelta > 0.18f) {
      tlX.snapTo(activeQuad.topLeft.x)
      tlY.snapTo(activeQuad.topLeft.y)
      trX.snapTo(activeQuad.topRight.x)
      trY.snapTo(activeQuad.topRight.y)
      brX.snapTo(activeQuad.bottomRight.x)
      brY.snapTo(activeQuad.bottomRight.y)
      blX.snapTo(activeQuad.bottomLeft.x)
      blY.snapTo(activeQuad.bottomLeft.y)
      hadQuad = true
    } else {
      val spec = spring<Float>(stiffness = Spring.StiffnessMedium, dampingRatio = 1.0f)
      launch { tlX.animateTo(activeQuad.topLeft.x, spec) }
      launch { tlY.animateTo(activeQuad.topLeft.y, spec) }
      launch { trX.animateTo(activeQuad.topRight.x, spec) }
      launch { trY.animateTo(activeQuad.topRight.y, spec) }
      launch { brX.animateTo(activeQuad.bottomRight.x, spec) }
      launch { brY.animateTo(activeQuad.bottomRight.y, spec) }
      launch { blX.animateTo(activeQuad.bottomLeft.x, spec) }
      launch { blY.animateTo(activeQuad.bottomLeft.y, spec) }
    }
  }

  Canvas(modifier = modifier.fillMaxSize()) {
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

      var pTL = toView(tlX.value, tlY.value)
      var pTR = toView(trX.value, trY.value)
      var pBR = toView(brX.value, brY.value)
      var pBL = toView(blX.value, blY.value)

      if (!isViewQuadConvex(pTL, pTR, pBR, pBL)) {
        pTL = toView(activeQuad.topLeft.x, activeQuad.topLeft.y)
        pTR = toView(activeQuad.topRight.x, activeQuad.topRight.y)
        pBR = toView(activeQuad.bottomRight.x, activeQuad.bottomRight.y)
        pBL = toView(activeQuad.bottomLeft.x, activeQuad.bottomLeft.y)
      }

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
}

private fun isViewQuadConvex(
  tl: Offset,
  tr: Offset,
  br: Offset,
  bl: Offset,
): Boolean {
  val pts = listOf(tl, tr, br, bl)
  var sign = 0f
  for (i in 0..3) {
    val p0 = pts[i]
    val p1 = pts[(i + 1) % 4]
    val p2 = pts[(i + 2) % 4]
    val cross = (p1.x - p0.x) * (p2.y - p1.y) - (p1.y - p0.y) * (p2.x - p1.x)
    if (abs(cross) < 1f) return false
    if (sign == 0f) sign = cross else if (sign * cross <= 0f) return false
  }
  return true
}
