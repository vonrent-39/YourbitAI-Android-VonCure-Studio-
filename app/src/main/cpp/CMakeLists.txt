cmake_minimum_required(VERSION 3.22)
project(youraicqa_llama)

# llama.cpp must be at app/src/main/cpp/llama.cpp (CI clones it; or add it as a git submodule).
# Pin the version via LLAMA_COMMIT in CI; llama_bridge.cpp must match that version's public C API.
# Only the CPU backend is built; one local model is loaded at a time.

# Always build native code as Release: Gradle's Debug build ran ggml at -O0 (10-30x slower).
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)
if(NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp не найден в ${LLAMA_DIR}.\n"
        "Добавь его как git-сабмодуль перед сборкой:\n"
        "  git submodule add https://github.com/ggml-org/llama.cpp app/src/main/cpp/llama.cpp\n"
        "  git submodule update --init --recursive")
endif()

# armeabi-v7a: ggml-cpu NEON fp16 intrinsics fail to build on 32-bit ARM, so skip the module.
# LlamaNative.isAvailable is then false.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    message(WARNING
        "youraicqa_llama: пропускаю сборку под armeabi-v7a — у ggml-cpu известная проблема "
        "с ARM NEON fp16-интринсиками на 32-битном ARM. Локальный GGUF-инференс не будет "
        "доступен на устройствах с этим ABI, остальное приложение продолжит работать штатно.")
    return()
endif()

# Client-only build: no curl downloads, examples, tests or server.
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

# CPU backend only; Vulkan (GGML_VULKAN) is a separate step and grows build time and APK size.
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

# Optional: build ggml-cpu with dotprod (ARMv8.2+, set -DYOURAICQA_ARM_DOTPROD=ON); SIGILL on older cores.
option(YOURAICQA_ARM_DOTPROD "ARMv8.2+dotprod для ggml-cpu" OFF)
if(YOURAICQA_ARM_DOTPROD AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
endif()

add_subdirectory(${LLAMA_DIR} llama_build)

add_library(youraicqa_llama SHARED llama_bridge.cpp)

target_include_directories(youraicqa_llama PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include
)

# Target names "llama"/"ggml" come from llama.cpp's own CMake; they changed across versions.
target_link_libraries(youraicqa_llama
    PRIVATE
    llama
    ggml
    log
    android
)
