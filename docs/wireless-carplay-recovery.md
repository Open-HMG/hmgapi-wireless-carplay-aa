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

## Telling a drop from a normal end

On 2026-11-08 the EV6 logged 22 session ends. Nine were abrupt: the CarPlay channel's iAP2
sends failed 0-10 ms before the end. One was the 19:56 drop; the other eight were each the
last end of a drive, the head unit powering off (nothing follows them in the log). One more
was the 0.8 s phantom right after the drop. The other 12 (the 23:01 Wi-Fi adb case, ends
around test reboots and installs, and others) had no failed send for at least 15 minutes
before. The phone's Bluetooth (an "iPhone BT connected"
event) comes back about 6 s after every end, a normal one too, so that can't tell them
apart: nlw.7-9 armed on it, which would have restarted a session the user ended.

## Rules

From open receivers that do the same: DiPlay (startup retry budget, first-TCP watchdog),
playport's supervisor, WirelessAndroidAutoDongle and aa-proxy-rs.

| Rule | Value |
|---|---|
| When it acts | only after an abnormal end ("armed"), decided at each end: an abrupt end, where the CarPlay channel's iAP2 sends failed within 1 s before it (the Wi-Fi link died under the session), or a session that ends within 2 s, before its iAP2 channel came up. A normal end may be the user's (Disconnect on the phone keeps Bluetooth for calls), so it's left alone, as is a phone that opens iAP2 over Bluetooth and doesn't start CarPlay, and a session the watchdog brought back that then ends normally |
| A session that is up | never touched; one attempt at a time |
| The phone | only one connected over Bluetooth (HFP). After an abnormal end with the phone off HFP (the iPhone may drop it during wireless CarPlay), it's armed if HFP comes back within 15 s; a phone that left, turned Bluetooth off or is in airplane mode is left alone |
| Never | during a call, with USB CarPlay, or while the head unit's Wi-Fi is on as a client (its hotspot is off then, as with adb over Wi-Fi) |
| Wait | 12 s from iAP2 over Bluetooth to the session, longer than any normal start seen (2.3-8.4 s, 11.1 s once after a boot): each attempt restarts the Bluetooth iAP2 session, which would cut off a phone that's already starting one. 12 s from an armed end to the phone opening iAP2 again |
| Attempts | backoff 2 s doubling to 30 s; at most 5; then it gives up until the phone connects over Bluetooth again (a session ending doesn't start it over) |
| Budget reset | after a session that ran 60 s |

An attempt is iAP2 identification again when SPP (the Bluetooth serial link iAP2 runs on)
is up, or an SPP connect when it isn't. Every decision is in HMG-API's log, starting
`bridge: watchdog:` (Bluetooth addresses masked to the last two bytes).

## Not done

The control-channel step Apple lists first (CarPlay's `/ctrl-int/1/connect` to the phone's
`_carplay-ctrl._tcp` service; DiPO has `CarPlayService.connectCarPlay(deviceID)`). It needs
new hook points, and the device ID DiPO expects can't be read from its quickened odex.

## Testing

`sh wireless-carplay-aa/tools/watchdog/test.sh` runs on a host. It replays the 19:56 drop
(first attempt 16 s after the drop with the phone on HFP, 21 s with it off HFP until it
comes back, instead of the 2 min it took by hand), a Wi-Fi adb case (2026-11-08 23:01), a
session the user ends, a slow start and each rule. It also replays every event of that day
(`tools/watchdog/test/ev6-20261108.events`, event kinds and times only, from HMG-API's log):
the watchdog arms on the 9 abrupt ends and the phantom only, and makes no attempt after
any other end.

Not yet seen on a car: a user's Disconnect on the phone (the test assumes it's a clean end,
like the 13 seen) and an abrupt end with the phone still on HFP.
