# Testing M3

M3 asks: does mesh traffic from the radio show up in WearTAK? That covers nodes on the map, companions in the contacts, and channel and direct messages as WearTAK conversations.

Replies to the mesh are M4. A message you write in one of these conversations isn't sent yet.

## Before you start

- **WearTAK on its own.** WearTAK drops CoT from every source, this plugin included, while its paired phone companion app is connected ("Suppressing direct CoT while bonded companion is connected" in WearTAK's log). Test with WearTAK standalone on the watch.
- **A callsign set in WearTAK.** Mesh chat is addressed to your WearTAK callsign. Until WearTAK has told the plugin what it is, messages wait (up to 200).
- **One app per radio**, as for M2. Quit WearMesh, or let it say "In use by another app on this watch".
- **Messages now go to WearTAK.** The plugin collects the radio's waiting messages again. The radio deletes a message once it's collected, so messages that arrive while the plugin holds the radio show in WearTAK, not later in WearMesh or the MeshCore app.

## Install

Download `weartak-meshcore-debug-apk` from the PR's CI run and install it over the M2 build:

```bash
adb -s <watch> install -r plugin-debug.apk
adb -s <watch> logcat -s MeshCorePlugin CEP-PluginManager CEP-PluginConnection CEP-PluginTransport WearTAK-TAKConnectService
```

## Steps and what success looks like

1. **Connect.** Open WearTAK and let the plugin connect, as in M2. The log shows `host settings: callsign "<yours>"`, then `radio link up`.
2. **Nodes on the map.** Within a few seconds of Connected, repeaters and room servers with a position appear as markers, and companions with a position appear as users (also in WearTAK's contacts). You get the 50 most recently heard nodes, from the last 24 hours. Each marker's details say "MeshCore repeater, last advert …".
3. **A channel message.** Have someone send on Public (or send one from another radio). A conversation called **#Public** appears in WearTAK's chat, with the line as the mesh shows it (`Name: text`). Messages on other channels get their own conversations (`#emergency`, and so on).
4. **A direct message.** Send the radio a DM from another companion. A conversation named after that node appears. If the node has a position, it's the same contact as its marker.
5. **Messages waiting on the radio.** Send a DM while the plugin is off, then turn the plugin on. The DM arrives in WearTAK after start-up.
6. **Markers stay.** Leave it for more than 30 minutes. The markers stay on the map: the plugin sends them again every 15 minutes, and they'd go stale after 30.

Send the log either way. Lines like `WearTAK refused … code N` or `new transport session` are the interesting ones.

## Things to know

- **Why every channel is a "direct" conversation.** WearTAK only accepts a group chat if the user's own uid is listed in it, and it doesn't tell plugins that uid. A chat from a channel's own uid to your callsign is accepted, so each channel is a conversation with a contact named after it. The bridge shows channels to ATAK the same way.
- **No message is thrown away quietly.** The radio no longer has a message once the plugin collects it, so the plugin queues each chat line until WearTAK accepts it. That covers WearTAK refusing CoT while its phone companion is connected, or the transport being replaced. The queue is retried at every delivery and every 15 minutes. A line is dropped only if WearTAK says it never will take it, or past 200 waiting, and either is logged. The queue lives as long as the plugin's process does.
- **Session renewals.** WearTAK admits 256 messages per transport session and never frees them. The plugin registers a new session after 224, so the log shows `new transport session` every so often. That's expected.
- **Not yet:** replies (M4), and MECP emergency markers and alerts.
