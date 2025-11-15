package com.cashin.plate_scanner.utils

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
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


/**
 * Converts the YOLO ONNX model analysis pipeline to use a TFLite Interpreter.
 * Assumes the TFLite model:
 * 1. Is named 'model.tflite' and placed in the 'assets' folder.
 * 2. Has a float32 input in NCHW (1, 3, 640, 640) format.
 * 3. Has a float32 output in the expected YOLOv8/v11 format (e.g., [1, 5, 8400]).
 */
internal class PlateAnalyzer(
    context: Context,
    private val onResult: (PipelineResult?) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val MODEL_WIDTH = 640
        private const val MODEL_HEIGHT = 640
        private const val MODEL_FILE = "license-plate-finetune-v1n.tflite" // TFLite model file name
        private const val ANALYSIS_INTERVAL = 500L // 2 frames/sec

        // Constants for TFLite input buffer size (float32, 3 channels)
        private const val NUM_CHANNELS = 3
        private const val FLOAT_SIZE = 4 // 4 bytes per float
    }

    // State management
    private enum class AnalyzerState { INITIALIZING, READY, CLOSING, CLOSED }
    private val state = AtomicReference(AnalyzerState.INITIALIZING)

    // Thread synchronization
    private val tfliteLock = ReentrantLock()
    private val processingSemaphore = Semaphore(1) // Only one frame at a time

    // TFLite Resources (protected by tfliteLock)
    private var tfliteInterpreter: Interpreter? = null

    // Pre-allocated for efficient inference (Input and Output)
    private lateinit var inputBuffer: ByteBuffer
    private lateinit var outputBuffer: FloatBuffer // Output is a FloatBuffer

    // OCR
    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Analysis control
    private var lastAnalyzedTimestamp = 0L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Helper to map the model from assets to MappedByteBuffer
    private fun loadModelFile(context: Context, modelFileName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelFileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    init {
        // Load model asynchronously
        scope.launch(Dispatchers.IO) {
            try {
                // 1. Load model file
                val modelFile = loadModelFile(context, MODEL_FILE)

                // 2. Initialize TFLite Interpreter
                // NOTE: Add options here if you need GPU/NNAPI delegation
                val interpreter = Interpreter(modelFile, Interpreter.Options())

                // 3. Pre-allocate buffers based on model requirements
                // TFLite requires a direct ByteBuffer for runForMultipleInputsOutputs
                inputBuffer = ByteBuffer.allocateDirect(
                    1 * MODEL_WIDTH * MODEL_HEIGHT * NUM_CHANNELS * FLOAT_SIZE
                ).order(ByteOrder.nativeOrder())

                // Determine output size. TFLite's output shape must match the model's output.
                // Assuming [1, 5, 8400] output for YOLOv11/v8 detection.
                val outputShape = interpreter.getOutputTensor(0).shape()
                val outputSize = outputShape.fold(1) { acc, i -> acc * i }
                outputBuffer = FloatBuffer.allocate(outputSize)


                tfliteLock.lock()
                try {
                    if (state.get() == AnalyzerState.CLOSED) {
                        interpreter.close()
                        return@launch
                    }

                    tfliteInterpreter = interpreter
                    state.set(AnalyzerState.READY)
                    Log.d("PlateAnalyzer", "TFLite Model Loaded Successfully. Output shape: ${outputShape.joinToString()}")
                } finally {
                    tfliteLock.unlock()
                }
            } catch (e: Exception) {
                Log.e("PlateAnalyzer", "Failed to load TFLite model", e)
                state.set(AnalyzerState.CLOSED)
            }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        // State and interval checks
        if (state.get() != AnalyzerState.READY) {
            imageProxy.close() // Close if not ready
            return
        }
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnalyzedTimestamp < ANALYSIS_INTERVAL) {
            imageProxy.close() // Close if interval too short
            return
        }

        // Try to acquire semaphore to limit concurrent processing
        if (!processingSemaphore.tryAcquire()) {
            imageProxy.close() // Close if another frame is still processing
            return
        }
        lastAnalyzedTimestamp = currentTime

        scope.launch(Dispatchers.IO) {
            // NOTE: We don't need 'shouldCloseProxy' flag anymore because we close it early.

            // 1. Convert to bitmap and close proxy immediately
            val bitmap = try {
                val tempBitmap = imageProxy.toBitmapY()
                imageProxy.close() // IMMEDIATELY RELEASE THE IMAGE PROXY
                tempBitmap?.config?.let {tempConfig->
                    tempBitmap.copy(tempConfig, true)
                }
            } catch (e: Exception) {
                // Log and return if image acquisition or copy fails
                Log.e("PlateAnalyzer", "Error converting ImageProxy to Bitmap.", e)
                processingSemaphore.release()
                return@launch
            }

            if (bitmap == null) {
                processingSemaphore.release()
                return@launch
            }

            try {
                if (state.get() != AnalyzerState.READY) {
                    return@launch
                }

                // Preprocess
                val (floatBuffer, scaleFactor, padX) = preprocess(bitmap)

                // Copy FloatBuffer content (NCHW) to the direct ByteBuffer
                inputBuffer.rewind()
                floatBuffer.rewind()
                inputBuffer.asFloatBuffer().put(floatBuffer)

                // Run detection with lock protection
                tfliteLock.lock()
                try {
                    if (state.get() != AnalyzerState.READY) return@launch

                    val outputFloatBuffer = runYoloDetectionWithLock()

                    val bestBox = postprocess(
                        outputFloatBuffer,
                        scaleFactor,
                        padX,
                        bitmap.width,
                        bitmap.height
                    ) ?: return@launch

                    // Release lock before OCR (which can take time)
                    tfliteLock.unlock()

                    // Crop and run OCR (same logic)
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
                    // Lock release: Only release if lock was acquired successfully and not already released before OCR
                    if (tfliteLock.isHeldByCurrentThread) {
                        tfliteLock.unlock()
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e("PlateAnalyzer", "Analysis failed", e)
                }
            } finally {
                // Only release the semaphore here. ImageProxy is already closed.
                processingSemaphore.release()
            }
        }
    }
    /**
     * TFLite specific detection method.
     * This method should ONLY be called while holding tfliteLock.
     * Assumes the global 'inputBuffer' has been filled by the caller.
     */
    private fun runYoloDetectionWithLock(): FloatBuffer {
        val interpreter = tfliteInterpreter ?: throw IllegalStateException("TFLite Interpreter not initialized")

        // Reset output buffer for fresh results
        outputBuffer.rewind()

        // TFLite's run method takes an object array for input and a map for output
        val outputMap = mapOf(0 to outputBuffer)

        // Run the model: [inputs: Array<Any>, outputs: Map<Int, Any>]
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)

        return outputBuffer
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
        if (state.get() == AnalyzerState.CLOSED) return

        if (!state.compareAndSet(AnalyzerState.READY, AnalyzerState.CLOSING) &&
            !state.compareAndSet(AnalyzerState.INITIALIZING, AnalyzerState.CLOSING)) {
            return
        }

        scope.coroutineContext.cancelChildren()

        // Wait for any in-flight analysis to finish
        try {
            if (processingSemaphore.tryAcquire(2, TimeUnit.SECONDS)) {
                processingSemaphore.release()
            }
        } catch (e: Exception) {
            Log.w("PlateAnalyzer", "Timeout waiting for analysis to complete", e)
        }

        // Close TFLite resources
        tfliteLock.lock()
        try {
            runCatching {
                tfliteInterpreter?.close()
            }.onFailure { e ->
                Log.e("PlateAnalyzer", "Error closing TFLite resources", e)
            }

            tfliteInterpreter = null
            state.set(AnalyzerState.CLOSED)
            Log.d("PlateAnalyzer", "Analyzer closed successfully")
        } finally {
            tfliteLock.unlock()
        }
    }
}