package com.mooncc.teamspeak6.domain.model

/**
 * TeamSpeak marks spacer channels by prefixing the name with `[*spacerN]`,
 * `[cspacerN]`, `[lspacerN]`, or `[rspacerN]`. The suffix number only has to be
 * unique per server, it carries no meaning for rendering.
 */
object ChannelSpacer {

    data class Info(val alignment: SpacerAlignment, val label: String)

    private val REGEX = Regex("""^\[([*clr])spacer(\d*)](.*)$""")

    /** Returns spacer metadata, or `null` when [name] is a regular channel. */
    fun parse(name: String): Info? {
        val match = REGEX.matchEntire(name) ?: return null
        val alignment = when (match.groupValues[1].lowercase()) {
            "*" -> SpacerAlignment.REPEAT
            "c" -> SpacerAlignment.CENTER
            "r" -> SpacerAlignment.RIGHT
            else -> SpacerAlignment.LEFT
        }
        return Info(alignment, match.groupValues[3])
    }
}
