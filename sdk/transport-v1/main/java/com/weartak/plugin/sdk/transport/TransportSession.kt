package com.weartak.plugin.sdk.transport

import android.os.Binder
import com.weartak.plugin.sdk.transport.model.TransportCapabilitiesParcel
import com.weartak.plugin.sdk.transport.model.TransportDeliveryParcel
import com.weartak.plugin.sdk.transport.model.TransportMessageParcel
import com.weartak.plugin.sdk.transport.model.TransportResultParcel
import com.weartak.plugin.sdk.transport.model.TransportSendRequestParcel

/**
 * Bounded state for one authenticated registration. No I/O or routing occurs here.
 * Host uses plugin UID; plugin uses verified host UID. Never derive peerUid from a parcel.
 * Caller must serialize admission with dispatch and close; admission does not send anything.
 */
class TransportSession(
    val sessionId: String,
    private val peerUid: Int,
    capabilities: TransportCapabilitiesParcel,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val capabilities = capabilities.copy(
        outboundPayloadKinds = capabilities.outboundPayloadKinds.toList(),
        inboundPayloadKinds = capabilities.inboundPayloadKinds.toList(),
        channelIds = capabilities.channelIds.toList(),
    )
    private data class Outbound(
        val channelId: String,
        val deadline: Long,
        var sequence: Long = 0,
        var stage: Int = 0,
        var terminal: Boolean = false,
    )
    private val outbound = mutableMapOf<String, Outbound>()
    private val inbound = mutableSetOf<String>()
    private var active = true

    init {
        require(TransportValidator.id(sessionId)) { "Invalid session ID" }
        require(peerUid >= 0) { "Invalid peer UID" }
        require(TransportValidator.capabilities(capabilities).code == TransportResultCode.ACCEPTED) {
            "Invalid capabilities"
        }
    }

    @Synchronized
    fun admitOutbound(request: TransportSendRequestParcel, callingUid: Int = Binder.getCallingUid()): TransportResultParcel {
        fence(request.sessionId, callingUid)?.let { return it }
        if (!capabilities.runtimeAvailable) return error(TransportResultCode.UNAVAILABLE, "Transport unavailable")
        if (request.channelId !in capabilities.channelIds) return error(TransportResultCode.UNSUPPORTED, "Unknown channel")
        val valid = TransportValidator.message(request.message, capabilities, outbound = true)
        if (valid.code != TransportResultCode.ACCEPTED) return valid
        val now = clock()
        if (request.deadlineEpochMillis <= now) return error(TransportResultCode.EXPIRED, "Deadline expired")
        if (request.deadlineEpochMillis - now > TransportContract.MAX_DEADLINE_MS) {
            return error(TransportResultCode.INVALID_REQUEST, "Deadline too distant")
        }
        if (request.message.messageId in outbound || request.message.messageId in inbound) {
            return error(TransportResultCode.DUPLICATE, "Message already observed")
        }
        if (outbound.values.count { !it.terminal } >= capabilities.maxPendingMessages || atCapacity()) {
            return error(TransportResultCode.LIMIT_EXCEEDED, "Session capacity reached")
        }
        outbound[request.message.messageId] = Outbound(request.channelId, request.deadlineEpochMillis)
        return ok()
    }

    @Synchronized
    fun admitInbound(
        sessionId: String,
        channelId: String,
        message: TransportMessageParcel,
        callingUid: Int = Binder.getCallingUid(),
    ): TransportResultParcel {
        fence(sessionId, callingUid)?.let { return it }
        if (!capabilities.runtimeAvailable) return error(TransportResultCode.UNAVAILABLE, "Transport unavailable")
        if (channelId !in capabilities.channelIds) return error(TransportResultCode.UNSUPPORTED, "Unknown channel")
        val valid = TransportValidator.message(message, capabilities, outbound = false)
        if (valid.code != TransportResultCode.ACCEPTED) return valid
        if (message.messageId in inbound || message.messageId in outbound) return error(TransportResultCode.DUPLICATE, "Message already observed")
        if (atCapacity()) return error(TransportResultCode.LIMIT_EXCEEDED, "Session capacity reached")
        inbound.add(message.messageId)
        return ok()
    }

    @Synchronized
    fun delivery(
        sessionId: String,
        update: TransportDeliveryParcel,
        callingUid: Int = Binder.getCallingUid(),
    ): TransportResultParcel {
        fence(sessionId, callingUid)?.let { return it }
        val valid = TransportValidator.delivery(update)
        if (valid.code != TransportResultCode.ACCEPTED) return valid
        val record = outbound[update.messageId] ?: return error(TransportResultCode.UNKNOWN_MESSAGE, "Unknown outbound message")
        if (record.channelId != update.channelId) return error(TransportResultCode.INVALID_REQUEST, "Channel mismatch")
        if (record.terminal || update.sequence <= record.sequence || update.stage < record.stage) {
            return error(TransportResultCode.STALE_UPDATE, "Terminal or stale delivery update")
        }
        if (clock() >= record.deadline) return error(TransportResultCode.EXPIRED, "Deadline expired")
        if ((update.stage == TransportDeliveryStage.CHANNEL_DELIVERED && !capabilities.deviceReceipts) ||
            (update.stage == TransportDeliveryStage.RECIPIENT_DELIVERED && !capabilities.recipientReceipts)
        ) return error(TransportResultCode.UNSUPPORTED, "Receipt scope not negotiated")
        record.sequence = update.sequence
        record.stage = update.stage
        record.terminal = update.stage == TransportDeliveryStage.FAILED ||
            update.stage == TransportDeliveryStage.CANCELLED ||
            update.stage == TransportDeliveryStage.RECIPIENT_DELIVERED ||
            (update.stage == TransportDeliveryStage.CHANNEL_DELIVERED && !capabilities.recipientReceipts)
        return ok()
    }

    /** ACCEPTED cancellation fences later updates; adapter must separately stop queued work. */
    @Synchronized
    fun cancel(sessionId: String, messageId: String, callingUid: Int = Binder.getCallingUid()): TransportResultParcel {
        fence(sessionId, callingUid)?.let { return it }
        val record = outbound[messageId] ?: return error(TransportResultCode.UNKNOWN_MESSAGE, "Unknown outbound message")
        if (record.terminal) return error(TransportResultCode.STALE_UPDATE, "Message already terminal")
        record.stage = TransportDeliveryStage.CANCELLED
        record.terminal = true
        return ok()
    }

    /** Return timed-out IDs so owner emits failures and cancels adapter work. */
    @Synchronized
    fun expire(): List<String> = outbound.filterValues { !it.terminal && clock() >= it.deadline }
        .map { (id, record) ->
            record.terminal = true
            record.stage = TransportDeliveryStage.FAILED
            id
        }

    /** Local lifecycle call. Invalidates session before owner drops Binder/dispatch resources. */
    @Synchronized
    fun close(): List<String> {
        active = false
        val pending = outbound.filterValues { !it.terminal }.keys.toList()
        outbound.clear()
        inbound.clear()
        return pending
    }

    private fun fence(id: String, uid: Int): TransportResultParcel? = when {
        uid != peerUid -> error(TransportResultCode.UNAUTHORIZED, "Caller UID mismatch")
        !active || id != sessionId -> error(TransportResultCode.INACTIVE_SESSION, "Inactive transport session")
        else -> null
    }

    private fun atCapacity() = outbound.size + inbound.size >= TransportContract.MAX_SESSION_MESSAGES
    private fun ok() = TransportValidator.ok(sessionId)
    private fun error(code: Int, detail: String) = TransportValidator.error(code, detail)
}
