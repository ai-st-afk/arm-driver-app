package ru.profstroyservices.armdriver.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.profstroyservices.armdriver.ui.assignment.AssignmentScreen
import ru.profstroyservices.armdriver.ui.assignment.AssignmentsGate
import ru.profstroyservices.armdriver.ui.history.HistoryScreen
import ru.profstroyservices.armdriver.ui.queue.QueueViewModel
import ru.profstroyservices.armdriver.ui.queue.UnsentBanner
import ru.profstroyservices.armdriver.ui.roadmap.RoadmapScreen
import ru.profstroyservices.armdriver.ui.settings.SettingsScreen
import ru.profstroyservices.armdriver.ui.update.UpdateBanner
import ru.profstroyservices.armdriver.ui.assignment.GateMode
import ru.profstroyservices.armdriver.ui.current.CurrentAssignmentBar
import ru.profstroyservices.armdriver.ui.current.CurrentAssignmentViewModel

// Постоянный нижний таб-бар — это и есть «всегда можно выйти в главное
// меню» из запроса автора: не нужен отдельный пункт «Главная», сам бар
// доступен на всех вложенных экранах. «Разнарядка» и «Мои рейсы» — каждая
// свой вложенный граф (корень-список + карточка/roadmap по id), чтобы
// стандартный паттерн save/restore по табам (popUpTo графа + restoreState)
// корректно восстанавливал, где пользователь был внутри таба, а не только
// его корневой экран.
object MainRoutes {
    const val ASSIGNMENTS_GRAPH = "assignments_graph"
    const val ASSIGNMENTS = "assignments"
    const val ASSIGNMENT_DETAIL = "assignments/{assignmentId}"

    const val TRIPS_GRAPH = "trips_graph"
    const val TRIPS = "trips"
    const val ROADMAP = "trips/{assignmentId}"

    const val HISTORY = "history"
    const val SETTINGS = "settings"

    fun assignmentDetail(id: String) = "assignments/$id"
    fun roadmap(id: String) = "trips/$id"
}

private data class TabItem(val navTarget: String, val selectionRoute: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem(MainRoutes.ASSIGNMENTS_GRAPH, MainRoutes.ASSIGNMENTS_GRAPH, "Разнарядка", Icons.Filled.Assignment),
    TabItem(MainRoutes.TRIPS_GRAPH, MainRoutes.TRIPS_GRAPH, "Мои рейсы", Icons.Filled.LocalShipping),
    TabItem(MainRoutes.HISTORY, MainRoutes.HISTORY, "История", Icons.Filled.History),
    TabItem(MainRoutes.SETTINGS, MainRoutes.SETTINGS, "Настройки", Icons.Filled.Settings)
)

