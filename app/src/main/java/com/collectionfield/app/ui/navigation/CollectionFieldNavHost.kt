package com.collectionfield.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.collectionfield.app.data.repository.AppContainer
import com.collectionfield.app.ui.screens.DailyPlanScreen
import com.collectionfield.app.ui.screens.DailyPlanViewModel
import com.collectionfield.app.ui.screens.HistoryScreen
import com.collectionfield.app.ui.screens.HistoryViewModel
import com.collectionfield.app.ui.screens.HomeScreen
import com.collectionfield.app.ui.screens.HomeViewModel
import com.collectionfield.app.ui.screens.LoginScreen
import com.collectionfield.app.ui.screens.LoginViewModel
import com.collectionfield.app.ui.screens.OutletListScreen
import com.collectionfield.app.ui.screens.OutletViewModel
import com.collectionfield.app.ui.screens.RouteMapScreen
import com.collectionfield.app.ui.screens.VisitActionViewModel
import com.collectionfield.app.ui.screens.simpleViewModelFactory

private object Route {
    const val LOGIN = "login"
    const val HOME = "home"
    const val OUTLETS = "outlets"
    const val HISTORY = "history"
    const val DAILY_PLAN = "daily_plan"
    const val ROUTE_MAP = "route_map"
}

@Composable
fun CollectionFieldNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val startDestination = if (container.authRepository.currentSession() == null) Route.LOGIN else Route.HOME

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Route.LOGIN) {
            val vm: LoginViewModel = viewModel(factory = simpleViewModelFactory { LoginViewModel(container) })
            LoginScreen(
                viewModel = vm,
                onLoggedIn = {
                    navController.navigate(Route.HOME) {
                        popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                    }
                },
            )
        }

        composable(Route.HOME) {
            val session = container.authRepository.currentSession()
            if (session == null) {
                navController.navigate(Route.LOGIN) {
                    popUpTo(Route.HOME) { inclusive = true }
                }
            } else {
                val vm: HomeViewModel = viewModel(
                    factory = simpleViewModelFactory { HomeViewModel(container, session) },
                )
                val visitActionVm: VisitActionViewModel = viewModel(
                    factory = simpleViewModelFactory { VisitActionViewModel(container) },
                )
                HomeScreen(
                    session = session,
                    viewModel = vm,
                    visitActionViewModel = visitActionVm,
                    themePreferences = container.themePreferences,
                    onOpenOutlets = { navController.navigate(Route.OUTLETS) },
                    onOpenHistory = { navController.navigate(Route.HISTORY) },
                    onOpenDailyPlan = { navController.navigate(Route.DAILY_PLAN) },
                    onOpenRouteMap = { navController.navigate(Route.ROUTE_MAP) },
                    onLoggedOut = {
                        navController.navigate(Route.LOGIN) {
                            popUpTo(Route.HOME) { inclusive = true }
                        }
                    },
                )
            }
        }

        composable(Route.DAILY_PLAN) {
            val session = container.authRepository.currentSession() ?: return@composable
            val vm: DailyPlanViewModel = viewModel(
                factory = simpleViewModelFactory { DailyPlanViewModel(container, session.uid) },
            )
            DailyPlanScreen(
                viewModel = vm,
                onViewMap = { navController.navigate(Route.ROUTE_MAP) }
            )
        }

        composable(Route.ROUTE_MAP) {
            val session = container.authRepository.currentSession() ?: return@composable
            val vm: DailyPlanViewModel = viewModel(
                factory = simpleViewModelFactory { DailyPlanViewModel(container, session.uid) },
            )
            RouteMapScreen(viewModel = vm)
        }

        composable(Route.OUTLETS) {
            val session = container.authRepository.currentSession() ?: return@composable
            val vm: OutletViewModel = viewModel(
                factory = simpleViewModelFactory { OutletViewModel(container, session.employeeCode) },
            )
            OutletListScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(Route.HISTORY) {
            val session = container.authRepository.currentSession() ?: return@composable
            val vm: HistoryViewModel = viewModel(
                factory = simpleViewModelFactory { HistoryViewModel(container, session.employeeCode) },
            )
            HistoryScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }
    }
}
