package hmgapi.mods.wireless.dipo;

import hmgapi.mods.wireless.dipo.Watchdog.Action;

/**
 * Host test of Watchdog: the EV6 drop of 2026-11-08 19:56, Wi-Fi adb at 23:01, a session the
 * user ends, normal and slow starts, and each rule.
 */
public final class WatchdogTest {
    static int fail;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) fail = 1;
    }

    static void is(String what, Object want, Object got) {
        check(what + (want.equals(got) ? "" : " (wanted " + want + ", got " + got + ")"), want.equals(got));
    }

    static long s(double sec) {
        return Math.round(sec * 1000);
    }

    public static void main(String[] a) {
        drop1956();
        userEnds();
        normal();
        rules();
        wifi2301();
        is("backoff 2, 4, 8, 16, 30, 30 s", "2000 4000 8000 16000 30000 30000",
                Watchdog.backoff(1) + " " + Watchdog.backoff(2) + " " + Watchdog.backoff(3) + " "
                        + Watchdog.backoff(4) + " " + Watchdog.backoff(5) + " " + Watchdog.backoff(6));
        System.exit(fail);
    }

    /** the drop, times in seconds from the session start at 19:53:58.740 (HMG-API's log) */
    static void drop1956() {
        Watchdog w = new Watchdog();
        w.attached(s(-2.5));
        w.sessionStarted(0);
        is("19:53:58 session up: nothing to watch", 0L, w.deadline);
        w.sessionEnded(s(124.81), true, true);           // 19:56:03.55, the Wi-Fi channel died
        check("19:56:03 a normal end (could be the user's): nothing to watch yet", w.deadline == 0 && !w.armed);
        w.sessionStarted(s(125.24));                     // the phone's resume attempt
        w.sessionEnded(s(126.07), false, true);          // 0.8 s later, before its iAP2 channel
        check("19:56:04 a 0.8 s session before its iAP2 channel: armed (" + w.reason + ")", w.armed);
        is("armed: 12 s for the phone to open iAP2 again", s(126.07 + 12), w.deadline);
        w.attached(s(129.01));                           // 19:56:07.75 SPP up, BT_ATTACHED
        is("19:56:07 iAP2 over Bluetooth: 12 s for the session", s(141.01), w.deadline);
        w.phoneConnected(s(133.82));                     // 19:56:12.56 HFP
        is("19:56:12 HFP back: deadline kept", s(141.01), w.deadline);
        is("before the deadline: nothing", Action.NONE, w.check(s(141.0), true, false, true));
        is("19:56:21.8 (18 s after the drop) no session: attempt 1, identification again", Action.REIDENTIFY,
                w.check(s(141.01), true, false, true));
        w.attached(s(141.1));
        is("attempt 1's iAP2 up: 12 s for the session", s(153.1), w.deadline);
        is("no session again: attempt 2", Action.REIDENTIFY, w.check(s(153.1), true, false, true));
        w.attached(s(153.2));
        w.sessionStarted(s(158.0));
        is("session back: nothing to watch", 0L, w.deadline);
        is("a session up is never touched", Action.NONE, w.check(s(200), true, false, true));
        // by hand it took 123 s and airplane mode
    }

    /** Disconnect on the phone (Bluetooth kept for calls): CarPlay stays off */
    static void userEnds() {
        Watchdog w = new Watchdog();
        w.attached(0);
        w.sessionStarted(s(3));
        w.sessionEnded(s(600), true, true);
        is("the user ends a session: nothing to watch", 0L, w.deadline);
        w.attached(s(604));
        is("the phone opens iAP2 over Bluetooth again: still nothing", 0L, w.deadline);
        is("no check acts", Action.NONE, w.check(s(700), true, false, true));
        w.attached(s(5000));
        is("the next drive, the phone's iAP2 but no CarPlay (the user's choice): nothing", 0L, w.deadline);
    }

    static void normal() {
        Watchdog w = new Watchdog();
        w.attached(0);
        is("not armed: a start is the phone's business", 0L, w.deadline);
        w.sessionStarted(s(8.4));
        w.sessionEnded(s(1000), true, false);
        is("ended with the phone gone (ignition off, walked away): nothing", 0L, w.deadline);

        Watchdog slow = new Watchdog();
        slow.attached(0);
        slow.sessionStarted(s(3));
        slow.sessionEnded(s(100), true, true);
        slow.phoneConnected(s(104));                     // a Bluetooth bounce: armed
        slow.attached(s(106));
        is("armed: 12 s from iAP2 over Bluetooth", s(118), slow.deadline);
        is("a start as slow as the slowest seen (11.1 s) isn't cut off", Action.NONE, slow.check(s(117.1), true, false, true));
        slow.sessionStarted(s(117.1));
        is("it came up: no attempt", 0, slow.attempts);
    }

    static void rules() {
        Watchdog w = new Watchdog();
        w.attached(0);
        w.sessionStarted(s(3));
        w.sessionEnded(s(100), true, true);
        w.phoneConnected(s(104));
        is("armed by a bounce, no iAP2, SPP down: ask for SPP", Action.SPP_CONNECT, w.check(s(116), false, false, true));
        is("then not before 2 s + 12 s", s(116 + 2 + 12), w.deadline);
        is("during a call: put off 5 s", Action.NONE, w.check(s(130), false, true, true));
        is("put off", s(135), w.deadline);
        is("phone gone at the check: stops", Action.NONE, w.check(s(135), false, false, false));
        is("stopped", 0L, w.deadline);

        Watchdog m = new Watchdog();
        m.attached(0);
        m.sessionStarted(s(3));
        m.sessionEnded(s(100), true, true);
        m.phoneConnected(s(104));
        check("the phone's Bluetooth reconnecting 4 s after the end: armed (" + m.reason + ")", m.armed);
        int n = 0;
        Action last = Action.NONE;
        for (long t = s(104); t < s(2000) && last != Action.GIVE_UP; t += 500) {
            last = m.check(t, true, false, true);
            if (last == Action.REIDENTIFY) {
                n++;
                m.attached(t + 100);
            }
        }
        is("5 attempts, then it gives up", "5 GIVE_UP", n + " " + last);
        is("then nothing until the phone connects again", 0L, m.deadline);
        m.attached(s(3000));
        is("gave up: a re-attach alone doesn't start it over", 0L, m.deadline);
        m.phoneConnected(s(3010));
        check("the phone connected again: a fresh budget, but nothing armed", m.attempts == 0 && !m.gaveUp && m.deadline == 0);

        Watchdog b = new Watchdog();
        b.attached(0);
        b.sessionStarted(s(3));
        b.sessionEnded(s(100), true, true);
        b.phoneConnected(s(130));
        check("HFP 30 s after the end: not a bounce, nothing to watch", !b.armed && b.deadline == 0);

        Watchdog st = new Watchdog();
        st.attached(0);
        st.sessionStarted(0);
        st.sessionEnded(s(10), true, true);
        st.phoneConnected(s(12));
        st.check(s(24), true, false, true);
        st.attached(s(24.1));
        st.sessionStarted(s(27));
        st.sessionEnded(s(27 + 61), true, true);
        check("after a session that ran 60 s: the count starts over, not armed", st.attempts == 0 && !st.armed);

        Watchdog us = new Watchdog();
        us.attached(0);
        us.sessionStarted(s(3));
        us.sessionEnded(s(100), false, true);
        us.stop();
        is("USB CarPlay or the bridge off: stops", 0L, us.deadline);
    }

    /**
     * 2026-11-08 23:01: the head unit's Wi-Fi was switched on (for adb), so its hotspot went off
     * and the session ended; Bluetooth and iAP2 came back but no session could start. nlw.7
     * re-identified 5 times. Times in seconds from the session start.
     */
    static void wifi2301() {
        Watchdog w = new Watchdog();
        w.attached(0);
        w.sessionStarted(0);
        w.sessionEnded(s(20), true, true);           // 23:01:06.8
        w.attached(s(22.9));                         // 23:01:09.7, iAP2 over Bluetooth again
        is("a normal end: nothing yet", 0L, w.deadline);
        w.phoneConnected(s(25.9));                   // 23:01:12.7, HFP: armed
        check("23:01:12 HFP 5.9 s after the end: armed", w.armed);
        is("the Wi-Fi is on as a client: stands down", Action.WIFI_BUSY,
                w.check(s(37.9), true, false, true, false));
        check("no attempt, nothing more to watch", w.attempts == 0 && w.deadline == 0 && !w.armed && !w.gaveUp);
        is("later checks: nothing", Action.NONE, w.check(s(60), true, false, true, false));
        w.attached(s(300));                          // Wi-Fi off, the phone opens iAP2 again
        is("Wi-Fi off and iAP2 again: left to the phone", 0L, w.deadline);
    }
}
