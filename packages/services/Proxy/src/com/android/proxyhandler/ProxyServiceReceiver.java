package com.android.proxyhandler;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Proxy;
import android.os.Bundle;

public class ProxyServiceReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent service = new Intent(context, ProxyService.class);
        Bundle bundle = intent.getExtras();
        if (bundle != null) {
            service.putExtra(Proxy.EXTRA_PROXY_INFO,
                    bundle.getParcelable(Proxy.EXTRA_PROXY_INFO));
        }
        context.startService(service);
    }

}
