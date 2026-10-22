package com.musheer360.swiftslate.service

import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.max

/**
 * Reads the visible content of the current window as a text snapshot for SCREEN
 * commands ([com.musheer360.swiftslate.model.Command.readsScreen]).
 *
 * Walking the tree is deliberately simple and defensive:
 *
 *  - Depth- and node-capped so an exotic host (or a bug in one) cannot slow the
 *    keystroke path down; the caps bound worst-case latency, not quality.
 *  - The user's own focused editable is EXCLUDED with its whole subtree — that is
 *    where the just-typed trigger (?reply) lives, and later the spinner/stream
 *    will be written there; it must never end up inside the "conversation".
 *  - Text nodes are collected with bounds and sorted top-to-bottom, left-to-right,
 *    which reproduces visual reading order far better than DFS order across apps
 *    that add/reorder nodes dynamically.
 *  - Exact duplicates are dropped (many apps expose the same string on a
 *    container node and its leaf), while legitimately repeated lines survive.
 *  - Intermediate nodes are NOT recycled: they are alive only for the microseconds
 *    of the synchronous traversal, and recycling mid-walk is the classic
 *    use-after-recycle crash (#125's whole family). They are collectible garbage.
 *
 * All access happens on the service's main thread (the caller wraps it), because
 * rootInActiveWindow and the node tree only give consistent reads there.
 */
object ScreenTextReader {

    data class Snapshot(
        /** Top-down reading-order transcript, newline-joined. */
        val text: String,
        /** How many text lines survived filtering — used for the "empty" check. */
        val lineCount: Int
    )

    /** Bounds console for the transcript: 6k characters ≈ a dense screen of chat. */
    private const val MAX_CHARS = 6_000
    private const val MAX_NODES = 600
    private const val MAX_DEPTH = 40

    /** Returns null when nothing useful could be read (no root, all text empty). */
    fun read(root: AccessibilityNodeInfo?): Snapshot? {
        if (root == null) return null
        val collected = mutableListOf<Pair<CharSequence, android.graphics.Rect>>()
        val seen = HashSet<String>()
        try {
            walk(root, 0, collected, seen)
        } catch (_: Exception) {
            // A stale/rebound tree mid-walk must never crash the service (#125).
            // Whatever was collected before the hop is still a valid partial read.
        }
        if (collected.isEmpty()) return null
        val sorted = collected.sortedWith(compareBy({ it.second.top }, { it.second.left }))
        val sb = StringBuilder()
        var count = 0
        for ((text, _) in sorted) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(text)
            if (sb.length >= MAX_CHARS) break
            count++
        }
        val finalText = if (sb.length > MAX_CHARS) sb.substring(0, MAX_CHARS) else sb.toString()
        if (finalText.isBlank()) return null
        return Snapshot(finalText, count)
    }

    /** Composes the user message for a screen command: snapshot + typed instruction. */
    fun composeUserMessage(instruction: String, snapshotText: String): String {
        val sb = StringBuilder()
        sb.append("<screen>\n").append(snapshotText).append("\n</screen>")
        if (instruction.isNotBlank()) {
            sb.append("\n\nAdditional instruction typed by the user: ")
                .append(instruction)
        }
        return sb.toString()
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: MutableList<Pair<CharSequence, android.graphics.Rect>>,
        seen: HashSet<String>
    ) {
        if (depth > MAX_DEPTH || out.size > MAX_NODES) return
        if (node.isEditable && node.isFocused) {
            // The user's own input field (the trigger lives here) — skip its subtree.
            return
        }
        val text = node.text
        if (text != null) {
            val trimmed = text.toString().trim()
            if (trimmed.isNotEmpty() &&
                !seen.contains(trimmed) &&
                !looksLikePrivateField(node)
            ) {
                seen.add(trimmed)
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty && node.isVisibleToUser) {
                    out.add(trimmed to rect)
                }
            }
        }
        for (child in node.children.orEmpty()) {
            if (child == null) continue
            walk(child, depth + 1, out, seen)
        }
    }

    /**
     * Content flagged via the accessibility hint as password-like must never be
     * photographed into the snapshot even if the source app mislabels isEditable.
     */
    private fun looksLikePrivateField(node: AccessibilityNodeInfo): Boolean = node.isPassword

    /** children can throw on stale nodes on some OEM builds; normalize to null. */
    private val AccessibilityNodeInfo.children: List<AccessibilityNodeInfo>?
        get() = try {
            val count = childCount
            val list = ArrayList<AccessibilityNodeInfo>(max(0, count))
            for (i in 0 until count) list.add(getChild(i))
            list
        } catch (_: Exception) {
            null
        }
}
