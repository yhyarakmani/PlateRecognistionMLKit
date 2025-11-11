package com.cashin.plate_scanner.ui.components

import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.LifecycleOwner
import com.cashin.plate_scanner.model.PipelineResult
import java.util.concurrent.Executors

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
internal fun CameraScreen(
    lifecycleOwner: LifecycleOwner,
    firstText: String,
    secondText: String,
    onResult: (PipelineResult?) -> Unit,
    onPlateDetected: (PipelineResult?) -> Unit
) {
    val cameraExecutor = remember {
        Executors.newSingleThreadExecutor()
    }
    var plateDetected by remember { mutableStateOf(false) }
    var pipelineResult by remember { mutableStateOf<PipelineResult?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            lifecycleOwner = lifecycleOwner,
            cameraExecutor = cameraExecutor,
            onResult = { result ->
                pipelineResult = result
                onResult(result)

                if (!plateDetected) {
                    result?.let {
                        val pattern =
                            ".*${Regex.escape(firstText)}.*${Regex.escape(secondText)}.*".toRegex(
                                RegexOption.IGNORE_CASE
                            )
                        if (pattern.matches(it.plateText)) {
                            plateDetected = true
                            onPlateDetected(result)
                        }
                    }
                }
            }
        )
        PlateOverlay(
            horizontalPadding = 100.dp,
            cornerRadius = 20.dp
        )
        // Draw bounding box overlay
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            pipelineResult?.let { result ->
                val analysisWidth = result.analysisSize.width.toFloat()
                val analysisHeight = result.analysisSize.height.toFloat()

                val screenWidth = constraints.maxWidth.toFloat()
                val screenHeight = constraints.maxHeight.toFloat()

                // Maintain aspect ratio
                val scale = minOf(screenWidth / analysisWidth, screenHeight / analysisHeight)

                // Compute padding offsets if the image doesn't fill the screen perfectly
                val scaledWidth = analysisWidth * scale
                val scaledHeight = analysisHeight * scale
                val horizontalOffset = (screenWidth - scaledWidth) / 2f
                val verticalOffset = (screenHeight - scaledHeight) / 2f

                val box = result.plateBox

                // Scale + offset box coordinates
                val left = box.left * scale + horizontalOffset
                val top = box.top * scale + verticalOffset
                val right = box.right * scale + horizontalOffset
                val bottom = box.bottom * scale + verticalOffset

                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRoundRect(
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                        color = Color.Green,
                        topLeft = Offset(left, top),
                        size = Size((right - left) * 2, (bottom - top) * 2),
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }
        }
    }
}

