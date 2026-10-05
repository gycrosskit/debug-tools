package com.dgtang.debugtools.bugreport

import kotlinx.serialization.Serializable
import kotlin.random.Random

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
        /** 禅道 REST v2 files 从 22.0 起支持；宿主核验服务端版本后显式启用。 */
        val attachmentsEnabled: Boolean = false,
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

enum class BugReportPlatform { ANDROID, IOS, OHOS }

enum class BugReportScope { COMMON, PLATFORM }

@Serializable
data class BugReportDraft(
    val title: String = "",
    val steps: String = "",
    val actualResult: String = "",
    val expectedResult: String = "",
    val scope: BugReportScope = BugReportScope.COMMON,
    val severity: Int = 3,
    val priority: Int = 3,
    val attachments: List<BugReportAttachment> = emptyList(),
    val reportId: String = "${Random.nextLong().toULong().toString(16)}-${Random.nextLong().toULong().toString(16)}",
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
    val failedAttachments: List<BugReportAttachment> = emptyList(),
    val workspaceSaved: Boolean = true,
    val attachmentNotice: BugReportNotice? = null,
    val reportId: String = "",
)

/** id 是宿主持有的稳定文件标识；组件不持久化附件内容，也不采集截图或读取任意路径。 */
@Serializable
data class BugReportAttachment(val id: String, val fileName: String, val contentType: String, val size: Long) {
    init {
        require(id.isNotBlank() && id.length <= 2_048)
        require(fileName.isNotBlank() && fileName.length <= 200 && fileName.none { it == '\r' || it == '\n' || it == '"' || it == '/' || it == '\\' })
        require(contentType.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")))
        require(size in 1..MAX_ATTACHMENT_BYTES)
    }
    companion object { const val MAX_ATTACHMENT_BYTES = 4L * 1024 * 1024 }
}

/** UNKNOWN 必须先核查远端结果；CREATED 只能补传附件，不能再次创建 Bug。 */
@Serializable
enum class PendingBugStatus { READY, UNKNOWN, CREATED }

@Serializable
data class PendingBugReport(
    val id: String,
    val destination: String,
    val draft: BugReportDraft,
    val status: PendingBugStatus = PendingBugStatus.READY,
    val created: BugSubmissionRecord? = null,
    val uploadedAttachments: Map<String, Int> = emptyMap(),
    val uncertainAttachments: List<String> = emptyList(),
)

/** 宿主向可迁移状态机提供应用上下文；模块不感知品牌、导航或业务诊断类型。 */
interface BugReportHostDataSource {
    suspend fun captureEvidence(): BugEvidenceSnapshot

    suspend fun captureContext(): BugReportContext
}
