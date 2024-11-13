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

import static android.content.pm.PackageManager.MATCH_ANY_USER;

import android.annotation.Nullable;
import android.app.compat.ChangeIdStateCache;
import android.app.compat.PackageOverride;
import android.compat.Compatibility.ChangeConfig;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;
import android.util.LongArray;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.compat.AndroidBuildClassifier;
import com.android.internal.compat.CompatibilityChangeConfig;
import com.android.internal.compat.CompatibilityChangeInfo;
import com.android.internal.compat.CompatibilityOverrideConfig;
import com.android.internal.compat.CompatibilityOverridesByPackageConfig;
import com.android.internal.compat.CompatibilityOverridesToRemoveByPackageConfig;
import com.android.internal.compat.CompatibilityOverridesToRemoveConfig;
import com.android.internal.compat.IOverrideValidator;
import com.android.internal.compat.OverrideAllowedState;
import com.android.server.compat.config.Change;
import com.android.server.compat.config.Config;
import com.android.server.compat.overrides.ChangeOverrides;
import com.android.server.compat.overrides.Overrides;
import com.android.server.compat.overrides.XmlWriter;
import com.android.server.pm.ApexManager;

import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.xml.datatype.DatatypeConfigurationException;

/**
 * CompatConfig maintains state related to the platform compatibility changes.
 *
 * <p>It stores the default configuration for each change, and any per-package overrides that have
 * been configured.
 */
final class CompatConfig {
    private static final String TAG = "CompatConfig";
    private static final String APP_COMPAT_DATA_DIR = "/data/misc/appcompat";
    private static final String STATIC_OVERRIDES_PRODUCT_DIR = "/product/etc/appcompat";
    private static final String OVERRIDES_FILE = "compat_framework_overrides.xml";

    private final ConcurrentHashMap<Long, CompatChange> mChanges = new ConcurrentHashMap<>();

    private final OverrideValidatorImpl mOverrideValidator;
    private final AndroidBuildClassifier mAndroidBuildClassifier;
    private Context mContext;
    private final Object mOverridesFileLock = new Object();
    @GuardedBy("mOverridesFileLock")
    private File mOverridesFile;
    @GuardedBy("mOverridesFileLock")
    private File mBackupOverridesFile;

    @VisibleForTesting
    CompatConfig(AndroidBuildClassifier androidBuildClassifier, Context context) {
        mOverrideValidator = new OverrideValidatorImpl(androidBuildClassifier, context, this);
        mAndroidBuildClassifier = androidBuildClassifier;
        mContext = context;
    }

    static CompatConfig create(AndroidBuildClassifier androidBuildClassifier, Context context) {
        CompatConfig config = new CompatConfig(androidBuildClassifier, context);
        config.initConfigFromLib(Environment.buildPath(
                Environment.getRootDirectory(), "etc", "compatconfig"));
        config.initConfigFromLib(Environment.buildPath(
                Environment.getRootDirectory(), "system_ext", "etc", "compatconfig"));

        List<ApexManager.ActiveApexInfo> apexes = ApexManager.getInstance().getActiveApexInfos();
        for (ApexManager.ActiveApexInfo apex : apexes) {
            config.initConfigFromLib(Environment.buildPath(
                    apex.apexDirectory, "etc", "compatconfig"));
        }
        config.initOverrides();
        config.invalidateCache();
        return config;
    }

    /**
     * Adds a change.
     *
     * <p>This is intended to be used by unit tests only.
     *
     * @param change the change to add
     */
    @VisibleForTesting
    void addChange(CompatChange change) {
        mChanges.put(change.getId(), change);
    }

    /**
     * Retrieves the set of disabled changes for a given app.
     *
     * <p>Any change ID not in the returned array is by default enabled for the app.
     *
     * <p>We use a primitive array to minimize memory footprint: every app process will store this
     * array statically so we aim to reduce overhead as much as possible.
     *
     * @param app the app in question
     * @return a sorted long array of change IDs
     */
    long[] getDisabledChanges(ApplicationInfo app) {
        LongArray disabled = new LongArray();
        for (CompatChange c : mChanges.values()) {
            if (!c.isEnabled(app, mAndroidBuildClassifier)) {
                disabled.add(c.getId());
            }
        }
        final long[] sortedChanges = disabled.toArray();
        Arrays.sort(sortedChanges);
        return sortedChanges;
    }

