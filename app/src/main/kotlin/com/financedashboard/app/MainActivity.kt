package com.financedashboard.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.financedashboard.app.ui.screens.AccountsScreen
import com.financedashboard.app.ui.screens.DashboardScreen
import com.financedashboard.app.ui.screens.DebtsScreen
import com.financedashboard.app.ui.screens.FiscalCard
import com.financedashboard.app.ui.screens.GlyphTile
import com.financedashboard.app.ui.screens.Glyph
import com.financedashboard.app.ui.screens.InflationScreen
import com.financedashboard.app.ui.screens.InvestScreen
import com.financedashboard.app.ui.screens.SettingsScreen
import com.financedashboard.app.ui.theme.Fiscal
import com.financedashboard.app.ui.theme.FinanceDashboardTheme

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Destination("dashboard", "Home", Icons.Filled.Dashboard),
    Destination("debts", "Debt", Icons.Filled.CreditCard),
    Destination("inflation", "Income", Icons.Filled.TrendingDown),
    Destination("invest", "Invest", Icons.Filled.ShowChart),
    Destination("more", "More", Icons.Filled.MoreHoriz),
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
        containerColor = Fiscal.Background,
        bottomBar = {
            NavigationBar(containerColor = Fiscal.NavBackground) {
                for (dest in destinations) {
                    val selected = currentRoute == dest.route ||
                        (dest.route == "more" && currentRoute in listOf("accounts", "settings"))
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Fiscal.Accent,
                            selectedTextColor = Fiscal.Accent,
                            indicatorColor = Fiscal.AccentTint,
                            unselectedIconColor = Fiscal.TextMuted,
                            unselectedTextColor = Fiscal.TextMuted,
                        ),
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
            composable("dashboard") { DashboardScreen(vm, navController) }
            composable("debts") { DebtsScreen(vm) }
            composable("inflation") { InflationScreen(vm) }
            composable("invest") { InvestScreen(vm) }
            composable("more") { MoreScreen(navController) }
            composable("accounts") { AccountsScreen(vm) }
            composable("settings") { SettingsScreen(vm) }
        }
    }
}

@Composable
private fun MoreScreen(navController: NavHostController) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("More", style = MaterialTheme.typography.headlineSmall, color = Fiscal.TextPrimary)
        FiscalCard(onClick = { navController.navigate("accounts") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphTile(Fiscal.Sky, Glyph.SQUARE)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Accounts", style = MaterialTheme.typography.titleSmall, color = Fiscal.TextPrimary)
                    Text(
                        "Balances, sparklines, spending, reclassification",
                        style = MaterialTheme.typography.bodySmall,
                        color = Fiscal.TextSecondary,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Fiscal.TextMuted)
            }
        }
        FiscalCard(onClick = { navController.navigate("settings") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphTile(Fiscal.Amber, Glyph.CIRCLE)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Settings", style = MaterialTheme.typography.titleSmall, color = Fiscal.TextPrimary)
                    Text(
                        "CSV import, CPI table, privacy",
                        style = MaterialTheme.typography.bodySmall,
                        color = Fiscal.TextSecondary,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Fiscal.TextMuted)
            }
        }
    }
}
