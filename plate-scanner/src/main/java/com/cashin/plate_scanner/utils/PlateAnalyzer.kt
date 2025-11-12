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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


internal class PlateAnalyzer(
    context: Context,
    private val onResult: (PipelineResult?) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val MODEL_WIDTH = 640
        private const val MODEL_HEIGHT = 640
        private const val MODEL_FILE = "license-plate-finetune-v1s.onnx"
        private const val ANALYSIS_INTERVAL = 500L // 2 frames/sec
    }

    // State management
    private enum class AnalyzerState { INITIALIZING, READY, CLOSING, CLOSED }
    private val state = AtomicReference(AnalyzerState.INITIALIZING)

    // Thread synchronization
    private val onnxLock = ReentrantLock()
    private val processingSemaphore = Semaphore(1) // Only one frame at a time

    // ONNX Resources (protected by onnxLock)
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var inputName: String? = null

    // OCR
    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Analysis control
    private var lastAnalyzedTimestamp = 0L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Load model asynchronously
        scope.launch(Dispatchers.IO) {
            try {
                val env = OrtEnvironment.getEnvironment()
                val modelBytes = context.assets.open(MODEL_FILE).readBytes()
                val session = env.createSession(modelBytes)
                val input = session.inputNames.first()

                onnxLock.lock()
                try {
                    if (state.get() == AnalyzerState.CLOSED) {
                        // Clean up if closed during initialization
                        session.close()
                        env.close()
                        return@launch
                    }

                    ortEnvironment = env
                    ortSession = session
                    inputName = input
                    state.set(AnalyzerState.READY)
                    Log.d("PlateAnalyzer", "ONNX Model Loaded Successfully. Input: $input")
                } finally {
                    onnxLock.unlock()
                }
            } catch (e: Exception) {
                Log.e("PlateAnalyzer", "Failed to load ONNX model", e)
                state.set(AnalyzerState.CLOSED)
            }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        // Immediately reject frames if not ready or closing/closed
        if (state.get() != AnalyzerState.READY) {
            imageProxy.close()
            return
        }

        // Check analysis interval
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnalyzedTimestamp < ANALYSIS_INTERVAL) {
            imageProxy.close()
            return
        }

        // Try to acquire semaphore to limit concurrent processing
        if (!processingSemaphore.tryAcquire()) {
            imageProxy.close()
            return
        }

        lastAnalyzedTimestamp = currentTime

        scope.launch(Dispatchers.IO) {
            var shouldCloseProxy = true
            try {
                // Double-check state after acquiring semaphore
                if (state.get() != AnalyzerState.READY) {
                    return@launch
                }

                // Convert to bitmap
                val bitmap = imageProxy.toBitmapY() ?: return@launch

                // Preprocess
                val (inputBuffer, scaleFactor, padX) = preprocess(bitmap)

                // Run detection with lock protection
                onnxLock.lock()
                try {
                    if (state.get() != AnalyzerState.READY) {
                        return@launch
                    }

                    val outputBuffer = runYoloDetectionWithLock(inputBuffer)
                    val bestBox = postprocess(
                        outputBuffer,
                        scaleFactor,
                        padX,
                        bitmap.width,
                        bitmap.height
                    ) ?: return@launch

                    // Release lock before OCR (which can take time)
                    onnxLock.unlock()
                    shouldCloseProxy = false

                    // Crop and run OCR
                    val croppedBitmap = Bitmap.createBitmap(
                        bitmap,
                        bestBox.left,
                        bestBox.top,
                        bestBox.width(),
                        bestBox.height()
                    )

                    val ocrInput = InputImage.fromBitmap(croppedBitmap, 0)
                    val visionText = ocr.process(ocrInput).await()
                    val cleanedText = visionText.text
                        .uppercase()
                        .replace(Regex("[^A-Z0-9]"), "")

                    // Deliver result on main thread
                    withContext(Dispatchers.Main) {
                        if (state.get() == AnalyzerState.READY) {
                            onResult(PipelineResult(
                                bestBox,
                                cleanedText,
                                Size(bitmap.width, bitmap.height),
                                bitmap = bitmap
                            ))
                        }
                    }
                } finally {
                    if (shouldCloseProxy) {
                        onnxLock.unlock()
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e("PlateAnalyzer", "Analysis failed", e)
                }
            } finally {
                imageProxy.close()
                processingSemaphore.release()
            }
        }
    }

    private fun runYoloDetectionWithLock(inputBuffer: FloatBuffer): FloatBuffer {
        // This method should ONLY be called while holding onnxLock
        val environment = ortEnvironment ?: throw IllegalStateException("ONNX Runtime not initialized")
        val session = ortSession ?: throw IllegalStateException("ONNX Session not initialized")
        val input = inputName ?: throw IllegalStateException("Input name not available")

        val shape = longArrayOf(1, 3, MODEL_HEIGHT.toLong(), MODEL_WIDTH.toLong())
        val tensor = OnnxTensor.createTensor(environment, inputBuffer, shape)

        try {
            val results = session.run(mapOf(input to tensor))
            return (results.first().value as OnnxTensor).floatBuffer
        } finally {
            tensor.close()
        }
    }

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T {
        return suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }.addOnFailureListener { exception ->
                if (continuation.isActive) continuation.resumeWithException(exception)
            }
            continuation.invokeOnCancellation {
                // No need to cancel the task explicitly as ML Kit doesn't support cancellation
            }
        }
    }

    fun close() {
        // Fast path if already closed
        if (state.get() == AnalyzerState.CLOSED) return

        // Transition to CLOSING state
        if (!state.compareAndSet(AnalyzerState.READY, AnalyzerState.CLOSING) &&
            !state.compareAndSet(AnalyzerState.INITIALIZING, AnalyzerState.CLOSING)) {
            return
        }

        // Cancel all ongoing work
        scope.coroutineContext.cancelChildren()

        // Wait for any in-flight analysis to finish
        try {
            // Try to acquire semaphore - this will wait until current processing completes
            if (processingSemaphore.tryAcquire(2, TimeUnit.SECONDS)) {
                processingSemaphore.release()
            }
        } catch (e: Exception) {
            Log.w("PlateAnalyzer", "Timeout waiting for analysis to complete", e)
        }

        // Close ONNX resources with lock protection
        onnxLock.lock()
        try {
            runCatching {
                ortSession?.close()
                ortEnvironment?.close()
            }.onFailure { e ->
                Log.e("PlateAnalyzer", "Error closing ONNX resources", e)
            }

            ortSession = null
            ortEnvironment = null
            state.set(AnalyzerState.CLOSED)
            Log.d("PlateAnalyzer", "Analyzer closed successfully")
        } finally {
            onnxLock.unlock()
        }
    }
}