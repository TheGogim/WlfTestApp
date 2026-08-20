package com.wlftest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wlftest.model.ShowType
import com.wlftest.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScrapersTestNav()
        }
    }
}

@Composable
private fun ScrapersTestNav() {
    val navController = rememberNavController()

    WlfTestTheme {
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                HomeScreen(navController = navController)
            }
            composable(
                route = "detail/{id}/{type}",
                arguments = listOf(
                    navArgument("id") { type = NavType.IntType },
                    navArgument("type") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getInt("id") ?: return@composable
                val typeStr = backStackEntry.arguments?.getString("type") ?: "MOVIE"
                val type = try {
                    ShowType.valueOf(typeStr)
                } catch (_: Exception) {
                    ShowType.MOVIE
                }
                DetailScreen(
                    navController = navController,
                    id = id,
                    type = type,
                )
            }
            composable("player") {
                PlayerScreen(navController = navController)
            }
        }
    }
}