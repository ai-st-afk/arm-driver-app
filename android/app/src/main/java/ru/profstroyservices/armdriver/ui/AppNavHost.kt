package ru.profstroyservices.armdriver.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ru.profstroyservices.armdriver.ui.assignment.AssignmentScreen
import ru.profstroyservices.armdriver.ui.roadmap.RoadmapScreen
import ru.profstroyservices.armdriver.ui.setup.SetupScreen

private object Routes {
    const val SETUP = "setup"
    const val ASSIGNMENT = "assignment"
    const val ROADMAP = "roadmap"
}

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.SETUP) {
        composable(Routes.SETUP) {
            SetupScreen(onContinue = { navController.navigate(Routes.ASSIGNMENT) })
        }
        composable(Routes.ASSIGNMENT) {
            AssignmentScreen(onOpenRoadmap = { navController.navigate(Routes.ROADMAP) })
        }
        composable(Routes.ROADMAP) {
            RoadmapScreen()
        }
    }
}
