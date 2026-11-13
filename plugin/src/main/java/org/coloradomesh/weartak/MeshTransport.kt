package org.coloradomesh.weartak

import android.util.Log
import com.weartak.plugin.sdk.transport.TransportContract
import com.weartak.plugin.sdk.transport.TransportPayloadKind
import com.weartak.plugin.sdk.transport.TransportResultCode
import com.weartak.plugin.sdk.transport.aidl.IPluginTransportCallbackV1
import com.weartak.plugin.sdk.transport.aidl.IPluginTransportHostV1
import com.weartak.plugin.sdk.transport.model.TransportCapabilitiesParcel
import com.weartak.plugin.sdk.transport.model.TransportResultParcel
import com.weartak.plugin.sdk.transport.model.TransportSendRequestParcel

/**
 * The plugin's transport-v1 registration with WearTAK. M0: registers one channel with no radio
 * behind it, and refuses anything WearTAK asks it to send. M3 and M4 put the radio behind it.
 *
 * Call [register] and [unregister] off WearTAK's Binder threads: they call back into WearTAK.
 */
class MeshTransport {

    @Volatile private var host: IPluginTransportHostV1? = null
    @Volatile private var sessionId: String? = null

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
            // M3 brings mesh chat in, and node positions as CoT.
            inboundPayloadKinds = listOf(TransportPayloadKind.CHAT_UTF8, TransportPayloadKind.COT_XML_UTF8),
            // One channel for now. How to address many mesh channels and DM recipients is an open
            // question for the WearTAK dev (the host uses the first channel a plugin advertises).
            channelIds = listOf(CHANNEL_ID),
            // A direct MeshCore message holds 160 bytes; a channel message less.
            maxPayloadBytes = MAX_MESH_MESSAGE_BYTES,
            maxPendingMessages = 8,
            runtimeAvailable = false,
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
        const val MAX_MESH_MESSAGE_BYTES = 160
    }
}
