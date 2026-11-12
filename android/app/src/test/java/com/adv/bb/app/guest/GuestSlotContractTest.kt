package com.adv.bb.app.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestSlotContractTest {

    @Test
    fun processNamesMapEverySlotAndRejectUnknownOnes() {
        assertEquals(":guest", GuestSlotContract.processSuffix(0))
        assertEquals(":p1", GuestSlotContract.processSuffix(1))
        assertEquals(":p2", GuestSlotContract.processSuffix(2))
        assertEquals(":p3", GuestSlotContract.processSuffix(3))
        assertEquals("com.adv.bb.app:guest", GuestSlotContract.processName("com.adv.bb.app", 0))
        assertEquals("com.adv.bb.app:p3", GuestSlotContract.processName("com.adv.bb.app", 3))
        assertThrows(IllegalArgumentException::class.java) { GuestSlotContract.processSuffix(4) }
    }

    @Test
    fun processNameIsTheOnlyOwnershipSource() {
        assertEquals(0, GuestSlotContract.fromProcessName("com.adv.bb.app:guest"))
        assertEquals(1, GuestSlotContract.fromProcessName("com.adv.bb.app:p1"))
        assertEquals(3, GuestSlotContract.fromProcessName("com.adv.bb.app:p3"))
        assertNull(GuestSlotContract.fromProcessName(null))
        assertNull(GuestSlotContract.fromProcessName("com.adv.bb.app"))
        assertNull(GuestSlotContract.fromProcessName("com.adv.bb.app:guest1"))
        assertNull(GuestSlotContract.fromProcessName("com.adv.bb.app:p4"))
    }

    @Test
    fun componentNamesAndReverseRoutingStayConsistent() {
        assertEquals(
            "com.adv.bb.app.guest.GuestActivityProxy",
            GuestSlotContract.proxyClassName(0),
        )
        assertEquals(
            "com.adv.bb.app.guest.GuestActivityProxy\$P2",
            GuestSlotContract.proxyClassName(2),
        )
        assertEquals(
            "com.adv.bb.app.guest.GuestLaunchActivity\$P1",
            GuestSlotContract.gateClassName(1),
        )
        assertEquals(
            "com.adv.bb.app.guest.GuestControlReceiver\$P3",
            GuestSlotContract.receiverClassName(3),
        )
        assertEquals(
            "com.adv.bb.app.guest.GuestServiceProxy",
            GuestSlotContract.serviceClassName(0),
        )
        assertEquals(
            "com.adv.bb.app.guest.GuestServiceProxy\$S2",
            GuestSlotContract.serviceClassName(0, 2),
        )
        assertEquals(
            "com.adv.bb.app.guest.GuestServiceProxyP2",
            GuestSlotContract.serviceClassName(2),
        )
        assertEquals(
            "com.adv.bb.app.guest.GuestServiceProxyP3\$S11",
            GuestSlotContract.serviceClassName(3, 11),
        )
        for (slot in GuestSlotContract.all) {
            assertTrue(GuestSlotContract.isProxyClass(GuestSlotContract.proxyClassName(slot)))
            assertTrue(GuestSlotContract.isGateClass(GuestSlotContract.gateClassName(slot)))
            // 每个槽位的整个 Service 代理池都要能反向路由回自己的槽位。
            for (index in 0 until GuestSlotContract.SERVICE_POOL) {
                val service = GuestSlotContract.serviceClassName(slot, index)
                assertTrue(GuestSlotContract.isServiceClass(service))
                assertEquals(slot, GuestSlotContract.slotForComponent(service))
            }
            assertEquals(slot, GuestSlotContract.slotForComponent(GuestSlotContract.proxyClassName(slot)))
            assertEquals(slot, GuestSlotContract.slotForComponent(GuestSlotContract.gateClassName(slot)))
        }
        assertFalse(
            GuestSlotContract.isServiceClass("com.adv.bb.app.guest.GuestServiceProxyP4"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            GuestSlotContract.serviceClassName(0, GuestSlotContract.SERVICE_POOL)
        }
        assertFalse(GuestSlotContract.isProxyClass("com.adv.bb.app.guest.GuestActivityProxy\$P4"))
        assertNull(GuestSlotContract.slotForComponent(null))
        assertThrows(IllegalArgumentException::class.java) { GuestSlotContract.proxyClassName(-1) }
    }

    @Test
    fun diagnosticsAndRecoveryFilesAreScopedBySlot() {
        assertEquals("guest-trace.log", GuestSlotContract.traceFileName(null))
        assertEquals("guest-trace.log", GuestSlotContract.traceFileName(0))
        assertEquals("guest-trace-p1.log", GuestSlotContract.traceFileName(1))
        assertEquals("native-crash.log", GuestSlotContract.nativeCrashFileName(0))
        assertEquals("native-crash-p3.log", GuestSlotContract.nativeCrashFileName(3))
        assertEquals(
            "ad6446c5-6b08-42ad-8aaa-3650a7b474e5.json",
            GuestSlotContract.recordFileName("ad6446c5-6b08-42ad-8aaa-3650a7b474e5"),
        )
        assertEquals(
            "ad6446c5-6b08-42ad-8aaa-3650a7b474e5.json.tmp",
            GuestSlotContract.recordTempFileName("ad6446c5-6b08-42ad-8aaa-3650a7b474e5"),
        )
    }

    @Test
    fun recoveryFileNameRejectsBlankAndPathTraversal() {
        assertThrows(IllegalArgumentException::class.java) {
            GuestSlotContract.recordFileName("")
        }
        val safe = GuestSlotContract.recordFileName("../../etc/passwd")
        assertFalse(safe.contains('/'))
        assertFalse(safe.contains('\\'))
        assertFalse(safe.contains(".."))
        assertTrue(safe.endsWith(".json"))
    }
}
