package com.dgtang.debugtools.bugreport

/** 不含服务端原文的状态码；宿主可以用自己的本地化资源映射。 */
enum class BugReportMessage {
    TARGET_UNAVAILABLE, TITLE_REQUIRED, ACCOUNT_REQUIRED, PASSWORD_REQUIRED, TOKEN_REQUIRED,
    AUTHORIZED, AUTHORIZATION_FAILED, PRODUCT_DENIED, SUBMIT_DENIED, CONNECTION_OK,
    TOKEN_MISSING, BUG_ID_MISSING, SHAKE_ENABLED, SHAKE_DISABLED, CREDENTIALS_CLEARED,
    SUBMITTED, SUBMITTED_LOCAL_STATE_FAILED, HISTORY_SAVE_FAILED, HISTORY_REFRESH_FAILED, ATTACHMENT_FAILED, WORKSPACE_SAVE_FAILED,
    DRAFT_SAVED, PENDING_SAVED, UNKNOWN_RESULT, WRONG_DESTINATION, WORKSPACE_UNAVAILABLE,
    OPERATION_FAILED,
}

/**
 * @property code 可本地化状态码。
 * @property bugId 已创建 ID，未关联为 0。
 * @property productId 权限失败对应产品 ID，未关联为 0。
 * @property branchId 权限失败对应分支 ID，未关联为 0。
 */
data class BugReportNotice(val code: BugReportMessage, val bugId: Int = 0, val productId: Int = 0, val branchId: Int = 0)
/** 默认状态文案与自动报告标签的语言；不翻译用户输入或原始日志。 */
enum class BugReportLanguage { CHINESE, ENGLISH }

