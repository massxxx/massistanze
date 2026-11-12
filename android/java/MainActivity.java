package it.massxxx.massistanze;

import com.getcapacitor.BridgeActivity;

// La musica continua anche con lo schermo spento o con un'altra app aperta:
// quando Android mette in pausa l'app, la pagina viene subito rimessa in funzione.
public class MainActivity extends BridgeActivity {
    @Override
    public void onPause() {
        super.onPause();
        try {
            if (bridge != null && bridge.getWebView() != null) {
                bridge.getWebView().onResume();
                bridge.getWebView().resumeTimers();
            }
        } catch (Exception e) {
            // niente
        }
    }
}
