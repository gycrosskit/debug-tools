package com.dgtang.debugtools.bugreport

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 禅道 REST 协议实现；TLS 策略由专用平台 Engine 提供，禁止复用业务 HttpClient。 */
class ZentaoBugClient(
    engineClient: HttpClient,
    private val target: BugReportTarget,
) {
    val available: Boolean = target.available
    private val client = engineClient.config {
        followRedirects = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    /** 使用账号密码换取 Token；调用方只应持久化返回值，禁止保存账号密码。 */
    suspend fun authorize(account: String, password: String): String {
        val resolvedTarget = requireTarget()
        require(account.isNotBlank()) { "请输入禅道账号" }
        require(password.isNotBlank()) { "请输入禅道密码" }
        val httpResponse = client.post(resolvedTarget.tokenUrl) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("account", account.trim())
                put("password", password)
            })
        }
        val response = httpResponse.body<JsonObject>()
        requireSuccess(
            httpStatus = httpResponse.status,
            response = response,
            notAllowedMessage = "禅道账号授权失败，请检查账号和密码",
        )
        return response.stringValue("token")
            ?: (response["data"] as? JsonObject)?.stringValue("token")
            ?: error("禅道授权成功但未返回 Token")
    }

    suspend fun testConnection(token: String) {
        val resolvedTarget = requireTarget()
        requireToken(token)
        val httpResponse = client.get("${resolvedTarget.apiBaseUrl}/products/${resolvedTarget.productId}") {
            header(TOKEN_HEADER, token.trim())
        }
        requireSuccess(
            httpStatus = httpResponse.status,
            response = httpResponse.body(),
            notAllowedMessage = "当前禅道账号无权访问产品 ${resolvedTarget.productId}",
        )
    }

    suspend fun submit(
        token: String,
        draft: BugReportDraft,
        context: BugReportContext,
        evidence: BugEvidenceSnapshot,
    ): BugSubmissionResult {
        val resolvedTarget = requireTarget()
        requireToken(token)
        require(draft.title.isNotBlank()) { "Bug 标题不能为空" }
        val httpResponse = client.post("${resolvedTarget.apiBaseUrl}/bugs") {
            header(TOKEN_HEADER, token.trim())
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("productID", resolvedTarget.productId)
                put("branch", resolvedTarget.branchId)
                put("openedBuild", buildJsonArray { add(resolvedTarget.openedBuild) })
                put("title", draft.title.trim())
                put("severity", draft.severity.coerceIn(1, 4))
                put("pri", draft.priority.coerceIn(1, 4))
                put("type", "codeerror")
                put("steps", buildSteps(draft, context, evidence))
            })
        }
        val response = httpResponse.body<JsonObject>()
        requireSuccess(
            httpStatus = httpResponse.status,
            response = response,
            notAllowedMessage =
                "当前禅道账号没有产品 ${resolvedTarget.productId} / 分支 ${resolvedTarget.branchId} 的提 Bug 权限",
        )
        val bug = response["bug"] as? JsonObject
            ?: response["data"] as? JsonObject
            ?: response
        val id = bug["id"]?.jsonPrimitive?.intOrNull
            ?: error("禅道返回成功但缺少 Bug ID")
        return BugSubmissionResult(
            id = id,
            url = "${resolvedTarget.webBaseUrl}/bug-view-$id.html",
        )
    }

    private fun buildSteps(
        draft: BugReportDraft,
        context: BugReportContext,
        evidence: BugEvidenceSnapshot,
    ): String = buildString {
        appendLine("【复现步骤】")
        appendLine(draft.steps.trim().ifBlank { "未填写" })
        appendLine("\n【实际结果】")
        appendLine(draft.actualResult.trim().ifBlank { "未填写" })
        appendLine("\n【期望结果】")
        appendLine(draft.expectedResult.trim().ifBlank { "未填写" })
        appendLine("\n【自动上下文】")
        appendLine("品牌：${context.brand}")
        appendLine("平台：${context.platform.name}")
        appendLine("影响范围：${draft.scope.name}")
        appendLine("版本：${context.version}")
        appendLine("环境：${context.environment}")
        appendLine("设备：${BugEvidenceFormatter.bound(context.device, 300)}")
        appendLine("用户 ID：${context.userId.takeIf { it > 0 } ?: "未登录"}")
        appendLine("\n【页面路径】")
        appendLine(evidence.pagePaths.joinToString(" -> ").ifBlank { "未采集" })
        appendLine("\n【最近日志上报】")
        append(BugEvidenceFormatter.boundRecentLogs(evidence.recentLogs).ifBlank { "未采集" })
    }.take(MAX_STEPS_LENGTH)

    private fun requireSuccess(
        httpStatus: HttpStatusCode,
        response: JsonObject,
        notAllowedMessage: String,
    ) {
        val status = response["status"]?.jsonPrimitive?.content.orEmpty()
        if (httpStatus.isSuccess() && (status.isBlank() || status.equals("success", ignoreCase = true))) {
            return
        }
        val message = response["message"]?.jsonPrimitive?.content
            ?: response["error"]?.jsonPrimitive?.content
            ?: "禅道请求失败（HTTP ${httpStatus.value}）"
        if (
            httpStatus == HttpStatusCode.Unauthorized ||
            httpStatus == HttpStatusCode.Forbidden ||
            message.equals("Not allowed", ignoreCase = true)
        ) {
            error(notAllowedMessage)
        }
        error(message)
    }

    private fun requireToken(token: String) {
        require(token.isNotBlank()) { "请先完成禅道账号授权" }
    }

    private fun requireTarget(): BugReportTarget.Configured =
        target as? BugReportTarget.Configured ?: error("当前品牌尚未开通禅道 Bug 提交")

    private companion object {
        const val TOKEN_HEADER = "Token"
        const val MAX_STEPS_LENGTH = 30_000
    }
}

private fun JsonObject.stringValue(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
