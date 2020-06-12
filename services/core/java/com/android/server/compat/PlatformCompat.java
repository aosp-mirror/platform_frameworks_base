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

package com.android.server.compat;

import static android.Manifest.permission.LOG_COMPAT_CHANGE;
import static android.Manifest.permission.OVERRIDE_COMPAT_CHANGE_CONFIG;
import static android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.Build;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.compat.AndroidBuildClassifier;
import com.android.internal.compat.ChangeReporter;
import com.android.internal.compat.CompatibilityChangeConfig;
import com.android.internal.compat.CompatibilityChangeInfo;
import com.android.internal.compat.IOverrideValidator;
import com.android.internal.compat.IPlatformCompat;
import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;

/**
 * System server internal API for gating and reporting compatibility changes.
 */
public class PlatformCompat extends IPlatformCompat.Stub {

    private static final String TAG = "Compatibility";

    private final Context mContext;
    private final ChangeReporter mChangeReporter;
    private final CompatConfig mCompatConfig;

    private static int sMinTargetSdk = Build.VERSION_CODES.P;
    private static int sMaxTargetSdk = Build.VERSION_CODES.Q;

    public PlatformCompat(Context context) {
        mContext = context;
        mChangeReporter = new ChangeReporter(
                ChangeReporter.SOURCE_SYSTEM_SERVER);
        mCompatConfig = CompatConfig.create(new AndroidBuildClassifier(), mContext);
    }

    @VisibleForTesting
    PlatformCompat(Context context, CompatConfig compatConfig) {
        mContext = context;
        mChangeReporter = new ChangeReporter(
                ChangeReporter.SOURCE_SYSTEM_SERVER);
        mCompatConfig = compatConfig;
    }

    @Override
    public void reportChange(long changeId, ApplicationInfo appInfo) {
        checkCompatChangeLogPermission();
        reportChange(changeId, appInfo.uid,
                ChangeReporter.STATE_LOGGED);
    }

    @Override
    public void reportChangeByPackageName(long changeId, String packageName, int userId) {
        checkCompatChangeLogPermission();
        ApplicationInfo appInfo = getApplicationInfo(packageName, userId);
        if (appInfo == null) {
            return;
        }
        reportChange(changeId, appInfo);
    }

    @Override
    public void reportChangeByUid(long changeId, int uid) {
        checkCompatChangeLogPermission();
        reportChange(changeId, uid, ChangeReporter.STATE_LOGGED);
    }

    @Override
    public boolean isChangeEnabled(long changeId, ApplicationInfo appInfo) {
        checkCompatChangeReadAndLogPermission();
        return isChangeEnabledInternal(changeId, appInfo);
    }

    /**
     * Internal version of the above method. Does not perform costly permission check.
     */
    public boolean isChangeEnabledInternal(long changeId, ApplicationInfo appInfo) {
        if (mCompatConfig.isChangeEnabled(changeId, appInfo)) {
            reportChange(changeId, appInfo.uid,
                    ChangeReporter.STATE_ENABLED);
            return true;
        }
        reportChange(changeId, appInfo.uid,
                ChangeReporter.STATE_DISABLED);
        return false;
    }

    @Override
    public boolean isChangeEnabledByPackageName(long changeId, String packageName,
            @UserIdInt int userId) {
        checkCompatChangeReadAndLogPermission();
        ApplicationInfo appInfo = getApplicationInfo(packageName, userId);
        if (appInfo == null) {
            return true;
        }
        return isChangeEnabled(changeId, appInfo);
    }

