package org.coloradomesh.weartak

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import com.weartak.plugin.sdk.transport.TransportHostSupport
import com.weartak.plugin.sdk.v2.WearTakPluginV2Contract
import com.weartak.plugin.sdk.v2.aidl.IWearTakPluginHostControlV2
import com.weartak.plugin.sdk.v3.aidl.IWearTakPluginHostControlV3
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The service WearTAK binds to. It registers a transport-v1 route (with nothing behind it until
 * M3) and, while WearTAK runs the plugin, keeps the link to the chosen radio ([RadioLink]) and
 * reports how it stands as the "MeshCore radio" route: Connecting, Connected, or Disconnected
 * with the reason. WearTAK's home connection icon shows the route once it is Connecting or
 * Connected. Everything is logged under the tag "MeshCorePlugin".
 *
 * WearTAK calls these methods on Binder threads and waits for each to return, so anything that
 * calls back into WearTAK (transport registration, route status) runs on [worker] instead, and
 * the radio link is driven from the main thread.
 */
class MeshCorePluginService : Service() {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "MeshCorePlugin") }
    private val main = Handler(Looper.getMainLooper())

    /** Reports each change of the radio link to WearTAK while the plugin runs. Main thread. */
    private val onLink: (LinkState) -> Unit = { link ->
        if (state == PluginLifecycleState.RUNNING) submit { reportRoute(link) }
    }

    @Volatile private var state = PluginLifecycleState.BOUND

    /**
     * Counts start and stop transitions. Work queued for a start checks it, so a start that a stop
     * (or the service's end) has overtaken never takes the radio.
     */
    @Volatile private var lifecycle = 0
    @Volatile private var routes: PluginRouteStatusReporter? = null
    private lateinit var transport: MeshTransport

    override fun onCreate() {
        super.onCreate()
        transport = MeshTransport()
        RadioLink.addListener(onLink)
        Log.i(TAG, "created, version ${BuildConfig.VERSION_NAME}")
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "bound by WearTAK")
        return binder
    }

    override fun onDestroy() {
        RadioLink.removeListener(onLink)
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

    // Synchronized so starts and stops, which arrive on Binder threads, queue their main-thread
    // work in the order they happened.
    @Synchronized
    private fun startEverything() {
        state = PluginLifecycleState.RUNNING
        val mine = ++lifecycle
        submit { transport.register() }
        // RadioLink reports its state at once and on every change; see onLink.
        main.post {
            if (lifecycle != mine) return@post
            RadioLink.start(this)
            val now = RadioLink.state
            submit { reportRoute(now) }
        }
    }

    @Synchronized
    private fun stopEverything(newState: PluginLifecycleState) {
        state = newState
        lifecycle++
        main.post { RadioLink.stop() }
        submit {
            transport.unregister()
            routes?.clear()
        }
    }

    /** Runs [task] on [worker], unless the service has ended and shut it down. */
    private fun submit(task: () -> Unit) {
        try {
            worker.execute(task)
        } catch (e: RejectedExecutionException) {
            Log.d(TAG, "service ended; dropped a queued WearTAK call ($e)")
        }
    }

    /** Tells WearTAK how the radio link stands. On [worker]. */
    private fun reportRoute(link: LinkState) {
        if (state != PluginLifecycleState.RUNNING) return
        val report = link.route() ?: return
        val code = routes?.report(
            routeId = ROUTE_ID,
            label = ROUTE_LABEL,
            state = report.state,
            statusText = report.text,
        )
        Log.i(TAG, "route ${report.state} \"${report.text}\": ${code ?: "no route status control"}")
    }

    private val binder = object : IWearTakPlugin.Stub() {
        override fun getPluginInfo() = PluginInfoParcel(
            pluginId = packageName,
            displayName = "MeshCore",
            versionName = BuildConfig.VERSION_NAME,
            packageName = packageName,
            description = "Chat and positions over a MeshCore LoRa radio",
        )

        // The radio screen is the plugin's own launcher activity, which WearTAK opens when its
        // Plugins row is tapped.
        override fun getCapabilities() = PluginCapabilitiesParcel(externalMainActivity = true)

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
        const val ROUTE_LABEL = "MeshCore radio"
    }
}
