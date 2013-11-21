/**
 * Copyright (c) 2013, The Android Open Source Project
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
package com.android.proxyhandler;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.net.IProxyCallback;
import com.android.net.IProxyPortListener;

/**
 * @hide
 */
public class ProxyService extends Service {

    private static ProxyServer server = null;

    /** Keep these values up-to-date with PacManager.java */
    public static final String KEY_PROXY = "keyProxy";
    public static final String HOST = "localhost";
    // STOPSHIP This being a static port means it can be hijacked by other apps.
    public static final int PORT = 8182;
    public static final String EXCL_LIST = "";

    @Override
    public void onCreate() {
        super.onCreate();
        if (server == null) {
            server = new ProxyServer();
            server.startServer();
        }
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
        return new IProxyCallback.Stub() {
            @Override
            public void getProxyPort(IBinder callback) throws RemoteException {
                if (server != null) {
                    IProxyPortListener portListener = IProxyPortListener.Stub.asInterface(callback);
                    if (portListener != null) {
                        server.setCallback(portListener);
                    }
                }
            }
        };
    }
}