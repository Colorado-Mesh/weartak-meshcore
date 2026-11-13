package org.coloradomesh.weartak

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.weartak.plugin.sdk.aidl.IWearTakHostCallback
import com.weartak.plugin.sdk.aidl.IWearTakPlugin
import com.weartak.plugin.sdk.aidl.IWearTakPluginControl
import com.weartak.plugin.sdk.model.HostSettingsParcel
import com.weartak.plugin.sdk.model.PluginCapabilitiesParcel
import com.weartak.plugin.sdk.model.PluginInfoParcel
import com.weartak.plugin.sdk.model.PluginInitParcel
import com.weartak.plugin.sdk.model.PluginLifecycleState
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import com.weartak.plugin.sdk.model.PluginStatusParcel
import com.weartak.plugin.sdk.model.SettingsApplyResultParcel
import com.weartak.plugin.sdk.model.SettingsSchemaParcel
import com.weartak.plugin.sdk.routestatus.PluginRouteStatusReporter
import com.weartak.plugin.sdk.routestatus.RouteConnectionState
import com.weartak.plugin.sdk.transport.TransportHostSupport
import com.weartak.plugin.sdk.v2.WearTakPluginV2Contract
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.v3.aidl.IWearTakPluginHostControlV3
import java.util.concurrent.Executors

/**
 * The service WearTAK binds to. M0: it does nothing useful yet. It lets WearTAK admit and
 * initialise the plugin, registers a transport-v1 route with no radio behind it, and reports
 * that route as disconnected, so the plugin shows up in WearTAK's Plugins list and connection
 * status. Everything it does is logged under the tag "MeshCorePlugin".
 *
 * WearTAK calls these methods on Binder threads and waits for each to return, so anything that
 * calls back into WearTAK (transport registration, route status) runs on [worker] instead.
 */
class MeshCorePluginService : Service() {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "MeshCorePlugin") }

    @Volatile private var state = PluginLifecycleState.BOUND
    @Volatile private var routes: PluginRouteStatusReporter? = null
    private lateinit var transport: MeshTransport

    override fun onCreate() {
        super.onCreate()
        transport = MeshTransport()
        Log.i(TAG, "created, version ${BuildConfig.VERSION_NAME}")
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "bound by WearTAK")
        return binder
    }

    override fun onDestroy() {
        stopEverything(PluginLifecycleState.SHUTDOWN)
        worker.shutdown()
        super.onDestroy()
    }

    /** Remembers what WearTAK offered at initialisation: the route status and transport binders. */
    private fun initialise(init: PluginInitParcel, apiCalled: String) {
        Log.i(
            TAG,
            "$apiCalled from ${init.hostPackageName} (caller uid ${Binder.getCallingUid()}, " +
                "host uid ${init.hostUid}), extras ${init.hostExtras.keySet().sorted()}",
        )
        routes = PluginRouteStatusReporter.fromInit(init)
        transport.attach(TransportHostSupport.fromInit(init))
        Log.i(TAG, "route status ${if (routes != null) "offered" else "not offered"}, transport-v1 ${if (transport.hasHost) "offered" else "not offered"}")
        state = PluginLifecycleState.READY
    }

    private fun startEverything() {
        state = PluginLifecycleState.RUNNING
        worker.execute {
            transport.register()
            val code = routes?.report(
                routeId = ROUTE_ID,
                label = "MeshCore radio",
                state = RouteConnectionState.DISCONNECTED,
                statusText = "No radio yet (test build)",
            )
            Log.i(TAG, "route status report: ${code ?: "no route status control"}")
        }
    }

    private fun stopEverything(newState: PluginLifecycleState) {
        state = newState
        worker.execute {
            transport.unregister()
            routes?.clear()
        }
    }

    private val binder = object : IWearTakPlugin.Stub() {
        override fun getPluginInfo() = PluginInfoParcel(
            pluginId = packageName,
            displayName = "MeshCore",
            versionName = BuildConfig.VERSION_NAME,
            packageName = packageName,
            description = "Chat and positions over a MeshCore LoRa radio",
        )

        // No hosted screens yet; M2 adds radio pairing.
        override fun getCapabilities() = PluginCapabilitiesParcel()

        override fun getApiVersion(): Int = WearTakPluginV2Contract.CONTRACT_API_VERSION

        override fun getStatus() = PluginStatusParcel(state = state)

        override fun initializePlugin(init: PluginInitParcel, control: IWearTakPluginControl?) =
            initialise(init, "initializePlugin")

        override fun initializePluginV2(
            init: PluginInitParcel,
            control: IWearTakPluginControl?,
            controlV2: IWearTakPluginHostControlV2?,
        ) = initialise(init, "initializePluginV2")

        override fun initializePluginV3(
            init: PluginInitParcel,
            control: IWearTakPluginControl?,
            controlV2: IWearTakPluginHostControlV2?,
            controlV3: IWearTakPluginHostControlV3?,
        ) = initialise(init, "initializePluginV3")

        override fun startPlugin() {
            Log.i(TAG, "startPlugin")
            startEverything()
        }

        override fun stopPlugin() {
            Log.i(TAG, "stopPlugin")
            stopEverything(PluginLifecycleState.STOPPED)
        }

        override fun shutdownPlugin() {
            Log.i(TAG, "shutdownPlugin")
            stopEverything(PluginLifecycleState.SHUTDOWN)
        }

        override fun registerHostCallback(callback: IWearTakHostCallback?) = Unit
        override fun unregisterHostCallback(callback: IWearTakHostCallback?) = Unit

        override fun getSettingsSchema() = SettingsSchemaParcel(title = "MeshCore")
        override fun getSettingsValues() = Bundle()
        override fun applySettings(values: Bundle?) = Unit
        override fun applySettingsWithResult(values: Bundle?, applyMode: Int) =
            SettingsApplyResultParcel(success = true, applyMode = applyMode)
        override fun onHostSettingsChanged(hostSettings: HostSettingsParcel?) = Unit

        override fun getMainUiSchema() = PluginMainUiSchemaParcel()
        override fun getMainUiState() = Bundle()
        override fun invokeMainUiAction(actionId: String?, args: Bundle?) = Unit
    }

    companion object {
        const val TAG = "MeshCorePlugin"
        const val ROUTE_ID = "meshcore-radio"
    }
}
