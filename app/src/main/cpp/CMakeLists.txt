cmake_minimum_required(VERSION 3.22.1)
project(wifigram LANGUAGES C CXX)

set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_CUSTOM_MODES OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_DEEP_PLC OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)

add_subdirectory(opus)

add_library(wifigram-opus SHARED opus_jni.cpp)
target_include_directories(wifigram-opus PRIVATE opus/include)
target_link_libraries(wifigram-opus opus log)
