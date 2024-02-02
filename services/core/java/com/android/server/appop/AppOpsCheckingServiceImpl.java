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

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.OP_NONE;
import static android.app.AppOpsManager.OP_SCHEDULE_EXACT_ALARM;
import static android.app.AppOpsManager.OP_USE_FULL_SCREEN_INTENT;
import static android.app.AppOpsManager.WATCH_FOREGROUND_CHANGES;
import static android.app.AppOpsManager.opRestrictsRead;
import static android.app.AppOpsManager.opToDefaultMode;

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
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserPackage;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

import libcore.util.EmptyArray;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;


/**
 * Legacy implementation for App-ops service's app-op mode (uid and package) storage and access.
 * In the future this class will also include mode callbacks and op restrictions.
 */
public class AppOpsCheckingServiceImpl implements AppOpsCheckingServiceInterface {

    static final String TAG = "LegacyAppOpsServiceInterfaceImpl";

    private static final boolean DEBUG = false;

    // Write at most every 30 minutes.
    private static final long WRITE_DELAY = DEBUG ? 1000 : 30 * 60 * 1000;

    /**
     * Sentinel integer version to denote that there was no appops.xml found on boot.
     * This will happen when a device boots with no existing userdata.
     */
    private static final int NO_FILE_VERSION = -2;

    /**
     * Sentinel integer version to denote that there was no version in the appops.xml found on boot.
     * This means the file is coming from a build before versioning was added.
     */
    private static final int NO_VERSION = -1;

    /**
     * Increment by one every time and add the corresponding upgrade logic in
     * {@link #upgradeLocked(int)} below. The first version was 1.
     */
    @VisibleForTesting
    static final int CURRENT_VERSION = 4;

    /**
     * This stores the version of appops.xml seen at boot. If this is smaller than
     * {@link #CURRENT_VERSION}, then we will run {@link #upgradeLocked(int)} on startup.
     */
    private int mVersionAtBoot = NO_FILE_VERSION;

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

