package com.cashin.platerecognistionmlkit.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.cashin.plate_scanner.PlateScannerCameraView
import com.cashin.plate_scanner.model.PipelineResult

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun LicensePlateScannerApp() {
    var hasPermission by remember { mutableStateOf(false) }

    // User inputs
    var firstText by remember { mutableStateOf("") }
    var secondText by remember { mutableStateOf("") }

    // States
    var startCamera by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }
    var successResult by remember { mutableStateOf<PipelineResult?>(null) }

    val context = LocalContext.current

    // Camera permission
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasPermission = isGranted }
    )

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else hasPermission = true
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        if (startCamera && !success) {
            if (hasPermission) {
                PlateScannerCameraView(
                    firstText = firstText,
                    secondText = secondText,
                    onPlateRecognized = { result ->
                        success = true
                        successResult = result
                    },
                )
            } else {
                Text("Camera permission required.")
            }
        } else {
            InputScreen(
                firstText = firstText,
                onFirstTextChanged = { firstText = it },
                secondText = secondText,
                onSecondTextChanged = { secondText = it },
                onStartScanning = {
                    success = false
                    successResult = null
                    startCamera = true
                },
                success = success,
                successResult = successResult,
                hasPermission = hasPermission
            )
        }
    }
}
