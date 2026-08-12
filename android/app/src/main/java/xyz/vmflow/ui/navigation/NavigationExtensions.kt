package xyz.vmflow.ui.navigation

import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination

/**
 * Switches between navigation-bar destinations.
 *
 * Pops back to the graph's start destination while saving each tab's
 * back stack, so returning to a tab restores where the user was — the
 * behaviour Material specifies for bottom navigation.
 */
fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
