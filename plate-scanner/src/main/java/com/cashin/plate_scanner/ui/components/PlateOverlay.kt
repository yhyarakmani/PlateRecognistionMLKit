package com.cashin.plate_scanner.ui.components

import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
internal fun PlateOverlay(
    horizontalPadding: Dp = 40.dp,
    cornerRadius: Dp = 24.dp,
    borderColor: Color = Color.White.copy(alpha = 0.8f),
    overlayColor: Color = Color.Black.copy(alpha = 0.5f)
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidthPx = constraints.maxWidth.toFloat()
        val screenHeightPx = constraints.maxHeight.toFloat()

        val horizontalPaddingPx = horizontalPadding.value

        val rectWidth = screenWidthPx - horizontalPaddingPx * 2
        val rectHeight = rectWidth / 3f // adjust as needed for plate shape

        val left = horizontalPadding.value
        val top = (screenHeightPx - rectHeight) / 2f
        val right = left + rectWidth
        val bottom = top + rectHeight

        Canvas(modifier = Modifier.fillMaxSize()) {
            val path = Path().apply {
                // Outer rectangle (whole screen)
                addRect(Rect(0f, 0f, size.width, size.height))
                // Inner transparent rounded rectangle
                addRoundRect(
                    RoundRect(
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        cornerRadius = CornerRadius(cornerRadius.toPx())
                    )
                )
                fillType = PathFillType.EvenOdd // subtract inner shape
            }

            // Draw semi-transparent overlay
            drawPath(path, color = overlayColor)

            // Draw border for the guide rect
            drawRoundRect(
                color = borderColor,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                cornerRadius = CornerRadius(cornerRadius.toPx()),
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }
}
