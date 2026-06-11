package com.dmd.scrollguard.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

private val Context.store by preferencesDataStore(name = "scrollguard")

/**
 * Single source of truth for settings + daily session state.
 *
 * For M1 simplicity some reads are blocking (runBlocking) — they're tiny
 * preference reads on a background-ish path; fine for a personal tool,
 * revisit if it ever janks.
 */
class SessionStore(private val context: Context) {

    companion object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val DIM_ENABLED = booleanPreferencesKey("dim_enabled")
        val DIM_INTENSITY = floatPreferencesKey("dim_intensity")          // 0.0..0.8
        val GATE_ENABLED = booleanPreferencesKey("gate_enabled")
        val GATE_PAUSE_SEC = intPreferencesKey("gate_pause_sec")
        val GATE_COOLDOWN_MIN = intPreferencesKey("gate_cooldown_min")
        val CHECKIN_INTERVAL_MIN = intPreferencesKey("checkin_interval_min")
        val BLOCK_MINUTES = intPreferencesKey("block_minutes")
        val MUSIC_URI = stringPreferencesKey("music_uri")                 // deep link to resting playlist

        val SECONDS_TODAY = longPreferencesKey("seconds_today")
        val LAST_RESET_DAY = stringPreferencesKey("last_reset_day")      // ISO date
        val BLOCK_UNTIL = longPreferencesKey("block_until")              // epoch millis
        val LAST_GATE_AT = longPreferencesKey("last_gate_at")            // epoch millis
        val SESSION_INTENT = stringPreferencesKey("session_intent")      // what the user said at the gate
    }

    data class Config(
        val enabled: Boolean = true,
        val dimEnabled: Boolean = true,
        val dimIntensity: Float = 0.45f,
        val gateEnabled: Boolean = true,
        val gatePauseSec: Int = 3,
        val gateCooldownMin: Int = 5,
        val checkinIntervalMin: Int = 10,
        val blockMinutes: Int = 5,
        val musicUri: String = "",
    )

    val configFlow: Flow<Config> = context.store.data.map { p ->
        Config(
            enabled = p[ENABLED] ?: true,
            dimEnabled = p[DIM_ENABLED] ?: true,
            dimIntensity = p[DIM_INTENSITY] ?: 0.45f,
            gateEnabled = p[GATE_ENABLED] ?: true,
            gatePauseSec = p[GATE_PAUSE_SEC] ?: 3,
            gateCooldownMin = p[GATE_COOLDOWN_MIN] ?: 5,
            checkinIntervalMin = p[CHECKIN_INTERVAL_MIN] ?: 10,
            blockMinutes = p[BLOCK_MINUTES] ?: 5,
            musicUri = p[MUSIC_URI] ?: "",
        )
    }

    fun configNow(): Config = runBlocking { configFlow.first() }

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.store.edit { it[key] = value }
    }

    // ---------- daily timer ----------

    /** Adds active feed seconds, resetting first if the day rolled over. */
    fun addSecondsBlocking(delta: Long): Long = runBlocking {
        var total = 0L
        context.store.edit { p ->
            val today = LocalDate.now().toString()
            if (p[LAST_RESET_DAY] != today) {
                p[LAST_RESET_DAY] = today
                p[SECONDS_TODAY] = 0L
            }
            total = (p[SECONDS_TODAY] ?: 0L) + delta
            p[SECONDS_TODAY] = total
        }
        total
    }

    fun secondsTodayBlocking(): Long = runBlocking {
        val p = context.store.data.first()
        if (p[LAST_RESET_DAY] != LocalDate.now().toString()) 0L else p[SECONDS_TODAY] ?: 0L
    }

    // ---------- block window ----------

    fun startBlockBlocking(minutes: Int): Long = runBlocking {
        val until = System.currentTimeMillis() + minutes * 60_000L
        context.store.edit { it[BLOCK_UNTIL] = until }
        until
    }

    fun blockUntilBlocking(): Long = runBlocking {
        context.store.data.first()[BLOCK_UNTIL] ?: 0L
    }

    fun isBlockedNow(): Boolean = blockUntilBlocking() > System.currentTimeMillis()

    // ---------- gate cooldown + intent ----------

    fun markGateShownBlocking() = runBlocking {
        context.store.edit { it[LAST_GATE_AT] = System.currentTimeMillis() }
    }

    fun lastGateAtBlocking(): Long = runBlocking {
        context.store.data.first()[LAST_GATE_AT] ?: 0L
    }

    fun setIntentBlocking(intent: String) = runBlocking {
        context.store.edit { it[SESSION_INTENT] = intent }
    }

    fun intentBlocking(): String = runBlocking {
        context.store.data.first()[SESSION_INTENT] ?: ""
    }
}
