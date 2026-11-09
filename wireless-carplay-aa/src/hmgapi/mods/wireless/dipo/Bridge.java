package hmgapi.mods.wireless.dipo;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.wifi.WifiConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.broadcom.bt.hfdevice.hfbtcm.HfDeviceBTCMManager;
import com.broadcom.bt.hfdevice.hfbtcm.HfDeviceStatusListener;
import com.tcc.iap2.IIap2Listener;
import com.tcc.iap2.IIap2Service;
import com.tcc.iap2.Iap2ListenerInfo;
import com.tcc.iap2.Iap2NotiMsg;

import java.lang.reflect.Method;
import java.util.ArrayList;

import hmgapi.hook.Points;

/**
 * Wireless CarPlay's Bluetooth bridge, in DiPO (from the bridge build, com.ev6.cpwl).
 *
 * An iPhone pairs over Bluetooth; wireless CarPlay starts with iAP2 over Bluetooth
 * (RFCOMM/SPP), which the iAP2 server here can't do by itself. The bridge carries it:
 * SPP data from Broadcom's Bluetooth manager goes into the iAP2 server as transport data
 * (Iap2SvcTransportReadDone), and what the server sends back (notification 80) goes out
 * over SPP. The iAP2 server opens the session for BT_ATTACHED (HMG-API's hook there, part
 * "bridge"). Once the phone has joined the hotspot and the CarPlay session runs, iAP2
 * moves into the CarPlay session's own channel: bytes the phone sends there
 * (oniAPSendMessage) go to the server, and what the server sends for it
 * (notification 81) goes out through the CarPlay library.
 *
 * HMG-API binds the iAP2 server and hooks Broadcom's manager (a library), and only
 * observes DiPO's CarPlay callbacks; DiPO's own iAP2 listener is told to ignore what
 * belongs to the bridge's sessions (Dipo's hooks).
 *
 * persist.ev6.cpwl.enable=0 turns it off; persist.ev6.cpwl.handover=created moves iAP2
 * into the CarPlay channel as soon as the session is created (default: on its first data).
 */
final class Bridge {
    static final String ACTION_ATTACH = "com.ev6.cpwl.BT_ATTACHED", ACTION_DETACH = "com.ev6.cpwl.BT_DETACHED";
    static final String IAP2_PKG = "com.tcc.iap2server";
    static final int APPLE_VENDOR_ID = 76;
    /** iAP2 components: 1 USB, 4 Bluetooth CarPlay, 5 the CarPlay session's channel */
    static final int COMPONENT_USB = 1, COMPONENT_BT_CARPLAY = 4, COMPONENT_CARPLAY_CLIENT = 5;
    static final int LISTENER_EXTERNAL_BT_TRANSPORT = 1024, LISTENER_EXTERNAL_CARPLAY_TRANSPORT = 2048;
    static final int NOTI_IDENTIFICATION_ACCEPTED = 0, NOTI_TRANSPORT_IDENTIFIER = 24;
    static final int NOTI_BT_TRANSPORT_DATA = 80, NOTI_CARPLAY_CLIENT_TRANSPORT_DATA = 81, OBJ_BLOB = 11;
    static final int MAX_PENDING = 64;
    static final long SPP_CONNECT_DELAY_MS = 3000;
    static final int SPP_DISCONNECTED = 0, SPP_CONNECTED = 2, EVENT_BTCM_CONNECTED = 2;

