package hmgapi.hook;

import java.util.ArrayList;
import java.util.List;

/** host stand-in: every call is recorded as one line */
public final class Cluster {
    public static final int UNIT_M = 0, UNIT_KM = 1, UNIT_MI = 2, UNIT_FT = 3, UNIT_YD = 4;
    public final List<String> calls = new ArrayList<String>();

    void rec(String s) { calls.add(s); }
    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        int end = b.length;
        while (end > 1 && b[end - 1] == 0) end--;   // trailing zeros left out
        for (int i = 0; i < end; i++) s.append(String.format("%02x", b[i] & 0xff));
        return s.toString();
    }
    public void tbtInfo(int a, int b, float c, float d, int e, int f, int g, int h, int i) {
        rec("tbt " + a + " " + b + " " + c + " " + d + " " + e + " " + f + " " + g + " " + h + " " + i);
    }
    public void tbtSubInfo(float a, int b, int c, int d, int e, int f, int g, float h, int i, int j) {
        rec("sub " + a + " " + b + " " + c + " " + d + " " + e + " " + f + " " + g + " " + h + " " + i + " " + j);
    }
    public void tbtSubInfo2C(int a, int b, int c, int d, int e, int f, int g, int h, int i) {
        rec("2c " + a + " " + b + " " + c + " " + d + " " + e + " " + f + " " + g + " " + h + " " + i);
    }
    public void turnListNew(byte[] f) { rec("hud " + hex(f)); }
    public void laneInfo(byte[] f) { rec("lanes " + hex(f)); }
    public void destinationName(int type, String name) { rec("dest " + type + " [" + name + "]"); }
    public void crossroadName(String name) { rec("road [" + name + "]"); }
    public static final class Turn {
        public int type, direction, traffic, no, unit;
        public String name = "", exitNumber = "";
        public float distance;
    }
    public void turnList(Turn[] t) {
        StringBuilder s = new StringBuilder("list");
        for (Turn x : t) s.append(" {" + x.no + " " + x.type + " " + x.direction + " [" + x.name + "] [" + x.exitNumber + "] " + x.distance + " " + x.unit + "}");
        rec(s.toString());
    }
}
