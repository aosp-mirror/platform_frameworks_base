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
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Binder;
import android.os.IPullAtomCallback;
import android.os.IStatsManagerService;
import android.os.IStatsd;
import android.os.Process;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.Map;
import java.util.Objects;

/**
 * Service for {@link android.app.StatsManager}.
 *
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
    @GuardedBy("mLock")
    private ArrayMap<Integer, PendingIntentRef> mActiveConfigsPirMap = new ArrayMap<>();
    @GuardedBy("mLock")
    private ArrayMap<ConfigKey, ArrayMap<Long, PendingIntentRef>> mBroadcastSubscriberPirMap =
            new ArrayMap<>();

    public StatsManagerService(Context context) {
        super();
        mContext = context;
    }

    private static class ConfigKey {
        private final int mUid;
        private final long mConfigId;

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

    private static class PullerKey {
        private final int mUid;
        private final int mAtomTag;

        PullerKey(int uid, int atom) {
            mUid = uid;
            mAtomTag = atom;
        }

        public int getUid() {
            return mUid;
        }

        public int getAtom() {
            return mAtomTag;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mUid, mAtomTag);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof PullerKey) {
                PullerKey other = (PullerKey) obj;
                return this.mUid == other.getUid() && this.mAtomTag == other.getAtom();
            }
            return false;
        }
    }

    private static class PullerValue {
        private final long mCoolDownMillis;
        private final long mTimeoutMillis;
        private final int[] mAdditiveFields;
        private final IPullAtomCallback mCallback;

        PullerValue(long coolDownMillis, long timeoutMillis, int[] additiveFields,
                IPullAtomCallback callback) {
            mCoolDownMillis = coolDownMillis;
            mTimeoutMillis = timeoutMillis;
            mAdditiveFields = additiveFields;
            mCallback = callback;
        }

        public long getCoolDownMillis() {
            return mCoolDownMillis;
        }

        public long getTimeoutMillis() {
            return mTimeoutMillis;
        }

        public int[] getAdditiveFields() {
            return mAdditiveFields;
        }

        public IPullAtomCallback getCallback() {
            return mCallback;
        }
    }

    private final ArrayMap<PullerKey, PullerValue> mPullers = new ArrayMap<>();

    @Override
    public void registerPullAtomCallback(int atomTag, long coolDownMillis, long timeoutMillis,
            int[] additiveFields, IPullAtomCallback pullerCallback) {
        enforceRegisterStatsPullAtomPermission();
        if (pullerCallback == null) {
            Log.w(TAG, "Puller callback is null for atom " + atomTag);
            return;
        }
        int callingUid = Binder.getCallingUid();
        PullerKey key = new PullerKey(callingUid, atomTag);
        PullerValue val =
                new PullerValue(coolDownMillis, timeoutMillis, additiveFields, pullerCallback);

        // Always cache the puller in StatsManagerService. If statsd is down, we will register the
        // puller when statsd comes back up.
        synchronized (mLock) {
            mPullers.put(key, val);
        }

        IStatsd statsd = getStatsdNonblocking();
        if (statsd == null) {
            return;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            statsd.registerPullAtomCallback(callingUid, atomTag, coolDownMillis, timeoutMillis,
                    additiveFields, pullerCallback);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to access statsd to register puller for atom " + atomTag);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void unregisterPullAtomCallback(int atomTag) {
        enforceRegisterStatsPullAtomPermission();
        int callingUid = Binder.getCallingUid();
        PullerKey key = new PullerKey(callingUid, atomTag);

        // Always remove the puller from StatsManagerService even if statsd is down. When statsd
        // comes back up, we will not re-register the removed puller.
        synchronized (mLock) {
            mPullers.remove(key);
        }

        IStatsd statsd = getStatsdNonblocking();
        if (statsd == null) {
            return;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            statsd.unregisterPullAtomCallback(callingUid, atomTag);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to access statsd to unregister puller for atom " + atomTag);
        } finally {
            Binder.restoreCallingIdentity(token);
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
            Log.e(TAG, "Failed to setDataFetchOperation with statsd");
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
            Log.e(TAG, "Failed to removeDataFetchOperation with statsd");
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public long[] setActiveConfigsChangedOperation(PendingIntent pendingIntent,
            String packageName) {
        enforceDumpAndUsageStatsPermission(packageName);
        int callingUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        PendingIntentRef pir = new PendingIntentRef(pendingIntent, mContext);
        // We add the PIR to a map so we can reregister if statsd is unavailable.
        synchronized (mLock) {
            mActiveConfigsPirMap.put(callingUid, pir);
        }
        try {
            IStatsd statsd = getStatsdNonblocking();
            if (statsd != null) {
                return statsd.setActiveConfigsChangedOperation(pir, callingUid);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to setActiveConfigsChangedOperation with statsd");
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return new long[] {};
    }

    @Override
    public void removeActiveConfigsChangedOperation(String packageName) {
        enforceDumpAndUsageStatsPermission(packageName);
        int callingUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        synchronized (mLock) {
            mActiveConfigsPirMap.remove(callingUid);
        }
        try {
            IStatsd statsd = getStatsdNonblocking();
            if (statsd != null) {
                statsd.removeActiveConfigsChangedOperation(callingUid);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to removeActiveConfigsChangedOperation with statsd");
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void setBroadcastSubscriber(long configId, long subscriberId,
            PendingIntent pendingIntent, String packageName) {
        enforceDumpAndUsageStatsPermission(packageName);
        int callingUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        PendingIntentRef pir = new PendingIntentRef(pendingIntent, mContext);
        ConfigKey key = new ConfigKey(callingUid, configId);
        // We add the PIR to a map so we can reregister if statsd is unavailable.
        synchronized (mLock) {
            ArrayMap<Long, PendingIntentRef> innerMap = mBroadcastSubscriberPirMap
                    .getOrDefault(key, new ArrayMap<>());
            innerMap.put(subscriberId, pir);
            mBroadcastSubscriberPirMap.put(key, innerMap);
        }
        try {
            IStatsd statsd = getStatsdNonblocking();
            if (statsd != null) {
                statsd.setBroadcastSubscriber(
                        configId, subscriberId, pir, callingUid);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to setBroadcastSubscriber with statsd");
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void unsetBroadcastSubscriber(long configId, long subscriberId, String packageName) {
        enforceDumpAndUsageStatsPermission(packageName);
        int callingUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        ConfigKey key = new ConfigKey(callingUid, configId);
        synchronized (mLock) {
            ArrayMap<Long, PendingIntentRef> innerMap = mBroadcastSubscriberPirMap
                    .getOrDefault(key, new ArrayMap<>());
            innerMap.remove(subscriberId);
            if (innerMap.isEmpty()) {
                mBroadcastSubscriberPirMap.remove(key);
            }
        }
        try {
            IStatsd statsd = getStatsdNonblocking();
            if (statsd != null) {
                statsd.unsetBroadcastSubscriber(configId, subscriberId, callingUid);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to unsetBroadcastSubscriber with statsd");
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public long[] getRegisteredExperimentIds() throws IllegalStateException {
        enforceDumpAndUsageStatsPermission(null);
        final long token = Binder.clearCallingIdentity();
        try {
            IStatsd statsd = waitForStatsd();
            if (statsd != null) {
                return statsd.getRegisteredExperimentIds();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to getRegisteredExperimentIds with statsd");
            throw new IllegalStateException(e.getMessage(), e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        throw new IllegalStateException("Failed to connect to statsd to registerExperimentIds");
    }

    @Override
    public byte[] getMetadata(String packageName) throws IllegalStateException {
        enforceDumpAndUsageStatsPermission(packageName);
        final long token = Binder.clearCallingIdentity();
        try {
            IStatsd statsd = waitForStatsd();
            if (statsd != null) {
                return statsd.getMetadata();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to getMetadata with statsd");
            throw new IllegalStateException(e.getMessage(), e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        throw new IllegalStateException("Failed to connect to statsd to getMetadata");
    }

    @Override
    public byte[] getData(long key, String packageName) throws IllegalStateException {
        enforceDumpAndUsageStatsPermission(packageName);
        int callingUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            IStatsd statsd = waitForStatsd();
            if (statsd != null) {
                return statsd.getData(key, callingUid);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to getData with statsd");
            throw new IllegalStateException(e.getMessage(), e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        throw new IllegalStateException("Failed to connect to statsd to getData");
    }

    @Override
    public void addConfiguration(long configId, byte[] config, String packageName)
            throws IllegalStateException {
        enforceDumpAndUsageStatsPermission(packageName);
        int callingUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            IStatsd statsd = waitForStatsd();
            if (statsd != null) {
                statsd.addConfiguration(configId, config, callingUid);
                return;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to addConfiguration with statsd");
            throw new IllegalStateException(e.getMessage(), e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        throw new IllegalStateException("Failed to connect to statsd to addConfig");
    }

    @Override
    public void removeConfiguration(long configId, String packageName)
            throws IllegalStateException {
        enforceDumpAndUsageStatsPermission(packageName);
        int callingUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            IStatsd statsd = waitForStatsd();
            if (statsd != null) {
                statsd.removeConfiguration(configId, callingUid);
                return;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to removeConfiguration with statsd");
            throw new IllegalStateException(e.getMessage(), e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        throw new IllegalStateException("Failed to connect to statsd to removeConfig");
    }

    void setStatsCompanionService(StatsCompanionService statsCompanionService) {
        mStatsCompanionService = statsCompanionService;
    }

    /**
     * Checks that the caller has both DUMP and PACKAGE_USAGE_STATS permissions. Also checks that
     * the caller has USAGE_STATS_PERMISSION_OPS for the specified packageName if it is not null.
     *
     * @param packageName The packageName to check USAGE_STATS_PERMISSION_OPS.
     */
    private void enforceDumpAndUsageStatsPermission(@Nullable String packageName) {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();

        if (callingPid == Process.myPid()) {
            return;
        }

        mContext.enforceCallingPermission(Manifest.permission.DUMP, null);
        mContext.enforceCallingPermission(Manifest.permission.PACKAGE_USAGE_STATS, null);

        if (packageName == null) {
            return;
        }
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

    private void enforceRegisterStatsPullAtomPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.REGISTER_STATS_PULL_ATOM,
                "Need REGISTER_STATS_PULL_ATOM permission.");
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
                    Log.e(TAG, "wait for statsd interrupted");
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

        final long token = Binder.clearCallingIdentity();
        try {
            registerAllPullers(statsd);
            registerAllDataFetchOperations(statsd);
            registerAllActiveConfigsChangedOperations(statsd);
            registerAllBroadcastSubscribers(statsd);
        } catch (RemoteException e) {
            Log.e(TAG, "StatsManager failed to (re-)register data with statsd");
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Pre-condition: the Binder calling identity has already been cleared
    private void registerAllPullers(IStatsd statsd) throws RemoteException {
        // Since we do not want to make an IPC with the lock held, we first create a copy of the
        // data with the lock held before iterating through the map.
        ArrayMap<PullerKey, PullerValue> pullersCopy;
        synchronized (mLock) {
            pullersCopy = new ArrayMap<>(mPullers);
        }

        for (Map.Entry<PullerKey, PullerValue> entry : pullersCopy.entrySet()) {
            PullerKey key = entry.getKey();
            PullerValue value = entry.getValue();
            statsd.registerPullAtomCallback(key.getUid(), key.getAtom(), value.getCoolDownMillis(),
                    value.getTimeoutMillis(), value.getAdditiveFields(), value.getCallback());
        }
        statsd.allPullersFromBootRegistered();
    }

    // Pre-condition: the Binder calling identity has already been cleared
    private void registerAllDataFetchOperations(IStatsd statsd) throws RemoteException {
        // Since we do not want to make an IPC with the lock held, we first create a copy of the
        // data with the lock held before iterating through the map.
        ArrayMap<ConfigKey, PendingIntentRef> dataFetchCopy;
        synchronized (mLock) {
            dataFetchCopy = new ArrayMap<>(mDataFetchPirMap);
        }

        for (Map.Entry<ConfigKey, PendingIntentRef> entry : dataFetchCopy.entrySet()) {
            ConfigKey key = entry.getKey();
            statsd.setDataFetchOperation(key.getConfigId(), entry.getValue(), key.getUid());
        }
    }

    // Pre-condition: the Binder calling identity has already been cleared
    private void registerAllActiveConfigsChangedOperations(IStatsd statsd) throws RemoteException {
        // Since we do not want to make an IPC with the lock held, we first create a copy of the
        // data with the lock held before iterating through the map.
        ArrayMap<Integer, PendingIntentRef> activeConfigsChangedCopy;
        synchronized (mLock) {
            activeConfigsChangedCopy = new ArrayMap<>(mActiveConfigsPirMap);
        }

        for (Map.Entry<Integer, PendingIntentRef> entry : activeConfigsChangedCopy.entrySet()) {
            statsd.setActiveConfigsChangedOperation(entry.getValue(), entry.getKey());
        }
    }

    // Pre-condition: the Binder calling identity has already been cleared
    private void registerAllBroadcastSubscribers(IStatsd statsd) throws RemoteException {
        // Since we do not want to make an IPC with the lock held, we first create a deep copy of
        // the data with the lock held before iterating through the map.
        ArrayMap<ConfigKey, ArrayMap<Long, PendingIntentRef>> broadcastSubscriberCopy =
                new ArrayMap<>();
        synchronized (mLock) {
            for (Map.Entry<ConfigKey, ArrayMap<Long, PendingIntentRef>> entry :
                    mBroadcastSubscriberPirMap.entrySet()) {
                broadcastSubscriberCopy.put(entry.getKey(), new ArrayMap(entry.getValue()));
            }
        }

        for (Map.Entry<ConfigKey, ArrayMap<Long, PendingIntentRef>> entry :
                mBroadcastSubscriberPirMap.entrySet()) {
            ConfigKey configKey = entry.getKey();
            for (Map.Entry<Long, PendingIntentRef> subscriberEntry : entry.getValue().entrySet()) {
                statsd.setBroadcastSubscriber(configKey.getConfigId(), subscriberEntry.getKey(),
                        subscriberEntry.getValue(), configKey.getUid());
            }
        }
    }
}
