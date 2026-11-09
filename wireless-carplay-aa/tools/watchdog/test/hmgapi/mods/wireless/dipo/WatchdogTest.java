package hmgapi.mods.wireless.dipo;

import hmgapi.mods.wireless.dipo.Watchdog.Action;

/** Host test of Watchdog: the EV6 drop of 2026-11-08 19:56, Wi-Fi adb at 23:01, normal starts, and each rule. */
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
        w.attached(s(-2.5), true);
        w.sessionStarted(0);
        is("19:53:58 session up: nothing to watch", 0L, w.deadline);
        w.sessionEnded(s(124.81), true, true);           // 19:56:03.55, the Wi-Fi channel died
        check("19:56:03 ended with the phone still connected: waits for it to open iAP2 again", w.deadline == s(124.81 + 12) && !w.armed);
        w.sessionStarted(s(125.24));                     // the phone's resume attempt
        w.sessionEnded(s(126.07), false, true);          // 0.8 s later, before its iAP2 channel
        check("19:56:04 a 0.8 s session before its iAP2 channel: armed (" + w.reason + ")", w.armed);
        w.attached(s(129.01), true);                     // 19:56:07.75 SPP up, BT_ATTACHED
        is("19:56:07 iAP2 over Bluetooth: armed, 6 s for the session", s(135.01), w.deadline);
        w.phoneConnected(s(133.82));                     // 19:56:12.56 HFP
        is("19:56:12 HFP back: deadline kept", s(135.01), w.deadline);
        is("before the deadline: nothing", Action.NONE, w.check(s(134.9), true, false, true));
        is("19:56:13.8 no session: attempt 1, identification again", Action.REIDENTIFY, w.check(s(135.01), true, false, true));
        w.attached(s(135.1), true);
        is("attempt 1's iAP2 up: 6 s for the session", s(141.1), w.deadline);
        is("no session again: attempt 2", Action.REIDENTIFY, w.check(s(141.1), true, false, true));
        w.attached(s(141.2), true);
        is("attempt 2's iAP2 up: not before the 4 s backoff... but 6 s for the session", s(147.2), w.deadline);
        is("attempt 3", Action.REIDENTIFY, w.check(s(147.2), true, false, true));
        w.attached(s(147.3), true);
        is("after attempt 3 the backoff (8 s) is longer than the 6 s wait", s(147.2 + 8), w.deadline);
        w.sessionStarted(s(150.0));
        is("session back: nothing to watch", 0L, w.deadline);
        is("a session up is never touched", Action.NONE, w.check(s(200), true, false, true));
        // the stock recovery took 110 s and airplane mode; this one would have started at 135 s
    }

    static void normal() {
        Watchdog w = new Watchdog();
        w.attached(0, true);
        is("known phone: 20 s for the session", s(20), w.deadline);
        w.sessionStarted(s(8.4));
        is("started in 8.4 s (the slowest seen): nothing done", 0L, w.deadline);
        w.sessionEnded(s(1000), true, false);
        is("ended with the phone gone (ignition off, walked away): nothing", 0L, w.deadline);

        Watchdog u = new Watchdog();
        u.attached(0, false);
        is("a phone that never had a session here (setup, its prompt): left alone", 0L, u.deadline);

        Watchdog r = new Watchdog();
        r.attached(0, true);
        r.sessionStarted(s(3));
        r.sessionEnded(s(400), true, true);
        r.attached(s(403), true);
        r.sessionStarted(s(406));
        is("the phone came back by itself in 3 s: nothing done", 0, r.attempts);
    }

    static void rules() {
        Watchdog w = new Watchdog();
        w.attached(0, true);
        w.sessionStarted(s(3));
        w.sessionEnded(s(100), true, true);
        is("no iAP2 12 s after the end, SPP down: ask for SPP", Action.SPP_CONNECT, w.check(s(112), false, false, true));
        is("then not before 2 s + 12 s", s(112 + 2 + 12), w.deadline);
        is("during a call: put off 5 s", Action.NONE, w.check(s(126), false, true, true));
        is("put off", s(131), w.deadline);
        is("phone gone at the check: stops", Action.NONE, w.check(s(131), false, false, false));
        is("stopped", 0L, w.deadline);

        Watchdog c = new Watchdog();
        c.attached(0, true);
        c.sessionStarted(s(3));
        c.sessionEnded(s(100), true, true);
        int n = 0;
        Action last = Action.NONE;
        for (long t = s(100); t < s(2000) && last != Action.GIVE_UP; t += 500) {
            last = c.check(t, true, false, true);
            if (last == Action.REIDENTIFY) {
                n++;
                c.attached(t + 100, true);
            }
        }
        is("not armed: 3 attempts, then it gives up", "3 GIVE_UP", n + " " + last);
        is("then nothing until the phone connects again", 0L, c.deadline);
        c.attached(s(3000), true);
        is("gave up: a re-attach alone doesn't start it over", 0L, c.deadline);
        c.phoneConnected(s(3010));
        c.attached(s(3015), true);
        is("the phone connected again: a fresh budget", "0 " + s(3035), c.attempts + " " + c.deadline);

        Watchdog m = new Watchdog();
        m.attached(0, true);
        m.sessionStarted(s(3));
        m.sessionEnded(s(100), true, true);
        m.phoneConnected(s(104));
        check("the phone's Bluetooth reconnecting 4 s after the end: armed (" + m.reason + ")", m.armed);
        n = 0;
        last = Action.NONE;
        for (long t = s(104); t < s(2000) && last != Action.GIVE_UP; t += 500) {
            last = m.check(t, true, false, true);
            if (last == Action.REIDENTIFY) {
                n++;
                m.attached(t + 100, true);
            }
        }
        is("armed: 5 attempts, then it gives up", "5 GIVE_UP", n + " " + last);

        Watchdog b = new Watchdog();
        b.attached(0, true);
        b.sessionStarted(s(3));
        b.sessionEnded(s(100), true, true);
        b.phoneConnected(s(130));
        check("HFP 30 s after the end: not a bounce", !b.armed);

        Watchdog st = new Watchdog();
        st.attached(0, true);
        st.sessionStarted(0);
        st.sessionEnded(s(10), true, true);
        st.check(s(22), true, false, true);
        st.attached(s(22.1), true);
        st.sessionStarted(s(25));
        st.sessionEnded(s(25 + 61), true, true);
        is("after a session that ran 60 s: the count starts over", 0, st.attempts);

        Watchdog us = new Watchdog();
        us.attached(0, true);
        us.stop();
        is("USB CarPlay or the bridge off: stops", 0L, us.deadline);
    }

    /**
     * 2026-11-08 23:01: the head unit's Wi-Fi joined a network (for adb), so its hotspot went
     * off and the session ended; Bluetooth and iAP2 came back but no session could start. nlw.7
     * re-identified 5 times. Times in seconds from the session start.
     */
    static void wifi2301() {
        Watchdog w = new Watchdog();
        w.attached(0, true);
        w.sessionStarted(0);
        w.sessionEnded(s(20), true, true);           // 23:01:06.8
        w.attached(s(22.9), true);                   // 23:01:09.7, iAP2 over Bluetooth again
        w.phoneConnected(s(25.9));                   // 23:01:12.7, HFP: armed
        check("23:01:12 HFP 5.9 s after the end: armed", w.armed);
        is("23:01:18 the Wi-Fi is joined to a network: stands down", Action.WIFI_BUSY,
                w.check(s(31.9), true, false, true, false));
        check("no attempt, nothing more to watch", w.attempts == 0 && w.deadline == 0 && !w.armed && !w.gaveUp);
        is("later checks: nothing", Action.NONE, w.check(s(60), true, false, true, false));
        w.attached(s(300), true);                    // Wi-Fi off, the phone opens iAP2 again
        is("Wi-Fi off and iAP2 again: watched as normal", s(320), w.deadline);
        is("no session 20 s later: attempt 1", Action.REIDENTIFY, w.check(s(320), true, false, true, true));
    }
}
