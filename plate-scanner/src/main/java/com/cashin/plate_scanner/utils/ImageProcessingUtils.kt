package com.cashin.plate_scanner.utils


import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

import android.graphics.*
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

// --- Data class for post-processing ---
private data class BoundingBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val confidence: Float
)

// --- Constants for post-processing ---
private const val CONFIDENCE_THRESHOLD = 0.5f
private const val NMS_THRESHOLD = 0.4f

/**
 * Pre-processes a Bitmap for the YOLO ONNX model.
 * 1. Resizes/Pads the image to 640x640.
 * 2. Normalizes pixel values to [0, 1].
 * 3. Writes to a FloatBuffer in planar (RRR...GGG...BBB...) format.
 * @return A Triple containing the FloatBuffer, the scale factor, and the padding.
 */
internal fun preprocess(bitmap: Bitmap): Triple<FloatBuffer, Float, Float> {
    val (scaledBitmap, scaleFactor, padX) = resizeAndPad(bitmap)

    val floatBuffer = FloatBuffer.allocate(3 * 640 * 640)
    val pixels = IntArray(640 * 640)
    scaledBitmap.getPixels(pixels, 0, 640, 0, 0, 640, 640)

    for (i in 0 until (640 * 640)) {
        val pixel = pixels[i]
        // R, G, B values in [0, 1]
        val r = ((pixel shr 16) and 0xFF) / 255.0f
        val g = ((pixel shr 8) and 0xFF) / 255.0f
        val b = (pixel and 0xFF) / 255.0f

        // Write in planar format: [RR...][GG...][BB...]
        floatBuffer.put(i, r)
        floatBuffer.put(i + (640 * 640), g)
        floatBuffer.put(i + 2 * (640 * 640), b)
    }
    floatBuffer.rewind()
    return Triple(floatBuffer, scaleFactor, padX)
}

private fun resizeAndPad(
    bitmap: Bitmap,
    modelWidth: Int = 640,
    modelHeight: Int = 640
): Triple<Bitmap, Float, Float> {
    val scaleFactor = min(
        modelWidth.toFloat() / bitmap.width,
        modelHeight.toFloat() / bitmap.height
    )
    val newWidth = (bitmap.width * scaleFactor).toInt()
    val newHeight = (bitmap.height * scaleFactor).toInt()

    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)

    // Create a new 640x640 bitmap with a black background
    val paddedBitmap = Bitmap.createBitmap(modelWidth, modelHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(paddedBitmap)

    val padX = (modelWidth - newWidth) / 2f
    val padY = (modelHeight - newHeight) / 2f

    canvas.drawBitmap(scaledBitmap, padX, padY, null)

    return Triple(paddedBitmap, scaleFactor, padX)
}

/**
 * Post-processes the YOLO output FloatBuffer.
 * 1. Decodes the [1, 5, 8400] output tensor.
 * 2. Applies confidence thresholding.
 * 3. Runs Non-Maximal Suppression (NMS).
 * 4. Scales the final box back to original image coordinates.
 * @return The best BoundingBox as a Rect, or null.
 */
internal fun postprocess(
    outputBuffer: FloatBuffer,
    scaleFactor: Float,
    padX: Float,
    origWidth: Int,
    origHeight: Int
): Rect? {
    // YOLOv11 (and v8) output is [1, 4+C, 8400], where C=1 (plate).
    // So, shape is [1, 5, 8400].
    val numProposals = 8400
    val boxes = mutableListOf<BoundingBox>()

    outputBuffer.rewind()
    for (i in 0 until numProposals) {
        // [cx, cy, w, h, conf]
        val cx = outputBuffer.get(i)
        val cy = outputBuffer.get(i + numProposals)
        val w = outputBuffer.get(i + 2 * numProposals)
        val h = outputBuffer.get(i + 3 * numProposals)
        val conf = outputBuffer.get(i + 4 * numProposals)

        if (conf < CONFIDENCE_THRESHOLD) continue

        val x1 = cx - w / 2
        val y1 = cy - h / 2
        val x2 = cx + w / 2
        val y2 = cy + h / 2

        // Scale and de-pad coordinates
        val realX1 = ((x1 - padX) / scaleFactor).coerceIn(0f, origWidth.toFloat())
        val realY1 = ((y1 - 0f) / scaleFactor).coerceIn(0f, origHeight.toFloat()) // Assuming padY is 0
        val realX2 = ((x2 - padX) / scaleFactor).coerceIn(0f, origWidth.toFloat())
        val realY2 = ((y2 - 0f) / scaleFactor).coerceIn(0f, origHeight.toFloat())

        boxes.add(BoundingBox(realX1, realY1, realX2, realY2, conf))
    }

    if (boxes.isEmpty()) return null

    // Run Non-Maximal Suppression
    val nmsBoxes = nms(boxes)

    // Return the box with the highest confidence
    val bestBox = nmsBoxes.maxByOrNull { it.confidence } ?: return null

    return Rect(
        bestBox.x1.toInt(),
        bestBox.y1.toInt(),
        bestBox.x2.toInt(),
        bestBox.y2.toInt()
    )
}

