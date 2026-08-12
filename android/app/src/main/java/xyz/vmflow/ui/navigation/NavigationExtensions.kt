package xyz.vmflow.ui.navigation

import androidx.navigation.NavHostController

/**
 * Switches between navigation-bar destinations.
 *
 * Pops back to the dashboard while saving each tab's back stack, so
 * returning to a tab restores where the user was — the behaviour
 * Material specifies for bottom navigation.
 *
 * The anchor is deliberately the dashboard route and NOT
 * `graph.findStartDestination()`. On a cold start without a session the
 * graph's start destination is `login`, and the sign-in handler removes
 * it from the back stack with `popUpTo(LOGIN) { inclusive = true }`.
 * Popping to an id that is no longer on the stack is a silent no-op, so
 * nothing would ever be popped or saved and the stack would grow with
 * every tab tap. The dashboard is on the stack in both entry paths.
 */
fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(TopLevelDestination.DASHBOARD.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
