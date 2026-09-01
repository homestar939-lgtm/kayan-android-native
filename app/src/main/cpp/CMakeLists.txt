cmake_minimum_required(VERSION 3.22.1)
project(kayan_llama VERSION 2.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ── Compiler flags ─────────────────────────────────────────────────────────
set(CMAKE_C_FLAGS_RELEASE   "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")

# ── llama.cpp via FetchContent ─────────────────────────────────────────────
# Pin to a specific release tag for reproducibility.
# Run `cmake -DLLAMA_TAG=<tag> ...` to override.
set(LLAMA_TAG "b3967" CACHE STRING "llama.cpp git tag/commit to build")

include(FetchContent)
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        ${LLAMA_TAG}
    GIT_SHALLOW    TRUE
)

# Disable everything we don't need to keep .so lean
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)  # mandatory for cross-compile
set(GGML_USE_VULKAN      OFF CACHE BOOL "" FORCE)  # flip ON if you ship Vulkan SDK

FetchContent_MakeAvailable(llama_cpp)

# ── JNI bridge ────────────────────────────────────────────────────────────
add_library(
    kayan_llama          # name used by System.loadLibrary("kayan_llama")
    SHARED
    llama_jni.cpp
)

target_include_directories(kayan_llama PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
    ${llama_cpp_SOURCE_DIR}
)

# Link llama, ggml, android log, and jnigraphics (for EGL GPU query)
target_link_libraries(kayan_llama
    PRIVATE
    llama
    ggml
    android
    log
    EGL           # for GPU vendor detection via EGL_VENDOR string
    GLESv2
)

# ── Trim .so size: strip symbols in release ────────────────────────────────
if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_custom_command(TARGET kayan_llama POST_BUILD
        COMMAND ${CMAKE_STRIP} --strip-unneeded $<TARGET_FILE:kayan_llama>
        COMMENT "Stripping kayan_llama.so"
    )
endif()
