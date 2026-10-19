package io.panelassistant.android.dashboard

/** Saturating Long addition shared by every dashboard traffic counter; clamps at [Long.MAX_VALUE]. */
internal fun saturatedAdd(left: Long, right: Long): Long =
    if (right >= Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

/** Per-second and millisecond projections used by live dashboard diagnostics. */
internal fun trafficRatePerSec(value: Long, sampleMs: Long): Double = value * 1000.0 / sampleMs.coerceAtLeast(1L)
internal fun trafficMicrosPerSecond(value: Long, sampleMs: Long): Double = value.toDouble() / sampleMs.coerceAtLeast(1L)
internal fun trafficMicrosToMs(value: Long): Double = value / 1000.0
