# Host toolchain for building vulkan-shaders-gen (a Windows host tool) during
# Android cross-compilation. GGML's ExternalProject uses this instead of the
# NDK toolchain so the shader generator is a native Windows exe.
set(CMAKE_BUILD_TYPE Release)
# ExternalProject's nested configure uses -GNinja but does not inherit
# CMAKE_MAKE_PROGRAM from the parent, so pin it explicitly here.
set(CMAKE_MAKE_PROGRAM "C:/Users/wwtts/AppData/Local/Android/Sdk/cmake/3.22.1/bin/ninja.exe")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE NEVER)

set(MSVC_BIN "C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools/VC/Tools/MSVC/14.44.35207/bin/Hostx64/x64")
set(CMAKE_C_COMPILER   "${MSVC_BIN}/cl.exe")
set(CMAKE_CXX_COMPILER "${MSVC_BIN}/cl.exe")
# Don't generate/manifest-embed — avoids needing mt.exe entirely for this host tool.
set(CMAKE_CXX_STANDARD_LIBRARIES "kernel32.lib user32.lib gdi32.lib winspool.lib shell32.lib ole32.lib oleaut32.lib uuid.lib comdlg32.lib advapi32.lib")
string(APPEND CMAKE_EXE_LINKER_FLAGS " /MANIFEST:NO")
set(CMAKE_MT "C:/Program Files (x86)/Windows Kits/10/bin/10.0.26100.0/x64/mt.exe")

# Ninja + MSVC needs the compiler to pass the initial detection.
set(CMAKE_C_COMPILER_WORKS 1)
set(CMAKE_CXX_COMPILER_WORKS 1)

# Output the generator exe somewhere deterministic.
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/host-bin")
foreach(CONFIG IN ITEMS DEBUG RELEASE MINSIZEREL RELWITHDEBINFO)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY_${CONFIG} "${CMAKE_BINARY_DIR}/host-bin")
endforeach()
