# GY CrossKit 鸿蒙 Debug Tools

配套包：`ohpm install @gycrosskit/debug-tools-native@0.2.0-rc.3`。Maven 与 HAR 须使用同版；OHPM 审核状态和 Release HAR 渠道见仓库根 README 的验收记录。

将 `GycDebugToolsModule.MODULE_NAME` 注册到 Kuikly renderer；每页持有对应 Module。宿主声明 `ohos.permission.ACCELEROMETER`，在允许调试工具的生命周期内启动/停止摇动。

Token 使用持久 HUKS AES-256-GCM，独立随机 nonce，prefs 只存密文。草稿/待提交记录只保存用户输入与文件标识；文件内容和自动诊断证据由宿主持有。目录、alias 和键名须按品牌/产品隔离，升级继续注入相同值。

`configure` 完成后才读取 Store；`startShake` 返回真实传感器注册结果。Kotlin 桥等待异步原生回执，不能把排队当作 STARTED。销毁停止传感器并撤销回调，取消不等于已完成落盘被撤销。

最低接口要求 API12；本次与宿主配套声明 compatible/target API22，实际本机 SDK 为 API26。编译与设备 HUKS/传感器验收分开记录。
