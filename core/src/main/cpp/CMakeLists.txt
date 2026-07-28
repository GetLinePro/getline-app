cmake_minimum_required(VERSION 3.22.1)

execute_process(
  COMMAND git rev-parse --short=7 HEAD
  WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../../foss/golang/clash"
  OUTPUT_VARIABLE MIHOMO_COMMIT
  OUTPUT_STRIP_TRAILING_WHITESPACE
  RESULT_VARIABLE GIT_RESULT
)
if (NOT GIT_RESULT EQUAL 0 OR MIHOMO_COMMIT STREQUAL "")
  message(FATAL_ERROR "Unable to determine the pinned Mihomo commit")
endif ()

set(GIT_VERSION "mihomo_${MIHOMO_COMMIT}")
message(STATUS "version info = ${GIT_VERSION}")

file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/generated")
configure_file(
  "${CMAKE_CURRENT_SOURCE_DIR}/version.h.in"
  "${CMAKE_CURRENT_BINARY_DIR}/generated/getline_native_version.h"
  @ONLY
)

project(clash-bridge C)


set(CMAKE_POSITION_INDEPENDENT_CODE on)
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3")

set(GO_OUTPUT_BASE ${GO_OUTPUT}/${FLAVOR_NAME})

if ("${CMAKE_BUILD_TYPE}" STREQUAL "Debug")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Debug")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "Release")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
elseif ("${CMAKE_BUILD_TYPE}" STREQUAL "RelWithDebInfo")
    set(GO_OUTPUT_BASE "${GO_OUTPUT_BASE}Release")
else ()
    message(FATAL_ERROR "Unknown build type ${CMAKE_BUILD_TYPE}")
endif ()

include_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")
include_directories("${GO_SOURCE}")

link_directories("${GO_OUTPUT_BASE}/${CMAKE_ANDROID_ARCH_ABI}")

add_library(bridge SHARED main.c jni_helper.c bridge_helper.c)
target_include_directories(
  bridge BEFORE PRIVATE
  "${CMAKE_CURRENT_BINARY_DIR}/generated"
)
get_filename_component(CORE_SOURCE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../.." ABSOLUTE)
target_compile_options(
  bridge PRIVATE
  "-ffile-prefix-map=${CORE_SOURCE_ROOT}=."
  "-fdebug-prefix-map=${CORE_SOURCE_ROOT}=."
  "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=."
  "-fdebug-prefix-map=${CMAKE_SOURCE_DIR}=."
  "-ffile-prefix-map=${CMAKE_BINARY_DIR}=."
  "-fdebug-prefix-map=${CMAKE_BINARY_DIR}=."
)
target_link_libraries(bridge log clash)
