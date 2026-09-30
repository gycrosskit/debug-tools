package com.dgtang.debugtools.bugreport

import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationTraceStoreTest {
    @Test
    fun `keeps bounded unique consecutive page names`() {
        val store = NavigationTraceStore(capacity = 3)

        store.record("Main/HOME")
        store.record("Main/HOME")
        store.record("Search")
        store.record("Audience")
        store.record("Settings")

        assertEquals(listOf("Search", "Audience", "Settings"), store.snapshot())
    }
}
