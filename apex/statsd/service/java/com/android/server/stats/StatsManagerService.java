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

package com.android.server.stats;

import static com.android.server.stats.StatsCompanion.PendingIntentRef;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Binder;
import android.os.IStatsManagerService;
import android.os.IStatsd;
import android.os.Process;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.util.Map;
import java.util.Objects;

/**
 * Service for {@link android.app.StatsManager}.
 * @hide
 */
public class StatsManagerService extends IStatsManagerService.Stub {

    private static final String TAG = "StatsManagerService";
    private static final boolean DEBUG = false;

    private static final int STATSD_TIMEOUT_MILLIS = 5000;

    private static final String USAGE_STATS_PERMISSION_OPS = "android:get_usage_stats";

    @GuardedBy("mLock")
    private IStatsd mStatsd;
    private final Object mLock = new Object();

    private StatsCompanionService mStatsCompanionService;
    private Context mContext;

    @GuardedBy("mLock")
    private ArrayMap<ConfigKey, PendingIntentRef> mDataFetchPirMap = new ArrayMap<>();

    public StatsManagerService(Context context) {
        super();
        mContext = context;
    }

    private static class ConfigKey {
        private int mUid;
        private long mConfigId;

        ConfigKey(int uid, long configId) {
            mUid = uid;
            mConfigId = configId;
        }

        public int getUid() {
            return mUid;
        }

        public long getConfigId() {
            return mConfigId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mUid, mConfigId);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof ConfigKey) {
                ConfigKey other = (ConfigKey) obj;
                return this.mUid == other.getUid() && this.mConfigId == other.getConfigId();
            }
            return false;
        }
    }

    @Override
    public void setDataFetchOperation(long configId, PendingIntent pendingIntent,
            String packageName) {
        enforceDumpAndUsageStatsPermission(packageName);
        int callingUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        PendingIntentRef pir = new PendingIntentRef(pendingIntent, mContext);
        ConfigKey key = new ConfigKey(callingUid, configId);
        // We add the PIR to a map so we can reregister if statsd is unavailable.
        synchronized (mLock) {
            mDataFetchPirMap.put(key, pir);
        }
        try {
            IStatsd statsd = getStatsdNonblocking();
            if (statsd != null) {
                statsd.setDataFetchOperation(configId, pir, callingUid);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to setDataFetchOperation with statsd");
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void removeDataFetchOperation(long configId, String packageName) {
        enforceDumpAndUsageStatsPermission(packageName);
        int callingUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        ConfigKey key = new ConfigKey(callingUid, configId);
        synchronized (mLock) {
            mDataFetchPirMap.remove(key);
        }
        try {
            IStatsd statsd = getStatsdNonblocking();
            if (statsd != null) {
                statsd.removeDataFetchOperation(configId, callingUid);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to removeDataFetchOperation with statsd");
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public long[] setActiveConfigsChangedOperation(PendingIntent pendingIntent,
            String packageName) {
        // no-op
        if (DEBUG) {
            Slog.d(TAG, "setActiveConfigsChangedOperation");
        }
        return new long[]{};
    }

    @Override
    public void setBroadcastSubscriber(long configKey, long subscriberId,
            PendingIntent pendingIntent, String packageName) {
        //no-op
        if (DEBUG) {
            Slog.d(TAG, "setBroadcastSubscriber");
        }
    }

    void setStatsCompanionService(StatsCompanionService statsCompanionService) {
        mStatsCompanionService = statsCompanionService;
    }

    private void enforceDumpAndUsageStatsPermission(String packageName) {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();

        if (callingPid == Process.myPid()) {
            return;
        }
        mContext.enforceCallingPermission(Manifest.permission.DUMP, null);
        mContext.enforceCallingPermission(Manifest.permission.PACKAGE_USAGE_STATS, null);

        AppOpsManager appOpsManager = (AppOpsManager) mContext
                .getSystemService(Context.APP_OPS_SERVICE);
        switch (appOpsManager.noteOp(USAGE_STATS_PERMISSION_OPS,
                Binder.getCallingUid(), packageName, null, null)) {
            case AppOpsManager.MODE_ALLOWED:
            case AppOpsManager.MODE_DEFAULT:
                break;
            default:
                throw new SecurityException(
                        String.format("UID %d / PID %d lacks app-op %s",
                                callingUid, callingPid, USAGE_STATS_PERMISSION_OPS)
                );
        }
    }

    /**
     * Clients should call this if blocking until statsd to be ready is desired
     *
     * @return IStatsd object if statsd becomes ready within the timeout, null otherwise.
     */
    private IStatsd waitForStatsd() {
        synchronized (mLock) {
            if (mStatsd == null) {
                try {
                    mLock.wait(STATSD_TIMEOUT_MILLIS);
                } catch (InterruptedException e) {
                    Slog.e(TAG, "wait for statsd interrupted");
                }
            }
            return mStatsd;
        }
    }

    /**
     * Clients should call this to receive a reference to statsd.
     *
     * @return IStatsd object if statsd is ready, null otherwise.
     */
    private IStatsd getStatsdNonblocking() {
        synchronized (mLock) {
            return mStatsd;
        }
    }

    /**
     * Called from {@link StatsCompanionService}.
     *
     * Tells StatsManagerService that Statsd is ready and updates
     * Statsd with the contents of our local cache.
     */
    void statsdReady(IStatsd statsd) {
        synchronized (mLock) {
            mStatsd = statsd;
            mLock.notify();
        }
        sayHiToStatsd(statsd);
    }

    /**
     * Called from {@link StatsCompanionService}.
     *
     * Tells StatsManagerService that Statsd is no longer ready
     * and we should no longer make binder calls with statsd.
     */
    void statsdNotReady() {
        synchronized (mLock) {
            mStatsd = null;
        }
    }

    private void sayHiToStatsd(IStatsd statsd) {
        if (statsd == null) {
            return;
        }
        ArrayMap<ConfigKey, PendingIntentRef> dataFetchCopy;
        synchronized (mLock) {
            dataFetchCopy = new ArrayMap<>(mDataFetchPirMap);
        }
        for (Map.Entry<ConfigKey, PendingIntentRef> entry : dataFetchCopy.entrySet()) {
            ConfigKey key = entry.getKey();
            try {
                statsd.setDataFetchOperation(key.getConfigId(), entry.getValue(), key.getUid());
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to setDataFetchOperation from pirMap");
            }
        }
    }
}
