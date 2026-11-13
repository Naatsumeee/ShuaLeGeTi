package com.examhelper.shua.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
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
import com.examhelper.shua.ui.maintenance.MaintenancePickerScreen
import com.examhelper.shua.ui.question.SingleQuestionScreen
import com.examhelper.shua.ui.search.SearchScreen
import com.examhelper.shua.ui.settings.SettingsScreen
import com.examhelper.shua.ui.stats.StatsScreen
import com.examhelper.shua.ui.study.StudyScreen
import com.examhelper.shua.ui.study.StudySetupScreen
import com.examhelper.shua.ui.theme.DockTab
import com.examhelper.shua.ui.theme.LiquidDock
import com.examhelper.shua.ui.wrongbook.WrongBookScreen
import com.examhelper.shua.ui.wrongbook.WrongBookPdfExportScreen

object Routes {
    const val BANKS = "banks"
    const val WRONGBOOK = "wrongbook"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val IMPORT_TEXT = "import_text"
    const val IMPORT_FILE = "import_file?jobId={jobId}"
    const val GENERATE = "generate/{bankId}"
    const val SEARCH = "search"
    const val BANK_DETAIL = "bank/{bankId}?allGroup={allGroup}"
    const val STUDY_SETUP = "study_setup/{bankId}/{mode}?allGroup={allGroup}"
    const val STUDY = "study/{bankId}/{mode}?filter={filter}"
    const val EXAM = "exam/{bankId}?filter={filter}&count={count}"
    const val EXAM_REPORT = "exam_report/{runId}"
    /**
     * 单题查看页：测试报告与错题本共用。
     *
     * runId 为空表示「不是从测试报告进来的」，页面底部按钮显示「返回错题本」。
     * 用 query 参数而不是路径段，是因为空字符串没法作为可选路径段。
     */
    const val SINGLE_QUESTION = "single_question/{questionId}?runId={runId}"
    const val EDITOR = "editor/{bankId}?queueIds={queueIds}&queueIndex={queueIndex}"
    const val MAINTENANCE = "maintenance/{kind}?ids={ids}"
    const val PDF_EXPORT = "pdf_export/{source}"

    fun importFile(jobId: String? = null) = "import_file" + (jobId?.let { "?jobId=$it" } ?: "")
    fun bankDetail(bankId: Long, allGroup: Boolean = false) = "bank/$bankId" + (if (allGroup) "?allGroup=true" else "")
    fun maintenance(kind: String, ids: List<Long>) = "maintenance/$kind?ids=${ids.joinToString(",")}"
    fun pdfExport(source: com.examhelper.shua.data.model.PdfExportSource) = "pdf_export/${source.name}"
    fun editor(bankId: Long, queueIds: List<Long> = emptyList(), queueIndex: Int = -1) = "editor/$bankId" + (if (queueIds.isEmpty()) "" else "?queueIds=${queueIds.joinToString(",")}&queueIndex=$queueIndex")
    fun studySetup(bankId: Long, mode: StudyMode, allGroup: Boolean = false) = "study_setup/$bankId/${mode.name}" + (if (allGroup) "?allGroup=true" else "")
    fun study(bankId: Long, mode: StudyMode, filterJson: String? = null) =
        "study/$bankId/${mode.name}" + (filterJson?.let { "?filter=${Uri.encode(it)}" } ?: "")
    fun exam(bankId: Long, filterJson: String? = null, count: Int) =
        "exam/$bankId?count=$count" + (filterJson?.let { "&filter=${Uri.encode(it)}" } ?: "")

