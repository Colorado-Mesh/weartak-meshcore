package com.weartak.plugin.sdk.transport.model

import android.os.Parcelable
import com.weartak.plugin.sdk.transport.TransportContract
import kotlinx.parcelize.Parcelize

@Parcelize
data class TransportCapabilitiesParcel(
    val version: Int = TransportContract.VERSION,
    /** Directions are relative to host, not the endpoint advertising these capabilities. */
    val outboundPayloadKinds: List<Int> = emptyList(),
    val inboundPayloadKinds: List<Int> = emptyList(),
    /** Ordered plugin preference; empty only on host means no channel-name restriction. */
    val channelIds: List<String> = emptyList(),
    val maxPayloadBytes: Int = TransportContract.MAX_PAYLOAD_BYTES,
    val maxPendingMessages: Int = TransportContract.MAX_PENDING_MESSAGES,
    val runtimeAvailable: Boolean = false,
    val deviceReceipts: Boolean = false,
    val recipientReceipts: Boolean = false,
) : Parcelable
