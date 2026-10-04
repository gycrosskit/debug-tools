# GY CrossKit Debug Tools

Android/iOS 的 Bug 草稿、提交状态、安全 Token 存储、本机历史、摇动触发、页面轨迹、有界证据格式化和禅道 REST 协议。复用现有
`com.dgtang.debugtools.bugreport` API；不依赖宿主品牌、导航、业务模块或 Compose UI。

## 支持与安装

| 项目 | 范围 |
| --- | --- |
| 平台 | Android（minSdk 24）、iOS Arm64、Simulator Arm64、x64 KLIB |
| 工具链 | Kotlin 2.2.21、AGP 8.10.1、Gradle 8.11.1、JVM 11；Gradle JDK 17+ |
| 依赖 | lifecycle-viewmodel 2.10.0、coroutines 1.10.2、serialization-json 1.9.0、Ktor 3.3.3 |
| 产物 | Android Release AAR 与 KMP/iOS KLIB；不单独提供 Swift Package/XCFramework |

固定发布坐标为 `com.github.gycrosskit.debug-tools:debug-tools:0.1.2`。本版已完成 JitPack 构建、Release 下载 SHA 核验及独立工程真正远程 Android/iOS 三架构编译、Simulator Framework 链接验收。
0.1.0 保留为首轮发布记录；其根坐标为聚合 POM，sources/metadata 变体存在 URL 改写，消费方使用 0.1.1。
使用 `maven("https://jitpack.io")` 和以下依赖：

```kotlin
implementation("com.github.gycrosskit.debug-tools:debug-tools:0.1.2")
```

## 最小接入

```kotlin
import com.dgtang.debugtools.bugreport.BugReportRepository
import com.dgtang.debugtools.bugreport.BugReportViewModel
import com.dgtang.debugtools.bugreport.ZentaoBugClient

// engineClient 是宿主提供的专用 TLS HttpClient；target、store、hostDataSource 同样由宿主注入。
// model 由宿主 ViewModelStore/Koin 管理。
val repository = BugReportRepository(ZentaoBugClient(engineClient, target), store)
val model = BugReportViewModel(repository, hostDataSource)
// UI 观察 model.state，并调用 openForm、updateTitle、submit 等操作。
```

宿主负责 `BugReportTarget` 的 HTTPS 地址、产品/分支、账号授权 UI、专用 TLS Engine、安全存储命名空间、
证据采集与隐私门禁、摇动开关与导航、表单 UI、文案本地化和 ViewModel 生命周期。未配置使用
`BugReportTarget.Unavailable`。账号密码不落盘；历史保存失败仍保留远端提交成功结果。HTTP 重定向关闭，
不得把业务客户端或日志输出中的凭据无意带入禅道授权链路。

`BugEvidenceFormatter` 只限长，不脱敏；传入日志和上下文会按现有契约进入 Bug 正文。宿主必须决定用户授权、
可信目标和脱敏范围；表单状态不暴露自动证据。组件不在公开反馈中接收真实日志或凭据。
Android Release 必须由宿主依赖配置排除开发工具；iOS 同一 Framework 包含此代码时，正式环境通过宿主 DI/准入禁用，不创建或启动 detector。
OpenHarmony 不在本组件范围。

## 原生安全存储与摇动触发（待发布）

以下新增 API 尚未包含在已发布的 0.1.1 中；验证和新版发布完成后才能通过新版固定坐标接入。
现有 `BugReportStore` 契约保持不变。Android 使用 Keystore AES/GCM 和私有 SharedPreferences，
iOS 使用 Keychain 和 NSUserDefaults。迁移时传入原标识，不复制 Token 到新空间：

```kotlin
// Android debug/QA Source Set；Json 沿用宿主配置。
val store = AndroidBugReportStore(
    context = context,
    json = json,
    preferencesName = "debug_bug_reporting",
    keyAlias = "debug_zentao_token",
    tokenKey = "zentao_token",
    shakeEnabledKey = "shake_enabled",
    historyKey = "submission_history",
)
val shakeDetector = AndroidShakeDetector(context)
```

```kotlin
// iOS；默认 NSUserDefaults.standardUserDefaults，也可注入原有 suite。
val store = IosBugReportStore(
    json = json,
    keychainService = "com.dgtang.live.debug.zentao",
    keychainAccount = "personal-token",
    preferencesNamespace = "debug_bug_reporting",
    shakeEnabledKey = "debug_bug_reporting_shake_enabled",
    historyKey = "debug_bug_reporting_history",
)
val shakeDetector = IosShakeDetector()
```

