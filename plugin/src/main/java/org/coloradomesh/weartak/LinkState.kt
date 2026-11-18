package org.coloradomesh.weartak

import com.weartak.plugin.sdk.routestatus.RouteConnectionState
import com.weartak.plugin.sdk.routestatus.RouteStatusContract

/**
 * Where the plugin's link to its radio stands. [RadioLink] keeps one; the radio screen shows it,
 * and the plugin service reports it to WearTAK as the "MeshCore radio" route.
 *
 * Plain Kotlin, so it is tested without a device.
 */
sealed interface LinkState {
    /** WearTAK has not started the plugin, so there is no link to keep. */
    data object Off : LinkState

    /** Started, but no radio has been chosen on the radio screen yet. */
    data object NoRadio : LinkState

    /** Started, but the watch has not given this app Bluetooth permission. */
    data object NoPermission : LinkState

    /** Connecting to [radio], or connected and reading it; not ready for traffic yet. */
    data class Connecting(val radio: String) : LinkState

    /** Connected to [radio] and through start-up. */
    data class Connected(val radio: String) : LinkState

    /** The link to [radio] ended or could not be made, for [reason]; the next try is in [retrySeconds]. */
    data class Waiting(val radio: String, val reason: String, val retrySeconds: Long) : LinkState
}

/** What the plugin tells WearTAK about its route: a [RouteConnectionState] and a line of text. */
data class RouteReport(val state: Int, val text: String)

/** The route report for this state, or null when the plugin should have no route at all. */
fun LinkState.route(): RouteReport? {
    val (state, text) = when (this) {
        LinkState.Off -> return null
        LinkState.NoRadio -> RouteConnectionState.DISCONNECTED to "No radio chosen. Tap MeshCore in Plugins"
        LinkState.NoPermission -> RouteConnectionState.ERROR to "Needs Bluetooth permission. Tap MeshCore in Plugins"
        is LinkState.Connecting -> RouteConnectionState.CONNECTING to "Connecting to $radio"
        is LinkState.Connected -> RouteConnectionState.CONNECTED to radio
        is LinkState.Waiting -> RouteConnectionState.DISCONNECTED to "$reason. Retry in ${retryText(retrySeconds)}"
    }
    return RouteReport(state, fitBytes(text, RouteStatusContract.MAX_STATUS_TEXT_BYTES))
}

/** One line for the radio screen. */
fun LinkState.summary(): String = when (this) {
    LinkState.Off -> "Not running: turn MeshCore on in WearTAK's Plugins"
    LinkState.NoRadio -> "No radio chosen"
    LinkState.NoPermission -> "Bluetooth permission needed"
    is LinkState.Connecting -> "Connecting to $radio"
    is LinkState.Connected -> "Connected to $radio"
    is LinkState.Waiting -> "$reason. Next try in ${retryText(retrySeconds)}"
}

/**
 * The link's reason for ending, in words for the watch. A radio that is connected to another app
 * (WearMesh, the MeshCore app) stops advertising and cannot be reached, which looks the same as
 * one that is off or out of range, so that case names all three.
 */
fun friendlyReason(reason: String): String = when {
    reason.startsWith("Could not connect") -> "Can't reach the radio: off, out of range, or in use by another app"
    reason.startsWith("Connection lost") -> "Lost the radio"
    else -> reason
}

private fun retryText(seconds: Long): String =
    if (seconds < 60) "$seconds s" else "${(seconds + 30) / 60} min"

/**
 * [text] cut to at most [maxBytes] of UTF-8, ending with an ellipsis when cut. WearTAK refuses a
 * route report whose text is too long, and a radio's name or a link error can be long.
 */
fun fitBytes(text: String, maxBytes: Int): String {
    if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
    val ellipsis = "…"
    val budget = maxBytes - ellipsis.toByteArray(Charsets.UTF_8).size
    var used = 0
    var end = 0
    while (end < text.length) {
        val next = if (Character.isHighSurrogate(text[end]) && end + 1 < text.length) end + 2 else end + 1
        val bytes = text.substring(end, next).toByteArray(Charsets.UTF_8).size
        if (used + bytes > budget) break
        used += bytes
        end = next
    }
    return text.substring(0, end).trimEnd() + ellipsis
}

/** Channel slots a radio has: [maxChannels] as it reports it, or 8 for firmware too old to say. */
fun channelSlots(maxChannels: Int): Int = if (maxChannels > 0) maxChannels else 8

/**
 * Whether the radio was still answering at the end of start-up: it answered at least one of its
 * [slots] channel reads ([unanswered] timed out). A radio that goes quiet after the device query
 * times out on every one. Some slots unanswered means a slow or flaky radio that is still there,
 * which is left connected rather than retried for ever.
 */
fun startUpHeard(slots: Int, unanswered: Int): Boolean = slots == 0 || unanswered < slots

/** How long to wait before trying the radio again. */
object Backoff {
    /** After the first failure. Doubles with each failure in a row. */
    const val FIRST_MS = 15_000L

    /** The longest pause, as in WearMesh: the radio may be away for hours, and each try costs battery. */
    const val MAX_MS = 300_000L

    /** The pause after [failures] failures in a row (1 or more). */
    fun pauseMs(failures: Int): Long =
        (FIRST_MS shl (failures - 1).coerceIn(0, 5)).coerceAtMost(MAX_MS)
}
