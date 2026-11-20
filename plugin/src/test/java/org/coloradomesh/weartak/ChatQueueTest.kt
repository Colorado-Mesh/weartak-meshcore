package org.coloradomesh.weartak

import com.weartak.plugin.sdk.transport.TransportResultCode
import org.coloradomesh.weartak.ChatQueue.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatQueueTest {
    private val logs = ArrayList<String>()
    private fun item(n: Int) = MeshFeed.Item("m$n", "MESHCORE-CH-0", "#Public", "<event/>", chat = true)

    @Test
    fun linesStayUntilWearTakTakesThem() {
        val queue = ChatQueue(log = { logs.add(it) })
        queue.add(item(1))
        queue.add(item(2))
        val offered = ArrayList<String>()
        queue.drain { offered.add(it.messageId); Outcome.WAIT }
        assertEquals(listOf("m1"), offered)
        assertEquals(2, queue.size)
        offered.clear()
        queue.drain { offered.add(it.messageId); Outcome.DONE }
        assertEquals(listOf("m1", "m2"), offered)
        assertEquals(0, queue.size)
    }

    @Test
    fun aLineWearTakWillNeverTakeIsDroppedAndTheRestGoOn() {
        val queue = ChatQueue(log = { logs.add(it) })
        queue.add(item(1))
        queue.add(item(2))
        queue.drain { if (it.messageId == "m1") Outcome.DROP else Outcome.DONE }
        assertEquals(0, queue.size)
        assertEquals(1, logs.size)
    }

    @Test
    fun theOldestLineGoesWhenTheQueueIsFull() {
        val queue = ChatQueue(max = 3, log = { logs.add(it) })
        (1..5).forEach { queue.add(item(it)) }
        val offered = ArrayList<String>()
        queue.drain { offered.add(it.messageId); Outcome.DONE }
        assertEquals(listOf("m3", "m4", "m5"), offered)
        assertEquals(2, logs.size)
    }

    @Test
    fun resultCodesMapToWhatHappensToALine() {
        assertEquals(Outcome.DONE, ChatQueue.outcomeOf(TransportResultCode.ACCEPTED))
        assertEquals(Outcome.DONE, ChatQueue.outcomeOf(TransportResultCode.DUPLICATE))
        assertEquals(Outcome.DROP, ChatQueue.outcomeOf(TransportResultCode.INVALID_REQUEST))
        assertEquals(Outcome.DROP, ChatQueue.outcomeOf(TransportResultCode.UNSUPPORTED))
        assertEquals(Outcome.DROP, ChatQueue.outcomeOf(TransportResultCode.LIMIT_EXCEEDED))
        // WearTAK not taking CoT now (its phone companion connected, say), no session, no transport.
        assertEquals(Outcome.WAIT, ChatQueue.outcomeOf(TransportResultCode.UNAVAILABLE))
        assertEquals(Outcome.WAIT, ChatQueue.outcomeOf(TransportResultCode.INACTIVE_SESSION))
        assertEquals(Outcome.WAIT, ChatQueue.outcomeOf(null))
    }
}
