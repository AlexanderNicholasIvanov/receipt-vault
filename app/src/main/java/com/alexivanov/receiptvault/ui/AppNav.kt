package com.alexivanov.receiptvault.ui

import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.alexivanov.receiptvault.capture.QuickAction
import com.alexivanov.receiptvault.domain.PageUris
import com.alexivanov.receiptvault.ui.budgets.BudgetScreen
import com.alexivanov.receiptvault.ui.detail.DetailScreen
import com.alexivanov.receiptvault.ui.home.HomeScreen
import com.alexivanov.receiptvault.ui.insights.InsightsScreen
import com.alexivanov.receiptvault.ui.lock.LocalAppLocked
import com.alexivanov.receiptvault.ui.review.CaptureSource
import com.alexivanov.receiptvault.ui.review.ReviewScreen
import com.alexivanov.receiptvault.ui.settings.SettingsScreen
import com.alexivanov.receiptvault.ui.trash.TrashScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.emptyFlow

private object Routes {
    const val HOME = "home"
    const val REVIEW = "review?uri={uri}&source={source}&id={id}"
    const val DETAIL = "receipt/{id}"
    const val INSIGHTS = "insights"
    const val SETTINGS = "settings"
    const val TRASH = "trash"
    const val BUDGETS = "budgets"

    fun review(uris: List<Uri> = emptyList(), source: CaptureSource = CaptureSource.MANUAL, id: Long? = null): String =
        "review?uri=${Uri.encode(PageUris.encode(uris.map(Uri::toString)))}&source=${source.name}&id=${id ?: ""}"

    fun detail(id: Long) = "receipt/$id"
}

/**
 * @param sharedImages photos or a PDF shared into the app; several photos are pages of one receipt.
 */
@Composable
fun AppNav(
    sharedImages: Flow<List<Uri>>,
    openReceipts: Flow<Long> = emptyFlow(),
    quickActions: Flow<QuickAction> = emptyFlow(),
    nav: NavHostController = rememberNavController(),
) {
    // Scans are launched by Home, which owns the scanner; it takes each request exactly once.
    val scanRequests = remember { Channel<Unit>(Channel.CONFLATED) }
    val scanRequestFlow = remember { scanRequests.receiveAsFlow() }
    var reviewHasUnsavedWork by remember { mutableStateOf(false) }
    // A widget, tile or shortcut tapped while a receipt is half filled in; waits for an answer.
    var waitingAction by remember { mutableStateOf<QuickAction?>(null) }
    fun start(action: QuickAction) {
        nav.popBackStack(Routes.HOME, inclusive = false)
        when (action) {
            QuickAction.SCAN -> scanRequests.trySend(Unit)
            QuickAction.MANUAL -> nav.navigate(Routes.review())
        }
    }
    LaunchedEffect(quickActions) {
        quickActions.collect { action -> if (reviewHasUnsavedWork) waitingAction = action else start(action) }
    }
    waitingAction?.takeUnless { LocalAppLocked.current }?.let { action ->
        AlertDialog(
            onDismissRequest = { waitingAction = null },
            title = { Text("Discard this receipt?") },
            text = { Text("You have a receipt in progress. Starting a new one won't save it.") },
            confirmButton = { TextButton(onClick = { waitingAction = null; start(action) }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { waitingAction = null }) { Text("Keep editing") } },
        )
    }
    LaunchedEffect(sharedImages) {
        sharedImages.collect { uris -> nav.navigate(Routes.review(uris, CaptureSource.SHARED)) }
    }
    LaunchedEffect(openReceipts) {
        openReceipts.collect { id -> nav.navigate(Routes.detail(id)) { launchSingleTop = true } }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { entry ->
            HomeScreen(
                onOpen = { entry.ifResumed { nav.navigate(Routes.detail(it)) } },
                // Not guarded: scanner and picker results can arrive before the screen is resumed.
                onCaptured = { uris, source -> nav.navigate(Routes.review(uris, source)) },
                onManual = { entry.ifResumed { nav.navigate(Routes.review()) } },
                onInsights = { entry.ifResumed { nav.navigate(Routes.INSIGHTS) } },
                onSettings = { entry.ifResumed { nav.navigate(Routes.SETTINGS) } },
                scanRequests = scanRequestFlow,
            )
        }
        composable(
            Routes.REVIEW,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType; defaultValue = "" },
                navArgument("source") { type = NavType.StringType; defaultValue = CaptureSource.MANUAL.name },
                navArgument("id") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            ReviewScreen(
                onBack = { entry.ifResumed { nav.popBackStack() } },
                onUnsavedWork = { reviewHasUnsavedWork = it },
                onSaved = { id ->
                    // Replace the editor with the saved receipt; editing an existing one just returns to it.
                    nav.popBackStack()
                    if (nav.currentDestination?.route != Routes.DETAIL) nav.navigate(Routes.detail(id))
                },
            )
        }
        composable(Routes.DETAIL, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            DetailScreen(id = id, onBack = { entry.ifResumed { nav.popBackStack() } }, onEdit = { entry.ifResumed { nav.navigate(Routes.review(id = it)) } })
        }
        composable(Routes.INSIGHTS) { entry -> InsightsScreen(onBack = { entry.ifResumed { nav.popBackStack() } }) }
        composable(Routes.SETTINGS) { entry ->
            SettingsScreen(onBack = { entry.ifResumed { nav.popBackStack() } }, onTrash = { entry.ifResumed { nav.navigate(Routes.TRASH) } }, onBudgets = { entry.ifResumed { nav.navigate(Routes.BUDGETS) } })
        }
        composable(Routes.BUDGETS) { entry -> BudgetScreen(onBack = { entry.ifResumed { nav.popBackStack() } }) }
        composable(Routes.TRASH) { entry -> TrashScreen(onBack = { entry.ifResumed { nav.popBackStack() } }) }
    }
}

/**
 * Runs [action] only while this screen is the one showing. A second tap on Back while the
 * screen is already leaving would otherwise pop the screen beneath it too, down to a blank one.
 */
private fun NavBackStackEntry.ifResumed(action: () -> Unit) {
    if (lifecycle.currentState == Lifecycle.State.RESUMED) action()
}
