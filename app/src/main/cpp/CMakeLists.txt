cmake_minimum_required(VERSION 3.22.1)

project("openkamera_native")

# Set C++ standard to C++17
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimization flags
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -Wall")

# Include directories
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/utils
    ${CMAKE_CURRENT_SOURCE_DIR}/preview
    ${CMAKE_CURRENT_SOURCE_DIR}/mtb
    ${CMAKE_CURRENT_SOURCE_DIR}/hdr
    ${CMAKE_CURRENT_SOURCE_DIR}/panorama
)

# Explicit source and header files for compilation and IDE indexing
set(NATIVE_SOURCES
    native_bridge.cpp
    utils/neon_math.h
    utils/bitmap_utils.h
    preview/histogram_compute.cpp
    preview/histogram_compute.h
    preview/focus_peaking.cpp
    preview/focus_peaking.h
    preview/zebra_stripes.cpp
    preview/zebra_stripes.h
    mtb/create_mtb.cpp
    mtb/create_mtb.h
    mtb/align_mtb.cpp
    mtb/align_mtb.h
    mtb/process_avg.cpp
    mtb/process_avg.h
    hdr/process_hdr.cpp
    hdr/process_hdr.h
    hdr/histogram_adjust.cpp
    hdr/histogram_adjust.h
    panorama/feature_detector.cpp
    panorama/feature_detector.h
    panorama/pyramid_blending.cpp
    panorama/pyramid_blending.h
)

add_library(
    openkamera_native
    SHARED
    ${NATIVE_SOURCES}
)

# Find Android NDK libraries
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    openkamera_native
    ${log-lib}
    ${jnigraphics-lib}
)
