Place OpenCV source here if you want a fully embedded offline build.

Expected layout:

`native/cpp_detector/third_party/opencv/CMakeLists.txt`

Build options:

- Default: system OpenCV if present, otherwise built-in backend
- Embedded source: if `third_party/opencv` exists, CMake uses it directly
- Downloaded bundle: run Gradle with `-PembedOpenCv=true` to let CMake fetch the configured OpenCV version
