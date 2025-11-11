package com.cashin.plate_scanner.utils

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.cashin.plate_scanner.model.PipelineResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.FloatBuffer


internal class PlateAnalyzer(
    context: Context,
    private val onResult: (PipelineResult?) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val MODEL_WIDTH = 640
        private const val MODEL_HEIGHT = 640
        private const val MODEL_FILE = "license-plate-finetune-v1s.onnx"
    }

    // --- Step 1: ONNX Runtime (YOLO) Setup ---
    private val ortEnvironment = OrtEnvironment.getEnvironment()
    private val ortSession: OrtSession
    private val inputName: String

    // --- Step 2: Google OCR (ML Kit) Setup ---
    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)


    // Throttle analysis to avoid overloading
    private var lastAnalyzedTimestamp = 0L

    init {
            // Load the ONNX model from assets
            val modelBytes = context.assets.open(MODEL_FILE).readBytes()
            ortSession = ortEnvironment.createSession(modelBytes)
            inputName = ortSession.inputNames.first()
            Log.d("PlateAnalyzer", "ONNX Model Loaded Successfully. Input: $inputName")
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnalyzedTimestamp < 500) { // Analyze ~2 frames/sec
            imageProxy.close()
            return
        }
        lastAnalyzedTimestamp = currentTime

        // 1. Convert YUV ImageProxy to RGB Bitmap
        val bitmap: Bitmap? = imageProxy.toBitmapY()
        if (bitmap == null) {
            onResult(null) // No plate found
            imageProxy.close()
            return
        }
        // 2. Pre-process the Bitmap for YOLO
        val (inputBuffer, scaleFactor, padX) = preprocess(bitmap)

        // 3. Run YOLO detection
        val outputBuffer = runYoloDetection(inputBuffer)

        // 4. Post-process the YOLO output
        val bestBox = postprocess(
            outputBuffer,
            scaleFactor,
            padX,
            bitmap.width,
            bitmap.height
        )

        if (bestBox == null) {
            onResult(null) // No plate found
            imageProxy.close()
            return
        }

        // 5. We found a plate! Now, crop and run OCR.
        val croppedBitmap = try {
            Bitmap.createBitmap(
                bitmap,
                bestBox.left,
                bestBox.top,
                bestBox.width(),
                bestBox.height()
            )
        } catch (e: Exception) {
            Log.e("PlateAnalyzer", "Crop failed: ${e.message}")
            imageProxy.close()
            return
        }

        val ocrInput = InputImage.fromBitmap(croppedBitmap, 0)

        ocr.process(ocrInput)
            .addOnSuccessListener { visionText ->
                val cleanedText = visionText.text
                    .uppercase()
                    .replace(Regex("[^A-Z0-9]"), "") // Keep only letters and numbers

                // We have a final result!
                val result = PipelineResult(
                    bestBox,
                    cleanedText,
                    Size(bitmap.width, bitmap.height),
                    bitmap = bitmap
                )
                onResult(result)
            }
            .addOnFailureListener {
                Log.e("PlateAnalyzer", "OCR Failed", it)
                onResult(null)
            }
            .addOnCompleteListener {
                imageProxy.close() // ALWAYS close the proxy
            }
    }

    private fun runYoloDetection(inputBuffer: FloatBuffer): FloatBuffer {
        // Create the tensor
        val shape = longArrayOf(1, 3, MODEL_HEIGHT.toLong(), MODEL_WIDTH.toLong())
        val tensor = OnnxTensor.createTensor(ortEnvironment, inputBuffer, shape)

        // Run inference
        val results = ortSession.run(mapOf(inputName to tensor))
        val output = results.first().value as OnnxTensor

        return output.floatBuffer
    }
}