    static volatile String sAddr;
    static volatile HfDeviceBTCMManager sBtcm;
    static volatile Object sCp;
    static volatile boolean sCpClient, sCpConnected, sCpWireless, sSession, sUsb;
    static volatile IIap2Service sIap2;
    /** the phone's Bluetooth address from its last iAP2 session (DiPO's first-connect notice) */
    static volatile String sLastBtId;
    static ClassLoader cl;
    static Context context;
    private static int sCpFail, sCpSent;
    /** when a CarPlay-channel iAP2 send last failed (now()), -1: not in this session */
    private static volatile long sCpFailAt = -1;
    /** the watchdog sent BT_ATTACHED again: the old Bluetooth session's disconnect is still to come */
    private static volatile boolean sReattach;
    private static Handler sHandler, sCpTx;
    private static Method sNativeSend;
    private static final ArrayList<byte[]> sPendingIn = new ArrayList<byte[]>();
    private static BtListener sBt;
    private static TxListener sTx;
    /** setting bridge_watchdog: start wireless CarPlay again when it doesn't come back by itself (Watchdog) */
    static volatile boolean sWatchdog = true;
    /** the phone of the last Bluetooth session (sAddr is cleared when SPP goes down) */
    static volatile String sPhone;
    /** only on the handler thread */
    private static final Watchdog sWd = new Watchdog();
    private static final Runnable sWdCheck = new Runnable() {
        @Override
        public void run() {
            wdCheck();
        }
    };

    private Bridge() {}

    static void log(String m) {
        Dipo.log("bridge: " + m);
    }

    static void error(String m, Throwable t) {
        Dipo.error("bridge: " + m, t);
    }

    /** DiPO registered its listener with Broadcom's manager (the manager is connected): ours goes next to it */
    static void onBtcmReady(HfDeviceBTCMManager m) {
        if (m == null) return;
        try {
            sBtcm = m;
            synchronized (Bridge.class) {
                if (sBt == null) sBt = new BtListener();
            }
            m.registerHfDeviceStatusListener(sBt);
            log("BTCM listener registered");
            BluetoothDevice d = m.IbtcmGetHfpConnectedDevice();
            if (d != null && isApple(d)) scheduleSppConnect(d.getAddress());
        } catch (Throwable t) {
            error("onBtcmReady", t);
        }
    }

    static boolean isOurs(Object listener) {
        return listener != null && listener == sBt;
    }

    /** the iAP2 server is bound: register for Bluetooth and CarPlay-channel transport data */
    static void onIap2Bound(IIap2Service s) {
        if (s == null) return;
        try {
            sIap2 = s;
            synchronized (Bridge.class) {
                if (sTx == null) sTx = new TxListener();
            }
            Iap2ListenerInfo info = new Iap2ListenerInfo();
            info.onSetSupportType(LISTENER_EXTERNAL_BT_TRANSPORT | LISTENER_EXTERNAL_CARPLAY_TRANSPORT);
            info.onSetListenerName("EV6_CPWL_BT");
            log("Iap2SvcInit(EV6_CPWL_BT, 0xc00) = " + s.Iap2SvcInit(sTx, info));
        } catch (Throwable t) {
            error("onIap2Bound", t);
        }
    }

    static boolean enabled() {
        return !"0".equals(prop("persist.ev6.cpwl.enable", "1"));
    }

