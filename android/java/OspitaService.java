package it.massxxx.massistanze;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import it.massxxx.massistanze.centro.Centro;
import it.massxxx.massistanze.centro.Server;

/** Il telefono fa da centro: server sulla porta 8766, anche a schermo spento (notifica fissa). */
public class OspitaService extends Service {
    public static final int PORTA = 8766;
    static volatile Centro centro;
    static volatile Server server;
    static volatile String errore = null;
    static volatile boolean attivo = false;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "FERMA".equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        avviaInPrimoPiano();
        if (server == null) {
            new Thread(new Runnable() {
                public void run() { accendi(); }
            }, "ospita").start();
        }
        return START_STICKY;
    }

    private void avviaInPrimoPiano() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("ospita", "Telefono centro", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        int imm = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent apri = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent piApri = PendingIntent.getActivity(this, 0, apri, imm | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent ferma = new Intent(this, OspitaService.class).setAction("FERMA");
        PendingIntent piFerma = PendingIntent.getService(this, 1, ferma, imm | PendingIntent.FLAG_UPDATE_CURRENT);
        List<String> ip = indirizzi();
        Notification n = new NotificationCompat.Builder(this, "ospita")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("MassiStanze sta ospitando")
                .setContentText(ip.isEmpty() ? "Gli altri si collegano a questo telefono" : "Dagli altri telefoni: " + ip.get(0) + ":" + PORTA)
                .setOngoing(true)
                .setContentIntent(piApri)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Smetti di ospitare", piFerma)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1, n);
    }

    private synchronized void accendi() {
        if (server != null) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MassiStanze:centro");
            wake.acquire();
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MassiStanze:centro");
            wifi.acquire();
        } catch (Exception e) {
            // si va avanti lo stesso
        }
        try {
            final Context ctx = getApplicationContext();
            Centro c = new Centro(new File(getFilesDir(), "centro"), new DecoderAndroid(ctx), Build.MODEL);
            Server s = new Server(c, new Server.Risorse() {
                public byte[] leggi(String percorso) {
                    try (InputStream in = ctx.getAssets().open("public/" + percorso)) {
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        byte[] buf = new byte[16384];
                        int r;
                        while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
                        return b.toByteArray();
                    } catch (Exception e) {
                        return null;
                    }
                }
            }, PORTA, new Server.Azioni() {
                public List<String> indirizzi() { return OspitaService.indirizzi(); }
                public void chiudi() { ctx.startService(new Intent(ctx, OspitaService.class).setAction("FERMA")); }
            });
            s.avvia();
            centro = c;
            server = s;
            errore = null;
            attivo = true;
        } catch (Exception e) {
            errore = "Non riesco ad aprire la porta " + PORTA + ": " + e.getMessage();
            attivo = false;
        }
    }

    @Override
    public void onDestroy() {
        attivo = false;
        Server s = server;
        Centro c = centro;
        server = null;
        centro = null;
        if (s != null) s.ferma();
        if (c != null) c.spegni();
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception e) { /* niente */ }
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception e) { /* niente */ }
        super.onDestroy();
    }

    /** indirizzi di questo telefono sulla rete di casa (o sul suo hotspot) */
    static List<String> indirizzi() {
        List<String> wifi = new ArrayList<>(), altri = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase();
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("tun") || n.startsWith("dummy")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || !a.isSiteLocalAddress()) continue;
                    if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("eth")) wifi.add(a.getHostAddress());
                    else altri.add(a.getHostAddress());
                }
            }
        } catch (Exception e) {
            // niente
        }
        wifi.addAll(altri);
        return wifi;
    }
}
