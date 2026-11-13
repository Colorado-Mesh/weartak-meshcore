package com.weartak.plugin.sdk.transport.aidl;

import com.weartak.plugin.sdk.transport.model.TransportSendRequestParcel;
import com.weartak.plugin.sdk.transport.model.TransportResultParcel;

/** Plugin-owned. Authenticate host UID before dispatching every callback. */
interface IPluginTransportCallbackV1 {
    TransportResultParcel sendMessage(in TransportSendRequestParcel request);
    TransportResultParcel cancelMessage(String sessionId, String messageId);
    void onSessionClosed(String sessionId, int reason);
}
