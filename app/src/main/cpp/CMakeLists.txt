cmake_minimum_required(VERSION 3.10)

project(moereng)

# Default location expected by this project:
# app/src/main/cpp/ncnn/<abi>/lib/cmake/ncnn/ncnnConfig.cmake
set(_default_ncnn_dir "${CMAKE_SOURCE_DIR}/ncnn/${ANDROID_ABI}/lib/cmake/ncnn")
set(ncnn_DIR "${_default_ncnn_dir}" CACHE PATH "Directory containing ncnnConfig.cmake")

if (NOT EXISTS "${ncnn_DIR}/ncnnConfig.cmake" AND NOT EXISTS "${ncnn_DIR}/ncnn-config.cmake")
        message(FATAL_ERROR
                        "ncnn config not found. Set -Dncnn_DIR=<path-to-cmake-ncnn> or place ncnn under app/src/main/cpp/ncnn/.\n"
                        "Current ANDROID_ABI=${ANDROID_ABI}\n"
                        "Expected default path: ${_default_ncnn_dir}")
endif()

find_package(ncnn CONFIG REQUIRED)

add_subdirectory(openjtalk)
add_subdirectory(fftpack)
add_subdirectory(wave_utils)

aux_source_directory(vits vits_source)
include_directories(${CMAKE_SOURCE_DIR}/vits)

add_library(moereng SHARED ${vits_source} vitsncnn_jni.cpp)

find_library(
        android-lib
        android
)

find_library(
        log-lib
        log
)

target_link_libraries(moereng ncnn ${android-lib} ${log-lib} libopenjtalk libfftpack libwaveutils)
