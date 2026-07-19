package com.emuhub.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.emuhub.app.di.AppContainer
import com.emuhub.app.ui.gamelist.GameListScreen
import com.emuhub.app.ui.home.HomeScreen
import com.emuhub.app.ui.onboarding.OnboardingScreen
import com.emuhub.app.ui.settings.SettingsScreen
import com.emuhub.app.ui.splash.SplashScreen

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer não fornecido")
}

object Routes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val GAMES = "games/{filter}"
    fun games(filter: String) = "games/$filter"

    // Filtros especiais da lista de jogos, além de um systemId.
    const val FILTER_ALL = "all"
    const val FILTER_FAVORITES = "favoritos"
    const val FILTER_RECENTS = "recentes"
}

@Composable
fun EmuHubNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            SplashScreen(onDone = { onboardingDone ->
                navController.navigate(if (onboardingDone) Routes.HOME else Routes.ONBOARDING) {
                    popUpTo(Routes.SPLASH) { inclusive = true }
                }
            })
        }
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onFinished = {
                navController.navigate(Routes.HOME) {
                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
            })
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenSystem = { navController.navigate(Routes.games(it)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.GAMES) { backStackEntry ->
            val filter = backStackEntry.arguments?.getString("filter") ?: Routes.FILTER_ALL
            GameListScreen(filter = filter, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
