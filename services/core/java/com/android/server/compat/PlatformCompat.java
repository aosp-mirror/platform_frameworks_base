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

import android.annotation.EnforcePermission;
import android.annotation.RequiresNoPermission;
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
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.compat.AndroidBuildClassifier;
import com.android.internal.compat.ChangeReporter;
import com.android.internal.compat.CompatibilityChangeConfig;
import com.android.internal.compat.CompatibilityChangeInfo;
import com.android.internal.compat.CompatibilityOverrideConfig;
import com.android.internal.compat.CompatibilityOverridesByPackageConfig;
import com.android.internal.compat.CompatibilityOverridesToRemoveByPackageConfig;
import com.android.internal.compat.CompatibilityOverridesToRemoveConfig;
import com.android.internal.compat.IOverrideValidator;
import com.android.internal.compat.IPlatformCompat;
import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collection;
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
    @EnforcePermission(LOG_COMPAT_CHANGE)
    public void reportChange(long changeId, ApplicationInfo appInfo) {
        super.reportChange_enforcePermission();

        reportChangeInternal(changeId, appInfo.uid, ChangeReporter.STATE_LOGGED);
    }

    @Override
    @EnforcePermission(LOG_COMPAT_CHANGE)
    public void reportChangeByPackageName(long changeId, String packageName,
            @UserIdInt int userId) {
        super.reportChangeByPackageName_enforcePermission();

        ApplicationInfo appInfo = getApplicationInfo(packageName, userId);
        if (appInfo != null) {
            reportChangeInternal(changeId, appInfo.uid, ChangeReporter.STATE_LOGGED);
        }
    }

    @Override
    @EnforcePermission(LOG_COMPAT_CHANGE)
    public void reportChangeByUid(long changeId, int uid) {
        super.reportChangeByUid_enforcePermission();

        reportChangeInternal(changeId, uid, ChangeReporter.STATE_LOGGED);
    }

    /**
     * Report the change, but skip over the sdk target version check. This can be used to force the
     * debug logs.
     *
     * @param changeId        of the change to report
     * @param uid             of the user
     * @param state           of the change - enabled/disabled/logged
     */
    private void reportChangeInternal(long changeId, int uid, int state) {
        mChangeReporter.reportChange(uid, changeId, state, true);
    }

    @Override
    @EnforcePermission(allOf = {LOG_COMPAT_CHANGE, READ_COMPAT_CHANGE_CONFIG})
    public boolean isChangeEnabled(long changeId, ApplicationInfo appInfo) {
        super.isChangeEnabled_enforcePermission();

        return isChangeEnabledInternal(changeId, appInfo);
    }

    @Override
    @EnforcePermission(allOf = {LOG_COMPAT_CHANGE, READ_COMPAT_CHANGE_CONFIG})
    public boolean isChangeEnabledByPackageName(long changeId, String packageName,
            @UserIdInt int userId) {
        super.isChangeEnabledByPackageName_enforcePermission();

        ApplicationInfo appInfo = getApplicationInfo(packageName, userId);
        if (appInfo == null) {
            return mCompatConfig.willChangeBeEnabled(changeId, packageName);
        }
        return isChangeEnabledInternal(changeId, appInfo);
    }

    @Override
    @EnforcePermission(allOf = {LOG_COMPAT_CHANGE, READ_COMPAT_CHANGE_CONFIG})
    public boolean isChangeEnabledByUid(long changeId, int uid) {
        super.isChangeEnabledByUid_enforcePermission();

        return isChangeEnabledByUidInternal(changeId, uid);
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
     * Internal version of {@link #isChangeEnabled(long, ApplicationInfo)}. If the provided appInfo
     * is not null, also reports the change.
     *
     * @param changeId of the change to report
     * @param appInfo  the app to check
     *
     * <p>Does not perform costly permission check.
     */
    public boolean isChangeEnabledInternal(long changeId, ApplicationInfo appInfo) {
        // Fetch the CompatChange. This is done here instead of in mCompatConfig to avoid multiple
        // fetches.
        CompatChange c = mCompatConfig.getCompatChange(changeId);

        boolean enabled = mCompatConfig.isChangeEnabled(c, appInfo);
        int state = enabled ? ChangeReporter.STATE_ENABLED : ChangeReporter.STATE_DISABLED;
        if (appInfo != null) {
            boolean isTargetingLatestSdk =
                    mCompatConfig.isChangeTargetingLatestSdk(c, appInfo.targetSdkVersion);
            mChangeReporter.reportChange(appInfo.uid, changeId, state, isTargetingLatestSdk);
        }
        return enabled;
    }

    /**
     * Called by the package manager to check if a given change is enabled for a given package name
     * and the target sdk version while the package is in the parsing state.
     *
     * <p>Does not perform costly permission check.
     *
     * @param changeId         the ID of the change in question
     * @param packageName      package name to check for
     * @param targetSdkVersion target sdk version to check for
     * @return {@code true} if the change would be enabled for this package name.
     */
    public boolean isChangeEnabledInternal(long changeId, String packageName,
            int targetSdkVersion) {
        if (mCompatConfig.willChangeBeEnabled(changeId, packageName)) {
            final ApplicationInfo appInfo = new ApplicationInfo();
            appInfo.packageName = packageName;
            appInfo.targetSdkVersion = targetSdkVersion;
            return isChangeEnabledInternalNoLogging(changeId, appInfo);
        }
        return false;
    }

    /**
     * Internal version of {@link #isChangeEnabledByUid(long, int)}.
     *
     * <p>Does not perform costly permission check.
     */
    public boolean isChangeEnabledByUidInternal(long changeId, int uid) {
        String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        if (packages == null || packages.length == 0) {
            return mCompatConfig.defaultChangeIdValue(changeId);
        }
        boolean enabled = true;
        final int userId = UserHandle.getUserId(uid);
        for (String packageName : packages) {
            final var appInfo = getApplicationInfo(packageName, userId);
            enabled &= isChangeEnabledInternal(changeId, appInfo);
        }
        return enabled;
    }

    @Override
    @EnforcePermission(OVERRIDE_COMPAT_CHANGE_CONFIG)
    public void setOverrides(CompatibilityChangeConfig overrides, String packageName) {
        super.setOverrides_enforcePermission();

        Map<Long, PackageOverride> overridesMap = new HashMap<>();
        for (long change : overrides.enabledChanges()) {
            overridesMap.put(change, new PackageOverride.Builder().setEnabled(true).build());
        }
        for (long change : overrides.disabledChanges()) {
            overridesMap.put(change, new PackageOverride.Builder().setEnabled(false)
                    .build());
        }
        mCompatConfig.addPackageOverrides(new CompatibilityOverrideConfig(overridesMap),
                packageName, /* skipUnknownChangeIds */ false);
        killPackage(packageName);
    }

    @Override
    @EnforcePermission(OVERRIDE_COMPAT_CHANGE_CONFIG)
    public void setOverridesForTest(CompatibilityChangeConfig overrides, String packageName) {
        super.setOverridesForTest_enforcePermission();

        Map<Long, PackageOverride> overridesMap = new HashMap<>();
        for (long change : overrides.enabledChanges()) {
            overridesMap.put(change, new PackageOverride.Builder().setEnabled(true).build());
        }
        for (long change : overrides.disabledChanges()) {
            overridesMap.put(change, new PackageOverride.Builder().setEnabled(false)
                    .build());
        }
        mCompatConfig.addPackageOverrides(new CompatibilityOverrideConfig(overridesMap),
                packageName, /* skipUnknownChangeIds */ false);
    }

    @Override
    @EnforcePermission(OVERRIDE_COMPAT_CHANGE_CONFIG_ON_RELEASE_BUILD)
    public void putAllOverridesOnReleaseBuilds(
            CompatibilityOverridesByPackageConfig overridesByPackage) {
        super.putAllOverridesOnReleaseBuilds_enforcePermission();

        for (CompatibilityOverrideConfig overrides :
                overridesByPackage.packageNameToOverrides.values()) {
            checkAllCompatOverridesAreOverridable(overrides.overrides.keySet());
        }
        mCompatConfig.addAllPackageOverrides(overridesByPackage, /* skipUnknownChangeIds= */ true);
    }

    @Override
    @EnforcePermission(OVERRIDE_COMPAT_CHANGE_CONFIG_ON_RELEASE_BUILD)
    public void putOverridesOnReleaseBuilds(CompatibilityOverrideConfig overrides,
            String packageName) {
        super.putOverridesOnReleaseBuilds_enforcePermission();

        checkAllCompatOverridesAreOverridable(overrides.overrides.keySet());
        mCompatConfig.addPackageOverrides(overrides, packageName, /* skipUnknownChangeIds= */ true);
    }

    @Override
    @EnforcePermission(OVERRIDE_COMPAT_CHANGE_CONFIG)
    public int enableTargetSdkChanges(String packageName, int targetSdkVersion) {
        super.enableTargetSdkChanges_enforcePermission();

        int numChanges =
                mCompatConfig.enableTargetSdkChangesForPackage(packageName, targetSdkVersion);
        killPackage(packageName);
        return numChanges;
    }

    @Override
    @EnforcePermission(OVERRIDE_COMPAT_CHANGE_CONFIG)
    public int disableTargetSdkChanges(String packageName, int targetSdkVersion) {
        super.disableTargetSdkChanges_enforcePermission();

        int numChanges =
                mCompatConfig.disableTargetSdkChangesForPackage(packageName, targetSdkVersion);
        killPackage(packageName);
        return numChanges;
    }

    @Override
    @EnforcePermission(OVERRIDE_COMPAT_CHANGE_CONFIG)
    public void clearOverrides(String packageName) {
        super.clearOverrides_enforcePermission();

        mCompatConfig.removePackageOverrides(packageName);
        killPackage(packageName);
    }

    @Override
    @EnforcePermission(OVERRIDE_COMPAT_CHANGE_CONFIG)
    public void clearOverridesForTest(String packageName) {
        super.clearOverridesForTest_enforcePermission();

        mCompatConfig.removePackageOverrides(packageName);
    }

    @Override
    @EnforcePermission(OVERRIDE_COMPAT_CHANGE_CONFIG)
    public boolean clearOverride(long changeId, String packageName) {
        super.clearOverride_enforcePermission();

        boolean existed = mCompatConfig.removeOverride(changeId, packageName);
        killPackage(packageName);
        return existed;
    }

    @Override
    @EnforcePermission(OVERRIDE_COMPAT_CHANGE_CONFIG)
    public boolean clearOverrideForTest(long changeId, String packageName) {
        super.clearOverrideForTest_enforcePermission();

        return mCompatConfig.removeOverride(changeId, packageName);
    }

    @Override
    @EnforcePermission(OVERRIDE_COMPAT_CHANGE_CONFIG_ON_RELEASE_BUILD)
    public void removeAllOverridesOnReleaseBuilds(
            CompatibilityOverridesToRemoveByPackageConfig overridesToRemoveByPackage) {
        super.removeAllOverridesOnReleaseBuilds_enforcePermission();

        for (CompatibilityOverridesToRemoveConfig overridesToRemove :
                overridesToRemoveByPackage.packageNameToOverridesToRemove.values()) {
            checkAllCompatOverridesAreOverridable(overridesToRemove.changeIds);
        }
        mCompatConfig.removeAllPackageOverrides(overridesToRemoveByPackage);
    }

    @Override
    @EnforcePermission(OVERRIDE_COMPAT_CHANGE_CONFIG_ON_RELEASE_BUILD)
    public void removeOverridesOnReleaseBuilds(
            CompatibilityOverridesToRemoveConfig overridesToRemove,
            String packageName) {
        super.removeOverridesOnReleaseBuilds_enforcePermission();

        checkAllCompatOverridesAreOverridable(overridesToRemove.changeIds);
        mCompatConfig.removePackageOverrides(overridesToRemove, packageName);
    }

    @Override
    @EnforcePermission(allOf = {LOG_COMPAT_CHANGE, READ_COMPAT_CHANGE_CONFIG})
    public CompatibilityChangeConfig getAppConfig(ApplicationInfo appInfo) {
        super.getAppConfig_enforcePermission();

        return mCompatConfig.getAppConfig(appInfo);
    }

    @Override
    @EnforcePermission(READ_COMPAT_CHANGE_CONFIG)
    public CompatibilityChangeInfo[] listAllChanges() {
        super.listAllChanges_enforcePermission();

        return mCompatConfig.dumpChanges();
    }

    @Override
    @RequiresNoPermission
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
     * Retrieves the set of changes that should be logged for a given app. Any change ID not in the
     * returned array is ignored for logging purposes.
     *
     * @param appInfo The app in question
     * @return A sorted long array of change IDs. We use a primitive array to minimize memory
     * footprint: Every app process will store this array statically so we aim to reduce
     * overhead as much as possible.
     */
    public long[] getLoggableChanges(ApplicationInfo appInfo) {
        return mCompatConfig.getLoggableChanges(appInfo);
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
        mContext.enforceCallingOrSelfPermission(
                READ_COMPAT_CHANGE_CONFIG, "Cannot read compat change");
        mContext.enforceCallingOrSelfPermission(
                LOG_COMPAT_CHANGE, "Cannot read log compat change usage");
        mCompatConfig.dumpConfig(pw);
    }

    @Override
    @RequiresNoPermission
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
                packageName, 0, Process.myUid(), userId);
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

    private void checkAllCompatOverridesAreOverridable(Collection<Long> changeIds) {
        for (Long changeId : changeIds) {
            if (isKnownChangeId(changeId) && !mCompatConfig.isOverridable(changeId)) {
                throw new SecurityException("Only change ids marked as Overridable can be "
                        + "overridden.");
            }
        }
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
        context.registerReceiverForAllUsers(receiver, filter, /* broadcastPermission= */
                null, /* scheduler= */ null);
    }

    /**
     * Registers the observer for
     * {@link android.provider.Settings.Global#FORCE_NON_DEBUGGABLE_FINAL_BUILD_FOR_COMPAT}.
     */
    public void registerContentObserver() {
        mCompatConfig.registerContentObserver();
    }
}
