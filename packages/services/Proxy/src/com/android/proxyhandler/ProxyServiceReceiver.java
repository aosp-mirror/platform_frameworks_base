package com.android.proxyhandler;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Proxy;
import android.net.ProxyProperties;
import android.os.Bundle;
import android.text.TextUtils;

public class ProxyServiceReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent service = new Intent(context, ProxyService.class);
        Bundle bundle = intent.getExtras();
        ProxyProperties proxy = null;
        if (bundle != null) {
            proxy = bundle.getParcelable(Proxy.EXTRA_PROXY_INFO);
            service.putExtra(Proxy.EXTRA_PROXY_INFO, proxy);
        }
        if ((proxy != null) && (!TextUtils.isEmpty(proxy.getPacFileUrl()))) {
            context.startService(service);
        } else {
            context.stopService(service);
        }
    }

}
