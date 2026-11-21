# Runs the native engine on your development machine (no Android, no emulator).
# This is how the engine was validated: real GGUF, real tokens, real cancellation.
#
#   ./scripts/fetch_llama.sh
#   cmake -B build -S scripts/host_test -DCMAKE_BUILD_TYPE=Release
#   cmake --build build -j
#   ./build/omni_host_test /path/to/model-Q4_K_M.gguf
cmake_minimum_required(VERSION 3.14)
project(omni_host_test CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(OMNI_ROOT "${CMAKE_CURRENT_LIST_DIR}/../..")
set(LLAMA_DIR "${OMNI_ROOT}/core/src/main/cpp/llama.cpp")

if(NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Run scripts/fetch_llama.sh first — llama.cpp is not vendored in the archive.")
endif()

set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          ON  CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} llama-build)

add_executable(omni_host_test
        host_test.cpp
        ${OMNI_ROOT}/core/src/main/cpp/omni_engine.cpp)

target_include_directories(omni_host_test PRIVATE
        ${OMNI_ROOT}/core/src/main/cpp
        ${LLAMA_DIR}/include
        ${LLAMA_DIR}/ggml/include)

target_link_libraries(omni_host_test PRIVATE llama ggml)
find_package(Threads REQUIRED)
target_link_libraries(omni_host_test PRIVATE Threads::Threads)
