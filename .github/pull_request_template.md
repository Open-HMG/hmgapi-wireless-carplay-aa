## What this changes

## Tested on

- [ ] CI passes (HMG-API's mod workflow: build, `wireless-carplay-aa/test.sh`, `tools/*/test.sh`, script check)
- [ ] With the private stock trees, if you have them: `wireless-carplay-aa/test.sh` and `tools/recipes/test.sh` pass (they skip without them)
- [ ] Cars (model, region, head unit software), or "off-car only":

## Checklist

- [ ] New or changed hook points: a HMG-API pull request first (see CONTRIBUTING.md); `hooks.points` only lists points HMG-API has
- [ ] Mod scripts only use commands the head unit has (`$HMGAPI_SDK/bin/check-scripts.sh wireless-carplay-aa`)
- [ ] Anything written to `/system` keeps a stock check, a backup and an exact restore
- [ ] No manufacturer apps or libraries (only hash-only recipes, delta patch sets, checksums and our own code), no per-car data (VIN, serials, keys, `/data` contents)
- [ ] `mod.json` `version` left as it is (it's set at release)
