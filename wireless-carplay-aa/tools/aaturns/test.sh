#!/bin/sh
# Host test of the Wireless mod's Android Auto turn-by-turn (AaTurns), with stand-ins
# for Android and HMG-API's Cluster/Car that record every cluster call.
set -e
cd "$(dirname "$0")"
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" $(find stubs test -name '*.java') ../../src/hmgapi/mods/wireless/AaTurns.java
java -cp "$CP" hmgapi.mods.wireless.AaTurnsTest
