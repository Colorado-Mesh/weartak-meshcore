# Testing M2

M2 asks: can the plugin hold a Bluetooth link to a MeshCore companion radio while WearTAK runs it, and does WearTAK show that link's state?

The plugin doesn't carry any mesh traffic yet; that's M3 and M4.

## Before you start

- **One app per radio.** A radio connected to the MeshCore app or the TAK bridge on a phone doesn't advertise and can't be reached here. A radio connected to WearMesh on this watch can be reached, because Android shares one connection between the apps on a device, but the two apps' traffic would mix, so the plugin refuses to join it. Disconnect it there first: quit WearMesh, or choose a different radio for it.
- Use a current WearTAK build (pipeline build 703 or later), with Network Preferences Checklist on (tap the version 8 times) to see the plugin's row.

## Install

Download `weartak-meshcore-debug-apk` from the latest CI run, or build it, then install it over the M0 build:

```bash
git submodule update --init
./gradlew :plugin:assembleDebug
adb -s <watch> install -r plugin/build/outputs/apk/debug/plugin-debug.apk
```

The app now has a screen: **MeshCore for WearTAK** in the watch's app list, and the same screen opens when you tap **MeshCore** in WearTAK's Plugins menu.

## Watch the logs

```bash
adb -s <watch> logcat -s MeshCorePlugin CEP-PluginManager CEP-PluginConnection
```

## Steps and what success looks like

1. **Open WearTAK with no radio chosen.** The log shows `radio link wanted`, then `route 1 "No radio chosen. Tap MeshCore in Plugins": 0`. Network Preferences shows MeshCore radio as disconnected with that text.
2. **Choose the radio.** In WearTAK, open Plugins and tap MeshCore. Tap **Find a radio**, allow Bluetooth when asked, and tap your radio in the list.
   - If the radio asks for pairing, enter its PIN when the watch asks. The default for most companion radios is `123456`.
3. **Connecting.** The screen says "Connecting to …". The log shows `Link: Connecting to …`, then `route 2 "Connecting to …"`. WearTAK's home connection icon should show the MeshCore route now.
4. **Connected.** After start-up (a few seconds, longer with many contacts), the screen says "Connected to <the radio's node name>". The log shows `radio link up: …` and `route 3 "<name>": 0`. The home connection icon and Network Preferences show MeshCore radio as connected.
5. **Drop and recover.** Turn the radio off. Within about 30 s the screen and route say "Lost the radio. Retry in 15 s", and the pauses then grow (30 s, 1 min, 2 min, 4 min, then 5 min). Turn the radio back on. It reconnects at the next try, or at once with **Try again now**.
6. **In use elsewhere.** Connect the radio to WearMesh in Live mode, then tap **Try again now** in the plugin. The plugin should not connect: the reason reads "In use by another app on this watch", and it checks again at each retry. A radio held by an app on another device (a phone) reads "Can't reach the radio: off, out of range, or in use by another app".
7. **Plugin off and on.** In WearTAK's Plugins menu, turn MeshCore off: the log shows `radio link no longer wanted` and `Radio link closed: Disconnected`, and the radio is free for other apps. Turn it on again: it reconnects.
8. **Forget radio.** On the plugin screen, tap **Forget radio**. The link closes and the route goes back to "No radio chosen".

Send the log either way, especially for steps 3 to 5.

## Things to know

- **No foreground service.** The link is kept only while WearTAK, which is bound to the plugin, runs it. Whether that's enough with the screen off for a long time is an M5 question. Retries are timed by an alarm that also fires in Doze, at most about every 9 minutes.
- **The on/off switch on the Network Preferences row isn't offered yet.** WearTAK ties that switch to the plugin setting `forwardOutboundCot`, which also turns on its older outbound CoT forwarding. That's a question for the WearTAK dev before M4.
- **The radio sees the plugin as "WearTAK"**, the name it gives when it says hello.
- **Messages stay on the radio.** The plugin reads the radio's contacts and channels but doesn't collect its waiting messages (it has nowhere to show them until M3), so WearMesh or the MeshCore app still gets them afterwards.
- **A link that hasn't finished start-up after 2 minutes is closed and tried again.** That covers a radio that connects but stops answering during start-up. **Try again now** works while Connecting too.
