# OmniLLM — native build.
#
# Cross-compiled by the Android Gradle plugin through externalNativeBuild (see core/build.gradle.kts).
# llama.cpp is vendored at ./llama.cpp (pinned commit recorded in README.md) so the project
# builds offline with no submodule fetch.

cmake_minimum_required(VERSION 3.22.1)

project(omnillm LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# --------------------------------------------------------------------------------------------
# Options
# --------------------------------------------------------------------------------------------
# Vulkan offload is opt-in: it needs glslc from the NDK and is only worth enabling once you have
# verified the stock CPU build runs. Pass -Pomni.vulkan=true to Gradle to turn it on.
option(OMNI_VULKAN "Build the ggml Vulkan backend (GPU layer offload)" OFF)

# KleidiAI kernels are ~10-25% faster on ARMv8.2+ but ggml fetches them from GitHub at *configure*
# time, so leaving this on makes an offline build fail. Opt in with -Pomni.kleidiai=true.
option(OMNI_KLEIDIAI "Fetch and use ARM KleidiAI kernels (needs network at configure time)" OFF)

# Everything else llama.cpp can build is dead weight inside an APK.
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE          OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)   # never use -march=native when cross-compiling
set(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)   # static backends: one .so, no dlopen surprises
set(GGML_OPENCL          OFF CACHE BOOL "" FORCE)   # documented Adreno/Mali driver crashes
set(GGML_VULKAN          ${OMNI_VULKAN} CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ${OMNI_KLEIDIAI} CACHE BOOL "" FORCE)
    set(GGML_OPENMP        ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    # 32-bit fallback: no dot-product kernels, no OpenMP, and the model must be tiny anyway.
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP        OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ARM_ARCH  "armv7-a" CACHE STRING "" FORCE)
endif()

if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp sources are missing.\n"
        "This project vendors llama.cpp at core/src/main/cpp/llama.cpp.\n"
        "Either restore it from the release archive (scripts/fetch_llama.sh) or clone it:\n"
        "  git clone --depth 1 https://github.com/ggml-org/llama.cpp "
        "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
endif()

# --------------------------------------------------------------------------------------------
# Vulkan header packaging
# --------------------------------------------------------------------------------------------
# ggml-vulkan includes <vulkan/vulkan.hpp>. The Android NDK ships only the C headers, and
# Vulkan-Headers ships as a git submodule that source archives do not contain. Accept explicit
# paths so a prebuilt/packaged header tree can be used:
#   -POMNI_VULKAN_HEADERS_DIR=<dir containing vulkan/vulkan.hpp>
#   -DOMNI_SPIRV_HEADERS_DIR=<dir containing spirv/unified1>
if(OMNI_VULKAN)
    if(OMNI_VULKAN_HEADERS_DIR AND EXISTS "${OMNI_VULKAN_HEADERS_DIR}/vulkan/vulkan.hpp")
        include_directories("${OMNI_VULKAN_HEADERS_DIR}")
        message(STATUS "OmniLLM: using external Vulkan headers from ${OMNI_VULKAN_HEADERS_DIR}")
    else()
        message(FATAL_ERROR
            "OMNI_VULKAN is ON but <vulkan/vulkan.hpp> was not provided.\n"
            "Pass -DOMNI_VULKAN_HEADERS_DIR=<Vulkan-Headers/include> (scripts/fetch_vulkan_headers.sh).")
    endif()
    if(OMNI_SPIRV_HEADERS_DIR AND EXISTS "${OMNI_SPIRV_HEADERS_DIR}/spirv/unified1")
        set(GGML_VULKAN_SPIRV_INCLUDE_DIR "${OMNI_SPIRV_HEADERS_DIR}" CACHE PATH "" FORCE)
    endif()
endif()

add_subdirectory(llama.cpp llama-build)

# --------------------------------------------------------------------------------------------
# Our JNI library
# --------------------------------------------------------------------------------------------
add_library(omnillm SHARED
        jni_bridge.cpp
        omni_engine.cpp)

target_include_directories(omnillm PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
        ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include)

target_compile_options(omnillm PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(omnillm PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)

target_link_libraries(omnillm
        llama
        ggml
        android
        log)

if(OMNI_VULKAN)
    # ggml-vulkan is registered automatically at init once linked in.
    target_link_libraries(omnillm ggml-vulkan)
    target_compile_definitions(omnillm PRIVATE OMNI_HAS_VULKAN=1)
endif()
