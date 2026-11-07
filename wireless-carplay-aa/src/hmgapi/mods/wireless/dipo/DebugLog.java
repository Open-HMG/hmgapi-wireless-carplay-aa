package hmgapi.mods.wireless.dipo;

import android.content.Context;
import android.os.StatFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Setting "debug_log" (test builds): a logcat of the main and system buffers kept in
 * DiPO's data folder, for after a drive (the main buffer only holds about 10 s).
 *
 * Bounded so it can't fill /data: logcat itself rotates 8 files of 4 MB (32 MB at
 * most), it doesn't start with less than 200 MB free, and one copy runs at a time
 * (a pidfile, checked against /proc/<pid>/cmdline). The noisiest tags of the unit
 * (about 26 KB/s in all) are dropped. The folder is deleted after testing.
 */
final class DebugLog {
    static final String DIR = "hmgapi";
    static final long MIN_FREE = 200L << 20;
    static final String[] QUIET = {
        "HEVService", "MicomD", "USMService", "ModeManagerService", "SplitScreenClimateView",
        "ThemeScheduleService", "DATCService", "vhandler", "SplitScreen", "VRService",
        "SystemService", "PgsService", "ClusterService", "MicomAudioManager", "ClimateManager",
        "AvnStatusBar", "RearMode", "WelcomeService", "UsageLoggingService", "TimePolicyManager",
        "SXM65HD", "BroadcastQueue", "ContextImpl", "BCMCanManager", "NavigationCluster",
    };

    private DebugLog() {}

    static void start(Context c) {
        try {
            File dir = new File(c.getApplicationInfo().dataDir, DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                Dipo.log("diagnostic log: can't make " + dir);
                return;
            }
            File pidfile = new File(dir, "logcat.pid");
            int old = readPid(pidfile);
            if (old > 0 && isLogcat(old)) {
                Dipo.log("diagnostic log: already running (pid " + old + ")");
                return;
            }
            StatFs fs = new StatFs("/data");
            long free = (long) fs.getAvailableBlocks() * fs.getBlockSize();
            if (free < MIN_FREE) {
                Dipo.log("diagnostic log: not started, only " + (free >> 20) + " MB free on /data");
                return;
            }
            String[] head = {"/system/bin/logcat", "-v", "time", "-b", "main", "-b", "system",
                    "-f", new File(dir, "carplay.log").getPath(), "-r", "4096"};
            String[] all = new String[head.length + 3 + QUIET.length];
            System.arraycopy(head, 0, all, 0, head.length);
            all[head.length] = "-n";
            all[head.length + 1] = "7";
            all[head.length + 2] = "*:V";
            for (int i = 0; i < QUIET.length; i++) all[head.length + 3 + i] = QUIET[i] + ":S";
            Process p = new ProcessBuilder(all).redirectErrorStream(true).start();
            int pid = pidOf(p);
            if (pid > 0) {
                FileOutputStream o = new FileOutputStream(pidfile);
                try {
                    o.write(String.valueOf(pid).getBytes("US-ASCII"));
                } finally {
                    o.close();
                }
            }
            Dipo.log("diagnostic log: started (pid " + pid + ") in " + dir + ", 8 x 4 MB at most, "
                    + (free >> 20) + " MB free on /data");
        } catch (Throwable t) {
            Dipo.error("diagnostic log: not started", t);
        }
    }

    static int readPid(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[16];
                int n = in.read(b);
                return n > 0 ? Integer.parseInt(new String(b, 0, n, "US-ASCII").trim()) : -1;
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return -1;
        }
    }

    static boolean isLogcat(int pid) {
        try {
            FileInputStream in = new FileInputStream("/proc/" + pid + "/cmdline");
            try {
                byte[] b = new byte[64];
                int n = in.read(b);
                return n > 0 && new String(b, 0, n, "US-ASCII").contains("logcat");
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** java.lang.ProcessManager$ProcessImpl.pid on Dalvik */
    static int pidOf(Process p) {
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getInt(p);
        } catch (Exception e) {
            return -1;
        }
    }
}
