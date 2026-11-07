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
