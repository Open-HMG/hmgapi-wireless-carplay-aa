#!/bin/sh
# Off-car test of the mod's native recipes in memory (hmgapi.engine.MemRecipe): the Android
# Auto recipes on the real stock libraries against the file-patched builds, and the CarPlay
# recipes against lpatch's own result on the file. Needs the private stock trees
# (~/hmg-private); skipped without them.
set -e
cd "$(dirname "$0")/../../../.."
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" core/runtime/java/hmgapi/engine/*.java core/runtime/test/hmgapi/engine/MemRecipeTest.java 2>/dev/null
H=$HOME/hmg-private; R=mods/wireless-carplay-aa/recipes
set --
[ -f $H/stock/ev6/lib/libautoreceiver_infobank.so ] && set -- "$@" $H/stock/ev6/lib/libautoreceiver_infobank.so $H/builds/2_tbt/files/libautoreceiver_infobank.so $R/aa-tbt-48ee793a.recipe
[ -f $H/stock/neev-can-260206/lib/libautoreceiver_infobank.so ] && set -- "$@" $H/stock/neev-can-260206/lib/libautoreceiver_infobank.so $H/builds-neev_ca/2_tbt/files_neev_ca/libautoreceiver_infobank.so $R/aa-tbt-a0bea1c4.recipe
# the CarPlay recipes (lpatch recipes, used as they are): against lpatch's own result on the file
L=out/lpatch-host; [ -x $L ] || L=tools/lpatch/lpatch-host
for x in core:carplay-core-wifi audio:carplay-audio-formats utils:carplay-utils-converter; do
    lib=$H/stock/ev6/lib/libCarPlay$(echo ${x%%:*} | sed 's/^./\U&/').so; rc=$R/${x#*:}.recipe
    [ -f "$lib" ] && [ -x $L ] || continue
    $L apply $rc "$lib" "$CP/${x#*:}.so" >/dev/null && set -- "$@" "$lib" "$CP/${x#*:}.so" $rc
done
[ $# -gt 0 ] || { echo "no stock libraries in $H (skipped)"; exit 0; }
exec java -cp "$CP" hmgapi.engine.MemRecipeTest "$@"
