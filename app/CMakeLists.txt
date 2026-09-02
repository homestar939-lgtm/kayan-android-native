cmake_minimum_required(VERSION 3.22.1)
project(kayan_llama)

# Fetch llama.cpp b4600
include(FetchContent)
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG b4600
)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(llama_cpp)

add_library(kayan_llama SHARED llama_jni.cpp)

# FIX Build #16 linker error: remove -lcommon, b4600 doesn't produce libcommon.a for Android
# Only link llama which already contains ggml
find_library(log-lib log)

target_link_libraries(kayan_llama PRIVATE
    llama
    ${log-lib}
    android
)

target_include_directories(kayan_llama PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/common
    ${llama_cpp_SOURCE_DIR}/ggml/include
)
