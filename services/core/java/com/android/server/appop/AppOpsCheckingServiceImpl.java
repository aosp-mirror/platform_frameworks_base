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
import static android.app.AppOpsManager.MODE_FOREGROUND;
import static android.app.AppOpsManager.OP_SCHEDULE_EXACT_ALARM;
import static android.app.AppOpsManager.OP_USE_FULL_SCREEN_INTENT;
import static android.companion.virtual.VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.app.AppOpsManager.Mode;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserPackage;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

    private final LegacyAppOpStateParser mAppOpsStateParser = new LegacyAppOpStateParser();
    @GuardedBy("mLock")
    private List<AppOpsModeChangedListener> mModeChangedListeners = new ArrayList<>();

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
    public SparseIntArray getNonDefaultUidModes(int uid, String persistentDeviceId) {
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
    public int getUidMode(int uid, String persistentDeviceId, int op) {
        synchronized (mLock) {
            SparseIntArray opModes = mUidModes.get(uid, null);
            if (opModes == null) {
                return AppOpsManager.opToDefaultMode(op);
            }
            return opModes.get(op, AppOpsManager.opToDefaultMode(op));
        }
    }

    @Override
    public boolean setUidMode(int uid, String persistentDeviceId, int op, int mode) {
        final int defaultMode = AppOpsManager.opToDefaultMode(op);
        List<AppOpsModeChangedListener> listenersCopy;
        synchronized (mLock) {
            SparseIntArray opModes = mUidModes.get(uid, null);

            int previousMode = defaultMode;
            if (opModes != null) {
                previousMode = opModes.get(op, defaultMode);
            }
            if (mode == previousMode) {
                return false;
            }

            if (mode == defaultMode) {
                opModes.delete(op);
                if (opModes.size() == 0) {
                    mUidModes.remove(uid);
                }
            } else {
                if (opModes == null) {
                    opModes = new SparseIntArray();
                    mUidModes.put(uid, opModes);
                }
                opModes.put(op, mode);
            }

            scheduleWriteLocked();
            listenersCopy = new ArrayList<>(mModeChangedListeners);
        }

        for (int i = 0; i < listenersCopy.size(); i++) {
            listenersCopy.get(i).onUidModeChanged(uid, op, mode);
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
        List<AppOpsModeChangedListener> listenersCopy;
        synchronized (mLock) {
            ArrayMap<String, SparseIntArray> packageModes = mUserPackageModes.get(userId, null);
            if (packageModes == null && mode != defaultMode) {
                packageModes = new ArrayMap<>();
                mUserPackageModes.put(userId, packageModes);
            }
            SparseIntArray opModes = null;
            int previousMode = defaultMode;
            if (packageModes != null) {
                opModes = packageModes.get(packageName);
                if (opModes != null) {
                    previousMode = opModes.get(op, defaultMode);
                }
            }

            if (mode == previousMode) {
                return;
            }

            if (mode == defaultMode) {
                opModes.delete(op);
                if (opModes.size() == 0) {
                    packageModes.remove(packageName);
                    if (packageModes.size() == 0) {
                        mUserPackageModes.remove(userId);
                    }
                }
            } else {
                if (packageModes == null) {
                    packageModes = new ArrayMap<>();
                    mUserPackageModes.put(userId, packageModes);
                }
                if (opModes == null) {
                    opModes = new SparseIntArray();
                    packageModes.put(packageName, opModes);
                }
                opModes.put(op, mode);
            }

            scheduleFastWriteLocked();
            listenersCopy = new ArrayList<>(mModeChangedListeners);
        }

        for (int i = 0; i < listenersCopy.size(); i++) {
            listenersCopy.get(i).onPackageModeChanged(packageName, userId, op, mode);
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
    public SparseBooleanArray getForegroundOps(int uid, String persistentDeviceId) {
        SparseBooleanArray result = new SparseBooleanArray();
        synchronized (mLock) {
            SparseIntArray modes = mUidModes.get(uid);
            if (modes == null) {
                return result;
            }
            for (int i = 0; i < modes.size(); i++) {
                if (modes.valueAt(i) == MODE_FOREGROUND) {
                    result.put(modes.keyAt(i), true);
                }
            }
        }

        return result;
    }

    @Override
    public SparseBooleanArray getForegroundOps(String packageName, int userId) {
        SparseBooleanArray result = new SparseBooleanArray();
        synchronized (mLock) {
            ArrayMap<String, SparseIntArray> packageModes = mUserPackageModes.get(userId);
            if (packageModes == null) {
                return result;
            }
            SparseIntArray modes = packageModes.get(packageName);
            if (modes == null) {
                return result;
            }
            for (int i = 0; i < modes.size(); i++) {
                if (modes.valueAt(i) == MODE_FOREGROUND) {
                    result.put(modes.keyAt(i), true);
                }
            }
        }

        return result;
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
                mVersionAtBoot = mAppOpsStateParser.readState(mFile, mUidModes, mUserPackageModes);
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
                final int oldMode =
                        getUidMode(
                                uid,
                                PERSISTENT_DEVICE_ID_DEFAULT,
                                OP_SCHEDULE_EXACT_ALARM);
                if (oldMode == AppOpsManager.opToDefaultMode(OP_SCHEDULE_EXACT_ALARM)) {
                    setUidMode(
                            uid,
                            PERSISTENT_DEVICE_ID_DEFAULT,
                            OP_SCHEDULE_EXACT_ALARM,
                            MODE_ALLOWED);
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
                    setUidMode(
                            uid,
                            PERSISTENT_DEVICE_ID_DEFAULT,
                            OP_USE_FULL_SCREEN_INTENT,
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

    @Override
    public boolean addAppOpsModeChangedListener(AppOpsModeChangedListener listener) {
        synchronized (mLock) {
            return mModeChangedListeners.add(listener);
        }
    }

    @Override
    public boolean removeAppOpsModeChangedListener(AppOpsModeChangedListener listener) {
        synchronized (mLock) {
            return mModeChangedListeners.remove(listener);
        }
    }
}
