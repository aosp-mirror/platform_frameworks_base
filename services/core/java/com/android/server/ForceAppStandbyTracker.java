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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.AppOpsManager.PackageOps;
import android.app.IUidObserver;
import android.content.Context;
import android.os.Handler;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.util.Preconditions;

import java.util.List;

/**
 * Class to track OP_RUN_ANY_IN_BACKGROUND, UID foreground state and "force all app standby".
 *
 * TODO Clean up cache when a user is deleted.
 * TODO Add unit tests. b/68769804.
 */
public class ForceAppStandbyTracker {
    private static final String TAG = "ForceAppStandbyTracker";

    @GuardedBy("ForceAppStandbyTracker.class")
    private static ForceAppStandbyTracker sInstance;

    private final Object mLock = new Object();
    private final Context mContext;

    AppOpsManager mAppOpsManager;
    IAppOpsService mAppOpsService;
    PowerManagerInternal mPowerManagerInternal;

    private final Handler mCallbackHandler;

    /**
     * Pair of (uid (not user-id), packageName) with OP_RUN_ANY_IN_BACKGROUND *not* allowed.
     */
    @GuardedBy("mLock")
    final ArraySet<Pair<Integer, String>> mForcedAppStandbyUidPackages = new ArraySet<>();

    @GuardedBy("mLock")
    final SparseBooleanArray mForegroundUids = new SparseBooleanArray();

    @GuardedBy("mLock")
    final ArraySet<Listener> mListeners = new ArraySet<>();

    @GuardedBy("mLock")
    boolean mStarted;

    @GuardedBy("mLock")
    boolean mForceAllAppsStandby;

    public static abstract class Listener {
        public void onRestrictionChanged(int uid, @Nullable String packageName) {
        }

        public void onGlobalRestrictionChanged() {
        }
    }

    private ForceAppStandbyTracker(Context context) {
        mContext = context;
        mCallbackHandler = FgThread.getHandler();
    }

