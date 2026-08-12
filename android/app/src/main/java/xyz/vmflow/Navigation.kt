package xyz.vmflow

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import xyz.vmflow.ui.auth.LoginScreen
import xyz.vmflow.ui.auth.RegisterScreen
import xyz.vmflow.ui.dashboard.DashboardScreen
import xyz.vmflow.ui.machines.MachineDetailScreen
import xyz.vmflow.ui.machines.MachineListScreen
import xyz.vmflow.ui.refill.RefillWizardScreen

object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val DASHBOARD = "dashboard"
    const val MACHINES = "machines"
    const val MACHINE_DETAIL = "machines/{machineId}"
    const val REFILL = "refill"

    fun machineDetail(machineId: String) = "machines/$machineId"
}

@Composable
fun VMflowNavHost(
    navController: NavHostController,
    startDestination: String
) {
    val animDuration = 300

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(animDuration)
            ) + fadeIn(tween(animDuration))
        },
        exitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(animDuration)
            ) + fadeOut(tween(animDuration))
        },
        popEnterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(animDuration)
            ) + fadeIn(tween(animDuration))
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(animDuration)
            ) + fadeOut(tween(animDuration))
        }
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                onNavigateToRegister = {
                    navController.navigate(Routes.REGISTER)
                }
            )
        }

        composable(Routes.REGISTER) {
            RegisterScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onNavigateToMachine = { id ->
                    navController.navigate(Routes.machineDetail(id))
                },
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.MACHINES) {
            MachineListScreen(
                onNavigateToMachine = { id ->
                    navController.navigate(Routes.machineDetail(id))
                }
            )
        }

        composable(
            route = Routes.MACHINE_DETAIL,
            arguments = listOf(
                navArgument("machineId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val machineId = backStackEntry.arguments?.getString("machineId") ?: return@composable
            MachineDetailScreen(
                machineId = machineId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.REFILL) {
            RefillWizardScreen(
                onDone = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.DASHBOARD) { inclusive = true }
                    }
                }
            )
        }
    }
}
