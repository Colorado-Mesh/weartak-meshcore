package com.weartak.plugin.sdk.transport

import android.util.Log
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.transport.aidl.IPluginTransportHostV1

object TransportHostSupport {
    /** Only call after verifying the host package/signature/UID through normal plugin binding. */
    @Suppress("DEPRECATION")
    fun fromInit(init: PluginInitParcel?): IPluginTransportHostV1? {
        val extras = init?.hostExtras ?: return null
        if (extras.get(TransportContract.EXTRA_VERSION) != TransportContract.VERSION) return null
        val binder = extras.getBinder(TransportContract.EXTRA_CONTROL) ?: return null
        if (!binder.isBinderAlive) {
            Log.w("TransportHostSupport", "Transport Binder unavailable")
            return null
        }
        return IPluginTransportHostV1.Stub.asInterface(binder)
    }
}
