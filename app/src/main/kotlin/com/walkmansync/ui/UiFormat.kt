package com.walkmansync.ui

import java.util.Locale

private val SIZE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

/** Bayt değerini okunur yazar (1024 tabanlı). Negatif/bilinmeyen değer için tire. */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return DASH
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < SIZE_UNITS.lastIndex) {
        value /= 1024.0
        unit++
    }
    if (unit == 0) return "$bytes B"
    val pattern = when {
        value >= 100.0 -> "%.0f %s"
        value >= 10.0 -> "%.1f %s"
        else -> "%.2f %s"
    }
    return String.format(Locale.getDefault(), pattern, value, SIZE_UNITS[unit])
}

/** Saniyedeki bayt hızı; ölçüm yoksa (0) tire. */
fun formatRate(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0) DASH else formatBytes(bytesPerSecond) + "/s"

/** Süre: 4:05 ya da 1:02:03. Negatif değer 0 sayılır. */
fun formatDuration(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0) + 500) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

/** Kalan süre; bilinmiyorsa tire. */
fun formatEta(millis: Long?): String = if (millis == null || millis < 0) DASH else formatDuration(millis)

/** Yüzde (0..100), tam sayı. */
fun formatPercent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100f).toInt()}%"

const val DASH = "—"
