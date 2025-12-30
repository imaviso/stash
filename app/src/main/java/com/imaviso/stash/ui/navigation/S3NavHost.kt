package com.imaviso.stash.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.imaviso.stash.ui.screens.BucketsScreen
import com.imaviso.stash.ui.screens.ConfigScreen
import com.imaviso.stash.ui.screens.ObjectsScreen
import com.imaviso.stash.ui.screens.TransfersScreen
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(
    val route: String,
) {
    data object Buckets : Screen("buckets")

    data object Config : Screen("config")

    data object Objects : Screen("objects/{bucketName}") {
        fun createRoute(bucketName: String): String {
            val encoded = URLEncoder.encode(bucketName, StandardCharsets.UTF_8.toString())
            return "objects/$encoded"
        }
    }

    data object Transfers : Screen("transfers/{bucketName}") {
        fun createRoute(bucketName: String): String {
            val encoded = URLEncoder.encode(bucketName, StandardCharsets.UTF_8.toString())
            return "transfers/$encoded"
        }
    }
}

@Composable
fun S3NavHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Buckets.route,
    ) {
        composable(Screen.Buckets.route) {
            BucketsScreen(
                onNavigateToConfig = {
                    navController.navigate(Screen.Config.route)
                },
                onNavigateToBucket = { bucketName ->
                    navController.navigate(Screen.Objects.createRoute(bucketName))
                },
            )
        }

        composable(Screen.Config.route) {
            ConfigScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
            )
        }

        composable(
            route = Screen.Objects.route,
            arguments =
                listOf(
                    navArgument("bucketName") { type = NavType.StringType },
                ),
        ) { backStackEntry ->
            val bucketName = backStackEntry.arguments?.getString("bucketName") ?: ""
            val decodedName = URLDecoder.decode(bucketName, StandardCharsets.UTF_8.toString())
            ObjectsScreen(
                bucketName = decodedName,
                onNavigateBack = {
                    navController.popBackStack()
                },
            )
        }
    }
}