    /**
     * Retrieves the set of changes that are intended to be logged. This includes changes that
     * target the most recent SDK version and are not disabled.
     *
     * @param app the app in question
     * @return a sorted long array of change IDs
     */
    long[] getLoggableChanges(ApplicationInfo app) {
        LongArray loggable = new LongArray(mChanges.size());
        for (CompatChange c : mChanges.values()) {
            long changeId = c.getId();
            boolean isLatestSdk = isChangeTargetingLatestSdk(c, app.targetSdkVersion);
            if (c.isEnabled(app, mAndroidBuildClassifier) && isLatestSdk) {
                loggable.add(changeId);
            }
        }
        final long[] sortedChanges = loggable.toArray();
        Arrays.sort(sortedChanges);
        return sortedChanges;
    }

    /**
     * Whether the change indicated by the given changeId is targeting the latest SDK version.
     * @param c             the change for which to check the target SDK version
     * @param appSdkVersion the target sdk version of the app
     * @return true if the changeId targets the current sdk version or the current development
     * version.
     */
    boolean isChangeTargetingLatestSdk(CompatChange c, int appSdkVersion) {
        int maxTargetSdk = maxTargetSdkForCompatChange(c) + 1;
        if (maxTargetSdk <= 0) {
            // No max target sdk found.
            return false;
        }

        return maxTargetSdk == Build.VERSION_CODES.CUR_DEVELOPMENT || maxTargetSdk == appSdkVersion;
    }

    /**
     * Retrieves the CompatChange associated with the given changeId. Will return null if the
     * changeId is not found. Used only for performance improvement purposes, in order to reduce
     * lookups.
     *
     * @param changeId for which to look up the CompatChange
     * @return the found compat change, or null if not found.
     */
    CompatChange getCompatChange(long changeId) {
        return mChanges.get(changeId);
    }

    /**
     * Looks up a change ID by name.
     *
     * @param name name of the change to look up
     * @return the change ID, or {@code -1} if no change with that name exists
     */
    long lookupChangeId(String name) {
        for (CompatChange c : mChanges.values()) {
            if (TextUtils.equals(c.getName(), name)) {
                return c.getId();
            }
        }
        return -1;
    }

    /**
     * Checks if a given change id is enabled for a given application.
     *
     * @param changeId the ID of the change in question
     * @param app      app to check for
     * @return {@code true} if the change is enabled for this app. Also returns {@code true} if the
     * change ID is not known, as unknown changes are enabled by default.
     */
    boolean isChangeEnabled(long changeId, ApplicationInfo app) {
        CompatChange c = mChanges.get(changeId);
        return isChangeEnabled(c, app);
    }

    /**
     * Checks if a given change is enabled for a given application.
     *
     * @param c   the CompatChange in question
     * @param app the app to check for
     * @return {@code true} if the change is enabled for this app. Also returns {@code true} if the
     * change ID is not known, as unknown changes are enabled by default.
     */
    boolean isChangeEnabled(CompatChange c, ApplicationInfo app) {
        if (c == null) {
            // we know nothing about this change: default behaviour is enabled.
            return true;
        }
        return c.isEnabled(app, mAndroidBuildClassifier);
    }

    /**
     * Checks if a given change will be enabled for a given package name after the installation.
     *
     * @param changeId    the ID of the change in question
     * @param packageName package name to check for
     * @return {@code true} if the change would be enabled for this package name. Also returns
     * {@code true} if the change ID is not known, as unknown changes are enabled by default.
     */
    boolean willChangeBeEnabled(long changeId, String packageName) {
        CompatChange c = mChanges.get(changeId);
        if (c == null) {
            // we know nothing about this change: default behaviour is enabled.
            return true;
        }
        return c.willBeEnabled(packageName);
    }

