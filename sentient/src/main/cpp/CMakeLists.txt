cmake_minimum_required(VERSION 3.22)
project(gmind_llama LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# Same recipe as GVoice's Whisper build (app/src/main/cpp): upstream is a verified, immutable
# input; CPU only, portable baseline, no network or dynamically loaded backends.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "Portable Android arm64 baseline" FORCE)
foreach(option
    LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_TOOLS LLAMA_BUILD_EXAMPLES LLAMA_BUILD_SERVER
    LLAMA_BUILD_APP LLAMA_BUILD_UI LLAMA_BUILD_MTMD LLAMA_OPENSSL LLAMA_CURL LLAMA_BUILD_IS_DEV
    GGML_BUILD_TESTS GGML_BUILD_EXAMPLES GGML_NATIVE GGML_CCACHE GGML_LTO
    GGML_OPENMP GGML_OPENMP_FETCH GGML_BLAS GGML_ACCELERATE GGML_LLAMAFILE
    GGML_CPU_KLEIDIAI GGML_CPU_HBM GGML_CPU_ALL_VARIANTS GGML_BACKEND_DL
    GGML_METAL GGML_CUDA GGML_HIP GGML_MUSA GGML_VULKAN GGML_RPC GGML_SYCL
    GGML_OPENCL GGML_OPENVINO GGML_WEBGPU GGML_HEXAGON GGML_ZDNN GGML_ZENDNN
    GGML_VIRTGPU GGML_VIRTGPU_BACKEND GGML_ET GGML_ET_SYSEMU
    GGML_SSE42 GGML_AVX GGML_AVX2 GGML_AVX_VNNI GGML_BMI2 GGML_FMA GGML_F16C
    GGML_AVX512 GGML_AVX512_VBMI GGML_AVX512_VNNI GGML_AVX512_BF16
    GGML_AMX_TILE GGML_AMX_INT8 GGML_AMX_BF16)
    set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
set(FETCHCONTENT_FULLY_DISCONNECTED ON CACHE BOOL "No dependency fetching during builds" FORCE)
if(NOT LLAMA_SOURCE OR NOT EXISTS "${LLAMA_SOURCE}/include/llama.h")
    message(FATAL_ERROR "Set LLAMA_SOURCE to the pinned, verified source (scripts/prepare_llama.py)")
endif()

get_filename_component(REPOSITORY_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
add_compile_options("-ffile-prefix-map=${REPOSITORY_ROOT}=."
                    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=./native-build"
                    -fvisibility=hidden -fvisibility-inlines-hidden)
add_subdirectory("${LLAMA_SOURCE}" llama-build)
add_library(gmind-llama SHARED llama_jni.cpp)
target_link_libraries(gmind-llama PRIVATE llama)
target_compile_options(gmind-llama PRIVATE -Wall -Wextra -Werror)
target_link_options(gmind-llama PRIVATE -Wl,--exclude-libs,ALL
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map"
    -Wl,-z,defs -Wl,-z,relro -Wl,-z,now -Wl,-z,noexecstack)
set_target_properties(gmind-llama PROPERTIES LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/exports.map")

option(GMIND_HOST_TEST "Build a host JNI library for tests, never packaged" OFF)
if(ANDROID)
    if(NOT ANDROID_ABI MATCHES "^(arm64-v8a|x86_64)$")
        message(FATAL_ERROR "Supported distributed ABIs: arm64-v8a, x86_64")
    endif()
    target_link_libraries(gmind-llama PRIVATE log)
    target_link_options(gmind-llama PRIVATE -Wl,-z,max-page-size=16384)
elseif(GMIND_HOST_TEST)
    find_package(JNI REQUIRED)
    target_include_directories(gmind-llama PRIVATE ${JNI_INCLUDE_DIRS})
else()
    message(FATAL_ERROR "Android NDK required; host builds require GMIND_HOST_TEST")
endif()
