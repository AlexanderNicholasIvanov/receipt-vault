package com.alexivanov.receiptvault.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alexivanov.receiptvault.ui.money
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** The last 6 months as a bar chart or, for anyone who'd rather read numbers, a table. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MonthlyChartCard(data: List<Pair<YearMonth, Long>>, currency: String, converted: Boolean) {
    var asTable by rememberSaveable { mutableIntStateOf(0) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val title = @Composable {
                Text("Monthly spending", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(
                    "Last 6 months · $currency" + if (converted) " · other currencies converted" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val toggle = @Composable {
                SingleChoiceSegmentedButtonRow {
                    listOf("Chart", "Table").forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = asTable == i,
                            onClick = { asTable = i },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                            icon = {},
                        ) { Text(label) }
                    }
                }
            }
            // Large text needs the whole width for the title, so the toggle goes underneath.
            if (isLargeFont()) {
                title()
                Spacer(Modifier.height(8.dp))
                toggle()
            } else {
                Row {
                    Column(Modifier.weight(1f)) { title() }
                    toggle()
                }
            }
            Spacer(Modifier.height(12.dp))
            if (asTable == 1) MonthlyTable(data, currency) else MonthlyBars(data, currency)
        }
    }
}

private fun monthName(m: YearMonth, locale: Locale) = "${m.month.getDisplayName(TextStyle.FULL, locale)} ${m.year}"

/**
 * One series, one hue: bars rounded only at the data end and anchored to the baseline, and
 * tap-to-read, since touch has no hover. This month isn't over, so its bar is striped and
 * labelled "so far" rather than looking like a drop. Each bar is its own TalkBack stop.
 */
@Composable
private fun MonthlyBars(data: List<Pair<YearMonth, Long>>, currency: String) {
    var selected by remember(data) { mutableIntStateOf(data.lastIndex) }
    val barColor = MaterialTheme.colorScheme.primary
    val mutedBar = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val max = data.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val locale = LocalConfiguration.current.locales[0]
    val current = data.lastIndex

    Column {
        data.getOrNull(selected)?.let { (m, v) ->
            Text(
                monthName(m, locale) + if (selected == current) " · so far" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(money(v, currency), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(140.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val slot = size.width / data.size
                val barWidth = (slot * 0.5f).coerceAtMost(28.dp.toPx())
                val radius = 4.dp.toPx()
                val baseline = size.height - 1.dp.toPx()
                data.forEachIndexed { i, (_, value) ->
                    val h = (value.toFloat() / max) * (baseline - 4.dp.toPx())
                    if (h <= 0f) return@forEachIndexed
                    val left = i * slot + (slot - barWidth) / 2
                    val path = Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = left, top = baseline - h, right = left + barWidth, bottom = baseline,
                                topLeftCornerRadius = CornerRadius(radius), topRightCornerRadius = CornerRadius(radius),
                                bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
                            ),
                        )
                    }
                    val color = if (i == selected) barColor else mutedBar
                    if (i == current) {
                        // Part month: a pale fill with 45° stripes, so it reads as unfinished without color alone.
                        drawPath(path, color.copy(alpha = color.alpha * 0.35f))
                        clipPath(path) {
                            val step = 6.dp.toPx()
                            var x = left - h
                            while (x < left + barWidth) {
                                drawLine(color, Offset(x, baseline), Offset(x + h, baseline - h), strokeWidth = 2.dp.toPx())
                                x += step
                            }
                        }
                    } else {
                        drawPath(path, color)
                    }
                }
                drawLine(axisColor, Offset(0f, baseline), Offset(size.width, baseline), strokeWidth = 1.dp.toPx())
            }
            // Touch targets the full height of each column, larger than the bars themselves.
            Row(Modifier.fillMaxSize()) {
                data.forEachIndexed { i, (m, v) ->
                    val spoken = monthName(m, locale) + (if (i == current) ", so far" else "") + ": " + money(v, currency)
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .selectable(selected = i == selected, role = Role.Tab, onClick = { selected = i })
                            .semantics { contentDescription = spoken },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).clearAndSetSemantics {}) {
            data.forEachIndexed { i, (m, _) ->
                Column(Modifier.weight(1f)) {
                    Text(
                        m.month.getDisplayName(TextStyle.SHORT, locale),
                        Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (i == selected) FontWeight.SemiBold else null,
                        color = if (i == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (i == current) {
                        Text(
                            "so far",
                            Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The same 6 months as rows of text, newest first. */
@Composable
private fun MonthlyTable(data: List<Pair<YearMonth, Long>>, currency: String) {
    val locale = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        data.asReversed().forEachIndexed { i, (m, v) ->
            if (i > 0) HorizontalDivider()
            Row(Modifier.semantics(mergeDescendants = true) {}) {
                Text(monthName(m, locale) + if (i == 0) " (so far)" else "", Modifier.weight(1f))
                Text(money(v, currency), fontWeight = FontWeight.Medium)
            }
        }
    }
}
