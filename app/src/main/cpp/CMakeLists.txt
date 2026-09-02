cmake_minimum_required(VERSION 3.22.1)
project(kayan_llama)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
include(FetchContent)
set(LLAMA_TAG "b4600" CACHE STRING "llama.cpp stable")
FetchContent_Declare(llama_cpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG ${LLAMA_TAG}
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(llama_cpp)
add_library(kayan_llama SHARED llama_jni.cpp)
target_include_directories(kayan_llama PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/common
)
find_library(log-lib log)
target_link_libraries(kayan_llama PRIVATE llama common ${log-lib} android)
if(TARGET ggml-base)
    target_link_libraries(kayan_llama PRIVATE ggml-base ggml-cpu ggml)
elseif(TARGET ggml)
    target_link_libraries(kayan_llama PRIVATE ggml)
endif()
