package hmgapi.hook;
/** host stand-in: what AaTurns passes on */
public final class ProjectionHooks {
    public static String last;
    public static void navigationDump(String line) { last = line; }
}
