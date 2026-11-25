package com.example.ace;

/** Native bridge. All methods block; callers must run them off the UI thread. */
public final class AceEngine {

    static {
        System.loadLibrary("ace-jni");
    }

    private AceEngine() {
    }

    /** @return native handle, or 0 on failure */
    public static native long nativeInit(String modelsDir, boolean keepLoaded);

    /** @return enriched request JSON (lyrics + metadata + audio_codes), or null on error */
    public static native String nativeLm(long handle, String requestJson);

    /** @return output audio file path, or null on error */
    public static native String nativeSynth(long handle, String requestJson, String outputPath);

    public static native void nativeCancel(long handle);

    public static native void nativeRelease(long handle);

    /** A/B switch for CPU flash attention; affects the whole native process. */
    public static native void nativeSetFlashAttn(boolean enabled);

    /** Pick the VAE decoder GGUF filename for an engine instance. */
    public static native void nativeSetVae(long handle, String filename);

    /** Pick the DiT GGUF file (fixed to official turbo in this build). */
    public static native void nativeSetDit(long handle, String filename);

    // Convenience aliases used by GenerationService.
    public static long init(String dir, boolean keepLoaded) {
        return nativeInit(dir, keepLoaded);
    }

    public static String lm(long h, String json) {
        return nativeLm(h, json);
    }

    public static String synth(long h, String json, String out) {
        return nativeSynth(h, json, out);
    }

    public static void cancel(long h) {
        nativeCancel(h);
    }

    public static void release(long h) {
        nativeRelease(h);
    }

    public static void setFlashAttn(boolean enabled) {
        nativeSetFlashAttn(enabled);
    }

    public static void setVae(long h, String filename) {
        nativeSetVae(h, filename);
    }

    public static void setDit(long h, String filename) {
        nativeSetDit(h, filename);
    }
}
