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
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemProperties;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * The service class for managing a VPN connection. It implements the
 * {@link IVpnService} binder interface.
 */
public class VpnServiceBinder extends Service {
    private static final String TAG = VpnServiceBinder.class.getSimpleName();
    private static final boolean DBG = true;

    private static final String STATES_FILE_RELATIVE_PATH = "/misc/vpn/.states";

    // The actual implementation is delegated to the VpnService class.
    private VpnService<? extends VpnProfile> mService;

    // TODO(oam): Test VPN when EFS is enabled (will do later)...
    private static String getStateFilePath() {
        // This call will return the correcu directory whether Encrypted FS is enabled or not
        // Disabled: /data/misc/vpn/.states   Enabled: /data/secure/misc/vpn/.states
	return Environment.getSecureDataDirectory().getPath() + STATES_FILE_RELATIVE_PATH;
    }

    private final IBinder mBinder = new IVpnService.Stub() {
        public boolean connect(VpnProfile p, String username, String password) {
            return VpnServiceBinder.this.connect(p, username, password);
        }

        public void disconnect() {
            VpnServiceBinder.this.disconnect();
        }

        public void checkStatus(VpnProfile p) {
            VpnServiceBinder.this.checkStatus(p);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        checkSavedStates();
    }


    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    void saveStates() throws IOException {
        if (DBG) Log.d("VpnServiceBinder", "     saving states");
        ObjectOutputStream oos =
                new ObjectOutputStream(new FileOutputStream(getStateFilePath()));
        oos.writeObject(mService);
        oos.close();
    }

    void removeStates() {
        try {
            File f = new File(getStateFilePath());
            if (f.exists()) f.delete();
        } catch (Throwable e) {
            if (DBG) Log.d("VpnServiceBinder", "     remove states: " + e);
        }
    }

    private synchronized boolean connect(final VpnProfile p,
            final String username, final String password) {
        if (mService != null) return false;
        final VpnService s = mService = createService(p);

        new Thread(new Runnable() {
            public void run() {
                s.onConnect(username, password);
            }
        }).start();
        return true;
    }

    private synchronized void disconnect() {
        if (mService == null) return;
        final VpnService s = mService;

        new Thread(new Runnable() {
            public void run() {
                s.onDisconnect();
            }
        }).start();
    }

    private synchronized void checkStatus(VpnProfile p) {
        if ((mService == null)
                || (!p.getName().equals(mService.mProfile.getName()))) {
            broadcastConnectivity(p.getName(), VpnState.IDLE);
        } else {
            broadcastConnectivity(p.getName(), mService.getState());
        }
    }

    private void checkSavedStates() {
        try {
            ObjectInputStream ois = new ObjectInputStream(new FileInputStream(
                    getStateFilePath()));
            mService = (VpnService<? extends VpnProfile>) ois.readObject();
            mService.recover(this);
            ois.close();
        } catch (FileNotFoundException e) {
            // do nothing
        } catch (Throwable e) {
            Log.i("VpnServiceBinder", "recovery error, remove states: " + e);
            removeStates();
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
