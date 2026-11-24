cmake_minimum_required(VERSION 3.22.1)
project(djfulanipro_audio LANGUAGES CXX)

include(FetchContent)
set(SOUNDSTRETCH OFF CACHE BOOL "" FORCE)
set(SOUNDTOUCH_DLL OFF CACHE BOOL "" FORCE)
set(OPENMP OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    soundtouch
    URL https://www.surina.net/soundtouch/soundtouch-2.4.1.tar.gz
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(soundtouch)

add_library(djfulani_audio SHARED native_audio_engine.cpp)
find_package(oboe REQUIRED CONFIG)

target_compile_features(djfulani_audio PRIVATE cxx_std_20)
target_compile_options(djfulani_audio PRIVATE -O3 -Wall -Wextra)
target_link_libraries(djfulani_audio
    oboe::oboe
    SoundTouch
    android
    log
)