Android 沿用 Base64（NO_WRAP）的 `12 字节 IV + AES/GCM 密文`，128 位认证标签；alias 不变即可读取旧 Token。
iOS 沿用 Generic Password 的 service/account 和 UTF-8 数据。历史仍为原 `BugSubmissionRecord` JSON。
缺少摇动开关时默认开启；缺少或无法解码历史时返回空列表。坏 Android 密文、坏 iOS UTF-8 只清凭据，
不会清历史、摇动设置或删除 Android Keystore alias。iOS 系统暂时不能读取 Keychain 时返回空 Token，不删除记录。

在宿主现有主线程生命周期中操作 detector，并沿用当前事件收集 scope：

```kotlin
// 已有生命周期协程中读取 store；availability 和导航仍由宿主维护。
if (bugReportAvailable && store.readShakeEnabled()) {
    when (shakeDetector.start()) {
        ShakeStartResult.STARTED -> Unit
        ShakeStartResult.SENSOR_UNAVAILABLE -> showSensorUnavailable()
        ShakeStartResult.REGISTRATION_FAILED -> showSensorRegistrationFailed()
        ShakeStartResult.CLOSED -> Unit
    }
} else {
    shakeDetector.stop()
}
// 已有生命周期收集器：shakeDetector.shakes.collect { openBugReport() }
// 开关变化：允许且开启则 start()，否则 stop()；宿主仍通过 repository 持久化开关。
// 生命周期结束：shakeDetector.close()
```

两端保留 2.7g 阈值、1200ms 冷却；Android 使用 SENSOR_DELAY_UI，iOS 每 0.1s 在主队列接收加速度。
`start`/`stop`/`close` 幂等，停用不重置冷却，关闭后 `start` 返回 CLOSED。
事件为无 replay、额外容量 1 的 Flow，不创建协程或常驻任务；没有收集者时事件不会稍后补发。
剪贴板、分享、重载、品牌状态、表单与导航仍在宿主或各自组件，不由摇动机制接管。

本次本地验证：Android 21 tests 通过；iOS Simulator 22 tests 通过、2 个 Keychain 测试显式跳过；
Android/Simulator/iOS Arm64 编译通过。Gradle Native 独立 Simulator 测试进程的 SecItemAdd 返回
`-25291 (errSecNotAvailable)`，所以 Token round-trip 和坏 Token 清理尚未通过真实 Keychain 验收。
已实跑的 iOS 存储测试覆盖原 NSUserDefaults 键、历史 JSON、默认开关以及 clearCredentials 不修改普通偏好。
Android Keystore 旧密文/坏密文、Keychain 升级、真实摇动和禁用/销毁注销仍需平台验收，
未将编译或无传感器环境的结果视为设备验收。具体日志、跳过测试启用步骤见 [开发与验证](docs/开发与验证.md)。

## 开发与验证

构建验收、产物校验与命令见 [开发与验证](docs/开发与验证.md)。

```bash
bash gradlew testDebugUnitTest iosSimulatorArm64Test publishAllPublicationsToStagingRepository
```

staging 位于 `build/maven/<版本>`，不使用 `mavenLocal`。`verification-consumer` 只声明固定 Maven 坐标与一个公开 API
消费样例；验证时通过仓库外的临时 Gradle init script，为此组件的精确 Maven modules 注入 staging file 仓库。
在根目录运行：

```bash
bash gradlew -p verification-consumer -PdebugToolsVersion=0.1.2 \
  compileDebugKotlinAndroid compileKotlinIosArm64 compileKotlinIosX64 linkDebugFrameworkIosSimulatorArm64
```

该入口默认消费 JitPack 精确版本；本地编译/链接不代表远程发布或设备业务验收。

macOS 本地归档准备使用现有 GY CrossKit 的 Release Maven 归档方案：

```bash
bash scripts/export-maven.sh
```

输出 `build/release/debug-tools-maven.tar.gz` 和 `SHA256SUMS`。`jitpack.yml` 只运行
`jitpack-install.sh`，从固定版本 GitHub Release 下载归档并校验仓库内 SHA-256，再安装全部
Android/iOS Maven 变体；Linux 不现场生成 iOS KLIB。0.1.2 的远程构建、下载、编译和 Simulator 链接均已通过，
结果见 [开发与验证](docs/开发与验证.md)。归档前已按组织共用模板移除 JitPack 改写出错误 URL/hash 的 sources 与
Native metadata 变体并重算校验文件，保留 common metadata、Android AAR 和 iOS KLIB API/runtime 变体；源码仍可从 Git 标签读取。

组件由既有宿主的独立 Debug Tools 模块迁入，保留其 API 与行为契约；源码权利人已授权以
[Apache-2.0](LICENSE) 发布。第三方依赖继续遵循各自许可证。
源码与发布入口：[GitHub](https://github.com/gycrosskit/debug-tools)、[Release](https://github.com/gycrosskit/debug-tools/releases)、
[Issues](https://github.com/gycrosskit/debug-tools/issues)。反馈只提交脱敏复现信息，不上传凭据或真实日志。
