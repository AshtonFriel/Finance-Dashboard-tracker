package com.financedashboard.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.financedashboard.app.ui.screens.AccountsScreen
import com.financedashboard.app.ui.screens.DashboardScreen
import com.financedashboard.app.ui.screens.DebtsScreen
import com.financedashboard.app.ui.screens.InflationScreen
import com.financedashboard.app.ui.screens.InvestScreen
import com.financedashboard.app.ui.screens.SettingsScreen
import com.financedashboard.app.ui.theme.FinanceDashboardTheme

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Destination("dashboard", "Home", Icons.Filled.Dashboard),
    Destination("inflation", "Inflation", Icons.Filled.TrendingDown),
    Destination("debts", "Debts", Icons.Filled.CreditCard),
    Destination("invest", "Invest", Icons.Filled.ShowChart),
    Destination("accounts", "Accounts", Icons.Filled.AccountBalance),
    Destination("settings", "Settings", Icons.Filled.Settings),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FinanceDashboardTheme {
                AppScaffold()
            }
        }
    }
}

@Composable
private fun AppScaffold(vm: AppViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                for (dest in destinations) {
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding),
        ) {
            composable("dashboard") { DashboardScreen(vm) }
            composable("inflation") { InflationScreen(vm) }
            composable("debts") { DebtsScreen(vm) }
            composable("invest") { InvestScreen(vm) }
            composable("accounts") { AccountsScreen(vm) }
            composable("settings") { SettingsScreen(vm) }
        }
    }
}
