package android.os;
/** host stand-in: records the delayed runnable instead of running it */
public class Handler {
    public Runnable delayed;
    public boolean post(Runnable r) { r.run(); return true; }
    public boolean postDelayed(Runnable r, long ms) { delayed = r; return true; }
    public void removeCallbacks(Runnable r) { if (delayed == r) delayed = null; }
}
