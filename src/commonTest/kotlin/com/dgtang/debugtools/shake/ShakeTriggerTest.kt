package com.dgtang.debugtools.shake

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShakeTriggerTest {
    @Test
    fun `preserves threshold and cooldown boundaries`() {
        val trigger = ShakeTrigger()
        assertFalse(trigger.detect(2.699, 5_000))
        assertTrue(trigger.detect(2.7, 5_000))
        assertFalse(trigger.detect(9.0, 6_199))
        assertTrue(trigger.detect(2.7, 6_200))
    }

    @Test
    fun `weak readings do not extend cooldown`() {
        val trigger = ShakeTrigger()
        assertTrue(trigger.detect(2.8, 5_000))
        assertFalse(trigger.detect(1.0, 6_199))
        assertTrue(trigger.detect(2.8, 6_200))
    }

    @Test
    fun `preserves initial startup cooldown and backward clock behavior`() {
        val trigger = ShakeTrigger()
        assertFalse(trigger.detect(2.8, 1_199))
        assertTrue(trigger.detect(2.8, 1_200))
        assertFalse(trigger.detect(2.8, 1_000))
        assertTrue(trigger.detect(2.8, 2_400))
    }

    @Test
    fun `invalid sensor data cannot consume cooldown`() {
        val trigger = ShakeTrigger()
        assertFalse(trigger.detect(Double.NaN, 5_000))
        assertFalse(trigger.detect(Double.POSITIVE_INFINITY, 5_000))
        assertTrue(trigger.detect(2.7, 5_000))
    }
}
