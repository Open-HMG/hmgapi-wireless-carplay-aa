package hmgapi.mods.wireless.dipo;

/**
 * When to ask the phone for a new key frame on the CarPlay screen stream (part
 * "picture"). No Android here: Dipo feeds it the hooks' events and the clock, and
 * calls forceKeyFrame() when it says so. Host-tested (tools/picture).
 *
 * Why: the stock CarPlay library decodes each screen frame on the stream's read
 * thread, and nothing in it or in DiPO ever calls forceKeyFrame(). Decoder failures
 * and screen timeouts reach DiPO and are only logged. So once the Telechips decoder
 * stops putting out frames, the picture stays frozen (audio is its own stream and
 * carries on) until the driver leaves CarPlay and comes back, which rebuilds the
 * renderer and starts a fresh stream. Seen on wireless sessions only (US EV6 260507).
 *
 * What: a key frame after a decoder failure or a screen timeout, and on wireless a
 * key frame at least every period (setting picture_period, PERIOD_MS without one), for
 * a decoder that hangs without reporting anything. Never more than one every MIN_GAP_MS: key frames are large, and asking
 * for them faster over a slow Wi-Fi link would make a stall worse.
 */
final class Keyframes {
    static final long MIN_GAP_MS = 2000;
    static final long PERIOD_MS = 10000;

    /** decoder failure types (libCarPlayUtils): 0 resets its black-screen count, the rest are failures */
    static final int NO_OUTPUT = 1, SEQUENCE_HEADER = 2, BLACK_SCREEN = 3;

    private final boolean periodic;
    final long period;
    private boolean session, wireless;
    private long last = -1;
    /** failures since the last request (for the log) */
    int failures;
    /** for the log's summaries, since the last summary: requests by kind, failures by type (0-3), screen timeouts, events inside the minimum gap */
    int periodicAsked, failureAsked, timeoutAsked, timeouts, limited;
    final int[] types = new int[4];

    Keyframes(boolean periodic) {
        this(periodic, PERIOD_MS);
    }

    Keyframes(boolean periodic, long period) {
        this.periodic = periodic;
        this.period = period < MIN_GAP_MS ? MIN_GAP_MS : period;
    }

    void sessionStarted(int transport) {
        session = true;
        wireless = transport == 1;
        last = -1;
        failures = 0;
    }

    void sessionEnded() {
        session = false;
        wireless = false;
    }

    /** whether the periodic tick should run during this session */
    boolean ticking() {
        return session && wireless && periodic;
    }

    /** a decoder failure: true to ask for a key frame now */
    boolean decodeFailure(int type, long now) {
        if (type >= 0 && type < types.length) types[type]++;
        if (type < NO_OUTPUT || type > BLACK_SCREEN) return false;
        failures++;
        if (!request(now)) {
            limited++;
            return false;
        }
        failureAsked++;
        return true;
    }

    /** the screen stream timed out: true to ask for a key frame now */
    boolean screenTimeout(long now) {
        timeouts++;
        if (!request(now)) {
            limited++;
            return false;
        }
        timeoutAsked++;
        return true;
    }

    /** the periodic tick: true to ask for a key frame now (none was asked for in the last period) */
    boolean tick(long now) {
        if (!ticking()) return false;
        if (last >= 0 && now - last < period) return false;
        if (!request(now)) return false;
        periodicAsked++;
        return true;
    }

    /** ms until the next tick is due */
    long nextTick(long now) {
        if (last < 0) return period;
        long d = last + period - now;
        return d < MIN_GAP_MS ? MIN_GAP_MS : d;
    }

    /** whether anything happened since the last summary */
    boolean eventful() {
        return periodicAsked + failureAsked + timeoutAsked + timeouts + limited + types[0] + types[1] + types[2] + types[3] > 0;
    }

    /** what happened since the last summary, for the log, and starts the next one */
    String summary() {
        String s = "key frames asked " + periodicAsked + " periodic, " + failureAsked + " after a decoder failure, " + timeoutAsked
                + " after a screen timeout; decoder failures " + types[1] + " no output, " + types[2] + " sequence header, " + types[3]
                + " black screen (" + types[0] + " resets); screen timeouts " + timeouts + "; " + limited + " inside the " + MIN_GAP_MS / 1000 + " s gap";
        periodicAsked = failureAsked = timeoutAsked = timeouts = limited = 0;
        for (int i = 0; i < types.length; i++) types[i] = 0;
        return s;
    }

    /** a failure or timeout means a stream is up, so these don't wait for onSessionStarted */
    private boolean request(long now) {
        if (last >= 0 && now - last < MIN_GAP_MS) return false;
        last = now;
        return true;
    }
}
