package com.musheer360.swiftslate.model

import androidx.compose.runtime.Immutable

enum class CommandType {
    AI, TEXT_REPLACER
}

@Immutable
data class Command(
    val trigger: String,
    val prompt: String,
    val isBuiltIn: Boolean = false,
    val type: CommandType = CommandType.AI,
    /**
     * Screen commands read the visible content of the current window (a chat, an
     * email, an article) as CONTEXT instead of operating on selected text. They may
     * run with nothing typed before the trigger, bypass the usual <input>
     * transformation framing, and must be the only command in a chain.
     */
    val readsScreen: Boolean = false
)

/**
 * A resolved chain of trailing triggers (e.g. "Hello world ?fix ?formal").
 *
 * [commands] is ordered by application: index 0 is the innermost command (runs against
 * [baseText] first) and the last index is the outermost command, whose output is what
 * finally lands in the field. Kept in the model layer so the commands UI can preview
 * the same resolution the service executes.
 */
@Immutable
data class CommandChain(
    val commands: List<Command>,
    val baseText: String
)
