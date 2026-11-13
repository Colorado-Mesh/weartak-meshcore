package com.weartak.plugin.sdk.transport.aidl;

import com.weartak.plugin.sdk.transport.model.TransportCapabilitiesParcel;
import com.weartak.plugin.sdk.transport.model.TransportMessageParcel;
import com.weartak.plugin.sdk.transport.model.TransportDeliveryParcel;
import com.weartak.plugin.sdk.transport.model.TransportResultParcel;
import com.weartak.plugin.sdk.transport.aidl.IPluginTransportCallbackV1;

/** Host-owned, scoped to an authenticated active plugin binding. Calls are off main thread. */
interface IPluginTransportHostV1 {
    TransportCapabilitiesParcel getHostCapabilities();
    TransportResultParcel registerTransport(in TransportCapabilitiesParcel capabilities, in IPluginTransportCallbackV1 callback);
    TransportResultParcel unregisterTransport(String sessionId);
    TransportResultParcel receiveMessage(String sessionId, String channelId, in TransportMessageParcel message);
    TransportResultParcel reportDelivery(String sessionId, in TransportDeliveryParcel delivery);
}
