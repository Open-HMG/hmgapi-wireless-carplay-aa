package hmgapi.mods.wireless.dipo;

/**
 * Host test of Keyframes: a wired and a wireless CarPlay session, with decoder
 * failures, screen timeouts and the periodic tick, on a made-up clock.
 */
public final class KeyframesTest {
    static int fail;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) fail = 1;
    }

    public static void main(String[] a) {
        Keyframes k = new Keyframes(true);

        // before onSessionStarted is seen (a session already up): failures still count
        check("failure before a session: key frame", k.decodeFailure(Keyframes.NO_OUTPUT, 1000));
        check("no periodic tick without a session", !k.ticking() && !k.tick(60000));

        // wired session: reactive only
        k.sessionStarted(0);
        check("wired: no periodic tick", !k.ticking() && !k.tick(100000));
        check("wired: no output frame -> key frame", k.decodeFailure(Keyframes.NO_OUTPUT, 100000));
        check("wired: another within 2 s -> none", !k.decodeFailure(Keyframes.NO_OUTPUT, 101999));
        check("wired: failures counted while rate-limited", k.failures == 2);
        check("wired: 2 s later -> key frame", k.decodeFailure(Keyframes.SEQUENCE_HEADER, 102000));
        check("type 0 (black-screen count reset) isn't a failure", !k.decodeFailure(0, 200000));
        check("unknown type isn't a failure", !k.decodeFailure(7, 200000));
        check("black screen -> key frame", k.decodeFailure(Keyframes.BLACK_SCREEN, 200000));
        check("screen timeout -> key frame", k.screenTimeout(203000));
        check("screen timeout within 2 s -> none", !k.screenTimeout(204000));
        k.sessionEnded();

        // wireless session: reactive plus a key frame at least every 10 s
        k.sessionStarted(1);
        check("wireless: ticking", k.ticking());
        check("wireless: first tick due after a period", k.nextTick(300000) == Keyframes.PERIOD_MS);
        check("wireless: tick -> key frame", k.tick(310000));
        check("wireless: tick again 5 s later -> none", !k.tick(315000));
        check("wireless: next tick 5 s after that", k.nextTick(315000) == 5000);
        check("wireless: failure at 316 s -> key frame", k.decodeFailure(Keyframes.NO_OUTPUT, 316000));
        check("wireless: tick at 320 s -> none (one 4 s ago)", !k.tick(320000));
        check("wireless: tick at 326 s -> key frame", k.tick(326000));
        check("next tick never sooner than the minimum gap", k.nextTick(335500) == Keyframes.MIN_GAP_MS);
        k.sessionEnded();
        check("session ended: no tick", !k.ticking() && !k.tick(400000));

        // periodic off
        Keyframes r = new Keyframes(false);
        r.sessionStarted(1);
        check("periodic off: wireless doesn't tick", !r.ticking() && !r.tick(50000));
        check("periodic off: failures still recover", r.decodeFailure(Keyframes.NO_OUTPUT, 50000));

        System.exit(fail);
    }
}