    /** 单题查看页；runId 非空表示从测试报告进入（决定底部按钮文案） */
    fun singleQuestion(questionId: Long, runId: String? = null) =
        "single_question/$questionId" + (runId?.let { "?runId=${Uri.encode(it)}" } ?: "")
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
                    // 一级 tab 横向切换：方向按 tab 索引判断，速度与 Dock 胶囊同一组弹簧。
                    // 只在一级 tab 之间做横移，进出二级页保持默认快速转场，避免列表页
                    // 与详情页之间来回横滑造成“层级不清”。
                    enterTransition = {
                        if (!isTabToTab(initialState.destination.route, targetState.destination.route)) {
                            // 二级页面（详情/刷题/导入）保持系统默认的快速淡入，
                            // 只有一级 tab 之间才做横向跟手过场。
                            slideInHorizontally(
                                animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
                                initialOffsetX = { full -> full / 4 }
                            ) + fadeIn(tween(120))
                        } else {
                            val from = tabIndex(initialState.destination.route.orEmpty())
                            val to = tabIndex(targetState.destination.route.orEmpty())
                            val dir = if (to >= from) 1 else -1
                            slideInHorizontally(
                                animationSpec = spring(dampingRatio = 0.80f, stiffness = Spring.StiffnessMedium),
                                initialOffsetX = { full -> dir * full / 3 }
                            ) + fadeIn(tween(90))
                        }
                    },
                    exitTransition = {
                        if (!isTabToTab(initialState.destination.route, targetState.destination.route)) {
                            slideOutHorizontally(
                                animationSpec = tween(160, easing = androidx.compose.animation.core.FastOutLinearInEasing),
                                targetOffsetX = { full -> -full / 6 }
                            ) + fadeOut(tween(110))
                        } else {
                            val from = tabIndex(initialState.destination.route.orEmpty())
                            val to = tabIndex(targetState.destination.route.orEmpty())
                            val dir = if (to >= from) 1 else -1
                            slideOutHorizontally(
                                animationSpec = tween(150, easing = androidx.compose.animation.core.FastOutLinearInEasing),
                                targetOffsetX = { full -> -dir * full / 4 }
                            ) + fadeOut(tween(110))
                        }
                    },
                    popEnterTransition = {
                        if (isTabToTab(initialState.destination.route, targetState.destination.route)) {
                            val from = tabIndex(initialState.destination.route.orEmpty())
                            val to = tabIndex(targetState.destination.route.orEmpty())
                            val dir = if (to >= from) 1 else -1
                            slideInHorizontally(
                                animationSpec = spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMedium),
                                initialOffsetX = { full -> dir * full / 4 }
                            ) + fadeIn(tween(90))
                        } else {
                            slideInHorizontally(
                                animationSpec = spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMedium),
                                initialOffsetX = { full -> -full / 4 }
                            ) + fadeIn(tween(100))
                        }
                    },
                    popExitTransition = {
                        if (isTabToTab(initialState.destination.route, targetState.destination.route)) {
                            val from = tabIndex(initialState.destination.route.orEmpty())
                            val to = tabIndex(targetState.destination.route.orEmpty())
                            val dir = if (to >= from) 1 else -1
                            slideOutHorizontally(
                                animationSpec = tween(150, easing = androidx.compose.animation.core.FastOutLinearInEasing),
                                targetOffsetX = { full -> -dir * full / 4 }
                            ) + fadeOut(tween(110))
                        } else {
                            slideOutHorizontally(
                                animationSpec = tween(160, easing = androidx.compose.animation.core.FastOutLinearInEasing),
                                targetOffsetX = { full -> full / 6 }
                            ) + fadeOut(tween(110))
                        }
                    },
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
                            },
                            onOpenQuestion = { qid -> navController.navigate(Routes.singleQuestion(qid, null)) },
                            onExportPdf = { source -> navController.navigate(Routes.pdfExport(source)) }
                        )
                    }
                    composable(Routes.STATS) { StatsScreen(contentPadding = PaddingValues(top = topInset)) }
                    composable(Routes.SETTINGS) { SettingsScreen(contentPadding = PaddingValues(top = topInset)) }
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
                            allGroup = entry.arguments?.getString("allGroup") == "true",
                            onBack = { navController.popBackStack() },
                            onStartStudy = { mode, allGroupMode -> navController.navigate(Routes.studySetup(bankId, mode, allGroupMode)) },
                            onGenerate = { ids -> navController.navigate(Routes.maintenance("generate", ids)) },
                            onEdit = { ids -> navController.navigate(Routes.maintenance("edit", ids)) },
                            onOpenGroupChild = { childId -> navController.navigate(Routes.bankDetail(childId)) },
                            onOpenGroupAll = { navController.navigate(Routes.bankDetail(bankId, true)) }
                        )
                    }
                    composable(Routes.STUDY_SETUP) { entry ->
                        val bankId = entry.arguments?.getString("bankId")?.toLongOrNull() ?: return@composable
                        val mode = runCatching {
                            StudyMode.valueOf(entry.arguments?.getString("mode").orEmpty())
                        }.getOrDefault(StudyMode.SEQUENTIAL)
                        val allGroup = entry.arguments?.getString("allGroup") == "true"
                        val goStudy: (String?, Int) -> Unit = { filterJson, examCount ->
                            val route = if (mode == StudyMode.EXAM) {
                                Routes.exam(bankId, filterJson, examCount)
                            } else {
                                Routes.study(bankId, mode, filterJson)
                            }
                            navController.navigate(route)
                        }
                        StudySetupScreen(
                            bankId = bankId,
                            modeName = mode.name,
                            allGroup = allGroup,
                            onStart = goStudy,
                            onBack = { navController.popBackStack() },
                            onResumeMode = { resumeMode, filterJson ->
                                val route = if (resumeMode == StudyMode.EXAM) {
                                    Routes.exam(bankId, filterJson, 0)
                                } else {
                                    Routes.study(bankId, resumeMode, filterJson)
                                }
                                navController.navigate(route)
                            }
                        )
                    }
                    composable(Routes.MAINTENANCE) { entry ->
                        val kind = entry.arguments?.getString("kind") ?: "generate"
                        val ids = entry.arguments?.getString("ids").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
                        MaintenancePickerScreen(
                            kind = kind,
                            bankIds = ids,
                            onBack = { navController.popBackStack() },
                            onStartOne = { id ->
                                if (kind == "generate") {
                                    com.examhelper.shua.data.generate.GenerateWorker.enqueue(
                                        context = com.examhelper.shua.ShuaApp.instance,
                                        bankId = id,
                                        forceAll = false,
                                        concurrency = 3,
                                        scope = com.examhelper.shua.data.generate.GenerateScope.MISSING
                                    )
                                    navController.navigate("generate/$id")
                                } else {
                                    navController.navigate(Routes.editor(id))
                                }
                            },
                            onStartSequence = { sequence ->
                                if (sequence.isNotEmpty()) {
                                    if (kind == "generate") {
                                        com.examhelper.shua.data.generate.GenerateWorker.enqueueSequential(
                                            com.examhelper.shua.ShuaApp.instance,
                                            sequence
                                        )
                                    } else {
                                        navController.navigate(Routes.editor(sequence.first(), sequence, 0))
                                    }
                                }
                            }
                        )
                    }
                    composable(Routes.PDF_EXPORT) {
                        WrongBookPdfExportScreen(onBack = { navController.popBackStack() })
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
                            onBack = { navController.popBackStack() },
                            // 点某一道题 → 单题详情（默认展开解析，底部按钮回报告）
                            onOpenQuestion = { qid ->
                                navController.navigate(Routes.singleQuestion(qid, runId))
                            }
                        )
                    }
                    composable(Routes.SINGLE_QUESTION) { entry ->
                        val qid = entry.arguments?.getString("questionId")?.toLongOrNull() ?: return@composable
                        val runId = entry.arguments?.getString("runId")?.takeIf { it.isNotBlank() }
                        SingleQuestionScreen(
                            questionId = qid,
                            runId = runId,
                            backLabel = if (runId != null) "返回测试报告" else "返回错题本",
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(Routes.EDITOR) { entry ->
                        val bankId = entry.arguments?.getString("bankId")?.toLongOrNull() ?: return@composable
                        val queueIds = entry.arguments?.getString("queueIds").orEmpty()
                            .split(',')
                            .mapNotNull { it.toLongOrNull() }
                        val queueIndex = entry.arguments?.getString("queueIndex")?.toIntOrNull() ?: -1
                        EditorScreen(bankId = bankId, onBack = {
                            val next = queueIndex + 1
                            if (next in queueIds.indices) {
                                navController.navigate(Routes.editor(queueIds[next], queueIds, next)) {
                                    popUpTo(entry.destination.id) { inclusive = true }
                                }
                            } else {
                                navController.popBackStack()
                            }
                        })
                    }
                }
            }
        }
    }
}

private fun tabIndex(route: String): Int =
    listOf(Routes.BANKS, Routes.WRONGBOOK, Routes.STATS, Routes.SETTINGS)
        .indexOf(route).coerceAtLeast(0)

private fun isTabRoute(route: String?): Boolean =
    route == Routes.BANKS || route == Routes.WRONGBOOK || route == Routes.STATS || route == Routes.SETTINGS

private fun isTabToTab(from: String?, to: String?): Boolean =
    isTabRoute(from) && isTabRoute(to) && from != to
