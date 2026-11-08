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

# The noise catcher of Speex: only the parts the preprocessor needs.
add_library(wifigram-speex STATIC
    speexdsp/libspeexdsp/preprocess.c
    speexdsp/libspeexdsp/fftwrap.c
    speexdsp/libspeexdsp/smallft.c
    speexdsp/libspeexdsp/filterbank.c
    speexdsp/libspeexdsp/mdf.c
)
target_include_directories(wifigram-speex PUBLIC
    speexdsp/include
    speexdsp/libspeexdsp
)
target_compile_definitions(wifigram-speex PRIVATE
    FLOATING_POINT
    OUTSIDE_SPEEX
    RANDOM_PREFIX=wfg
    USE_SMALLFT=1
    EXPORT=
)
target_compile_options(wifigram-speex PRIVATE -w -include speex/speexdsp_types.h)

add_library(wifigram-opus SHARED opus_jni.cpp speex_jni.cpp)
target_include_directories(wifigram-opus PRIVATE opus/include speexdsp/include)
target_link_libraries(wifigram-opus opus log wifigram-speex)
