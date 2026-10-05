package com.dgtang.debugtools.bugreport

/** Bug 正文保留原始诊断内容，只限制外部系统可接受的文本长度。 */
object BugEvidenceFormatter {
    /** 保留前 maxLength 个 UTF-16 字符（非负），不做脱敏或字节边界保证。 */
    fun bound(value: String, maxLength: Int = 1_500): String = value.take(maxLength)

    /** 保留最新 120 行、每行前 4000 字符及总计 12000 字符，截断提示默认中文；原文授权/脱敏由宿主负责。 */
    fun boundRecentLogs(value: String): String = boundRecentLogs(value, BugReportLanguage.CHINESE)

    /** 公开入口保留旧签名与默认中文；报告正文复用此实现，只本地化自动提示，不翻译日志原文。 */
    internal fun boundRecentLogs(value: String, language: BugReportLanguage): String {
        val recentLines = ArrayDeque<String>(MAX_LOG_LINES)
        value.lineSequence().forEach { line ->
            if (recentLines.size == MAX_LOG_LINES) recentLines.removeFirst()
            recentLines.addLast(line.take(MAX_LOG_LINE_CHARACTERS))
        }
        val recentText = recentLines.joinToString("\n")
        if (recentText.length <= MAX_LOG_CHARACTERS) return recentText
        val prefix = when (language) {
            BugReportLanguage.CHINESE -> LOG_TRUNCATED_PREFIX_CHINESE
            BugReportLanguage.ENGLISH -> LOG_TRUNCATED_PREFIX_ENGLISH
        }
        return prefix + recentText.takeLast(MAX_LOG_CHARACTERS - prefix.length)
    }

    private const val MAX_LOG_LINES = 120
    private const val MAX_LOG_LINE_CHARACTERS = 4_000
    private const val MAX_LOG_CHARACTERS = 12_000
    private const val LOG_TRUNCATED_PREFIX_CHINESE = "… 已省略更早日志\n"
    private const val LOG_TRUNCATED_PREFIX_ENGLISH = "… Earlier logs omitted\n"
}
