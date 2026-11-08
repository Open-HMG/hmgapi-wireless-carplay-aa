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
        // the departure's icon 1 as #15 sends it (tbt_no_icon "no_info"); the default "clear" is below
        CpTurns t = new CpTurns(new android.content.Context(), c, null);
        t.noIcon = "no_info";
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
        expect("India route stop still clears HUD (and the cluster turn and trip, as any state 0)",
                "tbt 0 0 0.0 0.0 -1 0 0 0 -1", "sub 0.0 -1 0 0 0 0 0 0.0 -1 0", "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");

        // "clear": a departure takes the last turn off the cluster and HUD, never icon 1
        // (a spinner that outlives the session on a US EV6's cluster)
        CpTurns d = new CpTurns(new android.content.Context(), c, null);
        d.noIcon = "clear";
        d.maneuver(maneuver(0, 2, 1, 0, 90, "Test Road", 100));
        d.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(1), 11, STR, s("100"), 12, ENUM, i(2), 13, U16, i(0)));
        c.calls.clear();
        d.maneuver(msg(0, U16, i(1), 1, U16, i(0), 3, ENUM, i(11)));
        d.routeGuidance(msg(0, U16, i(1)));
        expect("departure, clear: turn, trip and HUD cleared, no icon 1",
                "road [Test Road]", "tbt 0 0 0.0 0.0 -1 0 0 0 -1", "sub 0.0 -1 0 0 0 0 0 0.0 -1 0", "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");
        d.routeGuidance(msg(0, U16, i(1)));
        expect("departure again: nothing cleared twice", "road [Test Road]");

        // default "straight": a departure shows a straight arrow with its distance, never icon 1
        CpTurns e = new CpTurns(new android.content.Context(), c, null);
        e.maneuver(maneuver(0, 11, 0, 0, 0, "Start Rd", 200));
        c.calls.clear();
        e.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(1), 11, STR, s("0.2"), 12, ENUM, i(1), 13, U16, i(0)));
        expect("departure, default straight: 65 with the distance",
                "road [Start Rd]", "tbt 65 0 0.0 0.2 0 0 2 0 0", "hud f201410000" + utf16("Start Rd"), "2c 2 2 0 0 0 0 0 0 0");
    }

    /** the maneuvers a US EV6's iPhone sent on 2026-11-08 that had no arrow (Rt 2 W, I-95 S, rotaries) */
    static void driveTurns() {
        int[][] seen = {   // type, side, junction, exit, icon
            {8, 0, 0, 0, 86}, {13, 0, 0, 0, 174}, {14, 0, 0, 0, 173},
            {28, 0, 1, 61, 97}, {29, 0, 1, -54, 107}, {29, 0, 1, -61, 107}, {29, 0, 1, -8, 96}, {29, 0, 1, 16, 96},
            {29, 0, 1, 2, 96}, {29, 0, 1, 8, 96}, {30, 0, 1, -127, 104},
        };
        for (int[] v : seen) {
            int got = CpTurns.icon(v[0], v[1], v[2], v[3], 1);
            if (got == 0) got = CpTurns.fallback(v[0], v[1], v[2], v[3]);
            check("11-08 drive: type " + v[0] + " junction " + v[2] + " exit " + v[3] + " -> " + v[4] + " (got " + got + ")", got == v[4]);
            check("11-08 drive, turns ahead: type " + v[0] + " exit " + v[3] + " -> " + v[4], CpTurns.aheadIcon(v[0], v[1], v[2], v[3]) == v[4]);
        }
        check("highway exits keep their side, ramps and highway changes their forks",
                CpTurns.fallback(22, 0, 0, 30) == 82 && CpTurns.fallback(23, 0, 0, 30) == 81 && CpTurns.fallback(9, 0, 0, 20) == 172
                        && CpTurns.fallback(52, 0, 0, -20) == 174 && CpTurns.fallback(53, 0, 0, 20) == 173 && CpTurns.fallback(51, 0, 0, 0) == 172);
        check("roundabout exits: one icon per 45 degrees, never 103 or 106",
                CpTurns.roundabout(0) == 96 && CpTurns.roundabout(45) == 97 && CpTurns.roundabout(90) == 99 && CpTurns.roundabout(135) == 100
                        && CpTurns.roundabout(180) == 102 && CpTurns.roundabout(-135) == 104 && CpTurns.roundabout(-90) == 105
                        && CpTurns.roundabout(-45) == 107 && CpTurns.roundabout(-180) == 102 && CpTurns.roundabout(22) == 96 && CpTurns.roundabout(23) == 97);
        boolean never64 = true;
        for (int type = 0; type <= 60; type++)
            for (int j = 0; j <= 1; j++)
                for (int x = -180; x <= 180; x += 15) {
                    int a = CpTurns.aheadIcon(type, 0, j, x), f = CpTurns.fallback(type, 0, j, x);
                    if (a == 64 || a < 64 || f == 64 || f == 103 || f == 106 || a == 103 || a == 106) never64 = false;
                }
        check("never 64 (the spinner), 103 or 106 anywhere, and the turns ahead always get an arrow", never64);
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
        expect("maneuver update without its exit angle (DiPO resets it): the same maneuver keeps its arrow, distance updated",
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
        expect("source change (Maps to Waze): cluster turn and HUD cleared; the reset state is 0 until the phone resends it, so the HUD again",
                "tbt 0 0 0.0 0.0 -1 0 0 0 -1", "sub 0.0 -1 0 0 0 0 0 0.0 -1 0",
                "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2", "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");

        t.maneuver(maneuver(5, ARRIVE, 0, 0, 0, "Home Rd", 100));
        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(6), 4, STR, s("Home"), 11, STR, s("300"), 12, ENUM, i(FEET), 13, U16, i(5)));
        expect("state 6 with destination, arrival maneuver in 300 ft",
                "dest 0 [Home]", "road [Home Rd]", "tbt 112 0 0.0 300.0 0 0 3 0 0", "hud f201700000" + utf16("Home Rd"),
                "2c 3 0 300 0 0 0 0 0 0");

        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(2)));
        expect("arrived (state 2): icon 3, no HUD list for it, so the last turn's list is blanked",
                "road [Home Rd]", "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2", "tbt 3 0 0.0 300.0 0 0 3 0 0", "2c 3 0 300 0 0 0 0 0 0");

        t.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(0)));
        expect("state 0: cluster turn, trip and HUD cleared", "tbt 0 0 0.0 0.0 -1 0 0 0 -1", "sub 0.0 -1 0 0 0 0 0 0.0 -1 0",
                "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");

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
        expect("component 0's first update: DiPO starts it empty (state 0), so the cluster turn and HUD are cleared",
                "tbt 0 0 0.0 0.0 -1 0 0 0 -1", "sub 0.0 -1 0 0 0 0 0 0.0 -1 0", "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");

        // a fresh route: maneuvers the icon table doesn't have never leave the last turn's arrow up
        CpTurns g = new CpTurns(new android.content.Context(), c, null);
        g.noIcon = "clear";
        g.maneuver(maneuver(0, RIGHT, 0, 0, 90, "First St", 100));
        g.maneuver(maneuver(1, LEFT, 0, 1, -45, "Circle", 400));
        g.maneuver(maneuver(2, 99, 0, 0, 0, "Ramp", 900));
        g.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(1), 11, STR, s("0.1"), 12, ENUM, i(MILES), 13, U16, i(0)));
        c.calls.clear();
        g.routeGuidance(msg(0, U16, i(1), 11, STR, s("0.3"), 13, U16, i(1)));
        expect("next maneuver with no icon in the table (left, roundabout, -45): the slight-left exit (107), not the last right",
                "road [Circle]", "tbt 107 0 0.0 0.3 0 0 2 0 0", "hud f2016b0000" + utf16("Circle"), "2c 2 3 0 0 0 0 0 0 0");
        g.routeGuidance(msg(0, U16, i(1), 11, STR, s("0.5"), 13, U16, i(2)));
        expect("clear: a maneuver with no icon at all (an unknown type 99): ours cleared off the cluster, HUD blanked",
                "road [Ramp]", "tbt 0 0 0.0 0.0 -1 0 0 0 -1", "sub 0.0 -1 0 0 0 0 0 0.0 -1 0", "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");
        g.routeGuidance(msg(0, U16, i(1), 11, STR, s("0.4"), 13, U16, i(2)));
        expect("still no arrow: nothing cleared again", "road [Ramp]");
        g.routeGuidance(msg(0, U16, i(1), 11, STR, s("0.2"), 13, U16, i(0)));
        expect("a turn with an arrow after it: shown again", "road [First St]", "tbt 67 0 0.0 0.2 0 0 2 0 0", "hud f201430000" + utf16("First St"), "2c 2 2 0 0 0 0 0 0 0");
        g.disconnected();
        expect("session ended with a turn up: cluster turn, trip and HUD cleared",
                "tbt 0 0 0.0 0.0 -1 0 0 0 -1", "sub 0.0 -1 0 0 0 0 0 0.0 -1 0", "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");
        g.stop();
        expect("stopped with nothing of ours up: the car's own turn is left alone (HUD only)",
                "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");

        // the other tbt_no_icon choices
        for (String mode : new String[] {"skip", "no_info", "straight"}) {
            CpTurns h = new CpTurns(new android.content.Context(), c, null);
            if (!mode.equals("straight")) h.noIcon = mode;
            h.maneuver(maneuver(0, RIGHT, 0, 0, 90, "First St", 100));
            h.maneuver(maneuver(1, 99, 0, 0, 0, "Ramp", 900));
            h.routeGuidance(msg(0, U16, i(1), 1, ENUM, i(1), 11, STR, s("0.1"), 12, ENUM, i(MILES), 13, U16, i(0)));
            c.calls.clear();
            h.routeGuidance(msg(0, U16, i(1), 11, STR, s("0.5"), 13, U16, i(1)));
            if (mode.equals("skip")) expect("skip (2.1.0): nothing sent, the last arrow stays", "road [Ramp]");
            else if (mode.equals("no_info")) expect("no_info (nlw.1): icon 1, HUD list blanked once",
                    "road [Ramp]", "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2", "tbt 1 0 0.0 0.5 0 0 2 0 0", "2c 2 5 0 0 0 0 0 0 0");
            else expect("straight (default): a straight arrow, the distance stays",
                    "road [Ramp]", "tbt 65 0 0.0 0.5 0 0 2 0 0", "hud f201410000" + utf16("Ramp"), "2c 2 5 0 0 0 0 0 0 0");
        }

        check("icons: sharp left 69, slight right 66, roundabout exit 96/97, U-turn left-hand traffic 73",
                CpTurns.icon(1, 0, 0, -120, 1) == 69 && CpTurns.icon(2, 0, 0, 60, 1) == 66 && CpTurns.icon(2, 0, 1, 0, 1) == 96
                        && CpTurns.icon(31, 1, 1, 0, 1) == 96 && CpTurns.icon(4, 1, 0, 0, 1) == 73 && CpTurns.icon(47, 0, 0, -140, 1) == 69
                        && CpTurns.icon(99, 0, 0, 0, 1) == 0);
        check("lane angles: 0 straight, 90 right, -45 slight left, 180 back",
                CpTurns.laneIcon(0) == 1 && CpTurns.laneIcon(90) == 3 && CpTurns.laneIcon(-45) == 8 && CpTurns.laneIcon(180) == 5
                        && CpTurns.laneIcon(270) == 7);
        indiaTurns();
        driveTurns();
        System.exit(fail);
    }
}
