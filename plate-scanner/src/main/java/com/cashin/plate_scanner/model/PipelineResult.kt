package com.cashin.plate_scanner.model

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Size


// Data class to hold the final results
data class PipelineResult(
    val plateBox: Rect,        // The box from YOLO
    val plateText: String,     // The text from ML Kit OCR
    val analysisSize: Size,     // The size of the image we analyzed (e.g., 720x1280)
    val bitmap: Bitmap
)