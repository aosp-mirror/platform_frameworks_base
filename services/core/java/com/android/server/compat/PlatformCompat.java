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
import static android.Manifest.permission.OVERRIDE_COMPAT_CHANGE_CONFIG_ON_RELEASE_BUILD;
import static android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.SYSTEM_UID;

import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.compat.PackageOverride;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.net.Uri;
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
import com.android.internal.compat.CompatibilityOverrideConfig;
import com.android.internal.compat.IOverrideValidator;
import com.android.internal.compat.IPlatformCompat;
import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * System server internal API for gating and reporting compatibility changes.
 */
public class PlatformCompat extends IPlatformCompat.Stub {

    private static final String TAG = "Compatibility";

    private final Context mContext;
    private final ChangeReporter mChangeReporter;
    private final CompatConfig mCompatConfig;
    private final AndroidBuildClassifier mBuildClassifier;

    public PlatformCompat(Context context) {
        mContext = context;
        mChangeReporter = new ChangeReporter(ChangeReporter.SOURCE_SYSTEM_SERVER);
        mBuildClassifier = new AndroidBuildClassifier();
        mCompatConfig = CompatConfig.create(mBuildClassifier, mContext);
    }

    @VisibleForTesting
    PlatformCompat(Context context, CompatConfig compatConfig,
                   AndroidBuildClassifier buildClassifier) {
        mContext = context;
        mChangeReporter = new ChangeReporter(ChangeReporter.SOURCE_SYSTEM_SERVER);
        mCompatConfig = compatConfig;
        mBuildClassifier = buildClassifier;

        registerPackageReceiver(context);
    }

    @Override
    public void reportChange(long changeId, ApplicationInfo appInfo) {
        reportChangeByUid(changeId, appInfo.uid);
    }

    @Override
    public void reportChangeByPackageName(long changeId, String packageName,
            @UserIdInt int userId) {
        ApplicationInfo appInfo = getApplicationInfo(packageName, userId);
        if (appInfo != null) {
            reportChangeByUid(changeId, appInfo.uid);
        }
    }

    @Override
    public void reportChangeByUid(long changeId, int uid) {
        checkCompatChangeLogPermission();
        reportChangeInternal(changeId, uid, ChangeReporter.STATE_LOGGED);
    }

    private void reportChangeInternal(long changeId, int uid, int state) {
        mChangeReporter.reportChange(uid, changeId, state);
    }

    @Override
    public boolean isChangeEnabled(long changeId, ApplicationInfo appInfo) {
        checkCompatChangeReadAndLogPermission();
        return isChangeEnabledInternal(changeId, appInfo);
    }

    @Override
    public boolean isChangeEnabledByPackageName(long changeId, String packageName,
            @UserIdInt int userId) {
        checkCompatChangeReadAndLogPermission();
        ApplicationInfo appInfo = getApplicationInfo(packageName, userId);
        if (appInfo == null) {
            return mCompatConfig.willChangeBeEnabled(changeId, packageName);
        }
        return isChangeEnabledInternal(changeId, appInfo);
    }

