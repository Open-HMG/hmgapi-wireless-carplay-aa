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
        System.exit(fail);
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) fail = 1;
    }
}