    /**
     * Overrides the enabled state for a given change and app.
     *
     * <p>This method is intended to be used *only* for debugging purposes, ultimately invoked
     * either by an adb command, or from some developer settings UI.
     *
     * <p>Note: package overrides are not persistent and will be lost on system or runtime restart.
     *
     * @param changeId    the ID of the change to be overridden. Note, this call will succeed even
     *                    if this change is not known; it will only have any effect if any code in
     *                    the platform is gated on the ID given.
     * @param packageName the app package name to override the change for
     * @param enabled     if the change should be enabled or disabled
     * @return {@code true} if the change existed before adding the override
     * @throws IllegalStateException if overriding is not allowed
     */
    synchronized boolean addOverride(long changeId, String packageName, boolean enabled) {
        boolean alreadyKnown = addOverrideUnsafe(changeId, packageName,
                new PackageOverride.Builder().setEnabled(enabled).build());
        saveOverrides();
        invalidateCache();
        return alreadyKnown;
    }

    /**
     * Adds compat config overrides for multiple packages.
     *
     * <p>Equivalent to calling
     * {@link #addPackageOverrides(CompatibilityOverrideConfig, String, boolean)} on each entry
     * in {@code overridesByPackage}, but the state of the compat config will be updated only
     * once instead of for each package.
     *
     * @param overridesByPackage map from package name to compat config overrides to add for that
     *                           package.
     * @param skipUnknownChangeIds whether to skip unknown change IDs in {@code overridesByPackage}.
     */
    synchronized void addAllPackageOverrides(
            CompatibilityOverridesByPackageConfig overridesByPackage,
            boolean skipUnknownChangeIds) {
        for (String packageName : overridesByPackage.packageNameToOverrides.keySet()) {
            addPackageOverridesWithoutSaving(
                    overridesByPackage.packageNameToOverrides.get(packageName), packageName,
                    skipUnknownChangeIds);
        }
        saveOverrides();
        invalidateCache();
    }

    /**
     * Adds compat config overrides for a given package.
     *
     * <p>Note, package overrides are not persistent and will be lost on system or runtime restart.
     *
     * @param overrides   list of compat config overrides to add for the given package.
     * @param packageName app for which the overrides will be applied.
     * @param skipUnknownChangeIds whether to skip unknown change IDs in {@code overrides}.
     */
    synchronized void addPackageOverrides(CompatibilityOverrideConfig overrides,
            String packageName, boolean skipUnknownChangeIds) {
        addPackageOverridesWithoutSaving(overrides, packageName, skipUnknownChangeIds);
        saveOverrides();
        invalidateCache();
    }

    private void addPackageOverridesWithoutSaving(CompatibilityOverrideConfig overrides,
            String packageName, boolean skipUnknownChangeIds) {
        for (Long changeId : overrides.overrides.keySet()) {
            if (skipUnknownChangeIds && !isKnownChangeId(changeId)) {
                Slog.w(TAG, "Trying to add overrides for unknown Change ID " + changeId + ". "
                        + "Skipping Change ID.");
                continue;
            }
            addOverrideUnsafe(changeId, packageName, overrides.overrides.get(changeId));
        }
    }

    private boolean addOverrideUnsafe(long changeId, String packageName,
            PackageOverride overrides) {
        final AtomicBoolean alreadyKnown = new AtomicBoolean(true);
        OverrideAllowedState allowedState =
                mOverrideValidator.getOverrideAllowedState(changeId, packageName);
        allowedState.enforce(changeId, packageName);
        Long versionCode = getVersionCodeOrNull(packageName);

        final CompatChange c = mChanges.computeIfAbsent(changeId, (key) -> {
            alreadyKnown.set(false);
            return new CompatChange(changeId);
        });
        c.addPackageOverride(packageName, overrides, allowedState, versionCode);
        Slog.d(TAG, (overrides.isEnabled() ? "Enabled" : "Disabled")
                + " change " + changeId + (c.getName() != null ? " [" + c.getName() + "]" : "")
                + " for " + packageName);
        invalidateCache();
        return alreadyKnown.get();
    }

    /** Checks whether the change is known to the compat config. */
    boolean isKnownChangeId(long changeId) {
        return mChanges.containsKey(changeId);
    }

