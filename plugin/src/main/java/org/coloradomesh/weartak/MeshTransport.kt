package org.coloradomesh.weartak

import android.util.Log
import com.weartak.plugin.sdk.transport.TransportContract
import com.weartak.plugin.sdk.transport.TransportPayloadKind
import com.weartak.plugin.sdk.transport.TransportResultCode
import com.weartak.plugin.sdk.transport.aidl.IPluginTransportCallbackV1
import com.weartak.plugin.sdk.transport.aidl.IPluginTransportHostV1
import com.weartak.plugin.sdk.transport.model.TransportCapabilitiesParcel
import com.weartak.plugin.sdk.transport.model.TransportMessageParcel
import com.weartak.plugin.sdk.transport.model.TransportResultParcel
import com.weartak.plugin.sdk.transport.model.TransportSendRequestParcel

/**
 * The plugin's transport-v1 registration with WearTAK, and the way mesh traffic reaches it.
 *
 * M3: [deliver] hands WearTAK one CoT event (a marker or a GeoChat) from the mesh. WearTAK takes it
 * in as it does CoT from a TAK server. Sending to the mesh is M4: for now anything WearTAK asks to
 * send is refused.
 *
 * WearTAK admits at most [TransportContract.MAX_SESSION_MESSAGES] messages per session and never
 * frees them, so before a session fills up ([SESSION_BUDGET]) the plugin closes it and registers a
 * new one. A delivery refused for a full session is retried once on a new session.
 *
 * Call [register], [unregister] and [deliver] off WearTAK's Binder threads (the plugin service's
 * worker): they call into WearTAK and wait.
 */
class MeshTransport {

    @Volatile private var host: IPluginTransportHostV1? = null
    @Volatile private var sessionId: String? = null

    /** Messages WearTAK has admitted on this session. Only touched on the worker. */
    private var used = 0

    val hasHost: Boolean get() = host != null

    fun attach(host: IPluginTransportHostV1?) {
        this.host = host
    }

    fun register() {
        val host = host ?: run {
            Log.i(TAG, "transport-v1 not offered by this WearTAK")
            return
        }
        val capabilities = TransportCapabilitiesParcel(
            version = TransportContract.VERSION,
            // M4 sends chat out; nothing else goes over LoRa.
            outboundPayloadKinds = listOf(TransportPayloadKind.CHAT_UTF8),
            // Mesh traffic comes in as CoT: node markers, and chat as GeoChat built by WearTakChat.
            inboundPayloadKinds = listOf(TransportPayloadKind.COT_XML_UTF8),
            // One channel carries every mesh channel and node: each is its own conversation in
            // WearTAK, keyed by the sender's uid (see WearTakChat). WearTAK uses the first channel.
            channelIds = listOf(CHANNEL_ID),
            // One limit covers both directions, and a CoT event is a few hundred bytes to a couple
            // of kilobytes. Outgoing chat is held to what a MeshCore message fits in M4.
            maxPayloadBytes = MAX_PAYLOAD_BYTES,
            maxPendingMessages = 8,
            // WearTAK refuses incoming messages from a transport that isn't available. The plugin
            // only has traffic while its radio link is up.
            runtimeAvailable = true,
            deviceReceipts = true,
            recipientReceipts = true,
        )
        val result = try {
            host.registerTransport(capabilities, callback)
        } catch (e: Exception) {
            Log.w(TAG, "registerTransport failed", e)
            return
        }
        sessionId = result.sessionId.takeIf { result.code == TransportResultCode.ACCEPTED }
        used = 0
        Log.i(TAG, "registerTransport: code ${result.code}, session ${result.sessionId}, ${result.detail ?: "no detail"}")
    }

    fun unregister() {
        val session = sessionId ?: return
        sessionId = null
        try {
            val result = host?.unregisterTransport(session)
            Log.i(TAG, "unregisterTransport: code ${result?.code}")
        } catch (e: Exception) {
            Log.w(TAG, "unregisterTransport failed", e)
        }
    }

    /**
     * Hands WearTAK one CoT event from the mesh. Returns WearTAK's result code, or null when there
     * is no transport to hand it to.
     */
    fun deliver(item: MeshFeed.Item): Int? {
        val host = host ?: return null
        if (sessionId == null || used >= SESSION_BUDGET) renew("session ${if (sessionId == null) "missing" else "nearly full"}")
        var code = receive(host, item) ?: return null
        if (code == TransportResultCode.LIMIT_EXCEEDED) {
            // Full after all (WearTAK counts what it admitted before a restart of ours, say).
            renew("session full")
            code = receive(host, item) ?: return null
        }
        when (code) {
            TransportResultCode.ACCEPTED -> used++
            // A chat line already given to WearTAK, sent again: fine.
            TransportResultCode.DUPLICATE -> Unit
            else -> Log.w(TAG, "WearTAK refused ${item.messageId}: code $code")
        }
        return code
    }

    private fun receive(host: IPluginTransportHostV1, item: MeshFeed.Item): Int? {
        val session = sessionId ?: return null
        val message = TransportMessageParcel(
            messageId = item.messageId,
            payloadKind = TransportPayloadKind.COT_XML_UTF8,
            payload = item.xml.toByteArray(Charsets.UTF_8),
            senderUid = item.senderUid,
            senderCallsign = item.senderCallsign?.takeIf { it.toByteArray(Charsets.UTF_8).size <= TransportContract.MAX_ID_BYTES },
            sentAtEpochMillis = System.currentTimeMillis(),
        )
        return try {
            val result = host.receiveMessage(session, CHANNEL_ID, message)
            if (result.code != TransportResultCode.ACCEPTED && result.code != TransportResultCode.DUPLICATE) {
                Log.d(TAG, "receiveMessage ${item.messageId}: code ${result.code}, ${result.detail ?: "no detail"}")
            }
            result.code
        } catch (e: Exception) {
            Log.w(TAG, "receiveMessage failed", e)
            null
        }
    }

    /** Closes the current session, if any, and registers a new one with WearTAK. */
    private fun renew(why: String) {
        Log.i(TAG, "new transport session ($why, $used used)")
        unregister()
        register()
    }

    private val callback = object : IPluginTransportCallbackV1.Stub() {
        override fun sendMessage(request: TransportSendRequestParcel): TransportResultParcel {
            Log.i(
                TAG,
                "WearTAK asked to send message ${request.message.messageId} on ${request.channelId} " +
                    "(kind ${request.message.payloadKind}, ${request.message.payload.size} bytes); no radio yet",
            )
            return TransportResultParcel(TransportResultCode.UNAVAILABLE, request.sessionId, "No radio connected")
        }

        override fun cancelMessage(sessionId: String, messageId: String) =
            TransportResultParcel(TransportResultCode.UNKNOWN_MESSAGE, sessionId)

        override fun onSessionClosed(sessionId: String, reason: Int) {
            Log.i(TAG, "transport session $sessionId closed by WearTAK, reason $reason")
            if (this@MeshTransport.sessionId == sessionId) this@MeshTransport.sessionId = null
        }
    }

    companion object {
        private const val TAG = MeshCorePluginService.TAG
        const val CHANNEL_ID = "meshcore"

        /** Room for a marker or a GeoChat event, well within WearTAK's 48 KB. */
        const val MAX_PAYLOAD_BYTES = 8 * 1024

        /** A new session before WearTAK's per-session limit, leaving room for M4's outgoing chat. */
        const val SESSION_BUDGET = TransportContract.MAX_SESSION_MESSAGES - 32
    }
}
