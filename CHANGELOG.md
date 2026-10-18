# 更新日志

## 0.2.0-rc.7（准备中，2026-10-18）

- Android/iOS Store 拒绝相同物理键的配置，避免覆盖 journal；保留 OHOS 已有判重。
- iOS 构造失败通过 `@Throws` 交给调用方，接入须处理配置异常；移除未使用的 Kuikly Request 字段。
- 仅准备 Maven 候选，HAR 继续 0.2.0-rc.3；归档、完整 CI 和精确公开消费待该 RC 证据，真实设备、账号及宿主业务不由源码测试代验。