    /**
     * Returns the maximum SDK version for which this change can be opted in (or -1 if it is not
     * target SDK gated).
     *
     * @param changeId the id of the CompatChange to check for the max target sdk
     */
    int maxTargetSdkForChangeIdOptIn(long changeId) {
        CompatChange c = mChanges.get(changeId);
        return maxTargetSdkForCompatChange(c);
    }

    /**
     * Returns the maximum SDK version for which this change can be opted in (or -1 if it is not
     * target SDK gated).
     *
     * @param c the CompatChange to check for the max target sdk
     */
    int maxTargetSdkForCompatChange(CompatChange c) {
        if (c != null && c.getEnableSinceTargetSdk() != -1) {
            return c.getEnableSinceTargetSdk() - 1;
        }
        return -1;
    }

    /**
     * Returns whether the change is marked as logging only.
     */
    boolean isLoggingOnly(long changeId) {
        CompatChange c = mChanges.get(changeId);
        return c != null && c.getLoggingOnly();
    }

    /**
     * Returns whether the change is marked as disabled.
     */
    boolean isDisabled(long changeId) {
        CompatChange c = mChanges.get(changeId);
        return c != null && c.getDisabled();
    }

    /**
     * Returns whether the change is overridable.
     */
    boolean isOverridable(long changeId) {
        CompatChange c = mChanges.get(changeId);
        return c != null && c.getOverridable();
    }

    /**
     * Removes an override previously added via {@link #addOverride(long, String, boolean)}.
     *
     * <p>This restores the default behaviour for the given change and app, once any app processes
     * have been restarted.
     *
     * @param changeId    the ID of the change that was overridden
     * @param packageName the app package name that was overridden
     * @return {@code true} if an override existed;
     */
    synchronized boolean removeOverride(long changeId, String packageName) {
        boolean overrideExists = removeOverrideUnsafe(changeId, packageName);
        if (overrideExists) {
            saveOverrides();
            invalidateCache();
        }
        return overrideExists;
    }

    /**
     * Unsafe version of {@link #removeOverride(long, String)}.
     * It does not save the overrides.
     */
    private boolean removeOverrideUnsafe(long changeId, String packageName) {
        Long versionCode = getVersionCodeOrNull(packageName);
        CompatChange c = mChanges.get(changeId);
        if (c != null) {
            return removeOverrideUnsafe(c, packageName, versionCode);
        }
        return false;
    }

    /**
     * Similar to {@link #removeOverrideUnsafe(long, String)} except this method receives a {@link
     * CompatChange} directly as well as the package's version code.
     */
    private boolean removeOverrideUnsafe(CompatChange change, String packageName,
            @Nullable Long versionCode) {
        long changeId = change.getId();
        OverrideAllowedState allowedState =
                mOverrideValidator.getOverrideAllowedState(changeId, packageName);
        boolean overrideExists = change.removePackageOverride(packageName, allowedState,
                versionCode);
        if (overrideExists) {
            Slog.d(TAG, "Reset change " + changeId
                    + (change.getName() != null ? " [" + change.getName() + "]" : "")
                    + " for " + packageName + " to default value.");
        }
        return overrideExists;
    }

    /**
     * Removes overrides with a specified change ID that were previously added via
     * {@link #addOverride(long, String, boolean)} or
     * {@link #addPackageOverrides(CompatibilityOverrideConfig, String, boolean)} for multiple
     * packages.
     *
     * <p>Equivalent to calling
     * {@link #removePackageOverrides(CompatibilityOverridesToRemoveConfig, String)} on each entry
     * in {@code overridesToRemoveByPackage}, but the state of the compat config will be updated
     * only once instead of for each package.
     *
     * @param overridesToRemoveByPackage map from package name to a list of change IDs for
     *                                   which to restore the default behaviour for that
     *                                   package.
     */
    synchronized void removeAllPackageOverrides(
            CompatibilityOverridesToRemoveByPackageConfig overridesToRemoveByPackage) {
        boolean shouldInvalidateCache = false;
        for (String packageName :
                overridesToRemoveByPackage.packageNameToOverridesToRemove.keySet()) {
            shouldInvalidateCache |= removePackageOverridesWithoutSaving(
                    overridesToRemoveByPackage.packageNameToOverridesToRemove.get(packageName),
                    packageName);
        }
        if (shouldInvalidateCache) {
            saveOverrides();
            invalidateCache();
        }
    }

