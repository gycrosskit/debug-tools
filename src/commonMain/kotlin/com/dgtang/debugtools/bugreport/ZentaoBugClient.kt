package com.dgtang.debugtools.bugreport

import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
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
    private val reportLanguage: BugReportLanguage = BugReportLanguage.CHINESE,
) {
    val available: Boolean = target.available
    val attachmentsAvailable: Boolean = (target as? BugReportTarget.Configured)?.attachmentsEnabled == true
    internal val destination: String
        get() = requireTarget().let { "${it.apiBaseUrl}|${it.productId}|${it.branchId}|${it.openedBuild}" }
    private val client = engineClient.config {
        followRedirects = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    /** 使用账号密码换取 Token；调用方只应持久化返回值，禁止保存账号密码。 */
    suspend fun authorize(account: String, password: String): String {
        val resolvedTarget = requireTarget()
        if (account.isBlank()) throw BugReportException(BugReportNotice(BugReportMessage.ACCOUNT_REQUIRED))
        if (password.isBlank()) throw BugReportException(BugReportNotice(BugReportMessage.PASSWORD_REQUIRED))
        val httpResponse = client.post(resolvedTarget.tokenUrl) {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("account", account.trim())
                put("password", password)
            })
        }
        val response = responseBody(httpResponse, BugReportNotice(BugReportMessage.AUTHORIZATION_FAILED))
        requireSuccess(
            httpStatus = httpResponse.status,
            response = response,
            denied = BugReportNotice(BugReportMessage.AUTHORIZATION_FAILED),
        )
        return response.stringValue("token")
            ?: (response["data"] as? JsonObject)?.stringValue("token")
            ?: throw BugReportException(BugReportNotice(BugReportMessage.TOKEN_MISSING))
    }

    suspend fun testConnection(token: String) {
        val resolvedTarget = requireTarget()
        requireToken(token)
        val httpResponse = client.get("${resolvedTarget.apiBaseUrl}/products/${resolvedTarget.productId}") {
            header(TOKEN_HEADER, token.trim())
        }
        requireSuccess(
            httpStatus = httpResponse.status,
            response = responseBody(httpResponse, BugReportNotice(BugReportMessage.PRODUCT_DENIED, productId = resolvedTarget.productId)),
            denied = BugReportNotice(BugReportMessage.PRODUCT_DENIED, productId = resolvedTarget.productId),
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
        if (draft.title.isBlank()) throw BugReportException(BugReportNotice(BugReportMessage.TITLE_REQUIRED))
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
        val response = responseBody(httpResponse, BugReportNotice(BugReportMessage.SUBMIT_DENIED, productId = resolvedTarget.productId, branchId = resolvedTarget.branchId))
        requireSuccess(
            httpStatus = httpResponse.status,
            response = response,
            denied = BugReportNotice(BugReportMessage.SUBMIT_DENIED,
                productId = resolvedTarget.productId, branchId = resolvedTarget.branchId),
        )
        val bug = response["bug"] as? JsonObject
            ?: response["data"] as? JsonObject
            ?: response
        val id = bug["id"]?.jsonPrimitive?.intOrNull
            ?.takeIf { it > 0 }
            ?: throw BugReportException(BugReportNotice(BugReportMessage.BUG_ID_MISSING))
        return BugSubmissionResult(
            id = id,
            url = "${resolvedTarget.webBaseUrl}/bug-view-$id.html",
        )
    }

    /** 先创建 Bug 再绑定附件；HTTP 成功缺少正文件 ID 时仍不能确认上传完成。 */
    suspend fun uploadAttachment(token: String, bugId: Int, attachment: BugReportAttachment, bytes: ByteArray): Int {
        val resolved = requireTarget()
        check(attachmentsAvailable) { "Enable attachments only for ZenTao REST v2 22.0+" }
        requireToken(token)
        require(bugId > 0 && bytes.size.toLong() == attachment.size)
        val response = client.post("${resolved.apiBaseUrl}/files") {
            header(TOKEN_HEADER, token.trim())
            setBody(MultiPartFormDataContent(formData {
                append("objectType", "bug")
                append("objectID", bugId.toString())
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, attachment.contentType)
                    append(HttpHeaders.ContentDisposition, "filename=\"${attachment.fileName}\"")
                })
            }))
        }
        val body = responseBody(response, BugReportNotice(BugReportMessage.SUBMIT_DENIED, productId = resolved.productId, branchId = resolved.branchId))
        requireSuccess(response.status, body,
            BugReportNotice(BugReportMessage.SUBMIT_DENIED, productId = resolved.productId, branchId = resolved.branchId))
        return body["id"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
            ?: error("Attachment response did not return a positive file ID")
    }

    internal fun existingBug(id: Int): BugSubmissionResult {
        require(id > 0)
        return BugSubmissionResult(id, "${requireTarget().webBaseUrl}/bug-view-$id.html")
    }

    fun close() = client.close()

    private fun buildSteps(
        draft: BugReportDraft,
        context: BugReportContext,
        evidence: BugEvidenceSnapshot,
    ): String = buildString {
        appendLine(label("【复现步骤】", "[Steps]"))
        appendLine(draft.steps.trim().ifBlank { label("未填写", "Not provided") })
        appendLine("\n【实际结果】")
        appendLine(draft.actualResult.trim().ifBlank { label("未填写", "Not provided") })
        appendLine("\n【期望结果】")
        appendLine(draft.expectedResult.trim().ifBlank { label("未填写", "Not provided") })
        appendLine(label("\n【自动上下文】", "\n[Context]"))
        appendLine("${label("品牌", "Brand")}：${context.brand}")
        appendLine("${label("平台", "Platform")}：${context.platform.name}")
        appendLine("${label("影响范围", "Scope")}：${draft.scope.name}")
        appendLine("${label("版本", "Version")}：${context.version}")
        appendLine("${label("环境", "Environment")}：${context.environment}")
        appendLine("${label("设备", "Device")}：${BugEvidenceFormatter.bound(context.device, 300)}")
        appendLine("${label("用户 ID", "User ID")}：${context.userId.takeIf { it > 0 } ?: label("未登录", "Not signed in")}")
        appendLine(label("\n【页面路径】", "\n[Page trail]"))
        appendLine(evidence.pagePaths.joinToString(" -> ").ifBlank { label("未采集", "Not captured") })
        appendLine(label("\n【最近日志上报】", "\n[Recent logs]"))
        append(BugEvidenceFormatter.boundRecentLogs(evidence.recentLogs).ifBlank { label("未采集", "Not captured") })
    }.take(MAX_STEPS_LENGTH)

    private suspend fun responseBody(response: HttpResponse, denied: BugReportNotice): JsonObject {
        // 先分类身份/权限，HTML 错误页不能覆盖重新授权语义；5xx/缺 ID 仍是未知写入结果。
        if (response.status == HttpStatusCode.Unauthorized) {
            throw BugReportException(if (denied.code == BugReportMessage.AUTHORIZATION_FAILED) denied else BugReportNotice(BugReportMessage.TOKEN_REQUIRED))
        }
        if (response.status == HttpStatusCode.Forbidden) throw BugReportException(denied)
        return response.body()
    }

    private fun label(chinese: String, english: String) = if (reportLanguage == BugReportLanguage.CHINESE) chinese else english

    private fun requireSuccess(
        httpStatus: HttpStatusCode,
        response: JsonObject,
        denied: BugReportNotice,
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
            throw BugReportException(denied)
        }
        error(message)
    }

    private fun requireToken(token: String) {
        if (token.isBlank()) throw BugReportException(BugReportNotice(BugReportMessage.TOKEN_REQUIRED))
    }

    private fun requireTarget(): BugReportTarget.Configured =
        target as? BugReportTarget.Configured
            ?: throw BugReportException(BugReportNotice(BugReportMessage.TARGET_UNAVAILABLE))

    private companion object {
        const val TOKEN_HEADER = "Token"
        const val MAX_STEPS_LENGTH = 30_000
    }
}

private fun JsonObject.stringValue(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
