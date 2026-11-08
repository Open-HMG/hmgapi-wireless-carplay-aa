# The AR HUD and the speed limit under Android Auto

How the Wireless mod gets the AR HUD to animate Android Auto's turns, and what it takes
to fill the cluster's distance bar without losing the car's speed-limit sign. Worked out
on a US Ioniq 5 with the AR HUD (NEEV23, head unit 260507, November 2026) from
`dumpsys com.hkmc.micom.cluster`, logcat and the cluster service's logs on drives with
the car's navigation and with Android Auto. Nothing here is the car's code; it describes
what the car does. [cluster-turns.md](cluster-turns.md) has the full turn format.

## What triggers the AR HUD

The AR HUD draws its animated arrow from the cluster's **main turn display**
(`NavigationCluster.updateTBTInfo`) alone:

- **The turn type has to be one of the car's own `Navigation.DisplayType` values**
  (`TURN` 13 with its angle, `U_TURN` 31, `FORK_2` 25, `EXIT_RIGHT`/`EXIT_LEFT` 17/18,
  roundabouts 32/33). Android Auto's turns used to go out as the projection icons (64 and
  up). The cluster and the HUD's turn list draw those fine, but the AR HUD doesn't
  animate them. The car's navigation sends its own types only near a maneuver (about
  457 m, 1,500 ft) and the icons farther out, so the mod does the same.
- **The direction is the maneuver's angle** in 45 degree steps (0, 45/315, 90/270,
  135/225).
- **Side streets have to be 0.** The projection icons always carried 65535; with a `TURN`
  type that draws a road in every direction around the arrow. `dumpsys` shows it the
  other way round (0 as every index, 65535 as "None").
- **Nothing else is needed.** In AR mode the car's navigation sent no ADASIS or other
  map data and no `updateCluDataNew_14` pattern, so the turn type, angle, distance and
  bar are all the AR HUD gets.

This is the `tbt_standard` setting ("Android Auto turns in the car's own format", off by
default), `AaTurns.standard()`.

### The arrow gliding: the exact distance

The arrow moves toward the turn as the distance drops. Android Auto's turn-by-turn
reports the **display** distance once a second (50 ft or 0.1 mi steps), so the arrow
stepped and lagged. Near a maneuver the standard format now sends:

- **Maps' exact distance** (meters, converted to the cluster's unit) instead of the
  rounded one;
- **a countdown between Maps' reports**: every 250 ms the distance drops at Maps' own
  pace (its meters and seconds to the turn), for at most 1.5 s after its last report.
  If Maps goes quiet, the distance holds instead of running past the turn.

## The distance bar and the speed limit

### The problem

The bar that fills as the turn gets close isn't drawn from the turn frame. The cluster
draws it from the **trap frame** (`NavigationCluster.updateTBTTrapInfo`, field
`barGraphLevel`). The cluster service packs that call into one frame to the cluster, and
the same frame carries the speed limit:

| Byte | What |
|---|---|
| 8 | `barGraphLevel`: the distance bar |
| 9 | `speedLimit` |
| 10 | bus speed, speed-limit type and unit |
| 11 | speed info type |

**Whoever sends the trap frame last sets both the bar and the speed limit.**

While a phone guides, the car's navigation keeps running with no route. It sends the
trap frame **only when something changes** (the map's speed limit going 25 → 35 → 45,
mostly), with the bar 0 and its own map speed limit, unit (mph) and type. That's why
the bar stayed empty under Android Auto. The car's speed-limit sign comes from that map
data: in every capture, the speed-limit echo from the cluster (`NaviSLIFG3Info`, byte 14)
matched the car navigation's limit within 1 to 2 s. No camera (TSR) speed limits showed
up in any capture.

### What doesn't work

- **Sending the trap frame from Android Auto's side (GPM).** GPM has no idea what the
  speed limit is. Its own built-in trap call sends "no limit" (255) with the bar 100, and
  a frame with anything but the car navigation's limit replaces the sign until the car's
  navigation sends again, which can be minutes on one road.
- **Reading the limit from the cluster's echo and sending it back.** It arrives 1 to 2 s
  late, so the sign would be wrong at every change. It also only comes in through a
  listener class, and the mod would have to hard-code the US unit and type values, which
  Canada's cars don't use.

### What the mod does (2.2.1 test builds)

The bar goes the other way: from Android Auto's side to the car's navigation, which
already has the right speed limit.

1. **In GPM**, with standard turns on, `AaTurns` works out the bar the way the car does,
   `(100 - progress) / 10`, at least 1, over about 457 m, sent ×10 (90 down to 10). It
   broadcasts it (`hmgapi.mods.wireless.TBT_BAR`, extra `bar`) when it changes, every 2 s
   while it's on, and 0 when guidance stops.
2. **In the car's navigation** (`com.mnsoft.navi`), the Wireless hook on
   `updateTBTTrapInfo` (both forms: 17 ints, or `NaviTbtTrapInfo`) puts the bar into
   each trap frame the navigation sends. Everything else in the frame stays the car's:
   the speed limit, its unit and type, cameras and warnings.
3. **When the bar changes**, the hook sends the car navigation's last trap frame again
   with the new bar, since the navigation itself only sends on its own changes.
4. **It fails safe.** If the bar is more than 5 s old, or no phone is guiding, the frame
   goes through as the car's navigation sent it.

This needs four hook points HMG-API doesn't have yet, all in the framework:
`framework.navigation_cluster.update_tbt_trap_info`,
`framework.navigation_cluster.update_tbt_trap_info_new`, `framework.navi_tbt_trap_info`
and `framework.navi_tbt_trap_info.bar_graph_level`. They're mapped on the US Ioniq 5
260507 only. HMG-API turns off all of a mod's hooks on a car whose map lacks a point the
mod declares, so they have to be in HMG-API's catalogue and mapped on every car (a
HMG-API pull request, then `map-car.py` on the stock trees, or "Map this car from its
files" on cars mapped from Car Reports) before the bar can ship in Wireless.

## Checking on a car

- `adb shell dumpsys com.hkmc.micom.cluster`: the last turn frame (type by name,
  direction, distance) and trap frame (bar, speed limit) the cluster service got.
- logcat, tag `ClusterService`: each `NaviCluster.updateTBTTrapInfo()` with its
  `barGraphLevel` and `speedLimit`. With the fix, under Android Auto the bar counts down
  90 to 10 toward each turn and the speed limit stays the car navigation's.
- HMG-API's log (`/data/hmgapi/logs/hmgapi/log.log`): "navigation: Android Auto's
  distance bar goes in its trap frame (com.mnsoft.navi)" once the hook is in, or "...is
  off on this car (... not mapped)" when the car's map doesn't have the points.
