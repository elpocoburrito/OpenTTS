cmake_minimum_required(VERSION 3.22.1)

project(openttsnative VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)

# ---------------------------------------------------------------------------
# espeak-ng (vendored, GPL-3.0). Only the library is built: no player, no
# audio output, no async thread. We use it purely as a text -> IPA phonemizer,
# which is what Kokoro, Piper and Matcha all consume.
# ---------------------------------------------------------------------------
set(ESPEAK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/espeak-ng)

set(BUILD_SHARED_LIBS OFF)
set(BUILD_TESTING OFF)
set(HAVE_PTHREAD ON)
set(USE_MBROLA OFF)
set(USE_LIBSONIC OFF)
set(USE_LIBPCAUDIO OFF)
set(USE_SPEECHPLAYER OFF)
set(USE_ASYNC OFF)
set(USE_KLATT OFF)
set(ESPEAK_COMPAT OFF)

include(${ESPEAK_DIR}/cmake/config.cmake)

add_library(espeak-include INTERFACE)
target_include_directories(
  espeak-include INTERFACE
  ${ESPEAK_DIR}/src/include
  ${ESPEAK_DIR}/src/include/compat
)

add_subdirectory(${ESPEAK_DIR}/src/ucd-tools ${CMAKE_CURRENT_BINARY_DIR}/ucd-tools)
add_subdirectory(${ESPEAK_DIR}/src/libespeak-ng ${CMAKE_CURRENT_BINARY_DIR}/libespeak-ng)

# espeak-ng declares `espeak-include` (src/include + src/include/compat) as a
# PUBLIC dependency, so linking it would drag its hand-written <string.h> /
# <wctype.h> shims into our JNI translation unit and clash with bionic. Strip
# that inherited interface: the JNI bridge only needs the public API header.
set_target_properties(espeak-ng PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "")

# ---------------------------------------------------------------------------
# JNI bridge: Phonemizer.kt <-> libespeak-ng
# ---------------------------------------------------------------------------
add_library(opentts_tts SHARED phonemizer.cpp)

target_compile_options(opentts_tts PRIVATE -Wall -Wextra -fvisibility=hidden -O2)

# Note: deliberately *not* linking the `espeak-include` interface target — it
# also exposes src/include/compat, whose <wctype.h>/<string.h> shims shadow the
# bionic headers and pull in <ucd/ucd.h>. We only need the public API header.
target_include_directories(opentts_tts PRIVATE ${ESPEAK_DIR}/src/include)

target_link_libraries(opentts_tts PRIVATE espeak-ng log)
