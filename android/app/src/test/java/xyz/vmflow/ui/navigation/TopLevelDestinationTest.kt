package xyz.vmflow.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.vmflow.Routes

class TopLevelDestinationTest {

    @Test
    fun `every top level route resolves to its destination`() {
        assertEquals(TopLevelDestination.DASHBOARD, TopLevelDestination.fromRoute(Routes.DASHBOARD))
        assertEquals(TopLevelDestination.MACHINES, TopLevelDestination.fromRoute(Routes.MACHINES))
        assertEquals(TopLevelDestination.REFILL, TopLevelDestination.fromRoute(Routes.REFILL))
    }

    @Test
    fun `a detail route is not a top level destination`() {
        assertNull(TopLevelDestination.fromRoute(Routes.machineDetail("abc-123")))
        assertNull(TopLevelDestination.fromRoute(Routes.MACHINE_DETAIL))
    }

    @Test
    fun `auth routes are not top level destinations`() {
        assertNull(TopLevelDestination.fromRoute(Routes.LOGIN))
        assertNull(TopLevelDestination.fromRoute(Routes.REGISTER))
    }

    @Test
    fun `a null route resolves to nothing`() {
        assertNull(TopLevelDestination.fromRoute(null))
    }

    @Test
    fun `destinations are declared in navigation bar order`() {
        assertEquals(
            listOf(
                TopLevelDestination.DASHBOARD,
                TopLevelDestination.MACHINES,
                TopLevelDestination.REFILL,
            ),
            TopLevelDestination.entries.toList(),
        )
    }
}
