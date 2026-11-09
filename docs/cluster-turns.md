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
data (`updateTBTTrapInfo`, `barGraphLevel`), with the speed limit and cameras; the
Wireless mod doesn't send trap data (the car navigation's speed limit keeps going
through).

**Side streets** (the 16-bit `sideStreet`, one bit per 22.5 degrees) are the roads
the cluster draws around a `TURN` arrow. The car's navigation sent 0 (none) on every
turn of the drive. `dumpsys` prints 0 as every index "0 1 ... 15" and 65535 as "None",
its check is inverted. 65535 is what the projection icons always carried, and with a
`TURN` type it draws a road in every direction, so the mod sends 0 with the car's own
types and 65535 with the icons.

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

## CarPlay

CarPlay's turn-by-turn (`dipo/CpTurns`) sends the projection icons only. The phone gives
each maneuver an iAP2 ManeuverType (the same numbers as CarPlay's `CPManeuverType`), a
junction type and an exit angle (degrees, negative to the left). A table of exact matches
picks the icon first; anything it doesn't match gets the nearest icon the car has, the same
ones Android Auto's turns use:

| CarPlay maneuver | Icon |
|---|---|
| off ramp (8), no side | 86 |
| highway off ramp left / right (22 / 23), any angle | 82 / 81 |
| on ramp (9), change highway (51) | 172 |
| keep left / right (13 / 14), change highway left / right (52 / 53) | 174 / 173 |
| roundabout exits (28-46), enter/exit (6, 7), any maneuver at a roundabout junction | the exit angle's nearest of 96 97 99 100 102 104 105 107 |
| left / right / sharp / slight turns at an angle the table doesn't have | 66-71 by the angle |
| destinations (10, 12, 27; 24 / 25) | 112 (114 / 113) |
| the departure (11), a type Apple adds later | setting `tbt_no_icon`: straight (65) with the distance by default |

The turns ahead (`updateTPTurnList`) and the HUD list get the same icons, and never 64.

Clearing the turn while guidance is on is as bad as 64: on a US EV6 (260507, 2026-11-08)
the cluster drew its spinner for the whole of each stretch whose next maneuver the table
didn't match, and the distance and trip went with it. On Rt 2 and I-95 that was every
highway exit and keep left/right (types 8, 13, 14, up to 17 miles), and every rotary
(28-30 at angles like -54, 61, -127). City turns (types 1, 2, 3, 47, 49, 50, 24, 25) were
in the table. `tbt_no_icon` keeps "clear", "skip" (2.1.0) and "no_info" (icon 1) as
choices.

## When a CarPlay route ends

While a phone guides, the mod skips the car navigation's own turn-by-turn calls to the
cluster (`navi.tbt`), so they don't fight the phone's. With CarPlay that now lasts only
while a route is on: `CpTurns` tells the car navigation's process when the route starts and
ends (broadcast `hmgapi.mods.wireless.CP_ROUTE`, sent only to `com.mnsoft.navi` and only
from apps holding `BLUETOOTH_PRIVILEGED`), and when it ends it clears the destination name.

Letting the calls through again isn't enough: the car navigation sends its compass and
"no route" state once, when it changes, not again. So the mod keeps the last call of each
cluster method it skipped (`update_tbt_info`, the `update_tbt_sub_info` family,
`update_remain_dis`, `update_tp_crossroad_name`) and, 1 s after the route ends, sends them
again. On a US EV6 (260507, 2026-11-08) the cluster kept the spinner after a CarPlay route
ended until this; with it, the compass is back within about a second.

Android Auto's end of route isn't handled this way yet.

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