    final AtomicFile mFile;
    final Runnable mWriteRunner = new Runnable() {
        public void run() {
            synchronized (mLock) {
                mWriteScheduled = false;
                mFastWriteScheduled = false;
                AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
                    @Override
                    protected Void doInBackground(Void... params) {
                        writeState();
                        return null;
                    }
                };
                task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);
            }
        }
    };

    boolean mWriteScheduled;
    boolean mFastWriteScheduled;


    // Constant meaning that any UID should be matched when dispatching callbacks
    private static final int UID_ANY = -2;

    AppOpsCheckingServiceImpl(File storageFile,
            @NonNull Object lock, Handler handler, Context context,
            SparseArray<int[]> switchedOps) {
        this.mFile = new AtomicFile(storageFile);
        this.mLock = lock;
        this.mHandler = handler;
        this.mContext = context;
        this.mSwitchedOps = switchedOps;
    }

    @Override
    public void systemReady() {
        synchronized (mLock) {
            // TODO: This file version upgrade code may still need to happen after we switch to
            //  another implementation of AppOpsCheckingServiceInterface.
            upgradeLocked(mVersionAtBoot);
        }
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
    public SparseIntArray getNonDefaultPackageModes(String packageName, int userId) {
        synchronized (mLock) {
            ArrayMap<String, SparseIntArray> packageModes = mUserPackageModes.get(userId);
            if (packageModes == null) {
                return new SparseIntArray();
            }
            SparseIntArray opModes = packageModes.get(packageName);
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
                if (mode == defaultMode) {
                    return false;
                }
                opModes = new SparseIntArray();
                mUidModes.put(uid, opModes);
                opModes.put(op, mode);
                scheduleWriteLocked();
            } else {
                if (opModes.indexOfKey(op) >= 0) {
                    if (opModes.get(op) == mode) {
                        return false;
                    }
                } else if (mode == defaultMode) {
                    return false;
                }
                if (mode == defaultMode) {
                    opModes.delete(op);
                    if (opModes.size() <= 0) {
                        mUidModes.delete(uid);
                    }
                } else {
                    opModes.put(op, mode);
                }
                scheduleWriteLocked();
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
                    scheduleWriteLocked();
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
                scheduleWriteLocked();
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
            scheduleFastWriteLocked();
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
                scheduleFastWriteLocked();
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

    private void scheduleWriteLocked() {
        if (!mWriteScheduled) {
            mWriteScheduled = true;
            mHandler.postDelayed(mWriteRunner, WRITE_DELAY);
        }
    }

    private void scheduleFastWriteLocked() {
        if (!mFastWriteScheduled) {
            mWriteScheduled = true;
            mFastWriteScheduled = true;
            mHandler.removeCallbacks(mWriteRunner);
            mHandler.postDelayed(mWriteRunner, 10 * 1000);
        }
    }

    @Override
    public void writeState() {
        synchronized (mFile) {
            FileOutputStream stream;
            try {
                stream = mFile.startWrite();
            } catch (IOException e) {
                Slog.w(TAG, "Failed to write state: " + e);
                return;
            }

            try {
                TypedXmlSerializer out = Xml.resolveSerializer(stream);
                out.startDocument(null, true);
                out.startTag(null, "app-ops");
                out.attributeInt(null, "v", CURRENT_VERSION);

                SparseArray<SparseIntArray> uidModesCopy = new SparseArray<>();
                SparseArray<ArrayMap<String, SparseIntArray>> userPackageModesCopy =
                        new SparseArray<>();
                int uidModesSize;
                int usersSize;
                synchronized (mLock) {
                    uidModesSize = mUidModes.size();
                    for (int uidIdx = 0; uidIdx < uidModesSize; uidIdx++) {
                        int uid = mUidModes.keyAt(uidIdx);
                        SparseIntArray modes = mUidModes.valueAt(uidIdx);
                        uidModesCopy.put(uid, modes.clone());
                    }
                    usersSize = mUserPackageModes.size();
                    for (int userIdx = 0; userIdx < usersSize; userIdx++) {
                        int user = mUserPackageModes.keyAt(userIdx);
                        ArrayMap<String, SparseIntArray> packageModes =
                                mUserPackageModes.valueAt(userIdx);
                        ArrayMap<String, SparseIntArray> packageModesCopy = new ArrayMap<>();
                        userPackageModesCopy.put(user, packageModesCopy);
                        for (int pkgIdx = 0, packageModesSize = packageModes.size();
                                pkgIdx < packageModesSize; pkgIdx++) {
                            String pkg = packageModes.keyAt(pkgIdx);
                            SparseIntArray modes = packageModes.valueAt(pkgIdx);
                            packageModesCopy.put(pkg, modes.clone());
                        }
                    }
                }

                for (int uidStateNum = 0; uidStateNum < uidModesSize; uidStateNum++) {
                    int uid = uidModesCopy.keyAt(uidStateNum);
                    SparseIntArray modes = uidModesCopy.valueAt(uidStateNum);

                    out.startTag(null, "uid");
                    out.attributeInt(null, "n", uid);

                    final int modesSize = modes.size();
                    for (int modeIdx = 0; modeIdx < modesSize; modeIdx++) {
                        final int op = modes.keyAt(modeIdx);
                        final int mode = modes.valueAt(modeIdx);
                        out.startTag(null, "op");
                        out.attributeInt(null, "n", op);
                        out.attributeInt(null, "m", mode);
                        out.endTag(null, "op");
                    }
                    out.endTag(null, "uid");
                }

                for (int userIdx = 0; userIdx < usersSize; userIdx++) {
                    int userId = userPackageModesCopy.keyAt(userIdx);
                    ArrayMap<String, SparseIntArray> packageModes =
                            userPackageModesCopy.valueAt(userIdx);

                    out.startTag(null, "user");
                    out.attributeInt(null, "n", userId);

                    int packageModesSize = packageModes.size();
                    for (int pkgIdx = 0; pkgIdx < packageModesSize; pkgIdx++) {
                        String pkg = packageModes.keyAt(pkgIdx);
                        SparseIntArray modes = packageModes.valueAt(pkgIdx);

                        out.startTag(null, "pkg");
                        out.attribute(null, "n", pkg);

                        final int modesSize = modes.size();
                        for (int modeIdx = 0; modeIdx < modesSize; modeIdx++) {
                            final int op = modes.keyAt(modeIdx);
                            final int mode = modes.valueAt(modeIdx);

                            out.startTag(null, "op");
                            out.attributeInt(null, "n", op);
                            out.attributeInt(null, "m", mode);
                            out.endTag(null, "op");
                        }
                        out.endTag(null, "pkg");
                    }
                    out.endTag(null, "user");
                }

                out.endTag(null, "app-ops");
                out.endDocument();
                mFile.finishWrite(stream);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to write state, restoring backup.", e);
                mFile.failWrite(stream);
            }
        }
    }

    /* Current format
        <uid>
          <op>
        </uid>

        <user>
          <pkg>
            <op>
          </pkg>
        </user>
     */

    @Override
    public void readState() {
        synchronized (mFile) {
            synchronized (mLock) {
                FileInputStream stream;
                try {
                    stream = mFile.openRead();
                } catch (FileNotFoundException e) {
                    Slog.i(TAG, "No existing app ops " + mFile.getBaseFile() + "; starting empty");
                    mVersionAtBoot = NO_FILE_VERSION;
                    return;
                }

                try {
                    TypedXmlPullParser parser = Xml.resolvePullParser(stream);
                    int type;
                    while ((type = parser.next()) != XmlPullParser.START_TAG
                            && type != XmlPullParser.END_DOCUMENT) {
                        // Parse next until we reach the start or end
                    }

                    if (type != XmlPullParser.START_TAG) {
                        throw new IllegalStateException("no start tag found");
                    }

                    mVersionAtBoot = parser.getAttributeInt(null, "v", NO_VERSION);

                    int outerDepth = parser.getDepth();
                    while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                            && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                        if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                            continue;
                        }

                        String tagName = parser.getName();
                        if (tagName.equals("pkg")) {
                            // version 2 has the structure pkg -> uid -> op ->
                            // in version 3, since pkg and uid states are kept completely
                            // independent we switch to user -> pkg -> op
                            readPackage(parser);
                        } else if (tagName.equals("uid")) {
                            readUidOps(parser);
                        } else if (tagName.equals("user")) {
                            readUser(parser);
                        } else {
                            Slog.w(TAG, "Unknown element under <app-ops>: "
                                    + parser.getName());
                            XmlUtils.skipCurrentTag(parser);
                        }
                    }
                    return;
                } catch (XmlPullParserException e) {
                    throw new RuntimeException(e);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @Override
    public void shutdown() {
        boolean doWrite = false;
        synchronized (this) {
            if (mWriteScheduled) {
                mWriteScheduled = false;
                mFastWriteScheduled = false;
                mHandler.removeCallbacks(mWriteRunner);
                doWrite = true;
            }
        }
        if (doWrite) {
            writeState();
        }
    }

    @GuardedBy("mLock")
    private void readUidOps(TypedXmlPullParser parser) throws NumberFormatException,
            XmlPullParserException, IOException {
        final int uid = parser.getAttributeInt(null, "n");
        SparseIntArray modes = mUidModes.get(uid);
        if (modes == null) {
            modes = new SparseIntArray();
            mUidModes.put(uid, modes);
        }

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("op")) {
                final int code = parser.getAttributeInt(null, "n");
                final int mode = parser.getAttributeInt(null, "m");

                if (mode != opToDefaultMode(code)) {
                    modes.put(code, mode);
                }
            } else {
                Slog.w(TAG, "Unknown element under <uid>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    /*
     * Used for migration when pkg is the depth=1 tag
     */
    @GuardedBy("mLock")
    private void readPackage(TypedXmlPullParser parser)
            throws NumberFormatException, XmlPullParserException, IOException {
        String pkgName = parser.getAttributeValue(null, "n");
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("uid")) {
                readUid(parser, pkgName);
            } else {
                Slog.w(TAG, "Unknown element under <pkg>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    /*
     * Used for migration when uid is the depth=2 tag
     */
    @GuardedBy("mLock")
    private void readUid(TypedXmlPullParser parser, String pkgName)
            throws NumberFormatException, XmlPullParserException, IOException {
        int userId = UserHandle.getUserId(parser.getAttributeInt(null, "n"));
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("op")) {
                readOp(parser, userId, pkgName);
            } else {
                Slog.w(TAG, "Unknown element under <pkg>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    @GuardedBy("mLock")
    private void readUser(TypedXmlPullParser parser)
            throws NumberFormatException, XmlPullParserException, IOException {
        int userId = parser.getAttributeInt(null, "n");
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("pkg")) {
                readPackage(parser, userId);
            } else {
                Slog.w(TAG, "Unknown element under <user>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    @GuardedBy("mLock")
    private void readPackage(TypedXmlPullParser parser, int userId)
            throws NumberFormatException, XmlPullParserException, IOException {
        String pkgName = parser.getAttributeValue(null, "n");
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("op")) {
                readOp(parser, userId, pkgName);
            } else {
                Slog.w(TAG, "Unknown element under <pkg>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    @GuardedBy("mLock")
    private void readOp(TypedXmlPullParser parser, int userId, @NonNull String pkgName)
            throws NumberFormatException, XmlPullParserException {
        final int opCode = parser.getAttributeInt(null, "n");
        final int defaultMode = AppOpsManager.opToDefaultMode(opCode);
        final int mode = parser.getAttributeInt(null, "m", defaultMode);

        if (mode != defaultMode) {
            ArrayMap<String, SparseIntArray> packageModes = mUserPackageModes.get(userId);
            if (packageModes == null) {
                packageModes = new ArrayMap<>();
                mUserPackageModes.put(userId, packageModes);
            }

            SparseIntArray modes = packageModes.get(pkgName);
            if (modes == null) {
                modes = new SparseIntArray();
                packageModes.put(pkgName, modes);
            }

            modes.put(opCode, mode);
        }
    }

    @GuardedBy("mLock")
    private void upgradeLocked(int oldVersion) {
        if (oldVersion == NO_FILE_VERSION || oldVersion >= CURRENT_VERSION) {
            return;
        }
        Slog.d(TAG, "Upgrading app-ops xml from version " + oldVersion + " to " + CURRENT_VERSION);
        switch (oldVersion) {
            case NO_VERSION:
                upgradeRunAnyInBackgroundLocked();
                // fall through
            case 1:
                upgradeScheduleExactAlarmLocked();
                // fall through
            case 2:
                // split the appops.xml into appops.xml to store appop state and apppops_access.xml
                // to store app-op access.
                // fall through
            case 3:
                resetUseFullScreenIntentLocked();
                // fall through
        }
        scheduleFastWriteLocked();
    }

    /**
     * For all installed apps at time of upgrade, OP_RUN_ANY_IN_BACKGROUND will inherit the mode
     *  from RUN_IN_BACKGROUND.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    void upgradeRunAnyInBackgroundLocked() {
        final int uidModesSize = mUidModes.size();
        for (int uidIdx = 0; uidIdx < uidModesSize; uidIdx++) {
            SparseIntArray modesForUid = mUidModes.valueAt(uidIdx);

            final int idx = modesForUid.indexOfKey(AppOpsManager.OP_RUN_IN_BACKGROUND);
            if (idx >= 0) {
                // Only non-default should exist in the map
                modesForUid.put(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, modesForUid.valueAt(idx));
            }
        }

        final int usersSize = mUserPackageModes.size();
        for (int userIdx = 0; userIdx < usersSize; userIdx++) {
            ArrayMap<String, SparseIntArray> packageModes =
                    mUserPackageModes.valueAt(userIdx);

            for (int pkgIdx = 0, packageModesSize = packageModes.size();
                    pkgIdx < packageModesSize; pkgIdx++) {
                SparseIntArray modes = packageModes.valueAt(pkgIdx);

                final int idx = modes.indexOfKey(AppOpsManager.OP_RUN_IN_BACKGROUND);
                if (idx >= 0) {
                    // Only non-default should exist in the map
                    modes.put(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, modes.valueAt(idx));
                }
            }
        }
    }

    /**
     * The interpretation of the default mode - MODE_DEFAULT - for OP_SCHEDULE_EXACT_ALARM is
     * changing. Simultaneously, we want to change this op's mode from MODE_DEFAULT to MODE_ALLOWED
     * for already installed apps. For newer apps, it will stay as MODE_DEFAULT.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    void upgradeScheduleExactAlarmLocked() {
        final PermissionManagerServiceInternal pmsi = LocalServices.getService(
                PermissionManagerServiceInternal.class);
        final UserManagerInternal umi = LocalServices.getService(UserManagerInternal.class);
        final PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);

        final String[] packagesDeclaringPermission = pmsi.getAppOpPermissionPackages(
                AppOpsManager.opToPermission(OP_SCHEDULE_EXACT_ALARM));
        final int[] userIds = umi.getUserIds();

        for (final String pkg : packagesDeclaringPermission) {
            for (int userId : userIds) {
                final int uid = pmi.getPackageUid(pkg, 0, userId);
                final int oldMode = getUidMode(uid, OP_SCHEDULE_EXACT_ALARM);
                if (oldMode == AppOpsManager.opToDefaultMode(OP_SCHEDULE_EXACT_ALARM)) {
                    setUidMode(uid, OP_SCHEDULE_EXACT_ALARM, MODE_ALLOWED);
                }
            }
            // This appop is meant to be controlled at a uid level. So we leave package modes as
            // they are.
        }
    }

    /**
     * A cleanup step for U Beta 2 that reverts the OP_USE_FULL_SCREEN_INTENT's mode to MODE_DEFAULT
     * if the permission flags for the USE_FULL_SCREEN_INTENT permission does not have USER_SET.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    void resetUseFullScreenIntentLocked() {
        final PermissionManagerServiceInternal pmsi = LocalServices.getService(
                PermissionManagerServiceInternal.class);
        final UserManagerInternal umi = LocalServices.getService(UserManagerInternal.class);
        final PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);
        final PermissionManager permissionManager =
                mContext.getSystemService(PermissionManager.class);

        final String permissionName = AppOpsManager.opToPermission(OP_USE_FULL_SCREEN_INTENT);
        final String[] packagesDeclaringPermission =
            pmsi.getAppOpPermissionPackages(permissionName);
        final int[] userIds = umi.getUserIds();

        for (final String pkg : packagesDeclaringPermission) {
            for (int userId : userIds) {
                final int uid = pmi.getPackageUid(pkg, 0, userId);
                final int flags = permissionManager.getPermissionFlags(pkg, permissionName,
                        UserHandle.of(userId));
                if ((flags & PackageManager.FLAG_PERMISSION_USER_SET) == 0) {
                    setUidMode(uid, OP_USE_FULL_SCREEN_INTENT,
                            AppOpsManager.opToDefaultMode(OP_USE_FULL_SCREEN_INTENT));
                }
            }
        }
    }

    @VisibleForTesting
    List<Integer> getUidsWithNonDefaultModes() {
        List<Integer> result = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < mUidModes.size(); i++) {
                SparseIntArray modes = mUidModes.valueAt(i);
                if (modes.size() > 0) {
                    result.add(mUidModes.keyAt(i));
                }
            }
        }

        return result;
    }

    @VisibleForTesting
    List<UserPackage> getPackagesWithNonDefaultModes() {
        List<UserPackage> result = new ArrayList<>();
        synchronized (mLock) {
            for (int i = 0; i < mUserPackageModes.size(); i++) {
                ArrayMap<String, SparseIntArray> packageModes = mUserPackageModes.valueAt(i);
                for (int j = 0; j < packageModes.size(); j++) {
                    SparseIntArray modes = packageModes.valueAt(j);
                    if (modes.size() > 0) {
                        result.add(
                                UserPackage.of(mUserPackageModes.keyAt(i), packageModes.keyAt(j)));
                    }
                }
            }
        }

        return result;
    }
}