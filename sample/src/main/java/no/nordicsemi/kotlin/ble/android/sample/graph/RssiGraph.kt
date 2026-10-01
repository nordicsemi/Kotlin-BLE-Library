/*
 * Copyright (c) 2026, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list
 * of conditions and the following disclaimer in the documentation and/or other materials
 * provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be
 * used to endorse or promote products derived from this software without specific prior
 * written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
 * OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package no.nordicsemi.kotlin.ble.android.sample.graph

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// This file is self-contained and depends only on Compose. Copy it, if you need an RSSI graph.

/**
 * Holds the RSSI samples shown by [RssiGraph].
 *
 * Samples are added with [add], which may be called from any thread. Samples of a device
 * received within [averagingWindow] are averaged into a single point. Points older than
 * [timeWindow] are removed.
 *
 * Each device gets a distinct color, assigned in the order the devices were added.
 *
 * @param timeWindow The time span shown on the graph.
 * @param timeout If no sample was received from a device for this time, the line is broken
 * and the device is reported as inactive.
 * @param averagingWindow Samples received within this time are averaged into a single point.
 * @param palette Colors assigned to devices. When exhausted, more colors are generated.
 * @param clock The time source, in milliseconds. Samples added without a time use it as well.
 */
@Stable
class RssiGraphState(
    val timeWindow: Duration = 30.seconds,
    val timeout: Duration = 4.seconds,
    val averagingWindow: Duration = 200.milliseconds,
    private val palette: List<Color> = RssiGraphDefaults.Palette,
    val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private class Point(val time: Long, var sum: Int, var count: Int) {
        val rssi: Float get() = sum.toFloat() / count
    }

    private class Series(val color: Color) {
        val points = ArrayDeque<Point>()
        var lastRssi: Int = 0
        var lastTime: Long = Long.MIN_VALUE
    }

    private val lock = Any()
    private val series = LinkedHashMap<Any, Series>()

    /**
     * Adds an RSSI sample of the device with the given key.
     *
     * @param key The key of the device, for example its address.
     * @param rssi The RSSI value, in dBm.
     * @param time The time the sample was received, in [clock] time base.
     */
    fun add(key: Any, rssi: Int, time: Long = clock()) = synchronized(lock) {
        val s = series.getOrPut(key) { Series(colorAt(series.size)) }
        val last = s.points.lastOrNull()
        if (last != null && time - last.time < averagingWindow.inWholeMilliseconds) {
            last.sum += rssi
            last.count++
        } else {
            s.points.addLast(Point(time, rssi, 1))
        }
        s.lastRssi = rssi
        s.lastTime = time
        prune(s, time)
    }

    /** Returns the color of the device with the given key. */
    fun colorOf(key: Any): Color = synchronized(lock) {
        series[key]?.color ?: Color.Unspecified
    }

    /** Returns the last RSSI of the device, or `null` if it is inactive. */
    fun lastRssi(key: Any, now: Long = clock()): Int? = synchronized(lock) {
        series[key]
            ?.takeIf { now - it.lastTime < timeout.inWholeMilliseconds }
            ?.lastRssi
    }

    /** Removes all samples and colors. */
    fun clear() = synchronized(lock) {
        series.clear()
    }

    private fun prune(s: Series, now: Long) {
        // Keep one point to the left of the graph, so that the line enters it from the edge.
        val limit = now - timeWindow.inWholeMilliseconds - timeout.inWholeMilliseconds
        while (s.points.size > 1 && s.points[1].time < limit) {
            s.points.removeFirst()
        }
    }

    private fun colorAt(index: Int): Color =
        palette.getOrNull(index)
            // Use the golden angle to get hues far from the previous ones.
            ?: Color.hsv(hue = (index * 137.508f) % 360f, saturation = 0.7f, value = 0.85f)

    // Buffers passed to forEachSeries, reused to avoid allocations on every frame.
    private var timeBuffer = LongArray(0)
    private var rssiBuffer = FloatArray(0)

    /**
     * Calls [block] for each device, with the selected ones last, while holding the lock.
     *
     * The arrays passed to [block] are reused and valid only for `count` elements.
     */
    internal fun forEachSeries(
        now: Long,
        selected: Set<Any>,
        block: (color: Color, isSelected: Boolean, times: LongArray, rssi: FloatArray, count: Int) -> Unit,
    ) = synchronized(lock) {
        // Devices may stop advertising, so prune here too.
        series.values.forEach { prune(it, now) }
        val ordered = if (selected.isEmpty()) series.entries else series.entries.sortedBy { it.key in selected }
        ordered.forEach { (key, s) ->
            val count = s.points.size
            if (count == 0) return@forEach
            if (timeBuffer.size < count) {
                timeBuffer = LongArray(count * 2)
                rssiBuffer = FloatArray(count * 2)
            }
            s.points.forEachIndexed { i, point ->
                timeBuffer[i] = point.time
                rssiBuffer[i] = point.rssi
            }
            block(s.color, key in selected, timeBuffer, rssiBuffer, count)
        }
    }

    /** Returns the RSSI range of all points, or `null` if there are none. */
    internal fun rssiRange(): ClosedFloatingPointRange<Float>? = synchronized(lock) {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        series.values.forEach { s ->
            s.points.forEach {
                lo = min(lo, it.rssi)
                hi = max(hi, it.rssi)
            }
        }
        if (lo > hi) null else lo..hi
    }
}

