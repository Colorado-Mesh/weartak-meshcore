package org.coloradomesh.weartak

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import org.coloradomesh.meshcore.ble.FoundRadio
import org.coloradomesh.meshcore.ble.RadioScanner

/**
 * The plugin's one screen, opened by tapping MeshCore in WearTAK's Plugins menu (or from the
 * watch's app list). It asks for Bluetooth permission, finds and chooses the radio, and shows how
 * the link stands. The link itself is [RadioLink], kept by the plugin service while WearTAK runs
 * the plugin; this screen only changes the choice and asks the link to start again.
 */
class RadioActivity : ComponentActivity() {
    private lateinit var scanner: RadioScanner
    private val found = mutableStateListOf<FoundRadio>()
    private var finding by mutableStateOf(false)
    private var scanning by mutableStateOf(false)
    private var notice by mutableStateOf<String?>(null)
    private var link by mutableStateOf<LinkState>(LinkState.Off)
    private var chosen by mutableStateOf<ChosenRadio?>(null)
    private var permitted by mutableStateOf(false)

    private val onLink: (LinkState) -> Unit = { link = it }

    /** What to do once permission is granted. */
    private var afterPermissions: (() -> Unit)? = null

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = hasPermission()
        val next = afterPermissions
        afterPermissions = null
        if (permitted) {
            notice = null
            RadioLink.restart(this)
            next?.invoke()
        } else {
            notice = "Bluetooth permission is needed to reach the radio"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = RadioScanner(this)
        setContent { MaterialTheme { Screen() } }
    }

    override fun onStart() {
        super.onStart()
        chosen = RadioChoice.get(this)
        val wasPermitted = permitted
        permitted = hasPermission()
        RadioLink.addListener(onLink)
        // Granted in the watch's settings while this screen was away.
        if (permitted && !wasPermitted && link == LinkState.NoPermission) RadioLink.restart(this)
    }

    override fun onStop() {
        RadioLink.removeListener(onLink)
        scanner.stop()
        scanning = false
        super.onStop()
    }

    private fun hasPermission(): Boolean =
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    /** Runs [action] now if Bluetooth permission is granted, otherwise after asking for it. */
    private fun withPermissions(action: () -> Unit) {
        if (hasPermission()) {
            action()
            return
        }
        afterPermissions = action
        permissionRequest.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
    }

    private fun scan() = withPermissions {
        finding = true
        notice = scanner.blocker()
        if (notice != null) return@withPermissions
        found.clear()
        scanning = true
        scanner.start(
            onFound = { radio ->
                val at = found.indexOfFirst { it.address == radio.address }
                if (at >= 0) found[at] = radio else found.add(radio)
            },
            onDone = { problem ->
                scanning = false
                notice = when {
                    problem != null -> problem
                    found.isEmpty() -> "No radio found. Is it on, nearby and not connected to another app?"
                    else -> null
                }
            },
        )
    }

    private fun pick(radio: FoundRadio) {
        scanner.stop()
        scanning = false
        finding = false
        notice = null
        val choice = ChosenRadio(radio.address, radio.name.removeSuffix(PAIRED_SUFFIX))
        RadioChoice.set(this, choice)
        chosen = choice
        RadioLink.restart(this)
    }

    private fun forget() {
        RadioChoice.forget(this)
        chosen = null
        RadioLink.restart(this)
    }

    @Composable
    private fun Screen() {
        ScreenList {
            item { ListHeader { Text("MeshCore radio") } }
            item { Note(link.summary()) }
            if (finding) {
                item { WideChip(if (scanning) "Scanning…" else "Scan again", secondary = true, enabled = !scanning) { scan() } }
                items(found, key = { it.address }) { radio ->
                    WideChip(radio.name, detail = if (radio.rssi != 0) "${radio.rssi} dBm" else radio.address) { pick(radio) }
                }
                item {
                    WideChip("Cancel", secondary = true) {
                        scanner.stop()
                        scanning = false
                        finding = false
                        notice = null
                    }
                }
            } else {
                val current = chosen
                if (current != null) item { Note("Radio: ${current.name}") }
                if (!permitted) item { WideChip("Allow Bluetooth") { withPermissions {} } }
                item { WideChip(if (current == null) "Find a radio" else "Choose another radio", secondary = current != null) { scan() } }
                if (current != null) {
                    item { WideChip("Try again now", secondary = true, enabled = link is LinkState.Waiting || link is LinkState.Connecting) { RadioLink.restart(this@RadioActivity) } }
                    item { WideChip("Forget radio", secondary = true) { forget() } }
                }
                item { Note("One app per radio: disconnect it in WearMesh or the MeshCore app before using it here.") }
            }
            notice?.let { text -> item { Note(text) } }
        }
    }

    private companion object {
        /** RadioScanner marks radios the watch has paired with; not part of the name. */
        const val PAIRED_SUFFIX = " (paired)"
    }
}

// The list, chip and note below are WearMesh's (ui/Common.kt), so the two apps look alike.

/** A screen that is one scrolling list, with the time at the top and a scroll indicator. */
@Composable
private fun ScreenList(content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = state) },
    ) {
        ScalingLazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, top = 32.dp, end = 8.dp, bottom = 48.dp),
            content = content,
        )
    }
}

/** A full-width chip: primary for the main action, secondary otherwise. */
@Composable
private fun WideChip(
    label: String,
    detail: String? = null,
    secondary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Chip(
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = if (detail != null) {
            { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        colors = if (secondary) ChipDefaults.secondaryChipColors() else ChipDefaults.primaryChipColors(),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A short centred line of explanation. */
@Composable
private fun Note(text: String) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.caption2,
        modifier = Modifier.fillMaxWidth(),
    )
}
