package xyz.vmflow.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.functions.functions
import io.ktor.client.call.body
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import xyz.vmflow.models.Organization
import xyz.vmflow.models.OrganizationResponse

sealed class AuthState {
    data object Loading : AuthState()
    data object NotAuthenticated : AuthState()
    data class Authenticated(val userId: String) : AuthState()
}

object AuthRepository {
    private val auth get() = SupabaseService.client.auth

    @OptIn(ExperimentalCoroutinesApi::class)
    val authState: Flow<AuthState> = SupabaseService.clientFlow
        .flatMapLatest { it.auth.sessionStatus }
        .map { status ->
        when (status) {
            is SessionStatus.Authenticated -> AuthState.Authenticated(
                status.session.user?.id ?: ""
            )
            is SessionStatus.NotAuthenticated -> AuthState.NotAuthenticated
            is SessionStatus.Initializing -> AuthState.Loading
            else -> AuthState.Loading
        }
    }

    val isLoggedIn: Boolean
        get() = auth.currentSessionOrNull() != null

    val currentUserId: String?
        get() = auth.currentUserOrNull()?.id

    suspend fun signIn(email: String, password: String): Result<Unit> {
        return try {
            auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signUp(email: String, password: String): Result<Unit> {
        return try {
            auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signOut() {
        try {
            auth.signOut()
        } catch (_: Exception) {
            // Ignore sign-out errors
        }
    }

    suspend fun fetchOrganization(): Result<OrganizationResponse> {
        return try {
            val response = SupabaseService.client.functions.invoke("get-my-organization")
            val body = response.body<String>()
            val orgResponse = Json.decodeFromString<OrganizationResponse>(body)
            Result.success(orgResponse)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
