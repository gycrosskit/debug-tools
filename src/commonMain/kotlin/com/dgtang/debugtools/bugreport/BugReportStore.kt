package com.dgtang.debugtools.bugreport

/**
 * 宿主串行使用的原生 Store；实例/命名空间由宿主持有，不拥有页面 scope。
 * Token 使用安全存储，其他记录不含密码；suspend 不保证切线程，调用方遵循平台实现约束。
 * 平台没有可取消系统操作时取消只结束协程等待，不代表持久化操作撤销。
 */
interface BugReportStore {
    /** 读取安全 Token，未授权为空；系统暂不可用/坏数据行为见平台实现，不记录返回值。 */
    suspend fun readToken(): String
    /** 保存授权 Token；不接收账号密码，平台失败应报告，具体落盘保证见平台实现。 */
    suspend fun writeToken(value: String)
    /** 读取用户开关，缺失默认 true，不替代构建/隐私准入。 */
    suspend fun readShakeEnabled(): Boolean
    /** 保存用户开关；不负责启停传感器。 */
    suspend fun writeShakeEnabled(value: Boolean)
    /** 读取本机提交索引；原生实现的损坏历史返回空，不触发网络恢复。 */
    suspend fun readHistory(): List<BugSubmissionRecord>
    /** 替换本机索引列表；Repository 限制最新 20 条，Store 不自动提交。 */
    suspend fun writeHistory(value: List<BugSubmissionRecord>)
    /** 仅清 Token，不清用户开关、历史、草稿或待提交 journal。 */
    suspend fun clearCredentials()
}
