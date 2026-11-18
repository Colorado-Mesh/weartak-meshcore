package org.coloradomesh.weartak

import com.weartak.plugin.sdk.routestatus.RouteConnectionState
import com.weartak.plugin.sdk.routestatus.RouteStatusContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkStateTest {
    @Test
    fun eachStateMapsToTheRouteWearTakShows() {
        assertNull(LinkState.Off.route())
        assertEquals(RouteConnectionState.DISCONNECTED, LinkState.NoRadio.route()?.state)
        assertEquals(RouteConnectionState.ERROR, LinkState.NoPermission.route()?.state)
        assertEquals(RouteReport(RouteConnectionState.CONNECTING, "Connecting to Base"), LinkState.Connecting("Base").route())
        assertEquals(RouteReport(RouteConnectionState.CONNECTED, "Base"), LinkState.Connected("Base").route())
        assertEquals(
            RouteReport(RouteConnectionState.DISCONNECTED, "Lost the radio. Retry in 30 s"),
            LinkState.Waiting("Base", "Lost the radio", 30).route(),
        )
        assertEquals("Lost the radio. Retry in 5 min", LinkState.Waiting("Base", "Lost the radio", 300).route()?.text)
    }

    @Test
    fun routeTextAlwaysFitsWearTaksLimit() {
        val long = LinkState.Waiting("Base", "x".repeat(500), 30).route()!!
        assertTrue(long.text.toByteArray(Charsets.UTF_8).size <= RouteStatusContract.MAX_STATUS_TEXT_BYTES)
        assertTrue(long.text.endsWith("…"))
        val emoji = LinkState.Connected("📡".repeat(60)).route()!!
        assertTrue(emoji.text.toByteArray(Charsets.UTF_8).size <= RouteStatusContract.MAX_STATUS_TEXT_BYTES)
        assertTrue(emoji.text.removeSuffix("…").chunked(2).all { it == "📡" })
    }

    @Test
    fun shortTextIsLeftAlone() {
        assertEquals("Base camp", fitBytes("Base camp", 128))
        assertEquals("abc…", fitBytes("abcdefgh", 6))
    }

    @Test
    fun linkErrorsAreSaidPlainly() {
        assertEquals(
            "Can't reach the radio: off, out of range, or in use by another app",
            friendlyReason("Could not connect after 3 tries (status 133). Is it on and nearby?"),
        )
        assertEquals("Lost the radio", friendlyReason("Connection lost (status 8, timeout)"))
        assertEquals("Bluetooth is off", friendlyReason("Bluetooth is off"))
        assertEquals("The radio didn't finish start-up", friendlyReason("The radio didn't finish start-up"))
    }

    @Test
    fun retriesBackOffToFiveMinutes() {
        assertEquals(listOf(15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L), (1..7).map { Backoff.pauseMs(it) })
        assertEquals(300_000L, Backoff.pauseMs(100))
    }

    @Test
    fun aRadioThatAnswersNoChannelReadIsNotConnected() {
        assertTrue(startUpHeard(slots = 8, unanswered = 0))
        assertTrue(startUpHeard(slots = 8, unanswered = 7))
        assertFalse(startUpHeard(slots = 8, unanswered = 8))
        assertTrue(startUpHeard(slots = 0, unanswered = 0))
        assertEquals(40, channelSlots(40))
        assertEquals(8, channelSlots(0))
    }
}
