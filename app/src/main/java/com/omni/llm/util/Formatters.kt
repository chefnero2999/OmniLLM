package com.omni.llm.util

import java.util.Locale
import kotlin.math.abs

object Formatters {

    fun bytes(value: Long): String {
        if (value <= 0) return "0 MB"
        val gb = value.toDouble() / (1024.0 * 1024.0 * 1024.0)
        if (gb >= 1.0) return String.format(Locale.US, "%.2f GB", gb)
        val mb = value.toDouble() / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.0f MB", mb)
    }

    fun megabytes(value: Long): String = String.format(Locale.US, "%.1f GB", value / 1024.0)

    fun gbFromBytes(value: Long): Double = value.toDouble() / (1024.0 * 1024.0 * 1024.0)

    fun parameters(count: Long): String = when {
        count >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", count / 1_000_000_000.0)
        count >= 1_000_000 -> String.format(Locale.US, "%.0fM", count / 1_000_000.0)
        else -> count.toString()
    }

    fun tokensPerSecond(value: Double): String =
        if (value <= 0.0) "—" else String.format(Locale.US, "%.1f tok/s", value)

    fun duration(millis: Long): String = when {
        millis < 1000 -> "${millis}ms"
        millis < 60_000 -> String.format(Locale.US, "%.1fs", millis / 1000.0)
        else -> "${millis / 60_000}m ${(millis % 60_000) / 1000}s"
    }

    fun percent(fraction: Float): String = String.format(Locale.US, "%.0f%%", fraction * 100)

    /** Trims trailing zeroes so slider labels read "2048" rather than "2048.0". */
    fun compact(value: Number): String {
        val d = value.toDouble()
        return if (abs(d - d.toLong()) < 0.001) d.toLong().toString()
        else String.format(Locale.US, "%.2f", d)
    }
}
