package com.dmd.scrollguard.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Owns every overlay window. All methods must be called on the main thread.
 *
 * Five surfaces:
 *  - dim     : full-screen, untouchable muting layer (Tier 1 dim)
 *  - strip   : bottom info strip (timer left; arrow placeholder right — remap is M2)
 *  - gate    : full-screen intention prompt on fresh open
 *  - checkin : full-screen "what did you come for?" prompt
 *  - block   : full-screen rest/block screen with countdown + suggestions
 */
class OverlayController(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var dimView: View? = null
    private var stripView: LinearLayout? = null
    private var stripTimer: TextView? = null
    private var gateView: View? = null
    private var checkinView: View? = null
    private var blockView: View? = null
    private var blockCountdown: TextView? = null
    private var blockSuggestion: TextView? = null

    // ---------------------------------------------------------------- dim

    fun showDim(intensity: Float) = main.post {
        if (dimView != null) { setDim(intensity); return@post }
        val v = View(context).apply {
            setBackgroundColor(Color.argb((intensity.coerceIn(0f, 0.8f) * 255).toInt(), 10, 12, 16))
        }
        wm.addView(v, fullScreenParams(touchable = false))
        dimView = v
    }

    fun setDim(intensity: Float) = main.post {
        dimView?.setBackgroundColor(Color.argb((intensity.coerceIn(0f, 0.8f) * 255).toInt(), 10, 12, 16))
    }

    fun hideDim() = main.post { dimView?.let { safeRemove(it) }; dimView = null }

    // --------------------------------------------------------------- strip

    fun showStrip() = main.post {
        if (stripView != null) return@post
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(20), 0, dp(20), dp(11))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(26, 255, 255, 255), Color.TRANSPARENT)
            )
        }
        val timer = TextView(context).apply {
            text = "0:00"
            setTextColor(Color.WHITE)
            alpha = 0.72f
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setShadowLayer(6f, 0f, 1f, Color.argb(150, 0, 0, 0))
        }
        val spacer = View(context)
        // M2 placeholder: the direction arrow lives here when the remap lands.
        strip.addView(timer, LinearLayout.LayoutParams(WRAP, WRAP))
        strip.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, dp(54),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        wm.addView(strip, lp)
        stripView = strip
        stripTimer = timer
    }

    fun updateTimer(text: String) = main.post { stripTimer?.text = text }

    fun hideStrip() = main.post { stripView?.let { safeRemove(it) }; stripView = null; stripTimer = null }

    // ---------------------------------------------------------------- gate

    /**
     * Full-screen intention gate.
     * onOpen(intent) — user chose to proceed (intent may be "").
     * onNotNow()     — user backed out; caller should send HOME.
     */
    fun showGate(pauseSec: Int, onOpen: (String) -> Unit, onNotNow: () -> Unit) = main.post {
        if (gateView != null) return@post
        var chosenIntent = ""

        val root = panelRoot()
        root.addView(title("Open Instagram?"))
        root.addView(body("Take a breath first. If you're tired, rest might serve you better than the feed."))
        root.addView(label("IF YOU GO IN — WHAT FOR?"))

        val chipRow = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val intents = listOf("Reply to someone", "Post something", "Look something up", "Just browse")
        val chips = intents.map { txt ->
            chip(txt) { me ->
                chips()
                me.setBackgroundColor(Color.parseColor("#E0566D")); me.setTextColor(Color.parseColor("#0D1117"))
                chosenIntent = txt
            }
        }
        fun chipsReset() = chips.forEach { it.setBackgroundColor(Color.parseColor("#1B2430")); it.setTextColor(Color.WHITE) }
        // (binding the reset into the lambda above via this little indirection)
        chipResetHook = ::chipsReset
        chips.forEach { chipRow.addView(it, chipLp()) }
        root.addView(chipRow)

        val openBtn = bigButton("wait…", enabled = false) { gateDone(); onOpen(chosenIntent) }
        val notNow = bigButton("Not now", enabled = true) { gateDone(); onNotNow() }
        notNow.setTextColor(Color.parseColor("#E0566D"))
        root.addView(notNow, btnLp())
        root.addView(openBtn, btnLp())

        wm.addView(root, fullScreenParams(touchable = true))
        gateView = root

        // forced pause: enable Open only after pauseSec
        var n = pauseSec
        fun tick() {
            if (gateView == null) return
            if (n <= 0) { openBtn.isEnabled = true; openBtn.text = "Open"; openBtn.alpha = 1f }
            else { openBtn.text = "wait… $n"; n--; main.postDelayed(::tick, 1000) }
        }
        tick()
    }

    private var chipResetHook: (() -> Unit)? = null
    private fun chips() { chipResetHook?.invoke() }
    private fun gateDone() { gateView?.let { safeRemove(it) }; gateView = null }

    // ------------------------------------------------------------- checkin

    /**
     * Mid-session check-in.
     * Buttons: I'm done / A few more minutes / I want to rest.
     */
    fun showCheckIn(
        intentText: String,
        onDone: () -> Unit,
        onMore: () -> Unit,
        onRest: () -> Unit,
    ) = main.post {
        if (checkinView != null) return@post
        val root = panelRoot()
        root.addView(label("CHECK-IN"))
        root.addView(title("What did you open Instagram for?"))
        val recall = if (intentText.isNotBlank())
            "You came to ${intentText.lowercase()}. Did you finish — or is the feed pulling you along?\n\nAre you tired? Do you need rest?"
        else
            "Did you finish what you came for — or is the feed pulling you along?\n\nAre you tired? Do you need rest?"
        root.addView(body(recall))

        root.addView(bigButton("I'm done") { checkinDone(); onDone() }, btnLp())
        root.addView(bigButton("A few more minutes") { checkinDone(); onMore() }, btnLp())
        root.addView(bigButton("I want to rest") { checkinDone(); onRest() }, btnLp())

        wm.addView(root, fullScreenParams(touchable = true))
        checkinView = root
    }

    /** Rest sub-menu: music / block / both. */
    fun showRestMenu(
        onMusic: () -> Unit,
        onBlock: () -> Unit,
        onBoth: () -> Unit,
        onBack: () -> Unit,
    ) = main.post {
        if (checkinView != null) return@post
        val root = panelRoot()
        root.addView(label("REST"))
        root.addView(title("Good call. How do you want to rest?"))
        root.addView(bigButton("Rest music 🎵") { checkinDone(); onMusic() }, btnLp())
        root.addView(bigButton("Block social for a while ⏸") { checkinDone(); onBlock() }, btnLp())
        root.addView(bigButton("Both — music + block") { checkinDone(); onBoth() }, btnLp())
        root.addView(bigButton("Back") { checkinDone(); onBack() }, btnLp())
        wm.addView(root, fullScreenParams(touchable = true))
        checkinView = root
    }

    private fun checkinDone() { checkinView?.let { safeRemove(it) }; checkinView = null }
    fun hideCheckIn() = main.post { checkinDone() }

    // --------------------------------------------------------------- block

    private val suggestions = listOf(
        "Stand up and stretch.",
        "Step outside for a minute.",
        "Drink a glass of water.",
        "What's bugging you? Write it on a piece of paper.",
        "Look out a window at something far away.",
        "Take five slow breaths.",
        "Text someone you actually care about.",
    )

    fun showBlock() = main.post {
        if (blockView != null) return@post
        val root = panelRoot()
        root.addView(label("RESTING"))
        val cd = title("5:00")
        blockCountdown = cd
        root.addView(cd)
        root.addView(body("Instagram is paused. The feed will be there later — you don't have to be."))
        val sug = body(suggestions.random())
        sug.setTextColor(Color.parseColor("#F0A24B"))
        blockSuggestion = sug
        root.addView(sug)
        wm.addView(root, fullScreenParams(touchable = true))
        blockView = root
    }

    fun updateBlock(remainingMs: Long) = main.post {
        val s = (remainingMs / 1000).coerceAtLeast(0)
        blockCountdown?.text = String.format("%d:%02d", s / 60, s % 60)
        if (s % 45 == 0L) blockSuggestion?.text = suggestions.random()
    }

    fun hideBlock() = main.post { blockView?.let { safeRemove(it) }; blockView = null; blockCountdown = null }

    // -------------------------------------------------------------- shared

    fun hideAll() = main.post {
        hideDim(); hideStrip(); gateDone(); checkinDone()
        blockView?.let { safeRemove(it) }; blockView = null
    }

    private fun safeRemove(v: View) = try { wm.removeView(v) } catch (_: Exception) {}

    private fun fullScreenParams(touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
    }

    // small view factory helpers -----------------------------------------

    private fun panelRoot() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.parseColor("#F20A0E13")) // ~95% opaque dark
        setPadding(dp(28), dp(28), dp(28), dp(28))
    }

    private fun title(t: String) = TextView(context).apply {
        text = t; textSize = 22f; setTextColor(Color.WHITE)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(10))
    }

    private fun body(t: String) = TextView(context).apply {
        text = t; textSize = 14f; setTextColor(Color.parseColor("#7E8DA0"))
        gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), dp(16))
    }

    private fun label(t: String) = TextView(context).apply {
        text = t; textSize = 11f; setTextColor(Color.parseColor("#E0566D"))
        typeface = Typeface.MONOSPACE; letterSpacing = 0.14f
        gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(4))
    }

    private fun chip(t: String, onSel: (Button) -> Unit) = Button(context).apply {
        text = t; isAllCaps = false; textSize = 14f
        setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1B2430"))
        setOnClickListener { onSel(this) }
    }

    private fun bigButton(t: String, enabled: Boolean = true, onClick: () -> Unit) = Button(context).apply {
        text = t; isAllCaps = false; textSize = 15f
        isEnabled = enabled; alpha = if (enabled) 1f else 0.5f
        setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#2A3440"))
        setOnClickListener { onClick() }
    }

    private fun chipLp() = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
    private fun btnLp() = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