    /**
     * Get the singleton instance.
     */
    public static synchronized ForceAppStandbyTracker getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ForceAppStandbyTracker(context);
        }
        return sInstance;
    }

    /**
     * Call it when the system is ready.
     */
    public void start() {
        synchronized (mLock) {
            if (mStarted) {
                return;
            }
            mStarted = true;

            mAppOpsManager = Preconditions.checkNotNull(
                    mContext.getSystemService(AppOpsManager.class));
            mAppOpsService = Preconditions.checkNotNull(
                    IAppOpsService.Stub.asInterface(
                            ServiceManager.getService(Context.APP_OPS_SERVICE)));
            mPowerManagerInternal = Preconditions.checkNotNull(
                    LocalServices.getService(PowerManagerInternal.class));

            try {
                ActivityManager.getService().registerUidObserver(new UidObserver(),
                        ActivityManager.UID_OBSERVER_GONE | ActivityManager.UID_OBSERVER_IDLE
                                | ActivityManager.UID_OBSERVER_ACTIVE,
                        ActivityManager.PROCESS_STATE_UNKNOWN, null);
                mAppOpsService.startWatchingMode(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, null,
                        new AppOpsWatcher());
            } catch (RemoteException e) {
                // shouldn't happen.
            }

            mPowerManagerInternal.registerLowPowerModeObserver(
                    ServiceType.FORCE_ALL_APPS_STANDBY,
                    state -> updateForceAllAppsStandby(state.batterySaverEnabled));

            updateForceAllAppsStandby(
                    mPowerManagerInternal.getLowPowerState(ServiceType.FORCE_ALL_APPS_STANDBY)
                            .batterySaverEnabled);

            refreshForcedAppStandbyUidPackagesLocked();
        }
    }

    /**
     * Update {@link #mForcedAppStandbyUidPackages} with the current app ops state.
     */
    private void refreshForcedAppStandbyUidPackagesLocked() {
        final int op = AppOpsManager.OP_RUN_ANY_IN_BACKGROUND;

        mForcedAppStandbyUidPackages.clear();
        final List<PackageOps> ops = mAppOpsManager.getPackagesForOps(new int[] {op});

        if (ops == null) {
            return;
        }
        final int size = ops.size();
        for (int i = 0; i < size; i++) {
            final AppOpsManager.PackageOps pkg = ops.get(i);
            final List<AppOpsManager.OpEntry> entries = ops.get(i).getOps();

            for (int j = 0; j < entries.size(); j++) {
                AppOpsManager.OpEntry ent = entries.get(j);
                if (ent.getOp() != op) {
                    continue;
                }
                if (ent.getMode() != AppOpsManager.MODE_ALLOWED) {
                    mForcedAppStandbyUidPackages.add(Pair.create(
                            pkg.getUid(), pkg.getPackageName()));
                }
            }
        }
    }

    boolean isRunAnyInBackgroundAppOpRestricted(int uid, @NonNull String packageName) {
        try {
            return mAppOpsService.checkOperation(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,
                    uid, packageName) != AppOpsManager.MODE_ALLOWED;
        } catch (RemoteException e) {
            return false; // shouldn't happen.
        }
    }

    private int findForcedAppStandbyUidPackageIndexLocked(int uid, @NonNull String packageName) {
        // TODO Maybe we should switch to indexOf(Pair.create()) if the array size is too big.
        final int size = mForcedAppStandbyUidPackages.size();
        for (int i = 0; i < size; i++) {
            final Pair<Integer, String> pair = mForcedAppStandbyUidPackages.valueAt(i);

            if ((pair.first == uid) && packageName.equals(pair.second)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @return whether a uid package-name pair is in mForcedAppStandbyUidPackages.
     */
    boolean isUidPackageRestrictedLocked(int uid, @NonNull String packageName) {
        return findForcedAppStandbyUidPackageIndexLocked(uid, packageName) >= 0;
    }

    boolean updateRestrictedUidPackageLocked(int uid, @NonNull String packageName,
            boolean restricted) {
        final int index =  findForcedAppStandbyUidPackageIndexLocked(uid, packageName);
        final boolean wasRestricted = index >= 0;
        if (wasRestricted == restricted) {
            return false;
        }
        if (restricted) {
            mForcedAppStandbyUidPackages.add(Pair.create(uid, packageName));
        } else {
            mForcedAppStandbyUidPackages.removeAt(index);
        }
        return true;
    }

    void uidToForeground(int uid) {
        synchronized (mLock) {
            if (!UserHandle.isApp(uid)) {
                return;
            }
            // TODO This can be optimized by calling indexOfKey and sharing the index for get and
            // put.
            if (mForegroundUids.get(uid)) {
                return;
            }
            mForegroundUids.put(uid, true);
            notifyForUidPackage(uid, null);
        }
    }

    void uidToBackground(int uid, boolean remove) {
        synchronized (mLock) {
            if (!UserHandle.isApp(uid)) {
                return;
            }
            // TODO This can be optimized by calling indexOfKey and sharing the index for get and
            // put.
            if (!mForegroundUids.get(uid)) {
                return;
            }
            if (remove) {
                mForegroundUids.delete(uid);
            } else {
                mForegroundUids.put(uid, false);
            }
            notifyForUidPackage(uid, null);
        }
    }

    // Event handlers

    final class UidObserver extends IUidObserver.Stub {
        @Override public void onUidStateChanged(int uid, int procState, long procStateSeq) {
        }

        @Override public void onUidGone(int uid, boolean disabled) {
            uidToBackground(uid, /*remove=*/ true);
        }

        @Override public void onUidActive(int uid) {
            uidToForeground(uid);
        }

        @Override public void onUidIdle(int uid, boolean disabled) {
            // Just to avoid excessive memcpy, don't remove from the array in this case.
            uidToBackground(uid, /*remove=*/ false);
        }

        @Override public void onUidCachedChanged(int uid, boolean cached) {
        }
    };

    private final class AppOpsWatcher extends IAppOpsCallback.Stub {
        @Override
        public void opChanged(int op, int uid, String packageName) throws RemoteException {
            synchronized (mLock) {
                final boolean restricted = isRunAnyInBackgroundAppOpRestricted(uid, packageName);

                if (updateRestrictedUidPackageLocked(uid, packageName, restricted)) {
                    notifyForUidPackage(uid, packageName);
                }
            }
        }
    }

    private Listener[] cloneListeners() {
        synchronized (mLock) {
            return mListeners.toArray(new Listener[mListeners.size()]);
        }
    }

    void notifyForUidPackage(int uid, String packageName) {
        mCallbackHandler.post(() -> {
            for (Listener l : cloneListeners()) {
                l.onRestrictionChanged(uid, packageName);
            }
        });
    }

    void notifyGlobal() {
        mCallbackHandler.post(() -> {
            for (Listener l : cloneListeners()) {
                l.onGlobalRestrictionChanged();
            }
        });
    }

    void updateForceAllAppsStandby(boolean forceAllAppsStandby) {
        synchronized (mLock) {
            if (mForceAllAppsStandby == forceAllAppsStandby) {
                return;
            }
            mForceAllAppsStandby = forceAllAppsStandby;
            Slog.i(TAG, "Force all app standby: " + mForceAllAppsStandby);
            notifyGlobal();
        }
    }

    // Public interface.

    /**
     * Register a new listener.
     */
    public void addListener(@NonNull Listener listener) {
        synchronized (mLock) {
            mListeners.add(listener);
        }
    }

    /**
     * Whether force-app-standby is effective for a UID package-name.
     */
    public boolean isRestricted(int uid, @NonNull String packageName) {
        if (isInForeground(uid)) {
            return false;
        }
        synchronized (mLock) {
            if (mForceAllAppsStandby) {
                return true;
            }
            return isUidPackageRestrictedLocked(uid, packageName);
        }
    }

    /** For dumpsys -- otherwise the callers don't need to know it. */
    public boolean isInForeground(int uid) {
        if (!UserHandle.isApp(uid)) {
            return true;
        }
        synchronized (mLock) {
            return mForegroundUids.get(uid);
        }
    }

    /** For dumpsys -- otherwise the callers don't need to know it. */
    public boolean isForceAllAppsStandbyEnabled() {
        synchronized (mLock) {
            return mForceAllAppsStandby;
        }
    }

    /** For dumpsys -- otherwise the callers don't need to know it. */
    public boolean isRunAnyInBackgroundAppOpsAllowed(int uid, @NonNull String packageName) {
        synchronized (mLock) {
            return !isUidPackageRestrictedLocked(uid, packageName);
        }
    }

    /** For dumpsys -- otherwise the callers don't need to know it. */
    public SparseBooleanArray getForegroudUids() {
        synchronized (mLock) {
            return mForegroundUids.clone();
        }
    }

    /** For dumpsys -- otherwise the callers don't need to know it. */
    public ArraySet<Pair<Integer, String>> getRestrictedUidPackages() {
        synchronized (mLock) {
            return new ArraySet(mForcedAppStandbyUidPackages);
        }
    }
}
