package com.examhelper.shua.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.examhelper.shua.ui.bank.BankDetailScreen
import com.examhelper.shua.ui.bank.BankListScreen
import com.examhelper.shua.ui.bank.ImportTextScreen
import com.examhelper.shua.ui.settings.SettingsScreen
import com.examhelper.shua.ui.stats.StatsScreen
import com.examhelper.shua.ui.study.StudyScreen
import com.examhelper.shua.ui.wrongbook.WrongBookScreen

object Routes {
    const val BANKS = "banks"
    const val WRONGBOOK = "wrongbook"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val IMPORT_TEXT = "import_text"
    const val BANK_DETAIL = "bank/{bankId}"
    const val STUDY = "study/{bankId}/{mode}"

    fun bankDetail(bankId: Long) = "bank/$bankId"
    fun study(bankId: Long, mode: String) = "study/$bankId/$mode"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.BANKS, "题库", Icons.Filled.Folder),
    Tab(Routes.WRONGBOOK, "错题本", Icons.Filled.Warning),
    Tab(Routes.STATS, "统计", Icons.Filled.BarChart),
    Tab(Routes.SETTINGS, "设置", Icons.Filled.Settings)
)

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in tabs.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.BANKS,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.BANKS) {
                BankListScreen(
                    onOpenBank = { navController.navigate(Routes.bankDetail(it)) },
                    onImportText = { navController.navigate(Routes.IMPORT_TEXT) }
                )
            }
            composable(Routes.WRONGBOOK) {
                WrongBookScreen(onOpenBank = { navController.navigate(Routes.bankDetail(it)) })
            }
            composable(Routes.STATS) { StatsScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.IMPORT_TEXT) {
                ImportTextScreen(onDone = { navController.popBackStack() })
            }
            composable(Routes.BANK_DETAIL) { entry ->
                val bankId = entry.arguments?.getString("bankId")?.toLongOrNull() ?: return@composable
                BankDetailScreen(
                    bankId = bankId,
                    onBack = { navController.popBackStack() },
                    onStartStudy = { mode -> navController.navigate(Routes.study(bankId, mode.name)) }
                )
            }
            composable(Routes.STUDY) { entry ->
                val bankId = entry.arguments?.getString("bankId")?.toLongOrNull() ?: return@composable
                val mode = entry.arguments?.getString("mode") ?: "SEQUENTIAL"
                StudyScreen(
                    bankId = bankId,
                    modeName = mode,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