    @Override
    public boolean isChangeEnabledByUid(long changeId, int uid) {
        checkCompatChangeReadAndLogPermission();
        String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length == 0) {
            return true;
        }
        boolean enabled = true;
        for (String packageName : packages) {
            enabled = enabled && isChangeEnabledByPackageName(changeId, packageName,
                    UserHandle.getUserId(uid));
        }
        return enabled;
    }

    /**
     * Register a listener for change state overrides. Only one listener per change is allowed.
     *
     * <p>{@code listener.onCompatChange(String)} method is guaranteed to be called with
     * packageName before the app is killed upon an override change. The state of a change is not
     * guaranteed to change when {@code listener.onCompatChange(String)} is called.
     *
     * @param changeId to get updates for
     * @param listener the listener that will be called upon a potential change for package.
     * @throws IllegalStateException if a listener was already registered for changeId
     * @returns {@code true} if a change with changeId was already known, or (@code false}
     * otherwise.
     */
    public boolean registerListener(long changeId, CompatChange.ChangeListener listener) {
        return mCompatConfig.registerListener(changeId, listener);
    }

    @Override
    public void setOverrides(CompatibilityChangeConfig overrides, String packageName)
            throws RemoteException, SecurityException {
        checkCompatChangeOverridePermission();
        mCompatConfig.addOverrides(overrides, packageName);
        killPackage(packageName);
    }

    @Override
    public void setOverridesForTest(CompatibilityChangeConfig overrides, String packageName)
            throws RemoteException, SecurityException {
        checkCompatChangeOverridePermission();
        mCompatConfig.addOverrides(overrides, packageName);
    }

    @Override
    public int enableTargetSdkChanges(String packageName, int targetSdkVersion)
            throws RemoteException, SecurityException {
        checkCompatChangeOverridePermission();
        int numChanges = mCompatConfig.enableTargetSdkChangesForPackage(packageName,
                                                                        targetSdkVersion);
        killPackage(packageName);
        return numChanges;
    }

    @Override
    public int disableTargetSdkChanges(String packageName, int targetSdkVersion)
            throws RemoteException, SecurityException {
        checkCompatChangeOverridePermission();
        int numChanges = mCompatConfig.disableTargetSdkChangesForPackage(packageName,
                                                                         targetSdkVersion);
        killPackage(packageName);
        return numChanges;
    }

    @Override
    public void clearOverrides(String packageName) throws RemoteException, SecurityException {
        checkCompatChangeOverridePermission();
        mCompatConfig.removePackageOverrides(packageName);
        killPackage(packageName);
    }

    @Override
    public void clearOverridesForTest(String packageName)
            throws RemoteException, SecurityException {
        checkCompatChangeOverridePermission();
        mCompatConfig.removePackageOverrides(packageName);
    }

    @Override
    public boolean clearOverride(long changeId, String packageName)
            throws RemoteException, SecurityException {
        checkCompatChangeOverridePermission();
        boolean existed = mCompatConfig.removeOverride(changeId, packageName);
        killPackage(packageName);
        return existed;
    }

    @Override
    public CompatibilityChangeConfig getAppConfig(ApplicationInfo appInfo) {
        checkCompatChangeReadAndLogPermission();
        return mCompatConfig.getAppConfig(appInfo);
    }

    @Override
    public CompatibilityChangeInfo[] listAllChanges() {
        checkCompatChangeReadPermission();
        return mCompatConfig.dumpChanges();
    }

    @Override
    public CompatibilityChangeInfo[] listUIChanges() {
        return Arrays.stream(listAllChanges()).filter(
                x -> isShownInUI(x)).toArray(CompatibilityChangeInfo[]::new);
    }

    /**
     * Check whether the change is known to the compat config.
     *
     * @return {@code true} if the change is known.
     */
    public boolean isKnownChangeId(long changeId) {
        return mCompatConfig.isKnownChangeId(changeId);

    }

    /**
     * Retrieves the set of disabled changes for a given app. Any change ID not in the returned
     * array is by default enabled for the app.
     *
     * @param appInfo The app in question
     * @return A sorted long array of change IDs. We use a primitive array to minimize memory
     * footprint: Every app process will store this array statically so we aim to reduce
     * overhead as much as possible.
     */
    public long[] getDisabledChanges(ApplicationInfo appInfo) {
        return mCompatConfig.getDisabledChanges(appInfo);
    }

    /**
     * Look up a change ID by name.
     *
     * @param name Name of the change to look up
     * @return The change ID, or {@code -1} if no change with that name exists.
     */
    public long lookupChangeId(String name) {
        return mCompatConfig.lookupChangeId(name);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, "platform_compat", pw)) return;
        checkCompatChangeReadAndLogPermission();
        mCompatConfig.dumpConfig(pw);
    }

    @Override
    public IOverrideValidator getOverrideValidator() {
        return mCompatConfig.getOverrideValidator();
    }

    /**
     * Clears information stored about events reported on behalf of an app.
     * To be called once upon app start or end. A second call would be a no-op.
     *
     * @param appInfo the app to reset
     */
    public void resetReporting(ApplicationInfo appInfo) {
        mChangeReporter.resetReportedChanges(appInfo.uid);
    }

    private ApplicationInfo getApplicationInfo(String packageName, int userId) {
        return LocalServices.getService(PackageManagerInternal.class).getApplicationInfo(
                packageName, 0, userId, userId);
    }

    private void reportChange(long changeId, int uid, int state) {
        mChangeReporter.reportChange(uid, changeId, state);
    }

    private void killPackage(String packageName) {
        int uid = LocalServices.getService(PackageManagerInternal.class).getPackageUid(packageName,
                    0, UserHandle.myUserId());

        if (uid < 0) {
            Slog.w(TAG, "Didn't find package " + packageName + " on device.");
            return;
        }

        Slog.d(TAG, "Killing package " + packageName + " (UID " + uid + ").");
        killUid(UserHandle.getAppId(uid),
                UserHandle.USER_ALL, "PlatformCompat overrides");
    }

    private void killUid(int appId, int userId, String reason) {
        final long identity = Binder.clearCallingIdentity();
        try {
            IActivityManager am = ActivityManager.getService();
            if (am != null) {
                try {
                    am.killUid(appId, userId, reason);
                } catch (RemoteException e) {
                    /* ignore - same process */
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void checkCompatChangeLogPermission() throws SecurityException {
        if (mContext.checkCallingOrSelfPermission(LOG_COMPAT_CHANGE)
                != PERMISSION_GRANTED) {
            throw new SecurityException("Cannot log compat change usage");
        }
    }

    private void checkCompatChangeReadPermission() throws SecurityException {
        if (mContext.checkCallingOrSelfPermission(READ_COMPAT_CHANGE_CONFIG)
                != PERMISSION_GRANTED) {
            throw new SecurityException("Cannot read compat change");
        }
    }

    private void checkCompatChangeOverridePermission() throws SecurityException {
        if (mContext.checkCallingOrSelfPermission(OVERRIDE_COMPAT_CHANGE_CONFIG)
                != PERMISSION_GRANTED) {
            throw new SecurityException("Cannot override compat change");
        }
    }

    private void checkCompatChangeReadAndLogPermission() throws SecurityException {
        checkCompatChangeReadPermission();
        checkCompatChangeLogPermission();
    }

    private boolean isShownInUI(CompatibilityChangeInfo change) {
        if (change.getLoggingOnly()) {
            return false;
        }
        if (change.getEnableAfterTargetSdk() > 0) {
            if (change.getEnableAfterTargetSdk() < sMinTargetSdk
                    || change.getEnableAfterTargetSdk() > sMaxTargetSdk) {
                return false;
            }
        }
        return true;
    }
}
