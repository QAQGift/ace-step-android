# ACE-Step Android

On-device AI music generation for Android, powered by [acestep.cpp](https://github.com/ServeurpersoCom/acestep.cpp) (GGML). Write a caption and lyrics, get stereo 48 kHz audio — entirely on your phone, no server or internet required after models are downloaded.

This is a focused Android port: the app, the JNI bridge, and the native inference core. Upstream desktop tooling (WebUI, CLI server, CUDA/Vulkan build scripts) has been removed from this branch.

## Features

- Text-to-music generation from a caption + lyrics (or instrumental with empty lyrics)
- Explicit music metadata: BPM, key/scale, time signature — leave blank to let the planner decide
- Optional language tag in lyrics (e.g. `[zh]`) to pin the vocal language
- In-app audio player: play/pause, seek, progress, auto-loads the latest generation on startup
- Generation history with timings, seed, and metadata
- On-device model download from hf-mirror with resume support
- Automatic restore of the last successful track

## Requirements

- Android 8.0+ (API 26), arm64-v8a
- ~5.6 GB free storage for models
- A device with enough RAM for the 1.7B planner + DiT + VAE (STRICT model eviction keeps peak memory bounded)

## Models

The app downloads these four official GGUF files from the [Serveurperso/ACE-Step-1.5-GGUF](https://huggingface.co/Serveurperso/ACE-Step-1.5-GGUF) repository (via hf-mirror):

| Type | File | Size |
|---|---|---|
| Planner LM 1.7B Q8_0 | `acestep-5Hz-lm-1.7B-Q8_0.gguf` | 1.84 GB |
| Text encoder 0.6B Q8_0 | `Qwen3-Embedding-0.6B-Q8_0.gguf` | 748 MB |
| DiT turbo Q8_0 | `acestep-v15-turbo-Q8_0.gguf` | 2.37 GB |
| VAE BF16 | `vae-BF16.gguf` | 322 MB |

Open the **Models** tab, set a model directory, tap **Download models**, then **Check models** to verify 4/4 ready.

## Usage

1. **Models** tab → download and verify the four models.
2. **Compose** tab → write a prompt (style/instruments/vocal), optionally lyrics and metadata, choose duration and seed.
3. Tap **Generate**. Generation runs in a foreground service; progress is shown in the app.
4. Play the result with the built-in player. The last track is restored when the app reopens.

Tips:

- Empty lyrics → instrumental. Lyrics with section tags (e.g. `[verse]`) → vocals. Put `[zh]` / `[en]` on its own line to pin the language.
- Keep the caption consistent: describe instruments and vocals together. Avoid contradictory style words.
- A fixed seed replays the same planning (the app pins both the DiT seed and the LM seed).

## Build

Prerequisites: Android SDK (API 34), NDK with CMake 3.22, a recent JDK, and the patched `ggml` submodule.

```bash
git clone --recurse-submodules <this-repo>
cd ace-step-android

# Build the native library (ace-jni) into the JNI libs directory:
cmake --build build-simd --target ace-jni -j 8
cp build-simd/libace-jni.so android/app/src/main/jniLibs/arm64-v8a/

# Build the APK:
gradle -p android assembleDebug
# Output: android/app/build/outputs/apk/debug/app-debug.apk
```

The Android native build reads only `src/` (5 `.cpp` files + headers), `vendor/yyjson`, `vendor/minimp3`, `mp3/`, and the `ggml` submodule. `android/app/src/main/cpp/CMakeLists.txt` is the authoritative build definition.

## Project layout

```
android/                     Android Gradle project (app + JNI bridge)
  app/src/main/cpp/          native_ace.cpp — JNI entry points
  app/src/main/java/.../ace/ UI, services, player, history
  app/src/main/jniLibs/      prebuilt arm64-v8a .so libraries
src/                         shared inference core (LM, DiT, VAE, request, model store)
vendor/                      yyjson + minimp3 (minimal C dependencies)
mp3/                         MP3 encoder tables (used by audio-io.h)
ggml/                        patched GGML submodule (inference backend)
```

## Notes

- The stable configuration is hardcoded: official turbo DiT, stock BF16 VAE, 512/32 tiled decode, flash attention on, `keep_caption` on. Diagnostic toggles for these were removed in the stable build.
- Audio output is 48 kHz stereo WAV; the WAV writer expects planar stereo, matching the VAE decoder output.

## Acknowledgements

Based on [acestep.cpp](https://github.com/ServeurpersoCom/acestep.cpp) by Serveurperso, which implements [ACE-Step 1.5](https://github.com/ace-step/ACE-Step-1.5) by ACE Studio and StepFun in GGML. All model weights belong to their respective authors.

```bibtex
@misc{gong2026acestep,
  title={ACE-Step 1.5: Pushing the Boundaries of Open-Source Music Generation},
  author={Junmin Gong, Yulin Song, Wenxiao Zhao, Sen Wang, Shengyuan Xu, Jing Guo},
  howpublished={\url{https://github.com/ace-step/ACE-Step-1.5}},
  year={2026},
  note={GitHub repository}
}
```
