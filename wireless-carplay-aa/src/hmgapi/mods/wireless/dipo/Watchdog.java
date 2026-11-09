package hmgapi.mods.wireless.dipo;

/**
 * When wireless CarPlay should be started again by the car: the policy, with no Android in
 * it (Bridge feeds it events and carries out what check() says), so it can be tested on a
 * host. All times are milliseconds on one monotonic clock.
 *
 * Why the car acts: in Apple's model the head unit decides whether to reconnect after a
 * session ends (WWDC 2017, session 717): never after the user ends it, but after an
 * unexpected loss, first through the CarPlay control channel, then over Bluetooth. This class
 * covers the Bluetooth step (iAP2 identification again, which hands the phone the Wi-Fi
 * details and has it start the session). Seen on a US EV6 (2026-11-08 19:56): the phone's
 * Wi-Fi and Bluetooth hiccuped, a resume attempt ended after 0.8 s, Bluetooth and iAP2 came
 * back in 4 s, and the phone then never started CarPlay until airplane mode was toggled 110 s
 * later (after which the session was up in 3 s).
 *
 * Rules, after open receivers that do this (DiPlay's startup retry budget and first-TCP
 * watchdog, playport's supervisor, WirelessAndroidAutoDongle, aa-proxy-rs):
 *  - Only after an abnormal end, never a normal one, so a session the user ended (Disconnect
 *    on the phone, which keeps Bluetooth for calls), or iAP2 over Bluetooth with no session,
 *    is left alone. Abnormal ("armed"), decided again at each end: a session that ends within
 *    PHANTOM_MS, before its iAP2 channel came up, or an abrupt end, where the CarPlay
 *    channel's sends failed as it ended (ABRUPT_MS). On the EV6 (2026-11-08, 22 ends) the
 *    drop and eight ends as the head unit powered off were abrupt (the failure 0-10 ms
 *    before the end); the 12 normal ends had no failure for 15 minutes or more. The phone's Bluetooth (HFP) comes back
 *    about 6 s after every end, a normal one too, so that alone arms nothing.
 *  - Never touch a session that is up; one attempt at a time (one deadline).
 *  - Only for a phone that's still connected over Bluetooth (HFP), so a phone that left,
 *    turned Bluetooth off or is in airplane mode is left alone; after an abnormal end with the
 *    phone off HFP, it waits BOUNCE_MS for the phone to come back; not on USB; not during a call;
 *    not while the head unit's Wi-Fi is on as a client (its hotspot is off then, so no
 *    session can start: someone chose that, like a user disconnect).
 *  - Each attempt restarts the Bluetooth iAP2 session, which would cut off a phone that's
 *    already starting one, so the wait is longer than any normal start seen: from iAP2 over
 *    Bluetooth up to the session took 2.3-8.4 s on the EV6, 11.1 s once just after a boot.
 *  - Backoff 2 s doubling to 30 s, at most MAX_ATTEMPTS, then it gives up until the phone
 *    connects again (a session ending doesn't start it over); the count starts over after a
 *    session that ran STABLE_MS.
 */
final class Watchdog {
    static final long PHANTOM_MS = 2000, BOUNCE_MS = 15000, STABLE_MS = 60000;
    /** a CarPlay-channel send failure this close to a session's end makes it abrupt (seen: 0-10 ms) */
    static final long ABRUPT_MS = 1000;
    /** from iAP2 over Bluetooth up to the CarPlay session, longer than any normal start */
    static final long START_MS = 12000;
    /** from a session's end to the phone opening iAP2 over Bluetooth again (normal: 3-9 s) */
    static final long ATTACH_MS = 12000;
    static final long CALL_RETRY_MS = 5000;
    static final int MAX_ATTEMPTS = 5;

    enum Action { NONE, SPP_CONNECT, REIDENTIFY, GIVE_UP, WIFI_BUSY }

    /** Why the watchdog acts (for the log) */
    String reason = "";
    boolean sessionUp, armed, gaveUp;
    /** an abnormal end while the phone was off HFP: armed if it comes back within BOUNCE_MS */
    boolean awaitingPhone;
    int attempts;
    /** when check() should run next; 0: nothing to watch */
    long deadline;
    long sessionStart, lastEnd = -1;
    /** the next attempt not before this (the backoff), even when iAP2 comes up sooner */
    long notBefore;

