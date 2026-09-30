#!/usr/bin/env bash
set -euo pipefail

# 从同版本不可变 Release 安装 macOS 生成的完整 AAR/KLIB，不在 Linux 重建 iOS。
version=0.1.0
[[ "${VERSION:?JitPack must provide VERSION}" == "$version" ]] || { echo "Unsupported version" >&2; exit 1; }
archive="debug-tools-maven-${version}.tar.gz"
curl -fL --retry 3 -o "$archive" "https://github.com/gycrosskit/debug-tools/releases/download/${version}/${archive}"
sha256sum -c SHA256SUMS
mkdir -p "$HOME/.m2/repository" build/release-maven
tar -xzf "$archive" -C "$HOME/.m2/repository"
tar -xzf "$archive" -C build/release-maven
# 完整保留当前有效 metadata；远程验收若复现 JitPack URL 改写，再按共用模板定向修正。
