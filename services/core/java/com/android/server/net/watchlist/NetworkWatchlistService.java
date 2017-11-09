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

package com.android.server.net.watchlist;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.IIpConnectivityMetrics;
import android.net.INetdEventCallback;
import android.net.NetworkWatchlistManager;
import android.net.metrics.IpConnectivityLog;
import android.os.Binder;
import android.os.Process;
import android.os.SharedMemory;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.net.INetworkWatchlistManager;
import com.android.server.ServiceThread;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/**
 * Implementation of network watchlist service.
 */
public class NetworkWatchlistService extends INetworkWatchlistManager.Stub {

    private static final String TAG = NetworkWatchlistService.class.getSimpleName();
    static final boolean DEBUG = false;

    private static final String PROPERTY_NETWORK_WATCHLIST_ENABLED =
            "ro.network_watchlist_enabled";

    private static final int MAX_NUM_OF_WATCHLIST_DIGESTS = 10000;

    public static class Lifecycle extends SystemService {
        private NetworkWatchlistService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            if (!SystemProperties.getBoolean(PROPERTY_NETWORK_WATCHLIST_ENABLED, false)) {
                // Watchlist service is disabled
                return;
            }
            mService = new NetworkWatchlistService(getContext());
            publishBinderService(Context.NETWORK_WATCHLIST_SERVICE, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (!SystemProperties.getBoolean(PROPERTY_NETWORK_WATCHLIST_ENABLED, false)) {
                // Watchlist service is disabled
                return;
            }
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                try {
                    mService.initIpConnectivityMetrics();
                    mService.startWatchlistLogging();
                } catch (RemoteException e) {
                    // Should not happen
                }
                ReportWatchlistJobService.schedule(getContext());
            }
        }
    }

    private volatile boolean mIsLoggingEnabled = false;
    private final Object mLoggingSwitchLock = new Object();

    private final WatchlistSettings mSettings;
    private final Context mContext;

    // Separate thread to handle expensive watchlist logging work.
    private final ServiceThread mHandlerThread;

    @VisibleForTesting
    IIpConnectivityMetrics mIpConnectivityMetrics;
    @VisibleForTesting
    WatchlistLoggingHandler mNetworkWatchlistHandler;

    public NetworkWatchlistService(Context context) {
        mContext = context;
        mSettings = WatchlistSettings.getInstance();
        mHandlerThread = new ServiceThread(TAG, Process.THREAD_PRIORITY_BACKGROUND,
                        /* allowIo */ false);
        mHandlerThread.start();
        mNetworkWatchlistHandler = new WatchlistLoggingHandler(mContext,
                mHandlerThread.getLooper());
        mNetworkWatchlistHandler.reportWatchlistIfNecessary();
    }

    // For testing only
    @VisibleForTesting
    NetworkWatchlistService(Context context, ServiceThread handlerThread,
            WatchlistLoggingHandler handler, IIpConnectivityMetrics ipConnectivityMetrics) {
        mContext = context;
        mSettings = WatchlistSettings.getInstance();
        mHandlerThread = handlerThread;
        mNetworkWatchlistHandler = handler;
        mIpConnectivityMetrics = ipConnectivityMetrics;
    }

    private void initIpConnectivityMetrics() {
        mIpConnectivityMetrics = (IIpConnectivityMetrics) IIpConnectivityMetrics.Stub.asInterface(
                ServiceManager.getService(IpConnectivityLog.SERVICE_NAME));
    }

    private final INetdEventCallback mNetdEventCallback = new INetdEventCallback.Stub() {
        @Override
        public void onDnsEvent(String hostname, String[] ipAddresses, int ipAddressesCount,
                long timestamp, int uid) {
            if (!mIsLoggingEnabled) {
                return;
            }
            mNetworkWatchlistHandler.asyncNetworkEvent(hostname, ipAddresses, uid);
        }

        @Override
        public void onConnectEvent(String ipAddr, int port, long timestamp, int uid) {
            if (!mIsLoggingEnabled) {
                return;
            }
            mNetworkWatchlistHandler.asyncNetworkEvent(null, new String[]{ipAddr}, uid);
        }
    };

    @VisibleForTesting
    protected boolean startWatchlistLoggingImpl() throws RemoteException {
        if (DEBUG) {
            Slog.i(TAG, "Starting watchlist logging.");
        }
        synchronized (mLoggingSwitchLock) {
            if (mIsLoggingEnabled) {
                Slog.w(TAG, "Watchlist logging is already running");
                return true;
            }
            try {
                if (mIpConnectivityMetrics.addNetdEventCallback(
                        INetdEventCallback.CALLBACK_CALLER_NETWORK_WATCHLIST, mNetdEventCallback)) {
                    mIsLoggingEnabled = true;
                    return true;
                } else {
                    return false;
                }
            } catch (RemoteException re) {
                // Should not happen
                return false;
            }
        }
    }

    @Override
    public boolean startWatchlistLogging() throws RemoteException {
        enforceWatchlistLoggingPermission();
        return startWatchlistLoggingImpl();
    }

    @VisibleForTesting
    protected boolean stopWatchlistLoggingImpl() {
        if (DEBUG) {
            Slog.i(TAG, "Stopping watchlist logging");
        }
        synchronized (mLoggingSwitchLock) {
            if (!mIsLoggingEnabled) {
                Slog.w(TAG, "Watchlist logging is not running");
                return true;
            }
            // stop the logging regardless of whether we fail to unregister listener
            mIsLoggingEnabled = false;

            try {
                return mIpConnectivityMetrics.removeNetdEventCallback(
                        INetdEventCallback.CALLBACK_CALLER_NETWORK_WATCHLIST);
            } catch (RemoteException re) {
                // Should not happen
                return false;
            }
        }
    }

    @Override
    public boolean stopWatchlistLogging() throws RemoteException {
        enforceWatchlistLoggingPermission();
        return stopWatchlistLoggingImpl();
    }

    private void enforceWatchlistLoggingPermission() {
        final int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID) {
            throw new SecurityException(String.format("Uid %d has no permission to change watchlist"
                    + " setting.", uid));
        }
    }

    /**
     * Set a new network watchlist.
     * This method should be called by ConfigUpdater only.
     *
     * @return True if network watchlist is updated.
     */
    public boolean setNetworkSecurityWatchlist(List<byte[]> domainsCrc32Digests,
            List<byte[]> domainsSha256Digests,
            List<byte[]> ipAddressesCrc32Digests,
            List<byte[]> ipAddressesSha256Digests) {
        Slog.i(TAG, "Setting network watchlist");
        if (domainsCrc32Digests == null || domainsSha256Digests == null
                || ipAddressesCrc32Digests == null || ipAddressesSha256Digests == null) {
            Slog.e(TAG, "Parameters cannot be null");
            return false;
        }
        if (domainsCrc32Digests.size() != domainsSha256Digests.size()
                || ipAddressesCrc32Digests.size() != ipAddressesSha256Digests.size()) {
            Slog.e(TAG, "Must need to have the same number of CRC32 and SHA256 digests");
            return false;
        }
        if (domainsSha256Digests.size() + ipAddressesSha256Digests.size()
                > MAX_NUM_OF_WATCHLIST_DIGESTS) {
            Slog.e(TAG, "Total watchlist size cannot exceed " + MAX_NUM_OF_WATCHLIST_DIGESTS);
            return false;
        }
        mSettings.writeSettingsToDisk(domainsCrc32Digests, domainsSha256Digests,
                ipAddressesCrc32Digests, ipAddressesSha256Digests);
        Slog.i(TAG, "Set network watchlist: Success");
        return true;
    }

    @Override
    public void reportWatchlistIfNecessary() {
        // Allow any apps to trigger report event, as we won't run it if it's too early.
        mNetworkWatchlistHandler.reportWatchlistIfNecessary();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        mSettings.dump(fd, pw, args);
    }

}
