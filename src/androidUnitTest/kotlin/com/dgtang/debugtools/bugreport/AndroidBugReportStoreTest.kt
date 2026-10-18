package com.dgtang.debugtools.bugreport

import android.content.Context
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AndroidBugReportStoreTest {
    @Test fun rejectsCollidingTokenAndJournalBeforeChangingStoredData() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("store-key-collision", Context.MODE_PRIVATE)
        try {
            preferences.edit().putString("journal", "persisted-pending-report").commit()
            assertFailsWith<IllegalArgumentException> {
                AndroidBugReportStore(context, Json, "store-key-collision", "fixture-key",
                    tokenKey = "journal", pendingKey = "journal")
            }
            assertEquals("persisted-pending-report", preferences.getString("journal", null))
            AndroidBugReportStore(context, Json, "store-key-collision", "fixture-key")
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
