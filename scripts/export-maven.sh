#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
version=0.1.0
GROUP=com.github.gycrosskit VERSION="$version" bash gradlew --no-daemon publishAllPublicationsToStagingRepository
mkdir -p build/release
archive="debug-tools-maven-${version}.tar.gz"
COPYFILE_DISABLE=1 tar -czf "build/release/$archive" -C build/maven com
(cd build/release && shasum -a 256 "$archive") > SHA256SUMS
cp SHA256SUMS build/release/SHA256SUMS
