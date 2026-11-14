# The cluster's turn display: what the car's navigation sends

How the stock navigation (`com.mnsoft.navi`, its native engine) fills
`NavigationCluster.updateTBTInfo`, worked out from its cluster code and two drives on a
US Ioniq 5 (NEEV23, 260507, 2026-11-03). The Wireless mod's Android Auto turn-by-turn
(`AaTurns`, setting `tbt_standard`) follows it. Nothing here is the car's code; it
describes what the car does.

## Two formats

- **The main display** (`updateTBTInfo`: type, side streets, direction, distance, bar):
  near a maneuver the engine sends a standard type from `Navigation.DisplayType` with
  the maneuver's angle and a distance bar. Farther away it sends the projection icon
  (the same codes as below) with the bar off, or `DIRECT_TO_DEST` (2, the destination
  compass) when no maneuver is near.
- **The HUD's turn list and the upcoming turns** (`updateTPTurnListNew`,
  `updateTPTurnList`): the projection icons, 64 and up. HMG-API's codes are the same
  as the car's: straight 65; slight right/left 66/71; right/left 67/70; sharp right/left
  68/69; U-turn 72/73; exits and ramps 81-91; roundabouts 96-107 (192-203 for
  left-hand traffic); destinations 112-114; on-ramps and forks 172-174; ferry 176.

## Near a maneuver

The engine tries, in order: destination or waypoint, highway exit, turn point, service
area, roundabout, overpass, underpass, tunnel/toll. The first that applies sets the
main display:

| Maneuver | Type (`DisplayType`) | Direction |
|---|---|---|
| straight, turn, slight, sharp | `TURN` 13 | 0; slight 45/315; turn 90/270; sharp 135/225 |
| U-turn | `U_TURN` 31 | 270 (90 for left-hand traffic) |
| fork | `FORK_2` 25 | 90 / 270 |
| highway exit right / left | `EXIT_RIGHT` 17 / `EXIT_LEFT` 18 (`*_COMBI_1` 19/20 when another maneuver follows) | 90 / 270 |
| roundabout | `EXIT_ROUNDABOUT_TRS_RIGHT` 32 (33 for left-hand traffic) | the exit's direction |
| destination ahead / right / left | icon 112 / 113 / 114, no bar | 0 |
| arrived | `ARRIVED` 3 | 0 |

Angles are in 45 degree steps (the engine works in 7.5 degree units: 0, 6, 12 ... 42).

**The distance bar** is `(100 - progress) / 10`, at least 1, where the progress runs
from the start of the maneuver's guidance to the maneuver. On the drives that start
was about 457 m (1,500 ft) out: 0.3 mi 9, 900 ft 6, 800 ft 5, 300 ft 2, 30 ft 1. The
cluster shows it ×10 (90 ... 10). The navigation also sends the same bar in its trap
data (`updateTBTTrapInfo`, `barGraphLevel`), with the speed limit and cameras. The
cluster's bar is drawn from that trap data, which also sets the speed-limit sign, so
the Wireless mod never sends trap data of its own, and under Android Auto the bar stays
empty for now. [ar-hud.md](ar-hud.md) has the planned way to fill it with the car
navigation's speed limit kept.

**Side streets** (the 16-bit `sideStreet`, one bit per 22.5 degrees) are the roads
the cluster draws around a `TURN` arrow. The car's navigation sent 0 (none) on every
turn of the drive. `dumpsys` prints 0 as every index "0 1 ... 15" and 65535 as "None",
its check is inverted. 65535 is what the projection icons always carried, and with a
`TURN` type it draws a road in every direction, so the mod sends 0 with the car's own
types and 65535 with the icons.

CarPlay's turns follow the same format with the setting on; [ar-hud.md](ar-hud.md#carplay) has its mapping from iAP2 maneuvers.

## Roundabouts

Android Auto guides a roundabout in two steps: `ROUNDABOUT_ENTER_AND_EXIT` with the exit's
angle before it, then `ROUNDABOUT_EXIT` once the car is in it, usually with no side and no
angle. The Wireless mod keeps the exit icon from the first for the second, until the next
maneuver.

The exit icons are the car navigation's own, one per 45 degrees: 96 (straight on), 97, 99,
100, 102, 104, 105, 107. It never uses 103 or 106, and on a Creta Electric 106's
left-hand traffic twin (0xca) draws the loading spinner, so the mod doesn't send them either.
Roundabouts in the turns ahead get the same icons, with the cluster's direction rather than
Android Auto's angle.

**Which way round.** Measured on a US Ioniq 5 (issue #19), parked, by sending each code to the
cluster service over adb:

- In the car's own format, `EXIT_ROUNDABOUT_TRS_RIGHT` (32) draws a counterclockwise roundabout
  (right-hand traffic) and `EXIT_ROUNDABOUT_TRS_LEFT` (33) a clockwise one. The exit is drawn
  at the direction sent, in both.
- The icons 96-107 are counterclockwise, and 192-203 (each + 96) clockwise, with the exit at
  the same place for the same position in the set (0xc3 is a right exit, like 99).
- 103 and 106 draw the same as 104 and 107 on this car.

Android Auto's side isn't the way round: it marked a US (counterclockwise) roundabout LEFT, and
33 drew it clockwise. So the mod picks 32 or 33 from the icon's set (counterclockwise on every
car it supports), and inside a roundabout it keeps the exit icon whatever side Android Auto
gives.

## Never 64

The mod never sends 64 (0x40, "no direction"). The cluster draws its loading spinner for
it, in the turn and in the HUD list, measured on a Creta Electric
([sirishant/HMG-API, `docs/creta/cluster-turn-by-turn.md`](https://github.com/sirishant/HMG-API/blob/creta-su2iev/docs/creta/cluster-turn-by-turn.md)),
and on the road it stayed on through a roundabout until the next instruction. Far from a
turn the cluster draws its card from the turn list, so the turns ahead matter as much as
the turn itself.

- A roundabout with no exit to show, an unknown maneuver and a turn with no side: straight (65).
- A fork with no side: the plain fork icon (172). Before, it kept the previous maneuver's icon.
- The turns ahead get the same icons as the turn itself: ramps and forks 172-174, exits
  81/82/86, ferries 176, destinations 112-114, straight for the rest.

## Rerouting

Android Auto reports a reroute as status 3. With the car's own format on (`tbt_standard`),
a reroute still going after 1 second shows the cluster's "Recalculating..." (type 10,
`RECALC ROUTE`: drawn with a spinner on a US Ioniq 5, sent parked over adb, and on a Creta
Electric), until the new route's first turn, or the turn it had when Android Auto goes back
to guiding on the same route. Distances for the old turn don't replace it. A shorter reroute
leaves the turn as it is. Without it, the AR HUD broke for about a minute after a missed exit
on a US EV6 while Android Auto's new route settled.

## Lanes

The car navigation keeps running under a phone's guidance with no route, and sends the
lanes of each intersection it passes (`updateLaneInfo`, none marked). The Wireless mod
skips that call while a phone guides; Android Auto's lanes (with its recommended lanes
marked) go to the cluster alone.

## Checking on a car

`adb shell dumpsys com.hkmc.micom.cluster` prints the last values the cluster service
was sent (type by name, direction, distance, bar, trap data). Once a second during a
drive, with the car's navigation and then Android Auto on the same route, it shows any
difference.
