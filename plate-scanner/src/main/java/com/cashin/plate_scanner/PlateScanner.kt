package com.cashin.plate_scanner

import androidx.compose.runtime.Composable
import com.cashin.plate_scanner.model.PipelineResult
import com.cashin.plate_scanner.ui.components.CameraScreen

@Composable
fun PlateScannerCameraView(
    firstText: String,
    secondText: String,
    onPlateRecognized: (pipelineResult: PipelineResult) -> Unit
) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    CameraScreen(
        lifecycleOwner = lifecycleOwner,
        firstText = firstText,
        secondText = secondText,
        onResult = { result -> },
        onPlateDetected = { result ->
            result?.let {
                onPlateRecognized(it)
            }
        }
    )
}