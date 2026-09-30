package com.dgtang.debugtools.bugreport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** 仅保存无参数页面名的进程内环形轨迹，避免把 URL、搜索词或账号信息写入诊断上下文。 */
class NavigationTraceStore(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val mutableEntries = MutableStateFlow<List<String>>(emptyList())

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

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

    fun snapshot(): List<String> = mutableEntries.value

    private companion object {
        const val DEFAULT_CAPACITY = 20
        const val MAX_PAGE_NAME_LENGTH = 80
    }
}
