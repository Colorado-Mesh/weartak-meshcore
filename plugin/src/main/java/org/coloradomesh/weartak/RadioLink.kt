package org.coloradomesh.weartak

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import org.coloradomesh.meshcore.ble.RadioSession
import org.coloradomesh.meshcore.protocol.Channel
import org.coloradomesh.meshcore.protocol.ChannelMessage
import org.coloradomesh.meshcore.protocol.CompanionClient
import org.coloradomesh.meshcore.protocol.Contact
import org.coloradomesh.meshcore.protocol.DeviceInfo
import org.coloradomesh.meshcore.protocol.DirectMessage
import org.coloradomesh.meshcore.protocol.SelfInfo

/**
 * The one link from this app to its radio, shared by the plugin service and the radio screen,
 * which run in the same process.
 *
 * The plugin service calls [start] when WearTAK starts the plugin and [stop] when it stops it, so
 * the radio is held only while WearTAK uses the plugin. The radio screen calls [restart] when the
 * chosen radio or the Bluetooth permission changes, or to try again at once. Both watch [state]
 * through [addListener].
 *
 * A dropped or failed link is tried again after a growing pause ([Backoff]), timed both by a
 * Handler and by an alarm that may fire in Doze, whichever comes first: WearMesh found that a
 * Handler alone stops while the watch sleeps. The pause starts short again once a link gets
 * through start-up.
 *
 * One radio per app. A radio held by an app on another device doesn't advertise, so it can't be
 * reached here. A radio held by another app on this watch, such as WearMesh in Live mode, is
 * different: Android shares one Bluetooth connection between the apps on a device, so connecting
 * would join that app's link and mix the two apps' traffic. So the plugin doesn't connect to a
 * radio the watch is already connected to, and says it's in use.
 *
 * Main thread only. Callbacks from the link and the protocol client are posted to it, and each
 * carries the generation of the link it came from, so one from an old link is ignored.
 */
object RadioLink {
    private const val TAG = MeshCorePluginService.TAG

    /** The name the plugin gives the radio when it says hello; the radio keeps up to 12 bytes. */
    private const val APP_NAME = "WearTAK"

    /**
     * How long a link may take, from opening to the end of start-up, before it is closed and
     * tried again. The link gives up on its own if it cannot connect, but a radio that connects
     * and then stops answering during start-up would otherwise leave it Connecting for good.
     */
    private const val START_UP_TIMEOUT_MS = 120_000L

    /**
     * After the plugin closes its own link, Android can keep the connection a moment longer. A
     * radio still connected this soon after is checked again shortly, rather than taken to be in
     * use by another app.
     */
    private const val OWN_LINK_GRACE_MS = 10_000L
    private const val OWN_LINK_RECHECK_MS = 2_000L

    /** When this app last closed a link of its own, by SystemClock.elapsedRealtime. */
    private var lastClosedAt = 0L

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(LinkState) -> Unit>()

    private var app: Context? = null
    private var wanted = false
    private var session: RadioSession? = null

    /** Read on the link's thread too, to drop events from a link already replaced. */
    @Volatile private var generation = 0

    /**
     * Where the radio's contacts, channels and messages go (M3): the plugin service sets it while
     * WearTAK runs the plugin. Called on the link's thread, except onReady, on the main thread.
     */
    @Volatile var feed: MeshFeed? = null
    private var failures = 0
    private var radio: ChosenRadio? = null

    /** The radio's own node name, once it has said it; shown in place of its Bluetooth name. */
    private var nodeName: String? = null

    var state: LinkState = LinkState.Off
        private set

