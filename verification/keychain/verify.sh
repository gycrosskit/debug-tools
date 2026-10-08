#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
# 真实 App-hosted Simulator 验证；Xcode 的 Simulator 打包会生成正确权限段。
# 手工 swiftc/codesign 包可能返回 errSecMissingEntitlement，不能替代此流程。
output="$PWD/build/keychain-xcode-review"
xcodebuild -project verification/keychain/KeychainReview.xcodeproj -scheme KeychainReview \
 -configuration Debug -sdk iphonesimulator -arch arm64 -derivedDataPath "$output" build
xcrun simctl install "${DEBUG_KEYCHAIN_SIMULATOR:-booted}" "$output/Build/Products/Debug-iphonesimulator/KeychainReview.app"
xcrun simctl launch --console --terminate-running-process "${DEBUG_KEYCHAIN_SIMULATOR:-booted}" io.github.gycrosskit.debug.keychain-review
