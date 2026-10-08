#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
# 真实 App-hosted Simulator 验证；Xcode 的 Simulator 打包会生成正确权限段。
# 手工 swiftc/codesign 包可能返回 errSecMissingEntitlement，不能替代此流程。
# Always compile the current production Store and probe before launching the App.
init_script="$(mktemp)"
trap 'rm -f "$init_script"' EXIT
cat > "$init_script" <<'GRADLE'
allprojects { p ->
 p.afterEvaluate {
  if (p == rootProject) {
   def k = p.extensions.findByName('kotlin')
   k.sourceSets.getByName('iosMain').kotlin.srcDir(new File(p.projectDir, 'verification/keychain'))
   k.targets.getByName('iosSimulatorArm64').binaries.framework {
    baseName = 'DebugKeychainReview'
    isStatic = true
   }
  }
 }
}
GRADLE
bash gradlew --init-script "$init_script" linkDebugFrameworkIosSimulatorArm64 --no-daemon --max-workers=1
output="$PWD/build/keychain-xcode-review"
xcodebuild -project verification/keychain/KeychainReview.xcodeproj -scheme KeychainReview \
 -configuration Debug -sdk iphonesimulator -arch arm64 -derivedDataPath "$output" build
xcrun simctl install "${DEBUG_KEYCHAIN_SIMULATOR:-booted}" "$output/Build/Products/Debug-iphonesimulator/KeychainReview.app"
xcrun simctl launch --console --terminate-running-process "${DEBUG_KEYCHAIN_SIMULATOR:-booted}" io.github.gycrosskit.debug.keychain-review
