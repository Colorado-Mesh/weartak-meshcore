package com.weartak.plugin.sdk.transport.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Sequence strictly increases per outbound message, including same-stage observations. */
@Parcelize
data class TransportDeliveryParcel(
    val messageId: String,
    val channelId: String,
    val sequence: Long,
    val stage: Int,
    val errorCode: String? = null,
    val detail: String? = null,
) : Parcelable
