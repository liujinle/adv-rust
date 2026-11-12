package com.adv.core.engine.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualInstanceStateTest {

    @Test
    fun lifecycleFlagsAreExactForEveryState() {
        val active = setOf(
            VirtualInstanceState.STARTING,
            VirtualInstanceState.RUNNING,
            VirtualInstanceState.STOPPING,
        )
        val launchable = setOf(VirtualInstanceState.INSTALLED, VirtualInstanceState.STOPPED)
        val stoppable = setOf(VirtualInstanceState.STARTING, VirtualInstanceState.RUNNING)

        VirtualInstanceState.entries.forEach { state ->
            assertEquals("isActive(${state.name})", state in active, state.isActive)
            assertEquals("canLaunch(${state.name})", state in launchable, state.canLaunch)
            assertEquals("canStop(${state.name})", state in stoppable, state.canStop)
        }
    }

    @Test
    fun nativeNamesAreCaseInsensitiveAndUnknownFallsBackToCreated() {
        assertEquals(VirtualInstanceState.RUNNING, VirtualInstanceState.fromNativeName("running"))
        assertEquals(VirtualInstanceState.STOPPED, VirtualInstanceState.fromNativeName("STOPPED"))
        assertEquals(VirtualInstanceState.FAILED, VirtualInstanceState.fromNativeName("FaIlEd"))
        assertEquals(VirtualInstanceState.CREATED, VirtualInstanceState.fromNativeName(null))
        assertEquals(VirtualInstanceState.CREATED, VirtualInstanceState.fromNativeName("not-a-state"))
    }

    @Test
    fun labelsCoverAllStatesWithoutCollisions() {
        val labels = VirtualInstanceState.entries.map { it.label }
        assertEquals(VirtualInstanceState.entries.size, labels.toSet().size)
        labels.forEach { label -> assertTrue(label.isNotBlank()) }
        assertFalse(labels.any { it == "null" })
    }
}