    /** iAP2 over Bluetooth is up (BT_ATTACHED sent) */
    void attached(long t) {
        if (sessionUp || gaveUp || !armed) return;
        deadline = Math.max(t + START_MS, notBefore);
    }

    void sessionStarted(long t) {
        sessionUp = true;
        sessionStart = t;
        deadline = 0;
    }

    /**
     * The CarPlay session ended. bridged: its iAP2 channel came up (cpClient). abrupt: the
     * CarPlay channel's sends failed as it ended. phonePresent: the phone is still connected
     * over Bluetooth (HFP).
     */
    void sessionEnded(long t, boolean bridged, boolean abrupt, boolean phonePresent) {
        long ran = sessionUp ? t - sessionStart : 0;
        sessionUp = false;
        lastEnd = t;
        if (ran >= STABLE_MS) {
            gaveUp = false;
            attempts = 0;
            notBefore = 0;
        }
        // each end on its own: a session the watchdog brought back, then ended by the user, stays ended
        armed = false;
        awaitingPhone = false;
        deadline = 0;
        if (ran < PHANTOM_MS && !bridged) {
            armed = true;
            reason = "a session ended after " + ran + " ms, before its iAP2 channel";
        } else if (abrupt) {
            armed = true;
            reason = "the session ended abruptly after " + (ran / 1000) + " s (its CarPlay channel failed)";
        }
        if (!armed || gaveUp) {
            // a normal end may be the user's: left alone. Given up: until the phone connects again
            armed = false;
            return;
        }
        if (!phonePresent) {
            armed = false;
            awaitingPhone = true;
            return;
        }
        // the phone opens iAP2 over Bluetooth again by itself, normally; if not, ask
        deadline = Math.max(t + ATTACH_MS, notBefore);
    }

    /** the phone connected over Bluetooth (HFP) */
    void phoneConnected(long t) {
        if (gaveUp) {
            // the phone connected again (airplane mode, Bluetooth off and on): a fresh budget
            gaveUp = false;
            attempts = 0;
            notBefore = 0;
        }
        if (awaitingPhone && !sessionUp && t - lastEnd <= BOUNCE_MS) {
            armed = true;
            reason += ", the phone back on Bluetooth " + (t - lastEnd) + " ms later";
            deadline = Math.max(t + START_MS, notBefore);
        }
        awaitingPhone = false;
    }

    /** USB CarPlay, the bridge off, or the phone gone: nothing to watch */
    void stop() {
        deadline = 0;
        armed = false;
        awaitingPhone = false;
    }

    Action check(long t, boolean sppUp, boolean inCall, boolean phonePresent) {
        return check(t, sppUp, inCall, phonePresent, true);
    }

    /**
     * Called at the deadline. sppUp: SPP is connected to the phone. inCall: a phone call is on.
     * phonePresent: the phone is still connected over Bluetooth. hotspotPossible: the head
     * unit's Wi-Fi isn't on as a client; when it is, the watchdog stands down (WIFI_BUSY).
     */
    Action check(long t, boolean sppUp, boolean inCall, boolean phonePresent, boolean hotspotPossible) {
        if (sessionUp || deadline == 0 || t < deadline) return Action.NONE;
        if (!phonePresent) {
            stop();
            return Action.NONE;
        }
        if (!hotspotPossible) {
            stop();
            return Action.WIFI_BUSY;
        }
        if (inCall) {
            deadline = t + CALL_RETRY_MS;
            return Action.NONE;
        }
        if (attempts >= MAX_ATTEMPTS) {
            stop();
            gaveUp = true;
            return Action.GIVE_UP;
        }
        attempts++;
        // after this attempt: its iAP2 session (attached() sets the wait), or try again
        notBefore = t + backoff(attempts);
        deadline = notBefore + ATTACH_MS;
        return sppUp ? Action.REIDENTIFY : Action.SPP_CONNECT;
    }

    /** 2, 4, 8, 16, 30 s */
    static long backoff(int attempt) {
        long b = 2000L << Math.max(0, Math.min(attempt - 1, 4));
        return Math.min(b, 30000L);
    }
}
