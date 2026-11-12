# Builds libturkce_chiaki.so (libchiaki + our JNI layer) for the 32-bit TV. See derle.ps1.
# Same hand-listed sources as ios/ChiakiCore, with Mbed TLS instead of OpenSSL (no perl needed
# on Windows) and no Opus (the TV gets the game's sound over HDMI, the probe needs video only).
cmake_minimum_required(VERSION 3.20)
project(turkce_chiaki C)

set(CHIAKI "" CACHE PATH "chiaki-ng checkout")
set(MBEDTLS "" CACHE PATH "mbedtls checkout")
set(CORE "${CMAKE_CURRENT_SOURCE_DIR}/core")

set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(GEN_FILES OFF CACHE BOOL "" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
set(DISABLE_PACKAGE_CONFIG_AND_INSTALL ON CACHE BOOL "" FORCE)
add_subdirectory("${MBEDTLS}" mbedtls)

set(CHIAKI_LIB_ENABLE_OPUS 0)
set(CHIAKI_LIB_ENABLE_PI_DECODER 0)
set(CHIAKI_LIB_ENABLE_FFMPEG_DECODER 0)
configure_file("${CHIAKI}/lib/config.h.in" "${CMAKE_CURRENT_BINARY_DIR}/include/chiaki/config.h")

file(GLOB CHIAKI_SOURCES "${CHIAKI}/lib/src/*.c")
list(FILTER CHIAKI_SOURCES EXCLUDE REGEX "(ffmpegdecoder|pidecoder)\\.c$")
list(APPEND CHIAKI_SOURCES
	"${CHIAKI}/lib/src/remote/rudp.c"
	"${CHIAKI}/lib/src/remote/rudpsendbuffer.c"
	"${CORE}/src/holepunch_stub.c"
	"${CORE}/generated/takion.pb.c")

set(GF "${CHIAKI}/third-party/gf-complete/src")
set(JE "${CHIAKI}/third-party/jerasure/src")
set(NP "${CHIAKI}/third-party/nanopb")

add_library(chiaki STATIC ${CHIAKI_SOURCES}
	${NP}/pb_common.c ${NP}/pb_decode.c ${NP}/pb_encode.c
	${GF}/gf.c ${GF}/gf_wgen.c ${GF}/gf_w4.c ${GF}/gf_w8.c ${GF}/gf_w16.c ${GF}/gf_w32.c
	${GF}/gf_w64.c ${GF}/gf_w128.c ${GF}/gf_rand.c ${GF}/gf_general.c ${GF}/gf_cpu.c
	${JE}/galois.c ${JE}/jerasure.c ${JE}/reed_sol.c ${JE}/cauchy.c ${JE}/liberation.c)
target_include_directories(chiaki PUBLIC
	"${CMAKE_CURRENT_BINARY_DIR}/include"
	"${CHIAKI}/lib/include"
	"${CORE}/generated"
	"${NP}"
	"${CHIAKI}/third-party/gf-complete/include"
	"${CHIAKI}/third-party/jerasure/include"
	"${MBEDTLS}/include")
target_compile_definitions(chiaki PUBLIC CHIAKI_LIB_ENABLE_MBEDTLS PB_C99_STATIC_ASSERT)
target_compile_options(chiaki PRIVATE -Wno-unused-function -Wno-unused-variable -Wno-deprecated-declarations
	-ffunction-sections -fdata-sections)

add_library(turkce_chiaki SHARED chiaki_jni.c)
target_link_libraries(turkce_chiaki PRIVATE chiaki mbedcrypto log)
target_link_options(turkce_chiaki PRIVATE -Wl,--gc-sections -Wl,--strip-all)
