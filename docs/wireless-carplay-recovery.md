# Wireless CarPlay recovery (the watchdog)

When a wireless CarPlay session ends and the iPhone doesn't start a new one, the car asks
it to (`dipo/Watchdog`, the policy; `dipo/Bridge`, which carries it out). Setting
`bridge_watchdog`, on by default.

## Why the car acts

In Apple's model the head unit decides whether to reconnect (WWDC 2017, session 717,
"Enabling Wireless CarPlay"): never after the user ends the session, but after an
unexpected loss, first through the CarPlay control channel, then over Bluetooth. The mod
does the Bluetooth step: it runs iAP2 identification over Bluetooth again, which gives the
phone the hotspot's details and has it start the session.

Seen on a US EV6 (260507, 2026-11-08 19:56): the phone's Wi-Fi and Bluetooth hiccuped, a
resume attempt ended after 0.8 s, Bluetooth and iAP2 came back 4 s later, and the phone
then never started CarPlay. It stayed that way for 110 s until airplane mode was toggled;
the session was then up in 3 s.

## Rules

From open receivers that do the same: DiPlay (startup retry budget, first-TCP watchdog),
playport's supervisor, WirelessAndroidAutoDongle and aa-proxy-rs.

| Rule | Value |
|---|---|
| When it acts | only after an abnormal end: a session that ends within 2 s, before its iAP2 channel came up, or the phone's Bluetooth reconnecting within 15 s of an end ("armed"). A normal end may be the user's (Disconnect on the phone keeps Bluetooth for calls), so it's left alone, as is a phone that opens iAP2 over Bluetooth and doesn't start CarPlay |
| A session that is up | never touched; one attempt at a time |
| The phone | only one still connected over Bluetooth (HFP); a phone that left, turned Bluetooth off or is in airplane mode is left alone |
| Never | during a call, with USB CarPlay, or while the head unit's Wi-Fi is on as a client (its hotspot is off then, as with adb over Wi-Fi) |
| Wait | 12 s from iAP2 over Bluetooth to the session, longer than any normal start seen (2.3-8.4 s, 11.1 s once after a boot): each attempt restarts the Bluetooth iAP2 session, which would cut off a phone that's already starting one. 12 s from an armed end to the phone opening iAP2 again |
| Attempts | backoff 2 s doubling to 30 s; at most 5; then it gives up until the phone connects over Bluetooth again |
| Budget reset | after a session that ran 60 s |

An attempt is iAP2 identification again when SPP (the Bluetooth serial link iAP2 runs on)
is up, or an SPP connect when it isn't. Every decision is in HMG-API's log, starting
`bridge: watchdog:` (Bluetooth addresses masked to the last two bytes).

## Not done

The control-channel step Apple lists first (CarPlay's `/ctrl-int/1/connect` to the phone's
`_carplay-ctrl._tcp` service; DiPO has `CarPlayService.connectCarPlay(deviceID)`). It needs
new hook points, and the device ID DiPO expects can't be read from its quickened odex.

## Testing

`sh wireless-carplay-aa/tools/watchdog/test.sh` replays the 19:56 drop (first attempt at
19:56:21.8, 18 s after the drop, instead of the 2 min it took by hand), a Wi-Fi adb case
(2026-11-08 23:01), a session the user ends, a slow start, and each rule, on a host.
