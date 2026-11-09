#!/bin/sh
# Host test of the wireless CarPlay watchdog's policy (dipo/Watchdog), which has no Android in it.
set -e
cd "$(dirname "$0")"
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" $(find test -name '*.java') ../../src/hmgapi/mods/wireless/dipo/Watchdog.java
java -cp "$CP" hmgapi.mods.wireless.dipo.WatchdogTest
