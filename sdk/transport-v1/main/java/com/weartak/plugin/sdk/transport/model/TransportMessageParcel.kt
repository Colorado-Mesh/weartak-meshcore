package com.weartak.plugin.sdk.transport.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Identity mapping is adapter-owned; TAK UIDs are not vendor account identifiers. */
@Parcelize
data class TransportMessageParcel(
    val messageId: String,
    val payloadKind: Int,
    val payload: ByteArray,
    val senderUid: String,
    val recipientUid: String? = null,
    val senderCallsign: String? = null,
    val workspaceId: String? = null,
    val externalId: String? = null,
    val sentAtEpochMillis: Long? = null,
) : Parcelable
