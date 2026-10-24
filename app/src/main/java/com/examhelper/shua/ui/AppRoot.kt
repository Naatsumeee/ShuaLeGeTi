package com.examhelper.shua.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.examhelper.shua.ShuaApp
import com.examhelper.shua.data.db.StudyMode
import com.examhelper.shua.ui.bank.BankDetailScreen
import com.examhelper.shua.ui.bank.BankListScreen
import com.examhelper.shua.ui.bank.ImportTextScreen
import com.examhelper.shua.ui.editor.EditorScreen
import com.examhelper.shua.ui.exam.ExamReportScreen
import com.examhelper.shua.ui.exam.ExamScreen
import com.examhelper.shua.ui.generate.GenerateScreen
import com.examhelper.shua.ui.import_.ImportScreen
import com.examhelper.shua.ui.search.SearchScreen
import com.examhelper.shua.ui.settings.SettingsScreen
import com.examhelper.shua.ui.stats.StatsScreen
import com.examhelper.shua.ui.study.StudyScreen
import com.examhelper.shua.ui.study.StudySetupScreen
import com.examhelper.shua.ui.wrongbook.WrongBookScreen

object Routes {
    const val BANKS = "banks"
    const val WRONGBOOK = "wrongbook"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val IMPORT_TEXT = "import_text"
    const val IMPORT_FILE = "import_file?jobId={jobId}"
    const val GENERATE = "generate/{bankId}"
    const val SEARCH = "search"
    const val BANK_DETAIL = "bank/{bankId}"
    const val STUDY_SETUP = "study_setup/{bankId}/{mode}"
    const val STUDY = "study/{bankId}/{mode}?filter={filter}"
    const val EXAM = "exam/{bankId}?filter={filter}&count={count}"
    const val EXAM_REPORT = "exam_report/{runId}"
    const val EDITOR = "editor/{bankId}"

    fun importFile(jobId: String? = null) = "import_file" + (jobId?.let { "?jobId=$it" } ?: "")
    fun bankDetail(bankId: Long) = "bank/$bankId"
    fun studySetup(bankId: Long, mode: StudyMode) = "study_setup/$bankId/${mode.name}"
    fun study(bankId: Long, mode: StudyMode, filterJson: String? = null) =
        "study/$bankId/${mode.name}" + (filterJson?.let { "?filter=${Uri.encode(it)}" } ?: "")
    fun exam(bankId: Long, filterJson: String? = null, count: Int) =
        "exam/$bankId?count=$count" + (filterJson?.let { "&filter=${Uri.encode(it)}" } ?: "")
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.BANKS, "题库", Icons.Filled.Folder),
    Tab(Routes.WRONGBOOK, "错题本", Icons.Filled.Warning),
    Tab(Routes.STATS, "统计", Icons.Filled.BarChart),
    Tab(Routes.SETTINGS, "设置", Icons.Filled.Settings)
)

/** 紧凑底部导航：56dp 高、小图标细字，弱化存在感 */
@Composable
private fun CompactNavBar(currentRoute: String?, onSelect: (String) -> Unit) {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly
        ) {
            tabs.forEach { tab ->
                val selected = currentRoute == tab.route
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(tab.route) },
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        modifier = Modifier.size(20.dp),
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
fun AppRoot() {
    // 全局字体缩放
    val fontScale by produceState(1f) {
        ShuaApp.instance.container.settings.settings.collect { value = it.fontScale }
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale)
    ) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val showBottomBar = currentRoute in tabs.map { it.route }

        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    CompactNavBar(
                        currentRoute = currentRoute,
                        onSelect = { route ->
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
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
                        onImportText = { navController.navigate(Routes.IMPORT_TEXT) },
                        onImportFile = { jobId -> navController.navigate(Routes.importFile(jobId)) },
                        onSearch = { navController.navigate(Routes.SEARCH) }
                    )
                }
                composable(Routes.WRONGBOOK) {
                    WrongBookScreen(onOpenBank = { navController.navigate(Routes.bankDetail(it)) })
                }
                composable(Routes.STATS) { StatsScreen() }
                composable(Routes.SETTINGS) { SettingsScreen() }
                composable(Routes.SEARCH) {
                    SearchScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.IMPORT_TEXT) {
                    ImportTextScreen(onDone = { navController.popBackStack() })
                }
                composable(Routes.IMPORT_FILE) { entry ->
                    val jobId = entry.arguments?.getString("jobId")
                    ImportScreen(jobId = jobId, onDone = { navController.popBackStack() })
                }
                composable(Routes.GENERATE) { entry ->
                    val bankId = entry.arguments?.getString("bankId")?.toLongOrNull() ?: return@composable
                    GenerateScreen(bankId = bankId, onBack = { navController.popBackStack() })
                }
                composable(Routes.BANK_DETAIL) { entry ->
                    val bankId = entry.arguments?.getString("bankId")?.toLongOrNull() ?: return@composable
                    BankDetailScreen(
                        bankId = bankId,
                        onBack = { navController.popBackStack() },
                        onStartStudy = { mode -> navController.navigate(Routes.studySetup(bankId, mode)) },
                        onGenerate = { navController.navigate("generate/$bankId") },
                        onEdit = { navController.navigate("editor/$bankId") }
                    )
                }
                composable(Routes.STUDY_SETUP) { entry ->
                    val bankId = entry.arguments?.getString("bankId")?.toLongOrNull() ?: return@composable
                    val modeName = entry.arguments?.getString("mode") ?: "SEQUENTIAL"
                    StudySetupScreen(
                        bankId = bankId,
                        modeName = modeName,
                        onBack = { navController.popBackStack() },
                        onStart = { filterJson, examCount ->
                            val mode = runCatching { StudyMode.valueOf(modeName) }.getOrDefault(StudyMode.SEQUENTIAL)
                            if (mode == StudyMode.EXAM) {
                                navController.navigate(Routes.exam(bankId, filterJson, examCount))
                            } else {
                                navController.navigate(Routes.study(bankId, mode, filterJson))
                            }
                        }
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
                composable(Routes.EXAM) { entry ->
                    val bankId = entry.arguments?.getString("bankId")?.toLongOrNull() ?: return@composable
                    ExamScreen(
                        bankId = bankId,
                        onSubmitted = { runId ->
                            navController.navigate("exam_report/$runId") {
                                popUpTo(Routes.BANK_DETAIL)
                            }
                        },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Routes.EXAM_REPORT) { entry ->
                    val runId = entry.arguments?.getString("runId") ?: return@composable
                    ExamReportScreen(
                        runId = runId,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Routes.EDITOR) { entry ->
                    val bankId = entry.arguments?.getString("bankId")?.toLongOrNull() ?: return@composable
                    EditorScreen(bankId = bankId, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