/**
 * Colors used by [RssiGraph].
 *
 * @property grid The color of the grid lines.
 * @property label The color of the axis labels.
 * @property inactive The color of devices not selected, when some devices are selected.
 */
@Immutable
data class RssiGraphColors(
    val grid: Color,
    val label: Color,
    val inactive: Color,
)

object RssiGraphDefaults {
    /** Default colors of devices, distinct in light and dark theme. */
    val Palette = listOf(
        Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFB8C00),
        Color(0xFF8E24AA), Color(0xFF00ACC1), Color(0xFFD81B60), Color(0xFF7CB342),
        Color(0xFF5E35B1), Color(0xFFFFB300), Color(0xFF00897B), Color(0xFF6D4C41),
        Color(0xFF3949AB), Color(0xFFC0CA33), Color(0xFFF4511E), Color(0xFF039BE5),
    )

    @Composable
    fun colors(
        grid: Color = MaterialTheme.colorScheme.outlineVariant,
        label: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        inactive: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
    ) = RssiGraphColors(grid, label, inactive)
}

/**
 * A graph of RSSI values in time.
 *
 * The graph moves to the left with constant speed. The right edge is "now". Each point is
 * an averaged sample, and points of a device are connected unless they are further apart
 * than [RssiGraphState.timeout].
 *
 * @param state The state with the samples.
 * @param modifier The modifier.
 * @param selected Keys of the selected devices. If not empty, other devices are drawn in
 * [RssiGraphColors.inactive] color, below the selected ones.
 * @param paused When `true`, the graph stops moving.
 * @param rssiRange The minimum RSSI range shown. It is extended if values are outside it.
 * @param colors The colors.
 * @param labelStyle The style of the axis labels.
 */
