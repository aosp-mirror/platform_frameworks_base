/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.tare;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.IUidObserver;
import android.content.pm.PackageManagerInternal;
import android.os.RemoteException;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArrayMap;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Modifier that makes things more cheaper based on an app's process state. */
class ProcessStateModifier extends Modifier {
    private static final String TAG = "TARE-" + ProcessStateModifier.class.getSimpleName();

    private static final int PROC_STATE_BUCKET_NONE = 0;
    private static final int PROC_STATE_BUCKET_TOP = 1;
    private static final int PROC_STATE_BUCKET_FGS = 2;
    private static final int PROC_STATE_BUCKET_BFGS = 3;
    private static final int PROC_STATE_BUCKET_BG = 4;

    @IntDef(prefix = {"PROC_STATE_BUCKET_"}, value = {
            PROC_STATE_BUCKET_NONE,
            PROC_STATE_BUCKET_TOP,
            PROC_STATE_BUCKET_FGS,
            PROC_STATE_BUCKET_BFGS,
            PROC_STATE_BUCKET_BG
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProcStateBucket {
    }

    private final Object mLock = new Object();
    private final InternalResourceService mIrs;
    private final PackageManagerInternal mPackageManagerInternal;

    /** Cached mapping of userId+package to their UIDs (for all users) */
    private final SparseArrayMap<String, Integer> mPackageToUidCache = new SparseArrayMap<>();

    @GuardedBy("mLock")
    private final SparseIntArray mUidProcStateBucketCache = new SparseIntArray();

    private final IUidObserver mUidObserver = new IUidObserver.Stub() {
        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) {
            final int newBucket = getProcStateBucket(procState);
            synchronized (mLock) {
                final int curBucket = mUidProcStateBucketCache.get(uid);
                if (curBucket != newBucket) {
                    mUidProcStateBucketCache.put(uid, newBucket);
                }
                notifyStateChangedLocked(uid);
            }
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            synchronized (mLock) {
                if (mUidProcStateBucketCache.indexOfKey(uid) < 0) {
                    Slog.e(TAG, "UID " + uid + " marked gone but wasn't in cache.");
                    return;
                }
                mUidProcStateBucketCache.delete(uid);
                notifyStateChangedLocked(uid);
            }
        }

        @Override
        public void onUidActive(int uid) {
        }

        @Override
        public void onUidIdle(int uid, boolean disabled) {
        }

        @Override
        public void onUidCachedChanged(int uid, boolean cached) {
        }
    };

    ProcessStateModifier(@NonNull InternalResourceService irs) {
        super();
        mIrs = irs;
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
    }

    @Override
    @GuardedBy("mLock")
    void onSystemServicesReady() {
        try {
            ActivityManager.getService().registerUidObserver(mUidObserver,
                    ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE,
                    ActivityManager.PROCESS_STATE_UNKNOWN, null);
        } catch (RemoteException e) {
            // ignored; both services live in system_server
        }
    }

    /**
     * Get the final modified price based on an app's process state.
     *
     * @param ctp   Cost to produce. @see EconomicPolicy.Action#costToProduce
     * @param price Current price
     */
    long getModifiedPrice(final int userId, @NonNull final String pkgName,
            final long ctp, final long price) {
        final int procState;
        synchronized (mLock) {
            procState = mUidProcStateBucketCache.get(
                    getUidLocked(userId, pkgName), PROC_STATE_BUCKET_NONE);
        }
        switch (procState) {
            case PROC_STATE_BUCKET_TOP:
                return 0;
            case PROC_STATE_BUCKET_FGS:
                // Can't get notification priority. Just use CTP for now.
                return ctp;
            case PROC_STATE_BUCKET_BFGS:
                return (long) (ctp + .5 * (price - ctp));
            case PROC_STATE_BUCKET_BG:
            default:
                return price;
        }
    }

    @Override
    @GuardedBy("mLock")
    void dump(IndentingPrintWriter pw) {
        pw.print("Proc state bucket cache = ");
        pw.println(mUidProcStateBucketCache);
    }

    @GuardedBy("mLock")
    private int getUidLocked(final int userId, @NonNull final String pkgName) {
        if (!mPackageToUidCache.contains(userId, pkgName)) {
            mPackageToUidCache.add(userId, pkgName,
                    mPackageManagerInternal.getPackageUid(pkgName, 0, userId));
        }
        return mPackageToUidCache.get(userId, pkgName);
    }

    @ProcStateBucket
    private int getProcStateBucket(int procState) {
        if (procState <= ActivityManager.PROCESS_STATE_TOP) {
            return PROC_STATE_BUCKET_TOP;
        }
        if (procState <= ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE) {
            return PROC_STATE_BUCKET_FGS;
        }
        if (procState <= ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE) {
            return PROC_STATE_BUCKET_BFGS;
        }
        return PROC_STATE_BUCKET_BG;
    }

    @GuardedBy("mLock")
    private void notifyStateChangedLocked(final int uid) {
        // Never call out to the IRS with the local lock held.
        TareHandlerThread.getHandler().post(() -> mIrs.onUidStateChanged(uid));
    }
}
