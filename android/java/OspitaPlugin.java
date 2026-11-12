package it.massxxx.massistanze;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Dalla pagina: Capacitor.Plugins.Ospita.avvia() / ferma() / stato() */
@CapacitorPlugin(name = "Ospita")
public class OspitaPlugin extends Plugin {

    private JSObject stato() {
        JSObject o = new JSObject();
        o.put("attivo", OspitaService.attivo);
        o.put("porta", OspitaService.PORTA);
        o.put("errore", OspitaService.errore);
        JSArray ip = new JSArray();
        for (String s : OspitaService.indirizzi()) ip.put(s);
        o.put("ip", ip);
        return o;
    }

    @PluginMethod
    public void avvia(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 71);
        }
        try {
            ContextCompat.startForegroundService(getContext(), new Intent(getContext(), OspitaService.class));
        } catch (Exception e) {
            call.reject("Android non permette di avviare il centro: " + e.getMessage());
            return;
        }
        for (int i = 0; i < 40 && !OspitaService.attivo && OspitaService.errore == null; i++) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        call.resolve(stato());
    }

    @PluginMethod
    public void ferma(PluginCall call) {
        getContext().stopService(new Intent(getContext(), OspitaService.class));
        for (int i = 0; i < 20 && OspitaService.attivo; i++) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        call.resolve(stato());
    }

    @PluginMethod
    public void stato(PluginCall call) { call.resolve(stato()); }
}
