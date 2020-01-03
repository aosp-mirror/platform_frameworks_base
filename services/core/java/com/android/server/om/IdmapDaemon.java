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

import static com.android.server.om.OverlayManagerService.DEBUG;
import static com.android.server.om.OverlayManagerService.TAG;

import android.os.IBinder;
import android.os.IIdmap2;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.server.FgThread;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
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

    private static final Object IDMAP_TOKEN = new Object();
    private static final String IDMAP_DAEMON = "idmap2d";

    private static IdmapDaemon sInstance;
    private volatile IIdmap2 mService;
    private final AtomicInteger mOpenedCount = new AtomicInteger();

    /**
     * An {@link AutoCloseable} connection to the idmap service. When the connection is closed or
     * finalized, the idmap service will be stopped after a period of time unless another connection
     * to the service is open.
     **/
    private class Connection implements AutoCloseable {
        private boolean mOpened = true;

        private Connection() {
            synchronized (IDMAP_TOKEN) {
                mOpenedCount.incrementAndGet();
            }
        }

        @Override
        public void close() {
            synchronized (IDMAP_TOKEN) {
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
                    synchronized (IDMAP_TOKEN) {
                        // Only stop the service if the service does not have an open connection.
                        if (mService == null || mOpenedCount.get() != 0) {
                            return;
                        }

                        stopIdmapService();
                        mService = null;
                    }
                }, IDMAP_TOKEN, SERVICE_TIMEOUT_MS);
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
            int userId) throws Exception {
        try (Connection connection = connect()) {
            return mService.createIdmap(targetPath, overlayPath, policies, enforce, userId);
        }
    }

    boolean removeIdmap(String overlayPath, int userId) throws Exception {
        try (Connection connection = connect()) {
            return mService.removeIdmap(overlayPath, userId);
        }
    }

    boolean verifyIdmap(String overlayPath, int policies, boolean enforce, int userId)
            throws Exception {
        try (Connection connection = connect()) {
            return mService.verifyIdmap(overlayPath, policies, enforce, userId);
        }
    }

    String getIdmapPath(String overlayPath, int userId) throws Exception {
        try (Connection connection = connect()) {
            return mService.getIdmapPath(overlayPath, userId);
        }
    }

    private static void startIdmapService() {
        SystemProperties.set("ctl.start", IDMAP_DAEMON);
    }

    private static void stopIdmapService() {
        SystemProperties.set("ctl.stop", IDMAP_DAEMON);
    }

    private Connection connect() throws Exception {
        synchronized (IDMAP_TOKEN) {
            FgThread.getHandler().removeCallbacksAndMessages(IDMAP_TOKEN);
            if (mService != null) {
                // Not enough time has passed to stop the idmap service. Reuse the existing
                // interface.
                return new Connection();
            }

            // Start the idmap service if it is not currently running.
            startIdmapService();

            // Block until the service is found.
            FutureTask<IBinder> bindIdmap = new FutureTask<>(() -> {
                while (true) {
                    try {
                        IBinder binder = ServiceManager.getService(IDMAP_SERVICE);
                        if (binder != null) {
                            return binder;
                        }
                    } catch (Exception e) {
                        Slog.e(TAG, "service '" + IDMAP_SERVICE + "' not retrieved; "
                                + e.getMessage());
                    }
                    Thread.sleep(100);
                }
            });

            IBinder binder;
            try {
                FgThread.getHandler().postAtFrontOfQueue(bindIdmap);
                binder = bindIdmap.get(SERVICE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception rethrow) {
                Slog.e(TAG, "service '" + IDMAP_SERVICE + "' not found;");
                throw rethrow;
            }

            try {
                binder.linkToDeath(() -> {
                    Slog.w(TAG, "service '" + IDMAP_SERVICE + "' died");
                }, 0);
            } catch (RemoteException rethrow) {
                Slog.e(TAG, "service '" + IDMAP_SERVICE + "' failed to be bound");
                throw rethrow;
            }

            mService = IIdmap2.Stub.asInterface(binder);
            if (DEBUG) {
                Slog.d(TAG, "service '" + IDMAP_SERVICE + "' connected");
            }

            return new Connection();
        }
    }
}