@Composable
fun MainScaffold(
    startOnSettings: Boolean = false,
    navController: NavHostController = rememberNavController(),
    queueViewModel: QueueViewModel = hiltViewModel(),
    currentViewModel: CurrentAssignmentViewModel = hiltViewModel()
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val queueState by queueViewModel.state.collectAsState()
    val current by currentViewModel.current.collectAsState()
    var dismissedNewAssignmentId by rememberSaveable { mutableStateOf<String?>(null) }
    var openedReplacementId by rememberSaveable { mutableStateOf<String?>(null) }
    val newAssignment = current?.newSameDayAssignment
    val inheritedReplacement = newAssignment?.assignment?.basis != null
    val showNewAssignment = newAssignment != null && (!inheritedReplacement || newAssignment.id != openedReplacementId)
    val announcement = newAssignment?.takeIf { showNewAssignment }
    val openNewAssignment: () -> Unit = {
        newAssignment?.let {
            dismissedNewAssignmentId = it.id
            if (inheritedReplacement) openedReplacementId = it.id
            navController.navigate(MainRoutes.assignmentDetail(it.id)) { launchSingleTop = true }
        }
    }

    // Возврат в приложение — повод дослать то, что залипло в очереди, пока
    // телефон был без сети (автосинхронизации в объёме нет, см. AGENTS.md),
    // и забрать свежие разнарядки (диспетчер мог отменить или прислать новую).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        queueViewModel.onRetry()
        currentViewModel.refresh()
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == tab.selectionRoute } == true,
                        onClick = {
                            navController.navigate(tab.navTarget) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                // Вкладки разнарядок и рейсов не восстанавливаем:
                                // сохранённый экран показывал бы разнарядку,
                                // которая уже не текущая, и прятал новую. Вход
                                // через AssignmentsGate каждый раз решает заново.
                                restoreState = tab.navTarget != MainRoutes.TRIPS_GRAPH &&
                                    tab.navTarget != MainRoutes.ASSIGNMENTS_GRAPH
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        // consumeWindowInsets — иначе вложенные Scaffold у экранов внутри
        // NavHost (у них свой topBar) второй раз добавляют отступ под
        // статус-бар поверх уже применённого здесь: получался двойной
        // отступ сверху на каждом экране.
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            UpdateBanner()
            UnsentBanner(state = queueState, onRetry = queueViewModel::onRetry)
            current?.state?.let { CurrentAssignmentBar(state = it) }
            announcement?.let {
                TextButton(onClick = openNewAssignment, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (inheritedReplacement) "ЗАМЕНА МАШИНЫ — РАЗНАРЯДКА № ${it.label}. ПРОВЕРИТЬ МАШИНУ И РЕЙСЫ"
                        else "НОВАЯ РАЗНАРЯДКА № ${it.label} НА СЕГОДНЯ — ОТКРЫТЬ И ОЗНАКОМИТЬСЯ",
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            NavHost(
                navController = navController,
                startDestination = if (startOnSettings) MainRoutes.SETTINGS else MainRoutes.ASSIGNMENTS_GRAPH
            ) {
                navigation(startDestination = MainRoutes.ASSIGNMENTS, route = MainRoutes.ASSIGNMENTS_GRAPH) {
                    composable(MainRoutes.ASSIGNMENTS) {
                        AssignmentsGate(
                            mode = GateMode.ASSIGNMENTS,
                            onOpen = { id ->
                                navController.navigate(MainRoutes.assignmentDetail(id)) {
                                    popUpTo(MainRoutes.ASSIGNMENTS) { inclusive = true }
                                }
                            },
                            onSelectFromList = { id -> navController.navigate(MainRoutes.assignmentDetail(id)) }
                        )
                    }
                    composable(
                        MainRoutes.ASSIGNMENT_DETAIL,
                        arguments = listOf(navArgument("assignmentId") { type = NavType.StringType })
                    ) {
                        // Стрелка к списку — когда разнарядок больше одной, в
                        // том числе если вторая пришла, пока открыта первая
                        // (замена экипажа). При одной список не нужен.
                        val onBack: (() -> Unit)? = if ((current?.totalAssignments ?: 0) > 1) {
                            {
                                navController.navigate(MainRoutes.ASSIGNMENTS) {
                                    popUpTo(MainRoutes.ASSIGNMENTS_GRAPH)
                                    launchSingleTop = true
                                }
                            }
                        } else null
                        AssignmentScreen(onBack = onBack)
                    }
                }

                navigation(startDestination = MainRoutes.TRIPS, route = MainRoutes.TRIPS_GRAPH) {
                    composable(MainRoutes.TRIPS) {
                        val openRoadmap: (String) -> Unit = { id ->
                            navController.navigate(MainRoutes.roadmap(id)) {
                                popUpTo(MainRoutes.TRIPS) { inclusive = true }
                            }
                        }
                        AssignmentsGate(mode = GateMode.TRIPS, onOpen = openRoadmap, onSelectFromList = openRoadmap)
                    }
                    composable(
                        MainRoutes.ROADMAP,
                        arguments = listOf(navArgument("assignmentId") { type = NavType.StringType })
                    ) { entry ->
                        // Во вкладке только текущая разнарядка. Сменилась
                        // текущая (смена закончена, диспетчер прислал новую) —
                        // возвращаемся к развилке: там новая или пустой экран.
                        val shownId = entry.arguments?.getString("assignmentId")
                        val loaded = current
                        LaunchedEffect(loaded?.state?.id) {
                            if (loaded != null && loaded.state?.id != shownId) {
                                navController.navigate(MainRoutes.TRIPS) {
                                    popUpTo(MainRoutes.TRIPS_GRAPH)
                                    launchSingleTop = true
                                }
                            }
                        }
                        RoadmapScreen(onOpenAssignment = { id ->
                            navController.navigate(MainRoutes.assignmentDetail(id)) { launchSingleTop = true }
                        })
                    }
                }

                composable(MainRoutes.HISTORY) { HistoryScreen() }

                composable(MainRoutes.SETTINGS) { SettingsScreen() }
            }
        }
    }
    if (announcement != null && announcement.id != dismissedNewAssignmentId) {
        AlertDialog(
            onDismissRequest = { dismissedNewAssignmentId = newAssignment.id },
            title = { Text("Пришла ещё одна разнарядка") },
            text = { Text(if (inheritedReplacement)
                if (announcement.assignment.shiftStarted)
                    "Машину заменили. Откройте разнарядку № ${announcement.label} и проверьте новые рейсы. Смена продолжается; для рейсов нужны ключи от новой машины."
                else "Машину заменили, но 1С ещё не передала начало смены в новую разнарядку № ${announcement.label}. Откройте маршрут; этапы пока недоступны."
                else "Разнарядка № ${announcement.label} на сегодня. Откройте её, проверьте машину и рейсы, затем нажмите «Ознакомлен».") },
            confirmButton = {
                Button(
                    onClick = openNewAssignment,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.onError,
                        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.error
                    )
                ) { Text("Открыть разнарядку") }
            },
            dismissButton = {
                TextButton(onClick = { dismissedNewAssignmentId = newAssignment.id }) {
                    Text("Позже", color = androidx.compose.material3.MaterialTheme.colorScheme.onError)
                }
            },
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.error,
            titleContentColor = androidx.compose.material3.MaterialTheme.colorScheme.onError,
            textContentColor = androidx.compose.material3.MaterialTheme.colorScheme.onError
        )
    }
}
