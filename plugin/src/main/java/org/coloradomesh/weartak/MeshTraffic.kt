package org.coloradomesh.weartak

import android.util.Log

/**
 * Mesh traffic on its way to WearTAK, kept for the life of the plugin's process.
 *
 * WearTAK binds the plugin service and Android destroys it once WearTAK unbinds, while the
 * process may carry on. The radio no longer has a message once it's collected, so the messages
 * the plugin holds (waiting for the user's callsign in [feed], or for WearTAK to take them in
 * the chat queue) live here, not in the service, and a new service picks them up.
 *
 * Chat goes into the queue as soon as [feed] produces it, on the caller's thread, so a service
 * shutting down can't drop it in between. While a service is running it sets [sink], which
 * sends markers on and drains the queue into WearTAK.
 */
object MeshTraffic {
    private const val TAG = MeshCorePluginService.TAG

    private val chats = ChatQueue(log = { Log.w(TAG, it) })

    /** The running service's way into WearTAK, or null when there's none. */
    @Volatile var sink: ((MeshFeed.Item) -> Unit)? = null

    val feed = MeshFeed(
        send = { item ->
            if (item.chat) synchronized(chats) { chats.add(item) }
            sink?.invoke(item)
        },
        log = { Log.w(TAG, it) },
    )

    /** Offers WearTAK the queued chat, oldest first, until a line has to wait. */
    fun drainChats(deliver: (MeshFeed.Item) -> ChatQueue.Outcome) {
        synchronized(chats) {
            if (chats.size > 0) chats.drain(deliver)
        }
    }
}
