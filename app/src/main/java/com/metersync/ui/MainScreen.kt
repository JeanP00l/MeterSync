package com.metersync.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.metersync.utils.Logger
import android.net.Uri
import android.content.Intent

@Composable
fun MainScreen(
    navController: NavHostController,
    cameraLauncher: androidx.activity.result.ActivityResultLauncher<Intent>?,
    onCameraDataReady: (Uri, String) -> Unit
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Вход") },
                    label = { Text("Вход") },
                    selected = currentRoute == "login",
                    onClick = {
                        Logger.logUI("Bottom nav: Navigating to login")
                        navController.navigate("login") {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.List, contentDescription = "Список") },
                    label = { Text("Список") },
                    selected = currentRoute == "list",
                    onClick = {
                        Logger.logUI("Bottom nav: Navigating to list")
                        navController.navigate("list") {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "login",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("login") {
                Logger.logUI("Composing login screen")
                LoginScreen(onLoginSuccess = {
                    Logger.logUI("Login success, staying on login tab")
                    // Don't navigate automatically, let user switch tabs manually
                })
            }
            composable("list") {
                Logger.logUI("Composing address list screen")
                AddressListScreen(onOpenAddress = { addressId ->
                    Logger.logUI("Opening address detail for ID: $addressId")
                    navController.navigate("detail/$addressId")
                })
            }
            composable(
                route = "detail/{addressId}",
                arguments = listOf(navArgument("addressId") { type = NavType.LongType })
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getLong("addressId") ?: 0L
                Logger.logUI("Composing address detail screen for ID: $id")
                AddressDetailScreen(
                    addressId = id, 
                    onBack = {
                        Logger.logUI("Back button pressed")
                        navController.popBackStack()
                    },
                    cameraLauncher = cameraLauncher,
                    onCameraDataReady = onCameraDataReady
                )
            }
        }
    }
}