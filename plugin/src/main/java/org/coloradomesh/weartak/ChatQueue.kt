package org.coloradomesh.weartak

import com.weartak.plugin.sdk.transport.TransportResultCode

/**
 * Mesh chat on its way to WearTAK, kept until WearTAK takes it.
 *
 * The radio deletes a message once the plugin collects it, so the plugin holds the only copy.
 * A line stays queued until WearTAK accepts it (or already has it), or refuses it in a way that
 * won't change on a retry. Anything else (no transport, WearTAK not taking CoT while its phone
 * companion is connected, a session being replaced) leaves it queued for the next try: the next
 * delivery, or the plugin's 15-minute refresh.
 *
 * Bounded by [max]: past it the oldest line is dropped, through [log]. Not thread-safe: the
 * plugin service uses it on its worker only. Plain Kotlin, so it's tested without a device.
 */
class ChatQueue(private val max: Int = MAX_QUEUED, private val log: (String) -> Unit = {}) {
    enum class Outcome {
        /** WearTAK has it. */
        DONE,

        /** WearTAK will never take it; dropped. */
        DROP,

        /** Not now; try again later, and don't try the lines behind it yet. */
        WAIT,
    }

    private val items = ArrayDeque<MeshFeed.Item>()

    val size: Int get() = items.size

    fun add(item: MeshFeed.Item) {
        items.addLast(item)
        while (items.size > max) {
            val dropped = items.removeFirst()
            log("chat queue full; dropped ${dropped.messageId} from ${dropped.senderCallsign}")
        }
    }

    /**
     * Offers the queued lines to [deliver], oldest first, until one has to wait. Lines that are
     * done or dropped leave the queue; the order of the rest is kept.
     */
    fun drain(deliver: (MeshFeed.Item) -> Outcome) {
        while (items.isNotEmpty()) {
            val item = items.first()
            when (deliver(item)) {
                Outcome.DONE -> items.removeFirst()
                Outcome.DROP -> {
                    items.removeFirst()
                    log("WearTAK won't take ${item.messageId} from ${item.senderCallsign}; dropped")
                }
                Outcome.WAIT -> return
            }
        }
    }

    companion object {
        const val MAX_QUEUED = 200

        /**
         * What a delivery's result means for a queued line. [code] is WearTAK's result code, or
         * null when there was no transport to hand it to.
         *
         * A full session is renewed and retried inside the transport, so LIMIT_EXCEEDED here
         * means the event itself is too big, which a retry won't change.
         */
        fun outcomeOf(code: Int?): Outcome = when (code) {
            TransportResultCode.ACCEPTED, TransportResultCode.DUPLICATE -> Outcome.DONE
            TransportResultCode.INVALID_REQUEST, TransportResultCode.UNSUPPORTED,
            TransportResultCode.LIMIT_EXCEEDED -> Outcome.DROP
            else -> Outcome.WAIT
        }
    }
}
