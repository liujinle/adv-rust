package com.adv.bb.app.guest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchClockTest {

    @After
    fun tearDown() {
        LaunchClock.reset()
    }

    @Test
    fun `begin line carries t0 and zero elapsed`() {
        val line = LaunchClock.beginLine(
            "ad6446c5-6b08-42ad-8aaa-3650a7b474e5",
            1_700_000_000_000,
            " slot=p0",
        )
        assertTrue(line.startsWith("LAUNCH +0ms d=0ms BEGIN "))
        assertTrue(line.contains("t0=1700000000000"))
        assertTrue(line.endsWith("slot=p0"))
    }

    @Test
    fun `marks report total and delta since previous phase`() {
        val t0 = 1_000L
        val begin = LaunchClock.begin("instance-a", t0)
        assertTrue(begin.contains("BEGIN"))

        assertEquals("LAUNCH +120ms d=120ms hook 完成", LaunchClock.mark("hook 完成", 1_120))
        assertEquals("LAUNCH +500ms d=380ms prepare 完成", LaunchClock.mark("prepare 完成", 1_500))
    }

    @Test
    fun `finish reports total only once`() {
        LaunchClock.begin("instance-a", 1_000)
        LaunchClock.mark("attach 完成", 1_300)
        val done = LaunchClock.finish("首页 onResume", 2_800)
        assertEquals(
            "LAUNCH +1800ms d=1500ms DONE total=1800ms 首页 onResume instance=instance-a",
            done,
        )
        assertNull(LaunchClock.finish("首页 onResume", 2_900))
    }

    @Test
    fun `mark and finish without a session produce nothing`() {
        assertNull(LaunchClock.mark("orphan", 5_000))
        assertNull(LaunchClock.finish("orphan", 5_000))
    }

    @Test
    fun `clock never reports negative elapsed`() {
        LaunchClock.begin("instance-a", 1_000)
        assertEquals("LAUNCH +0ms d=0ms 回拨", LaunchClock.mark("回拨", 900))
    }

    @Test
    fun `reset starts a clean session`() {
        LaunchClock.begin("instance-a", 1_000)
        LaunchClock.finish("首页 onResume", 1_500)
        LaunchClock.reset()
        assertNull(LaunchClock.mark("after reset", 2_000))
        LaunchClock.begin("instance-b", 2_000)
        assertEquals("LAUNCH +300ms d=300ms 新会话", LaunchClock.mark("新会话", 2_300))
    }
}
