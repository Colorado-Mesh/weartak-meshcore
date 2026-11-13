package com.weartak.plugin.sdk.transport.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class TransportResultParcel(
    val code: Int,
    val sessionId: String? = null,
    val detail: String? = null,
) : Parcelable
