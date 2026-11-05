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
 * key frame at least every PERIOD_MS, for a decoder that hangs without reporting
 * anything. Never more than one every MIN_GAP_MS: key frames are large, and asking
 * for them faster over a slow Wi-Fi link would make a stall worse.
 */
final class Keyframes {
    static final long MIN_GAP_MS = 2000;
    static final long PERIOD_MS = 10000;

    /** decoder failure types (libCarPlayUtils): 0 resets its black-screen count, the rest are failures */
    static final int NO_OUTPUT = 1, SEQUENCE_HEADER = 2, BLACK_SCREEN = 3;

    private final boolean periodic;
    private boolean session, wireless;
    private long last = -1;
    /** failures since the last request (for the log) */
    int failures;

    Keyframes(boolean periodic) {
        this.periodic = periodic;
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
        if (type < NO_OUTPUT || type > BLACK_SCREEN) return false;
        failures++;
        return request(now);
    }

    /** the screen stream timed out: true to ask for a key frame now */
    boolean screenTimeout(long now) {
        return request(now);
    }

    /** the periodic tick: true to ask for a key frame now (none was asked for in the last period) */
    boolean tick(long now) {
        if (!ticking()) return false;
        if (last >= 0 && now - last < PERIOD_MS) return false;
        return request(now);
    }

    /** ms until the next tick is due */
    long nextTick(long now) {
        if (last < 0) return PERIOD_MS;
        long d = last + PERIOD_MS - now;
        return d < MIN_GAP_MS ? MIN_GAP_MS : d;
    }

    /** a failure or timeout means a stream is up, so these don't wait for onSessionStarted */
    private boolean request(long now) {
        if (last >= 0 && now - last < MIN_GAP_MS) return false;
        last = now;
        return true;
    }
}
