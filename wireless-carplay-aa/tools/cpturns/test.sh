#!/bin/sh
# Host test of the Wireless mod's CarPlay turn-by-turn (dipo/CpTurns), with the Android
# Auto test's stand-ins for Android and HMG-API's Cluster/Car, and the mod's own iAP2
# message stub to build messages with.
set -e
cd "$(dirname "$0")"
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" $(find stubs test ../aaturns/stubs -name '*.java') \
    ../../stubs/com/tcc/iap2/Iap2NotiMsg.java ../../src/hmgapi/mods/wireless/dipo/CpTurns.java
java -cp "$CP" hmgapi.mods.wireless.dipo.CpTurnsTest
