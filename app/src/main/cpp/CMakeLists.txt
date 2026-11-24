cmake_minimum_required(VERSION 3.22.1)
project(djfulanipro_audio LANGUAGES CXX)

include(FetchContent)
set(SOUNDSTRETCH OFF CACHE BOOL "" FORCE)
set(SOUNDTOUCH_DLL OFF CACHE BOOL "" FORCE)
set(OPENMP OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    soundtouch
    GIT_REPOSITORY https://github.com/rtiangha/soundtouch.git
    GIT_TAG f738b1132ec1fd56efc90367898244cf52d9e6a5
    GIT_SHALLOW TRUE
    GIT_PROGRESS TRUE
)
FetchContent_MakeAvailable(soundtouch)

add_library(djfulani_audio SHARED native_audio_engine.cpp)
find_package(oboe REQUIRED CONFIG)

target_include_directories(djfulani_audio PRIVATE
    "${soundtouch_SOURCE_DIR}/include"
)
target_compile_features(djfulani_audio PRIVATE cxx_std_20)
target_compile_options(djfulani_audio PRIVATE -O3 -Wall -Wextra)
target_link_libraries(djfulani_audio
    oboe::oboe
    SoundTouch
    android
    log
)
