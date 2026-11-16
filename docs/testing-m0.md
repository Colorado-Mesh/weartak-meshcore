# Testing M0

M0 asks one question: does WearTAK, as installed on the watch, find this plugin, admit it and initialise it?

## Install

Build it, or download `weartak-meshcore-debug-apk` from the latest CI run, then install it next to WearTAK:

```bash
./gradlew :plugin:assembleDebug
adb -s <watch> install -r plugin/build/outputs/apk/debug/plugin-debug.apk
```

The app has no icon or screen of its own. It only runs inside WearTAK.

## Watch the logs

In one terminal, before opening WearTAK (or after force-stopping it):

```bash
adb -s <watch> logcat -s MeshCorePlugin CEP-PluginManager CEP-PluginConnection CEP-PluginTransport
```

Then open WearTAK and go to its Plugins list.

## What success looks like

1. **Found:** "MeshCore" appears in WearTAK's Plugins list, with "MeshCore radio link" under it.
2. **Admitted and initialised:** the log shows `bound by WearTAK`, then `initializePluginV2 from com.aitech.weartak_civ…` and `startPlugin`. WearTAK logs `initialized plugin pkg=org.coloradomesh.weartak api=2`.
3. **Transport registered:** `registerTransport: code 0, session …`. Code 0 is ACCEPTED.
4. **Route reported:** `route status report: 0`. A "MeshCore radio" route, disconnected, may show in WearTAK's Network Preferences or connection icon settings.

Send the full log either way. The interesting failures:

| Log line | Meaning |
| --- | --- |
| Plugin not in the list at all (`CEP-PluginDiag: discovered plugins:` is empty) | WearTAK didn't find it: the bind permission or intent filter didn't match. The filter needs the `DEFAULT` category, because WearTAK queries with `MATCH_DEFAULT_ONLY` |
| `trust_failure:…` | WearTAK's trust check refused it |
| `api_version_mismatch` | WearTAK doesn't accept API version 2 |
| `registerTransport: code 8` | UNSUPPORTED: WearTAK found nothing in common with what the plugin offered |

## Things to know

- **Signing certificate pinning.** WearTAK trusts a plugin's signing certificate the first time it sees the plugin, then refuses that package if the certificate ever changes. All debug builds, local and CI, are signed with the shared key in `plugin/debug.keystore` (SHA-256 `3A:82:BE:D5:97:FA:BB:FC:35:F7:E0:8D:0D:5D:AD:B9:BC:0E:21:77:FC:95:21:AA:1B:3F:85:94:9C:7C:14:56`), so they can replace one another. A build signed any other way, including the TPC-signed release later, means uninstalling the plugin and clearing WearTAK's data first.
- **The bind permission.** The plugin defines `com.weartak.plugin.permission.BIND_PLUGIN` itself, because no WearTAK build defines it. If WearTAK was installed before the plugin and still can't bind, try reinstalling WearTAK after the plugin, and tell the WearTAK dev.
