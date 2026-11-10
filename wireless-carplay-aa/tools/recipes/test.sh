#!/bin/sh
# Off-car test of the mod's native recipes in memory (hmgapi.engine.MemRecipe): the Android
# Auto recipes on the real stock libraries against the file-patched builds, and the CarPlay
# recipes against lpatch's own result on the file. Needs the private stock trees
# (~/hmg-private); skipped without them. Runs with the HMG-API mod SDK (HMGAPI_SDK).
set -e
R=$(cd "$(dirname "$0")/../../recipes" && pwd)
SDK=$(cd "${HMGAPI_SDK:?set HMGAPI_SDK to the unpacked HMG-API mod SDK}" && pwd)
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
H=$HOME/hmg-private
set --
[ -f $H/stock/ev6/lib/libautoreceiver_infobank.so ] && set -- "$@" $H/stock/ev6/lib/libautoreceiver_infobank.so $H/builds/2_tbt/files/libautoreceiver_infobank.so $R/aa-tbt-48ee793a.recipe
[ -f $H/stock/neev-can-260206/lib/libautoreceiver_infobank.so ] && set -- "$@" $H/stock/neev-can-260206/lib/libautoreceiver_infobank.so $H/builds-neev_ca/2_tbt/files_neev_ca/libautoreceiver_infobank.so $R/aa-tbt-a0bea1c4.recipe
# the CarPlay recipes (lpatch recipes, used as they are): against lpatch's own result on the file
L=$SDK/bin/lpatch-host
for x in core:carplay-core-wifi core:carplay-core-read-wait audio:carplay-audio-formats utils:carplay-utils-converter; do
    lib=$H/stock/ev6/lib/libCarPlay$(echo ${x%%:*} | sed 's/^./\U&/').so; rc=$R/${x#*:}.recipe
    [ -f "$lib" ] && [ -x $L ] || continue
    $L apply $rc "$lib" "$CP/${x#*:}.so" >/dev/null && set -- "$@" "$lib" "$CP/${x#*:}.so" $rc
done
[ $# -gt 0 ] || { echo "no stock libraries in $H (skipped)"; exit 0; }
exec java -cp "$SDK/lib/hmgapi-engine.jar:$SDK/test/hmgapi-engine-test.jar" hmgapi.engine.MemRecipeTest "$@"
