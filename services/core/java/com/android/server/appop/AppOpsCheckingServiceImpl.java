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

import static android.app.AppOpsManager.OP_NONE;
import static android.app.AppOpsManager.WATCH_FOREGROUND_CHANGES;
import static android.app.AppOpsManager.opRestrictsRead;

import static com.android.server.appop.AppOpsService.ModeCallback.ALL_OPS;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.AppOpsManager.Mode;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.function.pooled.PooledLambda;

import libcore.util.EmptyArray;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.Objects;


/**
 * Legacy implementation for App-ops service's app-op mode (uid and package) storage and access.
 * In the future this class will also include mode callbacks and op restrictions.
 */
public class AppOpsCheckingServiceImpl implements AppOpsCheckingServiceInterface {

    static final String TAG = "LegacyAppOpsServiceInterfaceImpl";

    // Must be the same object that the AppOpsService is using for locking.
    final Object mLock;
    final Handler mHandler;
    final Context mContext;
    final SparseArray<int[]> mSwitchedOps;

    @GuardedBy("mLock")
    @VisibleForTesting
    final SparseArray<SparseIntArray> mUidModes = new SparseArray<>();

    @GuardedBy("mLock")
    final SparseArray<ArrayMap<String, SparseIntArray>> mUserPackageModes = new SparseArray<>();

    final SparseArray<ArraySet<OnOpModeChangedListener>> mOpModeWatchers = new SparseArray<>();
    final ArrayMap<String, ArraySet<OnOpModeChangedListener>> mPackageModeWatchers =
            new ArrayMap<>();

    final PersistenceScheduler mPersistenceScheduler;


    // Constant meaning that any UID should be matched when dispatching callbacks
    private static final int UID_ANY = -2;