    /**
     * Removes all overrides previously added via {@link #addOverride(long, String, boolean)} or
     * {@link #addPackageOverrides(CompatibilityOverrideConfig, String, boolean)} for a certain
     * package.
     *
     * <p>This restores the default behaviour for the given app.
     *
     * @param packageName the package for which the overrides should be purged
     */
    synchronized void removePackageOverrides(String packageName) {
        Long versionCode = getVersionCodeOrNull(packageName);
        boolean shouldInvalidateCache = false;
        for (CompatChange change : mChanges.values()) {
            shouldInvalidateCache |= removeOverrideUnsafe(change, packageName, versionCode);
        }
        if (shouldInvalidateCache) {
            saveOverrides();
            invalidateCache();
        }
    }

    /**
     * Removes overrides whose change ID is specified in {@code overridesToRemove} that were
     * previously added via {@link #addOverride(long, String, boolean)} or
     * {@link #addPackageOverrides(CompatibilityOverrideConfig, String, boolean)} for a certain
     * package.
     *
     * <p>This restores the default behaviour for the given change IDs and app.
     *
     * @param overridesToRemove list of change IDs for which to restore the default behaviour.
     * @param packageName       the package for which the overrides should be purged
     */
    synchronized void removePackageOverrides(CompatibilityOverridesToRemoveConfig overridesToRemove,
            String packageName) {
        boolean shouldInvalidateCache = removePackageOverridesWithoutSaving(overridesToRemove,
                packageName);
        if (shouldInvalidateCache) {
            saveOverrides();
            invalidateCache();
        }
    }

    private boolean removePackageOverridesWithoutSaving(
            CompatibilityOverridesToRemoveConfig overridesToRemove, String packageName) {
        boolean shouldInvalidateCache = false;
        for (Long changeId : overridesToRemove.changeIds) {
            if (!isKnownChangeId(changeId)) {
                Slog.w(TAG, "Trying to remove overrides for unknown Change ID " + changeId + ". "
                        + "Skipping Change ID.");
                continue;
            }
            shouldInvalidateCache |= removeOverrideUnsafe(changeId, packageName);
        }
        return shouldInvalidateCache;
    }

    private long[] getAllowedChangesSinceTargetSdkForPackage(String packageName,
            int targetSdkVersion) {
        LongArray allowed = new LongArray();
        for (CompatChange change : mChanges.values()) {
            if (change.getEnableSinceTargetSdk() != targetSdkVersion) {
                continue;
            }
            OverrideAllowedState allowedState =
                    mOverrideValidator.getOverrideAllowedState(change.getId(),
                            packageName);
            if (allowedState.state == OverrideAllowedState.ALLOWED) {
                allowed.add(change.getId());
            }
        }
        return allowed.toArray();
    }

    /**
     * Enables all changes with enabledSinceTargetSdk == {@param targetSdkVersion} for
     * {@param packageName}.
     *
     * @return the number of changes that were toggled
     */
    int enableTargetSdkChangesForPackage(String packageName, int targetSdkVersion) {
        long[] changes = getAllowedChangesSinceTargetSdkForPackage(packageName, targetSdkVersion);
        boolean shouldInvalidateCache = false;
        for (long changeId : changes) {
            shouldInvalidateCache |= addOverrideUnsafe(changeId, packageName,
                    new PackageOverride.Builder().setEnabled(true).build());
        }
        if (shouldInvalidateCache) {
            saveOverrides();
            invalidateCache();
        }
        return changes.length;
    }

    /**
     * Disables all changes with enabledSinceTargetSdk == {@param targetSdkVersion} for
     * {@param packageName}.
     *
     * @return the number of changes that were toggled
     */
    int disableTargetSdkChangesForPackage(String packageName, int targetSdkVersion) {
        long[] changes = getAllowedChangesSinceTargetSdkForPackage(packageName, targetSdkVersion);
        boolean shouldInvalidateCache = false;
        for (long changeId : changes) {
            shouldInvalidateCache |= addOverrideUnsafe(changeId, packageName,
                    new PackageOverride.Builder().setEnabled(false).build());
        }
        if (shouldInvalidateCache) {
            saveOverrides();
            invalidateCache();
        }
        return changes.length;
    }

