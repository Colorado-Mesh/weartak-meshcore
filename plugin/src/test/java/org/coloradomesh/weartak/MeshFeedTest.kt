package org.coloradomesh.weartak

import org.coloradomesh.meshcore.protocol.Channel
import org.coloradomesh.meshcore.protocol.ChannelMessage
import org.coloradomesh.meshcore.protocol.Contact
import org.coloradomesh.meshcore.protocol.DirectMessage
import org.coloradomesh.meshcore.protocol.SelfInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MeshFeedTest {
    private val now = Instant.parse("2026-11-20T18:00:00Z")
    private val sent = ArrayList<MeshFeed.Item>()
    private val feed = MeshFeed(send = { sent.add(it) }, clock = { now })

    // Distinct in the first 16 hex digits, which MeshPicture uses for marker uids.
    private fun key(n: Int) = "%016x".format(n) + "a".repeat(48)

    private fun node(n: Int, name: String, lat: Double, lon: Double, heardSecondsAgo: Long = 60, type: Int = Contact.TYPE_REPEATER) =
        Contact(publicKeyHex = key(n), type = type, name = name, lastAdvert = now.epochSecond - heardSecondsAgo, latitude = lat, longitude = lon)

    private fun self() = SelfInfo(
        name = "W0RMT-T1n", publicKeyHex = key(999), latitude = 0.0, longitude = 0.0,
        frequencyMhz = 910.525, bandwidthKhz = 62.5, spreadingFactor = 7, codingRate = 5, txPowerDbm = 22,
    )

    private fun startUp(vararg contacts: Contact) {
        feed.onSelf(self())
        for (contact in contacts) feed.onContact(contact)
        feed.onChannels(listOf(Channel(0, "Public", Channel.Kind.PUBLIC), Channel(1, "#emergency", Channel.Kind.HASHTAG)))
    }

    @Test
    fun nothingGoesOutBeforeStartUpIsThrough() {
        startUp(node(1, "Ridge", 40.0, -105.2))
        assertTrue(sent.isEmpty())
        feed.onReady()
        assertEquals(listOf("MESHCORE-${key(1).take(16)}"), sent.map { it.senderUid })
        assertTrue(sent.single().xml.contains("callsign=\"Ridge\""))
    }

    @Test
    fun onlyRealPositionsHeardInTheLastDayGoOnTheMap() {
        startUp(
            node(1, "Ridge", 40.0, -105.2),
            node(2, "Nowhere", 0.0, 0.0),
            node(3, "Old", 40.1, -105.3, heardSecondsAgo = 2 * 86_400),
        )
        feed.onReady()
        val names = sent.map { it.senderCallsign }
        assertEquals(listOf("Ridge"), names)
        // No channel markers at 0,0 either.
        assertTrue(sent.none { it.senderUid.startsWith("MESHCORE-CH-") })
    }

    @Test
    fun atMostFiftyMarkersNewestFirst() {
        startUp(*(1..80).map { node(it, "N$it", 40.0 + it / 100.0, -105.0, heardSecondsAgo = it.toLong()) }.toTypedArray())
        feed.onReady()
        assertEquals(MeshFeed.MAX_MARKERS, sent.size)
        assertEquals("N1", sent.first().senderCallsign)
        assertEquals("N50", sent.last().senderCallsign)
    }

    @Test
    fun aChangedNodeIsSentAtOnceAndAllAreSentOnRefresh() {
        startUp(node(1, "Ridge", 40.0, -105.2), node(2, "Mesa", 39.9, -105.1))
        feed.onReady()
        sent.clear()
        feed.onContact(node(2, "Mesa", 39.95, -105.15))
        assertEquals(listOf("Mesa"), sent.map { it.senderCallsign })
        sent.clear()
        feed.refreshMarkers()
        assertEquals(setOf("Ridge", "Mesa"), sent.map { it.senderCallsign }.toSet())
    }

    @Test
    fun markersStayLongerThanTheRefresh() {
        startUp(node(1, "Ridge", 40.0, -105.2))
        feed.onReady()
        assertTrue(sent.single().xml.contains("stale=\"2026-11-20T18:30:00Z\""))
        assertTrue(MeshFeed.MARKER_STALE > MeshFeed.REFRESH)
    }

    @Test
    fun chatWaitsForTheCallsignThenComesAsAConversationWithTheChannel() {
        startUp()
        feed.onReady()
        sent.clear()
        feed.onChannelMessage(ChannelMessage(0, "Alice: on the trail", now.epochSecond - 5))
        assertTrue(sent.isEmpty())
        feed.setCallsign("BUD")
        val chat = sent.single()
        assertEquals("MESHCORE-CH-0", chat.senderUid)
        assertEquals("#Public", chat.senderCallsign)
        assertTrue(chat.xml.contains("id=\"MESHCORE-CH-0\""))
        assertTrue(chat.xml.contains("to=\"BUD\""))
        assertTrue(chat.xml.contains(">Alice: on the trail</remarks>"))
    }

    @Test
    fun aDirectMessageIsAConversationWithTheNode() {
        startUp(node(7, "Alice", 40.0, -105.0, type = Contact.TYPE_COMPANION))
        feed.setCallsign("BUD")
        feed.onReady()
        sent.clear()
        feed.onDirectMessage(DirectMessage(key(7).take(12), "meet at the car", now.epochSecond))
        val chat = sent.single()
        assertEquals("MESHCORE-${key(7).take(16)}", chat.senderUid)
        assertEquals("Alice", chat.senderCallsign)
        assertTrue(chat.xml.contains("chatroom=\"Alice\""))
    }

    @Test
    fun resetForgetsTheRadio() {
        startUp(node(1, "Ridge", 40.0, -105.2))
        feed.onReady()
        feed.reset()
        sent.clear()
        feed.refreshMarkers()
        feed.onContact(node(1, "Ridge", 40.0, -105.2))
        assertTrue(sent.isEmpty())
    }
}
