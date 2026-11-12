# Builds libturkce_mt.so (Bergamot + our JNI layer) for the 32-bit TV. See derle.ps1.
cmake_minimum_required(VERSION 3.5.1)
cmake_policy(SET CMP0069 NEW)
set(CMAKE_POLICY_DEFAULT_CMP0069 NEW)

project(turkce_mt CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(BERGAMOT_DIR "" CACHE PATH "Path to the bergamot-translator checkout")

set(COMPILE_WASM OFF CACHE BOOL "" FORCE)
set(COMPILE_TESTS OFF CACHE BOOL "" FORCE)
set(COMPILE_UNIT_TESTS OFF CACHE BOOL "" FORCE)
set(COMPILE_LIBRARY_ONLY ON CACHE BOOL "" FORCE)
set(USE_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(USE_SENTENCEPIECE ON CACHE BOOL "" FORCE)
set(USE_MKL OFF CACHE BOOL "" FORCE)
set(ENABLE_CACHE_STATS OFF CACHE BOOL "" FORCE)
set(SSPLIT_COMPILE_LIBRARY_ONLY ON CACHE BOOL "" FORCE)
set(SSPLIT_USE_INTERNAL_PCRE2 ON CACHE BOOL "" FORCE)
set(USE_RUY ON CACHE BOOL "" FORCE)
set(USE_RUY_SGEMM ON CACHE BOOL "" FORCE)
set(INTGEMM_DONT_BUILD_TESTS ON)
set(COMPILE_WITHOUT_EXCEPTIONS ON)
set(AUTO_CPU_DETECT OFF CACHE BOOL "" FORCE)
set(USE_THREADS OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(BUILD_ARCH "armv8-a" CACHE STRING "" FORCE)
else()
  set(BUILD_ARCH "armv7-a" CACHE STRING "" FORCE)
endif()

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wno-builtin-macro-redefined -ffunction-sections -fdata-sections")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-builtin-macro-redefined -ffunction-sections -fdata-sections")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--gc-sections -Wl,--strip-all")

add_subdirectory(${BERGAMOT_DIR} bergamot-translator)

add_library(turkce_mt SHARED jni.cpp)
target_link_libraries(turkce_mt PRIVATE bergamot-translator log)