    boolean registerListener(long changeId, CompatChange.ChangeListener listener) {
        final AtomicBoolean alreadyKnown = new AtomicBoolean(true);
        final CompatChange c = mChanges.computeIfAbsent(changeId, (key) -> {
            alreadyKnown.set(false);
            invalidateCache();
            return new CompatChange(changeId);
        });
        c.registerListener(listener);
        return alreadyKnown.get();
    }

    boolean defaultChangeIdValue(long changeId) {
        CompatChange c = mChanges.get(changeId);
        if (c == null) {
            return true;
        }
        return c.defaultValue();
    }

    @VisibleForTesting
    void forceNonDebuggableFinalForTest(boolean value) {
        mOverrideValidator.forceNonDebuggableFinalForTest(value);
    }

    @VisibleForTesting
    void clearChanges() {
        mChanges.clear();
    }

    /**
     * Dumps the current list of compatibility config information.
     *
     * @param pw {@link PrintWriter} instance to which the information will be dumped
     */
    void dumpConfig(PrintWriter pw) {
        if (mChanges.size() == 0) {
            pw.println("No compat overrides.");
            return;
        }
        for (CompatChange c : mChanges.values()) {
            pw.println(c.toString());
        }
    }

    /**
     * Returns config for a given app.
     *
     * @param applicationInfo the {@link ApplicationInfo} for which the info should be dumped
     */
    CompatibilityChangeConfig getAppConfig(ApplicationInfo applicationInfo) {
        Set<Long> enabled = new HashSet<>();
        Set<Long> disabled = new HashSet<>();
        for (CompatChange c : mChanges.values()) {
            if (c.isEnabled(applicationInfo, mAndroidBuildClassifier)) {
                enabled.add(c.getId());
            } else {
                disabled.add(c.getId());
            }
        }
        return new CompatibilityChangeConfig(new ChangeConfig(enabled, disabled));
    }

    /**
     * Dumps all the compatibility change information.
     *
     * @return an array of {@link CompatibilityChangeInfo} with the current changes
     */
    CompatibilityChangeInfo[] dumpChanges() {
        CompatibilityChangeInfo[] changeInfos = new CompatibilityChangeInfo[mChanges.size()];
        int i = 0;
        for (CompatChange change : mChanges.values()) {
            changeInfos[i++] = new CompatibilityChangeInfo(change);
        }
        return changeInfos;
    }

    void initConfigFromLib(File libraryDir) {
        if (!libraryDir.exists() || !libraryDir.isDirectory()) {
            Slog.d(TAG, "No directory " + libraryDir + ", skipping");
            return;
        }
        for (File f : libraryDir.listFiles()) {
            Slog.d(TAG, "Found a config file: " + f.getPath());
            //TODO(b/138222363): Handle duplicate ids across config files.
            readConfig(f);
        }
    }

