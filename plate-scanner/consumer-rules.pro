# Consumer R8/ProGuard Rules for com.cashin.plate_scanner library

# 1. Protect all classes within your library's package
# This ensures external users can access and initialize your PlateAnalyzer and utility classes.
-keep public class com.cashin.plate_scanner.** { *; }
-keepclasseswithmembers public class com.cashin.plate_scanner.** { *; }

# 2. Prevent R8/ProGuard from displaying warnings about classes it can't find/process.
-dontwarn com.cashin.plate_scanner.**

# --- TFLite Conversion Update ---

# 3. Protect TensorFlow Lite components (org.tensorflow.lite.*)
# TFLite relies on reflection and JNI for model loading and inference.
# This rule preserves all necessary classes and methods in the TFLite package.
-keep class org.tensorflow.lite.** { *; }
-keep interface org.tensorflow.lite.** { *; }
-keep enum org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }