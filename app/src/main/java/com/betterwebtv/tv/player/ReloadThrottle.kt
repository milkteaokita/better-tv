package com.betterwebtv.tv.player

/**
 * Cooldown guard for user-triggered channel reloads so rapid remote OK presses
 * do not send repeated page requests in a short window.
 */
class ReloadThrottle(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {
    private var lastReloadAtMs: Long = Long.MIN_VALUE

    /**
     * Attempts to consume a reload slot at [nowMs]. Returns true if allowed,
     * or false if still inside the active cooldown window.
     */
    fun tryAcquire(nowMs: Long): Boolean {
        if (lastReloadAtMs != Long.MIN_VALUE && nowMs - lastReloadAtMs < cooldownMs) {
            return false
        }
        lastReloadAtMs = nowMs
        return true
    }

    /** Returns remaining cooldown duration in milliseconds, or 0 if ready. */
    fun remainingCooldownMs(nowMs: Long): Long {
        if (lastReloadAtMs == Long.MIN_VALUE) return 0L
        val elapsed = nowMs - lastReloadAtMs
        return if (elapsed < cooldownMs) (cooldownMs - elapsed).coerceAtLeast(0L) else 0L
    }

    /** Clears the cooldown state, e.g. after switching to a different channel. */
    fun reset() {
        lastReloadAtMs = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS: Long = 3_000L
    }
}
