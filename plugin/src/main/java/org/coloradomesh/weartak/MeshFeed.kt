package org.coloradomesh.weartak

import org.coloradomesh.meshcore.protocol.Channel
import org.coloradomesh.meshcore.protocol.ChannelMessage
import org.coloradomesh.meshcore.protocol.Contact
import org.coloradomesh.meshcore.protocol.DirectMessage
import org.coloradomesh.meshcore.protocol.SelfInfo
import org.coloradomesh.meshcore.tak.ClientIdentity
import org.coloradomesh.meshcore.tak.CotEvents
import org.coloradomesh.meshcore.tak.MarkerSpec
import org.coloradomesh.meshcore.tak.MeshPicture
import java.time.Duration
import java.time.Instant

/**
 * Turns what the radio reports into CoT for WearTAK (M3): node markers, and channel and direct
 * messages as GeoChat. The mapping is MeshPicture's, shared with the bridge, so nodes and
 * channels have the same uids and names in WearTAK as in ATAK.
 *
 * What it produces goes to [send], one [Item] at a time; the plugin service hands each to the
 * transport. Plain Kotlin, so it's tested without a device. Thread-safe: the radio's events come
 * on the link's thread, the user's callsign on a Binder thread, refreshes on the main thread.
 *
 * Shaped for a watch:
 * - Only nodes with a real position, heard in the last [MAX_AGE] and at most [MAX_MARKERS] of
 *   them, newest first. (MeshPicture also places channels and position-less companions at 0,0
 *   so ATAK can list them as contacts; on WearTAK's map they'd sit off Africa.)
 * - Markers stay [MARKER_STALE] and are sent again every [REFRESH] (see [refreshMarkers]), and
 *   one is sent at once when its node changes. WearTAK admits a limited number of messages per
 *   transport session, so the plugin sends no more than it needs to.
 * - Chat is addressed to the user's callsign ([WearTakChat]), so it waits until WearTAK has
 *   said what that is.
 */
class MeshFeed(
    private val send: (Item) -> Unit,
    private val clock: () -> Instant = Instant::now,
) {
    /** One CoT event for WearTAK, with the identity the transport reports it under. */
    data class Item(val messageId: String, val senderUid: String, val senderCallsign: String?, val xml: String)

    private val picture = MeshPicture(APP_NAME).apply { maxAgeSeconds = MAX_AGE.seconds }
    private var callsign: String? = null
    private var ready = false

    /** When each node's marker was last advertised, by marker uid, to put the newest first. */
    private val lastAdvert = HashMap<String, Long>()

    /** Messages that came before the user's callsign was known, oldest first. */
    private val held = ArrayDeque<Any>()

    @Synchronized
    fun setCallsign(value: String) {
        val known = value.trim().takeIf { it.isNotEmpty() } ?: return
        callsign = known
        while (held.isNotEmpty()) deliver(held.removeFirst())
    }

    @Synchronized
    fun onSelf(info: SelfInfo) = picture.setSelf(info)

    @Synchronized
    fun onContact(contact: Contact) {
        picture.putContact(contact)
        lastAdvert[picture.markerUid(contact)] = contact.lastAdvert
        if (!ready) return
        val uid = picture.markerUid(contact)
        visibleMarkers().firstOrNull { it.uid == uid }?.let(::sendMarker)
    }

    @Synchronized
    fun onChannels(channels: List<Channel>) = picture.setChannels(channels)

    /** Start-up is through: put the nodes on the map. */
    @Synchronized
    fun onReady() {
        ready = true
        refreshMarkersLocked()
    }

    @Synchronized
    fun onChannelMessage(message: ChannelMessage) = deliverOrHold(message)

    @Synchronized
    fun onDirectMessage(message: DirectMessage) = deliverOrHold(message)

    /** Sends every marker again, before the last ones go stale. Called every [REFRESH]. */
    @Synchronized
    fun refreshMarkers() {
        if (ready) refreshMarkersLocked()
    }

    /** The link ended or the plugin stopped: forget the radio's picture until it's read again. */
    @Synchronized
    fun reset() {
        ready = false
        picture.clear()
        lastAdvert.clear()
        held.clear()
    }

    private fun refreshMarkersLocked() {
        for (marker in visibleMarkers()) sendMarker(marker)
    }

    /** The markers worth a watch's map: real positions, newest adverts first, at most [MAX_MARKERS]. */
    private fun visibleMarkers(): List<MarkerSpec> =
        picture.markers(clock().epochSecond)
            .filter { it.latitude != 0.0 || it.longitude != 0.0 }
            .filterNot { it.uid.startsWith(MECP_PREFIX) }
            .sortedByDescending { lastAdvert[it.uid] ?: 0L }
            .take(MAX_MARKERS)

    private fun sendMarker(marker: MarkerSpec) {
        val now = clock()
        val xml = CotEvents.marker(marker.copy(staleAfter = MARKER_STALE), now)
        send(Item("${marker.uid}.${now.toEpochMilli()}", marker.uid, marker.callsign, xml))
    }

    private fun deliverOrHold(message: Any) {
        if (callsign == null) {
            held.addLast(message)
            while (held.size > MAX_HELD) held.removeFirst()
            return
        }
        deliver(message)
    }

    private fun deliver(message: Any) {
        val to = callsign ?: return
        // MeshPicture addresses chat to an ATAK user; WearTakChat uses only the sender fields.
        val user = ClientIdentity(USER_UID, to)
        val spec = when (message) {
            is ChannelMessage -> picture.chatLine(message, user) ?: return
            is DirectMessage -> picture.directLine(message, user)
            else -> return
        }
        send(Item(spec.messageId, spec.senderUid, spec.senderCallsign, WearTakChat.direct(spec, to, clock())))
    }

    companion object {
        /** Put in marker remarks by MeshPicture ("linked through …"). */
        const val APP_NAME = "MeshCore for WearTAK"

        val MAX_AGE: Duration = Duration.ofHours(24)
        const val MAX_MARKERS = 50
        val MARKER_STALE: Duration = Duration.ofMinutes(30)
        val REFRESH: Duration = Duration.ofMinutes(15)

        /** Messages kept while waiting for the user's callsign. */
        const val MAX_HELD = 50

        /** MECP (emergency) markers aren't sent yet; a later step. */
        private const val MECP_PREFIX = "MECP-"

        /** Stands in for the user's uid, which WearTAK doesn't tell plugins and WearTakChat doesn't use. */
        private const val USER_UID = "WEARTAK-USER"
    }
}
