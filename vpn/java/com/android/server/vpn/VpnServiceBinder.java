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
import android.content.Context;
import android.content.Intent;
import android.net.vpn.IVpnService;
import android.net.vpn.L2tpIpsecProfile;
import android.net.vpn.L2tpIpsecPskProfile;
import android.net.vpn.L2tpProfile;
import android.net.vpn.PptpProfile;
import android.net.vpn.VpnManager;
import android.net.vpn.VpnProfile;
import android.net.vpn.VpnState;
import android.util.Log;

/**
 * The service class for managing a VPN connection. It implements the
 * {@link IVpnService} binder interface.
 */
public class VpnServiceBinder extends IVpnService.Stub {
    private static final String TAG = VpnServiceBinder.class.getSimpleName();
    private static final boolean DBG = true;

    // The actual implementation is delegated to the VpnService class.
    private VpnService<? extends VpnProfile> mService;

    private Context mContext;

    public VpnServiceBinder(Context context) {
        mContext = context;
    }

    @Override
    public synchronized boolean connect(VpnProfile p, final String username,
            final String password) {
        if ((mService != null) && !mService.isIdle()) return false;
        final VpnService s = mService = createService(p);

        new Thread(new Runnable() {
            public void run() {
                s.onConnect(username, password);
            }
        }).start();
        return true;
    }

    @Override
    public synchronized void disconnect() {
        if (mService == null) return;
        final VpnService s = mService;
        mService = null;

        new Thread(new Runnable() {
            public void run() {
                s.onDisconnect();
            }
        }).start();
    }

    @Override
    public synchronized String getState(VpnProfile p) {
        if ((mService == null)
                || (!p.getName().equals(mService.mProfile.getName()))) {
            return VpnState.IDLE.toString();
        } else {
            return mService.getState().toString();
        }
    }

    @Override
    public synchronized boolean isIdle() {
        return (mService == null || mService.isIdle());
    }

    private VpnService<? extends VpnProfile> createService(VpnProfile p) {
        switch (p.getType()) {
            case L2TP:
                L2tpService l2tp = new L2tpService();
                l2tp.setContext(mContext, (L2tpProfile) p);
                return l2tp;

            case PPTP:
                PptpService pptp = new PptpService();
                pptp.setContext(mContext, (PptpProfile) p);
                return pptp;

            case L2TP_IPSEC_PSK:
                L2tpIpsecPskService psk = new L2tpIpsecPskService();
                psk.setContext(mContext, (L2tpIpsecPskProfile) p);
                return psk;

            case L2TP_IPSEC:
                L2tpIpsecService l2tpIpsec = new L2tpIpsecService();
                l2tpIpsec.setContext(mContext, (L2tpIpsecProfile) p);
                return l2tpIpsec;

            default:
                return null;
        }
    }
}
