package com.examhelper.shua.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.examhelper.shua.ui.theme.DockTab
import com.examhelper.shua.ui.theme.LiquidDock
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

private val dockTabs = tabs.map { DockTab(it.route, it.label, it.icon) }

/**
 * 底部 iOS 悬浮 Dock：毛玻璃胶囊 + 阴影 + 舒适圆角，逐项弹性放大选中态。
 * 距屏幕底部留 max(14dp, 手势条安全区)。
 */
@Composable
private fun FloatingDock(currentRoute: String?, onSelect: (String) -> Unit) {
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LiquidDock(
        tabs = dockTabs,
        currentRoute = currentRoute,
        bottomInset = navInset,
        onSelect = onSelect
    )
}

@Composable
fun AppRoot(fontScale: Float = 1f) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale)
    ) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val showBottomBar = currentRoute in tabs.map { it.route }
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // 顶部暖调晕染：给浮层玻璃提供可感知的底纹，同时避免纯平死板
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                                androidx.compose.ui.graphics.Color.Transparent
                            )
                        )
                    )
            )

            Scaffold(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (showBottomBar) {
                        FloatingDock(
                            currentRoute = currentRoute,
                            onSelect = { route ->
                                // 目标 tab 若仍在返回栈里，先弹回去（这是「点题库没反应」的根因：
                                // 栈里已有 banks 时，popUpTo(start)+navigate(start) 不会产生任何
                                // 可见跳转，看起来就像按钮失灵）。栈里没有才走新建 + 恢复状态。
                                val popped = navController.popBackStack(route, inclusive = false)
                                if (!popped) {
                                    navController.navigate(route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        )
                    }
                }
            ) { padding ->
                NavHost(
                    navController = navController,
                    startDestination = Routes.BANKS,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            // Dock 走悬浮层，底部内容只需要避让 Dock 高度；顶部自绘安全区
                            bottom = if (showBottomBar) padding.calculateBottomPadding() else 0.dp
                        )
                ) {
                    composable(Routes.BANKS) {
                        BankListScreen(
                            contentPadding = PaddingValues(top = topInset, bottom = 8.dp),
                            onOpenBank = { navController.navigate(Routes.bankDetail(it)) },
                            onImportText = { navController.navigate(Routes.IMPORT_TEXT) },
                            onImportFile = { jobId -> navController.navigate(Routes.importFile(jobId)) },
                            onSearch = { navController.navigate(Routes.SEARCH) }
                        )
                    }
                    composable(Routes.WRONGBOOK) {
                        WrongBookScreen(
                            contentPadding = PaddingValues(top = topInset),
                            onOpenBank = { navController.navigate(Routes.bankDetail(it)) },
                            onStartStudy = { bankId, mode, filterJson ->
                                navController.navigate(Routes.study(bankId, mode, filterJson))
                            }
                        )
                    }
                    composable(Routes.STATS) {
                        StatsScreen(contentPadding = PaddingValues(top = topInset))
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(contentPadding = PaddingValues(top = topInset))
                    }
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
                            onResumeMode = { resumeMode, filterJson ->
                                if (resumeMode == StudyMode.EXAM) {
                                    navController.navigate(Routes.exam(bankId, filterJson, 30))
                                } else {
                                    navController.navigate(Routes.study(bankId, resumeMode, filterJson))
                                }
                            },
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
}
