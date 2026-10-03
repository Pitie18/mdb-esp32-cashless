package xyz.vmflow

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {

    @Test
    fun `machineDetail builds a route the NavHost pattern matches`() {
        assertEquals("machines/abc-123", Routes.machineDetail("abc-123"))
    }

    @Test
    fun `machineDetail route matches the declared MACHINE_DETAIL pattern`() {
        val built = Routes.machineDetail("abc-123")
        val pattern = Routes.MACHINE_DETAIL.replace("{machineId}", "abc-123")
        assertEquals(pattern, built)
    }
}
