package com.android.proxyhandler;

import android.app.Service;
import android.content.Intent;
import android.net.Proxy;
import android.net.ProxyProperties;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;

/**
 * @hide
 */
public class ProxyService extends Service {

    private static ProxyServer server = null;

    /** Keep these values up-to-date with PacManager.java */
    public static final String KEY_PROXY = "keyProxy";
    public static final String HOST = "localhost";
    public static final int PORT = 8182;
    public static final String EXCL_LIST = "";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            handleCommand(intent);
        }
        return START_STICKY;
    }

    private void handleCommand(Intent intent) {
        Bundle bundle = intent.getExtras();
        ProxyProperties proxy = null;
        if ((bundle != null) && bundle.containsKey(Proxy.EXTRA_PROXY_INFO)) {
            proxy = bundle.getParcelable(Proxy.EXTRA_PROXY_INFO);
            if ((proxy != null) && !TextUtils.isEmpty(proxy.getPacFileUrl())) {
                startProxy(proxy);
            } else {
                stopSelf();
            }
        } else {
            stopSelf();
        }
    }


    private void startProxy(ProxyProperties proxy) {
        if (server == null) {
            server = new ProxyServer();
            server.startServer();
        }
        server.setProxy(proxy);
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stopServer();
            server = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}