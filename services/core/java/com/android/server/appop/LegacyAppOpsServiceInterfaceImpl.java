/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.appop;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.app.AppOpsManager.Mode;
import android.util.ArrayMap;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;


/**
 * Legacy implementation for App-ops service's app-op mode (uid and package) storage and access.
 * In the future this class will also include mode callbacks and op restrictions.
 */
public class LegacyAppOpsServiceInterfaceImpl implements AppOpsServiceInterface {

    // Should be the same object that the AppOpsService is using for locking.
    final Object mLock;

    @GuardedBy("mLock")
    @VisibleForTesting
    final SparseArray<SparseIntArray> mUidModes = new SparseArray<>();

    @GuardedBy("mLock")
    final ArrayMap<String, SparseIntArray> mPackageModes = new ArrayMap<>();

    final PersistenceScheduler mPersistenceScheduler;


    LegacyAppOpsServiceInterfaceImpl(PersistenceScheduler persistenceScheduler,
            @NonNull Object lock) {
        this.mPersistenceScheduler = persistenceScheduler;
        this.mLock = lock;
    }

    @Override
    public SparseIntArray getNonDefaultUidModes(int uid) {
        synchronized (mLock) {
            SparseIntArray opModes = mUidModes.get(uid, null);
            if (opModes == null) {
                return new SparseIntArray();
            }
            return opModes.clone();
        }
    }

    @Override
    public int getUidMode(int uid, int op) {
        synchronized (mLock) {
            SparseIntArray opModes = mUidModes.get(uid, null);
            if (opModes == null) {
                return AppOpsManager.opToDefaultMode(op);
            }
            return opModes.get(op, AppOpsManager.opToDefaultMode(op));
        }
    }

    @Override
    public boolean setUidMode(int uid, int op, int mode) {
        final int defaultMode = AppOpsManager.opToDefaultMode(op);
        synchronized (mLock) {
            SparseIntArray opModes = mUidModes.get(uid, null);
            if (opModes == null) {
                if (mode != defaultMode) {
                    opModes = new SparseIntArray();
                    mUidModes.put(uid, opModes);
                    opModes.put(op, mode);
                    mPersistenceScheduler.scheduleWriteLocked();
                }
            } else {
                if (opModes.indexOfKey(op) >= 0 && opModes.get(op) == mode) {
                    return false;
                }
                if (mode == defaultMode) {
                    opModes.delete(op);
                    if (opModes.size() <= 0) {
                        opModes = null;
                        mUidModes.delete(uid);
                    }
                } else {
                    opModes.put(op, mode);
                }
                mPersistenceScheduler.scheduleWriteLocked();
            }
        }
        return true;
    }

    @Override
    public int getPackageMode(String packageName, int op) {
        synchronized (mLock) {
            SparseIntArray opModes = mPackageModes.getOrDefault(packageName, null);
            if (opModes == null) {
                return AppOpsManager.opToDefaultMode(op);
            }
            return opModes.get(op, AppOpsManager.opToDefaultMode(op));
        }
    }

    @Override
    public void setPackageMode(String packageName, int op, @Mode int mode) {
        final int defaultMode = AppOpsManager.opToDefaultMode(op);
        synchronized (mLock) {
            SparseIntArray opModes = mPackageModes.get(packageName);
            if (opModes == null) {
                if (mode != defaultMode) {
                    opModes = new SparseIntArray();
                    mPackageModes.put(packageName, opModes);
                    opModes.put(op, mode);
                    mPersistenceScheduler.scheduleWriteLocked();
                }
            } else {
                if (opModes.indexOfKey(op) >= 0 && opModes.get(op) == mode) {
                    return;
                }
                if (mode == defaultMode) {
                    opModes.delete(op);
                    if (opModes.size() <= 0) {
                        opModes = null;
                        mPackageModes.remove(packageName);
                    }
                } else {
                    opModes.put(op, mode);
                }
                mPersistenceScheduler.scheduleWriteLocked();
            }
        }
    }

    @Override
    public void removeUid(int uid) {
        synchronized (mLock) {
            SparseIntArray opModes = mUidModes.get(uid);
            if (opModes == null) {
                return;
            }
            mUidModes.remove(uid);
            mPersistenceScheduler.scheduleFastWriteLocked();
        }
    }


    @Override
    public boolean areUidModesDefault(int uid) {
        synchronized (mLock) {
            SparseIntArray opModes = mUidModes.get(uid);
            return (opModes == null || opModes.size() <= 0);
        }
    }

    @Override
    public boolean arePackageModesDefault(String packageMode) {
        synchronized (mLock) {
            SparseIntArray opModes = mPackageModes.get(packageMode);
            return (opModes == null || opModes.size() <= 0);
        }
    }

    @Override
    public boolean removePackage(String packageName) {
        synchronized (mLock) {
            SparseIntArray ops = mPackageModes.remove(packageName);
            if (ops != null) {
                mPersistenceScheduler.scheduleFastWriteLocked();
                return true;
            }
            return false;
        }
    }

    @Override
    public void clearAllModes() {
        synchronized (mLock) {
            mUidModes.clear();
            mPackageModes.clear();
        }
    }

}
