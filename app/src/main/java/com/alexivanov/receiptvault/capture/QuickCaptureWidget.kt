package com.alexivanov.receiptvault.capture

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.alexivanov.receiptvault.R

/**
 * Home-screen widget with two buttons: scan a receipt or type one in. It shows
 * no amounts, so nothing private is visible on the home screen.
 */
class QuickCaptureWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    companion object {
        fun views(context: Context) = RemoteViews(context.packageName, R.layout.widget_quick_capture).apply {
            setOnClickPendingIntent(R.id.widget_scan, QuickCapture.pendingIntent(context, QuickAction.SCAN))
            setOnClickPendingIntent(R.id.widget_manual, QuickCapture.pendingIntent(context, QuickAction.MANUAL))
        }
    }
}