    /** a system property (android.os.SystemProperties isn't in the public SDK) */
    static String prop(String key, String fallback) {
        try {
            return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class).invoke(null, key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    static synchronized Handler handler() {
        if (sHandler == null) {
            HandlerThread t = new HandlerThread("hmgapi-cpwl");
            t.start();
            sHandler = new Handler(t.getLooper());
        }
        return sHandler;
    }

    static boolean isApple(BluetoothDevice d) {
        if (d == null) return false;
        try {
            Object v = BluetoothDevice.class.getMethod("getVendor").invoke(d);
            if (v instanceof Integer) return (Integer) v == APPLE_VENDOR_ID;
        } catch (Throwable t) {
            // no vendor id on this build: by name
        }
        try {
            String n = d.getName();
            return n != null && (n.contains("iPhone") || n.contains("iPad"));
        } catch (Throwable t) {
            return false;
        }
    }

    static void scheduleSppConnect(final String addr) {
        if (addr == null || !enabled() || sUsb || sSession || sCpWireless) return;
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    HfDeviceBTCMManager m = sBtcm;
                    if (m == null || sUsb || sSession || sCpWireless || !enabled()) return;
                    if (m.IbtcmIsSppConnectedDevice(addr)) log("SPP already up for " + addr);
                    else log("IbtcmSppConnect(" + addr + ") = " + m.IbtcmSppConnect(addr));
                } catch (Throwable t) {
                    error("sppConnect", t);
                }
            }
        }, SPP_CONNECT_DELAY_MS);
    }

    static void onSppUp(final String addr) {
        if (addr == null || sSession) return;
        sAddr = addr;
        sPhone = addr;
        if (sCpClient || sCpWireless || !enabled() || sUsb) {
            log("SPP up, no iAP2 session started (CarPlay client " + sCpClient + ", wireless " + sCpWireless
                    + ", enabled " + enabled() + ", USB " + sUsb + ")");
            return;
        }
        sSession = true;
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context c = context;
                    if (c == null) {
                        log("no context, cannot start session");
                        sSession = false;
                        return;
                    }
                    ensureHotspot(c);
                    Intent i = new Intent(ACTION_ATTACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    i.putExtra("component", COMPONENT_BT_CARPLAY);
                    i.putExtra("serial", addr);
                    c.sendBroadcast(i);
                    log("BT_ATTACHED sent for " + addr);
                    sWd.attached(now());
                    wdSchedule("waiting for the session");
                } catch (Throwable t) {
                    sSession = false;
                    error("onSppUp", t);
                }
            }
        });
    }

    static void onUsbGone() {
        final String addr = sAddr;
        if (addr == null || sSession) return;
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    HfDeviceBTCMManager m = sBtcm;
                    if (m != null && !sUsb && !sSession && addr.equals(sAddr) && m.IbtcmIsSppConnectedDevice(addr)) onSppUp(addr);
                } catch (Throwable t) {
                    error("onUsbGone", t);
                }
            }
        }, SPP_CONNECT_DELAY_MS);
    }

    static void onCpSessionCreated(Object cp) {
        if (cp != null) sCp = cp;
        log("CarPlay session created (usb=" + sUsb + " btAddr=" + sAddr + ")");
        if ("created".equals(prop("persist.ev6.cpwl.handover", "data")) && !sUsb && sAddr != null) {
            startCpClient("session created");
        }
    }

    static void onCpSessionStarted(int transport) {
        final boolean wireless = transport == 1 && !sUsb;
        sCpWireless = wireless;
        sCpFailAt = -1;
        log("CarPlay session started transport=" + transport + " wireless=" + sCpWireless);
        handler().post(new Runnable() {
            @Override
            public void run() {
                if (sWd.attempts > 0) log("watchdog: session back after " + sWd.attempts + " attempt(s)");
                sWd.sessionStarted(now());
                wdSchedule(null);
            }
        });
    }

    /** iAP2 bytes from the phone over the CarPlay channel: to the iAP2 server (buffered until its session is up) */
    static void onCpIapIn(Object cp, byte[] data, int len) {
        try {
            if (cp != null) sCp = cp;
            if (data == null || len <= 0 || sUsb) return;
            if (len >= data.length) len = data.length;
            byte[] b = new byte[len];
            System.arraycopy(data, 0, b, 0, len);
            if (!sCpClient) startCpClient("iAP2 data from CarPlay channel");
            IIap2Service s = sIap2;
            if (sCpConnected && s != null) {
                flushPending(s);
                s.Iap2SvcTransportReadDone(b, len);
            } else {
                synchronized (sPendingIn) {
                    if (sPendingIn.size() < MAX_PENDING) sPendingIn.add(b);
                }
            }
        } catch (Throwable t) {
            error("onCpIapIn", t);
        }
    }

    static void onCpSessionFinalized(int type) {
        try {
            final long endAt = now(), failAt = sCpFailAt;
            // a dropped Wi-Fi link fails the CarPlay channel's sends as it ends; a clean end doesn't
            final boolean abrupt = failAt >= 0 && endAt - failAt <= Watchdog.ABRUPT_MS;
            log("CarPlay session finalized type=" + type + " cpClient=" + sCpClient
                    + (abrupt ? ", abrupt (a channel send failed " + (endAt - failAt) + " ms before)" : ""));
            boolean client = sCpClient, wireless = sCpWireless;
            sCpWireless = false;
            sCpClient = false;
            sCpConnected = false;
            synchronized (sPendingIn) {
                sPendingIn.clear();
            }
            if (client) sendDetach();
            if (client || wireless) onUsbGone();
            // every end reaches the watchdog (a USB one too, so it doesn't stay "up"); only a wireless one can arm it
            final boolean bridged = client, ours = client || wireless;
            handler().post(new Runnable() {
                @Override
                public void run() {
                    boolean present = ours && phonePresent();
                    sWd.sessionEnded(endAt, bridged, abrupt && ours, present);
                    wdSchedule(!ours ? null
                            : sWd.awaitingPhone ? sWd.reason + "; the phone isn't on Bluetooth, waiting for it to come back"
                            : !present ? "the phone isn't connected over Bluetooth, nothing to do"
                            : sWd.armed ? sWd.reason : null);
                }
            });
        } catch (Throwable t) {
            error("onCpSessionFinalized", t);
        }
    }

    /** DiPO's iAP2 listener: ignore the Bluetooth and CarPlay-channel sessions ending while wireless CarPlay is up */
    static boolean dipoIgnoreDisconnect(int component) {
        boolean ignore = (sCpClient || sCpWireless) && (component == COMPONENT_BT_CARPLAY || component == COMPONENT_CARPLAY_CLIENT);
        if (ignore) log("DiPO: ignoring iAP2 disconnect comp=" + component + " (wireless CarPlay up)");
        return ignore;
    }

    /** DiPO's iAP2 listener: the CarPlay-channel session's identification isn't a new phone */
    static boolean dipoDropNoti(int what) {
        boolean drop = sCpClient && (what == NOTI_IDENTIFICATION_ACCEPTED || what == NOTI_TRANSPORT_IDENTIFIER);
        if (drop) log("DiPO: dropping iAP2 noti " + what + " from the CarPlay-channel session");
        return drop;
    }

    static void startCpClient(final String why) {
        if (sCpClient) return;
        sCpClient = true;
        sCpConnected = false;
        final String addr = sAddr;
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context c = context;
                    if (c == null) return;
                    Intent i = new Intent(ACTION_ATTACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    i.putExtra("component", COMPONENT_CARPLAY_CLIENT);
                    i.putExtra("serial", addr);
                    c.sendBroadcast(i);
                    log("iAP2 handover to CarPlay channel requested (" + why + ")");
                } catch (Throwable t) {
                    error("startCpClient", t);
                }
            }
        });
    }

    static void onCpClientConnected() {
        sCpConnected = true;
        IIap2Service s = sIap2;
        if (s != null) flushPending(s);
    }

    private static void flushPending(IIap2Service s) {
        ArrayList<byte[]> all;
        synchronized (sPendingIn) {
            if (sPendingIn.isEmpty()) return;
            all = new ArrayList<byte[]>(sPendingIn);
            sPendingIn.clear();
        }
        for (byte[] b : all) {
            try {
                s.Iap2SvcTransportReadDone(b, b.length);
            } catch (Throwable t) {
                error("flushPending", t);
            }
        }
        log("flushed " + all.size() + " buffered CarPlay-channel packets");
    }

    private static synchronized Handler cpTx() {
        if (sCpTx == null) {
            HandlerThread t = new HandlerThread("hmgapi-cp-tx");
            t.start();
            sCpTx = new Handler(t.getLooper());
        }
        return sCpTx;
    }

    private static Method nativeSend() {
        if (sNativeSend == null) sNativeSend = Points.method(cl, "dipo.carplay.native_iap_send");
        return sNativeSend;
    }

    /** iAP2 bytes from the server for the CarPlay channel: out through the CarPlay library */
    static void writeCp(byte[] data) {
        final Object cp = sCp;
        if (cp == null || data == null || data.length == 0 || !sCpClient) return;
        final byte[] b = data.clone();
        cpTx().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Object r = nativeSend().invoke(cp, b, b.length);
                    int rc = r instanceof Integer ? (Integer) r : -1;
                    if (rc == 0) {
                        sCpSent++;
                        if (sCpSent <= 3 || sCpSent % 200 == 0) log("CarPlay-channel iAP2 sent " + b.length + " bytes (#" + sCpSent + ")");
                    } else {
                        sCpFail++;
                        sCpFailAt = now();
                        if (sCpFail <= 5 || sCpFail % 50 == 0) log("CarPlay-channel iAP2 send failed rc=" + rc + " len=" + b.length);
                    }
                } catch (Throwable t) {
                    error("writeCp native", t);
                }
            }
        });
    }

    static void onSppDown(String addr) {
        if (addr == null || !addr.equals(sAddr)) return;
        if (sCpWireless) {
            sSession = false;
            startCpClient("bluetooth dropped during wireless CarPlay");
            return;
        }
        sAddr = null;
        if (sSession) {
            sSession = false;
            sendDetach();
        }
    }

    static long now() {
        return SystemClock.elapsedRealtime();
    }

    /** (handler thread) the next check at the watchdog's deadline; why: logged when there is one */
    static void wdSchedule(String why) {
        Handler h = handler();
        h.removeCallbacks(sWdCheck);
        long d = sWd.deadline;
        if (!sWatchdog || d == 0) return;
        long in = Math.max(0, d - now());
        h.postDelayed(sWdCheck, in);
        if (why != null) log("watchdog: " + why + (sWd.armed ? " (armed)" : "") + ", next check in " + (in / 1000) + " s");
    }

    /** (handler thread) the watchdog's deadline: carry out what it says */
    static void wdCheck() {
        try {
            final String addr = sPhone;
            HfDeviceBTCMManager m = sBtcm;
            if (!sWatchdog || addr == null || m == null || !enabled() || sUsb) {
                sWd.stop();
                return;
            }
            boolean spp = m.IbtcmIsSppConnectedDevice(addr);
            Watchdog.Action a = sWd.check(now(), spp, inCall(), phonePresent(), !wifiClientOn());
            switch (a) {
                case SPP_CONNECT:
                    log("watchdog: attempt " + sWd.attempts + " (" + sWd.reason + "): IbtcmSppConnect(" + mask(addr) + ") = "
                            + m.IbtcmSppConnect(addr));
                    break;
                case REIDENTIFY:
                    // no session is up (the watchdog checked), so these are stale and would block onSppUp
                    if (sCpClient || sCpWireless) {
                        log("watchdog: clearing stale CarPlay flags (client " + sCpClient + ", wireless " + sCpWireless + ")");
                        sCpClient = false;
                        sCpWireless = false;
                        sCpConnected = false;
                    }
                    log("watchdog: attempt " + sWd.attempts + " (" + sWd.reason + "): iAP2 over Bluetooth again (identification)");
                    // BT_ATTACHED again: the iAP2 server stops the old Bluetooth session and starts a new one
                    sSession = false;
                    sReattach = true;
                    onSppUp(addr);
                    break;
                case WIFI_BUSY:
                    log("watchdog: the head unit's Wi-Fi is on as a client, so its hotspot is off: not restarting CarPlay ("
                            + sWd.reason + ")");
                    break;
                case GIVE_UP:
                    log("watchdog: gave up after " + sWd.attempts + " attempt(s) (" + sWd.reason
                            + "); it tries again after the phone connects again");
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            error("watchdog", t);
        } finally {
            wdSchedule(null);
        }
    }

    /** the phone of the last session is connected over Bluetooth (HFP) */
    static boolean phonePresent() {
        try {
            HfDeviceBTCMManager m = sBtcm;
            String addr = sPhone;
            BluetoothDevice d = m == null ? null : m.IbtcmGetHfpConnectedDevice();
            return d != null && addr != null && addr.equalsIgnoreCase(d.getAddress());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * the head unit's Wi-Fi is switched on as a client (e.g. for adb over Wi-Fi), whether or
     * not it has joined a network, and its hotspot is off: on this unit the two don't run together
     */
    static boolean wifiClientOn() {
        try {
            Object wifi = context.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return false;
            Class<?> k = wifi.getClass();
            boolean sta = (Boolean) k.getMethod("isWifiEnabled").invoke(wifi);
            boolean ap = false;
            try {
                ap = (Boolean) k.getMethod("isWifiApEnabled").invoke(wifi);
            } catch (Throwable t) {
                // no such call: treat as off
            }
            return sta && !ap;
        } catch (Throwable t) {
            return false;
        }
    }

    /** a Bluetooth address for the log: the last two bytes only */
    static String mask(String addr) {
        return addr == null || addr.length() < 5 ? String.valueOf(addr) : "XX:XX:XX:XX:" + addr.substring(addr.length() - 5);
    }

    /** a phone call is on (the car's or CarPlay's): no reconnecting then */
    static boolean inCall() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int mode = am.getMode();
            return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE;
        } catch (Throwable t) {
            return false;
        }
    }

    static void sendDetach() {
        handler().post(new Runnable() {
            @Override
            public void run() {
                try {
                    Context c = context;
                    if (c == null) return;
                    Intent i = new Intent(ACTION_DETACH);
                    i.setPackage(IAP2_PKG);
                    i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    c.sendBroadcast(i);
                    log("BT_DETACHED sent");
                } catch (Throwable t) {
                    error("sendDetach", t);
                }
            }
        });
    }

    /** SPP data from the phone: to the iAP2 server */
    static void onSppData(BluetoothDevice d, byte[] data, int len) {
        try {
            String addr = sAddr;
            IIap2Service s = sIap2;
            if (addr == null || s == null || d == null || data == null || len <= 0 || !sSession || !addr.equals(d.getAddress())) return;
            if (len >= data.length) len = data.length;
            if (len != data.length) {
                byte[] b = new byte[len];
                System.arraycopy(data, 0, b, 0, len);
                data = b;
            }
            s.Iap2SvcTransportReadDone(data, len);
        } catch (Throwable t) {
            error("onSppData", t);
        }
    }

    /** iAP2 bytes from the server for the Bluetooth session: out over SPP */
    static void writeSpp(byte[] data) {
        try {
            String addr = sAddr;
            HfDeviceBTCMManager m = sBtcm;
            if (addr == null || m == null || data == null || data.length == 0 || !sSession) return;
            if (!m.IbtcmSppOutPutStreamWrite(addr, data)) log("SPP write failed, " + data.length + " bytes");
        } catch (Throwable t) {
            error("writeSpp", t);
        }
    }

    /** the head unit's hotspot in mode 2 (for connectivity), which wireless CarPlay joins */
    static void ensureHotspot(Context c) {
        try {
            Object wifi = c.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return;
            Class<?> k = wifi.getClass();
            boolean on = false;
            int mode;
            try {
                on = (Boolean) k.getMethod("isWifiApEnabled").invoke(wifi);
            } catch (Throwable t) {
                // no such call: treat as off
            }
            try {
                mode = (Integer) k.getMethod("getWifiApMode").invoke(wifi);
            } catch (Throwable t) {
                mode = -1;
            }
            if (on && mode == 2) {
                log("hotspot already up (mode 2)");
                return;
            }
            Object conf = k.getMethod("getWifiApConfiguration").invoke(wifi);
            Object r = k.getMethod("setWifiApEnabledForConnectivity", WifiConfiguration.class, boolean.class).invoke(wifi, conf, true);
            log("setWifiApEnabledForConnectivity -> " + r + " (was on=" + on + " mode=" + mode + ")");
        } catch (Throwable t) {
            error("ensureHotspot", t);
        }
    }

    /** Broadcom's status callbacks: an iPhone connects (start SPP), SPP up/down, SPP data */
    static final class BtListener extends HfDeviceStatusListener {
        @Override
        public void onBTCMDeviceConnectionEventChanged(BluetoothDevice d, int event, boolean[] profiles) {
            if (event != EVENT_BTCM_CONNECTED) return;
            try {
                if (isApple(d)) {
                    log("iPhone BT connected: " + d.getAddress());
                    scheduleSppConnect(d.getAddress());
                    handler().post(new Runnable() {
                        @Override
                        public void run() {
                            boolean was = sWd.armed;
                            sWd.phoneConnected(now());
                            if (sWd.armed && !was) wdSchedule(sWd.reason);
                        }
                    });
                }
            } catch (Throwable t) {
                error("connEvent", t);
            }
        }

        @Override
        public void onBTCMSppStatusChanged(BluetoothDevice d, int status) {
            if (d == null) return;
            try {
                if (!isApple(d)) return;
                String addr = d.getAddress();
                log("SPP status " + status + " for " + addr);
                if (status == SPP_CONNECTED) onSppUp(addr);
                else if (status == SPP_DISCONNECTED) onSppDown(addr);
            } catch (Throwable t) {
                error("sppStatus", t);
            }
        }

        @Override
        public void onBTCMSppInPutStreamChanged(BluetoothDevice d, byte[] buffer, int length) {
            onSppData(d, buffer, length);
        }
    }

    /** the iAP2 server's side: USB and CarPlay-channel sessions, and transport data to send */
    static final class TxListener extends IIap2Listener.Stub {
        @Override
        public void connected(int component, String serial) {
            log("iAP2 connected comp=" + component);
            // what DiPO keeps as the current device (setCurrentIapDeviceName): a Bluetooth address is the notice's key
            if ((component == COMPONENT_USB || component == COMPONENT_BT_CARPLAY) && serial != null && serial.contains(":")) sLastBtId = serial;
            if (component == COMPONENT_USB) {
                sUsb = true;
                handler().post(new Runnable() {
                    @Override
                    public void run() {
                        sWd.stop();
                        wdSchedule(null);
                    }
                });
            }
            else if (component == COMPONENT_BT_CARPLAY) sReattach = false; // the old one's disconnect came first (always, seen)
            else if (component == COMPONENT_CARPLAY_CLIENT) onCpClientConnected();
        }

        @Override
        public void disconnected(int component) {
            log("iAP2 disconnected comp=" + component);
            if (component == COMPONENT_USB) {
                sUsb = false;
                onUsbGone();
            } else if (component == COMPONENT_BT_CARPLAY) {
                // after the watchdog's BT_ATTACHED the old session's disconnect comes after the new attach
                // (seen 1 s later): it isn't the new session's, which would then drop every iAP2 byte
                if (sReattach) {
                    sReattach = false;
                    log("iAP2: the old Bluetooth session's disconnect, the new one is kept");
                } else sSession = false;
            } else if (component == COMPONENT_CARPLAY_CLIENT) {
                sCpConnected = false;
            }
        }

        @Override
        public void notify(int what, int a1, int a2, Iap2NotiMsg msg) {
            if ((what != NOTI_BT_TRANSPORT_DATA && what != NOTI_CARPLAY_CLIENT_TRANSPORT_DATA) || msg == null) return;
            try {
                Iap2NotiMsg.Iap2ObjGroup g = msg.getItemGroup();
                if (g == null) return;
                for (int k = 0; k < g.getItemCnt(); k++) {
                    Iap2NotiMsg.Iap2ObjItem it = g.getObjItem(k);
                    if (it == null || it.getItemParamType() != OBJ_BLOB) continue;
                    if (what == NOTI_CARPLAY_CLIENT_TRANSPORT_DATA) writeCp(it.getItemByteArray());
                    else writeSpp(it.getItemByteArray());
                }
            } catch (Throwable t) {
                error("notify", t);
            }
        }
    }
}
