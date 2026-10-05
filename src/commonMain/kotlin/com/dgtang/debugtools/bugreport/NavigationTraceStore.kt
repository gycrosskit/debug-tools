package com.dgtang.debugtools.bugreport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 进程内环形轨迹，可跨线程 record；宿主只传无参数页面名，本库不会自动删除 URL/query/账号。
 * @param capacity 保留条数，必须大于 0，默认 20。
 */
class NavigationTraceStore(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val mutableEntries = MutableStateFlow<List<String>>(emptyList())

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    /** trim 后保留前 80 个字符，忽略空白与相邻重复；不写文件。 */
    fun record(page: String) {
        val safePage = page.trim().take(MAX_PAGE_NAME_LENGTH)
        if (safePage.isEmpty()) return
        mutableEntries.update { current ->
            if (current.lastOrNull() == safePage) current
            else if (current.size < capacity) current + safePage
            else buildList(capacity) {
                addAll(current.subList(1, current.size))
                add(safePage)
            }
        }
    }

    /** 返回当前只读快照，不清空、不冻结后续记录。 */
    fun snapshot(): List<String> = mutableEntries.value

    private companion object {
        const val DEFAULT_CAPACITY = 20
        const val MAX_PAGE_NAME_LENGTH = 80
    }
}
