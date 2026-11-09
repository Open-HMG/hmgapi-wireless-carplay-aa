package hmgapi.mods.wireless.dipo;

/**
 * When wireless CarPlay should be started again by the car: the policy, with no Android in
 * it (Bridge feeds it events and carries out what check() says), so it can be tested on a
 * host. All times are milliseconds on one monotonic clock.
 *
 * Why the car acts: in Apple's model the head unit decides whether to reconnect after a
 * session ends (WWDC 2017, session 717): not after the user ends it, but after an unexpected
 * loss, first through the CarPlay control channel, then over Bluetooth. This class covers the
 * Bluetooth step (iAP2 identification again, which hands the phone the Wi-Fi details and has
 * it start the session). Seen on a US EV6 (2026-11-08 19:56): the phone's Wi-Fi and Bluetooth
 * hiccuped, a resume attempt ended after 0.8 s, Bluetooth and iAP2 came back in 4 s, and the
 * phone then never started CarPlay until airplane mode was toggled 110 s later (after which
 * the session was up in 3 s).
 *
 * Rules, after open receivers that do this (DiPlay's startup retry budget and first-TCP
 * watchdog, playport's supervisor, WirelessAndroidAutoDongle, aa-proxy-rs):
 *  - Never touch a session that is up; one attempt at a time (one deadline).
 *  - Only for a phone that's still connected over Bluetooth (HFP), so a phone that left,
 *    turned Bluetooth off or is in airplane mode is left alone; not on USB; not during a call;
 *    not while the head unit's Wi-Fi is joined to a network (its hotspot can't be on then, so
 *    no session can start: someone chose that, like a user disconnect in Apple's model).
 *  - A phone that never had a wireless session here (first setup, the phone's "use CarPlay?"
 *    prompt) is never touched unless something already went wrong.
 *  - Armed by an abnormal end (a session that ends within PHANTOM_MS before its iAP2 channel
 *    came up, or the phone's Bluetooth reconnecting within BOUNCE_MS of an end): a shorter
 *    wait and more attempts. Normal start, from iAP2 over Bluetooth up to the session, took
 *    2.3-8.4 s on the EV6 (11.1 s once, just after a boot).
 *  - Backoff 2 s doubling to 30 s, at most MAX_ARMED (armed) or MAX_PLAIN attempts, then it
 *    gives up until the phone connects again; the count starts over after a session that
 *    ran STABLE_MS.
 */
final class Watchdog {
    static final long PHANTOM_MS = 2000, BOUNCE_MS = 15000, STABLE_MS = 60000;
    /** from iAP2 over Bluetooth up to the CarPlay session */
    static final long START_ARMED_MS = 6000, START_PLAIN_MS = 20000;
    /** from a session's end to the phone opening iAP2 over Bluetooth again (normal: 3-9 s) */
    static final long ATTACH_MS = 12000;
    static final long CALL_RETRY_MS = 5000;
    static final int MAX_ARMED = 5, MAX_PLAIN = 3;

    enum Action { NONE, SPP_CONNECT, REIDENTIFY, GIVE_UP, WIFI_BUSY }

    /** Why the watchdog acts (for the log) */
    String reason = "";
    boolean sessionUp, armed, gaveUp;
    int attempts;
    /** when check() should run next; 0: nothing to watch */
    long deadline;
    long sessionStart, lastEnd = -1;
    /** the next attempt not before this (the backoff), even when iAP2 comes up sooner */
    long notBefore;

    /** iAP2 over Bluetooth is up (BT_ATTACHED sent). known: this phone had a wireless session here before. */
    void attached(long t, boolean known) {
        if (sessionUp || gaveUp) return;
        if (!armed && !known) {
            deadline = 0;
            return;
        }
        if (!armed) reason = "no session after iAP2 over Bluetooth";
        deadline = Math.max(t + (armed ? START_ARMED_MS : START_PLAIN_MS), notBefore);
    }

    void sessionStarted(long t) {
        sessionUp = true;
        sessionStart = t;
        deadline = 0;
    }

    /**
     * The CarPlay session ended. bridged: its iAP2 channel came up (cpClient). phonePresent:
     * the phone is still connected over Bluetooth (HFP).
     */
    void sessionEnded(long t, boolean bridged, boolean phonePresent) {
        long ran = sessionUp ? t - sessionStart : 0;
        sessionUp = false;
        lastEnd = t;
        if (ran >= STABLE_MS || gaveUp) {
            gaveUp = false;
            attempts = 0;
            armed = false;
            notBefore = 0;
        }
        if (!phonePresent) {
            stop();
            return;
        }
        if (ran < PHANTOM_MS && !bridged) {
            armed = true;
            reason = "a session ended after " + ran + " ms, before its iAP2 channel";
        } else if (!armed) {
            reason = "the session ended with the phone still connected";
        }
        // the phone opens iAP2 over Bluetooth again by itself, normally; if it doesn't, ask
        deadline = t + ATTACH_MS;
    }

    /** the phone connected over Bluetooth (HFP) */
    void phoneConnected(long t) {
        if (gaveUp) {
            // the phone connected again (airplane mode, Bluetooth off and on): a fresh budget
            gaveUp = false;
            attempts = 0;
            notBefore = 0;
        }
        if (!sessionUp && lastEnd >= 0 && t - lastEnd <= BOUNCE_MS && !armed) {
            armed = true;
            reason = "the phone's Bluetooth reconnected " + (t - lastEnd) + " ms after the session ended";
            if (deadline != 0) deadline = Math.max(Math.min(deadline, t + START_ARMED_MS), notBefore);
        }
    }

    /** USB CarPlay, the bridge off, or the phone gone: nothing to watch */
    void stop() {
        deadline = 0;
        armed = false;
    }

    /**
     * Called at the deadline. sppUp: SPP is connected to the phone. inCall: a phone call is on.
     * phonePresent: the phone is still connected over Bluetooth.
     */
    Action check(long t, boolean sppUp, boolean inCall, boolean phonePresent) {
        return check(t, sppUp, inCall, phonePresent, true);
    }

    /**
     * hotspotPossible: the head unit's Wi-Fi isn't joined to a network. When it is, the watchdog
     * stands down (WIFI_BUSY) until the phone opens iAP2 over Bluetooth again (attached()).
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
        if (attempts >= (armed ? MAX_ARMED : MAX_PLAIN)) {
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
