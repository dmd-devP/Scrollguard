package com.dmd.scrollguard.detect

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Classifies the current Instagram screen.
 *
 * IMPORTANT: these are *heuristics* against Instagram's accessibility tree.
 * Instagram updates WILL occasionally break them — that's expected maintenance.
 * Strategy: structural signals first, resource-id keywords second.
 *
 * Tuning tip: enable "Layout Inspector"-style dumping via logcat (see
 * FeedAccessibilityService.DEBUG_DUMP) and scroll around Instagram to see
 * which ids/classes appear on each screen of YOUR installed version.
 */
enum class IgContext { FEED, POSTING, SAFE }

object InstagramClassifier {

    // Resource-id fragments that strongly suggest each context.
    // Instagram ids look like: com.instagram.android:id/<name>
    private val POSTING_HINTS = listOf(
        "camera", "capture", "gallery", "edit", "filter", "caption",
        "creation", "composer", "postcapture", "share_sheet", "story_creation",
        "clips_editor", "media_picker"
    )
    private val SAFE_HINTS = listOf(
        "direct", "inbox", "thread", "message",          // DMs
        "search_edit_text", "action_bar_search",          // search typing
        "profile_header", "follow_button",               // someone's profile page
        "settings"
    )
    private val FEED_HINTS = listOf(
        "refreshable_container", "feed", "clips_viewer", "reel", "explore_grid"
    )

    /**
     * Walks the tree once, collecting signals, then decides.
     * Priority: POSTING > SAFE > FEED — if you're editing a Story, the feed
     * containers may still exist behind it, so posting/safe must win.
     */
    fun classify(root: AccessibilityNodeInfo?): Classification {
        if (root == null) return Classification(IgContext.SAFE, null)

        var postingScore = 0
        var safeScore = 0
        var feedScore = 0
        var scrollable: AccessibilityNodeInfo? = null

        walk(root, 0) { node ->
            val id = node.viewIdResourceName?.substringAfter(":id/")?.lowercase() ?: ""
            if (id.isNotEmpty()) {
                if (POSTING_HINTS.any { id.contains(it) }) postingScore++
                if (SAFE_HINTS.any { id.contains(it) }) safeScore++
                if (FEED_HINTS.any { id.contains(it) }) feedScore++
            }
            // Structural: a big scrollable container is a feed candidate.
            if (node.isScrollable && scrollable == null) {
                scrollable = node
            }
        }

        val ctx = when {
            postingScore > 0 -> IgContext.POSTING
            safeScore > 0 -> IgContext.SAFE
            feedScore > 0 && scrollable != null -> IgContext.FEED
            // Fallback: a scrollable screen with no other signals — treat as feed
            // only if it's large; conservative default is SAFE.
            else -> IgContext.SAFE
        }
        return Classification(ctx, if (ctx == IgContext.FEED) scrollable else null)
    }

    data class Classification(val context: IgContext, val feedNode: AccessibilityNodeInfo?)

    private fun walk(node: AccessibilityNodeInfo, depth: Int, visit: (AccessibilityNodeInfo) -> Unit) {
        if (depth > 18) return // safety: trees can be deep/cyclic-ish
        visit(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, depth + 1, visit)
        }
    }

    /** For DEBUG_DUMP: flattens visible resource-ids to one line for logcat. */
    fun dumpIds(root: AccessibilityNodeInfo?): String {
        if (root == null) return "(null root)"
        val ids = mutableListOf<String>()
        walk(root, 0) { n ->
            n.viewIdResourceName?.let { ids.add(it.substringAfter(":id/")) }
        }
        return ids.distinct().joinToString(",")
    }
}
