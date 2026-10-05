package com.dgtang.debugtools.bugreport

import kotlinx.serialization.Serializable
import kotlin.random.Random

/** 调试包提交 Bug 时使用的产品目标；未开通必须显式使用 [Unavailable]。 */
sealed interface BugReportTarget {
    /** 当前品牌/环境不支持提交，所有网络写入应在调用前被拒绝。 */
    data object Unavailable : BugReportTarget

    /**
     * 宿主提供的固定目标；不是自动发现或账号资料。
     * @property tokenUrl HTTPS 授权入口，TLS 特例只能留在专用 Engine。
     * @property apiBaseUrl HTTPS REST 根地址，不含尾随路径资源；用于绑定离线队列目标。
     * @property webBaseUrl 生成 Bug 浏览链接的网页根地址。
     * @property productId 已核验的正产品 ID。
     * @property branchId 已核验的正分支 ID。
     * @property openedBuild 禅道版本标识，按原文提交且用于队列目标匹配。
     * @property attachmentsEnabled 仅 REST v2 22.0+ 且已核验文件权限时启用。
     */
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

/** 仅表示静态配置存在，不代表服务可达或当前账号有权限。 */
val BugReportTarget.available: Boolean
    get() = this is BugReportTarget.Configured

/** 宿主提交时的实际平台，不从设备字符串推断。 */
enum class BugReportPlatform { ANDROID, IOS, OHOS }

/** 用户声明的影响范围，COMMON 不代表组件已完成全平台验收。 */
enum class BugReportScope { COMMON, PLATFORM }

/**
 * 用户输入草稿，不保存账号、Token 或自动诊断证据；宿主保留 reportId 才能防止重复提交。
 * @property title 标题，Repository 上限 120 UTF-16 字符，提交时 trim 且不得为空白。
 * @property steps 复现步骤，最多 4000 字符。
 * @property actualResult 实际结果，最多 4000 字符。
 * @property expectedResult 期望结果，最多 4000 字符。
 * @property scope 用户选择的共用/平台影响范围。
 * @property severity 禅道严重程度，提交时限制到 1..4。
 * @property priority 禅道优先级，提交时限制到 1..4。
 * @property attachments 最多 3 个唯一 id 的附件描述，不含文件内容。
 * @property reportId 稳定本机草稿/待提交标识，非空白且最多 128 字符；修改正文不能更换它绕过 UNKNOWN。
 */
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

/**
 * 每次提交时由宿主捕获的上下文，不进入持久化离线队列。
 * @property brand 宿主品牌名。
 * @property platform 实际运行平台。
 * @property version 用户可见版本及构建信息。
 * @property environment 当前环境名。
 * @property device 宿主授权的设备描述，提交时最多 300 字符。
 * @property userId 业务用户 ID，非正数显示未登录；宿主决定隐私准入。
 */
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
 * @property recentLogs 保留原文并限长的日志（含 Ktor Header/Body）；本库不脱敏，宿主须先授权和筛选。
 */
data class BugEvidenceSnapshot(
    val pagePaths: List<String> = emptyList(),
    val recentLogs: String = "",
)

/**
 * 本机历史索引，不作为服务端创建授权。
 * @property id 已确认创建的正 Bug ID。
 * @property title 提交标题。
 * @property submittedAtMillis 确认时的 Unix 毫秒。
 * @property url 目标网页链接，打开方式由宿主负责。
 */
@Serializable
data class BugSubmissionRecord(
    val id: Int,
    val title: String,
    val submittedAtMillis: Long,
    val url: String,
)

/**
 * @property tokenConfigured 安全 Store 中存在非空 Token，不代表权限/有效期已验证。
 * @property shakeEnabled 用户开关，缺失时默认 true；平台准入仍由宿主决定。
 */
data class BugReportSettings(
    val tokenConfigured: Boolean = false,
    val shakeEnabled: Boolean = true,
)

/**
 * 远端已知创建结果；本机落盘失败不能改报提交失败后重复 POST。
 * @property id 已创建 Bug 的正 ID。
 * @property url 目标浏览链接。
 * @property historySaved 本机索引是否成功保存。
 * @property failedAttachments 尚未确认上传的附件，含明确失败或结果未知。
 * @property workspaceSaved 防重/恢复日志是否完整保存；false 时先核查服务端。
 * @property attachmentNotice 附件错误分类，无错误/未分类为 null。
 * @property reportId 关联的本机草稿标识，协议层直接结果可能为空。
 */
data class BugSubmissionResult(
    val id: Int,
    val url: String,
    val historySaved: Boolean = true,
    val failedAttachments: List<BugReportAttachment> = emptyList(),
    val workspaceSaved: Boolean = true,
    val attachmentNotice: BugReportNotice? = null,
    val reportId: String = "",
)

/**
 * 附件描述不保存内容，也不采集截图或读取任意路径；宿主负责授权、读取与释放原件。
 * @property id 稳定授权文件标识（非空白，最多 2048 字符），不是由本库解析的路径。
 * @property fileName 上传文件名（最多 200 字符），不得含换行、引号、斜杠。
 * @property contentType 合法 type/subtype MIME，仅允许字母/数字及 .+-。
 * @property size 真实文件字节数（1..4 MiB），读取后须与之相等。
 */
@Serializable
data class BugReportAttachment(val id: String, val fileName: String, val contentType: String, val size: Long) {
    init {
        require(id.isNotBlank() && id.length <= 2_048)
        require(fileName.isNotBlank() && fileName.length <= 200 && fileName.none { it == '\r' || it == '\n' || it == '"' || it == '/' || it == '\\' })
        require(contentType.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")))
        require(size in 1..MAX_ATTACHMENT_BYTES)
    }
    companion object {
        /** 单附件内容上限，单位字节；不含 multipart 编码开销。 */
        const val MAX_ATTACHMENT_BYTES = 4L * 1024 * 1024
    }
}

/** UNKNOWN 必须先核查远端结果；CREATED 只能补传附件，不能再次创建 Bug。 */
@Serializable
enum class PendingBugStatus { READY, UNKNOWN, CREATED }

/**
 * 离线恢复日志，仅存用户输入/附件描述/确认状态，不存自动证据或凭据。
 * @property id 本机待提交标识，与 draft.reportId 对应。
 * @property destination REST 根地址/产品/分支/版本联合目标，不匹配时禁止发送。
 * @property draft 保持稳定 reportId 的用户输入。
 * @property status READY 可发送，UNKNOWN 须人工核查，CREATED 不得再次创建。
 * @property created 已确认的 Bug 索引，CREATED 必须存在。
 * @property uploadedAttachments 附件 id 到服务端正 fileId 的确认记录。
 * @property uncertainAttachments 可能已发送但缺 ACK 的附件 id；人工核查前不得重传。
 */
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
    /** 在所属页面 scope 后台读取有界原文；取消须传播，资源须在成功/失败后释放。 */
    suspend fun captureEvidence(): BugEvidenceSnapshot

    /** 提交时捕获当前品牌/环境/用户；宿主负责读取方式、线程及隐私准入。 */
    suspend fun captureContext(): BugReportContext
}
