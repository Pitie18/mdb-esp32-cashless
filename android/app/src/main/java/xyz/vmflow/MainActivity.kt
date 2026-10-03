package xyz.vmflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import xyz.vmflow.data.AuthRepository
import xyz.vmflow.data.AuthState
import xyz.vmflow.ui.navigation.TopLevelDestination
import xyz.vmflow.ui.navigation.navigateToTopLevel
import xyz.vmflow.ui.theme.VMflowTheme
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VMflowTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VMflowAppRoot()
                }
            }
        }
    }
}

@Composable
fun VMflowAppRoot() {
    val navController = rememberNavController()
    var startDestination by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val authState = AuthRepository.authState.first { it !is AuthState.Loading }
        startDestination = when (authState) {
            is AuthState.Authenticated -> Routes.DASHBOARD
            else -> Routes.LOGIN
        }
    }

    val start = startDestination ?: return

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // The bar is a persistent anchor, not a per-screen detail overlay: it
    // stays visible on every destination except login/register (and while
    // the start destination is still being resolved, i.e. no route yet).
    // Previously this was `TopLevelDestination.fromRoute(route) == null`,
    // which also hid it on `machines/{id}` — the exact same screen kept the
    // bar when reached from the Machines tab (route stays `machines`) but
    // lost it when reached from the Dashboard (route becomes
    // `machines/{id}`), confirmed as the same bug on a device.
    val isAuthRoute = currentRoute == null || currentRoute == Routes.LOGIN || currentRoute == Routes.REGISTER
    val selectedDestination = TopLevelDestination.fromRouteRoot(currentRoute)

    NavigationSuiteScaffold(
        layoutType = if (isAuthRoute) {
            NavigationSuiteType.None
        } else {
            NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())
        },
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                val selected = destination == selectedDestination
                item(
                    selected = selected,
                    onClick = { navController.navigateToTopLevel(destination) },
                    icon = {
                        Icon(
                            imageVector = if (selected) {
                                destination.selectedIcon
                            } else {
                                destination.unselectedIcon
                            },
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(destination.labelRes)) },
                )
            }
        },
    ) {
        VMflowNavHost(
            navController = navController,
            startDestination = start
        )
    }
}
