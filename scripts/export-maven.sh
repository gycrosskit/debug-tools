#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
version="${VERSION:-0.2.0-rc.4}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
rm -rf "build/maven/$version"
GROUP=com.github.gycrosskit.debug-tools VERSION="$version" bash gradlew --no-daemon --max-workers=1 -Dorg.gradle.parallel=false publishAllPublicationsToStagingRepository
python3 scripts/jitpack-metadata.py "build/maven/$version"
mkdir -p build/release
python3 scripts/check-maven.py "build/maven/$version" com.github.gycrosskit.debug-tools "$version" debug-tools ios_arm64,ios_x64,ios_simulator_arm64,ohos_arm64 debug-tools,debug-tools-android,debug-tools-iosarm64,debug-tools-iosx64,debug-tools-iossimulatorarm64,debug-tools-jvm,debug-tools-ohosarm64,debug-tools-kuikly,debug-tools-kuikly-ohosarm64
archive="debug-tools-maven.tar.gz"
COPYFILE_DISABLE=1 tar --no-xattrs -czf "build/release/$archive" -C "build/maven/$version" com
(cd build/release && shasum -a 256 "$archive") > SHA256SUMS
cp SHA256SUMS build/release/SHA256SUMS
