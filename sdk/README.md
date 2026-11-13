# WearTAK plugin SDK (built from source)

WearTAK has no published, signed SDK AAR, so this build compiles the SDK the way WearTAK's own
build does: a pinned SDK commit plus WearTAK's additive transport-v1 overlay.

| Part | Where | Pin |
| --- | --- | --- |
| SDK | `weartak-plugin-sdk/` submodule, from the public mirror `https://github.com/aegorsuch/weartak-plugin-sdk` of `git.tak.gov/weartak-plugins/weartak-plugin-sdk` | commit `86bdc8b04797c2cf6caa30e78e796abac1c2a330` |
| transport-v1 overlay | `sdk/transport-v1/main/`, extracted from `ci/plugin-sdk-transport/transport-v1-overlay.zip` in `aegorsuch/weartak-wearos` | archive SHA-256 `a51f54fbfc1c95a9583ac97daeec2f957fb42de87d27a9bbd823597184033ffd`; per-file hashes in `transport-v1/overlay-source.sha256` |

These are the pins in WearTAK's `ci/plugin-sdk.json` as of 13 November 2026. When WearTAK moves to
newer ones, move both together: check out the new commit in the submodule, replace
`transport-v1/main/` with the new overlay's `src/main/`, and update this table.

The SDK and overlay are GPLv3, like this repository.
