package com.fitsync.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fitsync.feature.about.AcknowledgementsScreen
import com.fitsync.feature.about.ChangelogScreen
import com.fitsync.feature.detail.AccountDetailScreen
import com.fitsync.feature.detail.AccountsScreen
import com.fitsync.feature.detail.ActivityDetailScreen
import com.fitsync.feature.detail.CopyActivitiesScreen
import com.fitsync.feature.detail.CopyHealthScreen
import com.fitsync.feature.detail.ImportScreen
import com.fitsync.feature.home.HomeScreen
import com.fitsync.feature.login.LoginWizardScreen
import com.fitsync.feature.personal.PersonalApiScreen
import com.fitsync.ui.theme.FitSyncTheme

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object LoginWizard : Screen("login-wizard")
    data object Changelog : Screen("changelog")
    data object Acknowledgements : Screen("acknowledgements")
    data object Accounts : Screen("accounts")
    data object Import : Screen("import")
    data object CopyActivities : Screen("copy-activities")
    data object CopyHealth : Screen("copy-health")
    data object PersonalApi : Screen("personal-api")

    companion object {
        const val ACCOUNT_DETAIL = "account/{accountId}"
        const val ACTIVITY_DETAIL = "activity/{activityId}"
    }
}

@Composable
fun FitSyncRoot() {
    FitSyncTheme {
        val navController = rememberNavController()
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onAddAccount = { navController.navigate(Screen.LoginWizard.route) },
                    onOpenChangelog = { navController.navigate(Screen.Changelog.route) },
                    onOpenAcknowledgements = { navController.navigate(Screen.Acknowledgements.route) },
                    onOpenAccounts = { navController.navigate(Screen.Accounts.route) },
                    onOpenActivity = { navController.navigate("activity/$it") },
                    onOpenImport = { navController.navigate(Screen.Import.route) },
                    onOpenCopyActivities = { navController.navigate(Screen.CopyActivities.route) },
                    onOpenCopyHealth = { navController.navigate(Screen.CopyHealth.route) },
                    onOpenPersonalApi = { navController.navigate(Screen.PersonalApi.route) },
                )
            }
            composable(Screen.LoginWizard.route) {
                LoginWizardScreen(
                    onFinished = {
                        navController.popBackStack(Screen.Home.route, inclusive = true)
                    },
                )
            }
            composable(Screen.Changelog.route) {
                ChangelogScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Acknowledgements.route) {
                AcknowledgementsScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Accounts.route) {
                AccountsScreen(
                    onBack = { navController.popBackStack() },
                    onAccountClick = { navController.navigate("account/$it") },
                )
            }
            composable(
                route = Screen.ACCOUNT_DETAIL,
                arguments = listOf(navArgument("accountId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getLong("accountId") ?: 0L
                AccountDetailScreen(accountId = id, onBack = { navController.popBackStack() })
            }
            composable(
                route = Screen.ACTIVITY_DETAIL,
                arguments = listOf(navArgument("activityId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getLong("activityId") ?: 0L
                ActivityDetailScreen(activityId = id, onBack = { navController.popBackStack() })
            }
            composable(Screen.Import.route) {
                ImportScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.CopyActivities.route) {
                CopyActivitiesScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.CopyHealth.route) {
                CopyHealthScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.PersonalApi.route) {
                PersonalApiScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
