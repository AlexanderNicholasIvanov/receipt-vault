package com.alexivanov.receiptvault.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.alexivanov.receiptvault.ui.detail.DetailScreen
import com.alexivanov.receiptvault.ui.home.HomeScreen
import com.alexivanov.receiptvault.ui.insights.InsightsScreen
import com.alexivanov.receiptvault.ui.review.CaptureSource
import com.alexivanov.receiptvault.ui.review.ReviewScreen
import com.alexivanov.receiptvault.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.Flow

private object Routes {
    const val HOME = "home"
    const val REVIEW = "review?uri={uri}&source={source}&id={id}"
    const val DETAIL = "receipt/{id}"
    const val INSIGHTS = "insights"
    const val SETTINGS = "settings"

    fun review(uri: Uri? = null, source: CaptureSource = CaptureSource.MANUAL, id: Long? = null): String =
        "review?uri=${Uri.encode(uri?.toString().orEmpty())}&source=${source.name}&id=${id ?: ""}"

    fun detail(id: Long) = "receipt/$id"
}

/**
 * @param sharedImages photos shared into the app from Gallery or elsewhere.
 */
@Composable
fun AppNav(sharedImages: Flow<Uri>, nav: NavHostController = rememberNavController()) {
    LaunchedEffect(sharedImages) {
        sharedImages.collect { uri -> nav.navigate(Routes.review(uri, CaptureSource.SHARED)) }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpen = { nav.navigate(Routes.detail(it)) },
                onCaptured = { uri, source -> nav.navigate(Routes.review(uri, source)) },
                onManual = { nav.navigate(Routes.review()) },
                onInsights = { nav.navigate(Routes.INSIGHTS) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.REVIEW,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType; defaultValue = "" },
                navArgument("source") { type = NavType.StringType; defaultValue = CaptureSource.MANUAL.name },
                navArgument("id") { type = NavType.StringType; defaultValue = "" },
            ),
        ) {
            ReviewScreen(
                onBack = { nav.popBackStack() },
                onSaved = { id ->
                    // Replace the editor with the saved receipt; editing an existing one just returns to it.
                    nav.popBackStack()
                    if (nav.currentDestination?.route != Routes.DETAIL) nav.navigate(Routes.detail(id))
                },
            )
        }
        composable(Routes.DETAIL, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            DetailScreen(id = id, onBack = { nav.popBackStack() }, onEdit = { nav.navigate(Routes.review(id = it)) })
        }
        composable(Routes.INSIGHTS) { InsightsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}
