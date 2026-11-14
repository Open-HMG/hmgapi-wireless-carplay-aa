package hmgapi.mods.wireless.dipo;

import com.tcc.iap2.Iap2NotiMsg;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import hmgapi.hook.Car;
import hmgapi.hook.Cluster;

/**
 * Host test of CpTurns: a drive through iAP2 route guidance messages, checking every
 * cluster call against what the turn-by-turn build's DiPO manager (e/u, e/c, e/v)
 * sends for the same input (worked out from its smali).
 */
public final class CpTurnsTest {
    static int fail;
    static Cluster c;

    static void expect(String what, String... want) {
        List<String> got = c.calls;
        String w = String.join("\n", want), g = String.join("\n", got);
        if (w.equals(g)) {
            System.out.println("ok    " + what);
        } else {
            System.out.println("FAIL  " + what + "\n  wanted:\n    " + w.replace("\n", "\n    ") + "\n  got:\n    " + g.replace("\n", "\n    "));
            fail = 1;
        }
        got.clear();
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) fail = 1;
    }

    static String utf16(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) b.append(String.format("%02x%02x", ch & 0xff, ch >> 8));
        String r = b.toString();
        while (r.endsWith("00")) r = r.substring(0, r.length() - 2);
        return r;
    }

    /** iAP2 types: 1/5 small ints, 6 uint32, 7 uint64, 8 enum, 10 string, 13 groups */
    static final int U8 = 1, U16 = 5, U32 = 6, U64 = 7, ENUM = 8, STR = 10, GROUPS = 13;

    static Iap2NotiMsg msg(Object... items) {
        Iap2NotiMsg m = new Iap2NotiMsg();
        for (int i = 0; i < items.length; i += 3) m.getItemGroup().add((Integer) items[i], (Integer) items[i + 1], items[i + 2]);
        return m;
    }

    static int[] i(int... v) {
        return v;
    }

    static long[] l(long... v) {
        return v;
    }

    static String[] s(String v) {
        return new String[] { v };
    }

    /** a RouteGuidanceManeuverUpdate for component 1 */
    static Iap2NotiMsg maneuver(int index, int type, int side, int junction, int exit, String after, int distance) {
        return msg(0, U16, i(1), 1, U16, i(index), 3, ENUM, i(type), 4, STR, s(after), 5, U32, i(distance),
                8, ENUM, i(side), 9, ENUM, i(junction), 11, U8, i(exit));
    }

    /** Public, synthetic fixtures for the ordinary turns observed on India 260430.
     * No car dumps or private route strings are needed for this regression. */
    static void indiaTurns() {
        check("India Apple Maps left/right and straight", CpTurns.icon(1, 1, 0, -90, 1) == 70
                && CpTurns.icon(2, 1, 0, 90, 1) == 67 && CpTurns.icon(3, 1, 0, 0, 1) == 65);
        for (int side = 0; side <= 1; side++) {
            check("explicit ordinary turns without optional angle, side " + side,
                    CpTurns.icon(1, side, 0, 0, 1) == 70 && CpTurns.icon(2, side, 0, 0, 1) == 67);
            check("departure is NO_INFO, not a straight arrow, side " + side,
                    CpTurns.icon(11, side, 0, 0, 1) == 1);
        }
        check("invalid traffic sides do not gain ordinary turn/departure fallbacks",
                CpTurns.icon(1, 2, 0, 0, 1) == 0 && CpTurns.icon(2, -1, 0, 0, 1) == 0
                        && CpTurns.icon(3, 2, 0, 0, 1) == 0 && CpTurns.icon(11, 2, 0, 0, 1) == 0);
        check("unrecognized angles and junctions stay unsupported",
                CpTurns.icon(1, 1, 0, -91, 1) == 0 && CpTurns.icon(2, 1, 0, 91, 1) == 0
                        && CpTurns.icon(11, 1, 0, 90, 1) == 0 && CpTurns.icon(11, 1, 1, 0, 1) == 0);
        c = new Cluster();
        CpTurns t = new CpTurns(new android.content.Context(), c, null);
        t.maneuver(maneuver(0, 2, 1, 0, 90, "Test Road", 100));
        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(1), 11, STR, s("100"),
                12, ENUM, i(2), 13, U16, i(0)));
        expect("India Apple right turn reaches cluster and HUD",
                "road [Test Road]", "tbt 67 0 0.0 100.0 0 0 0 0 0",
                "hud f201430000" + utf16("Test Road"), "2c 0 0 100 0 0 0 0 0 0");
        t.maneuver(msg(0, U16, i(1), 1, U16, i(0), 3, ENUM, i(11)));
        t.routeGuidance(msg(0, U16, i(1)));
        expect("Google departure clears prior HUD turn and sends non-directional NO_INFO",
                "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2",
                "road [Test Road]", "tbt 1 0 0.0 100.0 0 0 0 0 0", "2c 0 0 100 0 0 0 0 0 0");
        t.maneuver(msg(0, U16, i(1), 1, U16, i(0), 3, ENUM, i(2)));
        t.routeGuidance(msg(0, U16, i(1)));
        expect("Google right without angle reaches cluster and HUD after departure",
                "road [Test Road]", "tbt 67 0 0.0 100.0 0 0 0 0 0",
                "hud f201430000" + utf16("Test Road"), "2c 0 0 100 0 0 0 0 0 0");
        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(0)));
        expect("India route stop still clears HUD", "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");
    }

    public static void main(String[] a) throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        Car.unit = Car.DISTANCE_MILES;
        c = new Cluster();
        final CountDownLatch restarted = new CountDownLatch(1);
        CpTurns t = new CpTurns(new android.content.Context(), c, new Runnable() {
            @Override
            public void run() {
                restarted.countDown();
            }
        });
        final int RIGHT = 2, LEFT = 1, STRAIGHT = 3, UTURN = 4, ARRIVE = 12, MILES = 1, KM = 0, FEET = 4;

        t.maneuver(maneuver(0, RIGHT, 0, 0, 90, "Main St", 300));
        t.maneuver(maneuver(1, LEFT, 0, 0, -90, "Oak Ave", 800));
        t.maneuver(maneuver(2, STRAIGHT, 0, 0, 0, "Elm St", 0));
        t.maneuver(maneuver(3, UTURN, 0, 0, 0, "Pine St", 2000));
        expect("maneuvers alone: nothing sent");

        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(1), 7, U32, i(5000), 9, ENUM, i(MILES), 11, STR, s("0.2"),
                12, ENUM, i(MILES), 13, U16, i(0, 1, 2, 3), 5, U64, l(1700000000L), 19, STR, s("Maps")));
        expect("right turn in 0.2 mi: crossroad, turn, HUD list with the next turn, HUD distance, trip, turns ahead",
                "road [Main St]", "tbt 67 0 0.0 0.2 0 0 2 0 0", "hud f202434600" + utf16("Main St"), "2c 2 2 0 0 0 0 0 0 0",
                "sub 3.1 0 2 22 13 1 1 0.0 -1 0",
                "list {1 70 0 [Oak Ave] [] " + (800 / 1609f) + " 2} {2 72 0 [Pine St] [] " + (2000 / 1609f) + " 2}");

        t.routeGuidance(msg(0, U16, i(1), 11, STR, s("0.1")));
        expect("partial update (distance only): same HUD list not resent",
                "road [Main St]", "tbt 67 0 0.0 0.1 0 0 2 0 0", "2c 2 1 0 0 0 0 0 0 0", "sub 3.1 0 2 22 13 1 1 0.0 -1 0",
                "list {1 70 0 [Oak Ave] [] " + (800 / 1609f) + " 2} {2 72 0 [Pine St] [] " + (2000 / 1609f) + " 2}");

        Iap2NotiMsg lanes = new Iap2NotiMsg();
        Iap2NotiMsg.Iap2ObjGroup l1 = lanes.new Iap2ObjGroup(), l2 = lanes.new Iap2ObjGroup();
        l1.add(1, U16, i(1)).add(2, U16, i(0));
        l2.add(2, U16, i(90, 45)).add(3, U16, i(90));
        lanes.getItemGroup().add(0, U16, i(1)).add(2, GROUPS, new Iap2NotiMsg.Iap2ObjGroup[] { l1, l2 });
        t.lanes(lanes);
        StringBuilder z = new StringBuilder();
        for (int k = 0; k < 14; k++) z.append("00");
        expect("lanes: straight; right + slight right highlighted", "lanes f102000102" + z + "0100000302");

        Car.unit = Car.DISTANCE_KM;
        t.routeGuidance(msg(0, U16, i(1), 7, U32, i(800), 11, STR, s("1.5"), 12, ENUM, i(KM)));
        expect("km: turn in the phone's km, trip in m (owner's km, under 1 km), turns ahead in m/km",
                "road [Main St]", "tbt 67 0 0.0 1.5 0 0 1 0 0", "2c 1 5 1 0 0 0 0 0 0", "sub 800.0 0 0 22 13 1 1 0.0 -1 0",
                "list {1 70 0 [Oak Ave] [] 800.0 0} {2 72 0 [Pine St] [] 2.0 1}");
        Car.unit = Car.DISTANCE_MILES;

        t.maneuver(msg(0, U16, i(1), 1, U16, i(0), 5, U32, i(250)));
        t.routeGuidance(msg(0, U16, i(1), 11, STR, s("0.1"), 12, ENUM, i(MILES)));
        expect("maneuver update without optional angle retains its explicit right-turn direction",
                "road [Main St]", "tbt 67 0 0.0 0.1 0 0 2 0 0", "2c 2 1 0 0 0 0 0 0 0", "sub 0.4 0 2 22 13 1 1 0.0 -1 0",
                "list {1 70 0 [Oak Ave] [] " + (800 / 1609f) + " 2} {2 72 0 [Pine St] [] " + (2000 / 1609f) + " 2}");
        t.maneuver(maneuver(0, RIGHT, 0, 0, 90, "Main St", 250));

        t.routeGuidance(msg(0, U16, i(1), 7, U32, i(100), 6, U64, l(0), 5, U64, l(0)));
        expect("trip under 161 m with miles: feet; no arrival time",
                "road [Main St]", "tbt 67 0 0.0 0.1 0 0 2 0 0", "2c 2 1 0 0 0 0 0 0 0", "sub 328.0 0 3 -1 -1 -1 -1 0.0 -1 0",
                "list {1 70 0 [Oak Ave] [] " + (800 / 1609f) + " 2} {2 72 0 [Pine St] [] " + (2000 / 1609f) + " 2}");

        t.routeGuidance(msg(0, U16, i(1), 7, U32, i(0), 6, U64, l(600)));
        Calendar in10 = Calendar.getInstance();
        in10.add(Calendar.SECOND, 600);
        expect("arrival from the time remaining (no distance)",
                "road [Main St]", "tbt 67 0 0.0 0.1 0 0 2 0 0", "2c 2 1 0 0 0 0 0 0 0",
                "sub 0.0 -1 0 " + in10.get(Calendar.HOUR_OF_DAY) + " " + in10.get(Calendar.MINUTE) + " 1 1 0.0 -1 0",
                "list {1 70 0 [Oak Ave] [] " + (800 / 1609f) + " 2} {2 72 0 [Pine St] [] " + (2000 / 1609f) + " 2}");

        t.routeGuidance(msg(0, U16, i(1), 6, U64, l(0), 5, U64, l(600000000L)));
        expect("arrival as seconds since 2001 (Apple's epoch): 2020-01-06 10:40",
                "road [Main St]", "tbt 67 0 0.0 0.1 0 0 2 0 0", "2c 2 1 0 0 0 0 0 0 0", "sub 0.0 -1 0 10 40 1 1 0.0 -1 0",
                "list {1 70 0 [Oak Ave] [] " + (800 / 1609f) + " 2} {2 72 0 [Pine St] [] " + (2000 / 1609f) + " 2}");

        t.routeGuidance(msg(0, U16, i(1), 19, STR, s("Waze"), 5, U64, l(0)));
        check("source change: route guidance restarted", restarted.await(2, TimeUnit.SECONDS));
        expect("source change (Maps to Waze): HUD cleared; the reset state is 0 until the phone resends it, so cleared again",
                "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2", "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");

        t.maneuver(maneuver(5, ARRIVE, 0, 0, 0, "Home Rd", 100));
        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(6), 4, STR, s("Home"), 11, STR, s("300"), 12, ENUM, i(FEET), 13, U16, i(5)));
        expect("state 6 with destination, arrival maneuver in 300 ft",
                "dest 0 [Home]", "road [Home Rd]", "tbt 112 0 0.0 300.0 0 0 3 0 0", "hud f201700000" + utf16("Home Rd"),
                "2c 3 0 300 0 0 0 0 0 0");

        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(2)));
        expect("arrived (state 2): icon 3, no HUD list for it", "road [Home Rd]", "tbt 3 0 0.0 300.0 0 0 3 0 0", "2c 3 0 300 0 0 0 0 0 0");

        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(0)));
        expect("state 0: HUD cleared", "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");

        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(1)));
        t.stop();
        c.calls.clear();
        t.stop();
        expect("route guidance stopped (car took turn-by-turn back): HUD cleared", "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");

        t.disconnected();
        expect("iAP2 session ended: HUD cleared", "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");
        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(1), 11, STR, s("0.5"), 12, ENUM, i(MILES), 13, U16, i(5), 19, STR, s("Maps")));
        expect("after the session ended: maneuvers gone, a new source isn't a change", "");
        c.calls.clear();

        t.maneuver(msg(0, U16, i(7), 1, U16, i(0), 3, ENUM, i(LEFT), 4, STR, s("Comp 7 Rd"), 8, ENUM, i(0), 9, ENUM, i(0), 11, U8, i(-60)));
        t.routeGuidance(msg(0, U16, i(7), 1, ENUM, i(1), 11, STR, s("0.3"), 12, ENUM, i(MILES), 13, U16, i(0)));
        expect("a second component keeps its own state", "road [Comp 7 Rd]", "tbt 71 0 0.0 0.3 0 0 2 0 0",
                "hud f201470000" + utf16("Comp 7 Rd"), "2c 2 3 0 0 0 0 0 0 0");

        t.maneuver(msg(1, U16, i(0), 3, ENUM, i(STRAIGHT), 4, STR, s("Any Rd"), 8, ENUM, i(0), 9, ENUM, i(0)));
        t.routeGuidance(msg(1, ENUM, i(1), 11, STR, s("0.4"), 13, U16, i(0)));
        expect("no component ids: every known component gets it",
                "road [Any Rd]", "tbt 65 0 0.0 0.4 0 0 2 0 0", "hud f201410000" + utf16("Any Rd"), "2c 2 4 0 0 0 0 0 0 0",
                "road [Any Rd]", "tbt 65 0 0.0 0.4 0 0 2 0 0", "2c 2 4 0 0 0 0 0 0 0");

        t.routeGuidance(msg(0, U16, i(0), 1, ENUM, i(1), 11, STR, s("0.4"), 13, U16, i(0)));
        expect("component 0's first update: DiPO starts it empty (state 0), so the HUD is cleared",
                "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");

        check("icons: sharp left 69, slight right 66, roundabout exit 96/97, U-turn left-hand traffic 73",
                CpTurns.icon(1, 0, 0, -120, 1) == 69 && CpTurns.icon(2, 0, 0, 60, 1) == 66 && CpTurns.icon(2, 0, 1, 0, 1) == 96
                        && CpTurns.icon(31, 1, 1, 0, 1) == 96 && CpTurns.icon(4, 1, 0, 0, 1) == 73 && CpTurns.icon(47, 0, 0, -140, 1) == 69
                        && CpTurns.icon(99, 0, 0, 0, 1) == 0);
        check("lane angles: 0 straight, 90 right, -45 slight left, 180 back",
                CpTurns.laneIcon(0) == 1 && CpTurns.laneIcon(90) == 3 && CpTurns.laneIcon(-45) == 8 && CpTurns.laneIcon(180) == 5
                        && CpTurns.laneIcon(270) == 7);
        indiaTurns();
        standardTurns();
        System.exit(fail);
    }

    static boolean is(int[] got, int type, int dir, int bar) {
        return got[0] == type && got[1] == dir && got[2] == bar;
    }

    /** setting tbt_standard: the car navigation's own format near a maneuver (docs/ar-hud.md) */
    static void standardTurns() {
        c = new Cluster();
        CpTurns t = new CpTurns(new android.content.Context(), c, null);
        t.standardTurns = true;
        t.maneuver(maneuver(0, 2, 0, 0, 0, "Test Road", 900));
        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(1), 10, U32, i(900), 11, STR, s("900"), 12, ENUM, i(2), 13, U16, i(0)));
        expect("standard turns: a right turn 900 m out is the icon, bar off",
                "road [Test Road]", "tbt 67 0 0.0 900.0 0 0 0 0 0",
                "hud f201430000" + utf16("Test Road"), "2c 0 0 900 0 0 0 0 0 0");
        t.routeGuidance(msg(0, U16, i(1), 10, U32, i(300), 11, STR, s("300")));
        expect("standard turns: 300 m out it's TURN 90 with the bar at 60; the HUD list keeps the icon",
                "road [Test Road]", "tbt 13 0 90.0 300.0 0 0 0 60 0", "2c 0 0 300 0 0 0 0 0 0");
        t.routeGuidance(msg(0, U16, i(1), 10, U32, i(5), 11, STR, s("5")));
        expect("standard turns: 5 m out the bar is 10 at least",
                "road [Test Road]", "tbt 13 0 90.0 5.0 0 0 0 10 0", "2c 0 0 5 0 0 0 0 0 0");
        check("standard turns: off by default", !new CpTurns(new android.content.Context(), new Cluster(), null).standardTurns);

        // type, side, junction, exit, icon, meters -> {type, direction, bar}
        check("standard: the window is 457 m (bar 90 at its edge), the icon beyond it and with no distance",
                is(CpTurns.standard(2, 0, 0, 0, 67, 457), 13, 90, 90) && is(CpTurns.standard(2, 0, 0, 0, 67, 458), 67, 0, 0)
                        && is(CpTurns.standard(2, 0, 0, 0, 67, -1), 67, 0, 0) && is(CpTurns.standard(1, 0, 0, 0, 70, 0), 13, 270, 10));
        check("standard: straight TURN 0; exit angles in 45 degree steps (-60 315, -120 225, 120 135, 45 45)",
                is(CpTurns.standard(3, 0, 0, 0, 65, 200), 13, 0, 40) && is(CpTurns.standard(1, 0, 0, -60, 71, 200), 13, 315, 40)
                        && is(CpTurns.standard(1, 0, 0, -120, 69, 200), 13, 225, 40) && is(CpTurns.standard(2, 0, 0, 120, 68, 200), 13, 135, 40)
                        && is(CpTurns.standard(2, 0, 0, 45, 144, 200), 13, 45, 40));
        check("standard: slight and sharp turns by type (49 315, 50 45, 47 225, 48 135)",
                is(CpTurns.standard(49, 0, 0, -45, 71, 200), 13, 315, 40) && is(CpTurns.standard(50, 0, 0, 45, 66, 200), 13, 45, 40)
                        && is(CpTurns.standard(47, 0, 0, -140, 69, 200), 13, 225, 40) && is(CpTurns.standard(48, 0, 0, 140, 68, 200), 13, 135, 40));
        check("standard: U-turn 270 (90 for left-hand traffic), forks, highway exits",
                is(CpTurns.standard(4, 0, 0, 0, 72, 200), 31, 270, 40) && is(CpTurns.standard(4, 1, 0, 0, 73, 200), 31, 90, 40)
                        && is(CpTurns.standard(52, 0, 0, 0, 174, 200), 25, 270, 40) && is(CpTurns.standard(53, 0, 0, 0, 173, 200), 25, 90, 40)
                        && is(CpTurns.standard(22, 0, 0, 0, 82, 200), 18, 270, 40) && is(CpTurns.standard(23, 0, 0, 0, 81, 200), 17, 90, 40));
        check("standard: roundabout exit icons as 32 (33 left-hand traffic) with the icon's direction",
                is(CpTurns.standard(29, 0, 1, 90, 99, 200), 32, 90, 40) && is(CpTurns.standard(32, 0, 1, -45, 104, 200), 32, 225, 40)
                        && is(CpTurns.standard(1, 1, 1, -90, 105, 200), 33, 270, 40) && is(CpTurns.standard(2, 0, 1, 0, 96, 200), 32, 0, 40));
        check("standard: on-ramps and ferries keep their icon with the bar",
                is(CpTurns.standard(9, 0, 0, 0, 172, 200), 172, 0, 40) && is(CpTurns.standard(15, 0, 0, 0, 176, 200), 176, 0, 40));
        check("standard: destinations keep their icon, no bar; ARRIVED within 20 m and in state 2; NO_INFO left alone",
                is(CpTurns.standard(12, 0, 0, 0, 112, 300), 112, 0, 0) && is(CpTurns.standard(25, 0, 0, 0, 113, 100), 113, 0, 0)
                        && is(CpTurns.standard(12, 0, 0, 0, 112, 15), 3, 0, 0) && is(CpTurns.standard(12, 0, 0, 0, 3, -1), 3, 0, 0)
                        && is(CpTurns.standard(11, 0, 0, 0, 1, 200), 1, 0, 0));
        check("turnDir: an angle pointing the other way keeps the plain turn",
                CpTurns.turnDir(-90, true) == 90 && CpTurns.turnDir(90, false) == 270 && CpTurns.turnDir(-90, false) == 270);
        check("roundaboutDir: the clockwise set (+96), and -1 for 103, 106 and turn icons",
                CpTurns.roundaboutDir(195) == 90 && CpTurns.roundaboutDir(103) == -1 && CpTurns.roundaboutDir(106) == -1
                        && CpTurns.roundaboutDir(67) == -1);
    }
}