    /** Calls [listener] now with the current state, then on every change. */
    fun addListener(listener: (LinkState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (LinkState) -> Unit) {
        listeners -= listener
    }

    /** WearTAK started the plugin: keep a link to the chosen radio until [stop]. */
    fun start(context: Context) {
        app = context.applicationContext
        if (wanted) return
        Log.i(TAG, "radio link wanted")
        wanted = true
        failures = 0
        connect()
    }

    /** WearTAK stopped the plugin: let the radio go. */
    fun stop() {
        if (!wanted) return
        Log.i(TAG, "radio link no longer wanted")
        wanted = false
        cancelRetry()
        closeSession()
        set(LinkState.Off)
    }

    /**
     * The chosen radio or the permission changed, or the user asked to try now: drop any link and
     * start again without a pause. Does nothing while WearTAK has the plugin stopped.
     */
    fun restart(context: Context) {
        app = context.applicationContext
        if (!wanted) return
        Log.i(TAG, "radio link restarting")
        failures = 0
        cancelRetry()
        closeSession()
        connect()
    }

    private fun connect() {
        val context = app ?: return
        cancelRetry()
        val chosen = RadioChoice.get(context)
        if (chosen == null) return set(LinkState.NoRadio)
        if (!hasPermission(context)) return set(LinkState.NoPermission)
        if (chosen.address != radio?.address) nodeName = null
        radio = chosen
        if (connectedToWatch(context, chosen.address)) {
            if (SystemClock.elapsedRealtime() - lastClosedAt < OWN_LINK_GRACE_MS) {
                // Probably our own link, just closed and not yet gone.
                set(LinkState.Connecting(label()))
                main.postDelayed(retry, OWN_LINK_RECHECK_MS)
                return
            }
            Log.i(TAG, "the watch is already connected to ${chosen.address}; another app holds it")
            return waitAndRetry(IN_USE_HERE)
        }
        val mine = ++generation
        set(LinkState.Connecting(label()))
        val listener = object : CompanionClient.Listener {
            // Start-up's channel reads, counted on the link's thread: a read the radio never
            // answers still counts towards the end of start-up.
            private var slots = 0
            private var unansweredSlots = 0

            override fun onLog(message: String) {
                Log.d(TAG, "Radio: $message")
            }

            override fun onDevice(info: DeviceInfo) {
                slots = channelSlots(info.maxChannels)
            }

            override fun onChannelSlotsUnanswered(slots: Set<Int>) {
                unansweredSlots = slots.size
            }

            override fun onContact(contact: Contact) {
                if (mine == generation) feed?.onContact(contact)
            }

            override fun onChannels(channels: List<Channel>) {
                if (mine == generation) feed?.onChannels(channels)
            }

            override fun onChannelMessage(message: ChannelMessage) {
                if (mine == generation) feed?.onChannelMessage(message)
            }

            override fun onDirectMessage(message: DirectMessage) {
                if (mine == generation) feed?.onDirectMessage(message)
            }

            override fun onSelf(info: SelfInfo) {
                if (mine == generation) feed?.onSelf(info)
                main.post {
                    if (mine != generation) return@post
                    nodeName = info.name.takeIf { it.isNotBlank() }
                    if (state is LinkState.Connecting) set(LinkState.Connecting(label()))
                }
            }

            // Start-up is through: the link works, unless the radio went quiet after the device
            // query and every channel read timed out. Messages are left on the radio (see below).
            override fun onReady() {
                val heard = startUpHeard(slots, unansweredSlots)
                if (unansweredSlots > 0) Log.w(TAG, "the radio didn't answer $unansweredSlots of $slots channel slots")
                main.post {
                    if (mine != generation || state is LinkState.Connected) return@post
                    if (!heard) {
                        closed("The radio stopped answering during start-up")
                        return@post
                    }
                    failures = 0
                    main.removeCallbacks(startUpTimeout)
                    Log.i(TAG, "radio link up: ${label()}")
                    set(LinkState.Connected(label()))
                    feed?.onReady()
                }
            }

            // A link that is up but never got through start-up would sit idle: close it and try again.
            override fun onStartUpFailed(what: String) {
                Log.w(TAG, "the radio did not answer $what; closing the link to try again")
                main.post { if (mine == generation) session?.close() }
            }
        }
        val opened = RadioSession(
            context = context,
            address = chosen.address,
            listener = listener,
            appName = APP_NAME,
            onLog = { Log.i(TAG, "Link: $it") },
            onClosed = { reason -> main.post { if (mine == generation) closed(reason) } },
            // Messages are collected and shown in WearTAK (M3). The radio deletes a message once
            // it's collected, so while the plugin holds the radio, messages are WearTAK's.
            startUp = CompanionClient.StartUp(collectMessages = true),
        )
        session = opened
        main.postDelayed(startUpTimeout, START_UP_TIMEOUT_MS)
        opened.open()
    }

    private val startUpTimeout = Runnable {
        if (state is LinkState.Connected || session == null) return@Runnable
        Log.w(TAG, "the radio did not finish start-up in ${START_UP_TIMEOUT_MS / 1000} s; closing the link to try again")
        // closed() closes the session and moves on, without waiting for the link to confirm.
        closed("The radio didn't finish start-up")
    }

    private fun closed(reason: String) {
        main.removeCallbacks(startUpTimeout)
        // Close the old session even though it reported itself closed: with Bluetooth off it gives
        // up before connecting and leaves its link's thread running. Closing it again is harmless,
        // and the new generation makes its second close callback be ignored.
        generation++
        session?.close()
        session = null
        feed?.reset()
        lastClosedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "radio link closed: $reason")
        if (!wanted) return
        waitAndRetry(friendlyReason(reason))
    }

    private fun waitAndRetry(reason: String) {
        failures++
        val pause = Backoff.pauseMs(failures)
        set(LinkState.Waiting(label(), reason, pause / 1000))
        scheduleRetry(pause)
    }

    /**
     * Whether the watch already has a Bluetooth connection to [address]: Android lists a device
     * connected by any app on the watch, not only this one.
     */
    @SuppressLint("MissingPermission") // connect() checks BLUETOOTH_CONNECT first
    private fun connectedToWatch(context: Context, address: String): Boolean = try {
        context.getSystemService(BluetoothManager::class.java)
            ?.getConnectedDevices(BluetoothProfile.GATT)
            ?.any { it.address.equals(address, ignoreCase = true) } == true
    } catch (e: SecurityException) {
        Log.w(TAG, "couldn't ask Android which devices are connected: $e")
        false
    }

    private fun closeSession() {
        main.removeCallbacks(startUpTimeout)
        // Callbacks from the old link carry an old generation and are ignored.
        generation++
        if (session != null) lastClosedAt = SystemClock.elapsedRealtime()
        session?.close()
        feed?.reset()
        session = null
    }

    private fun label(): String = nodeName ?: radio?.name ?: "the radio"

    private fun set(newState: LinkState) {
        if (newState == state) return
        state = newState
        for (listener in listeners.toList()) listener(newState)
    }

    private fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    // --- Retrying -------------------------------------------------------------------------

    private val retry = Runnable { retryNow("timer") }

    private fun scheduleRetry(pauseMs: Long) {
        main.postDelayed(retry, pauseMs)
        val context = app ?: return
        context.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + pauseMs,
            retryIntent(context),
        )
    }

    private fun cancelRetry() {
        main.removeCallbacks(retry)
        val context = app ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(retryIntent(context))
    }

    private fun retryNow(by: String) {
        if (!wanted || session != null) return
        Log.i(TAG, "trying the radio again ($by)")
        connect()
    }

    private fun retryIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, RetryReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The retry alarm; it only matters when the Handler's timer has stopped in Doze. */
    class RetryReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            main.post {
                if (app == null) app = context.applicationContext
                retryNow("alarm")
            }
        }
    }
}
