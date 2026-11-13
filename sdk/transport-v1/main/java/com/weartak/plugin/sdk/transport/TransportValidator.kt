package com.weartak.plugin.sdk.transport

import com.weartak.plugin.sdk.transport.model.TransportCapabilitiesParcel
import com.weartak.plugin.sdk.transport.model.TransportDeliveryParcel
import com.weartak.plugin.sdk.transport.model.TransportMessageParcel
import com.weartak.plugin.sdk.transport.model.TransportResultParcel
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Structural validation only; host must still parse CoT securely and authorize identities. */
object TransportValidator {
    fun id(value: String?): Boolean =
        value != null && value.isNotBlank() &&
            value.toByteArray(Charsets.UTF_8).size <= TransportContract.MAX_ID_BYTES &&
            value.none { it.isISOControl() }

    fun capabilities(value: TransportCapabilitiesParcel, host: Boolean = false): TransportResultParcel {
        fun kinds(values: List<Int>) = values.size <= 2 && values.distinct().size == values.size &&
            values.all { it == TransportPayloadKind.CHAT_UTF8 || it == TransportPayloadKind.COT_XML_UTF8 }
        return if (value.version != TransportContract.VERSION) {
            error(TransportResultCode.UNSUPPORTED, "Unsupported transport version")
        } else if (!kinds(value.outboundPayloadKinds) || !kinds(value.inboundPayloadKinds) ||
            (!host && value.channelIds.isEmpty()) || value.channelIds.size > TransportContract.MAX_CHANNELS ||
            value.channelIds.distinct().size != value.channelIds.size || !value.channelIds.all(::id) ||
            value.maxPayloadBytes !in 1..TransportContract.MAX_PAYLOAD_BYTES ||
            value.maxPendingMessages !in 1..TransportContract.MAX_PENDING_MESSAGES
        ) {
            error(TransportResultCode.INVALID_REQUEST, "Invalid transport capabilities")
        } else ok()
    }

    fun message(
        value: TransportMessageParcel,
        capabilities: TransportCapabilitiesParcel,
        outbound: Boolean,
    ): TransportResultParcel {
        val kinds = if (outbound) capabilities.outboundPayloadKinds else capabilities.inboundPayloadKinds
        if (value.payloadKind !in kinds) return error(TransportResultCode.UNSUPPORTED, "Payload kind not negotiated")
        if (!id(value.messageId) || !id(value.senderUid) ||
            listOf(value.recipientUid, value.senderCallsign, value.workspaceId, value.externalId)
                .any { it != null && !id(it) }
        ) return error(TransportResultCode.INVALID_REQUEST, "Invalid message identity")
        if (value.sentAtEpochMillis?.let { it !in 0..TransportContract.MAX_SENT_AT_EPOCH_MILLIS } == true) {
            return error(TransportResultCode.INVALID_REQUEST, "Invalid sent timestamp")
        }
        if (value.payload.isEmpty() || value.payload.size > capabilities.maxPayloadBytes ||
            value.payload.size > TransportContract.MAX_PAYLOAD_BYTES
        ) return error(TransportResultCode.LIMIT_EXCEEDED, "Payload exceeds negotiated bound")
        val decoded = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value.payload))
        } catch (_: CharacterCodingException) {
            return error(TransportResultCode.INVALID_REQUEST, "Malformed UTF-8")
        }
        if (decoded.isBlank() || decoded.contains('\u0000')) {
            return error(TransportResultCode.INVALID_REQUEST, "Empty or NUL-containing payload")
        }
        return ok()
    }

    fun delivery(value: TransportDeliveryParcel): TransportResultParcel {
        if (!id(value.messageId) || !id(value.channelId) || value.sequence <= 0 ||
            value.stage !in TransportDeliveryStage.QUEUED..TransportDeliveryStage.CANCELLED ||
            value.errorCode?.let { !id(it) } == true ||
            value.detail?.toByteArray(Charsets.UTF_8)?.size?.let { it > TransportContract.MAX_DETAIL_BYTES } == true ||
            (value.stage == TransportDeliveryStage.FAILED && value.errorCode == null) ||
            (value.stage != TransportDeliveryStage.FAILED && value.errorCode != null)
        ) return error(TransportResultCode.INVALID_REQUEST, "Invalid delivery observation")
        return ok()
    }

    internal fun ok(sessionId: String? = null) = TransportResultParcel(TransportResultCode.ACCEPTED, sessionId)
    internal fun error(code: Int, detail: String) = TransportResultParcel(code, detail = detail)
}
