package com.adv.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineRunStateTest {

    @Test
    fun everyEngineStateHasOneReadableLabel() {
        val expected = mapOf(
            EngineRunState.UNINITIALIZED to "未初始化",
            EngineRunState.INITIALIZING to "初始化中",
            EngineRunState.READY to "运行中",
            EngineRunState.FAILED to "初始化失败",
            EngineRunState.STOPPED to "已关闭",
        )
        assertEquals(expected.keys, EngineRunState.entries.toSet())
        expected.forEach { (state, label) -> assertEquals(label, state.label) }
        assertEquals(EngineRunState.entries.size, expected.values.toSet().size)
        expected.values.forEach { label -> assertTrue(label.isNotBlank()) }
    }
}
