cmake_minimum_required(VERSION 3.16)

project(cpp_detector LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(cpp_detector SHARED
    src/cpp_detector.cpp
    src/detector_backend_builtin.cpp
)

target_include_directories(cpp_detector PRIVATE src)

if(MSVC)
    target_compile_options(cpp_detector PRIVATE /W4 /EHsc)
else()
    target_compile_options(cpp_detector PRIVATE -Wall -Wextra -Wpedantic)
endif()

set_target_properties(cpp_detector PROPERTIES
    OUTPUT_NAME "cpp_detector"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/release"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/release"
    ARCHIVE_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/release"
)
