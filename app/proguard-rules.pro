# Keep ExecuTorch JNI-facing classes (loaded reflectively / via native).
-keep class org.pytorch.executorch.** { *; }
-keep class com.facebook.jni.** { *; }
-dontwarn org.pytorch.executorch.**
