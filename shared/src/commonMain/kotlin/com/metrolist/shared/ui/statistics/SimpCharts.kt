package com.metrolist.shared.ui.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 5-axis behavioral fingerprint model:
 * consistency: regularity of listening across days/hours
 * discovery: proportion of newly played songs
 * diversity: distribution across distinct artists
 * concentration: fraction of plays spent on top artist
 * replay: average repeat listens per track
 */
data class ListeningFingerprint(
    val consistency: Float,
    val discovery: Float,
    val diversity: Float,
    val concentration: Float,
    val replay: Float,
) {
    val axes: List<Float> get() = listOf(consistency, discovery, diversity, concentration, replay)
}

/**
 * Calculates behavioral fingerprint metrics based on local playback history totals.
 */
fun calculateFingerprint(
    totalPlays: Int,
    distinctArtists: Int,
    distinctTracks: Int,
    hourlyStats: Map<Int, Int>,
    dailyStats: Map<String, Int>,
): ListeningFingerprint {
    if (totalPlays <= 0) {
        return ListeningFingerprint(0.2f, 0.2f, 0.2f, 0.2f, 0.2f)
    }
    val activeDays = dailyStats.count { it.value > 0 }.toFloat()
    val totalObservedDays = dailyStats.size.coerceAtLeast(1).toFloat()
    val consistency = (activeDays / totalObservedDays).coerceIn(0.1f, 1.0f)

    val discovery = (distinctTracks.toFloat() / totalPlays.toFloat()).coerceIn(0.1f, 1.0f)
    val diversity = (distinctArtists.toFloat() / distinctTracks.coerceAtLeast(1).toFloat()).coerceIn(0.1f, 1.0f)

    val maxHourlyCount = hourlyStats.values.maxOrNull() ?: 1
    val concentration = (maxHourlyCount.toFloat() / totalPlays.toFloat() * 2.5f).coerceIn(0.1f, 1.0f)
    val replay = (1.0f - discovery * 0.8f).coerceIn(0.1f, 1.0f)

    return ListeningFingerprint(
        consistency = consistency,
        discovery = discovery,
        diversity = diversity,
        concentration = concentration,
        replay = replay
    )
}

/**
 * Twenty-four filled wedges around a central hole — SimpMusic's "Listening clock".
 * Each wedge represents an hour (0..23), scaled radially by listening intensity.
 */
