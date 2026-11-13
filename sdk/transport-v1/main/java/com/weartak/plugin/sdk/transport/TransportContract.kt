package com.weartak.plugin.sdk.transport

/** Optional extension; base v1/v2/v3 API versions and transaction ordering are unchanged. */
object TransportContract {
    const val VERSION = 1
    const val EXTRA_VERSION = "transportVersionV1"
    const val EXTRA_CONTROL = "transportControlV1"
    const val MAX_PAYLOAD_BYTES = 48 * 1024
    const val MAX_ID_BYTES = 128
    const val MAX_CHANNELS = 8
    const val MAX_PENDING_MESSAGES = 32
    const val MAX_SESSION_MESSAGES = 256
    const val MAX_DETAIL_BYTES = 256
    const val MAX_DEADLINE_MS = 24 * 60 * 60 * 1000L
    const val MAX_SENT_AT_EPOCH_MILLIS = 253402300799999L
}

object TransportPayloadKind {
    const val CHAT_UTF8 = 1
    const val COT_XML_UTF8 = 2
}

object TransportDeliveryStage {
    const val QUEUED = 1
    const val CHANNEL_SUBMITTED = 2
    const val CHANNEL_DELIVERED = 3
    const val RECIPIENT_DELIVERED = 4
    const val FAILED = 5
    const val CANCELLED = 6
}

object TransportResultCode {
    /** Request admitted only; never means channel or recipient delivery. */
    const val ACCEPTED = 0
    const val INVALID_REQUEST = 1
    const val UNAUTHORIZED = 2
    const val INACTIVE_SESSION = 3
    const val LIMIT_EXCEEDED = 4
    const val DUPLICATE = 5
    const val UNKNOWN_MESSAGE = 6
    const val STALE_UPDATE = 7
    const val UNSUPPORTED = 8
    const val UNAVAILABLE = 9
    const val EXPIRED = 10
}

object TransportCloseReason {
    const val STOPPED = 1
    const val DISCONNECTED = 2
    const val REPLACED = 3
    const val SHUTDOWN = 4
}