    @Override
    public boolean isChangeEnabledByUid(long changeId, int uid) {
        checkCompatChangeReadAndLogPermission();
        String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length == 0) {
            return mCompatConfig.defaultChangeIdValue(changeId);
        }
        boolean enabled = true;
        for (String packageName : packages) {
            enabled &= isChangeEnabledByPackageName(changeId, packageName,
                    UserHandle.getUserId(uid));
        }
        return enabled;
    }

    /**
     * Internal version of the above method, without logging.
     *
     * <p>Does not perform costly permission check.
     * TODO(b/167551701): Remove this method and add 'loggability' as a changeid property.
     */
    public boolean isChangeEnabledInternalNoLogging(long changeId, ApplicationInfo appInfo) {
        return mCompatConfig.isChangeEnabled(changeId, appInfo);
    }

    /**
     * Internal version of {@link #isChangeEnabled(long, ApplicationInfo)}.
     *
     * <p>Does not perform costly permission check.
     */
    public boolean isChangeEnabledInternal(long changeId, ApplicationInfo appInfo) {
        boolean enabled = isChangeEnabledInternalNoLogging(changeId, appInfo);
        if (appInfo != null) {
            reportChangeInternal(changeId, appInfo.uid,
                    enabled ? ChangeReporter.STATE_ENABLED : ChangeReporter.STATE_DISABLED);
        }
        return enabled;
    }

    @Override
    public void setOverrides(CompatibilityChangeConfig overrides, String packageName) {
        checkCompatChangeOverridePermission();
        Map<Long, PackageOverride> overridesMap = new HashMap<>();
        for (long change : overrides.enabledChanges()) {
            overridesMap.put(change, new PackageOverride.Builder().setEnabled(true).build());
        }
        for (long change : overrides.disabledChanges()) {
            overridesMap.put(change, new PackageOverride.Builder().setEnabled(false)
                    .build());
        }
        mCompatConfig.addOverrides(new CompatibilityOverrideConfig(overridesMap), packageName);
        killPackage(packageName);
    }

    @Override
    public void setOverridesOnReleaseBuilds(CompatibilityOverrideConfig overrides,
            String packageName) {
        // TODO(b/183630314): Unify the permission enforcement with the other setOverrides* methods.
        checkCompatChangeOverrideOverridablePermission();
        checkAllCompatOverridesAreOverridable(overrides);
        mCompatConfig.addOverrides(overrides, packageName);
    }

    @Override
    public void setOverridesForTest(CompatibilityChangeConfig overrides, String packageName) {
        checkCompatChangeOverridePermission();
        Map<Long, PackageOverride> overridesMap = new HashMap<>();
        for (long change : overrides.enabledChanges()) {
            overridesMap.put(change, new PackageOverride.Builder().setEnabled(true).build());
        }
        for (long change : overrides.disabledChanges()) {
            overridesMap.put(change, new PackageOverride.Builder().setEnabled(false)
                    .build());
        }
        mCompatConfig.addOverrides(new CompatibilityOverrideConfig(overridesMap), packageName);
    }

    @Override
    public int enableTargetSdkChanges(String packageName, int targetSdkVersion) {
        checkCompatChangeOverridePermission();
        int numChanges =
                mCompatConfig.enableTargetSdkChangesForPackage(packageName, targetSdkVersion);
        killPackage(packageName);
        return numChanges;
    }

    @Override
    public int disableTargetSdkChanges(String packageName, int targetSdkVersion) {
        checkCompatChangeOverridePermission();
        int numChanges =
                mCompatConfig.disableTargetSdkChangesForPackage(packageName, targetSdkVersion);
        killPackage(packageName);
        return numChanges;
    }

    @Override
    public void clearOverrides(String packageName) {
        checkCompatChangeOverridePermission();
        mCompatConfig.removePackageOverrides(packageName);
        killPackage(packageName);
    }

    @Override
    public void clearOverridesForTest(String packageName) {
        checkCompatChangeOverridePermission();
        mCompatConfig.removePackageOverrides(packageName);
    }

    @Override
    public boolean clearOverride(long changeId, String packageName) {
        checkCompatChangeOverridePermission();
        boolean existed = mCompatConfig.removeOverride(changeId, packageName);
        killPackage(packageName);
        return existed;
    }

    @Override
    public void clearOverrideForTest(long changeId, String packageName) {
        checkCompatChangeOverridePermission();
        mCompatConfig.removeOverride(changeId, packageName);
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
        return Arrays.stream(listAllChanges()).filter(this::isShownInUI).toArray(
                CompatibilityChangeInfo[]::new);
    }

    /** Checks whether the change is known to the compat config. */
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
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, "platform_compat", pw)) {
            return;
        }
        checkCompatChangeReadAndLogPermission();
        mCompatConfig.dumpConfig(pw);
    }

    @Override
    public IOverrideValidator getOverrideValidator() {
        return mCompatConfig.getOverrideValidator();
    }

    /**
     * Clears information stored about events reported on behalf of an app.
     *
     * <p>To be called once upon app start or end. A second call would be a no-op.
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

    private void killPackage(String packageName) {
        int uid = LocalServices.getService(PackageManagerInternal.class).getPackageUid(packageName,
                0, UserHandle.myUserId());

        if (uid < 0) {
            Slog.w(TAG, "Didn't find package " + packageName + " on device.");
            return;
        }

        Slog.d(TAG, "Killing package " + packageName + " (UID " + uid + ").");
        killUid(UserHandle.getAppId(uid));
    }

    private void killUid(int appId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            IActivityManager am = ActivityManager.getService();
            if (am != null) {
                am.killUid(appId, UserHandle.USER_ALL, "PlatformCompat overrides");
            }
        } catch (RemoteException e) {
            /* ignore - same process */
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void checkCompatChangeLogPermission() throws SecurityException {
        // Don't check for permissions within the system process
        if (Binder.getCallingUid() == SYSTEM_UID) {
            return;
        }
        if (mContext.checkCallingOrSelfPermission(LOG_COMPAT_CHANGE) != PERMISSION_GRANTED) {
            throw new SecurityException("Cannot log compat change usage");
        }
    }

    private void checkCompatChangeReadPermission() {
        // Don't check for permissions within the system process
        if (Binder.getCallingUid() == SYSTEM_UID) {
            return;
        }
        if (mContext.checkCallingOrSelfPermission(READ_COMPAT_CHANGE_CONFIG)
                != PERMISSION_GRANTED) {
            throw new SecurityException("Cannot read compat change");
        }
    }

    private void checkCompatChangeOverridePermission() {
        // Don't check for permissions within the system process
        if (Binder.getCallingUid() == SYSTEM_UID) {
            return;
        }
        if (mContext.checkCallingOrSelfPermission(OVERRIDE_COMPAT_CHANGE_CONFIG)
                != PERMISSION_GRANTED) {
            throw new SecurityException("Cannot override compat change");
        }
    }

    private void checkCompatChangeOverrideOverridablePermission() {
        // Don't check for permissions within the system process
        if (Binder.getCallingUid() == SYSTEM_UID) {
            return;
        }
        if (mContext.checkCallingOrSelfPermission(OVERRIDE_COMPAT_CHANGE_CONFIG_ON_RELEASE_BUILD)
                != PERMISSION_GRANTED) {
            throw new SecurityException("Cannot override compat change");
        }
    }

    private void checkAllCompatOverridesAreOverridable(CompatibilityOverrideConfig overrides) {
        for (Long changeId : overrides.overrides.keySet()) {
            if (!mCompatConfig.isOverridable(changeId)) {
                throw new SecurityException("Only change ids marked as Overridable can be "
                        + "overridden.");
            }
        }
    }

    private void checkCompatChangeReadAndLogPermission() {
        checkCompatChangeReadPermission();
        checkCompatChangeLogPermission();
    }

    private boolean isShownInUI(CompatibilityChangeInfo change) {
        if (change.getLoggingOnly()) {
            return false;
        }
        if (change.getId() == CompatChange.CTS_SYSTEM_API_CHANGEID) {
            return false;
        }
        if (change.getEnableSinceTargetSdk() > 0) {
            return change.getEnableSinceTargetSdk() >= Build.VERSION_CODES.Q
                && change.getEnableSinceTargetSdk() <= mBuildClassifier.platformTargetSdk();
        }
        return true;
    }

    /**
     * Registers a listener for change state overrides.
     *
     * <p>Only one listener per change is allowed.
     *
     * <p>{@code listener.onCompatChange(String)} method is guaranteed to be called with
     * packageName before the app is killed upon an override change. The state of a change is not
     * guaranteed to change when {@code listener.onCompatChange(String)} is called.
     *
     * @param changeId to get updates for
     * @param listener the listener that will be called upon a potential change for package
     * @return {@code true} if a change with changeId was already known, or (@code false}
     * otherwise
     * @throws IllegalStateException if a listener was already registered for changeId
     */
    public boolean registerListener(long changeId, CompatChange.ChangeListener listener) {
        return mCompatConfig.registerListener(changeId, listener);
    }

    /**
     * Registers a broadcast receiver that listens for package install, replace or remove.
     *
     * @param context the context where the receiver should be registered
     */
    public void registerPackageReceiver(Context context) {
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                final Uri packageData = intent.getData();
                if (packageData == null) {
                    return;
                }
                final String packageName = packageData.getSchemeSpecificPart();
                if (packageName == null) {
                    return;
                }
                mCompatConfig.recheckOverrides(packageName);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        context.registerReceiver(receiver, filter);
    }

    /**
     * Registers the observer for
     * {@link android.provider.Settings.Global#FORCE_NON_DEBUGGABLE_FINAL_BUILD_FOR_COMPAT}.
     */
    public void registerContentObserver() {
        mCompatConfig.registerContentObserver();
    }
}
