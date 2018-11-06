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

import android.annotation.Nullable;
import android.content.Context;
import android.net.IIpConnectivityMetrics;
import android.net.INetdEventCallback;
import android.net.metrics.IpConnectivityLog;
import android.os.Binder;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.internal.net.INetworkWatchlistManager;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.net.BaseNetdEventCallback;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Implementation of network watchlist service.
 */
public class NetworkWatchlistService extends INetworkWatchlistManager.Stub {

    private static final String TAG = NetworkWatchlistService.class.getSimpleName();
    static final boolean DEBUG = false;

    private static final int MAX_NUM_OF_WATCHLIST_DIGESTS = 10000;

    public static class Lifecycle extends SystemService {
        private NetworkWatchlistService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            if (Settings.Global.getInt(getContext().getContentResolver(),
                    Settings.Global.NETWORK_WATCHLIST_ENABLED, 1) == 0) {
                // Watchlist service is disabled
                Slog.i(TAG, "Network Watchlist service is disabled");
                return;
            }
            mService = new NetworkWatchlistService(getContext());
            publishBinderService(Context.NETWORK_WATCHLIST_SERVICE, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                if (Settings.Global.getInt(getContext().getContentResolver(),
                        Settings.Global.NETWORK_WATCHLIST_ENABLED, 1) == 0) {
                    // Watchlist service is disabled
                    Slog.i(TAG, "Network Watchlist service is disabled");
                    return;
                }
                try {
                    mService.init();
                    mService.initIpConnectivityMetrics();
                    mService.startWatchlistLogging();
                } catch (RemoteException e) {
                    // Should not happen
                }
                ReportWatchlistJobService.schedule(getContext());
            }
        }
    }

    @GuardedBy("mLoggingSwitchLock")
    private volatile boolean mIsLoggingEnabled = false;
    private final Object mLoggingSwitchLock = new Object();

    private final WatchlistConfig mConfig;
    private final Context mContext;

    // Separate thread to handle expensive watchlist logging work.
    private final ServiceThread mHandlerThread;

    @VisibleForTesting
    IIpConnectivityMetrics mIpConnectivityMetrics;
    @VisibleForTesting
    WatchlistLoggingHandler mNetworkWatchlistHandler;

    public NetworkWatchlistService(Context context) {
        mContext = context;
        mConfig = WatchlistConfig.getInstance();
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
        mConfig = WatchlistConfig.getInstance();
        mHandlerThread = handlerThread;
        mNetworkWatchlistHandler = handler;
        mIpConnectivityMetrics = ipConnectivityMetrics;
    }

    private void init() {
        mConfig.removeTestModeConfig();
    }

    private void initIpConnectivityMetrics() {
        mIpConnectivityMetrics = (IIpConnectivityMetrics) IIpConnectivityMetrics.Stub.asInterface(
                ServiceManager.getService(IpConnectivityLog.SERVICE_NAME));
    }

    private final INetdEventCallback mNetdEventCallback = new BaseNetdEventCallback() {
        @Override
        public void onDnsEvent(int netId, int eventType, int returnCode, String hostname,
                String[] ipAddresses, int ipAddressesCount, long timestamp, int uid) {
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

    private boolean isCallerShell() {
        final int callingUid = Binder.getCallingUid();
        return callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID;
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        if (!isCallerShell()) {
            Slog.w(TAG, "Only shell is allowed to call network watchlist shell commands");
            return;
        }
        (new NetworkWatchlistShellCommand(this, mContext)).exec(this, in, out, err, args, callback,
                resultReceiver);
    }

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

    @Nullable
    @Override
    public byte[] getWatchlistConfigHash() {
        return mConfig.getWatchlistConfigHash();
    }

    private void enforceWatchlistLoggingPermission() {
        final int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID) {
            throw new SecurityException(String.format("Uid %d has no permission to change watchlist"
                    + " setting.", uid));
        }
    }

    @Override
    public void reloadWatchlist() throws RemoteException {
        enforceWatchlistLoggingPermission();
        Slog.i(TAG, "Reloading watchlist");
        mConfig.reloadConfig();
    }

    @Override
    public void reportWatchlistIfNecessary() {
        // Allow any apps to trigger report event, as we won't run it if it's too early.
        mNetworkWatchlistHandler.reportWatchlistIfNecessary();
    }

    /**
     * Force generate watchlist report for testing.
     *
     * @param lastReportTime Watchlist report will cotain all records before this time.
     * @return True if operation success.
     */
    public boolean forceReportWatchlistForTest(long lastReportTime) {
        if (mConfig.isConfigSecure()) {
            // Should not force generate report under production config.
            return false;
        }
        mNetworkWatchlistHandler.forceReportWatchlistForTest(lastReportTime);
        return true;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        mConfig.dump(fd, pw, args);
    }

}
