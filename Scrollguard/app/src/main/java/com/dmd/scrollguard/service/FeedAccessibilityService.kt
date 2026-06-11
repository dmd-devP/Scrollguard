package com.dmd.scrollguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.dmd.scrollguard.data.SessionStore
import com.dmd.scrollguard.detect.IgContext
import com.dmd.scrollguard.detect.InstagramClassifier
import com.dmd.scrollguard.overlay.OverlayController

/**
 * The brain. Watches Instagram, classifies the screen, and drives:
 *   gate (fresh open) → dim+strip+timer (feed) → check-in → rest/block.
 *
 * M1: no gesture remap (that's M2).
 */
class FeedAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ScrollGuard"
        private const val INSTAGRAM = "com.instagram.android"
        /** Set true + watch logcat to tune InstagramClassifier hints. */
        private const val DEBUG_DUMP = false
        var instance: FeedAccessibilityService? = null
    }

    private lateinit var store: SessionStore
    private lateinit var overlays: OverlayController
    private val main = Handler(Looper.getMainLooper())

    // live state
    private var currentPackage: String = ""
    private var igContext: IgContext = IgContext.SAFE
    private var inInstagram = false
    private var gateShowing = false
    private var promptShowing = false   // check-in or rest menu on screen
    private var feedSecondsThisSession = 0L
    private var nextCheckInAtSec = 0L

    // ------------------------------------------------------------ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        store = SessionStore(this)
        overlays = OverlayController(this)
        startForegroundKeeper()
        main.post(ticker)
        Log.i(TAG, "service connected")
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        overlays.hideAll()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private fun startForegroundKeeper() {
        try {
            startService(Intent(this, GuardForegroundService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "fg service start failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------ events

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val cfg = store.configNow()
        if (!cfg.enabled) { teardownUi(); return }

        val pkg = event.packageName?.toString() ?: return

        // Track foreground transitions
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val wasInstagram = inInstagram
            // Ignore our own overlays and system UI when deciding "where are we"
            if (pkg == packageName || pkg == "com.android.systemui") return
            currentPackage = pkg
            inInstagram = pkg == INSTAGRAM

            if (inInstagram && !wasInstagram) onInstagramOpened(cfg)
            if (!inInstagram && wasInstagram) onInstagramLeft()
        }

        if (!inInstagram || pkg != INSTAGRAM) return

        // Block window: Instagram is paused
        if (store.isBlockedNow()) {
            enforceBlock()
            return
        }

        // Classify the current screen (cheap enough on content-changed; debounced by notificationTimeout)
        val root = rootInActiveWindow
        if (DEBUG_DUMP) Log.d(TAG, "ids: " + InstagramClassifier.dumpIds(root))
        val result = InstagramClassifier.classify(root)
        applyContext(result.context, cfg)
    }

    // ------------------------------------------------------------ open/leave

    private fun onInstagramOpened(cfg: SessionStore.Config) {
        feedSecondsThisSession = 0
        nextCheckInAtSec = cfg.checkinIntervalMin * 60L

        if (store.isBlockedNow()) { enforceBlock(); return }

        // Gate, with cooldown so bouncing out-and-back doesn't re-gate
        val cooldownMs = cfg.gateCooldownMin * 60_000L
        val sinceGate = System.currentTimeMillis() - store.lastGateAtBlocking()
        if (cfg.gateEnabled && sinceGate > cooldownMs && !gateShowing) {
            gateShowing = true
            store.markGateShownBlocking()
            overlays.showGate(
                pauseSec = cfg.gatePauseSec,
                onOpen = { intent ->
                    gateShowing = false
                    store.setIntentBlocking(intent)
                },
                onNotNow = {
                    gateShowing = false
                    goHome()
                }
            )
        }
    }

    private fun onInstagramLeft() {
        igContext = IgContext.SAFE
        gateShowing = false
        promptShowing = false
        overlays.hideDim()
        overlays.hideStrip()
        overlays.hideCheckIn()
        // keep block screen if a block is running and user pokes other social apps later (M3)
        if (!store.isBlockedNow()) overlays.hideBlock()
    }

    private fun teardownUi() {
        overlays.hideAll()
        gateShowing = false
        promptShowing = false
    }

    // ------------------------------------------------------------ context switch

    private fun applyContext(newCtx: IgContext, cfg: SessionStore.Config) {
        if (gateShowing || promptShowing) return // a prompt owns the screen
        if (newCtx == igContext) return
        igContext = newCtx
        when (newCtx) {
            IgContext.FEED -> {
                if (cfg.dimEnabled) overlays.showDim(cfg.dimIntensity)
                overlays.showStrip()
            }
            IgContext.POSTING -> { // true color to create
                overlays.hideDim()
                overlays.hideStrip()
            }
            IgContext.SAFE -> {
                overlays.hideDim()
                overlays.hideStrip()
            }
        }
    }

    // ------------------------------------------------------------ ticker (1s)

    private val ticker = object : Runnable {
        override fun run() {
            try { tick() } catch (e: Exception) { Log.w(TAG, "tick: ${e.message}") }
            main.postDelayed(this, 1000)
        }
    }

    private fun tick() {
        val cfg = store.configNow()
        if (!cfg.enabled) return

        // Block countdown maintenance
        if (store.isBlockedNow()) {
            if (inInstagram) enforceBlock()
            overlays.updateBlock(store.blockUntilBlocking() - System.currentTimeMillis())
            return
        } else {
            overlays.hideBlock()
        }

        // Feed time accrual + check-in arming
        if (inInstagram && igContext == IgContext.FEED && !gateShowing && !promptShowing) {
            feedSecondsThisSession += 1
            val today = store.addSecondsBlocking(1)
            overlays.updateTimer(fmt(today))

            if (feedSecondsThisSession >= nextCheckInAtSec) {
                showCheckIn(cfg)
            }
        }
    }

    private fun fmt(s: Long): String {
        val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, x) else String.format("%d:%02d", m, x)
    }

    // ------------------------------------------------------------ check-in / rest

    private fun showCheckIn(cfg: SessionStore.Config) {
        promptShowing = true
        overlays.showCheckIn(
            intentText = store.intentBlocking(),
            onDone = {
                promptShowing = false
                goHome()
            },
            onMore = {
                promptShowing = false
                nextCheckInAtSec = feedSecondsThisSession + cfg.checkinIntervalMin * 60L
            },
            onRest = { showRestMenu(cfg) }
        )
    }

    private fun showRestMenu(cfg: SessionStore.Config) {
        overlays.showRestMenu(
            onMusic = {
                promptShowing = false
                playRestMusic(cfg)
                goHome()
            },
            onBlock = {
                promptShowing = false
                startBlock(cfg)
            },
            onBoth = {
                promptShowing = false
                playRestMusic(cfg)
                startBlock(cfg)
            },
            onBack = { showCheckIn(cfg) }
        )
    }

    private fun playRestMusic(cfg: SessionStore.Config) {
        val uri = cfg.musicUri.trim()
        if (uri.isEmpty()) {
            Log.i(TAG, "no resting playlist configured")
            return
        }
        try {
            // Holding SYSTEM_ALERT_WINDOW exempts us from background-activity-launch limits.
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
        } catch (e: Exception) {
            Log.w(TAG, "music launch failed: ${e.message}")
        }
    }

    private fun startBlock(cfg: SessionStore.Config) {
        store.startBlockBlocking(cfg.blockMinutes)
        enforceBlock()
    }

    private fun enforceBlock() {
        overlays.hideDim(); overlays.hideStrip(); overlays.hideCheckIn()
        overlays.showBlock()
        overlays.updateBlock(store.blockUntilBlocking() - System.currentTimeMillis())
        goHome()
    }

    private fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }
}
