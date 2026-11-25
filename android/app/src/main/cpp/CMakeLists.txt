cmake_minimum_required(VERSION 3.22)
project(ace-android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# cpp/ -> main/ -> src/ -> app/ -> android/ -> repo root
get_filename_component(ACE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(GGML_SOURCE_DIR "${ACE_ROOT}/ggml" CACHE PATH "Patched GGML fork")

# Android 15+ requires 16 KB page-size aligned LOAD segments for all shared
# libraries. Must be set before add_subdirectory(ggml) so GGML .so files inherit it.
if(ANDROID)
  set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
  set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
endif()

# OpenMP needs libomp.so at runtime, which is not shipped with this APK.
# Inference is single-batch so OpenMP parallelism is not needed.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# DiT tensor names exceed GGML default of 64. This MUST be global (before
# add_subdirectory) so the ggml library itself is compiled with 128 too;
# setting it only on ace-core causes gguf_init_from_file to fail parsing DiT.
add_compile_definitions(GGML_MAX_NAME=128)

# ARM SIMD: target Snapdragon 8 Elite (Oryon, ARMv9.2). -march with dotprod +
# i8mm makes clang define __ARM_FEATURE_DOTPROD / __ARM_FEATURE_MATMUL_INT8,
# which enables GGML's quantized GEMM kernels (quants.c/repack.cpp). Set
# globally before add_subdirectory so both ggml-cpu and ace-core inherit it.
# GGML_NATIVE is OFF for cross-compile, so it won't add these on its own.
if(ANDROID AND ACE_ARM_ARCH)
  add_compile_options(-march=${ACE_ARM_ARCH})
  message(STATUS "ACE: compiling with -march=${ACE_ARM_ARCH}")
endif()

# Backend selection. Command line -DGGML_VULKAN=ON/OFF can override these.
set(GGML_VULKAN ON CACHE BOOL "")
set(GGML_CUDA OFF CACHE BOOL "")
set(GGML_METAL OFF CACHE BOOL "")
set(GGML_BLAS OFF CACHE BOOL "")
set(GGML_SYCL OFF CACHE BOOL "")
set(GGML_LLAMAFILE OFF CACHE BOOL "")

add_subdirectory(${GGML_SOURCE_DIR} ggml)

add_library(ace-core STATIC
  ${ACE_ROOT}/src/request.cpp
  ${ACE_ROOT}/src/model-store.cpp
  ${ACE_ROOT}/src/pipeline-lm.cpp
  ${ACE_ROOT}/src/pipeline-synth.cpp
  ${ACE_ROOT}/src/pipeline-synth-ops.cpp
  ${ACE_ROOT}/vendor/yyjson/yyjson.c)

target_include_directories(ace-core PUBLIC
  ${ACE_ROOT}/src
  ${ACE_ROOT}
  ${CMAKE_CURRENT_BINARY_DIR}
  ${ACE_ROOT}/vendor/yyjson)
target_include_directories(ace-core SYSTEM PRIVATE ${GGML_SOURCE_DIR}/include)

target_link_libraries(ace-core PUBLIC ggml)
if(TARGET ggml-base)
  target_link_libraries(ace-core PUBLIC ggml-base)
endif()
if(TARGET ggml-vulkan)
  # NDK sysroot ships only the C header (vulkan.h, header version 275) and no
  # C++ binding (vulkan.hpp). Use the matching pair from the Vulkan SDK so the
  # versions stay consistent. Placed BEFORE so it wins over the sysroot copy.
  if(DEFINED VULKAN_SDK_INCLUDE_DIR)
    target_include_directories(ggml-vulkan BEFORE PRIVATE ${VULKAN_SDK_INCLUDE_DIR})
  endif()
  target_link_libraries(ace-core PUBLIC ggml-vulkan)
endif()
find_package(Threads REQUIRED)
target_link_libraries(ace-core PUBLIC Threads::Threads)

add_library(ace-jni SHARED native_ace.cpp)
target_link_libraries(ace-jni PRIVATE ace-core android log)
