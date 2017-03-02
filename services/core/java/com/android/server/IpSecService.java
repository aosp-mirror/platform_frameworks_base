/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import static android.Manifest.permission.DUMP;

import android.content.Context;
import android.net.IIpSecService;
import android.net.INetd;
import android.net.util.NetdService;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/** @hide */
public class IpSecService extends IIpSecService.Stub {
    private static final String TAG = "IpSecService";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String NETD_SERVICE_NAME = "netd";

    /** Binder context for this service */
    private final Context mContext;

    private Object mLock = new Object();

    private static final int NETD_FETCH_TIMEOUT = 5000; //ms

    /**
     * Constructs a new IpSecService instance
     *
     * @param context Binder context for this service
     */
    private IpSecService(Context context) {
        mContext = context;
    }

    static IpSecService create(Context context) throws InterruptedException {
        final IpSecService service = new IpSecService(context);
        service.connectNativeNetdService();
        return service;
    }

    public void systemReady() {
        if (isNetdAlive()) {
            Slog.d(TAG, "IpSecService is ready");
        } else {
            Slog.wtf(TAG, "IpSecService not ready: failed to connect to NetD Native Service!");
        }
    }

    private void connectNativeNetdService() {
        // Avoid blocking the system server to do this
        Thread t =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                synchronized (mLock) {
                                    NetdService.get(NETD_FETCH_TIMEOUT);
                                }
                            }
                        });
        t.run();
    }

    INetd getNetdInstance() {
        final INetd netd = NetdService.getInstance();
        if (netd == null) {
            throw new RemoteException("Failed to Get Netd Instance").rethrowFromSystemServer();
        }
        return netd;
    }

    boolean isNetdAlive() {
        synchronized (mLock) {
            final INetd netd = getNetdInstance();
            if (netd == null) {
                return false;
            }

            try {
                return netd.isAlive();
            } catch (RemoteException re) {
                return false;
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(DUMP, TAG);

        pw.println("IpSecService Log:");
        pw.println("NetdNativeService Connection: " + (isNetdAlive() ? "alive" : "dead"));
        pw.println();
    }
}
