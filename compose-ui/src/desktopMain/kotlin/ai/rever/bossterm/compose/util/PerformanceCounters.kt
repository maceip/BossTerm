package ai.rever.bossterm.compose.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight in-process counters for performance observability.
 */
object PerformanceCounters {
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    fun increment(name: String, delta: Long = 1L) {
        counters.computeIfAbsent(name) { AtomicLong(0) }.addAndGet(delta)
    }

    fun get(name: String): Long = counters[name]?.get() ?: 0L

    fun snapshot(): Map<String, Long> = counters.entries.associate { it.key to it.value.get() }

    fun reset() {
        counters.clear()
    }
}
