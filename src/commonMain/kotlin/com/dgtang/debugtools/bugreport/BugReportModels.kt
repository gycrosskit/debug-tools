package com.dgtang.debugtools.bugreport

import kotlinx.serialization.Serializable

/** 调试包提交 Bug 时使用的产品目标；未开通必须显式使用 [Unavailable]。 */
sealed interface BugReportTarget {
    data object Unavailable : BugReportTarget

    data class Configured(
        val tokenUrl: String,
        val apiBaseUrl: String,
        val webBaseUrl: String,
        val productId: Int,
        val branchId: Int,
        val openedBuild: String,
    ) : BugReportTarget {
        init {
            require(tokenUrl.startsWith("https://")) { "Token API must use HTTPS" }
            require(apiBaseUrl.startsWith("https://")) { "Bug API must use HTTPS" }
            require(productId > 0 && branchId > 0) { "Product and branch must be positive" }
        }
    }
}

val BugReportTarget.available: Boolean
    get() = this is BugReportTarget.Configured

enum class BugReportPlatform { ANDROID, IOS }

enum class BugReportScope { COMMON, PLATFORM }

data class BugReportDraft(
    val title: String = "",
    val steps: String = "",
    val actualResult: String = "",
    val expectedResult: String = "",
    val scope: BugReportScope = BugReportScope.COMMON,
    val severity: Int = 3,
    val priority: Int = 3,
)

data class BugReportContext(
    val brand: String,
    val platform: BugReportPlatform,
    val version: String,
    val environment: String,
    val device: String,
    val userId: Int,
)

/**
 * 打开表单时在后台冻结的宿主诊断证据，不进入测试人员可见的表单状态。
 *
 * @property pagePaths 不含参数的页面路径。
 * @property recentLogs 保留原文并限长的最近应用日志，其中同时包含 Ktor Header 与 Body。
 */
data class BugEvidenceSnapshot(
    val pagePaths: List<String> = emptyList(),
    val recentLogs: String = "",
)

@Serializable
data class BugSubmissionRecord(
    val id: Int,
    val title: String,
    val submittedAtMillis: Long,
    val url: String,
)

data class BugReportSettings(
    val tokenConfigured: Boolean = false,
    val shakeEnabled: Boolean = true,
)

/** 远端创建结果；本机历史保存失败不能把已创建的 Bug 改报为提交失败。 */
data class BugSubmissionResult(
    val id: Int,
    val url: String,
    val historySaved: Boolean = true,
)

/** 宿主向可迁移状态机提供应用上下文；模块不感知品牌、导航或业务诊断类型。 */
interface BugReportHostDataSource {
    suspend fun captureEvidence(): BugEvidenceSnapshot

    suspend fun captureContext(): BugReportContext
}
