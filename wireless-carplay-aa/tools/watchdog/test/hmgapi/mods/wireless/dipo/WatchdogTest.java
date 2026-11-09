package hmgapi.mods.wireless.dipo;

import hmgapi.mods.wireless.dipo.Watchdog.Action;

/**
 * Host test of Watchdog: the EV6 drop of 2026-11-08 19:56, Wi-Fi adb at 23:01, a session the
 * user ends, normal and slow starts, each rule, and a replay of all 22 session ends that day.
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

    public static void main(String[] a) throws Exception {
        drop1956();
        userEnds();
        normal();
        rules();
        wifi2301();
        replay(true);
        replay(false);
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
        w.sessionEnded(s(124.81), true, true, true);     // 19:56:03.55, its channel's sends failed 10 ms before
        check("19:56:03 an abrupt end: armed (" + w.reason + ")", w.armed);
        w.sessionStarted(s(125.24));                     // the phone's resume attempt
        w.sessionEnded(s(126.07), false, false, true);   // 0.8 s later, before its iAP2 channel
        check("19:56:04 a 0.8 s session before its iAP2 channel: armed (" + w.reason + ")", w.armed);
        is("armed: 12 s for the phone to open iAP2 again", s(126.07 + 12), w.deadline);
        w.attached(s(129.01));                           // 19:56:07.75 SPP up, BT_ATTACHED
        is("19:56:07 iAP2 over Bluetooth: 12 s for the session", s(141.01), w.deadline);
        w.phoneConnected(s(133.82));                     // 19:56:12.56 HFP
        is("19:56:12 HFP back: deadline kept", s(141.01), w.deadline);
        is("before the deadline: nothing", Action.NONE, w.check(s(141.0), true, false, true));
        is("19:56:19.75 (16 s after the drop) no session: attempt 1, identification again", Action.REIDENTIFY,
                w.check(s(141.01), true, false, true));
        w.attached(s(141.1));
        is("attempt 1's iAP2 up: 12 s for the session", s(153.1), w.deadline);
        is("no session again: attempt 2", Action.REIDENTIFY, w.check(s(153.1), true, false, true));
        w.attached(s(153.2));
        w.sessionStarted(s(158.0));
        is("session back: nothing to watch", 0L, w.deadline);
        is("a session up is never touched", Action.NONE, w.check(s(200), true, false, true));
        w.sessionEnded(s(198), true, false, true);       // 40 s later the user taps Disconnect
        check("the session it brought back, then ended normally (the user): nothing", !w.armed && w.deadline == 0);
        // by hand it took 123 s and airplane mode

        // the same drop with the phone off HFP at both ends (wireless CarPlay carries calls, so the
        // iPhone may drop HFP during a session): armed when it comes back
        Watchdog h = new Watchdog();
        h.sessionStarted(0);
        h.sessionEnded(s(124.81), true, true, false);
        h.sessionStarted(s(125.24));
        h.sessionEnded(s(126.07), false, false, false);
        check("phone off HFP at the end: waits for it, nothing scheduled", h.awaitingPhone && !h.armed && h.deadline == 0);
        h.attached(s(129.01));
        is("iAP2 before HFP: still nothing", 0L, h.deadline);
        h.phoneConnected(s(133.82));
        check("HFP back 7.8 s after: armed (" + h.reason + ")", h.armed);
        is("19:56:24.56 (21 s after the drop): attempt 1", Action.REIDENTIFY, h.check(s(145.82), true, false, true));
    }

    /** Disconnect on the phone (Bluetooth kept for calls): CarPlay stays off */
    static void userEnds() {
        Watchdog w = new Watchdog();
        w.attached(0);
        w.sessionStarted(s(3));
        w.sessionEnded(s(600), true, false, true);
        is("the user ends a session (no channel failure): nothing to watch", 0L, w.deadline);
        w.attached(s(604));
        w.phoneConnected(s(606));
        is("the phone opens iAP2 and its Bluetooth comes back, as after every end: still nothing", 0L, w.deadline);
        is("no check acts", Action.NONE, w.check(s(700), true, false, true));
        w.attached(s(5000));
        is("the next drive, the phone's iAP2 but no CarPlay (the user's choice): nothing", 0L, w.deadline);

        Watchdog off = new Watchdog();
        off.sessionStarted(0);
        off.sessionEnded(s(600), true, false, false);
        off.phoneConnected(s(606));
        is("a normal end with the phone off HFP, then back: nothing", 0L, off.deadline);
    }

    static void normal() {
        Watchdog w = new Watchdog();
        w.attached(0);
        is("not armed: a start is the phone's business", 0L, w.deadline);
        w.sessionStarted(s(8.4));
        w.sessionEnded(s(1000), true, true, false);
        is("abrupt, the phone gone (ignition off, walked away): nothing scheduled", 0L, w.deadline);
        w.phoneConnected(s(1030));
        check("back 30 s later: too late to be the same drop, nothing", !w.armed && w.deadline == 0);

        Watchdog slow = new Watchdog();
        slow.attached(0);
        slow.sessionStarted(s(3));
        slow.sessionEnded(s(100), true, true, true);
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
        w.sessionEnded(s(100), true, true, true);
        is("abrupt, no iAP2, SPP down: ask for SPP", Action.SPP_CONNECT, w.check(s(112), false, false, true));
        is("then not before 2 s + 12 s", s(112 + 2 + 12), w.deadline);
        is("during a call: put off 5 s", Action.NONE, w.check(s(126), false, true, true));
        is("put off", s(131), w.deadline);
        is("phone gone at the check: stops", Action.NONE, w.check(s(131), false, false, false));
        is("stopped", 0L, w.deadline);

        Watchdog m = new Watchdog();
        m.attached(0);
        m.sessionStarted(s(3));
        m.sessionEnded(s(100), true, true, true);
        int n = 0;
        Action last = Action.NONE;
        for (long t = s(100); t < s(2000) && last != Action.GIVE_UP; t += 500) {
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
        m.sessionStarted(s(3001));
        m.sessionEnded(s(3001.5), false, false, true);
        check("gave up: a phantom session doesn't start it over either", m.gaveUp && m.deadline == 0 && m.attempts == 5);
        m.phoneConnected(s(3010));
        check("the phone connected again: a fresh budget, but nothing armed", m.attempts == 0 && !m.gaveUp && m.deadline == 0);

        Watchdog st = new Watchdog();
        st.attached(0);
        st.sessionStarted(0);
        st.sessionEnded(s(10), true, true, true);
        st.check(s(22), true, false, true);
        st.attached(s(22.1));
        st.sessionStarted(s(27));
        st.sessionEnded(s(27 + 61), true, false, true);
        check("after a session that ran 60 s: the count starts over, not armed", st.attempts == 0 && !st.armed);

        Watchdog us = new Watchdog();
        us.attached(0);
        us.sessionStarted(s(3));
        us.sessionEnded(s(100), false, false, true);
        us.stop();
        is("USB CarPlay or the bridge off: stops", 0L, us.deadline);
        us.phoneConnected(s(104));
        is("USB end, then Bluetooth back: nothing", 0L, us.deadline);
    }

    /**
     * 2026-11-08 23:01: the head unit's Wi-Fi was switched on (for adb), so its hotspot went off
     * and the session ended cleanly (no channel failure); nlw.7 re-identified 5 times, armed by
     * HFP coming back. Times in seconds from the session start.
     */
    static void wifi2301() {
        Watchdog w = new Watchdog();
        w.attached(0);
        w.sessionStarted(0);
        w.sessionEnded(s(20), true, false, true);    // 23:01:06.8
        w.attached(s(22.9));                         // 23:01:09.7, iAP2 over Bluetooth again
        w.phoneConnected(s(25.9));                   // 23:01:12.7, HFP
        check("23:01 a clean end, HFP back 5.9 s later: not armed", !w.armed && w.deadline == 0);
        is("no check acts", Action.NONE, w.check(s(37.9), true, false, true, false));

        Watchdog b = new Watchdog();
        b.sessionStarted(0);
        b.sessionEnded(s(20), true, true, true);     // had it been abrupt
        is("armed but the Wi-Fi is on as a client: stands down", Action.WIFI_BUSY,
                b.check(s(32), true, false, true, false));
        check("no attempt, nothing more to watch", b.attempts == 0 && b.deadline == 0 && !b.armed && !b.gaveUp);
    }

    /** the session ends that arm the watchdog, of the 22 on 2026-11-08: the channel failed, or a phantom */
    static final java.util.List<String> ARMED = java.util.Arrays.asList("21:00:37.000", "12:04:58.860",
            "12:35:25.010", "13:25:09.460", "15:07:55.260", "18:02:05.780", "19:44:23.070", "19:56:03.550",
            "19:56:04.810", "20:01:43.270");

    static long ms(String hms) {
        String[] p = hms.split("[:.]");
        return ((Long.parseLong(p[0]) * 60 + Long.parseLong(p[1])) * 60 + Long.parseLong(p[2])) * 1000 + Long.parseLong(p[3]);
    }

    /**
     * Every wireless CarPlay event of 2026-11-08 through the watchdog as Bridge feeds it, with
     * the phone on HFP at each end (present) or off it. The watchdog's checks run at their
     * deadlines; an attempt's iAP2 comes up 50 ms later (as seen), the phone never answers it.
     */
    static void replay(boolean present) throws Exception {
        String v = present ? "replay, phone on HFP at the ends: " : "replay, phone off HFP at the ends: ";
        java.util.List<String> lines = java.nio.file.Files.readAllLines(java.nio.file.Paths.get("test/ev6-20261108.events"));
        Watchdog w = null;
        long lastFail = -1, dropAt = ms("19:56:03.550"), firstAttempt = -1;
        int ends = 0, attempts = 0;
        StringBuilder armed = new StringBuilder(), wrong = new StringBuilder();
        String lastEnd = "";
        for (String l : lines) {
            if (l.startsWith("#") || l.isEmpty()) continue;
            String[] f = l.split(" ");
            long t = ms(f[0]);
            if (w != null && !f[1].equals("boot")) { // the head unit was off before a boot: nothing ran
                while (w.deadline > 0 && w.deadline <= t) {
                    long at = w.deadline;
                    Action act = w.check(at, true, false, true);
                    if (act == Action.REIDENTIFY || act == Action.SPP_CONNECT) {
                        attempts++;
                        if (!lastEnd.startsWith("19:56")) wrong.append(" ").append(act).append(" after ").append(lastEnd);
                        if (firstAttempt < 0) firstAttempt = at;
                        w.attached(at + 50);
                    }
                }
            }
            switch (f[1]) {
                case "boot": w = new Watchdog(); lastFail = -1; break;
                case "attach": w.attached(t); break;
                case "start": w.sessionStarted(t); lastFail = -1; break;
                case "fail": lastFail = t; break;
                case "hfp": w.phoneConnected(t); break;
                case "end":
                    ends++;
                    boolean abrupt = lastFail >= 0 && t - lastFail <= Watchdog.ABRUPT_MS;
                    w.sessionEnded(t, f[2].equals("bridged"), abrupt, present);
                    if (w.armed || w.awaitingPhone) armed.append(armed.length() > 0 ? " " : "").append(f[0]);
                    lastEnd = f[0];
                    break;
                default: throw new IllegalStateException(l);
            }
        }
        is(v + "22 session ends", 22, ends);
        StringBuilder want = new StringBuilder();
        for (String e : ARMED) want.append(want.length() > 0 ? " " : "").append(e);
        is(v + "armed on the 9 abrupt ends and the phantom only", want.toString(), armed.toString());
        is(v + "no attempt after any other end", "", wrong.toString());
        check(v + "the 19:56 drop: first attempt " + (firstAttempt - dropAt) / 1000.0 + " s after it (by hand: 123 s)",
                firstAttempt > dropAt && firstAttempt - dropAt <= 25000);
        check(v + attempts + " attempts in all, at most 5", attempts >= 1 && attempts <= 5);
    }
}
