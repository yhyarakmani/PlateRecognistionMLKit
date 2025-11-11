# Plate Scanner – Android License Plate Detection Library

**Plate Scanner** is a **plug-and-play Android library** for detecting and recognizing vehicle license plates using the device camera.  
It leverages **CameraX**, **ML Kit Text Recognition**, and **ONNX** for fast and accurate OCR.

This library is designed for **Jetpack Compose apps**, providing a simple composable `CameraScreen` component that handles:

- Real-time camera preview
- Automatic license plate detection
- OCR recognition of plate characters
- Bounding box overlay for visual feedback
- Configurable plate text matching

---

## Features

- Works with **CameraX** and Jetpack Compose
- Detects plates and extracts plate text automatically
- Returns a **bitmap of detected plate** for further processing
- Draws bounding boxes for detected plates
- Provides callbacks for **real-time plate recognition**
- Supports **plug-and-play integration via AAR or Maven**

---

## Usage Example

```kotlin
PlateScannerCameraView(
    lifecycleOwner = this,
    firstText = "123",
    secondText = "ABC",
    onResult = { result ->
    },
    onPlateDetected = { result ->
        Log.d("Camera", "✅ Plate recognized")
    }
)
