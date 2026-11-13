# MeshCore for WearTAK

A [WearTAK](https://github.com/aegorsuch/weartak-wearos) plugin that connects WearTAK on a Wear OS watch to a [MeshCore](https://docs.meshcore.io/) companion radio over Bluetooth, so WearTAK can chat and see mesh nodes over LoRa with no phone, LTE or TAK server. A [Colorado Mesh](https://coloradomesh.org/) project.

## Status

**M0, in progress.** The plugin does nothing useful yet. It lets WearTAK find, admit and initialise it, registers a transport-v1 route with no radio behind it, and reports that route as disconnected. How to try it is in [docs/testing-m0.md](docs/testing-m0.md).

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

Needs JDK 17 and the Android SDK (compile SDK 35). The WearTAK plugin SDK is built from source as part of the build: see [sdk/README.md](sdk/README.md).

## Repository layout

| Path | What |
| --- | --- |
| `plugin/` | The plugin app, `org.coloradomesh.weartak` |
| `sdk/` | The WearTAK plugin SDK, compiled from the `weartak-plugin-sdk` submodule plus WearTAK's transport-v1 overlay |
| `weartak-plugin-sdk/` | Submodule: the SDK at the commit WearTAK pins |

## Licence

GPLv3, like WearTAK and its plugin SDK. See [LICENSE](LICENSE).
