package com.adv.core.data

import com.adv.core.engine.api.VirtualInstanceState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestLivenessReconcilePolicyTest {

    private val now = 1_000_000L
    private val grace = 15_000L

    @Test
    fun respectsStartGraceAndAliveInstance() {
        val justLaunched = now - grace + 1
        assertFalse(
            GuestLivenessReconcilePolicy.shouldStop(
                VirtualInstanceState.STARTING,
                "A",
                justLaunched,
                emptySet(),
                now,
                grace,
            ),
        )
        assertFalse(
            GuestLivenessReconcilePolicy.shouldStop(
                VirtualInstanceState.RUNNING,
                "A",
                now - grace,
                setOf("B", "A"),
                now,
                grace,
            ),
        )
    }

    @Test
    fun stopsOnlyActiveMissingInstancesAfterGrace() {
        assertTrue(
            GuestLivenessReconcilePolicy.shouldStop(
                VirtualInstanceState.RUNNING,
                "A",
                now - grace,
                setOf("B"),
                now,
                grace,
            ),
        )
        assertTrue(
            GuestLivenessReconcilePolicy.shouldStop(
                VirtualInstanceState.STOPPING,
                "A",
                null,
                emptySet(),
                now,
                grace,
            ),
        )
        assertFalse(
            GuestLivenessReconcilePolicy.shouldStop(
                VirtualInstanceState.STOPPED,
                "A",
                null,
                emptySet(),
                now,
                grace,
            ),
        )
        assertFalse(
            GuestLivenessReconcilePolicy.shouldStop(
                VirtualInstanceState.CREATED,
                "A",
                null,
                emptySet(),
                now,
                grace,
            ),
        )
    }
}
