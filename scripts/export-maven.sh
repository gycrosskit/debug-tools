#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
version="${VERSION:-0.1.2}"
GROUP=com.github.gycrosskit.debug-tools VERSION="$version" bash gradlew --no-daemon publishAllPublicationsToStagingRepository
python3 scripts/jitpack-metadata.py "build/maven/$version"
mkdir -p build/release
python3 scripts/check-maven.py "build/maven/$version" com.github.gycrosskit.debug-tools "$version" debug-tools ios_arm64,ios_x64,ios_simulator_arm64
archive="debug-tools-maven.tar.gz"
COPYFILE_DISABLE=1 tar --no-xattrs -czf "build/release/$archive" -C "build/maven/$version" com
(cd build/release && shasum -a 256 "$archive") > SHA256SUMS
cp SHA256SUMS build/release/SHA256SUMS
