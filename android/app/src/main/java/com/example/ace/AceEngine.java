package com.example.ace;

/** Native bridge. All methods block; callers must run them off the UI thread. */
public final class AceEngine {

    static {
        System.loadLibrary("ace-jni");
    }

    private static long sHandle = 0L;
    private static String sModelsDir = null;

    // Pipeline stage reported from native during a generation. Values map to
    // the step labels in MainActivity. Volatile so the UI thread sees updates.
    public static volatile int sNativeStage = -1;
    public static void onNativeStage(int stage) { sNativeStage = stage; }

    private AceEngine() {
    }

    /** @return native handle, or 0 on failure */
    public static native long nativeInit(String modelsDir, boolean keepLoaded);

    /** @return enriched request JSON (lyrics + metadata + audio_codes), or null on error */
    public static native String nativeLm(long handle, String requestJson);

    /** @return output audio file path, or null on error */
    public static native String nativeSynth(long handle, String requestJson, String outputPath);

    /** Stage timings (ms) from the last synth: [load, dit, vae, write]. */
    public static native long[] nativeGetSynthTimings(long handle);

    public static native void nativeCancel(long handle);

    public static native void nativeRelease(long handle);

    /** A/B switch for CPU flash attention; affects the whole native process. */
    public static native void nativeSetFlashAttn(boolean enabled);

    /** Process-wide CPU thread override; 0 restores the automatic heuristic. */
    public static native void nativeSetThreads(int threads);

    /** Cache policy: 0 STRICT, 1 tiered with budget, 2 NEVER. */
    public static native void nativeSetCachePolicy(long handle, int policy, long budgetMb);

    /** Pick the VAE decoder GGUF filename for an engine instance. */
    public static native void nativeSetVae(long handle, String filename);

    /** Pick the DiT GGUF file (fixed to official turbo in this build). */
    public static native void nativeSetDit(long handle, String filename);

    /** Pick the LM planner GGUF file. */
    public static native void nativeSetLm(long handle, String filename);

    /** Pick the text encoder GGUF file. */
    public static native void nativeSetTextEnc(long handle, String filename);

    /** VAE tiling; use 512/32 for validated path, 1024/64 for official path. */
    public static native void nativeSetVaeTiling(long handle, int chunk, int overlap);

    /** LM KV capacity; 0 restores the 4096 default with 8192 fallback. */
    public static native void nativeSetKvSeq(long handle, int maxSeq);

    /**
     * Return a process-wide engine. The native store keeps its cached modules
     * between requests, which avoids reloading ~5.6 GB of GGUF weights on every
     * generation. Reinitialises only when the model directory changes.
     */
    public static synchronized long ensure(String dir) {
        if (sHandle != 0L && dir != null && dir.equals(sModelsDir)) {
            return sHandle;
        }
        if (sHandle != 0L) {
            nativeRelease(sHandle);
            sHandle = 0L;
            sModelsDir = null;
        }
        sHandle = nativeInit(dir, true);
        sModelsDir = sHandle != 0L ? dir : null;
        return sHandle;
    }

    public static synchronized long currentHandle() {
        return sHandle;
    }

    public static synchronized void releaseAll() {
        if (sHandle != 0L) {
            nativeRelease(sHandle);
            sHandle = 0L;
            sModelsDir = null;
        }
    }

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

    /** @return [loadMs, ditMs, vaeMs, writeMs], or null if handle is invalid. */
    public static long[] getSynthTimings(long h) {
        return nativeGetSynthTimings(h);
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

    public static void setThreads(int threads) {
        nativeSetThreads(threads);
    }

    public static void setCachePolicy(long h, int policy, long budgetMb) {
        nativeSetCachePolicy(h, policy, budgetMb);
    }

    public static void setVae(long h, String filename) {
        nativeSetVae(h, filename);
    }

    public static void setDit(long h, String filename) {
        nativeSetDit(h, filename);
    }

    public static void setLm(long h, String filename) {
        nativeSetLm(h, filename);
    }

    public static void setTextEnc(long h, String filename) {
        nativeSetTextEnc(h, filename);
    }

    public static void setVaeTiling(long h, int chunk, int overlap) {
        nativeSetVaeTiling(h, chunk, overlap);
    }

    public static void setKvSeq(long h, int maxSeq) {
        nativeSetKvSeq(h, maxSeq);
    }
}
