# Contributing

Thanks for helping. Bugs in this mod and requests to add a car go in
[issues](https://github.com/Open-HMG/hmgapi-wireless-cp-aa/issues/new/choose); code and
docs in pull requests. Problems with HMG-API itself (installing, the app, other mods, Car
Reports, hook maps) go to [HMG-API](https://github.com/Open-HMG/HMG-API/issues/new/choose).

## Before a pull request

- Build and test off the car with the HMG-API mod SDK (README, "Building and testing"):
  the build, `wireless-carplay-aa/test.sh`, `tools/*/test.sh` and `check-scripts`, the
  same steps CI runs. Needs a JDK, the Android SDK and the SDK's `requirements.txt`; no
  car files or keys. Maintainers with the private stock trees also get the real-file
  checks in `test.sh` and `tools/recipes/test.sh`.
- Say which cars (model, region, head unit software) a change was tested on, or "off-car only".
- New or changed hook points: a HMG-API pull request first, mapped for every car
  (README, "Hook points live in HMG-API"). Here, `hooks.points` only names points HMG-API
  has; CI checks them against the `"hmgapi"` floor.
- Mod scripts: only commands the head unit has (`$HMGAPI_SDK/bin/check-scripts.sh wireless-carplay-aa`).
- Leave `version` in `mod.json` as it is: it's set at release.
- Who reviews what (`.github/CODEOWNERS`): VysseCodes reviews CI, releases and signing
  (`.github/`) and native code (`wireless-carplay-aa/native/`); everything else needs one
  of the Open-HMG maintainers.

## Releases (maintainers)

Versions follow HMG-API's [docs/releasing.md](https://github.com/Open-HMG/HMG-API/blob/main/docs/releasing.md)
(finals, release candidates, betas, hotfixes), with this mod's own numbers. A
pre-release (`v2.2.0-rc.1`) is a GitHub pre-release, never "Latest", and isn't added to
the catalogue; its `"hmgapi"` may name a HMG-API rc (`">=1.1.0-rc.1"`), a final's can't.

1. Set `version` in `wireless-carplay-aa/mod.json` by pull request (and `"hmgapi"` if
   the mod now needs a newer HMG-API; say so in the release notes,
   `.github/release-notes/v<version>.md`).
2. Push the tag `v<version>` (only admins can). The release workflow refuses a tag that
   isn't `mod.json`'s version, runs CI, signs the package with the author key (`MOD_KEY`,
   in the `release` environment), and makes a **draft** release with the package, its
   NEMESIS packages and `SHA256SUMS`.
3. Publish the draft (none before HMG-API 1.0.3), then open the catalogue pull request in
   HMG-API with the `tools/catalog.py add` command from the run's summary.

## Rules

- Anything that writes to `/system` keeps a stock checksum check, a backup and an
  uninstall that restores the exact stock bytes.
- The repo holds no manufacturer apps or libraries: only hooks, hash-only recipes,
  delta patch sets, checksums and our own code.
- Never collect or publish per-car secrets (VIN, serials, keys, account tokens,
  `/data` contents), in code, reports, logs or issues.
- Vehicle commands go through the car's own services, never our own CAN frames.
- Commit messages describe the change in plain words.

By contributing you agree your work is licensed under the project's AGPL-3.0.
