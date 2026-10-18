package com.dgtang.debugtools.bugreport

import kotlin.test.Test
import kotlin.test.assertFailsWith

class BugReportStoreKeysTest {
    @Test fun everyCollisionInOneStorageIsRejected() {
        val keys = listOf("token", "shake", "history", "draft", "pending")
        requireDistinctStoreKeys(*keys.toTypedArray())
        for (first in keys.indices) {
            for (second in first + 1 until keys.size) {
                val collision = keys.toMutableList().apply { this[second] = this[first] }
                assertFailsWith<IllegalArgumentException> {
                    requireDistinctStoreKeys(*collision.toTypedArray())
                }
            }
        }
    }
}