/** 宿主可注入自己的资源映射；兼容默认中文，同时提供英文，无需 UI 依赖。 */
fun BugReportNotice.text(language: BugReportLanguage = BugReportLanguage.CHINESE): String = when (language) {
    BugReportLanguage.CHINESE -> when (code) {
        BugReportMessage.TARGET_UNAVAILABLE -> "当前品牌尚未开通禅道 Bug 提交"
        BugReportMessage.TITLE_REQUIRED -> "请填写 Bug 标题"
        BugReportMessage.ACCOUNT_REQUIRED -> "请输入禅道账号"
        BugReportMessage.PASSWORD_REQUIRED -> "请输入禅道密码"
        BugReportMessage.TOKEN_REQUIRED -> "请先完成禅道账号授权"
        BugReportMessage.AUTHORIZED -> "禅道账号授权成功"
        BugReportMessage.AUTHORIZATION_FAILED -> "禅道账号授权失败，请检查账号和密码"
        BugReportMessage.PRODUCT_DENIED -> "当前禅道账号无权访问产品 $productId"
        BugReportMessage.SUBMIT_DENIED -> "当前禅道账号没有产品 $productId / 分支 $branchId 的提 Bug 权限"
        BugReportMessage.CONNECTION_OK -> "禅道连接成功"
        BugReportMessage.TOKEN_MISSING -> "禅道授权成功但未返回 Token"
        BugReportMessage.BUG_ID_MISSING -> "禅道返回成功但缺少 Bug ID"
        BugReportMessage.SHAKE_ENABLED -> "摇一摇提 Bug 已开启"
        BugReportMessage.SHAKE_DISABLED -> "摇一摇提 Bug 已关闭"
        BugReportMessage.CREDENTIALS_CLEARED -> "禅道登录信息已清除"
        BugReportMessage.SUBMITTED -> "Bug #$bugId 提交成功"
        BugReportMessage.SUBMITTED_LOCAL_STATE_FAILED -> "Bug #$bugId 已创建，本机记录未完整保存；请先核查禅道"
        BugReportMessage.HISTORY_SAVE_FAILED -> "Bug #$bugId 提交成功，本机历史未保存"
        BugReportMessage.HISTORY_REFRESH_FAILED -> "Bug #$bugId 提交成功，本机历史刷新失败"
        BugReportMessage.ATTACHMENT_FAILED -> "Bug #$bugId 已创建，部分附件未上传；请补传附件"
        BugReportMessage.WORKSPACE_SAVE_FAILED -> "本机草稿或待提交记录未保存"
        BugReportMessage.DRAFT_SAVED -> "草稿已保存"
        BugReportMessage.PENDING_SAVED -> "已保存待提交记录，请联网后手动提交"
        BugReportMessage.UNKNOWN_RESULT -> "提交结果不明确，请先核查禅道，确认未创建后再重试"
        BugReportMessage.WRONG_DESTINATION -> "当前禅道目标与待提交记录不同"
        BugReportMessage.WORKSPACE_UNAVAILABLE -> "当前 Store 不支持草稿和待提交记录"
        BugReportMessage.OPERATION_FAILED -> "操作失败，请稍后重试"
    }
    BugReportLanguage.ENGLISH -> when (code) {
        BugReportMessage.TARGET_UNAVAILABLE -> "Bug reporting is not configured"
        BugReportMessage.TITLE_REQUIRED -> "Enter a bug title"
        BugReportMessage.ACCOUNT_REQUIRED -> "Enter your ZenTao account"
        BugReportMessage.PASSWORD_REQUIRED -> "Enter your ZenTao password"
        BugReportMessage.TOKEN_REQUIRED -> "Authorize your ZenTao account first"
        BugReportMessage.AUTHORIZED -> "ZenTao account authorized"
        BugReportMessage.AUTHORIZATION_FAILED -> "ZenTao authorization failed"
        BugReportMessage.PRODUCT_DENIED -> "Access to product $productId denied"
        BugReportMessage.SUBMIT_DENIED -> "Bug creation in product $productId / branch $branchId denied"
        BugReportMessage.CONNECTION_OK -> "ZenTao connection successful"
        BugReportMessage.TOKEN_MISSING -> "Authorization returned no token"
        BugReportMessage.BUG_ID_MISSING -> "Successful response returned no bug ID"
        BugReportMessage.SHAKE_ENABLED -> "Shake to report enabled"
        BugReportMessage.SHAKE_DISABLED -> "Shake to report disabled"
        BugReportMessage.CREDENTIALS_CLEARED -> "ZenTao credentials cleared"
        BugReportMessage.SUBMITTED -> "Bug #$bugId submitted"
        BugReportMessage.SUBMITTED_LOCAL_STATE_FAILED -> "Bug #$bugId created; local recovery state was not fully saved"
        BugReportMessage.HISTORY_SAVE_FAILED -> "Bug #$bugId submitted; local history was not saved"
        BugReportMessage.HISTORY_REFRESH_FAILED -> "Bug #$bugId submitted; local history could not refresh"
        BugReportMessage.ATTACHMENT_FAILED -> "Bug #$bugId created; retry the remaining attachments"
        BugReportMessage.WORKSPACE_SAVE_FAILED -> "Local draft or pending report was not saved"
        BugReportMessage.DRAFT_SAVED -> "Draft saved"
        BugReportMessage.PENDING_SAVED -> "Pending report saved; submit manually when online"
        BugReportMessage.UNKNOWN_RESULT -> "Check ZenTao before retrying this uncertain submission"
        BugReportMessage.WRONG_DESTINATION -> "The pending report belongs to a different ZenTao target"
        BugReportMessage.WORKSPACE_UNAVAILABLE -> "This store does not support drafts or pending reports"
        BugReportMessage.OPERATION_FAILED -> "Operation failed; try again later"
    }
}

/**
 * 带中性状态的操作异常；宿主不得原样公开可能含凭据的 cause。
 * @property notice 可本地化的失败原因，不含远端正文。
 * @param cause 原始诊断异常，可能含签名 URL/敏感上下文。
 */
class BugReportException(val notice: BugReportNotice, cause: Throwable? = null) :
    IllegalStateException(notice.text(), cause)
