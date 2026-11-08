# Wireless CarPlay + Android Auto for HMG-API

A mod for [HMG-API](https://github.com/Open-HMG/HMG-API), the mod manager for Hyundai,
Kia and Genesis Gen5W head units. It turns on wireless CarPlay and wireless Android Auto
over the head unit's own hotspot, with turn-by-turn directions on the cluster and HUD.

It moved here from HMG-API's `mods/wireless-carplay-aa`, with its history. HMG-API's
releases up to 1.0.2 carry it as `wireless-carplay-aa-2.1.0.zip`; later versions are
released from this repository.

## What it does

Each part is a setting in the HMG-API app (pick the mod in its list for its settings), all
on by default:

| Setting | What |
|---|---|
| Turn-by-turn on the cluster and HUD | The phone's directions on the cluster and HUD, for CarPlay and Android Auto |
| Wireless CarPlay | The Bluetooth bridge and Wi-Fi sessions CarPlay needs |
| CarPlay music and Siri over Wi-Fi | AAC and Opus audio (`libleafiaaac.so`, using the head unit's own codecs) |
| Settings > Phone Projection opens Device Connections | A hook in the Settings app (US/Canada) |
| USB drives and hubs don't start wired Android Auto | A hook in the Android Auto app: a USB stick no longer blocks wireless Android Auto |

Like every HMG-API mod, it checks each file it changes against its stock checksum, backs
it up, and puts back the exact stock bytes when it's removed. Most of it is hooks and
in-memory patches (no files changed); the files it does change are listed in the app's plan.

## Supported cars

| Car | Region | Head unit software |
|---|---|---|
| Kia EV6 (`CVEV`) | USA | 260507 |
| Hyundai Ioniq 5 (`NEEV`, `NEEV23`) | USA | 260507 |
| Kia EV6 (`CVEV`) | Canada | 260206 |
| Hyundai Ioniq 5 (`NEEV`) | Canada | 260206 |

The list is `system` in [`wireless-carplay-aa/mod.json`](wireless-carplay-aa/mod.json).
It needs the head unit's Wi-Fi hardware and a hook map for the car in HMG-API
([Supported cars](https://github.com/Open-HMG/HMG-API/wiki/Supported-cars)). Only each
region's newest software is supported: on older software, update the head unit first.
Other regions wait for now. Your car isn't listed? Open an "Add my car" issue.

## Installing

Install HMG-API first ([Installing HMG-API](https://github.com/Open-HMG/HMG-API/wiki/Installing-HMG-API)).
Then, from this repository's [releases](https://github.com/Open-HMG/hmgapi-wireless-cp-aa/releases):

- **USB stick**: put `wireless-carplay-aa-<version>.zip` in `HMGAPI/updates/` on the
  stick, then HMG-API app → Check USB updates → the mod → Apply changes.
- **NEMESIS**: unzip `wireless-carplay-aa-<version>-nemesis.zip` onto the top level of a
  USB stick and confirm the install in NEMESIS Manager.
- **adb**: `adb push` the zip, then `hmgapid engine install <zip>`, `plan`, `apply`.

[Installing and removing mods](https://github.com/Open-HMG/HMG-API/wiki/Installing-and-removing-mods)
has the steps. Updates must be signed with the same author key as the installed version;
every release here is (author key `7251ce71-a146-8a1e-958b-dbffc6af7359`, VysseCodes).

## Hook points live in HMG-API

The mod hooks the car's apps only through HMG-API's hook points (`hooks.points` in
`mod.json`). Points, and their maps for every car, are in HMG-API, not here. A change
that needs a new or changed point:

1. A pull request to [HMG-API](https://github.com/Open-HMG/HMG-API): the point in
   `core/hookpoints/`, mapped on every car in `maps/` (`tools/map-car.py`,
   `tools/test-hooks.py`), and `docs/hook-points.md`.
2. A HMG-API release that carries it.
3. A pull request here using it, with `"hmgapi": ">=<that release>"` in `mod.json` and
   `hmgapi-sdk.json` (and the workflows' pin) moved to that release.

This repository's pull request can be open, as a draft, during 1 and 2; its CI fails
until the SDK has the point. See [HMG-API's docs/mod-repos.md](https://github.com/Open-HMG/HMG-API/blob/main/docs/mod-repos.md), section 4.

## Building and testing

CI runs HMG-API's mod workflow, with the HMG-API mod SDK pinned in `hmgapi-sdk.json`
(until HMG-API 1.0.3, a HMG-API commit the SDK is built from). On your computer, with the
SDK unpacked in `$HMGAPI_SDK` (`./build.sh sdk` in HMG-API at that commit) and the
Android SDK in `ANDROID_HOME`:

```
$HMGAPI_SDK/bin/build-mod.sh wireless-carplay-aa out/build/wireless-carplay-aa
```
```
HMGAPI_SDK=$HMGAPI_SDK sh wireless-carplay-aa/test.sh
```
```
HMGAPI_SDK=$HMGAPI_SDK sh wireless-carplay-aa/tools/aaturns/test.sh
```
```
HMGAPI_SDK=$HMGAPI_SDK sh wireless-carplay-aa/tools/cpturns/test.sh
```
```
HMGAPI_SDK=$HMGAPI_SDK sh wireless-carplay-aa/tools/recipes/test.sh
```
```
$HMGAPI_SDK/bin/check-scripts.sh wireless-carplay-aa
```

`test.sh` and `tools/recipes/test.sh` also check the mod on a car's real stock files,
which aren't in any repository (maintainers keep them privately, in `~/hmg-private`);
without them those parts say "skipped" and pass. `native/leafiaaac/build.sh` rebuilds
`files/libleafiaaac.so` (clang + lld).

Releases: a maintainer pushes a tag `v<mod.json version>`; the release workflow tests,
signs and makes a draft release (see [CONTRIBUTING.md](CONTRIBUTING.md)).

## License

AGPL-3.0, see [LICENSE](LICENSE). The repository holds no manufacturer apps or
libraries: only hooks, hash-only recipes, delta patch sets, checksums and our own code.