private fun nms(boxes: List<BoundingBox>): List<BoundingBox> {
    val sortedBoxes = boxes.sortedByDescending { it.confidence }.toMutableList() // <-- mutable
    val selectedBoxes = mutableListOf<BoundingBox>()

    while (sortedBoxes.isNotEmpty()) {
        val first = sortedBoxes.removeAt(0)  // remove first/highest-confidence box
        selectedBoxes.add(first)

        // remove overlapping boxes
        var i = 0
        while (i < sortedBoxes.size) {
            val next = sortedBoxes[i]
            val iou = calculateIoU(first, next)
            if (iou >= NMS_THRESHOLD) {
                sortedBoxes.removeAt(i)  // works because it's a MutableList
            } else {
                i++
            }
        }
    }

    return selectedBoxes
}

private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
    val x1 = max(box1.x1, box2.x1)
    val y1 = max(box1.y1, box2.y1)
    val x2 = min(box1.x2, box2.x2)
    val y2 = min(box1.y2, box2.y2)

    val intersectionArea = max(0f, x2 - x1) * max(0f, y2 - y1)
    val box1Area = (box1.x2 - box1.x1) * (box1.y2 - box1.y1)
    val box2Area = (box2.x2 - box2.x1) * (box2.y2 - box2.y1)
    val unionArea = box1Area + box2Area - intersectionArea

    return intersectionArea / unionArea
}


internal fun ImageProxy.toBitmapY(): Bitmap? {
    // convert to NV21 byte array
    val nv21 = yuv420ToNv21(this) ?: return null

    // convert NV21 to JPEG, then decode to Bitmap
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    val success = yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
    if (!success) return null
    val imageBytes = out.toByteArray()
    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

    // rotate to correct orientation if necessary (ImageProxy.imageInfo.rotationDegrees)
    val rotation = imageInfo.rotationDegrees
    return if (rotation != 0) {
        rotateBitmap(bitmap, rotation)
    } else {
        bitmap
    }
}

private fun rotateBitmap(src: Bitmap, degree: Int): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(degree.toFloat())
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}

/**
 * Convert YUV_420_888 (ImageProxy) to NV21 byte[].
 * Handles generic rowStride and pixelStride.
 */
private fun yuv420ToNv21(image: ImageProxy): ByteArray {
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    val width = image.width
    val height = image.height
    val ySize = width * height
    val uvSize = width * height / 4
    val nv21 = ByteArray(ySize + uvSize * 2)

    // --- Copy Y plane safely ---
    val yBuffer = yPlane.buffer
    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride
    var pos = 0
    val row = ByteArray(yRowStride)
    for (r in 0 until height) {
        val remaining = minOf(yRowStride, yBuffer.remaining())
        yBuffer.get(row, 0, remaining)
        for (c in 0 until width) {
            nv21[pos++] = row[c * yPixelStride]
        }
    }

    // --- Copy UV planes safely ---
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val uRowStride = uPlane.rowStride
    val vRowStride = vPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    val vPixelStride = vPlane.pixelStride
    val halfWidth = width / 2
    val halfHeight = height / 2
    var uvPos = ySize

    val uRow = ByteArray(uRowStride)
    val vRow = ByteArray(vRowStride)

    for (r in 0 until halfHeight) {
        val uRemaining = minOf(uRowStride, uBuffer.remaining())
        val vRemaining = minOf(vRowStride, vBuffer.remaining())
        uBuffer.get(uRow, 0, uRemaining)
        vBuffer.get(vRow, 0, vRemaining)

        for (c in 0 until halfWidth) {
            nv21[uvPos++] = vRow[c * vPixelStride]
            nv21[uvPos++] = uRow[c * uPixelStride]
        }
    }

    return nv21
}
