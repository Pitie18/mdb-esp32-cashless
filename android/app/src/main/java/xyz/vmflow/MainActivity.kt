package xyz.vmflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import xyz.vmflow.data.AuthRepository
import xyz.vmflow.data.AuthState
import xyz.vmflow.ui.theme.VMflowTheme
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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

    startDestination?.let { start ->
        VMflowNavHost(
            navController = navController,
            startDestination = start
        )
    }
}
