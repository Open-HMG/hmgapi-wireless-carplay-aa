#!/bin/sh
# Host test of the Wireless mod's CarPlay picture recovery policy (dipo/Keyframes):
# when a key frame is asked for, and how often at most.
set -e
cd "$(dirname "$0")"
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" $(find test -name '*.java') ../../src/hmgapi/mods/wireless/dipo/Keyframes.java
java -cp "$CP" hmgapi.mods.wireless.dipo.KeyframesTest
