#!/bin/sh
# The release files for one tag: the mod signed with the author key, its NEMESIS
# packages and SHA256SUMS, in out/assets. Run by .github/workflows/release.yml; runs
# the same on a computer:
#   HMGAPI_SDK=<unpacked SDK> .github/release.sh <tag> <author key>
# The tag must be v<mod.json version>. The key must be the author key this mod has
# always been signed with (.github/mod-key.pub, UUID below): an update signed with
# another key doesn't install over the old one (docs/mod-repos.md in HMG-API, section 5).
set -e
MOD=wireless-carplay-aa
UUID=7251ce71-a146-8a1e-958b-dbffc6af7359
REPO=https://github.com/Open-HMG/hmgapi-wireless-cp-aa
TAG=${1:?usage: release.sh <tag> <author key>}; KEY=${2:?usage: release.sh <tag> <author key>}
SDK=$(cd "${HMGAPI_SDK:?set HMGAPI_SDK to the unpacked HMG-API mod SDK}" && pwd)
cd "$(dirname "$0")/.."
case $KEY in /*) ;; *) KEY=$PWD/$KEY ;; esac
V=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$MOD/mod.json")
[ "$TAG" = "v$V" ] || { echo "tag $TAG, but $MOD/mod.json's version is $V: tag v$V"; exit 1; }
# signing reads <key>.pub beside the key: the committed one (a .pub already there must be it)
ENG="java -cp $SDK/lib/hmgapi-engine.jar hmgapi.engine.Signing"
p=$($ENG uuid .github/mod-key.pub)
[ "$p" = "$UUID" ] || { echo ".github/mod-key.pub is author key $p, wanted $UUID"; exit 1; }
if [ -f "$KEY.pub" ]; then cmp -s "$KEY.pub" .github/mod-key.pub || { echo "$KEY.pub isn't .github/mod-key.pub: wrong key?"; exit 1; }
else cp .github/mod-key.pub "$KEY.pub"; fi
rm -rf out/build out/work out/packages out/dist out/assets
sh "$SDK/bin/build-mod.sh" "$MOD" "out/build/$MOD" "out/work/$MOD"
Z=$(HMGAPI_MOD_BUILD="$PWD/out/build/$MOD" sh "$SDK/bin/mkmodpkg.sh" "$MOD" "$KEY" | tail -1)
[ "$(basename "$Z")" = "$MOD-$V.zip" ] || { echo "$Z"; echo "signing failed: is the key (MOD_KEY) the private half of .github/mod-key.pub, author $UUID?"; exit 1; }
# the signature must check out with the author's UUID, as the engine checks it on the car
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
unzip -q "$Z" -d "$W"
$ENG verify "$W" "$UUID"
sh "$SDK/bin/mknemesismod.sh" "$Z" out/dist
mkdir -p out/assets
cp "$Z" out/dist/"$MOD-$V"-nemesis.zip out/dist/"$MOD-$V"-nemesis-package.zip out/assets/
(cd out/assets && sha256sum *.zip > SHA256SUMS && cat SHA256SUMS)
# for the catalogue PR in HMG-API, once the release is published (a maintainer's)
D=$REPO/releases/download/$TAG/$MOD-$V.zip
printf '%s\n' "curl -fLO $D" "tools/catalog.py add $MOD-$V.zip --download $D --repo $REPO" > out/catalog-command.txt
echo "Catalogue (in HMG-API, after publishing):"; cat out/catalog-command.txt