@Composable
fun ListeningClockChart(
    playsByHour: List<Int>,
    modifier: Modifier = Modifier,
    diameter: Dp = 190.dp,
) {
    val hours = if (playsByHour.size == 24) playsByHour else List(24) { 0 }
    val peak = hours.indices.maxByOrNull { hours[it] } ?: 0
    val max = hours.maxOrNull()?.coerceAtLeast(1) ?: 1
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val accentColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(diameter)) {
            val outer = size.minDimension / 2f
            val inner = outer * 0.46f
            val gap = 2.2f
            hours.forEachIndexed { hour, count ->
                val start = hour * 15f + gap / 2f - 90f
                val sweep = 15f - gap
                drawWedge(center, inner, outer, start, sweep, trackColor)
                if (count > 0) {
                    val valueOuter = inner + (outer - inner) * (count.toFloat() / max)
                    val wedgeColor = if (count > max * 0.55f) accentColor else accentColor.copy(alpha = 0.50f)
                    drawWedge(center, inner, valueOuter, start, sweep, wedgeColor)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Peak Listening Hour",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = "${hourLabel(peak)} – ${hourLabel((peak + 1) % 24)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = "Plays in peak hour",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = "${hours[peak]} listens",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
    }
}

/** Draws one annulus sector (wedge) */
private fun DrawScope.drawWedge(
    center: Offset,
    inner: Float,
    outer: Float,
    startDeg: Float,
    sweepDeg: Float,
    color: Color,
) {
    val path = Path().apply {
        val steps = 6
        repeat(steps + 1) { i ->
            val a = ((startDeg + sweepDeg * i / steps) * PI / 180f).toFloat()
            val p = Offset(center.x + cos(a) * outer, center.y + sin(a) * outer)
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        for (i in steps downTo 0) {
            val a = ((startDeg + sweepDeg * i / steps) * PI / 180f).toFloat()
            lineTo(center.x + cos(a) * inner, center.y + sin(a) * inner)
        }
        close()
    }
    drawPath(path, color)
}

/**
 * Five-axis behavioral radar chart comparing current period against previous period.
 */
@Composable
fun FingerprintChart(
    current: ListeningFingerprint,
    previous: ListeningFingerprint? = null,
    modifier: Modifier = Modifier,
    diameter: Dp = 190.dp,
) {
    val labels = listOf("Consistency", "Discovery", "Diversity", "Focus", "Replay")
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f)
    val prevColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f)
    val accentColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(diameter)) {
            val radius = size.minDimension / 2f * 0.76f
            fun ring(fraction: Float) = polygonPath(center, radius * fraction, 5)
            listOf(0.33f, 0.66f, 1f).forEach { drawPath(ring(it), gridColor, style = Stroke(1.5f)) }
            repeat(5) { i ->
                drawLine(gridColor, center, axisPoint(center, radius, i, 5, 1f), strokeWidth = 1.5f)
            }
            previous?.let {
                drawPath(valuePath(center, radius, it.axes), prevColor, style = Stroke(2f))
            }
            val nowPath = valuePath(center, radius, current.axes)
            drawPath(nowPath, accentColor.copy(alpha = 0.25f))
            drawPath(nowPath, accentColor, style = Stroke(2.5f))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { i, label ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(76.dp)
                    )
                    Text(
                        text = "${(current.axes[i] * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun axisPoint(
    center: Offset,
    radius: Float,
    index: Int,
    count: Int,
    fraction: Float,
): Offset {
    val angle = (index.toFloat() / count) * 2f * PI.toFloat() - PI.toFloat() / 2f
    return Offset(center.x + cos(angle) * radius * fraction, center.y + sin(angle) * radius * fraction)
}

private fun polygonPath(
    center: Offset,
    radius: Float,
    sides: Int,
): Path = Path().apply {
    repeat(sides) { i ->
        val p = axisPoint(center, radius, i, sides, 1f)
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

private fun valuePath(
    center: Offset,
    radius: Float,
    values: List<Float>,
): Path = Path().apply {
    values.forEachIndexed { i, v ->
        val p = axisPoint(center, radius, i, values.size, v.coerceIn(0f, 1f))
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

private fun hourLabel(hour: Int): String = "${hour.toString().padStart(2, '0')}:00"

/**
 * Receiptify item representation.
 */
data class ReceiptLine(
    val plays: Int,
    val title: String,
    val artist: String,
    val durationMs: Long,
)

/**
 * Receiptify styled listening receipt paper with retro monospace design,
 * barcode simulation, dashed receipts lines, and listening totals.
 */
@Composable
fun ListeningReceiptView(
    lines: List<ReceiptLine>,
    periodLabel: String,
    totalPlays: Int,
    totalTimeMs: Long,
    modifier: Modifier = Modifier,
) {
    val monoStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        fontWeight = FontWeight.Medium
    )
    val paperColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val inkColor = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(paperColor)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "SIMPMETRO",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Black
            ),
            color = inkColor
        )
        Text(
            text = periodLabel.uppercase(),
            style = monoStyle.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        DashedReceiptDivider()

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("QTY", style = monoStyle.copy(fontWeight = FontWeight.Bold), color = inkColor)
            Text("TRACK", style = monoStyle.copy(fontWeight = FontWeight.Bold), color = inkColor)
            Text("AMT", style = monoStyle.copy(fontWeight = FontWeight.Bold), color = inkColor)
        }

        DashedReceiptDivider()

        lines.take(10).forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${line.plays}x",
                    style = monoStyle,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(36.dp)
                )
                Text(
                    text = "${line.title} - ${line.artist}".uppercase(),
                    style = monoStyle,
                    color = inkColor,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                )
                Text(
                    text = formatDuration(line.durationMs),
                    style = monoStyle,
                    color = inkColor
                )
            }
        }

        DashedReceiptDivider()

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("ITEM COUNT:", style = monoStyle.copy(fontWeight = FontWeight.Bold), color = inkColor)
            Text("$totalPlays", style = monoStyle.copy(fontWeight = FontWeight.Bold), color = inkColor)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("TOTAL TIME:", style = monoStyle.copy(fontWeight = FontWeight.Bold), color = inkColor)
            Text(formatDuration(totalTimeMs), style = monoStyle.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
        }

        DashedReceiptDivider()

        // Barcode visualization
        Text(
            text = "THANK YOU FOR LISTENING",
            style = monoStyle.copy(fontWeight = FontWeight.Bold),
            color = inkColor,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
        )
        ReceiptBarcode(modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
    }
}

@Composable
private fun DashedReceiptDivider() {
    val dividerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    Canvas(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp)) {
        drawLine(
            color = dividerColor,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 2f,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        )
    }
}

@Composable
private fun ReceiptBarcode(modifier: Modifier = Modifier) {
    val barColor = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = modifier.size(160.dp, 36.dp)) {
        val barCount = 38
        val spacing = size.width / barCount
        for (i in 0 until barCount) {
            if ((i * 7 + 3) % 5 != 0) {
                val thickness = if ((i * 3) % 4 == 0) 3.5f else 1.8f
                drawLine(
                    color = barColor,
                    start = Offset(i * spacing, 0f),
                    end = Offset(i * spacing, size.height),
                    strokeWidth = thickness
                )
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
