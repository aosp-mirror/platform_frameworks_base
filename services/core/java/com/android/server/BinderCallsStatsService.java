/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server;

import android.app.AppGlobals;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.KeyValueListParser;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;
import com.android.internal.os.BinderCallsStats;
import com.android.internal.os.CachedDeviceState;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BinderCallsStatsService extends Binder {

    private static final String TAG = "BinderCallsStatsService";

    private static final String PERSIST_SYS_BINDER_CALLS_DETAILED_TRACKING
            = "persist.sys.binder_calls_detailed_tracking";


    /** Listens for flag changes. */
    private static class SettingsObserver extends ContentObserver {
        private static final String SETTINGS_ENABLED_KEY = "enabled";
        private static final String SETTINGS_DETAILED_TRACKING_KEY = "detailed_tracking";
        private static final String SETTINGS_UPLOAD_DATA_KEY = "upload_data";
        private static final String SETTINGS_SAMPLING_INTERVAL_KEY = "sampling_interval";

        private boolean mEnabled;
        private final Uri mUri = Settings.Global.getUriFor(Settings.Global.BINDER_CALLS_STATS);
        private final Context mContext;
        private final KeyValueListParser mParser = new KeyValueListParser(',');
        private final BinderCallsStats mBinderCallsStats;

        public SettingsObserver(Context context, BinderCallsStats binderCallsStats) {
            super(BackgroundThread.getHandler());
            mContext = context;
            context.getContentResolver().registerContentObserver(mUri, false, this,
                    UserHandle.USER_SYSTEM);
            mBinderCallsStats = binderCallsStats;
            // Always kick once to ensure that we match current state
            onChange();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (mUri.equals(uri)) {
                onChange();
            }
        }

        public void onChange() {
            // Do not overwrite the default set manually.
            if (!SystemProperties.get(PERSIST_SYS_BINDER_CALLS_DETAILED_TRACKING).isEmpty()) {
              return;
            }

            try {
                    mParser.setString(Settings.Global.getString(mContext.getContentResolver(),
                            Settings.Global.BINDER_CALLS_STATS));
            } catch (IllegalArgumentException e) {
                    Slog.e(TAG, "Bad binder call stats settings", e);
            }
            mBinderCallsStats.setDetailedTracking(mParser.getBoolean(
                    SETTINGS_DETAILED_TRACKING_KEY, BinderCallsStats.DETAILED_TRACKING_DEFAULT));
            mBinderCallsStats.setSamplingInterval(mParser.getInt(
                    SETTINGS_SAMPLING_INTERVAL_KEY,
                    BinderCallsStats.PERIODIC_SAMPLING_INTERVAL_DEFAULT));


            final boolean enabled =
                    mParser.getBoolean(SETTINGS_ENABLED_KEY, BinderCallsStats.ENABLED_DEFAULT);
            if (mEnabled != enabled) {
                if (enabled) {
                    Binder.setObserver(mBinderCallsStats);
                    Binder.setProxyTransactListener(
                            new Binder.PropagateWorkSourceTransactListener());
                } else {
                    Binder.setObserver(null);
                    Binder.setProxyTransactListener(null);
                }
                mEnabled = enabled;
                mBinderCallsStats.reset();
            }
        }
    }

    /**
     * @hide Only for use within the system server.
     */
    public static class Internal {
        private final BinderCallsStats mBinderCallsStats;

        Internal(BinderCallsStats binderCallsStats) {
            this.mBinderCallsStats = binderCallsStats;
        }

        /** @see BinderCallsStats#reset */
        public void reset() {
            mBinderCallsStats.reset();
        }

        /**
         * @see BinderCallsStats#getExportedCallStats.
         *
         * Note that binder calls stats will be reset by statsd every time
         * the data is exported.
         */
        public ArrayList<BinderCallsStats.ExportedCallStat> getExportedCallStats() {
            return mBinderCallsStats.getExportedCallStats();
        }

        /** @see BinderCallsStats#getExportedExceptionStats */
        public ArrayMap<String, Integer> getExportedExceptionStats() {
            return mBinderCallsStats.getExportedExceptionStats();
        }
    }

    public static class LifeCycle extends SystemService {
        private BinderCallsStatsService mService;
        private BinderCallsStats mBinderCallsStats;

        public LifeCycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mBinderCallsStats = new BinderCallsStats(new BinderCallsStats.Injector());
            mService = new BinderCallsStatsService(mBinderCallsStats);
            publishLocalService(Internal.class, new Internal(mBinderCallsStats));
            publishBinderService("binder_calls_stats", mService);
            boolean detailedTrackingEnabled = SystemProperties.getBoolean(
                    PERSIST_SYS_BINDER_CALLS_DETAILED_TRACKING, false);

            if (detailedTrackingEnabled) {
                Slog.i(TAG, "Enabled CPU usage tracking for binder calls. Controlled by "
                        + PERSIST_SYS_BINDER_CALLS_DETAILED_TRACKING
                        + " or via dumpsys binder_calls_stats --enable-detailed-tracking");
                mBinderCallsStats.setDetailedTracking(true);
            }
        }

        @Override
        public void onBootPhase(int phase) {
            if (SystemService.PHASE_SYSTEM_SERVICES_READY == phase) {
                CachedDeviceState.Readonly deviceState = getLocalService(
                        CachedDeviceState.Readonly.class);
                mService.systemReady(getContext());
                mBinderCallsStats.setDeviceState(deviceState);
            }
        }
    }

    private SettingsObserver mSettingsObserver;
    private final BinderCallsStats mBinderCallsStats;

    BinderCallsStatsService(BinderCallsStats binderCallsStats) {
        mBinderCallsStats = binderCallsStats;
    }

    public void systemReady(Context context) {
        mSettingsObserver = new SettingsObserver(context, mBinderCallsStats);
    }

    public void reset() {
        Slog.i(TAG, "Resetting stats");
        mBinderCallsStats.reset();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        boolean verbose = false;
        if (args != null) {
            for (final String arg : args) {
                if ("-a".equals(arg)) {
                    verbose = true;
                } else if ("--reset".equals(arg)) {
                    reset();
                    pw.println("binder_calls_stats reset.");
                    return;
                } else if ("--enable".equals(arg)) {
                    Binder.setObserver(mBinderCallsStats);
                    return;
                } else if ("--disable".equals(arg)) {
                    Binder.setObserver(null);
                    return;
                } else if ("--no-sampling".equals(arg)) {
                    mBinderCallsStats.setSamplingInterval(1);
                    return;
                } else if ("--enable-detailed-tracking".equals(arg)) {
                    SystemProperties.set(PERSIST_SYS_BINDER_CALLS_DETAILED_TRACKING, "1");
                    mBinderCallsStats.setDetailedTracking(true);
                    pw.println("Detailed tracking enabled");
                    return;
                } else if ("--disable-detailed-tracking".equals(arg)) {
                    SystemProperties.set(PERSIST_SYS_BINDER_CALLS_DETAILED_TRACKING, "");
                    mBinderCallsStats.setDetailedTracking(false);
                    pw.println("Detailed tracking disabled");
                    return;
                } else if ("-h".equals(arg)) {
                    pw.println("binder_calls_stats commands:");
                    pw.println("  --reset: Reset stats");
                    pw.println("  --enable: Enable tracking binder calls");
                    pw.println("  --disable: Disables tracking binder calls");
                    pw.println("  --no-sampling: Tracks all calls");
                    pw.println("  --enable-detailed-tracking: Enables detailed tracking");
                    pw.println("  --disable-detailed-tracking: Disables detailed tracking");
                    return;
                } else {
                    pw.println("Unknown option: " + arg);
                }
            }
        }
        mBinderCallsStats.dump(pw, getAppIdToPackagesMap(), verbose);
    }

    private Map<Integer, String> getAppIdToPackagesMap() {
        List<PackageInfo> packages;
        try {
            packages = AppGlobals.getPackageManager()
                    .getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES,
                            UserHandle.USER_SYSTEM).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        Map<Integer,String> map = new HashMap<>();
        for (PackageInfo pkg : packages) {
            String name = pkg.packageName;
            int uid = pkg.applicationInfo.uid;
            // Use sharedUserId string as package name if there are collisions
            if (pkg.sharedUserId != null && map.containsKey(uid)) {
                name = "shared:" + pkg.sharedUserId;
            }
            map.put(uid, name);
        }
        return map;
    }

}
