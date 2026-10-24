# 更新日志

## Unreleased

- Kuikly KMP 与宿主统一为 `2.29.1-2.0.21-ohos`，配套 iOS/OHOS Renderer 固定 `2.29.1`；同步配置与锁文件，后续随宿主升级。尚未发布，完整 SDK 与消费验证分别记录。

## 0.2.0-rc.7（准备中，2026-10-18）

- Android/iOS Store 拒绝相同物理键的配置，避免覆盖 journal；保留 OHOS 已有判重。
- iOS 构造失败通过 `@Throws` 交给调用方，接入须处理配置异常；移除未使用的 Kuikly Request 字段。
- 仅准备 Maven 候选，HAR 继续 0.2.0-rc.3；归档、完整 CI 和精确公开消费待该 RC 证据，真实设备、账号及宿主业务不由源码测试代验。
