package com.jacklugano.termvault.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jacklugano.termvault.ui.hosts.HostEditScreen
import com.jacklugano.termvault.ui.hosts.HostListScreen
import com.jacklugano.termvault.ui.session.SessionScreen
import com.jacklugano.termvault.ui.snippets.SnippetsScreen

object Routes {
    const val HOSTS = "hosts"
    const val HOST_EDIT = "hostEdit/{hostId}"
    const val SESSION = "session/{hostId}"
    const val SNIPPETS = "snippets"

    fun hostEdit(hostId: Long) = "hostEdit/$hostId"
    fun session(hostId: Long) = "session/$hostId"
}

@Composable
fun TermVaultNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOSTS) {
        composable(Routes.HOSTS) {
            HostListScreen(
                onAddHost = { navController.navigate(Routes.hostEdit(0L)) },
                onEditHost = { id -> navController.navigate(Routes.hostEdit(id)) },
                onConnect = { id -> navController.navigate(Routes.session(id)) },
                // hostId 0 = apri la schermata sessione senza aprire una nuova
                // connessione: mostra le schede già attive.
                onOpenSessions = { navController.navigate(Routes.session(0L)) },
                onOpenSnippets = { navController.navigate(Routes.SNIPPETS) },
            )
        }
        composable(
            Routes.HOST_EDIT,
            arguments = listOf(navArgument("hostId") { type = NavType.LongType }),
        ) {
            HostEditScreen(onDone = { navController.popBackStack() })
        }
        composable(
            Routes.SESSION,
            arguments = listOf(navArgument("hostId") { type = NavType.LongType }),
        ) { backStackEntry ->
            SessionScreen(
                requestedHostId = backStackEntry.arguments?.getLong("hostId") ?: 0L,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SNIPPETS) {
            SnippetsScreen(onBack = { navController.popBackStack() })
        }
    }
}
