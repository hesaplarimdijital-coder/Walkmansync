package com.walkmansync.core.engine

import com.walkmansync.core.runtime.Clock

/**
 * Kayan pencereli hız ölçer. Örnekler (monoton zaman, kümülatif bayt) çiftleridir; pencere dışına çıkan
 * eski örnekler atılır, ancak pencere başlangıcını kapsayan tek bir örnek tutulur.
 * Tek coroutine tarafından kullanılır (thread-safe değildir).
 */
class SpeedMeter(private val clock: Clock, windowMs: Long) {
    private val windowNanos = windowMs * 1_000_000L
    private val times = ArrayDeque<Long>()
    private val bytes = ArrayDeque<Long>()

    /** [cumulativeBytes] şimdiye dek yazılan toplam bayt (azalmaz). */
    fun record(cumulativeBytes: Long) {
        val now = clock.monotonicNanos()
        if (bytes.isNotEmpty() && cumulativeBytes < bytes.last()) reset()
        times.addLast(now)
        bytes.addLast(cumulativeBytes)
        while (times.size > 2 && now - times[1] >= windowNanos) {
            times.removeFirst()
            bytes.removeFirst()
        }
    }

    /** Bayt/sn; yeterli örnek yoksa 0. */
    fun bytesPerSecond(): Long {
        if (times.size < 2) return 0L
        val dt = times.last() - times.first()
        if (dt <= 0L) return 0L
        val db = bytes.last() - bytes.first()
        if (db <= 0L) return 0L
        return (db.toDouble() * 1_000_000_000.0 / dt.toDouble()).toLong()
    }

    fun reset() {
        times.clear()
        bytes.clear()
    }
}
