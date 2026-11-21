#!/usr/bin/env bash
# Vulkan backend support files for ggml-vulkan.
#
# Why this exists: ggml-vulkan includes <vulkan/vulkan.hpp>, which the Android NDK does not ship,
# and Vulkan-Headers / SPIRV-Headers are git submodules that source archives omit. This script
# fetches the two tagged trees and writes a CMake package config for SPIRV-Headers.
#
#   ./scripts/fetch_vulkan_headers.sh
#   ./gradlew assembleDebug -Pomni.vulkan=true -Pomni.abis=arm64-v8a
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/third_party"
VK_TAG="v1.3.275"
mkdir -p "$DEST"

if [ ! -f "$DEST/vulkan-headers/include/vulkan/vulkan.hpp" ]; then
  echo "==> Vulkan-Headers $VK_TAG"
  rm -rf "$DEST/vulkan-headers"
  git clone --depth 1 --branch "$VK_TAG" https://github.com/KhronosGroup/Vulkan-Headers "$DEST/vulkan-headers"
fi

if [ ! -d "$DEST/spirv-headers/include/spirv" ]; then
  echo "==> SPIRV-Headers"
  rm -rf "$DEST/spirv-headers"
  git clone --depth 1 https://github.com/KhronosGroup/SPIRV-Headers "$DEST/spirv-headers"
fi

mkdir -p "$DEST/spirv-cfg"
cat > "$DEST/spirv-cfg/SPIRV-HeadersConfig.cmake" <<EOF
if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "$DEST/spirv-headers/include")
endif()
set(SPIRV-Headers_FOUND TRUE)
EOF

echo "==> done"
echo "    headers : $DEST/vulkan-headers/include"
echo "    spirv   : $DEST/spirv-headers/include"
echo "    cmake   : $DEST/spirv-cfg"
