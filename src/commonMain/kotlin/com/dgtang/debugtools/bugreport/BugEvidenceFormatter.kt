package com.dgtang.debugtools.bugreport

/** Bug 正文保留原始诊断内容，只限制外部系统可接受的文本长度。 */
object BugEvidenceFormatter {
    fun bound(value: String, maxLength: Int = 1_500): String = value.take(maxLength)

    /** 保留最近业务与网络日志，从尾部截取有界内容后交给禅道。 */
    fun boundRecentLogs(value: String): String {
        val recentLines = ArrayDeque<String>(MAX_LOG_LINES)
        value.lineSequence().forEach { line ->
            if (recentLines.size == MAX_LOG_LINES) recentLines.removeFirst()
            recentLines.addLast(line.take(MAX_LOG_LINE_CHARACTERS))
        }
        val recentText = recentLines.joinToString("\n")
        if (recentText.length <= MAX_LOG_CHARACTERS) return recentText
        return LOG_TRUNCATED_PREFIX + recentText.takeLast(MAX_LOG_CHARACTERS - LOG_TRUNCATED_PREFIX.length)
    }

    private const val MAX_LOG_LINES = 120
    private const val MAX_LOG_LINE_CHARACTERS = 4_000
    private const val MAX_LOG_CHARACTERS = 12_000
    private const val LOG_TRUNCATED_PREFIX = "… 已省略更早日志\n"
}