@Composable
fun RssiGraph(
    state: RssiGraphState,
    modifier: Modifier = Modifier,
    selected: Set<Any> = emptySet(),
    paused: Boolean = false,
    rssiRange: IntRange = -100..-30,
    colors: RssiGraphColors = RssiGraphDefaults.colors(),
    labelStyle: TextStyle = MaterialTheme.typography.labelSmall,
) {
    val textMeasurer = rememberTextMeasurer()
    val style = labelStyle.copy(color = colors.label)
    // Measuring text is expensive, and the labels are drawn on every frame.
    // The cache of the TextMeasurer is too small to keep all of them.
    val labels = remember(textMeasurer, style) { HashMap<String, TextLayoutResult>() }
    fun label(text: String) = labels.getOrPut(text) { textMeasurer.measure(text, style) }

    // The time of the right edge of the graph, updated every frame.
    // It is read only in the draw phase, so moving the graph does not cause recomposition.
    var now by remember(state) { mutableLongStateOf(state.clock()) }
    LaunchedEffect(state, paused) {
        if (!paused) {
            while (true) {
                withFrameMillis { now = state.clock() }
            }
        }
    }

    // Reused between frames to avoid allocations.
    val buffers = remember { DrawBuffers() }

    Canvas(modifier = modifier) {
        val now = now
        val window = state.timeWindow.inWholeMilliseconds.toFloat()
        val timeout = state.timeout.inWholeMilliseconds

        // Vertical range, rounded to 10 dB.
        val data = state.rssiRange()
        val top = max(rssiRange.last.toFloat(), data?.let { ceil(it.endInclusive / 10f) * 10f } ?: Float.NEGATIVE_INFINITY)
        val bottom = min(rssiRange.first.toFloat(), data?.let { floor(it.start / 10f) * 10f } ?: Float.POSITIVE_INFINITY)
        val rssiStep = if (top - bottom > 80f) 20 else 10

        // Plot area, leaving space for labels.
        val labelPadding = 4.dp.toPx()
        val yLabelWidth = label("-100").size.width
        val xLabelHeight = label("0").size.height
        val left = yLabelWidth + labelPadding
        val right = size.width - 1.dp.toPx()
        val plotTop = xLabelHeight / 2f
        val plotBottom = size.height - xLabelHeight - labelPadding
        fun x(time: Long) = right - (now - time) / window * (right - left)
        fun y(rssi: Float) = plotTop + (top - rssi) / (top - bottom) * (plotBottom - plotTop)

        // Horizontal grid lines with RSSI labels.
        var r = top.toInt()
        while (r >= bottom) {
            val ry = y(r.toFloat())
            drawLine(colors.grid, Offset(left, ry), Offset(right, ry), strokeWidth = 1.dp.toPx())
            val label = label(r.toString())
            drawText(label, topLeft = Offset(left - labelPadding - label.size.width, ry - label.size.height / 2f))
            r -= rssiStep
        }

        // Vertical grid lines with time labels.
        val timeStep = listOf(1, 2, 5, 10, 15, 30, 60, 120, 300)
            .first { window / (it * 1000f) <= 6f } * 1000L
        var t = 0L
        while (t <= window) {
            val tx = x(now - t)
            drawLine(colors.grid, Offset(tx, plotTop), Offset(tx, plotBottom), strokeWidth = 1.dp.toPx())
            val label = label(if (t == 0L) "now" else "-${t / 1000}s")
            val lx = (tx - label.size.width / 2f).coerceIn(left, size.width - label.size.width)
            drawText(label, topLeft = Offset(lx, plotBottom + labelPadding))
            t += timeStep
        }

        // Lines and dots of each device.
        val lineWidth = 1.5.dp.toPx()
        val selectedLineWidth = 2.5.dp.toPx()
        val dotSize = 4.dp.toPx()
        val selectedDotSize = 5.dp.toPx()
        val hasSelection = selected.isNotEmpty()
        clipRect(left = left, top = 0f, right = size.width, bottom = plotBottom + dotSize) {
            state.forEachSeries(now, selected) { color, isSelected, times, rssi, count ->
                val c = if (!hasSelection || isSelected) color else colors.inactive

                if (buffers.dots.size < count * 2) {
                    buffers.dots = FloatArray(count * 4)
                    buffers.lines = FloatArray(count * 8)
                }
                val dots = buffers.dots
                val lines = buffers.lines
                var lineCount = 0
                for (i in 0 until count) {
                    val px = x(times[i])
                    val py = y(rssi[i])
                    if (i > 0 && times[i] - times[i - 1] < timeout) {
                        lines[lineCount * 4] = dots[(i - 1) * 2]
                        lines[lineCount * 4 + 1] = dots[(i - 1) * 2 + 1]
                        lines[lineCount * 4 + 2] = px
                        lines[lineCount * 4 + 3] = py
                        lineCount++
                    }
                    dots[i * 2] = px
                    dots[i * 2 + 1] = py
                }
                // Separate line segments are drawn on the GPU. A single stroked Path with many
                // points would be rasterized by Skia on the CPU, which is much slower.
                drawIntoCanvas { canvas ->
                    val linePaint = buffers.linePaint
                    linePaint.color = c
                    linePaint.strokeWidth = if (isSelected) selectedLineWidth else lineWidth
                    canvas.nativeCanvas.drawLines(lines, 0, lineCount * 4, linePaint.asFrameworkPaint())

                    val dotPaint = buffers.dotPaint
                    dotPaint.color = c
                    dotPaint.strokeWidth = if (isSelected) selectedDotSize else dotSize
                    canvas.nativeCanvas.drawPoints(dots, 0, count * 2, dotPaint.asFrameworkPaint())
                }
            }
        }
    }
}

private class DrawBuffers {
    val linePaint = Paint().apply { strokeCap = StrokeCap.Butt }
    val dotPaint = Paint().apply { strokeCap = StrokeCap.Round }
    var lines = FloatArray(0)
    var dots = FloatArray(0)
}
