package com.musheer360.swiftslate.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.musheer360.swiftslate.api.ApiClientUtils
import com.musheer360.swiftslate.api.GeminiClient
import com.musheer360.swiftslate.api.OpenAICompatibleClient
import com.musheer360.swiftslate.manager.CommandManager
import com.musheer360.swiftslate.manager.KeyManager
import com.musheer360.swiftslate.manager.StatsManager
import com.musheer360.swiftslate.model.Command
import com.musheer360.swiftslate.model.CommandChain
import com.musheer360.swiftslate.model.CommandType
import com.musheer360.swiftslate.model.PrefKeys
import com.musheer360.swiftslate.ui.processtext.ProcessTextEdit
import com.musheer360.swiftslate.ui.processtext.ProcessTextReplacementBridge
import com.musheer360.swiftslate.ui.processtext.resolveProcessTextEdit
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.SwiftSlateApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AssistantService : AccessibilityService() {

    private lateinit var keyManager: KeyManager
    private lateinit var commandManager: CommandManager
    private lateinit var statsManager: StatsManager
    private val client = GeminiClient()
    private val openAIClient = OpenAICompatibleClient()
    private val serviceJob = SupervisorJob()
    // Any exception escaping a coroutine launched from the accessibility service would kill
    // the whole process with no UI to crash into (the Settings toggle stays "on" regardless,
    // so the Dashboard just shows the service as inactive). Log and swallow instead.
    private val serviceScope = CoroutineScope(
        serviceJob + Dispatchers.IO +
            CoroutineExceptionHandler { _, e ->
                Log.w(TAG, "uncaught exception in service scope", e)
            }
    )
    private val isProcessing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var triggerLastChars = setOf<Char>()
    private var cachedPrefix = CommandManager.DEFAULT_PREFIX
    private var cachedTranslatePrefix = ""

    /**
     * A translate match waiting for the typing to pause (see TRANSLATE_COMMIT_DELAY_MS).
     * Exactly one at a time; every new translate event replaces it (recycling the
     * superseded node), and every fire consumes it.
     */
    private var pendingTranslate: PendingTranslate? = null
    private var pendingTranslateRunnable: Runnable? = null

    private class PendingTranslate(
        val source: AccessibilityNodeInfo,
        val instruction: String,
        val command: Command,
        val appPackage: String?
    )
    /** Packages whose events must be ignored entirely (excluded-apps feature). Refreshed with the trigger cache. */
    private var excludedPackages: Set<String> = emptySet()
    @Volatile
    private var currentJob: Job? = null
    private var processingResetRunnable: Runnable? = null
    // Intentionally single-level undo (toggle between current and previous text).
    // Tracks the source node's identity to prevent cross-field undo corruption.
    @Volatile
    private var lastOriginalText: String? = null
    @Volatile
    private var lastUndoSourceId: String? = null
    @Volatile
    private var lastCopiedText: String? = null
    @Volatile
    private var lastReplacedText: String? = null
    @Volatile
    private var lastReplacedAt = 0L
    @Volatile
    private var lastFocusFallbackAt = 0L
    @Volatile
    private var lastReplacedSource: AccessibilityNodeInfo? = null
    private var verifyRunnable: Runnable? = null
    /** (clipboard, originalClip, ourText) for a paste-fallback restore that has not run yet. */
    private var pendingClipRestore: Triple<android.content.ClipboardManager, ClipData?, String>? = null
    private var lastTriggerRefresh = 0L
    private var watchdogRunnable: Runnable? = null
    private val overlayToast by lazy { OverlayToast(this@AssistantService, handler) }

    /**
     * Stable identity for the field an undo point belongs to.
     *
     * Uses [AccessibilityNodeInfo.hashCode], which the framework overrides to derive from the
     * source node id (accessibility view id + virtual descendant id) and window id — so it is
     * a per-node value, not an identity hash, and it is equal across the successive node
     * instances the same field produces.
     *
     * This deliberately does NOT prefer viewIdResourceName, which it used to: that is the less
     * precise of the two. Sibling fields built from one layout share a resource name, so a
     * RecyclerView of identical rows or a multi-field form collapsed to a single id and undo
     * could be applied to the wrong field — exactly the corruption the check exists to prevent.
     */
    private fun sourceId(source: AccessibilityNodeInfo): String = source.hashCode().toString()

    private companion object {
        const val TAG = "SwiftSlateService"
        const val TRIGGER_REFRESH_INTERVAL_MS = 5_000L
        const val PROCESSING_WATCHDOG_MS = 120_000L
        const val FOCUS_FALLBACK_MIN_INTERVAL_MS = 300L
        /**
         * How long a translate match waits after the last keystroke before committing.
         * Translate is the one parameterized trigger: its language argument is typed
         * word-by-word, and intermediate prefixes can themselves be valid entries
         * ("hi" of "hindi"). Rescheduling on every text change instead of firing
         * instantly is what lets full language names work.
         */
        const val TRANSLATE_COMMIT_DELAY_MS = 1_200L
        val SPINNER_FRAMES = arrayOf("◐", "◓", "◑", "◒")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            keyManager = (applicationContext as SwiftSlateApp).keyManager
            commandManager = CommandManager(applicationContext)
            statsManager = StatsManager(applicationContext)
            updateTriggers()
            // The permanent shade notice flips to "active" the moment the service is
            // really bound. If init failed above, no update happens — the pre-refresh
            // state lingers, which may briefly say active for a service that stays inert
            // (#125 degraded mode) until the next teardown sets it stopped. Acceptable:
            // notification truth is eventually consistent, unlike the dead-silence status quo.
            KeepAlive.postActive(this)
        } catch (e: Exception) {
            // This callback runs on the binder thread with no framework guard: an exception
            // here propagates to AccessibilityManagerService, which drops the service into the
            // "crashed services" limbo with the toggle still on. Log and degrade instead — the
            // event path and command path already handle an uninitialized manager (#125).
            Log.w(TAG, "onServiceConnected failed; service will stay inert until re-enabled", e)
        }
    }

    private fun updateTriggers() {
        cachedPrefix = commandManager.getTriggerPrefix()
        cachedTranslatePrefix = "${cachedPrefix}translate:"
        val cmds = commandManager.getCommands()
        triggerLastChars = cmds.mapNotNull { it.trigger.lastOrNull() }.toSet()
        excludedPackages = try {
            val prefs = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val raw = prefs.getString(PrefKeys.EXCLUDED_PACKAGES, null)
            if (raw.isNullOrEmpty()) {
                emptySet()
            } else {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.toSet()
            }
        } catch (_: Exception) {
            emptySet()
        }
        lastTriggerRefresh = System.currentTimeMillis()
    }

    /**
     * Arms the translate commit timer. Called on every matching volver keystroke, so
     * the timer keeps sliding forward as long as the user is still typing the
     * language name — the commit happens exactly once, after a 1.2s pause.
     */
    private fun scheduleTranslateCommit(
        source: AccessibilityNodeInfo,
        instruction: String,
        command: Command,
        appPackage: String?
    ) {
        pendingTranslateRunnable?.let { handler.removeCallbacks(it) }
        pendingTranslateRunnable = null
        val pending = pendingTranslate
        if (pending != null && pending.source !== source) {
            pending.source.safeRecycle()
        }
        pendingTranslate = PendingTranslate(source, instruction, command, appPackage)
        val runnable = Runnable { firePendingTranslate() }
        pendingTranslateRunnable = runnable
        if (!handler.postDelayed(runnable, TRANSLATE_COMMIT_DELAY_MS)) {
            // Looper is dead — the service is going down anyway; degrade to immediate.
            firePendingTranslate()
        }
    }

    /** Consumes the pending translate when the user has paused typing. */
    private fun firePendingTranslate() {
        pendingTranslateRunnable = null
        val pending = pendingTranslate ?: return
        pendingTranslate = null
        if (!isProcessing.compareAndSet(false, true)) {
            // Some other command owns the field — the stale snapshot loses.
            pending.source.safeRecycle()
            return
        }
        startWatchdog()
        cancelPendingProcessingReset()
        currentJob?.cancel()
        processCommand(pending.source, pending.instruction, pending.command, pending.appPackage)
    }

    /** Drops a pending translate; [currentSource] must not be double-recycled. */
    private fun cancelPendingTranslate(currentSource: AccessibilityNodeInfo?) {
        pendingTranslateRunnable?.let { handler.removeCallbacks(it) }
        pendingTranslateRunnable = null
        val pending = pendingTranslate
        pendingTranslate = null
        if (pending != null && (currentSource == null || pending.source !== currentSource)) {
            pending.source.safeRecycle()
        }
    }

    private fun startWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            if (isProcessing.get()) {
                currentJob?.cancel()
                isProcessing.set(false)
            }
        }
        watchdogRunnable = runnable
        handler.postDelayed(runnable, PROCESSING_WATCHDOG_MS)
    }

    private fun cancelWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun cancelPendingProcessingReset() {
        processingResetRunnable?.let { handler.removeCallbacks(it) }
        processingResetRunnable = null
    }

    private fun scheduleProcessingReset() {
        cancelPendingProcessingReset()
        val runnable = Runnable { isProcessing.set(false) }
        processingResetRunnable = runnable
        if (!handler.postDelayed(runnable, 500)) {
            isProcessing.set(false)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            handleAccessibilityEvent(event)
        } catch (e: Exception) {
            // AccessibilityNodeInfo methods throw IllegalStateException when the underlying
            // view is gone or the node was already recycled by the time we call into it — a
            // real race in the Accessibility API, not something we can fully prevent by
            // checking first. Nothing here ran inside a coroutine, so nothing catches this on
            // its own: an accessibility service has no foreground UI to crash into, so an
            // uncaught exception silently kills the whole service process. The Settings toggle
            // stays "on" (that flag is independent of whether the process is alive), so the
            // user sees the Dashboard go inactive with no error and no way to tell why. See
            // #125 — swallow and drop the event instead of taking the service down with it.
            Log.w(TAG, "dropping accessibility event", e)
            try { event?.source?.safeRecycle() } catch (_: Exception) {}
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        val eventPackage = event.packageName?.toString()
        if (eventPackage == packageName) return
        if (!::keyManager.isInitialized) return

        // Some hosts (WeChat-style editors, WebView fields) emit text-changed events whose
        // source node is null or already recycled. Fall back to the focused input node of the
        // active window before giving up — see #125 / #131. The root lookup is a binder call on
        // the main thread, so it is throttled: hosts that flood null-source events are rare, and
        // skipping an occasional event is harmless for trigger detection.
        val source = event.source ?: run {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFocusFallbackAt < FOCUS_FALLBACK_MIN_INTERVAL_MS) return
            lastFocusFallbackAt = now
            findFocusedEditableSource()
        } ?: return
        if (source.isPassword) {
            source.safeRecycle()
            return
        }
        // Excluded apps: banking/password type packages opted out of in place. Checked before
        // any other work because these events may never legitimately trigger a text rewrite.
        if (eventPackage != null && eventPackage in excludedPackages) {
            source.safeRecycle()
            return
        }
        val text = source.text?.toString() ?: run {
            source.safeRecycle()
            return
        }
        if (text.startsWith(cachedPrefix)) {
            Log.d(TAG, "evt pkg=" + eventPackage + " text='" + text + "'" +
                " srcNull=" + (event.source == null) + " processing=" + isProcessing.get())
        }
        if (handlePendingProcessTextReplacement(event, source, text)) return
        if (isProcessing.get()) {
            source.safeRecycle()
            return
        }
        if (text.isEmpty()) {
            cancelPendingTranslate(source)
            verifyRunnable?.let { handler.removeCallbacks(it) }
            lastReplacedText = null
            val prev = lastReplacedSource
            lastReplacedSource = null
            if (prev != null && prev !== source) {
                prev.safeRecycle()
            }
            source.safeRecycle()
            return
        }

        // Skip events where text matches what we just replaced (prevents IME re-commit race)
        val replaced = lastReplacedText
        if (replaced != null && text == replaced &&
            System.currentTimeMillis() - lastReplacedAt < 1000) {
            source.safeRecycle()
            return
        }

        if (System.currentTimeMillis() - lastTriggerRefresh > TRIGGER_REFRESH_INTERVAL_MS) {
            updateTriggers()
        }

        // IME text can carry case noise ("?Reply" from auto-capitalization on an empty
        // field) and trailing auto-space ("?reply "). All trigger work — the fast-path
        // last-char gate, the translate lookahead, and matching — must operate on the
        // same trimmed form, or anything typed auto-appended after the trigger
        // silently kills detection before findCommand ever runs.
        val matchText = text.trimEnd()
        if (matchText.isEmpty()) {
            // Whitespace-only field: nothing to match, nothing to do.
            source.safeRecycle()
            return
        }
        val lastChar = matchText[matchText.length - 1]
        if (!triggerLastChars.contains(lastChar)) {
            if (!lastChar.isLetterOrDigit() || !matchText.contains(cachedTranslatePrefix)) {
                source.safeRecycle()
                return
            }
        }

        val command = commandManager.findCommand(matchText) ?: run {
            source.safeRecycle()
            return
        }
        // Screen-command diagnostics: what matched, and what the service decided.
        Log.d(TAG, "command matched: '" + command.trigger + "' screen=" + command.readsScreen +
            " textLen=" + text.length + " srcFromEvent=" + (event.source != null))

        val precedingText = matchText.substring(0, matchText.length - command.trigger.length)
        val cleanText = precedingText.trim()

        if (command.trigger.endsWith("undo") && command.isBuiltIn) {
            if (!isProcessing.compareAndSet(false, true)) {
                source.safeRecycle()
                return
            }
            startWatchdog()
            cancelPendingProcessingReset()
            currentJob?.cancel()
            handleUndo(source, cleanText)
            return
        }

        if (command.isBuiltIn && (command.trigger.endsWith("copy") || command.trigger.endsWith("cut") ||
            command.trigger.endsWith("paste") || command.trigger.endsWith("replace") ||
            command.trigger.endsWith("clear"))) {
            if (!isProcessing.compareAndSet(false, true)) {
                source.safeRecycle()
                return
            }
            startWatchdog()
            cancelPendingProcessingReset()
            currentJob?.cancel()
            handleClipboardCommand(source, precedingText, command)
            return
        }

        // Chain resolution for the AI / text-replacer stages below. System commands were
        // intercepted above, so a system command can only appear here as a lone stage where
        // it is the entire chain — matching the single-command paths byte-for-byte.
        val chain = commandManager.buildChain(matchText)
        if (chain == null || chain.commands.isEmpty()) {
            source.safeRecycle()
            return
        }

        when (command.type) {
            CommandType.TEXT_REPLACER -> {
                if (chain.commands.size > 1) {
                    // The text-replacer is one stage inside a chain ("?sig ?formal" etc.) — the
                    // whole chain goes through the multi-stage executor, which applies the
                    // replacer's prompt to the accumulator exactly like the single path.
                    if (!isProcessing.compareAndSet(false, true)) {
                        source.safeRecycle()
                        return
                    }
                    startWatchdog()
                    cancelPendingProcessingReset()
                    currentJob?.cancel()
                    currentJob = executeCommandChain(source, chain, eventPackage)
                    return
                }
                if (!isProcessing.compareAndSet(false, true)) {
                    source.safeRecycle()
                    return
                }
                startWatchdog()
                cancelPendingProcessingReset()
                currentJob?.cancel()
                currentJob = serviceScope.launch {
                    val thisJob = coroutineContext[Job]
                    try {
                        withContext(Dispatchers.Main) {
                            val replacerOk = replaceText(source, precedingText + command.prompt)
                            if (!replacerOk) {
                                // Don't record an undo point, a CONFIRM haptic or a usage stat
                                // for a replacement the field silently refused.
                                performHapticFeedback(HapticFeedbackConstants.REJECT)
                                showToast(getString(R.string.toast_replace_failed))
                            } else {
                                lastOriginalText = precedingText
                                lastUndoSourceId = sourceId(source)
                                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                statsManager.recordUsage(command.trigger, eventPackage)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            showToast(getString(R.string.toast_replace_failed))
                        }
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main) {
                            if (currentJob === thisJob) {
                                cancelWatchdog()
                                scheduleProcessingReset()
                            }
                            recycleIfUnowned(source)
                        }
                    }
                }
            }
            CommandType.AI -> {
                // Translate is the parameterized trigger: its language argument is typed
                // one keystroke at a time, and intermediate prefixes can be valid entries
                // ("hi" of "hindi"). Never act on the first match — hold the match until
                // the typing pauses; each new translate event simply re-arms the timer.
                if (command.trigger.startsWith(cachedTranslatePrefix)) {
                    scheduleTranslateCommit(source, cleanText, command, eventPackage)
                    return
                }
                // Screen commands read the window as context: typing just the trigger with
                // nothing in front of it ("?reply") is the normal way to invoke them.
                if (cleanText.isEmpty() && !command.readsScreen) {
                    source.safeRecycle()
                    return
                }
                if (command.readsScreen) {
                    // A screen command always runs alone — stacking stages over a snapshot
                    // has no defined semantics, and the snapshot path owns the field start
                    // to finish like a lone AI command does.
                    if (!isProcessing.compareAndSet(false, true)) {
                        Log.d(TAG, "screen command skipped: already processing")
                        source.safeRecycle()
                        return
                    }
                    startWatchdog()
                    cancelPendingProcessingReset()
                    currentJob?.cancel()
                    Log.d(TAG, "screen command start: cleanText='" + cleanText + "'")
                    processCommand(source, cleanText, command, eventPackage)
                    return
                }
                if (chain.commands.size > 1) {
                    // Multiple trailing triggers: inner stages transform the text, the
                    // outermost stage produces what finally lands in the field.
                    if (!isProcessing.compareAndSet(false, true)) {
                        source.safeRecycle()
                        return
                    }
                    startWatchdog()
                    cancelPendingProcessingReset()
                    currentJob?.cancel()
                    currentJob = executeCommandChain(source, chain, eventPackage)
                    return
                }
                if (!isProcessing.compareAndSet(false, true)) {
                    source.safeRecycle()
                    return
                }
                startWatchdog()
                cancelPendingProcessingReset()
                currentJob?.cancel()
                processCommand(source, cleanText, command, eventPackage)
            }
        }
    }

    /**
     * Best-effort source for text-changed events that arrive without one: the focused input
     * node of the active window. Returns null when unavailable; the caller treats the result
     * exactly like a null event.source and recycles it like one. All node access is guarded —
     * the root can be stale the moment we ask (#125).
     *
     * Two-stage: `findFocus(FOCUS_INPUT)` keeps precedence (it surfaces sources upstream
     * accepts), and a bounded recursive search for an editable+focused node runs only when
     * `findFocus` reports nothing — some hosts expose the input deeper in the tree than
     * `findFocus` reaches.
     *
     * Ownership: when `findFocus` returns non-null, this method recycles `root` and returns the
     * focused node. When `findFocus` returns null, ownership of `root` is transferred to
     * [FocusedEditableFinder] which recycles every visited node except the match it returns
     * (or all on miss). The outer catch's `safeRecycle` is a safety net for the rare case where
     * an exception escapes before the finder takes ownership; double-recycle is benign
     * (`safeRecycle` catches `IllegalStateException`, and on API 33+ `recycle()` is a no-op).
     */
    private fun findFocusedEditableSource(): AccessibilityNodeInfo? {
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.w(TAG, "focused-node fallback: root unavailable", e)
            null
        } ?: return null
        try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused === root) return root
            if (focused != null) {
                root.safeRecycle()
                return focused
            }
            // Second stage: findFocus found nothing, so walk the tree for an editable+focused
            // node. The walk recycles every visited node except the match it returns.
            val found = FocusedEditableFinder.find(AccessibilityFocusNode(root)) as? AccessibilityFocusNode
            return found?.node
        } catch (e: Exception) {
            Log.w(TAG, "focused-node fallback failed", e)
            root.safeRecycle()
            return null
        }
    }

    private fun handlePendingProcessTextReplacement(
        event: AccessibilityEvent,
        source: AccessibilityNodeInfo,
        afterText: String
    ): Boolean {
        val request = ProcessTextReplacementBridge.current(SystemClock.elapsedRealtime())
            ?: return false
        // The request's package is optional, but an event without a package can never be
        // verified against it — previously a pending request with a null sourcePackage was
        // matched against (and consumed by) an edit in ANY app, or one with no package at all.
        val eventPackage = event.packageName?.toString() ?: return false
        if (request.sourcePackage != null && eventPackage != request.sourcePackage) {
            return false
        }
        val beforeText = event.beforeText?.toString() ?: return false
        val edit = resolveProcessTextEdit(
            beforeText = beforeText,
            afterText = afterText,
            fromIndex = event.fromIndex,
            removedCount = event.removedCount,
            addedCount = event.addedCount,
            request = request
        )
        if (edit == ProcessTextEdit.Unrelated) {
            return false
        }
        if (edit is ProcessTextEdit.Appended && isProcessing.get()) {
            // A command is already running. Leave the request pending and the field untouched
            // instead of consuming the request and swallowing the user's keystroke — a later
            // text-changed event inside the bridge TTL can still apply it (#125).
            source.safeRecycle()
            return false
        }
        if (!ProcessTextReplacementBridge.consume(request)) {
            return false
        }
        if (edit == ProcessTextEdit.Replaced) {
            source.safeRecycle()
            return true
        }

        edit as ProcessTextEdit.Appended
        if (!isProcessing.compareAndSet(false, true)) {
            // Lost a race with a new command after the pre-check; the request is already
            // consumed, so drop this edit rather than clobbering the command's field writes.
            source.safeRecycle()
            return true
        }
        startWatchdog()
        cancelPendingProcessingReset()
        currentJob = serviceScope.launch {
            val thisJob = coroutineContext[Job]
            try {
                val replaced = replaceText(source, edit.correctedText)
                if (replaced) {
                    lastOriginalText = beforeText
                    lastUndoSourceId = sourceId(source)
                } else {
                    withContext(Dispatchers.Main) {
                        showToast(getString(R.string.toast_replace_failed))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    showToast(getString(R.string.toast_replace_failed))
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (currentJob === thisJob) {
                        cancelWatchdog()
                        scheduleProcessingReset()
                    }
                    recycleIfUnowned(source)
                }
            }
        }
        return true
    }

    private fun processCommand(
        source: AccessibilityNodeInfo,
        text: String,
        command: Command,
        appPackage: String? = null
    ) {
        if (!keyManager.keystoreAvailable) {
            // keys_keystore_error rather than toast_keystore_unavailable: the latter tells the
            // user to reinstall, which destroys every key, command and setting, and does not
            // address the usual cause (the KeyStore key being invalidated by a lock-screen
            // change, where re-adding the keys is enough). Both strings are already localized.
            handler.post { overlayToast.show(getString(R.string.keys_keystore_error)) }
            cancelWatchdog()
            isProcessing.set(false)
            recycleIfUnowned(source)
            return
        }

        currentJob = serviceScope.launch {
            val thisJob = coroutineContext[Job]
            val originalText = text
            var spinnerJob: Job? = null
            // Streaming: when enabled, the field is live-rewritten as chunks land instead
            // of a spinner holding it. The final write is STILL the verified
            // [replaceText] — live frames only make the wait visible.
            val streamingOn = try {
                getSharedPreferences("settings", MODE_PRIVATE)
                    .getBoolean(PrefKeys.STREAMING_ENABLED, true)
            } catch (_: Exception) { true }
            val renderer = if (streamingOn) StreamRenderer(serviceScope, source) else null
            try {
                // Screen commands first photograph the current window — BEFORE anything
                // touches the field (spinner/stream writes would otherwise appear inside
                // the "conversation" they are about to read). Node access is main-thread
                // only (Dispatchers.IO scope rarely matters, but the window root can be
                // recycled between binds, so guard the whole read).
                var userMessage = originalText
                if (command.readsScreen) {
                    val snapshot = withContext(Dispatchers.Main) {
                        val root = try {
                            rootInActiveWindow
                        } catch (_: Exception) { null }
                        try {
                            ScreenTextReader.read(root)
                        } finally {
                            root?.safeRecycle()
                        }
                    }
                    if (snapshot == null) {
                        Log.d(TAG, "screen snapshot: EMPTY (nothing readable)")
                        performHapticFeedback(HapticFeedbackConstants.REJECT)
                        showToast(getString(R.string.screen_read_failed))
                        return@launch
                    }
                    Log.d(TAG, "screen snapshot: " + snapshot.lineCount + " lines, " +
                        snapshot.text.length + " chars")
                    userMessage = ScreenTextReader.composeUserMessage(originalText, snapshot.text)
                }
                val outcome = withTimeout(90_000) {
                    runTextCommand(
                        applicationContext, keyManager, client, openAIClient,
                        command.prompt, userMessage,
                        onFirstAttempt = { spinnerJob = startInlineSpinner(source, originalText) },
                        onDelta = renderer?.let { r -> { soFar: String, _: Int -> r.onDelta(soFar) } },
                        screenMode = command.readsScreen
                    )
                }
                // From the first attempt onward the field holds the spinner glyph (or the
                // live stream) instead of the user's text, so every outcome below starts by
                // taking it back out. Neither marker means nothing was ever written — a
                // failed no-op write must not produce a "could not restore your text" prefix.
                val fieldWasAltered = spinnerJob != null || (renderer?.sawDeltas ?: false)
                spinnerJob?.cancelAndJoin()
                spinnerJob = null
                renderer?.stop()

                when (outcome) {
                    is CommandOutcome.Success -> {
                        Log.d(TAG, "screen/AI outcome: Success")
                        if (!replaceText(source, outcome.text)) {
                            // The field rejected the write. Restore the user's text, and don't
                            // record an undo point or a CONFIRM haptic for text that never landed.
                            replaceText(source, originalText)
                            performHapticFeedback(HapticFeedbackConstants.REJECT)
                            showToast(getString(R.string.toast_replace_failed))
                        } else {
                            lastOriginalText = originalText
                            lastUndoSourceId = sourceId(source)
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            statsManager.recordUsage(command.trigger, appPackage)
                        }
                    }
                    is CommandOutcome.Refusal -> {
                        Log.d(TAG, "screen/AI outcome: Refusal")
                        replaceText(source, originalText)
                        performHapticFeedback(HapticFeedbackConstants.REJECT)
                        showToast(getString(R.string.error_safety_blocked))
                    }
                    // Nothing was sent, so there is nothing to restore.
                    is CommandOutcome.Unavailable -> {
                        Log.d(TAG, "screen/AI outcome: Unavailable")
                        showToast(outcome.message)
                    }
                    is CommandOutcome.Failure -> {
                        Log.d(TAG, "screen/AI outcome: Failure: " + outcome.message)
                        val restoredOk = !fieldWasAltered || replaceText(source, originalText)
                        performHapticFeedback(HapticFeedbackConstants.REJECT)
                        showToast(
                            if (restoredOk) outcome.message
                            else getString(R.string.toast_restore_failed) + "\n" + outcome.message
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                spinnerJob?.cancelAndJoin()
                renderer?.stop()
                // If the restore fails the field is left holding the spinner glyph, which
                // matters more to the user than the timeout itself — say so rather than
                // swallowing it (both strings already exist in every locale).
                var restoreFailed = false
                try { restoreFailed = !replaceText(source, originalText) } catch (_: Exception) { restoreFailed = true }
                showToast(
                    if (restoreFailed) getString(R.string.toast_restore_failed) + "\n" + getString(R.string.toast_request_timed_out)
                    else getString(R.string.toast_request_timed_out)
                )
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    renderer?.stop()
                    spinnerJob?.cancel()
                    // Only restore if this job still owns the field. The watchdog clears
                    // isProcessing as soon as it cancels, so a new command can start while this
                    // handler is still running under NonCancellable — and restoring the old text
                    // then would overwrite what the newer job has already written.
                    if (currentJob === thisJob) {
                        try { replaceText(source, originalText) } catch (_: Exception) {}
                    }
                }
                throw e
            } catch (e: Exception) {
                spinnerJob?.cancelAndJoin()
                renderer?.stop()
                // showToast() dismisses any visible toast first, so the previous code's
                // restore-failure toast was destroyed microseconds later by the error toast
                // below — making it unreadable. Combine them instead.
                var restoreFailed = false
                try { restoreFailed = !replaceText(source, originalText) } catch (_: Exception) { restoreFailed = true }
                val mapped = mapErrorMessage(e.message ?: "Unknown error")
                showToast(if (restoreFailed) getString(R.string.toast_restore_failed) + "\n" + mapped else mapped)
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (currentJob === thisJob) {
                        cancelWatchdog()
                        scheduleProcessingReset()
                    }
                    renderer?.stop()
                    spinnerJob?.cancel()
                    recycleIfUnowned(source)
                }
            }
        }
    }

    /**
     * Runs a resolved chain of stages inner→outer and writes the final accumulator once.
     *
     * Stage semantics mirror the single-command paths: a TEXT_REPLACER stage appends its
     * prompt to the running text (the snippet-expansion behaviour of a lone replacer), and an
     * AI stage transforms the running text through [runTextCommand]. The field is only touched
     * twice at most: the inline spinner's writes (first AI stage) and the final replacement —
     * intermediate stages never write, so a mid-chain failure leaves the user's text pristine.
     *
     * On failure the field is restored to the state it had when the spinner started (or the
     * raw pre-trigger text when no stage touched it), then the failure toast fires — the same
     * contract the single-command restore path honors.
     */
    private fun executeCommandChain(
        source: AccessibilityNodeInfo,
        chain: CommandChain,
        appPackage: String?
    ): Job {
        return serviceScope.launch {
            val thisJob = coroutineContext[Job]
            var spinnerJob: Job? = null
            var restoreText = chain.baseText
            try {
                var acc = chain.baseText
                var fieldWrittenBySpinner = false
                var chainAborted = false

                for (stage in chain.commands) {
                    if (chainAborted) break
                    when (stage.type) {
                        CommandType.TEXT_REPLACER -> {
                            acc = joinStageText(acc, stage.prompt)
                            statsManager.recordUsage(stage.trigger, appPackage)
                        }
                        CommandType.AI -> {
                            if (acc.isEmpty()) {
                                // Nothing to send — same reason the single path refuses
                                // cleanText.isEmpty() before touching the service.
                                spinnerJob?.cancelAndJoin()
                                spinnerJob = null
                                chainAborted = true
                                break
                            }
                            val outcome = withTimeout(90_000) {
                                runTextCommand(
                                    applicationContext, keyManager, client, openAIClient,
                                    stage.prompt, acc,
                                    onFirstAttempt = {
                                        if (spinnerJob == null) {
                                            // Snapshot for restore BEFORE the spinner overwrites the
                                            // field: everything applied so far (replacer stages too).
                                            restoreText = acc
                                            spinnerJob = startInlineSpinner(source, acc)
                                            fieldWrittenBySpinner = true
                                        }
                                    }
                                )
                            }
                            spinnerJob?.cancelAndJoin()
                            spinnerJob = null
                            when (outcome) {
                                is CommandOutcome.Success -> {
                                    acc = outcome.text
                                    statsManager.recordUsage(stage.trigger, appPackage)
                                }
                                is CommandOutcome.Refusal -> {
                                    performHapticFeedback(HapticFeedbackConstants.REJECT)
                                    showToast(getString(R.string.error_safety_blocked))
                                    chainAborted = true
                                }
                                is CommandOutcome.Unavailable -> {
                                    showToast(outcome.message)
                                    chainAborted = true
                                }
                                is CommandOutcome.Failure -> {
                                    performHapticFeedback(HapticFeedbackConstants.REJECT)
                                    showToast(outcome.message)
                                    chainAborted = true
                                }
                            }
                        }
                    }
                }

                val finalText = acc
                if (!chainAborted) {
                    val wrote = withContext(Dispatchers.Main) { replaceText(source, finalText) }
                    if (!wrote) {
                        performHapticFeedback(HapticFeedbackConstants.REJECT)
                        showToast(getString(R.string.toast_replace_failed))
                        // Mirror the single path's restore-for-failed-write so the field does
                        // not keep whatever a spinner stage left in it.
                        if (fieldWrittenBySpinner) {
                            replaceText(source, restoreText)
                        }
                    } else {
                        lastOriginalText = chain.baseText
                        lastUndoSourceId = sourceId(source)
                        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    }
                } else if (fieldWrittenBySpinner) {
                    // A stage refused/failed after the spinner had touched the field: put
                    // back what the user was about to have transformed.
                    withContext(Dispatchers.Main) { replaceText(source, restoreText) }
                }
            } catch (e: TimeoutCancellationException) {
                spinnerJob?.cancelAndJoin()
                var restoreFailed = false
                try { restoreFailed = !replaceText(source, restoreText) } catch (_: Exception) { restoreFailed = true }
                showToast(
                    if (restoreFailed) getString(R.string.toast_restore_failed) + "\n" + getString(R.string.toast_request_timed_out)
                    else getString(R.string.toast_request_timed_out)
                )
            } catch (e: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    spinnerJob?.cancel()
                    if (currentJob === thisJob) {
                        try { replaceText(source, restoreText) } catch (_: Exception) {}
                    }
                }
                throw e
            } catch (e: Exception) {
                spinnerJob?.cancelAndJoin()
                withContext(Dispatchers.Main) { replaceText(source, restoreText) }
                showToast(mapErrorMessage(e.message ?: "Unknown error"))
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (currentJob === thisJob) {
                        cancelWatchdog()
                        scheduleProcessingReset()
                    }
                    spinnerJob?.cancel()
                    recycleIfUnowned(source)
                }
            }
        }
    }

    /** Replacer-stage join: "a" + ["b"] = "a b", empty sides handled by the survivor alone. */
    private fun joinStageText(text: String, replacement: String): String {
        if (text.isEmpty()) return replacement
        if (replacement.isEmpty()) return text
        return "$text $replacement"
    }

    private fun handleUndo(source: AccessibilityNodeInfo, currentText: String) {
        currentJob = serviceScope.launch {
            val thisJob = coroutineContext[Job]
            try {
                val previousText = lastOriginalText
                val undoId = lastUndoSourceId
                if (previousText == null || undoId != sourceId(source)) {
                    performHapticFeedback(HapticFeedbackConstants.REJECT)
                    showToast(getString(R.string.toast_nothing_to_undo))
                } else if (replaceText(source, previousText)) {
                    // Commit the new undo point only after the write succeeded. Doing it first
                    // meant a silently-failed replace destroyed the saved original text.
                    lastOriginalText = currentText
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                } else {
                    performHapticFeedback(HapticFeedbackConstants.REJECT)
                    showToast(getString(R.string.toast_undo_failed))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast(getString(R.string.toast_undo_failed))
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (currentJob === thisJob) {
                        cancelWatchdog()
                        scheduleProcessingReset()
                    }
                    recycleIfUnowned(source)
                }
            }
        }
    }

    private fun handleClipboardCommand(source: AccessibilityNodeInfo, precedingText: String, command: Command) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        currentJob = serviceScope.launch {
            val thisJob = coroutineContext[Job]
            try {
                val trigger = command.trigger
                when {
                    trigger.endsWith("copy") -> {
                        val textToCopy = precedingText.trim()
                        if (textToCopy.isEmpty()) {
                            performHapticFeedback(HapticFeedbackConstants.REJECT)
                            showToast(getString(R.string.toast_nothing_to_copy))
                        } else {
                            // The success decision must be made OUTSIDE the inner withContext:
                            // return@withContext exits only that lambda, so the success toast
                            // still fired — and since showToast dismisses the previous toast, it
                            // hid the failure message entirely.
                            val wrote = withContext(Dispatchers.Main) {
                                replaceText(source, precedingText, callerOwnsClipboard = true)
                            }
                            if (wrote) {
                                lastCopiedText = textToCopy
                                withContext(Dispatchers.Main) {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Ghostwriter", textToCopy))
                                }
                                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                showToast(getString(R.string.toast_copied))
                                statsManager.recordUsage(command.trigger)
                            } else {
                                performHapticFeedback(HapticFeedbackConstants.REJECT)
                                showToast(getString(R.string.toast_replace_failed))
                            }
                        }
                    }
                    trigger.endsWith("cut") -> {
                        val textToCut = precedingText.trim()
                        if (textToCut.isEmpty()) {
                            performHapticFeedback(HapticFeedbackConstants.REJECT)
                            showToast(getString(R.string.toast_nothing_to_cut))
                        } else {
                            val wrote = withContext(Dispatchers.Main) {
                                replaceText(source, "", callerOwnsClipboard = true)
                            }
                            if (wrote) {
                                lastCopiedText = textToCut
                                lastOriginalText = precedingText
                                lastUndoSourceId = sourceId(source)
                                withContext(Dispatchers.Main) {
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Ghostwriter", textToCut))
                                }
                                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                showToast(getString(R.string.toast_cut))
                                statsManager.recordUsage(command.trigger)
                            } else {
                                // Never claim "Cut to clipboard" while the text is still there.
                                performHapticFeedback(HapticFeedbackConstants.REJECT)
                                showToast(getString(R.string.toast_replace_failed))
                            }
                        }
                    }
                    trigger.endsWith("paste") -> handlePasteInto(source, precedingText, keepPrefix = precedingText, command = command)
                    trigger.endsWith("replace") -> handlePasteInto(source, precedingText, keepPrefix = "", command = command)
                    trigger.endsWith("clear") -> {
                        // Erase the WHOLE field — the user's text AND the trigger itself —
                        // without touching the clipboard: unlike cut, nothing is copied.
                        val wiped = withContext(Dispatchers.Main) {
                            replaceText(source, "")
                        }
                        if (wiped) {
                            // Undo restores what the user had typed, without the trigger
                            // (same convention as cut).
                            lastOriginalText = precedingText
                            lastUndoSourceId = sourceId(source)
                            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            showToast(getString(R.string.toast_cleared))
                            statsManager.recordUsage(command.trigger)
                        } else {
                            performHapticFeedback(HapticFeedbackConstants.REJECT)
                            showToast(getString(R.string.toast_replace_failed))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showToast(getString(R.string.toast_clipboard_failed))
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (currentJob === thisJob) {
                        cancelWatchdog()
                        scheduleProcessingReset()
                    }
                    recycleIfUnowned(source)
                }
            }
        }
    }

    /**
     * Handles `?paste` and `?replace`.
     *
     * [keepPrefix] is the text to keep in front of the pasted content: the text preceding
     * the trigger for `?paste`, empty for `?replace`.
     *
     * Prefers [pasteFromSystemClipboard], which drives the target app's own paste action so
     * the *real* system clipboard is used. The previous implementation read the clipboard
     * itself and fell back to [lastCopiedText], which could never work for text copied in
     * another app: an accessibility service is not the focused window and not the default
     * IME, and framework ClipboardService gates OP_READ_CLIPBOARD on exactly that — so
     * `primaryClip` was always null and `?paste` reported "Clipboard is empty" for anything
     * SwiftSlate had not copied itself. Writing is not gated, which is why `?copy` works and
     * why the fallback below can still stage text on the clipboard.
     */
    private suspend fun handlePasteInto(
        source: AccessibilityNodeInfo,
        precedingText: String,
        keepPrefix: String,
        command: Command
    ) {
        val pasted = pasteFromSystemClipboard(source, keepPrefix)
        if (pasted != null) {
            lastOriginalText = precedingText
            lastUndoSourceId = sourceId(source)
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            statsManager.recordUsage(command.trigger)
            return
        }

        // Either the clipboard is empty or the field ignored ACTION_SET_TEXT / ACTION_PASTE.
        // Fall back to the only clipboard content this service is allowed to know about: what
        // it last copied itself.
        val fallback = lastCopiedText
        if (fallback.isNullOrEmpty()) {
            performHapticFeedback(HapticFeedbackConstants.REJECT)
            showToast(getString(R.string.toast_clipboard_empty))
            return
        }
        if (replaceText(source, keepPrefix + fallback)) {
            lastOriginalText = precedingText
            lastUndoSourceId = sourceId(source)
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            statsManager.recordUsage(command.trigger)
        } else {
            performHapticFeedback(HapticFeedbackConstants.REJECT)
            showToast(getString(R.string.toast_replace_failed))
        }
    }

    /**
     * Strips the trigger, puts the caret after [keepPrefix], and asks the target app to paste.
     * The app performs the read under its own window focus, so this honours text copied
     * anywhere on the device without SwiftSlate ever reading the clipboard.
     *
     * Returns the resulting field text, or null when the field does not offer a paste action,
     * the app refused a step, or the paste landed somewhere unexpected. The field is left
     * untouched in the first case and holding [keepPrefix] in the others, both of which the
     * caller's fallback can recover from.
     */
    private suspend fun pasteFromSystemClipboard(
        source: AccessibilityNodeInfo,
        keepPrefix: String
    ): String? = withContext(Dispatchers.Main) {
        if (!source.refresh()) return@withContext null
        // Check before touching anything: TextView only advertises ACTION_PASTE when the field is
        // editable, has a selection, AND hasPrimaryClip() is true. Without this guard an empty
        // clipboard still got the trigger stripped by the setFieldText below, so the command
        // silently ate the user's "?paste" and then reported the clipboard was empty.
        val pasteSupported = source.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_PASTE
        }
        if (!pasteSupported) return@withContext null
        if (!setFieldText(source, keepPrefix)) return@withContext null
        delay(50)
        if (!source.refresh()) return@withContext null
        // Bail out if the field silently rejected the write, otherwise the paste would land
        // on top of the still-present trigger text.
        if (source.text?.toString() != keepPrefix) return@withContext null

        val caret = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, keepPrefix.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, keepPrefix.length)
        }
        source.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, caret)
        if (!source.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return@withContext null

        delay(100)
        if (!source.refresh()) return@withContext null
        val after = source.text?.toString() ?: return@withContext null
        // Nothing added => empty clipboard or an ignored paste. Not starting with keepPrefix
        // => the app ignored ACTION_SET_SELECTION and pasted somewhere else; treat both as a
        // failure so the caller's fallback can put the field into a known state.
        if (after == keepPrefix || !after.startsWith(keepPrefix)) return@withContext null
        scheduleTextVerification(source, after)
        after
    }

    /**
     * Writes [newText] into [source]. Returns false when the field could not be updated.
     * It previously returned Unit and signalled failure by returning early, which made every
     * failure invisible: handleUndo had already overwritten its saved original text, and the
     * restore paths could not tell a real restore from a silent no-op.
     */
    private suspend fun replaceText(
        source: AccessibilityNodeInfo,
        newText: String,
        // When the caller owns the clipboard after this call (?copy / ?cut), the paste fallback
        // must not restore or clear it — that destroyed the very clip the command just placed.
        callerOwnsClipboard: Boolean = false
    ): Boolean = withContext(Dispatchers.Main) {
        if (!source.refresh()) return@withContext false
        val bundle = Bundle()
        bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)

        val success = source.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)

        if (success) {
            // Verify the text actually persisted — some apps (Firefox, Google Keep)
            // return true but don't update their internal text state
            delay(100)
            if (!source.refresh()) {
                // The node was recycled during the verification delay. Reading .text now
                // would throw IllegalStateException; report the write as unverified and let
                // the caller's failure path handle it instead of failing the replacement.
                return@withContext false
            }
            val currentText = source.text?.toString()
            if (currentText == newText) {
                scheduleTextVerification(source, newText)
                return@withContext true // Text persisted
            }
            // Some editors (WebView-based, Samsung Notes, Keep) accept ACTION_SET_TEXT but
            // commit asynchronously — give them one longer window before declaring the write
            // ignored, so note-apps don't get a spurious clipboard fallback (#125).
            delay(400)
            if (!source.refresh()) return@withContext false
            val settledText = source.text?.toString()
            if (settledText == newText) {
                scheduleTextVerification(source, newText)
                return@withContext true // Text persisted late
            }
            // Text didn't persist, fall through to clipboard fallback
        }

        // Clipboard fallback: select all + paste (goes through app's input pipeline)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val oldClip = clipboard.primaryClip
        val newClip = ClipData.newPlainText("Ghostwriter Result", newText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            newClip.description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(newClip)

        if (!source.refresh() || source.text == null) {
            // We already replaced the clipboard above; bail out without leaving our temp clip
            // (which holds the transformed text) as the user's clipboard.
            if (!callerOwnsClipboard) restoreClipboard(clipboard, oldClip, newText)
            return@withContext false
        }
        val selectAllArgs = Bundle()
        selectAllArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
        selectAllArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, source.text?.length ?: 0)
        source.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs)

        val pasted = source.performAction(AccessibilityNodeInfo.ACTION_PASTE)

        scheduleTextVerification(source, newText)

        if (!callerOwnsClipboard) {
            // Deliberately does NOT touch `source`: scheduleTextVerification recycles the node
            // at +300ms, so source.refresh() here threw IllegalStateException on API < 33.
            // pendingClipRestore lets onInterrupt/onDestroy run this synchronously — both flush
            // the handler, which previously cancelled it and left SwiftSlate's temp clip (the
            // transformed text) as the user's clipboard indefinitely.
            val currentPending = Triple(clipboard, oldClip, newText)
            pendingClipRestore = currentPending
            handler.postDelayed({
                try {
                    restoreClipboard(clipboard, oldClip, newText)
                } catch (_: Exception) {
                } finally {
                    if (pendingClipRestore === currentPending) {
                        pendingClipRestore = null
                    }
                }
            }, 500)
        }
        // Report what the paste action actually returned. Returning an unconditional true here
        // silently defeated every caller's failure check.
        pasted
    }

    /**
     * Puts the user's clipboard back after the paste fallback in [replaceText].
     *
     * [oldClip] is null whenever the read was denied, which is the normal case: framework
     * ClipboardService gates OP_READ_CLIPBOARD on window focus / default-IME status, and an
     * accessibility service is neither. So this cannot verify what is currently on the
     * clipboard, and it cannot restore what was there before.
     *
     * Leaving SwiftSlate's temp clip in place is not an option — it holds the user's
     * transformed text and would be handed to every later paste, and IS_SENSITIVE only
     * applies from API 33. Previously the fallback therefore cleared the clipboard outright,
     * which destroyed whatever the user had copied on every replacement in an app that
     * ignores ACTION_SET_TEXT (Firefox, Google Keep). Restoring [lastCopiedText] instead
     * recovers the most recent clip this service actually knows about, and only falls back
     * to clearing when there is none.
     */
    private fun restoreClipboard(clipboard: ClipboardManager, oldClip: ClipData?, ourText: String) {
        try {
            val current = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (current != null && current != ourText) return // user copied something newer
            val recovered = oldClip ?: lastCopiedText?.let { ClipData.newPlainText("Ghostwriter", it) }
            clipboard.setPrimaryClip(recovered ?: ClipData.newPlainText("", ""))
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.safeRecycle() {
        try { recycle() } catch (_: Exception) {}
    }

    /** Recycle source only if scheduleTextVerification didn't take ownership. */
    private fun recycleIfUnowned(source: AccessibilityNodeInfo) {
        if (lastReplacedSource !== source) {
            source.safeRecycle()
        }
    }

    private fun scheduleTextVerification(source: AccessibilityNodeInfo, expectedText: String) {
        lastReplacedText = expectedText
        lastReplacedAt = System.currentTimeMillis()
        // Recycle the previous source if it's a different node
        val prev = lastReplacedSource
        if (prev != null && prev !== source) {
            prev.safeRecycle()
        }
        lastReplacedSource = source
        verifyRunnable?.let { handler.removeCallbacks(it) }
        val capturedSource = source
        val runnable = Runnable {
            try {
                if (!capturedSource.refresh()) return@Runnable
                val currentText = capturedSource.text?.toString()
                val isImeClobber = currentText != null && currentText.isNotEmpty() && expectedText.startsWith(currentText)
                if (isImeClobber && currentText != expectedText && currentText.length < expectedText.length) {
                    val bundle = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expectedText)
                    }
                    capturedSource.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                }
            } catch (_: Exception) {
            } finally {
                // Only recycle if this source is still the current one (not replaced by a newer command)
                if (lastReplacedSource === capturedSource) {
                    lastReplacedText = null
                    capturedSource.safeRecycle()
                    lastReplacedSource = null
                }
            }
        }
        verifyRunnable = runnable
        if (!handler.postDelayed(runnable, 300)) {
            lastReplacedText = null
            lastReplacedAt = 0L
            lastReplacedSource = null
        }
    }

    private fun setFieldText(source: AccessibilityNodeInfo, text: String): Boolean {
        if (!source.refresh()) return false
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return source.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    private fun startInlineSpinner(source: AccessibilityNodeInfo, baseText: String): Job {
        return serviceScope.launch(Dispatchers.Main) {
            var frameIndex = 0
            try {
                while (isActive) {
                    if (!setFieldText(source, "$baseText ${SPINNER_FRAMES[frameIndex]}")) break
                    frameIndex = (frameIndex + 1) % SPINNER_FRAMES.size
                    delay(200)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The node was recycled or the view went away mid-request; stop the spinner
                // instead of letting an unhandled exception kill the service. The AI result
                // path still restores the original text. See #125.
                Log.w(TAG, "spinner node went stale; stopping spinner", e)
            }
        }
    }

    /**
     * Runs a paste-fallback clipboard restore that has not fired yet. Both onInterrupt and
     * onDestroy call handler.removeCallbacksAndMessages(null), which cancelled the pending
     * +500ms restore and left SwiftSlate's temp clip (the user's transformed text) on the
     * clipboard for good.
     */
    private fun flushPendingClipRestore() {
        val pending = pendingClipRestore ?: return
        pendingClipRestore = null
        restoreClipboard(pending.first, pending.second, pending.third)
    }

    private fun mapErrorMessage(raw: String): String = getString(ErrorMessages.map(raw))

    private suspend fun showToast(msg: String) = withContext(Dispatchers.Main) {
        overlayToast.show(msg)
    }

    @Suppress("DEPRECATION")
    private fun performHapticFeedback(feedbackType: Int) {
        handler.post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    val vibrator = vibratorManager.defaultVibrator
                    when (feedbackType) {
                        HapticFeedbackConstants.CONFIRM ->
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                        HapticFeedbackConstants.REJECT ->
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    when (feedbackType) {
                        HapticFeedbackConstants.CONFIRM ->
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                        HapticFeedbackConstants.REJECT ->
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    vibrator.vibrate(50)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {
        flushPendingClipRestore()
        isProcessing.set(false)
        currentJob?.cancel()
        serviceJob.cancelChildren()
        handler.removeCallbacksAndMessages(null)
        lastReplacedText = null
        lastReplacedAt = 0L
        lastReplacedSource?.safeRecycle()
        lastReplacedSource = null
        overlayToast.dismiss()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Say "stopped" BEFORE releasing anything: the shutdown path below can throw on an
        // already-broken teardown, and the notification must not depend on this method
        // surviving. System-initiated kills, MIUI recycles, app updates — all funnel here,
        // so the shade always ends in the truthful stopped state.
        KeepAlive.postStopped(this)
        flushPendingClipRestore()
        isProcessing.set(false)
        lastReplacedText = null
        lastReplacedAt = 0L
        lastReplacedSource?.safeRecycle()
        lastReplacedSource = null
        handler.removeCallbacksAndMessages(null)
        overlayToast.dismiss()
        serviceScope.cancel()
    }
}

/**
 * Live redraws the target field while an AI command streams.
 *
 * Writes bypass [AssistantService.replaceText]'s verified path on purpose — verification
 * sleeps 100-500ms per call, which would turn each streamed chunk into a slideshow, and
 * its clipboard fallback would scribble partial text into the user's clipboard on hosts
 * that ignore ACTION_SET_TEXT. Live frames instead reuse [AssistantService.setFieldText]
 * (the same no-delay direct write the inline spinner performs), throttled to ~8 fps by
 * writing the LATEST accumulated text at most every 120ms. The FINAL text always lands
 * through the verified [AssistantService.replaceText] write, so the last live frame is
 * cosmetic only: correctness rests on the final write exactly as in the spinner path.
 *
 * Failed mid-stream writes are non-terminal: some hosts recycle the node mid-edit; the
 * renderer stops itself and lets the outcome handling decide what the field ends with.
 */
private class StreamRenderer(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val source: AccessibilityNodeInfo
) {
    private val latest = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var job: Job? = null

    /** True once at least one delta arrived — the write-below marker for the caller. */
    val sawDeltas: Boolean get() = started.get()

    fun onDelta(textSoFar: String) {
        latest.set(textSoFar)
        if (started.getAndSet(true)) return
        job = scope.launch(Dispatchers.Main) {
            try {
                while (isActive) {
                    latest.get()?.let { seen ->
                        if (!directSetText(source, ApiClientUtils.streamingDisplayText(seen))) cancel()
                    }
                    delay(120)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                cancel()
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
    }
}

// [AssistantService.setFieldText] is an instance member of the service; the renderer is a
// top-level class, so this tiny standalone mirrors it — refresh + ACTION_SET_TEXT, no delay.
private fun directSetText(source: AccessibilityNodeInfo, text: String): Boolean {
    if (!source.refresh()) return false
    val bundle = Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    return source.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
}
