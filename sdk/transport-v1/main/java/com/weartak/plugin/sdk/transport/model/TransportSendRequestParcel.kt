package com.weartak.plugin.sdk.transport.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Host selects exactly one channel. Plugin owns channel envelopes and fragmentation. */
@Parcelize
data class TransportSendRequestParcel(
    val sessionId: String,
    val channelId: String,
    val message: TransportMessageParcel,
    val deadlineEpochMillis: Long,
) : Parcelable
