package hmgapi.mods.wireless;

import android.os.Handler;

import java.util.List;

import hmgapi.hook.Car;
import hmgapi.hook.Cluster;

/**
 * Host test of AaTurns: a drive through the AA library's callbacks, checking every
 * cluster call against what the turn-by-turn build's GpmNaviTBTManager sends for the
 * same input (worked out from its code).
 */
public final class AaTurnsTest {
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

    static String utf16(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) b.append(String.format("%02x%02x", ch & 0xff, ch >> 8));
        String r = b.toString();
        while (r.endsWith("00")) r = r.substring(0, r.length() - 2);
        return r;
    }

    public static void main(String[] a) {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        Car.unit = Car.DISTANCE_MILES;
        c = new Cluster();
        Handler h = new Handler();
        AaTurns t = new AaTurns(new android.content.Context(), c, h);
        final int RIGHT = 2, LEFT = 1, TURN = 4, MILES = 4, MILES_P1 = 5, METERS = 1, FEET = 6;

        t.nextTurn("Main St", RIGHT, TURN, -1, 0);
        expect("no route yet: road name only", "road [Main St]");
        t.status(1);
        t.nextTurn("Main St", RIGHT, TURN, -1, 0);
        expect("right turn: turn, HUD list, HUD distance", "road [Main St]", "tbt 67 65535 90.0 0.0 0 0 0 32 0",
                "hud f201430000" + utf16("Main St"), "2c 0 0 0 0 0 0 0 0 0");
        t.nextTurnDistance(320, 30, 200, MILES_P1);
        expect("0.2 mi: HUD list unchanged, not resent", "tbt 67 65535 90.0 0.2 0 0 2 32 0", "2c 2 2 0 0 0 0 0 0 0");
        t.nextTurnDistance(100, 10, 100, METERS);
        expect("100 m with miles set: feet", "tbt 67 65535 90.0 328.1 0 0 3 32 0", "2c 3 0 328 0 0 0 0 0 0");
        Car.unit = Car.DISTANCE_KM;
        t.nextTurnDistance(1500, 60, 932, MILES);
        expect("0.9 mi with km set: 1.5 km", "tbt 67 65535 90.0 1.5 0 0 1 32 0", "2c 1 5 1 0 0 0 0 0 0");
        Car.unit = Car.DISTANCE_MILES;

        String lanes = "02" + "01aabbcc" + "02000000";
        t.nextTurn(lanes, 0, 34, 0, 0);
        expect("lanes (count, a free byte, lane types; arrows from byte 19)", "lanes f102000102" + "0000000000000000000000000000" + "aabbcc");
        t.nextTurn(lanes, 0, 34, 0, 0);
        expect("same lanes: not resent");
        t.nextTurn("02zz", 0, 34, 0, 0);
        expect("bad lane string: nothing sent");

        t.nextTurn("Oak Ave", LEFT, 64, 270, TURN | 255 << 8);
        t.nextTurnDistance(800, 0, 500, MILES | 0x200);
        t.nextTurnDistance(90, 9, 295000, FEET);
        expect("distance between the turns ahead and their end: HUD list gets the next turn",
                "tbt 67 65535 90.0 295.0 0 0 3 32 0", "hud f202434600" + utf16("Main St"), "2c 3 0 295 0 0 0 0 0 0");
        t.nextTurn(null, 0, 67, 0, 1);
        expect("end of the turns ahead: the cluster's list", "list {1 70 270 [Oak Ave] [] " + (800 / 1609f) + " 2}");
        t.nextTurnDistance(80, 8, 262000, FEET);
        expect("next distance: HUD list already has it, not resent", "tbt 67 65535 90.0 262.0 0 0 3 32 0", "2c 3 0 262 0 0 0 0 0 0");

        t.nextTurnDistance(16093, 1800000000, 0, 0x100 | MILES_P1 | 0x800 | 1 << 12);
        expect("trip: 10.0 mi, arrival 8:00, point 1", "sub 10.0 0 2 8 0 1 1 0.0 -1 1");
        check("unit check scheduled", h.delayed != null);
        h.delayed.run();
        expect("tick, nothing changed: nothing sent");
        Car.unit = Car.DISTANCE_KM;
        h.delayed.run();
        expect("owner switched to km: resent", "sub 16.0 0 1 8 0 1 1 0.0 -1 1");
        Car.unit = Car.DISTANCE_MILES;

        t.status(2);
        expect("guidance ends: everything cleared", "dest 0 []", "tbt 0 0 0.0 0.0 -1 0 0 0 -1",
                "sub 0.0 -1 0 0 0 0 0 0.0 -1 0", "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");
        check("unit check stopped", h.delayed == null);

        t.status(1);
        t.nextTurn("Home", 0, 32, 0, 2);
        expect("destination name", "dest 0 [Home]");
        t.nextTurn("Home", 0, 32, 0, 3);
        expect("same destination: not resent");
        check("destination point kept", t.estPoint == 3);

        AaTurns d = new AaTurns(new android.content.Context(), c, new Handler());
        d.status(1);
        d.nextTurn("Start Rd", 0, 1, 0, 0);
        expect("depart (first): road as destination, no distance yet", "dest 0 [Start Rd]", "tbt 2 65535 0.0 -1.0 0 0 0 254 -2");
        d.nextTurn("Circle", RIGHT, 13, 100, 0);
        expect("roundabout, exit at 100 degrees", "road [Circle]", "tbt 99 65535 90.0 -1.0 0 0 0 254 -2", "hud f201630000" + utf16("Circle"));
        d.nextTurn("Old Rd", 0, 11, 0, 0);
        expect("roundabout enter: not for the cluster");
        d.stop();
        d.end();
        expect("connection ends during a route: cleared", "dest 0 []", "tbt 0 0 0.0 0.0 -1 0 0 0 -1",
                "sub 0.0 -1 0 0 0 0 0 0.0 -1 0", "2c 0 0 0 0 0 0 0 0 0", "lanes f1", "hud f2", "hud f2");
        d.nextTurn("S 3 0a0102", 0, 33, 0, 0);
        expect("navigation message: nothing to the cluster");
        check("... passed on to mods that asked (projection events)", "S 3 0a0102".equals(hmgapi.hook.ProjectionHooks.last));

        // tbt_standard: the car navigation's own format near a maneuver (its cluster code)
        AaTurns s = new AaTurns(new android.content.Context(), c, new Handler());
        s.standardTurns = true;
        s.status(1);
        s.nextTurn("Elm St", LEFT, TURN, -1, 0);
        c.calls.clear();
        s.nextTurnDistance(1200, 90, 700, MILES_P1);
        expect("standard: 0.7 mi out: the icon (70 left), bar off", "tbt 70 65535 270.0 0.7 0 0 2 0 0", "2c 2 7 0 0 0 0 0 0 0");
        s.nextTurnDistance(440, 40, 273, MILES_P1);
        expect("standard: 440 m: TURN (13) 270, bar 90; HUD keeps its icon", "tbt 13 0 270.0 0.273 0 0 2 90 0", "2c 2 3 0 0 0 0 0 0 0");
        s.nextTurnDistance(274, 25, 900000, FEET);
        expect("standard: 900 ft (274 m): bar 50", "tbt 13 0 270.0 900.0 0 0 3 50 0", "2c 3 0 900 0 0 0 0 0 0");
        s.nextTurnDistance(9, 1, 29529, FEET);
        expect("standard: 29 ft: bar 10 (at least 1)", "tbt 13 0 270.0 29.529 0 0 3 10 0", "2c 3 0 29 0 0 0 0 0 0");
        s.nextTurn("Hwy 9", 0, 14, -1, 0);
        expect("standard: straight near: TURN 0", "road [Hwy 9]", "tbt 13 0 0.0 29.529 0 0 3 10 0",
                "hud f201410000" + utf16("Hwy 9"), "2c 3 0 29 0 0 0 0 0 0");

        final int SLIGHT = 3, SHARP = 5, UTURN = 6, ON_RAMP = 7, OFF_RAMP = 8, FORK = 9, MERGE = 10, RB_EXIT = 12, RB_BOTH = 13, DEST = 19, FERRY = 16;
        stdCase("right turn", TURN, RIGHT, 67, 90, 100, "13 90 20");
        stdCase("slight right: 45 (the car's), not the icon's 30", SLIGHT, RIGHT, 66, 30, 100, "13 45 20");
        stdCase("slight left: 315", SLIGHT, LEFT, 71, 330, 100, "13 315 20");
        stdCase("sharp right: 135", SHARP, RIGHT, 68, 135, 100, "13 135 20");
        stdCase("sharp left: 225", SHARP, LEFT, 69, 225, 100, "13 225 20");
        stdCase("U-turn (left): U_TURN 270", UTURN, LEFT, 72, 270, 100, "31 270 20");
        stdCase("fork right: FORK_2 90", FORK, RIGHT, 173, 0, 100, "25 90 20");
        stdCase("exit right: EXIT_RIGHT 90", OFF_RAMP, RIGHT, 81, 30, 300, "17 90 60");
        stdCase("exit left: EXIT_LEFT 270", OFF_RAMP, LEFT, 82, 330, 300, "18 270 60");
        stdCase("merge: TURN 0", MERGE, 0, 65, 0, 100, "13 0 20");
        stdCase("roundabout, exit at 90: roundabout (32), the exit's direction", RB_BOTH, RIGHT, 99, 90, 200, "32 90 40");
        stdCase("roundabout, left-hand traffic: 33", RB_EXIT, LEFT, 33, 0, 200, "33 0 40");
        stdCase("on-ramp: the icon, with the bar", ON_RAMP, RIGHT, 173, 30, 200, "173 30 40");
        stdCase("ferry: the icon, with the bar", FERRY, 0, 176, 0, 200, "176 0 40");
        stdCase("turn without a side: the icon, with the bar", TURN, 0, 64, 0, 200, "64 0 40");
        stdCase("destination on the right: 113, no bar", DEST, RIGHT, 3, 0, 100, "113 0 0");
        stdCase("destination on the left: 114", DEST, LEFT, 3, 0, 100, "114 0 0");
        stdCase("destination ahead: 112, also far out", DEST, 0, 3, 0, 5000, "112 0 0");
        stdCase("destination within 20 m: ARRIVED (3)", DEST, RIGHT, 3, 0, 19, "3 0 0");
        stdCase("457 m: in, bar 90", TURN, RIGHT, 67, 90, 457, "13 90 90");
        stdCase("458 m: out, the icon, bar off", TURN, RIGHT, 67, 90, 458, "67 90 0");
        stdCase("no distance yet: the icon, bar off", TURN, RIGHT, 67, 90, -1, "67 90 0");
        System.exit(fail);
    }

    static void stdCase(String what, int ev, int side, int icon, int iconDir, int m, String want) {
        int[] r = AaTurns.standard(ev, side, icon, iconDir, m);
        String got = r[0] + " " + r[1] + " " + r[2];
        check("standard: " + what + (got.equals(want) ? "" : " (got " + got + ", wanted " + want + ")"), got.equals(want));
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) fail = 1;
    }
}
