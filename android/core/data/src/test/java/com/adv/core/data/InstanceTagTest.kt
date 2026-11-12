package com.adv.core.data

import com.adv.core.engine.api.VirtualInstanceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstanceTagTest {

    private val id = "ad6446c5-6b08-42ad-8aaa-3650a7b474e5"
    private val other = "8fcd7ce9-97af-4927-af94-7b548c463d37"

    private fun overview(displayName: String, instanceId: String = id) = InstanceOverview(
        instanceId = instanceId,
        displayName = displayName,
        packageName = "com.phoenix.read",
        versionName = "7.4.6.32",
        state = VirtualInstanceState.STOPPED,
        createdAtMs = 0L,
        updatedAtMs = 0L,
        lastLaunchedAtMs = null,
        lastError = null,
        dataPath = "/data/user/0/com.adv.bb.app/files/engine/x",
        appLabel = "红果免费短剧",
        versionLabel = "7.4.6.32",
    )

    @Test
    fun `short id is stable and case insensitive`() {
        assertEquals("ad6446c5", InstanceTag.shortId(id))
        assertEquals(InstanceTag.shortId(id), InstanceTag.shortId(id.uppercase()))
        assertEquals("#ad6446c5", InstanceTag.hash(id))
    }

    @Test
    fun `title is the instance name so renaming is visible in the list`() {
        assertEquals("红果免费短剧", overview("红果免费短剧").title)
        assertEquals("红果免费短剧 2", overview("红果免费短剧 2").title)
    }

    @Test
    fun `subtitle carries version and the full short id`() {
        assertEquals("v7.4.6.32 · #ad6446c5", overview("红果免费短剧").subtitle)
        // 两个实例名相同时，副标题仍然不同（短 id 不会被标题的省略号吃掉）。
        assertEquals("v7.4.6.32 · #8fcd7ce9", overview("红果免费短剧", other).subtitle)
        assertTrue(
            overview("红果免费短剧").subtitle != overview("红果免费短剧", other).subtitle,
        )
    }

    @Test
    fun `recents title keeps custom name but still tags the instance`() {
        assertEquals(
            "红果免费短剧 2 · #8fcd7ce9",
            InstanceTag.recents("红果免费短剧 2", "8fcd7ce9-97af-4927-af94-7b548c463d37"),
        )
        assertEquals("虚拟应用 · #ad6446c5", InstanceTag.recents("  ", id))
    }

    @Test
    fun `recents title is not tagged twice`() {
        val tagged = InstanceTag.recents("测试实例", id)
        assertEquals(tagged, InstanceTag.recents(tagged, id))
    }
}
