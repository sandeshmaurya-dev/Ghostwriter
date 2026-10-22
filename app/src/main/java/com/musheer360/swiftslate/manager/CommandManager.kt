package com.musheer360.swiftslate.manager

import android.content.Context
import android.content.SharedPreferences
import com.musheer360.swiftslate.model.Command
import com.musheer360.swiftslate.model.CommandChain
import com.musheer360.swiftslate.model.CommandType
import org.json.JSONArray
import org.json.JSONObject

class CommandManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("commands", Context.MODE_PRIVATE)
    private val settingsPrefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Volatile
    private var cachedCommands: List<Command>? = null
    @Volatile
    private var cacheTimestamp = 0L
    /**
     * Raw JSON [cachedCommands] was parsed from, so an expired TTL can be revalidated with a
     * string compare instead of re-parsing every command. The TTL has to stay: the UI and the
     * accessibility service hold separate CommandManager instances in one process, and this is
     * how the service notices commands edited in the UI. But it fired on the service's
     * keystroke path, so the parse ran again every 5s of typing for no change.
     */
    @Volatile
    private var cachedCommandsJson: String? = null
    /**
     * Prefix [cachedCommands] was built with. Part of the cache key because built-in triggers
     * are derived from it — with a JSON-only check, changing the prefix while no custom commands
     * exist leaves custom_commands as "[]" and the other instance would keep serving built-ins
     * under the old prefix forever.
     */
    @Volatile
    private var cachedPrefix: String? = null
    // Typed prefs reads can throw ClassCastException on a corrupted store; this runs on the
    // accessibility service's bind path, where an escape would kill the whole process (#125).
    private var aiCommandsSeeded =
        try { prefs.getBoolean("ai_commands_seeded", false) } catch (_: Exception) { false }
    private var screenDefaultsEnsured =
        try { prefs.getBoolean("screen_defaults_ensured", false) } catch (_: Exception) { false }

    /**
     * Resolves a chain of trailing triggers, e.g. "Hello world ?fix ?formal".
     *
     * Detection scans right-to-left (the longest/rightmost trigger matches first), so
     * [commands] comes back in application order: the INNERMOST command (applied to the user's
     * raw text first) at index 0 and the outermost (the one the service would have matched
     * on its own) last. The final transformation the user sees therefore stays the last one
     * they typed, which keeps single-command behaviour byte-identical to the old path.
     *
     * System commands (undo/copy/cut/paste/replace) never participate in a chain: the service
     * intercepts them as the sole command before the AI/replacer paths run, and an inner one
     * (e.g. "hi ?copy ?fix") has no meaningful stage semantics — so discovery stops there and
     * such a lone system command is returned as a one-element chain.
     *
     * [baseText] is the text beneath the innermost trigger — the input every chain executor
     * starts from. Computed incrementally while stripping, so a partially-recognized tail
     * (no stage found beyond one) still yields the correct remainder.
     */
    fun buildChain(text: String, maxDepth: Int = MAX_CHAIN_DEPTH): CommandChain? {
        val found = mutableListOf<Command>()
        var rest = text.trimEnd()
        var baseText = ""
        while (found.size < maxDepth) {
            val cmd = findCommand(rest) ?: break
            val before = if (rest.endsWith(cmd.trigger, ignoreCase = true)) {
                rest.substring(0, rest.length - cmd.trigger.length)
            } else break
            if (cmd.isBuiltIn && SYSTEM_TRIGGERS.any { cmd.trigger.endsWith(it) }) {
                if (found.isEmpty()) {
                    found.add(cmd)
                } else {
                    // An inner system command truncates the chain: the text before it becomes
                    // the base the already-found stages run against ("a ?undo ?fix" -> ?fix
                    // sees "a"), never the literal "?undo" string handed to a prompt.
                    baseText = before.trim()
                }
                break
            }
            found.add(cmd)
            rest = before
            baseText = rest.trim()
            if (baseText.isEmpty()) break
        }
        if (found.isEmpty()) return null
        return CommandChain(commands = found.asReversed().toList(), baseText = baseText)
    }

    companion object {
        const val DEFAULT_PREFIX = "?"
        const val PREF_TRIGGER_PREFIX = "trigger_prefix"
        private const val CACHE_TTL_MS = 5_000L

        /** Caps chained commands so a pathological tail cannot fan out into unbounded AI calls. */
        const val MAX_CHAIN_DEPTH = 4

        /** Limits enforced on every write path — see [isValidCommand] / [importCommands]. */
        const val MAX_TRIGGER_LENGTH = 50
        const val MAX_PROMPT_LENGTH = 5_000
        const val MAX_CUSTOM_COMMANDS = 100

        /**
         * Whether a custom command is storable. Applied by both [saveCustomCommand] and
         * [importCommands]: the limits used to live only in the import path, so the UI could
         * create commands that the app's own exported backup would then refuse to import.
         */
        fun isValidCommand(trigger: String, prompt: String, prefix: String): Boolean =
            trigger.isNotBlank() && prompt.isNotBlank() &&
                trigger.length <= MAX_TRIGGER_LENGTH && prompt.length <= MAX_PROMPT_LENGTH &&
                trigger.startsWith(prefix) && trigger.length > prefix.length

        /** Triggers intercepted by the service before any chained stage semantics apply. */
        val SYSTEM_TRIGGERS = listOf("undo", "copy", "cut", "paste", "replace", "clear")
    }

    // System commands — local operations that cannot be edited or deleted
    private val systemDefinitions = listOf(
        "undo" to "Undo the last replacement and restore the original text.",
        "copy" to "Copy the text to clipboard.",
        "cut" to "Cut the text to clipboard.",
        "paste" to "Paste from clipboard.",
        "replace" to "Replace text with clipboard content.",
        "clear" to "Erase the whole field, including the trigger text.",
        "translate:xx" to "Translate text to any language code (e.g. ?translate:es, ?translate:fr)."
    )

    // Default AI commands — seeded into custom commands on first run so users can edit/delete them
    // (name, prompt, readsScreen). Screen commands read the visible window content — their
    // prompts are Tasks over that snapshot, never text rewrites.
    private val defaultAiDefinitions = listOf(
        Triple("fix", "Fix grammar, spelling, and punctuation errors.", false),
        Triple("improve", "Rewrite to improve clarity, flow, and coherence.", false),
        Triple("shorten", "Rewrite to be more concise while preserving the core meaning.", false),
        Triple("expand", "Rewrite with more detail. Elaborate only on what is stated or widely known \u2014 do not fabricate information.", false),
        Triple("formal", "Rewrite in a formal, professional tone.", false),
        Triple("casual", "Rewrite in a casual, friendly tone.", false),
        Triple("emoji", "Add relevant emojis throughout.", false),
        Triple("human", "Rewrite to sound naturally human, not AI-generated. Never use emdashes or semicolons, use commas or periods instead. Drop AI clichés and filler phrases. Use contractions, everyday words, and varied sentence lengths. Keep all facts, names, and numbers intact.", false),
        Triple(
            "reply",
            "Draft the next message that the user \u2014 the person whose phone this is \u2014 would send in this conversation. Read the conversation for context, then write ONLY the reply message text itself: no quotes, no explanation, no signature. Match the conversation's language, register and emoji style.",
            true
        ),
        Triple(
            "summary",
            "Summarize the content read from the screen in a few short sentences. Use the same language as the content.",
            true
        )
    )

    /** The reply prompt before screen-context was introduced; owned by the one-time migration. */
    private val OLD_REPLY_PROMPT = "Generate a contextual reply to this message."

    /** Drops the cache and its validity key so the next [getCommands] rebuilds from prefs. */
    private fun invalidateCache() {
        cachedCommands = null
        cachedCommandsJson = null
        cachedPrefix = null
    }

    fun getTriggerPrefix(): String {
        return try {
            settingsPrefs.getString(PREF_TRIGGER_PREFIX, DEFAULT_PREFIX) ?: DEFAULT_PREFIX
        } catch (_: Exception) {
            DEFAULT_PREFIX
        }
    }

    @Synchronized fun setTriggerPrefix(newPrefix: String): Boolean {
        if (newPrefix.length != 1 || newPrefix[0].isLetterOrDigit() || newPrefix[0].isWhitespace()) return false
        // Write prefix first so crash between writes is self-healing on retry
        settingsPrefs.edit().putString(PREF_TRIGGER_PREFIX, newPrefix).apply()
        // Migrate custom command triggers — idempotent: always fix commands not matching current prefix
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = try { JSONArray(customStr) } catch (_: Exception) {
            prefs.edit().putString("custom_commands", "[]").apply()
            invalidateCache()
            return true
        }
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val oldTrigger = obj.optString("trigger", "")
            val prompt = obj.optString("prompt", "")
            if (oldTrigger.isEmpty() || prompt.isEmpty()) continue
            val migrated = if (!oldTrigger.startsWith(newPrefix)) {
                // Strip any single-char non-alphanumeric prefix, then apply new prefix
                val stripped = if (!oldTrigger[0].isLetterOrDigit()) oldTrigger.substring(1) else oldTrigger
                newPrefix + stripped
            } else oldTrigger
            val newObj = JSONObject()
            newObj.put("trigger", migrated)
            newObj.put("prompt", prompt)
            newObj.put("type", obj.optString("type", CommandType.AI.name))
            if (obj.optBoolean("reads_screen", false)) newObj.put("reads_screen", true)
            newArr.put(newObj)
        }
        prefs.edit().putString("custom_commands", newArr.toString()).apply()
        invalidateCache()
        return true
    }

    private fun getBuiltInCommands(): List<Command> {
        val prefix = getTriggerPrefix()
        return systemDefinitions.map { (name, prompt) -> Command("$prefix$name", prompt, true) }
    }

    private fun seedDefaultAiCommands() {
        val prefix = getTriggerPrefix()
        migrateReplyToScreen(prefix)
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = try { JSONArray(customStr) } catch (_: Exception) { JSONArray() }
        val existingTriggers = (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it)?.optString("trigger")?.takeIf { t -> t.isNotEmpty() } }
            .toSet()
        var added = false
        for ((name, prompt, readsScreen) in defaultAiDefinitions) {
            val trigger = "$prefix$name"
            if (trigger !in existingTriggers) {
                val obj = JSONObject()
                obj.put("trigger", trigger)
                obj.put("prompt", prompt)
                obj.put("type", CommandType.AI.name)
                if (readsScreen) obj.put("reads_screen", true)
                arr.put(obj)
                added = true
            }
        }
        val editor = prefs.edit()
        if (added) {
            editor.putString("custom_commands", arr.toString())
            invalidateCache()
        }
        editor.putBoolean("ai_commands_seeded", true).apply()
        aiCommandsSeeded = true
    }

    /**
     * One-time for UPDATES, not fresh installs: the screen-context ship added the
     * "summary" default and upgraded "reply", but [seedDefaultAiCommands] only ever
     * ran once (ai_commands_seeded), so updated installs would never see the two new
     * commands. Runs at the top of [getCommands] until it succeeds once; off the
     * keystroke hot path after that.
     */
    @Synchronized private fun ensureScreenDefaults() {
        if (screenDefaultsEnsured) return
        seedDefaultAiCommands()
        prefs.edit().putBoolean("screen_defaults_ensured", true).apply()
        screenDefaultsEnsured = true
    }

    /**
     * One-time migration: "clear" did not exist as a system command, and installs that
     * picked it up as a CUSTOM command got a plain text-replacer that wrote its prompt
     * into the field instead of erasing it. Remove any custom impostor so the real
     * system "clear" takes over. Case-insensitive because IME-typed triggers may have
     * been stored capitalized.
     */
    @Synchronized private fun migrateClearToSystem(prefix: String) {
        if (prefs.getBoolean("clear_migrated", false)) return
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        try {
            val arr = JSONArray(customStr)
            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val trigger = obj.optString("trigger", "")
                if (trigger.equals(prefix + "clear", ignoreCase = true)) continue
                newArr.put(obj)
            }
            if (newArr.length() != arr.length()) {
                prefs.edit().putString("custom_commands", newArr.toString()).apply()
                invalidateCache()
            }
        } catch (_: Exception) {
            // Corrupt store — getCommands already self-heals it; never block on migration.
        }
        prefs.edit().putBoolean("clear_migrated", true).apply()
    }

    /**
     * One-time migration: installs before screen-context existed, so their seeded
     * "?reply" still carries the old transform-on-selection prompt. Rewrite it to the
     * screen-context prompt + flag (only when the user has not replaced the prompt
     * themselves — a customized command is left alone). No-op for fresh installs,
     * whose seed already writes the screen version.
     */
    private fun migrateReplyToScreen(prefix: String) {
        // Fresh flag: the original screen_reply_migrated was already consumed on update
        // installs whose stored reply prompt didn't match the legacy text — the upgrade
        // never actually ran there. This pass is idempotent, so a new guard is safe.
        if (prefs.getBoolean("screen_reply_upgraded", false)) return
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = try { JSONArray(customStr) } catch (_: Exception) {
            prefs.edit().putBoolean("screen_reply_upgraded", true).apply()
            return
        }
        var changed = false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            // Match ANY "?reply" that still lacks the screen flag — not just the exact
            // legacy prompt. Earlier builds seeded several prompt wordings, so an
            // exact-match check could ping off a prompt it didn't know about and mark the
            // migration done without ever upgrading anything, leaving that install with a
            // reply command that can never run from an empty field.
            if (obj.optString("trigger").equals(prefix + "reply", ignoreCase = true) &&
                !obj.optBoolean("reads_screen", false)
            ) {
                obj.put("prompt", defaultAiDefinitions.first { it.first == "reply" }.second)
                obj.put("reads_screen", true)
                changed = true
            }
        }
        if (changed) {
            prefs.edit().putString("custom_commands", arr.toString()).apply()
            invalidateCache()
        }
        prefs.edit().putBoolean("screen_reply_upgraded", true).apply()
    }

    @Volatile
    private var migrating = false

    @Synchronized fun getCommands(): List<Command> {
        if (!aiCommandsSeeded) {
            seedDefaultAiCommands()
        }
        if (!screenDefaultsEnsured) {
            // Update path: installs predating screen-context must get the new defaults
            // (summary added, reply upgraded) exactly once, cheaply (gated boolean).
            ensureScreenDefaults()
        }
        if (!prefs.getBoolean("clear_migrated", false)) {
            migrateClearToSystem(getTriggerPrefix())
        }
        // Same direct placement as migrateClearToSystem: migrateReplyToScreen also lives
        // behind seedDefaultAiCommands in its original call site, but that seed is skipped
        // whenever aiCommandsSeeded is already true — which is every update install. The
        // consequence was update installs keeping a ?reply without the screen flag forever
        // (confirmed on-device: "command matched: '?reply' screen=false" in logcat while
        // the seed's own migration flag sat consumed ). Both migrations are idempotent and
        // one-shot-flag-guarded, so an unconditional call here costs one prefs.getBoolean.
        if (!prefs.getBoolean("screen_reply_upgraded", false)) {
            migrateReplyToScreen(getTriggerPrefix())
        }
        val now = System.currentTimeMillis()
        val cached = cachedCommands
        if (cached != null && now - cacheTimestamp < CACHE_TTL_MS) return cached
        val prefix = getTriggerPrefix()
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        if (cached != null && customStr == cachedCommandsJson && prefix == cachedPrefix) {
            cacheTimestamp = now
            return cached
        }
        // Guard against a corrupted store — an unhandled JSONException here would
        // crash the accessibility service on every text-change event with no recovery.
        val arr = try { JSONArray(customStr) } catch (_: Exception) {
            prefs.edit().putString("custom_commands", "[]").apply()
            JSONArray()
        }
        val customCommands = mutableListOf<Command>()
        var needsMigration = false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val trigger = obj.optString("trigger", "")
            val prompt = obj.optString("prompt", "")
            if (trigger.isEmpty() || prompt.isEmpty()) continue
            if (!trigger.startsWith(prefix)) needsMigration = true
            customCommands.add(Command(trigger, prompt, false,
                try { CommandType.valueOf(obj.optString("type", CommandType.AI.name)) } catch (_: Exception) { CommandType.AI },
                obj.optBoolean("reads_screen", false)))
        }
        // Self-heal prefix mismatch (e.g. crash between two apply() calls in setTriggerPrefix)
        if (needsMigration && !migrating) {
            migrating = true
            try {
                setTriggerPrefix(prefix)
                return getCommands()
            } finally {
                migrating = false
            }
        }
        val result = (getBuiltInCommands() + customCommands).sortedByDescending { it.trigger.length }
        cachedCommands = result
        cachedCommandsJson = customStr
        cachedPrefix = prefix
        cacheTimestamp = System.currentTimeMillis()
        return result
    }

    /**
     * Stores [command], replacing [replacing] in the same write.
     *
     * [replacing] defaults to the command's own trigger, which makes this an upsert; pass the
     * old trigger to rename. The Commands screen used to call removeCustomCommand() then
     * saveCustomCommand() when saving a rename — two separate prefs writes, so a failure between
     * them left the command deleted and not re-added.
     *
     * Returns false if the command is not storable, in which case nothing is written.
     */
    @Synchronized fun saveCustomCommand(command: Command, replacing: String = command.trigger): Boolean {
        if (!isValidCommand(command.trigger, command.prompt, getTriggerPrefix())) return false
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = try { JSONArray(customStr) } catch (_: Exception) { JSONArray() }
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val trigger = obj.optString("trigger")
            if (trigger != replacing && trigger != command.trigger) {
                newArr.put(obj)
            }
        }
        val newObj = JSONObject()
        newObj.put("trigger", command.trigger)
        newObj.put("prompt", command.prompt)
        newObj.put("type", command.type.name)
        if (command.readsScreen) newObj.put("reads_screen", true)
        newArr.put(newObj)
        prefs.edit().putString("custom_commands", newArr.toString()).apply()
        invalidateCache()
        return true
    }

    @Synchronized fun removeCustomCommand(trigger: String) {
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = try { JSONArray(customStr) } catch (_: Exception) { JSONArray() }
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("trigger") != trigger) {
                newArr.put(obj)
            }
        }
        prefs.edit().putString("custom_commands", newArr.toString()).apply()
        invalidateCache()
    }

    @Synchronized fun exportCommands(): String {
        return prefs.getString("custom_commands", "[]") ?: "[]"
    }

    /**
     * Imports commands from a backup, sanitizing entries instead of rejecting the whole file.
     *
     * Only a genuinely malformed file (unparseable JSON) fails hard. Individual entries are
     * cleaned so that backups from older versions — which could store prompts longer than
     * [MAX_PROMPT_LENGTH] or triggers with a stale prefix — still restore: triggers are
     * truncated to [MAX_TRIGGER_LENGTH] and migrated to the current prefix, prompts truncated
     * to [MAX_PROMPT_LENGTH], unknown types default to [CommandType.AI], and unusable entries
     * (blank trigger/prompt) are dropped. The result is capped at [MAX_CUSTOM_COMMANDS].
     */
    @Synchronized fun importCommands(json: String): Boolean {
        return try {
            val arr = JSONArray(json)
            val prefix = getTriggerPrefix()
            val cleaned = JSONArray()
            for (i in 0 until arr.length()) {
                if (cleaned.length() >= MAX_CUSTOM_COMMANDS) break
                val obj = arr.optJSONObject(i) ?: continue
                var trigger = obj.optString("trigger", "").trim()
                val prompt = obj.optString("prompt", "").take(MAX_PROMPT_LENGTH)
                if (trigger.isEmpty() || prompt.isBlank()) continue
                if (!trigger.startsWith(prefix)) {
                    // Same migration as setTriggerPrefix: strip any leading non-alphanumeric
                    // char, then apply the current prefix.
                    val stripped = if (!trigger[0].isLetterOrDigit()) trigger.substring(1) else trigger
                    trigger = prefix + stripped
                }
                trigger = trigger.take(MAX_TRIGGER_LENGTH)
                if (trigger.length <= prefix.length) continue
                val type = obj.optString("type", CommandType.AI.name)
                val out = JSONObject()
                out.put("trigger", trigger)
                out.put("prompt", prompt)
                out.put("type",
                    if (type == CommandType.TEXT_REPLACER.name) CommandType.TEXT_REPLACER.name else CommandType.AI.name)
                if (obj.optBoolean("reads_screen", false)) out.put("reads_screen", true)
                cleaned.put(out)
            }
            if (arr.length() > 0 && cleaned.length() == 0) return false
            prefs.edit().putString("custom_commands", cleaned.toString()).apply()
            invalidateCache()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun findCommand(text: String): Command? {
        // IME auto-capitalization: the field starts a "sentence" when empty and the keyboard's
        // pending shift turns "?reply" into "?Reply"; autocorrect can also append a trailing space
        // ("?reply "). Match on the trimmed text, ignoring case, so both still fire.
        val matchText = text.trimEnd()
        val commands = getCommands()
        for (cmd in commands) {  // Already sorted by trigger length in getCommands()
            if (cmd.trigger.endsWith("translate:xx")) continue
            if (matchText.endsWith(cmd.trigger, ignoreCase = true)) {
                return cmd
            }
        }
        val prefix = getTriggerPrefix()
        // Translate trigger — accepts language names or codes up to 15 characters
        // ("hindi", "english", "en", "pt-BR", …). The service debounces translate
        // matches, so typing still reaches the full name before it fires: partial
        // input that happens to match another language ("hin" of "hindi") simply
        // re-schedules the commit instead of firing mid-word. The AI model handles
        // unknown or invalid names gracefully.
        val translatePrefix = "${prefix}translate:"
        val translateIdx = lastIndexOfIgnoreCase(matchText, translatePrefix)
        if (translateIdx >= 0) {
            val langPart = matchText.substring(translateIdx + translatePrefix.length)
            if (langPart.length in 2..15 && langPart.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }) {
                return Command("${translatePrefix}$langPart", "Translate the text to '$langPart' (a language name or code). Respond with ONLY the translated text.", true)
            }
        }
        return null
    }

    /**
     * Case-insensitive [String.lastIndexOf] over ASCII letters. Written char-by-char
     * instead of uppercasing the whole text because locale-aware case folding can change
     * string length (ß→SS, İ→İ), which would corrupt the substring indices used by the
     * translate parameter extraction above.
     */
    private fun lastIndexOfIgnoreCase(text: String, needle: String): Int {
        if (needle.isEmpty()) return text.length
        var i = text.length - needle.length
        while (i >= 0) {
            var j = 0
            while (j < needle.length) {
                val c = text[i + j]
                val n = needle[j]
                if (c != n && c.uppercaseChar() != n.uppercaseChar()) break
                j++
            }
            if (j == needle.length) return i
            i--
        }
        return -1
    }
}
