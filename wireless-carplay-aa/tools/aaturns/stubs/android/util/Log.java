package android.util;
public final class Log {
    public static int d(String t, String m) { return 0; }
    public static String last;
    public static int i(String t, String m) { last = t + " " + m; return 0; }
}
