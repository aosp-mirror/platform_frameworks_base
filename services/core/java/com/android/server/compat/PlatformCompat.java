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

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;
import android.util.StatsLog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.compat.AndroidBuildClassifier;
import com.android.internal.compat.ChangeReporter;
import com.android.internal.compat.CompatibilityChangeConfig;
import com.android.internal.compat.CompatibilityChangeInfo;
import com.android.internal.compat.IOverrideValidator;
import com.android.internal.compat.IPlatformCompat;
import com.android.internal.util.DumpUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * System server internal API for gating and reporting compatibility changes.
 */
public class PlatformCompat extends IPlatformCompat.Stub {

    private static final String TAG = "Compatibility";

    private final Context mContext;
    private final ChangeReporter mChangeReporter;
    private final CompatConfig mCompatConfig;

    public PlatformCompat(Context context) {
        mContext = context;
        mChangeReporter = new ChangeReporter(
                StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__SOURCE__SYSTEM_SERVER);
        mCompatConfig = CompatConfig.create(new AndroidBuildClassifier(), mContext);
    }

    @VisibleForTesting
    PlatformCompat(Context context, CompatConfig compatConfig) {
        mContext = context;
        mChangeReporter = new ChangeReporter(
                StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__SOURCE__SYSTEM_SERVER);
        mCompatConfig = compatConfig;
    }

    @Override
    public void reportChange(long changeId, ApplicationInfo appInfo) {
        reportChange(changeId, appInfo.uid,
                StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__STATE__LOGGED);
    }

    @Override
    public void reportChangeByPackageName(long changeId, String packageName, int userId) {
        ApplicationInfo appInfo = getApplicationInfo(packageName, userId);
        if (appInfo == null) {
            return;
        }
        reportChange(changeId, appInfo);
    }

    @Override
    public void reportChangeByUid(long changeId, int uid) {
        reportChange(changeId, uid, StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__STATE__LOGGED);
    }

    @Override
    public boolean isChangeEnabled(long changeId, ApplicationInfo appInfo) {
        if (mCompatConfig.isChangeEnabled(changeId, appInfo)) {
            reportChange(changeId, appInfo.uid,
                    StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__STATE__ENABLED);
            return true;
        }
        reportChange(changeId, appInfo.uid,
                StatsLog.APP_COMPATIBILITY_CHANGE_REPORTED__STATE__DISABLED);
        return false;
    }

    @Override
    public boolean isChangeEnabledByPackageName(long changeId, String packageName, int userId) {
        ApplicationInfo appInfo = getApplicationInfo(packageName, userId);
        if (appInfo == null) {
            return true;
        }
        return isChangeEnabled(changeId, appInfo);
    }

    @Override
    public boolean isChangeEnabledByUid(long changeId, int uid) {
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
        mCompatConfig.addOverrides(overrides, packageName);
        killPackage(packageName);
    }

    @Override
    public void setOverridesForTest(CompatibilityChangeConfig overrides, String packageName)
            throws RemoteException, SecurityException {
        mCompatConfig.addOverrides(overrides, packageName);
    }

    @Override
    public void clearOverrides(String packageName) throws RemoteException, SecurityException {
        mCompatConfig.removePackageOverrides(packageName);
        killPackage(packageName);
    }

    @Override
    public void clearOverridesForTest(String packageName)
            throws RemoteException, SecurityException {
        mCompatConfig.removePackageOverrides(packageName);
    }

    @Override
    public boolean clearOverride(long changeId, String packageName)
            throws RemoteException, SecurityException {
        boolean existed = mCompatConfig.removeOverride(changeId, packageName);
        killPackage(packageName);
        return existed;
    }

    @Override
    public CompatibilityChangeConfig getAppConfig(ApplicationInfo appInfo) {
        return mCompatConfig.getAppConfig(appInfo);
    }

    @Override
    public CompatibilityChangeInfo[] listAllChanges() {
        return mCompatConfig.dumpChanges();
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
        try {
            return mContext.getPackageManager().getApplicationInfoAsUser(packageName, 0, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "No installed package " + packageName);
        }
        return null;
    }

    private void reportChange(long changeId, int uid, int state) {
        mChangeReporter.reportChange(uid, changeId, state);
    }

    private void killPackage(String packageName) {
        int uid = -1;
        try {
            uid = mContext.getPackageManager().getPackageUid(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Didn't find package " + packageName + " on device.", e);
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
}
