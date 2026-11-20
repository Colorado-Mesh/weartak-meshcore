# MeshCore for WearTAK

A [WearTAK](https://github.com/aegorsuch/weartak-wearos) plugin that connects WearTAK on a Wear OS watch to a [MeshCore](https://docs.meshcore.io/) companion radio over Bluetooth, so WearTAK can chat and see mesh nodes over LoRa with no phone, LTE or TAK server. A [Colorado Mesh](https://coloradomesh.org/) project.

## Status

**M3, in testing.** While WearTAK runs the plugin, mesh traffic from the chosen MeshCore radio appears in WearTAK. Nodes with a position show on the map; companions also appear in WearTAK's contacts. Each mesh channel and each node you hear from in a DM is its own WearTAK conversation, named after the channel (`#Public`) or the node. Replying to the mesh is M4. How to try it is in [docs/testing-m3.md](docs/testing-m3.md).

M2 (the radio link, shown as WearTAK's "MeshCore radio" route) passed on 18 November 2026; see [docs/testing-m2.md](docs/testing-m2.md).

M0 (WearTAK admits and initialises the plugin) passed on 16 November 2026; see [docs/testing-m0.md](docs/testing-m0.md). M1 (the shared `tak` module) merged in meshcore-kotlin and the bridge on 17 November.

| Milestone | What |
| --- | --- |
| M0 | A do-nothing plugin that WearTAK admits and initialises |
| M1 | The bridge's mesh-to-CoT code moved into [meshcore-kotlin](https://github.com/Colorado-Mesh/meshcore-kotlin) |
| M2 | Bluetooth link to the radio, reusing [WearMesh](https://github.com/Colorado-Mesh/wearmesh); radio status in WearTAK |
| M3 | Mesh chat and node positions into WearTAK |
| M4 | WearTAK chat out to mesh channels and direct messages |
| M5 | Power and robustness |
| M6 | MECP emergencies, tracked positions |
| M7 | Testers, then publishing through tak.gov |

## Build

```bash
git clone --recurse-submodules https://github.com/Colorado-Mesh/weartak-meshcore.git
cd weartak-meshcore
./gradlew :plugin:assembleDebug
```

Needs JDK 17 and the Android SDK (compile SDK 35). The WearTAK plugin SDK is built from source as part of the build: see [sdk/README.md](sdk/README.md). [meshcore-kotlin](https://github.com/Colorado-Mesh/meshcore-kotlin) is included as a composite build; after pulling a change that moves it, run `git submodule update --init`.

One radio per app: the plugin holds its radio alone, so a radio used here can't be connected to WearMesh or the MeshCore app at the same time.

## Repository layout

| Path | What |
| --- | --- |
| `plugin/` | The plugin app, `org.coloradomesh.weartak` |
| `sdk/` | The WearTAK plugin SDK, compiled from the `weartak-plugin-sdk` submodule plus WearTAK's transport-v1 overlay |
| `weartak-plugin-sdk/` | Submodule: the SDK at the commit WearTAK pins |
| `meshcore-kotlin/` | Submodule: the shared MeshCore protocol and Bluetooth code, as used by WearMesh and the bridge |

## Licence

GPLv3, like WearTAK and its plugin SDK. See [LICENSE](LICENSE).
