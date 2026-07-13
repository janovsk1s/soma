cmake_minimum_required(VERSION 3.22.1)
project(soma_whisper LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/vendor/whisper.cpp)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

# Preview APKs are debug-signed so they can update in place on a test phone, but
# unoptimized ggml inference is more than an order of magnitude slower. Retain
# debug symbols while optimizing the native transcription engine and ggml.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(-O2)
endif()

add_subdirectory(${WHISPER_ROOT}/ggml ${CMAKE_CURRENT_BINARY_DIR}/ggml)

add_library(
    soma_whisper
    SHARED
    soma_whisper_jni.cpp
    ${WHISPER_ROOT}/src/whisper.cpp
)

target_compile_definitions(soma_whisper PRIVATE GGML_USE_CPU WHISPER_VERSION="1.9.1")
target_include_directories(
    soma_whisper
    PRIVATE
    ${WHISPER_ROOT}
    ${WHISPER_ROOT}/src
    ${WHISPER_ROOT}/include
    ${WHISPER_ROOT}/ggml/include
    ${WHISPER_ROOT}/ggml/src
    ${WHISPER_ROOT}/ggml/src/ggml-cpu
)

find_library(ANDROID_LOG log)
find_library(ANDROID_LIB android)
target_link_libraries(soma_whisper PRIVATE ggml ${ANDROID_LOG} ${ANDROID_LIB})

if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(soma_whisper PRIVATE -O3 -ffunction-sections -fdata-sections)
    target_link_options(soma_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -flto)
endif()