    AppOpsCheckingServiceImpl(PersistenceScheduler persistenceScheduler,
            @NonNull Object lock, Handler handler, Context context,
            SparseArray<int[]> switchedOps) {
        this.mPersistenceScheduler = persistenceScheduler;
        this.mLock = lock;
        this.mHandler = handler;
        this.mContext = context;
        this.mSwitchedOps = switchedOps;
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
    public int getPackageMode(String packageName, int op, @UserIdInt int userId) {
        synchronized (mLock) {
            ArrayMap<String, SparseIntArray> packageModes = mUserPackageModes.get(userId, null);
            if (packageModes == null) {
                return AppOpsManager.opToDefaultMode(op);
            }
            SparseIntArray opModes = packageModes.getOrDefault(packageName, null);
            if (opModes == null) {
                return AppOpsManager.opToDefaultMode(op);
            }
            return opModes.get(op, AppOpsManager.opToDefaultMode(op));
        }
    }

    @Override
    public void setPackageMode(String packageName, int op, @Mode int mode, @UserIdInt int userId) {
        final int defaultMode = AppOpsManager.opToDefaultMode(op);
        synchronized (mLock) {
            ArrayMap<String, SparseIntArray> packageModes = mUserPackageModes.get(userId, null);
            if (packageModes == null) {
                packageModes = new ArrayMap<>();
                mUserPackageModes.put(userId, packageModes);
            }
            SparseIntArray opModes = packageModes.get(packageName);
            if (opModes == null) {
                if (mode != defaultMode) {
                    opModes = new SparseIntArray();
                    packageModes.put(packageName, opModes);
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
                        packageModes.remove(packageName);
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
    public boolean arePackageModesDefault(@NonNull String packageName, @UserIdInt int userId) {
        synchronized (mLock) {
            ArrayMap<String, SparseIntArray> packageModes = mUserPackageModes.get(userId, null);
            if (packageModes == null) {
                return true;
            }
            SparseIntArray opModes = packageModes.get(packageName);
            return (opModes == null || opModes.size() <= 0);
        }
    }

    @Override
    public boolean removePackage(String packageName, @UserIdInt int userId) {
        synchronized (mLock) {
            ArrayMap<String, SparseIntArray> packageModes = mUserPackageModes.get(userId, null);
            if (packageModes == null) {
                return false;
            }
            SparseIntArray ops = packageModes.remove(packageName);
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
            mUserPackageModes.clear();
        }
    }

    @Override
    public void startWatchingOpModeChanged(@NonNull OnOpModeChangedListener changedListener,
            int op) {
        Objects.requireNonNull(changedListener);
        synchronized (mLock) {
            ArraySet<OnOpModeChangedListener> modeWatcherSet = mOpModeWatchers.get(op);
            if (modeWatcherSet == null) {
                modeWatcherSet = new ArraySet<>();
                mOpModeWatchers.put(op, modeWatcherSet);
            }
            modeWatcherSet.add(changedListener);
        }
    }

    @Override
    public void startWatchingPackageModeChanged(@NonNull OnOpModeChangedListener changedListener,
            @NonNull String packageName) {
        Objects.requireNonNull(changedListener);
        Objects.requireNonNull(packageName);
        synchronized (mLock) {
            ArraySet<OnOpModeChangedListener> modeWatcherSet =
                    mPackageModeWatchers.get(packageName);
            if (modeWatcherSet == null) {
                modeWatcherSet = new ArraySet<>();
                mPackageModeWatchers.put(packageName, modeWatcherSet);
            }
            modeWatcherSet.add(changedListener);
        }
    }

    @Override
    public void removeListener(@NonNull OnOpModeChangedListener changedListener) {
        Objects.requireNonNull(changedListener);

        synchronized (mLock) {
            for (int i = mOpModeWatchers.size() - 1; i >= 0; i--) {
                ArraySet<OnOpModeChangedListener> cbs = mOpModeWatchers.valueAt(i);
                cbs.remove(changedListener);
                if (cbs.size() <= 0) {
                    mOpModeWatchers.removeAt(i);
                }
            }

            for (int i = mPackageModeWatchers.size() - 1; i >= 0; i--) {
                ArraySet<OnOpModeChangedListener> cbs = mPackageModeWatchers.valueAt(i);
                cbs.remove(changedListener);
                if (cbs.size() <= 0) {
                    mPackageModeWatchers.removeAt(i);
                }
            }
        }
    }

    @Override
    public ArraySet<OnOpModeChangedListener> getOpModeChangedListeners(int op) {
        synchronized (mLock) {
            ArraySet<OnOpModeChangedListener> modeChangedListenersSet = mOpModeWatchers.get(op);
            if (modeChangedListenersSet == null) {
                return new ArraySet<>();
            }
            return new ArraySet<>(modeChangedListenersSet);
        }
    }

    @Override
    public ArraySet<OnOpModeChangedListener> getPackageModeChangedListeners(
            @NonNull String packageName) {
        Objects.requireNonNull(packageName);

        synchronized (mLock) {
            ArraySet<OnOpModeChangedListener> modeChangedListenersSet =
                    mPackageModeWatchers.get(packageName);
            if (modeChangedListenersSet == null) {
                return new ArraySet<>();
            }
            return new ArraySet<>(modeChangedListenersSet);
        }
    }

    @Override
    public void notifyWatchersOfChange(int code, int uid) {
        ArraySet<OnOpModeChangedListener> listenerSet = getOpModeChangedListeners(code);
        if (listenerSet == null) {
            return;
        }
        for (int i = 0; i < listenerSet.size(); i++) {
            final OnOpModeChangedListener listener = listenerSet.valueAt(i);
            notifyOpChanged(listener, code, uid, null);
        }
    }

    @Override
    public void notifyOpChanged(@NonNull OnOpModeChangedListener onModeChangedListener, int code,
            int uid, @Nullable String packageName) {
        Objects.requireNonNull(onModeChangedListener);

        if (uid != UID_ANY && onModeChangedListener.getWatchingUid() >= 0
                && onModeChangedListener.getWatchingUid() != uid) {
            return;
        }

        // See CALL_BACK_ON_CHANGED_LISTENER_WITH_SWITCHED_OP_CHANGE
        int[] switchedCodes;
        if (onModeChangedListener.getWatchedOpCode() == ALL_OPS) {
            switchedCodes = mSwitchedOps.get(code);
        } else if (onModeChangedListener.getWatchedOpCode() == OP_NONE) {
            switchedCodes = new int[]{code};
        } else {
            switchedCodes = new int[]{onModeChangedListener.getWatchedOpCode()};
        }

        for (int switchedCode : switchedCodes) {
            // There are features watching for mode changes such as window manager
            // and location manager which are in our process. The callbacks in these
            // features may require permissions our remote caller does not have.
            final long identity = Binder.clearCallingIdentity();
            try {
                if (shouldIgnoreCallback(switchedCode, onModeChangedListener.getCallingPid(),
                        onModeChangedListener.getCallingUid())) {
                    continue;
                }
                onModeChangedListener.onOpModeChanged(switchedCode, uid, packageName);
            } catch (RemoteException e) {
                /* ignore */
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private boolean shouldIgnoreCallback(int op, int watcherPid, int watcherUid) {
        // If it's a restricted read op, ignore it if watcher doesn't have manage ops permission,
        // as watcher should not use this to signal if the value is changed.
        return opRestrictsRead(op) && mContext.checkPermission(Manifest.permission.MANAGE_APPOPS,
                watcherPid, watcherUid) != PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void notifyOpChangedForAllPkgsInUid(int code, int uid, boolean onlyForeground,
            @Nullable OnOpModeChangedListener callbackToIgnore) {
        String[] uidPackageNames = getPackagesForUid(uid);
        ArrayMap<OnOpModeChangedListener, ArraySet<String>> callbackSpecs = null;

        synchronized (mLock) {
            ArraySet<OnOpModeChangedListener> callbacks = mOpModeWatchers.get(code);
            if (callbacks != null) {
                final int callbackCount = callbacks.size();
                for (int i = 0; i < callbackCount; i++) {
                    OnOpModeChangedListener callback = callbacks.valueAt(i);

                    if (onlyForeground && (callback.getFlags()
                            & WATCH_FOREGROUND_CHANGES) == 0) {
                        continue;
                    }

                    ArraySet<String> changedPackages = new ArraySet<>();
                    Collections.addAll(changedPackages, uidPackageNames);
                    if (callbackSpecs == null) {
                        callbackSpecs = new ArrayMap<>();
                    }
                    callbackSpecs.put(callback, changedPackages);
                }
            }

            for (String uidPackageName : uidPackageNames) {
                callbacks = mPackageModeWatchers.get(uidPackageName);
                if (callbacks != null) {
                    if (callbackSpecs == null) {
                        callbackSpecs = new ArrayMap<>();
                    }
                    final int callbackCount = callbacks.size();
                    for (int i = 0; i < callbackCount; i++) {
                        OnOpModeChangedListener callback = callbacks.valueAt(i);

                        if (onlyForeground && (callback.getFlags()
                                & WATCH_FOREGROUND_CHANGES) == 0) {
                            continue;
                        }

                        ArraySet<String> changedPackages = callbackSpecs.get(callback);
                        if (changedPackages == null) {
                            changedPackages = new ArraySet<>();
                            callbackSpecs.put(callback, changedPackages);
                        }
                        changedPackages.add(uidPackageName);
                    }
                }
            }

            if (callbackSpecs != null && callbackToIgnore != null) {
                callbackSpecs.remove(callbackToIgnore);
            }
        }

        if (callbackSpecs == null) {
            return;
        }

        for (int i = 0; i < callbackSpecs.size(); i++) {
            final OnOpModeChangedListener callback = callbackSpecs.keyAt(i);
            final ArraySet<String> reportedPackageNames = callbackSpecs.valueAt(i);
            if (reportedPackageNames == null) {
                mHandler.sendMessage(PooledLambda.obtainMessage(
                        AppOpsCheckingServiceImpl::notifyOpChanged,
                        this, callback, code, uid, (String) null));

            } else {
                final int reportedPackageCount = reportedPackageNames.size();
                for (int j = 0; j < reportedPackageCount; j++) {
                    final String reportedPackageName = reportedPackageNames.valueAt(j);
                    mHandler.sendMessage(PooledLambda.obtainMessage(
                            AppOpsCheckingServiceImpl::notifyOpChanged,
                            this, callback, code, uid, reportedPackageName));
                }
            }
        }
    }

    private static String[] getPackagesForUid(int uid) {
        String[] packageNames = null;

        // Very early during boot the package manager is not yet or not yet fully started. At this
        // time there are no packages yet.
        if (AppGlobals.getPackageManager() != null) {
            try {
                packageNames = AppGlobals.getPackageManager().getPackagesForUid(uid);
            } catch (RemoteException e) {
                /* ignore - local call */
            }
        }
        if (packageNames == null) {
            return EmptyArray.STRING;
        }
        return packageNames;
    }

    @Override
    public SparseBooleanArray evalForegroundUidOps(int uid, SparseBooleanArray foregroundOps) {
        synchronized (mLock) {
            return evalForegroundOps(mUidModes.get(uid), foregroundOps);
        }
    }

    @Override
    public SparseBooleanArray evalForegroundPackageOps(String packageName,
            SparseBooleanArray foregroundOps, @UserIdInt int userId) {
        synchronized (mLock) {
            ArrayMap<String, SparseIntArray> packageModes = mUserPackageModes.get(userId, null);
            return evalForegroundOps(packageModes == null ? null : packageModes.get(packageName),
                    foregroundOps);
        }
    }

    private SparseBooleanArray evalForegroundOps(SparseIntArray opModes,
            SparseBooleanArray foregroundOps) {
        SparseBooleanArray tempForegroundOps = foregroundOps;
        if (opModes != null) {
            for (int i = opModes.size() - 1; i >= 0; i--) {
                if (opModes.valueAt(i) == AppOpsManager.MODE_FOREGROUND) {
                    if (tempForegroundOps == null) {
                        tempForegroundOps = new SparseBooleanArray();
                    }
                    evalForegroundWatchers(opModes.keyAt(i), tempForegroundOps);
                }
            }
        }
        return tempForegroundOps;
    }

    private void evalForegroundWatchers(int op, SparseBooleanArray foregroundOps) {
        boolean curValue = foregroundOps.get(op, false);
        ArraySet<OnOpModeChangedListener> listenerSet = mOpModeWatchers.get(op);
        if (listenerSet != null) {
            for (int cbi = listenerSet.size() - 1; !curValue && cbi >= 0; cbi--) {
                if ((listenerSet.valueAt(cbi).getFlags()
                        & AppOpsManager.WATCH_FOREGROUND_CHANGES) != 0) {
                    curValue = true;
                }
            }
        }
        foregroundOps.put(op, curValue);
    }

    @Override
    public boolean dumpListeners(int dumpOp, int dumpUid, String dumpPackage,
            PrintWriter printWriter) {
        boolean needSep = false;
        if (mOpModeWatchers.size() > 0) {
            boolean printedHeader = false;
            for (int i = 0; i < mOpModeWatchers.size(); i++) {
                if (dumpOp >= 0 && dumpOp != mOpModeWatchers.keyAt(i)) {
                    continue;
                }
                boolean printedOpHeader = false;
                ArraySet<OnOpModeChangedListener> modeChangedListenerSet =
                        mOpModeWatchers.valueAt(i);
                for (int j = 0; j < modeChangedListenerSet.size(); j++) {
                    final OnOpModeChangedListener listener = modeChangedListenerSet.valueAt(j);
                    if (dumpPackage != null
                            && dumpUid != UserHandle.getAppId(listener.getWatchingUid())) {
                        continue;
                    }
                    needSep = true;
                    if (!printedHeader) {
                        printWriter.println("  Op mode watchers:");
                        printedHeader = true;
                    }
                    if (!printedOpHeader) {
                        printWriter.print("    Op ");
                        printWriter.print(AppOpsManager.opToName(mOpModeWatchers.keyAt(i)));
                        printWriter.println(":");
                        printedOpHeader = true;
                    }
                    printWriter.print("      #"); printWriter.print(j); printWriter.print(": ");
                    printWriter.println(listener.toString());
                }
            }
        }

        if (mPackageModeWatchers.size() > 0 && dumpOp < 0) {
            boolean printedHeader = false;
            for (int i = 0; i < mPackageModeWatchers.size(); i++) {
                if (dumpPackage != null
                        && !dumpPackage.equals(mPackageModeWatchers.keyAt(i))) {
                    continue;
                }
                needSep = true;
                if (!printedHeader) {
                    printWriter.println("  Package mode watchers:");
                    printedHeader = true;
                }
                printWriter.print("    Pkg "); printWriter.print(mPackageModeWatchers.keyAt(i));
                printWriter.println(":");
                ArraySet<OnOpModeChangedListener> modeChangedListenerSet =
                        mPackageModeWatchers.valueAt(i);

                for (int j = 0; j < modeChangedListenerSet.size(); j++) {
                    printWriter.print("      #"); printWriter.print(j); printWriter.print(": ");
                    printWriter.println(modeChangedListenerSet.valueAt(j).toString());
                }
            }
        }
        return needSep;
    }

}