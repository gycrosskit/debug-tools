#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
version=0.1.1
GROUP=com.github.gycrosskit.debug-tools VERSION="$version" bash gradlew --no-daemon publishAllPublicationsToStagingRepository
python3 scripts/jitpack-metadata.py "build/maven/$version"
mkdir -p build/release
archive="debug-tools-maven-${version}.tar.gz"
COPYFILE_DISABLE=1 tar -czf "build/release/$archive" -C "build/maven/$version" com
(cd build/release && shasum -a 256 "$archive") > SHA256SUMS
cp SHA256SUMS build/release/SHA256SUMS