    private void readConfig(File configFile) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(configFile))) {
            Config config = com.android.server.compat.config.XmlParser.read(in);
            for (Change change : config.getCompatChange()) {
                Slog.d(TAG, "Adding: " + change.toString());
                mChanges.put(change.getId(), new CompatChange(change));
            }
        } catch (IOException | DatatypeConfigurationException | XmlPullParserException e) {
            Slog.e(TAG, "Encountered an error while reading/parsing compat config file", e);
        } finally {
            invalidateCache();
        }
    }

    private void initOverrides() {
        initOverrides(new File(APP_COMPAT_DATA_DIR, OVERRIDES_FILE),
                new File(STATIC_OVERRIDES_PRODUCT_DIR, OVERRIDES_FILE));
    }

    @VisibleForTesting
    void initOverrides(File dynamicOverridesFile, File staticOverridesFile) {
        // Clear overrides from all changes before loading.

        for (CompatChange c : mChanges.values()) {
            c.clearOverrides();
        }


        loadOverrides(staticOverridesFile);

        synchronized (mOverridesFileLock) {
            mOverridesFile = dynamicOverridesFile;
            mBackupOverridesFile = makeBackupFile(dynamicOverridesFile);
            if (mBackupOverridesFile.exists()) {
                mOverridesFile.delete();
                mBackupOverridesFile.renameTo(mOverridesFile);
            }
            loadOverrides(mOverridesFile);
        }

        if (staticOverridesFile.exists()) {
            // Only save overrides if there is a static overrides file.
            saveOverrides();
        }
    }

    private File makeBackupFile(File overridesFile) {
        return new File(overridesFile.getPath() + ".bak");
    }

    private void loadOverrides(File overridesFile) {
        if (!overridesFile.exists()) {
            // Overrides file doesn't exist.
            return;
        }

        try (InputStream in = new BufferedInputStream(new FileInputStream(overridesFile))) {
            Overrides overrides = com.android.server.compat.overrides.XmlParser.read(in);
            if (overrides == null) {
                Slog.w(TAG, "Parsing " + overridesFile.getPath() + " failed");
                return;
            }
            for (ChangeOverrides changeOverrides : overrides.getChangeOverrides()) {
                long changeId = changeOverrides.getChangeId();
                CompatChange compatChange = mChanges.get(changeId);
                if (compatChange == null) {
                    Slog.w(TAG, "Change ID " + changeId + " not found. "
                            + "Skipping overrides for it.");
                    continue;
                }
                compatChange.loadOverrides(changeOverrides);
            }
        } catch (IOException | DatatypeConfigurationException | XmlPullParserException e) {
            Slog.w(TAG, "Error processing " + overridesFile + " " + e.toString());
            return;
        }
    }

    /**
     * Persist compat framework overrides to /data/misc/appcompat/compat_framework_overrides.xml
     */
    void saveOverrides() {
        synchronized (mOverridesFileLock) {
            if (mOverridesFile == null || mBackupOverridesFile == null) {
                return;
            }

            Overrides overrides = new Overrides();
            List<ChangeOverrides> changeOverridesList = overrides.getChangeOverrides();
            for (CompatChange c : mChanges.values()) {
                ChangeOverrides changeOverrides = c.saveOverrides();
                if (changeOverrides != null) {
                    changeOverridesList.add(changeOverrides);
                }
            }

            // Rename the file to the backup.
            if (mOverridesFile.exists()) {
                if (mBackupOverridesFile.exists()) {
                    mOverridesFile.delete();
                } else {
                    if (!mOverridesFile.renameTo(mBackupOverridesFile)) {
                        Slog.e(TAG, "Couldn't rename file " + mOverridesFile
                                + " to " + mBackupOverridesFile);
                        return;
                    }
                }
            }

            // Create the file if it doesn't already exist
            try {
                mOverridesFile.createNewFile();
            } catch (IOException e) {
                Slog.e(TAG, "Could not create override config file: " + e.toString());
                return;
            }
            try (PrintWriter out = new PrintWriter(mOverridesFile)) {
                XmlWriter writer = new XmlWriter(out);
                XmlWriter.write(writer, overrides);
            } catch (IOException e) {
                Slog.e(TAG, e.toString());
            }

            // Remove the backup if the write succeeds.
            mBackupOverridesFile.delete();
        }
    }

    IOverrideValidator getOverrideValidator() {
        return mOverrideValidator;
    }

    private void invalidateCache() {
        ChangeIdStateCache.invalidate();
    }

    /**
     * Rechecks all the existing overrides for a package.
     */
    void recheckOverrides(String packageName) {
        Long versionCode = getVersionCodeOrNull(packageName);
        boolean shouldInvalidateCache = false;
        for (CompatChange c : mChanges.values()) {
            OverrideAllowedState allowedState =
                    mOverrideValidator.getOverrideAllowedStateForRecheck(c.getId(),
                            packageName);
            shouldInvalidateCache |= c.recheckOverride(packageName, allowedState, versionCode);
        }
        if (shouldInvalidateCache) {
            invalidateCache();
        }
    }

    @Nullable
    private Long getVersionCodeOrNull(String packageName) {
        try {
            ApplicationInfo applicationInfo = mContext.getPackageManager().getApplicationInfo(
                    packageName, MATCH_ANY_USER);
            return applicationInfo.longVersionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    void registerContentObserver() {
        mOverrideValidator.registerContentObserver();
    }
}
