/*
 * Copyright (C) 2009, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.vpn;

import android.app.Service;
import android.content.Intent;
import android.net.vpn.IVpnService;
import android.net.vpn.L2tpIpsecProfile;
import android.net.vpn.L2tpIpsecPskProfile;
import android.net.vpn.L2tpProfile;
import android.net.vpn.PptpProfile;
import android.net.vpn.VpnManager;
import android.net.vpn.VpnProfile;
import android.net.vpn.VpnState;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;

/**
 * The service class for managing a VPN connection. It implements the
 * {@link IVpnService} binder interface.
 */
public class VpnServiceBinder extends Service {
    private final String TAG = VpnServiceBinder.class.getSimpleName();

    // The actual implementation is delegated to the VpnService class.
    private VpnService<? extends VpnProfile> mService;

    private final IBinder mBinder = new IVpnService.Stub() {
        public boolean connect(VpnProfile p, String username, String password) {
            return VpnServiceBinder.this.connect(p, username, password);
        }

        public void disconnect() {
            if (mService != null) mService.onDisconnect(true);
        }

        public void checkStatus(VpnProfile p) {
            VpnServiceBinder.this.checkStatus(p);
        }
    };

    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private synchronized boolean connect(
            VpnProfile p, String username, String password) {
        if (mService != null) return false;
        try {
            mService = createService(p);
            mService.onConnect(username, password);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "connect()", e);
            if (mService != null) mService.onError();
            return false;
        }
    }

    private synchronized void checkStatus(VpnProfile p) {
        if (mService == null) broadcastConnectivity(p.getName(), VpnState.IDLE);

        if (!p.getName().equals(mService.mProfile.getName())) {
            broadcastConnectivity(p.getName(), VpnState.IDLE);
        } else {
            broadcastConnectivity(p.getName(), mService.getState());
        }
    }

    private VpnService<? extends VpnProfile> createService(VpnProfile p) {
        switch (p.getType()) {
            case L2TP:
                L2tpService l2tp = new L2tpService();
                l2tp.setContext(this, (L2tpProfile) p);
                return l2tp;

            case PPTP:
                PptpService pptp = new PptpService();
                pptp.setContext(this, (PptpProfile) p);
                return pptp;

            case L2TP_IPSEC_PSK:
                L2tpIpsecPskService psk = new L2tpIpsecPskService();
                psk.setContext(this, (L2tpIpsecPskProfile) p);
                return psk;

            case L2TP_IPSEC:
                L2tpIpsecService l2tpIpsec = new L2tpIpsecService();
                l2tpIpsec.setContext(this, (L2tpIpsecProfile) p);
                return l2tpIpsec;

            default:
                return null;
        }
    }

    private void broadcastConnectivity(String name, VpnState s) {
        new VpnManager(this).broadcastConnectivity(name, s);
    }
}
