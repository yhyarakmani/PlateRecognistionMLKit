# 1. Protect all classes within your library's package
-keep public class com.cashin.plate_scanner.** { *; }
-keepclasseswithmembers public class com.cashin.plate_scanner.** { *; }

# 2. Prevent R8/ProGuard from displaying warnings about classes it can't find/process.
-dontwarn com.cashin.plate_scanner.**

# 3. Protect ONNX Runtime components (ai.onnxruntime.*)
# This keeps constructors and methods used by native JNI calls and reflection.
-keep class ai.onnxruntime.** { *; }
-keep interface ai.onnxruntime.** { *; }
-keep enum ai.onnxruntime.** { *; }
