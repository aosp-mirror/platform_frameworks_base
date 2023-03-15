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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.FabricatedOverlayInfo;
import android.os.FabricatedOverlayInternal;
import android.os.IBinder;
import android.os.IIdmap2;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemService;
import android.text.TextUtils;
import android.util.Slog;

import com.android.server.FgThread;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * To prevent idmap2d from continuously running, the idmap daemon will terminate after 10 seconds
 * without a transaction.
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
        @Nullable
        private final IIdmap2 mIdmap2;
        private boolean mOpened = true;

        private Connection(IIdmap2 idmap2) {
            synchronized (mIdmapToken) {
                mOpenedCount.incrementAndGet();
                mIdmap2 = idmap2;
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

        @Nullable
        public IIdmap2 getIdmap2() {
            return mIdmap2;
        }
    }

    static IdmapDaemon getInstance() {
        if (sInstance == null) {
            sInstance = new IdmapDaemon();
        }
        return sInstance;
    }

    String createIdmap(@NonNull String targetPath, @NonNull String overlayPath,
            @Nullable String overlayName, int policies, boolean enforce, int userId)
            throws TimeoutException, RemoteException {
        try (Connection c = connect()) {
            final IIdmap2 idmap2 = c.getIdmap2();
            if (idmap2 == null) {
                Slog.w(TAG, "idmap2d service is not ready for createIdmap(\"" + targetPath
                        + "\", \"" + overlayPath + "\", \"" + overlayName + "\", " + policies + ", "
                        + enforce + ", " + userId + ")");
                return null;
            }

            return idmap2.createIdmap(targetPath, overlayPath, TextUtils.emptyIfNull(overlayName),
                    policies, enforce, userId);
        }
    }

    boolean removeIdmap(String overlayPath, int userId) throws TimeoutException, RemoteException {
        try (Connection c = connect()) {
            final IIdmap2 idmap2 = c.getIdmap2();
            if (idmap2 == null) {
                Slog.w(TAG, "idmap2d service is not ready for removeIdmap(\"" + overlayPath
                        + "\", " + userId + ")");
                return false;
            }

            return idmap2.removeIdmap(overlayPath, userId);
        }
    }

    boolean verifyIdmap(@NonNull String targetPath, @NonNull String overlayPath,
            @Nullable String overlayName, int policies, boolean enforce, int userId)
            throws Exception {
        try (Connection c = connect()) {
            final IIdmap2 idmap2 = c.getIdmap2();
            if (idmap2 == null) {
                Slog.w(TAG, "idmap2d service is not ready for verifyIdmap(\"" + targetPath
                        + "\", \"" + overlayPath + "\", \"" + overlayName + "\", " + policies + ", "
                        + enforce + ", " + userId + ")");
                return false;
            }

            return idmap2.verifyIdmap(targetPath, overlayPath, TextUtils.emptyIfNull(overlayName),
                    policies, enforce, userId);
        }
    }

    boolean idmapExists(String overlayPath, int userId) {
        try (Connection c = connect()) {
            final IIdmap2 idmap2 = c.getIdmap2();
            if (idmap2 == null) {
                Slog.w(TAG, "idmap2d service is not ready for idmapExists(\"" + overlayPath
                        + "\", " + userId + ")");
                return false;
            }

            return new File(idmap2.getIdmapPath(overlayPath, userId)).isFile();
        } catch (Exception e) {
            Slog.wtf(TAG, "failed to check if idmap exists for " + overlayPath, e);
            return false;
        }
    }

    FabricatedOverlayInfo createFabricatedOverlay(@NonNull FabricatedOverlayInternal overlay) {
        try (Connection c = connect()) {
            final IIdmap2 idmap2 = c.getIdmap2();
            if (idmap2 == null) {
                Slog.w(TAG, "idmap2d service is not ready for createFabricatedOverlay()");
                return null;
            }

            return idmap2.createFabricatedOverlay(overlay);
        } catch (Exception e) {
            Slog.wtf(TAG, "failed to fabricate overlay " + overlay, e);
            return null;
        }
    }

    boolean deleteFabricatedOverlay(@NonNull String path) {
        try (Connection c = connect()) {
            final IIdmap2 idmap2 = c.getIdmap2();
            if (idmap2 == null) {
                Slog.w(TAG, "idmap2d service is not ready for deleteFabricatedOverlay(\"" + path
                        + "\")");
                return false;
            }

            return idmap2.deleteFabricatedOverlay(path);
        } catch (Exception e) {
            Slog.wtf(TAG, "failed to delete fabricated overlay '" + path + "'", e);
            return false;
        }
    }

    synchronized List<FabricatedOverlayInfo> getFabricatedOverlayInfos() {
        final ArrayList<FabricatedOverlayInfo> allInfos = new ArrayList<>();
        Connection c = null;
        int iteratorId = -1;
        try {
            c = connect();
            final IIdmap2 service = c.getIdmap2();
            if (service == null) {
                Slog.w(TAG, "idmap2d service is not ready for getFabricatedOverlayInfos()");
                return Collections.emptyList();
            }

            iteratorId = service.acquireFabricatedOverlayIterator();
            List<FabricatedOverlayInfo> infos;
            while (!(infos = service.nextFabricatedOverlayInfos(iteratorId)).isEmpty()) {
                allInfos.addAll(infos);
            }
            return allInfos;
        } catch (Exception e) {
            Slog.wtf(TAG, "failed to get all fabricated overlays", e);
        } finally {
            try {
                if (c.getIdmap2() != null && iteratorId != -1) {
                    c.getIdmap2().releaseFabricatedOverlayIterator(iteratorId);
                }
            } catch (RemoteException e) {
                // ignore
            }
            c.close();
        }
        return allInfos;
    }

    String dumpIdmap(@NonNull String overlayPath) {
        try (Connection c = connect()) {
            final IIdmap2 service = c.getIdmap2();
            if (service == null) {
                final String dumpText = "idmap2d service is not ready for dumpIdmap()";
                Slog.w(TAG, dumpText);
                return dumpText;
            }
            String dump = service.dumpIdmap(overlayPath);
            return TextUtils.nullIfEmpty(dump);
        } catch (Exception e) {
            Slog.wtf(TAG, "failed to dump idmap", e);
            return null;
        }
    }

    @Nullable
    private IBinder getIdmapService() throws TimeoutException, RemoteException {
        try {
            SystemService.start(IDMAP_DAEMON);
        } catch (RuntimeException e) {
            if (e.getMessage().contains("failed to set system property")) {
                Slog.w(TAG, "Failed to enable idmap2 daemon", e);
                return null;
            }
        }

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
        try {
            SystemService.stop(IDMAP_DAEMON);
        } catch (RuntimeException e) {
            // If the idmap daemon cannot be disabled for some reason, it is okay
            // since we already finished invoking idmap.
            Slog.w(TAG, "Failed to disable idmap2 daemon", e);
        }
    }

    @NonNull
    private Connection connect() throws TimeoutException, RemoteException {
        synchronized (mIdmapToken) {
            FgThread.getHandler().removeCallbacksAndMessages(mIdmapToken);
            if (mService != null) {
                // Not enough time has passed to stop the idmap service. Reuse the existing
                // interface.
                return new Connection(mService);
            }

            IBinder binder = getIdmapService();
            if (binder == null) {
                return new Connection(null);
            }

            mService = IIdmap2.Stub.asInterface(binder);
            return new Connection(mService);
        }
    }
}
