package com.k2fsa.sherpa.onnx;

/** Android loader: the native libraries are packaged in the APK's jniLibs. */
public class LibraryLoader {
    private static boolean isLoaded = false;

    static synchronized void maybeLoad() {
        if (!isLoaded) {
            System.loadLibrary("onnxruntime");
            System.loadLibrary("sherpa-onnx-jni");
            isLoaded = true;
        }
    }
}
