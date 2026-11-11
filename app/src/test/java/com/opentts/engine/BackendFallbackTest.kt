package com.opentts.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A reading app must get a voice even when the configured backend cannot run,
 * so the ordering has to keep the preferred backend first and cover all three.
 */
class BackendFallbackTest {

    private val all = setOf(
        TtsSettings.BACKEND_LOCAL,
        TtsSettings.BACKEND_SERVER,
        TtsSettings.BACKEND_CLOUD,
    )

    @Test
    fun `preferred backend is tried first`() {
        for (backend in all) {
            assertEquals(backend, BackendFallback.order(backend).first())
        }
    }

    @Test
    fun `every backend appears exactly once`() {
        for (backend in all) {
            val order = BackendFallback.order(backend)
            assertEquals(all.size, order.size)
            assertEquals(all, order.toSet())
        }
    }

    @Test
    fun `unknown stored value still yields a usable order`() {
        val order = BackendFallback.order("something-else")
        assertEquals(all.size + 1, order.size)
        assertEquals(all, order.drop(1).toSet())
    }
}
