/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.om;

import static android.content.Context.IDMAP_SERVICE;

import static com.android.server.om.OverlayManagerService.TAG;

import android.os.IBinder;
import android.os.IIdmap2;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemService;
import android.util.Slog;

import com.android.server.FgThread;

import java.io.File;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * To prevent idmap2d from continuously running, the idmap daemon will terminate after 10
 * seconds without a transaction.
 **/
class IdmapDaemon {
    // The amount of time in milliseconds to wait after a transaction to the idmap service is made
    // before stopping the service.
    private static final int SERVICE_TIMEOUT_MS = 10000;

    // The amount of time in milliseconds to wait when attempting to connect to idmap service.
    private static final int SERVICE_CONNECT_TIMEOUT_MS = 5000;
    private static final int SERVICE_CONNECT_INTERVAL_SLEEP_MS = 5;

    private static final String IDMAP_DAEMON = "idmap2d";

    private static IdmapDaemon sInstance;
    private volatile IIdmap2 mService;
    private final AtomicInteger mOpenedCount = new AtomicInteger();
    private final Object mIdmapToken = new Object();

    /**
     * An {@link AutoCloseable} connection to the idmap service. When the connection is closed or
     * finalized, the idmap service will be stopped after a period of time unless another connection
     * to the service is open.
     **/
    private class Connection implements AutoCloseable {
        private boolean mOpened = true;

        private Connection() {
            synchronized (mIdmapToken) {
                mOpenedCount.incrementAndGet();
            }
        }

        @Override
        public void close() {
            synchronized (mIdmapToken) {
                if (!mOpened) {
                    return;
                }

                mOpened = false;
                if (mOpenedCount.decrementAndGet() != 0) {
                    // Only post the callback to stop the service if the service does not have an
                    // open connection.
                    return;
                }

                FgThread.getHandler().postDelayed(() -> {
                    synchronized (mIdmapToken) {
                        // Only stop the service if the service does not have an open connection.
                        if (mService == null || mOpenedCount.get() != 0) {
                            return;
                        }

                        stopIdmapService();
                        mService = null;
                    }
                }, mIdmapToken, SERVICE_TIMEOUT_MS);
            }
        }
    }

    static IdmapDaemon getInstance() {
        if (sInstance == null) {
            sInstance = new IdmapDaemon();
        }
        return sInstance;
    }

    String createIdmap(String targetPath, String overlayPath, int policies, boolean enforce,
            int userId) throws TimeoutException, RemoteException {
        try (Connection c = connect()) {
            return mService.createIdmap(targetPath, overlayPath, policies, enforce, userId);
        }
    }

    boolean removeIdmap(String overlayPath, int userId) throws TimeoutException, RemoteException {
        try (Connection c = connect()) {
            return mService.removeIdmap(overlayPath, userId);
        }
    }

    boolean verifyIdmap(String targetPath, String overlayPath, int policies, boolean enforce,
             int userId)
            throws Exception {
        try (Connection c = connect()) {
            return mService.verifyIdmap(targetPath, overlayPath, policies, enforce, userId);
        }
    }

    boolean idmapExists(String overlayPath, int userId) {
        try (Connection c = connect()) {
            return new File(mService.getIdmapPath(overlayPath, userId)).isFile();
        } catch (Exception e) {
            Slog.wtf(TAG, "failed to check if idmap exists for " + overlayPath + ": "
                    + e.getMessage());
            return false;
        }
    }

    private IBinder getIdmapService() throws TimeoutException, RemoteException {
        SystemService.start(IDMAP_DAEMON);

        final long endMillis = SystemClock.elapsedRealtime() + SERVICE_CONNECT_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() <= endMillis) {
            final IBinder binder = ServiceManager.getService(IDMAP_SERVICE);
            if (binder != null) {
                binder.linkToDeath(
                        () -> Slog.w(TAG, String.format("service '%s' died", IDMAP_SERVICE)), 0);
                return binder;
            }

            try {
                Thread.sleep(SERVICE_CONNECT_INTERVAL_SLEEP_MS);
            } catch (InterruptedException ignored) {
            }
        }

        throw new TimeoutException(
            String.format("Failed to connect to '%s' in %d milliseconds", IDMAP_SERVICE,
                    SERVICE_CONNECT_TIMEOUT_MS));
    }

    private static void stopIdmapService() {
        SystemService.stop(IDMAP_DAEMON);
    }

    private Connection connect() throws TimeoutException, RemoteException {
        synchronized (mIdmapToken) {
            FgThread.getHandler().removeCallbacksAndMessages(mIdmapToken);
            if (mService != null) {
                // Not enough time has passed to stop the idmap service. Reuse the existing
                // interface.
                return new Connection();
            }

            mService = IIdmap2.Stub.asInterface(getIdmapService());
            return new Connection();
        }
    }
}
