# FIXED: Compatible with llama.cpp b3548 - stable for Android NDK 26
cmake_minimum_required(VERSION 3.22.1)
project(kayan_llama)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Speed up and reduce size for Android
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

include(FetchContent)
# b3548 is LAST stable tag where single link to llama works perfectly on Android
set(LLAMA_TAG "b3548" CACHE STRING "llama.cpp stable")

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG ${LLAMA_TAG}
)
FetchContent_MakeAvailable(llama_cpp)

add_library(kayan_llama SHARED llama_jni.cpp)

target_include_directories(kayan_llama PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
    ${llama_cpp_SOURCE_DIR}/common
)

find_library(log-lib log)

# Defensive link - works for old and new llama.cpp structure
if(TARGET ggml-base)
    target_link_libraries(kayan_llama PRIVATE llama common ggml ggml-base ggml-cpu ${log-lib} android)
else()
    target_link_libraries(kayan_llama PRIVATE llama common ggml ${log-lib} android)
endif()
