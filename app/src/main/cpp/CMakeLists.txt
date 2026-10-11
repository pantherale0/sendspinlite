# CMake build for the SendSpin Android JNI bridge.
#
# Builds sendspin-cpp with IXWebSocket for Android and links it into libsendspin_jni.so.
# Kotlin loads the bridge with System.loadLibrary.
#
# The host build uses POSIX sockets and standard threads under the NDK.
# FetchContent supplies ArduinoJson, micro-flac, micro-opus, and IXWebSocket.

cmake_minimum_required(VERSION 3.22.1)
project(sendspin_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Path to the sendspin-cpp submodule (repo-root/sendspin-cpp).
get_filename_component(SENDSPIN_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../sendspin-cpp" ABSOLUTE)

if(NOT EXISTS "${SENDSPIN_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "sendspin-cpp submodule not found at ${SENDSPIN_CPP_DIR}. "
        "Run: git submodule update --init --recursive")
endif()

# Enable player, metadata, and controller roles. Disable unused roles.
set(SENDSPIN_ENABLE_PLAYER ON CACHE BOOL "" FORCE)
set(SENDSPIN_ENABLE_METADATA ON CACHE BOOL "" FORCE)
set(SENDSPIN_ENABLE_CONTROLLER ON CACHE BOOL "" FORCE)
set(SENDSPIN_ENABLE_COLOR OFF CACHE BOOL "" FORCE)
set(SENDSPIN_ENABLE_ARTWORK OFF CACHE BOOL "" FORCE)
set(SENDSPIN_ENABLE_VISUALIZER OFF CACHE BOOL "" FORCE)

# Exclude host examples and native tests from the app build.
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SENDSPIN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(ENABLE_WERROR OFF CACHE BOOL "" FORCE)
set(ENABLE_SANITIZERS OFF CACHE BOOL "" FORCE)

# Cleartext ws:// transport needs neither TLS nor zlib.
set(USE_TLS OFF CACHE BOOL "" FORCE)
set(USE_ZLIB OFF CACHE BOOL "" FORCE)

# Android 15+ 16 KB page-size devices require 16 KB ELF alignment for native libraries.
# NDK r28+ defaults to this; keep explicit flags for NDK r27 and static deps linked into the .so.
if(ANDROID)
    set(_SENDSPIN_PAGE_SIZE_LDFLAGS "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
    add_link_options(${_SENDSPIN_PAGE_SIZE_LDFLAGS})
endif()

add_subdirectory(${SENDSPIN_CPP_DIR} ${CMAKE_CURRENT_BINARY_DIR}/sendspin-cpp)

add_library(sendspin_jni SHARED
    jni_onload.cpp
    sendspin_client_handle.cpp
    android_player_listener.cpp
    android_metadata_listener.cpp
    android_client_listener.cpp
    android_controller_listener.cpp
    android_network_provider.cpp
)

target_compile_features(sendspin_jni PRIVATE cxx_std_20)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(sendspin_jni
    PRIVATE
    sendspin
    ${log-lib}
    ${android-lib}
)

if(ANDROID)
    target_link_options(sendspin_jni PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
    )
endif()
