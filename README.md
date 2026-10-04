# GY CrossKit Debug Tools

Android/iOS 的 Bug 草稿、提交状态、本机历史、页面轨迹、有界证据格式化和禅道 REST 协议。复用现有
`com.dgtang.debugtools.bugreport` API；不依赖宿主品牌、导航、业务模块或 Compose UI。

## 支持与安装

| 项目 | 范围 |
| --- | --- |
| 平台 | Android（minSdk 24）、iOS Arm64、Simulator Arm64、x64 KLIB |
| 工具链 | Kotlin 2.2.21、AGP 8.10.1、Gradle 8.11.1、JVM 11；Gradle JDK 17+ |
| 依赖 | lifecycle-viewmodel 2.10.0、coroutines 1.10.2、serialization-json 1.9.0、Ktor 3.3.3 |
| 产物 | Android Release AAR 与 KMP/iOS KLIB；不单独提供 Swift Package/XCFramework |

固定发布坐标为 `com.github.gycrosskit.debug-tools:debug-tools:0.1.1`。**0.1.0 已保留为首轮发布，JitPack 将其根坐标生成为聚合 POM，sources/metadata 变体也存在 URL 改写。
0.1.1 使用上述实际 KMP 模块坐标并定向修正已证实的坏变体；JitPack 构建及
远程 Android/iOS 消费验收尚未完成；发布状态以 [Release](https://github.com/gycrosskit/debug-tools/releases) 和验收记录为准。**
远程发布验收完成后消费方使用 `maven("https://jitpack.io")` 和以下依赖：

```kotlin
implementation("com.github.gycrosskit.debug-tools:debug-tools:0.1.1")
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

宿主负责 `BugReportTarget` 的 HTTPS 地址、产品/分支、账号授权 UI、专用 TLS Engine、安全 Token Store、
证据采集与隐私门禁、摇一摇传感器、表单 UI、文案本地化和 ViewModel 生命周期。未配置使用
`BugReportTarget.Unavailable`。账号密码不落盘；历史保存失败仍保留远端提交成功结果。HTTP 重定向关闭，
不得把业务客户端或日志输出中的凭据无意带入禅道授权链路。

`BugEvidenceFormatter` 只限长，不脱敏；传入日志和上下文会按现有契约进入 Bug 正文。宿主必须决定用户授权、
可信目标和脱敏范围；表单状态不暴露自动证据。组件不在公开反馈中接收真实日志或凭据。
Android Release 是否排除开发工具由宿主依赖配置决定；iOS 是否包含此代码由宿主 Framework 变体决定。
OpenHarmony 不在本组件范围。

## 开发与验证

构建验收、产物校验与命令见 [开发与验证](docs/开发与验证.md)。

```bash
bash gradlew testDebugUnitTest iosSimulatorArm64Test publishAllPublicationsToStagingRepository
```

staging 位于 `build/maven/<版本>`，不使用 `mavenLocal`。`verification-consumer` 只声明固定 Maven 坐标与一个公开 API
消费样例；验证时通过仓库外的临时 Gradle init script，为此组件的精确 Maven modules 注入 staging file 仓库。
在根目录运行：

```bash
bash gradlew -p verification-consumer -I <临时脚本> \
  compileDebugKotlinAndroid compileKotlinIosArm64 compileKotlinIosX64 linkDebugFrameworkIosSimulatorArm64
```

远程发布后移除临时脚本，重新验证 JitPack 下载；本地编译/链接不代表远程发布或设备业务验收。

macOS 本地归档准备使用现有 GY CrossKit 的 Release Maven 归档方案：

```bash
bash scripts/export-maven.sh
```

输出 `build/release/debug-tools-maven-0.1.1.tar.gz` 和 `SHA256SUMS`。`jitpack.yml` 只运行
`jitpack-install.sh`，从固定版本 GitHub Release 下载归档并校验仓库内 SHA-256，再安装全部
Android/iOS Maven 变体；Linux 不现场生成 iOS KLIB。首次远程构建和消费仍在验收中，当前不宣称远程安装完成。
已按组织共用模板移除 JitPack 改写出错误 URL/hash 的 sources 与 Native metadata 变体，保留 common metadata、Android AAR 和 iOS KLIB API/runtime 变体；源码仍可从 Git 标签读取。

组件由既有宿主的独立 Debug Tools 模块迁入，保留其 API 与行为契约；源码权利人已授权以
[Apache-2.0](LICENSE) 发布。第三方依赖继续遵循各自许可证。
源码与发布入口：[GitHub](https://github.com/gycrosskit/debug-tools)、[Release](https://github.com/gycrosskit/debug-tools/releases)、
[Issues](https://github.com/gycrosskit/debug-tools/issues)。反馈只提交脱敏复现信息，不上传凭据或真实日志。
