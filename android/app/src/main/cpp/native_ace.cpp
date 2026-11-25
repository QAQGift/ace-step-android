#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <stdlib.h>
#include <string>

#include "request.h"
#include "model-store.h"
#include "pipeline-lm.h"
#include "pipeline-synth.h"
#include "audio-io.h"

#define LOG_TAG "AceJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct AceEngine {
    std::string models_dir;
    std::string lm_path;
    std::string text_enc_path;
    std::string dit_path;
    std::string vae_path;
    int          vae_chunk   = 512;   // mobile validated default
    int          vae_overlap = 32;
    ModelStore * store = nullptr;
    std::atomic<bool> cancelled{false};
};

static bool cancel_cb(void * data) {
    return static_cast<AceEngine *>(data)->cancelled.load();
}

static long long elapsed_ms(std::chrono::steady_clock::time_point a,
                            std::chrono::steady_clock::time_point b) {
    return (long long) std::chrono::duration_cast<std::chrono::milliseconds>(b - a).count();
}

static std::string jstring_to_std(JNIEnv * env, jstring s) {
    if (!s) return "";
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

// Ignore the keepLoaded flag: on mobile we must use STRICT eviction to stay
// within the per-process memory budget. Loading all ~5.6 GB of weights at once
// gets the process killed by the low-memory killer.
extern "C" JNIEXPORT jlong JNICALL
Java_com_example_ace_AceEngine_nativeInit(JNIEnv * env, jclass /*clazz*/,
                                          jstring j_models_dir, jboolean /*keep_loaded*/) {
    auto * e = new AceEngine();
    e->models_dir = jstring_to_std(env, j_models_dir);
    if (e->models_dir.empty() || e->models_dir.back() != '/') e->models_dir += '/';

    e->lm_path = e->models_dir + "acestep-5Hz-lm-1.7B-Q8_0.gguf";
    e->text_enc_path = e->models_dir + "Qwen3-Embedding-0.6B-Q8_0.gguf";
    e->dit_path = e->models_dir + "acestep-v15-turbo-Q8_0.gguf";
    e->vae_path = e->models_dir + "vae-BF16.gguf";

    // STRICT: at most one GPU module resident at a time.
    e->store = store_create(EVICT_STRICT);
    if (!e->store) {
        LOGE("store_create failed");
        delete e;
        return 0;
    }

    LOGI("engine initialized (STRICT), models=%s, cpu_flash_attn=%s",
         e->models_dir.c_str(), backend_use_flash_attn() ? "ON" : "OFF");
    return reinterpret_cast<jlong>(e);
}

// Choose the VAE decoder GGUF for this engine instance. ScragVAE is a
// decoder-only fine-tune that restores high frequencies lost by stock VAE.
extern "C" JNIEXPORT void JNICALL
Java_com_example_ace_AceEngine_nativeSetVae(JNIEnv * env, jclass, jlong handle,
                                            jstring j_name) {
    auto * e = reinterpret_cast<AceEngine *>(handle);
    if (!e) return;
    std::string name = jstring_to_std(env, j_name);
    if (name.empty()) name = "vae-BF16.gguf";
    if (name.find('/') == std::string::npos) {
        e->vae_path = e->models_dir + name;
    } else {
        e->vae_path = name;
    }
    LOGI("VAE decoder set to %s", e->vae_path.c_str());
}

// Set the DiT GGUF file (fixed to official turbo in this build).
extern "C" JNIEXPORT void JNICALL
Java_com_example_ace_AceEngine_nativeSetDit(JNIEnv * env, jclass, jlong handle,
                                           jstring j_name) {
    auto * e = reinterpret_cast<AceEngine *>(handle);
    if (!e) return;
    std::string name = jstring_to_std(env, j_name);
    if (name.empty()) name = "acestep-v15-turbo-Q8_0.gguf";
    if (name.find('/') == std::string::npos) {
        e->dit_path = e->models_dir + name;
    } else {
        e->dit_path = name;
    }
    LOGI("DiT set to %s", e->dit_path.c_str());
}

// A/B switch: backend_use_flash_attn() reads this env var on every call from
// every translation unit, so changing the process environment here is enough
// to switch all six attention sites without recompiling.
extern "C" JNIEXPORT void JNICALL
Java_com_example_ace_AceEngine_nativeSetFlashAttn(JNIEnv *, jclass, jboolean enabled) {
    setenv("ACESTEP_CPU_FLASH_ATTN", enabled ? "1" : "0", 1);
    LOGI("cpu_flash_attn switched to %s (process env)", enabled ? "ON" : "OFF");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ace_AceEngine_nativeLm(JNIEnv * env, jclass /*clazz*/,
                                        jlong handle, jstring j_request_json) {
    auto * e = reinterpret_cast<AceEngine *>(handle);
    if (!e) return nullptr;
    e->cancelled = false;

    // Build an LM context just for this call; freed on return.
    AceLmParams lp;
    ace_lm_default_params(&lp);
    lp.model_path = e->lm_path.c_str();
    // Keep official 8192 KV capacity. Phase1 max_new_tokens=2048 plus a long
    // prompt (CJK lyrics + CoT) can exceed 2048; on overflow qw3lm_forward logs
    // FATAL and returns WITHOUT writing logits → planner samples garbage
    // (wrong bpm/language). 8192 is what upstream uses and avoids this path.
    lp.max_batch = 1;
    auto lm_t0 = std::chrono::steady_clock::now();
    AceLm * lm = ace_lm_load(e->store, &lp);
    auto lm_t1 = std::chrono::steady_clock::now();
    if (!lm) {
        LOGE("LM load failed");
        return nullptr;
    }
    LOGI("LM context loaded in %lld ms", elapsed_ms(lm_t0, lm_t1));

    AceRequest req;
    std::string json = jstring_to_std(env, j_request_json);
    if (!request_parse_json(&req, json.c_str())) {
        LOGE("invalid request json");
        ace_lm_free(lm);
        return nullptr;
    }

    // Upstream CLI/server call request_resolve_lm_seed() before ace_lm_generate.
    // Without it lm_seed stays -1 → (uint32_t)-1 for both planner and codes,
    // so the UI seed never controls the LM stage. Mirror the UI seed into lm_seed
    // (when the caller did not set one) so planning is reproducible.
    if (req.lm_seed < 0) {
        req.lm_seed = req.seed;
    }
    request_resolve_seed(&req);
    request_resolve_lm_seed(&req);
    LOGI("LM seeds: seed=%lld lm_seed=%lld", (long long) req.seed, (long long) req.lm_seed);

    AceRequest out;
    auto lm_t2 = std::chrono::steady_clock::now();
    int rc = ace_lm_generate(lm, &req, 1, &out,
                             nullptr, nullptr,
                             cancel_cb, e, LM_MODE_GENERATE);
    auto lm_t3 = std::chrono::steady_clock::now();
    ace_lm_free(lm);
    auto lm_t4 = std::chrono::steady_clock::now();
    LOGI("LM timings: load=%lldms generate=%lldms free=%lldms",
         elapsed_ms(lm_t0, lm_t1), elapsed_ms(lm_t2, lm_t3), elapsed_ms(lm_t3, lm_t4));

    if (rc != 0) {
        LOGE("ace_lm_generate failed: %d", rc);
        return nullptr;
    }

    std::string result = request_to_json(&out, false);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ace_AceEngine_nativeSynth(JNIEnv * env, jclass /*clazz*/,
                                           jlong handle, jstring j_request_json,
                                           jstring j_output_path) {
    auto * e = reinterpret_cast<AceEngine *>(handle);
    if (!e) return nullptr;
    e->cancelled = false;

    AceRequest req;
    std::string json = jstring_to_std(env, j_request_json);
    if (!request_parse_json(&req, json.c_str())) {
        LOGE("invalid synth request json");
        return nullptr;
    }
    request_resolve_seed(&req);  // upstream behavior: random_device when seed<0

    // Build synth context only for this call.
    AceSynthParams sp;
    ace_synth_default_params(&sp);
    sp.text_encoder_path = e->text_enc_path.c_str();
    sp.dit_path = e->dit_path.c_str();
    sp.vae_path = e->vae_path.c_str();
    // 30s @ 25Hz latent = 750 frames. chunk>750 would fall into the direct
    // (non-tiled) decode path which produces audible distortion on this port.
    // Keep chunk<=512 so tiled decode (the validated path) is always used.
    // Defaults below are the validated mobile tiling path. The upstream
    // acestep.cpp defaults are 1024/64; a JNI switch can override them to
    // compare against the official (non-tiled for 30s) decode path.
    int vae_chunk   = e->vae_chunk;
    int vae_overlap = e->vae_overlap;
    if (vae_chunk > 0 && vae_overlap > 0 && vae_chunk - 2 * vae_overlap > 0) {
        sp.vae_chunk   = vae_chunk;
        sp.vae_overlap = vae_overlap;
    } else {
        sp.vae_chunk   = 512;
        sp.vae_overlap = 32;
    }
    LOGI("VAE tiling: chunk=%d overlap=%d", sp.vae_chunk, sp.vae_overlap);
    LOGI("loading synth: textenc=%s", e->text_enc_path.c_str());
    LOGI("loading synth: dit=%s", e->dit_path.c_str());
    LOGI("loading synth: vae=%s", e->vae_path.c_str());
    auto s_t0 = std::chrono::steady_clock::now();
    AceSynth * synth = ace_synth_load(e->store, &sp);
    auto s_t1 = std::chrono::steady_clock::now();
    if (!synth) {
        LOGE("synth load failed (see AceNative for detail)");
        return nullptr;
    }
    LOGI("synth context loaded in %lld ms", elapsed_ms(s_t0, s_t1));

    auto s_t2 = std::chrono::steady_clock::now();
    AceSynthJob * job = ace_synth_job_run_dit(
        synth, &req,
        nullptr, 0, nullptr, 0,
        nullptr, 0, nullptr, 0,
        1, cancel_cb, e);
    auto s_t3 = std::chrono::steady_clock::now();
    if (!job) {
        LOGE("ace_synth_job_run_dit failed");
        ace_synth_free(synth);
        return nullptr;
    }

    AceAudio audio{};
    int rc = ace_synth_job_run_vae(synth, job, &audio, cancel_cb, e);
    auto s_t4 = std::chrono::steady_clock::now();
    ace_synth_job_free(job);
    ace_synth_free(synth);
    LOGI("synth timings: load=%lldms dit=%lldms vae=%lldms",
         elapsed_ms(s_t0, s_t1), elapsed_ms(s_t2, s_t3), elapsed_ms(s_t3, s_t4));

    if (rc != 0 || !audio.samples) {
        LOGE("ace_synth_job_run_vae failed: %d", rc);
        ace_audio_free(&audio);  // avoid leaking a partially filled buffer
        return nullptr;
    }

    std::string out_path = jstring_to_std(env, j_output_path);
    if (out_path.empty()) out_path = e->models_dir + "output.wav";

    // BUGFIX: audio.samples from ops_vae_decode is ALREADY planar stereo
    // [L0..LT, R0..RT], and audio_write()/audio_encode_wav_s16() expect exactly
    // that layout (L = audio, R = audio + T_audio). The previous code ran
    // audio_planar_to_interleaved() here, producing an interleaved stream that
    // audio_write then re-interpreted as planar -> channels got scrambled
    // (vocals unintelligible, high ZCR). Official ace-synth.cpp passes samples
    // straight to audio_write with no conversion. Do the same here.
    bool ok = false;
    auto s_t5 = std::chrono::steady_clock::now();
    if (audio.samples) {
        ok = audio_write(out_path.c_str(), audio.samples, audio.n_samples,
                         audio.sample_rate, req.mp3_bitrate, WAV_S16, req.peak_clip);
    }
    auto s_t6 = std::chrono::steady_clock::now();
    LOGI("audio write took %lld ms", elapsed_ms(s_t5, s_t6));
    ace_audio_free(&audio);

    if (!ok) {
        LOGE("audio_write failed: %s", out_path.c_str());
        return nullptr;
    }
    LOGI("audio written: %s", out_path.c_str());
    return env->NewStringUTF(out_path.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ace_AceEngine_nativeCancel(JNIEnv *, jclass, jlong handle) {
    auto * e = reinterpret_cast<AceEngine *>(handle);
    if (e) e->cancelled = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ace_AceEngine_nativeRelease(JNIEnv *, jclass, jlong handle) {
    auto * e = reinterpret_cast<AceEngine *>(handle);
    if (!e) return;
    if (e->store) store_free(e->store);
    delete e;
    LOGI("engine released");
}
