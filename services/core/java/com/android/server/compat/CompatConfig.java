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

import android.annotation.Nullable;
import android.app.compat.ChangeIdStateCache;
import android.app.compat.PackageOverride;
import android.compat.Compatibility.ChangeConfig;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.text.TextUtils;
import android.util.LongArray;
import android.util.LongSparseArray;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.compat.AndroidBuildClassifier;
import com.android.internal.compat.CompatibilityChangeConfig;
import com.android.internal.compat.CompatibilityChangeInfo;
import com.android.internal.compat.CompatibilityOverrideConfig;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    @GuardedBy("mChanges")
    private final LongSparseArray<CompatChange> mChanges = new LongSparseArray<>();

    private final OverrideValidatorImpl mOverrideValidator;
    private final AndroidBuildClassifier mAndroidBuildClassifier;
    private Context mContext;
    private File mOverridesFile;

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
     * <p>This is intended to be used by code that reads change config from the filesystem. This
     * should be done at system startup time.
     *
     * <p>Any change with the same ID will be overwritten.
     *
     * @param change the change to add
     */
    void addChange(CompatChange change) {
        synchronized (mChanges) {
            mChanges.put(change.getId(), change);
            invalidateCache();
        }
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
        synchronized (mChanges) {
            for (int i = 0; i < mChanges.size(); ++i) {
                CompatChange c = mChanges.valueAt(i);
                if (!c.isEnabled(app, mAndroidBuildClassifier)) {
                    disabled.add(c.getId());
                }
            }
        }
        // Note: we don't need to explicitly sort the array, as the behaviour of LongSparseArray
        // (mChanges) ensures it's already sorted.
        return disabled.toArray();
    }

    /**
     * Looks up a change ID by name.
     *
     * @param name name of the change to look up
     * @return the change ID, or {@code -1} if no change with that name exists
     */
    long lookupChangeId(String name) {
        synchronized (mChanges) {
            for (int i = 0; i < mChanges.size(); ++i) {
                if (TextUtils.equals(mChanges.valueAt(i).getName(), name)) {
                    return mChanges.keyAt(i);
                }
            }
        }
        return -1;
    }

    /**
     * Checks if a given change is enabled for a given application.
     *
     * @param changeId the ID of the change in question
     * @param app      app to check for
     * @return {@code true} if the change is enabled for this app. Also returns {@code true} if the
     * change ID is not known, as unknown changes are enabled by default.
     */
    boolean isChangeEnabled(long changeId, ApplicationInfo app) {
        synchronized (mChanges) {
            CompatChange c = mChanges.get(changeId);
            if (c == null) {
                // we know nothing about this change: default behaviour is enabled.
                return true;
            }
            return c.isEnabled(app, mAndroidBuildClassifier);
        }
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
        synchronized (mChanges) {
            CompatChange c = mChanges.get(changeId);
            if (c == null) {
                // we know nothing about this change: default behaviour is enabled.
                return true;
            }
            return c.willBeEnabled(packageName);
        }
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
    boolean addOverride(long changeId, String packageName, boolean enabled) {
        boolean alreadyKnown = addOverrideUnsafe(changeId, packageName,
                new PackageOverride.Builder().setEnabled(enabled).build());
        saveOverrides();
        invalidateCache();
        return alreadyKnown;
    }

    /**
     * Overrides the enabled state for a given change and app.
     *
     * <p>Note, package overrides are not persistent and will be lost on system or runtime restart.
     *
     * @param overrides   list of overrides to default changes config.
     * @param packageName app for which the overrides will be applied.
     */
    void addOverrides(CompatibilityOverrideConfig overrides, String packageName) {
        for (Long changeId : overrides.overrides.keySet()) {
            addOverrideUnsafe(changeId, packageName, overrides.overrides.get(changeId));
        }
        saveOverrides();
        invalidateCache();
    }

    private boolean addOverrideUnsafe(long changeId, String packageName,
            PackageOverride overrides) {
        boolean alreadyKnown = true;
        OverrideAllowedState allowedState =
                mOverrideValidator.getOverrideAllowedState(changeId, packageName);
        allowedState.enforce(changeId, packageName);
        Long versionCode = getVersionCodeOrNull(packageName);
        synchronized (mChanges) {
            CompatChange c = mChanges.get(changeId);
            if (c == null) {
                alreadyKnown = false;
                c = new CompatChange(changeId);
                addChange(c);
            }
            c.addPackageOverride(packageName, overrides, allowedState, versionCode);
            invalidateCache();
        }
        return alreadyKnown;
    }

    /** Checks whether the change is known to the compat config. */
    boolean isKnownChangeId(long changeId) {
        synchronized (mChanges) {
            CompatChange c = mChanges.get(changeId);
            return c != null;
        }
    }

    /**
     * Returns the maximum SDK version for which this change can be opted in (or -1 if it is not
     * target SDK gated).
     */
    int maxTargetSdkForChangeIdOptIn(long changeId) {
        synchronized (mChanges) {
            CompatChange c = mChanges.get(changeId);
            if (c != null && c.getEnableSinceTargetSdk() != -1) {
                return c.getEnableSinceTargetSdk() - 1;
            }
            return -1;
        }
    }

    /**
     * Returns whether the change is marked as logging only.
     */
    boolean isLoggingOnly(long changeId) {
        synchronized (mChanges) {
            CompatChange c = mChanges.get(changeId);
            return c != null && c.getLoggingOnly();
        }
    }

    /**
     * Returns whether the change is marked as disabled.
     */
    boolean isDisabled(long changeId) {
        synchronized (mChanges) {
            CompatChange c = mChanges.get(changeId);
            return c != null && c.getDisabled();
        }
    }

    /**
     * Returns whether the change is overridable.
     */
    boolean isOverridable(long changeId) {
        synchronized (mChanges) {
            CompatChange c = mChanges.get(changeId);
            return c != null && c.getOverridable();
        }
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
    boolean removeOverride(long changeId, String packageName) {
        boolean overrideExists = removeOverrideUnsafe(changeId, packageName);
        saveOverrides();
        invalidateCache();
        return overrideExists;
    }

    /**
     * Unsafe version of {@link #removeOverride(long, String)}.
     * It does not save the overrides.
     */
    private boolean removeOverrideUnsafe(long changeId, String packageName) {
        Long versionCode = getVersionCodeOrNull(packageName);
        synchronized (mChanges) {
            CompatChange c = mChanges.get(changeId);
            if (c != null) {
                return removeOverrideUnsafe(c, packageName, versionCode);
            }
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
        if (change.hasPackageOverride(packageName)) {
            allowedState.enforce(changeId, packageName);
            change.removePackageOverride(packageName, allowedState, versionCode);
            invalidateCache();
            return true;
        }
        return false;
    }

    /**
     * Removes all overrides previously added via {@link #addOverride(long, String, boolean)} or
     * {@link #addOverrides(CompatibilityOverrideConfig, String)} for a certain package.
     *
     * <p>This restores the default behaviour for the given app.
     *
     * @param packageName the package for which the overrides should be purged
     */
    void removePackageOverrides(String packageName) {
        Long versionCode = getVersionCodeOrNull(packageName);
        synchronized (mChanges) {
            for (int i = 0; i < mChanges.size(); ++i) {
                CompatChange change = mChanges.valueAt(i);
                removeOverrideUnsafe(change, packageName, versionCode);
            }
            saveOverrides();
            invalidateCache();
        }
    }

    /**
     * Removes overrides whose change ID is specified in {@code overridesToRemove} that were
     * previously added via {@link #addOverride(long, String, boolean)} or
     * {@link #addOverrides(CompatibilityOverrideConfig, String)} for a certain package.
     *
     * <p>This restores the default behaviour for the given change IDs and app.
     *
     * @param overridesToRemove list of change IDs for which to restore the default behaviour.
     * @param packageName       the package for which the overrides should be purged
     */
    void removePackageOverrides(CompatibilityOverridesToRemoveConfig overridesToRemove,
            String packageName) {
        for (Long changeId : overridesToRemove.changeIds) {
            removeOverrideUnsafe(changeId, packageName);
        }
        saveOverrides();
        invalidateCache();
    }

    private long[] getAllowedChangesSinceTargetSdkForPackage(String packageName,
            int targetSdkVersion) {
        LongArray allowed = new LongArray();
        synchronized (mChanges) {
            for (int i = 0; i < mChanges.size(); ++i) {
                CompatChange change = mChanges.valueAt(i);
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
        for (long changeId : changes) {
            addOverrideUnsafe(changeId, packageName,
                    new PackageOverride.Builder().setEnabled(true).build());
        }
        saveOverrides();
        invalidateCache();
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
        for (long changeId : changes) {
            addOverrideUnsafe(changeId, packageName,
                    new PackageOverride.Builder().setEnabled(false).build());
        }
        saveOverrides();
        invalidateCache();
        return changes.length;
    }

    boolean registerListener(long changeId, CompatChange.ChangeListener listener) {
        boolean alreadyKnown = true;
        synchronized (mChanges) {
            CompatChange c = mChanges.get(changeId);
            if (c == null) {
                alreadyKnown = false;
                c = new CompatChange(changeId);
                addChange(c);
            }
            c.registerListener(listener);
        }
        return alreadyKnown;
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
        synchronized (mChanges) {
            mChanges.clear();
        }
    }

    /**
     * Dumps the current list of compatibility config information.
     *
     * @param pw {@link PrintWriter} instance to which the information will be dumped
     */
    void dumpConfig(PrintWriter pw) {
        synchronized (mChanges) {
            if (mChanges.size() == 0) {
                pw.println("No compat overrides.");
                return;
            }
            for (int i = 0; i < mChanges.size(); ++i) {
                CompatChange c = mChanges.valueAt(i);
                pw.println(c.toString());
            }
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
        synchronized (mChanges) {
            for (int i = 0; i < mChanges.size(); ++i) {
                CompatChange c = mChanges.valueAt(i);
                if (c.isEnabled(applicationInfo, mAndroidBuildClassifier)) {
                    enabled.add(c.getId());
                } else {
                    disabled.add(c.getId());
                }
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
        synchronized (mChanges) {
            CompatibilityChangeInfo[] changeInfos = new CompatibilityChangeInfo[mChanges.size()];
            for (int i = 0; i < mChanges.size(); ++i) {
                CompatChange change = mChanges.valueAt(i);
                changeInfos[i] = new CompatibilityChangeInfo(change);
            }
            return changeInfos;
        }
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
                addChange(new CompatChange(change));
            }
        } catch (IOException | DatatypeConfigurationException | XmlPullParserException e) {
            Slog.e(TAG, "Encountered an error while reading/parsing compat config file", e);
        }
    }

    private void initOverrides() {
        initOverrides(new File(APP_COMPAT_DATA_DIR, OVERRIDES_FILE),
                new File(STATIC_OVERRIDES_PRODUCT_DIR, OVERRIDES_FILE));
    }

    @VisibleForTesting
    void initOverrides(File dynamicOverridesFile, File staticOverridesFile) {
        // Clear overrides from all changes before loading.
        synchronized (mChanges) {
            for (int i = 0; i < mChanges.size(); ++i) {
                mChanges.valueAt(i).clearOverrides();
            }
        }

        loadOverrides(staticOverridesFile);

        mOverridesFile = dynamicOverridesFile;
        loadOverrides(dynamicOverridesFile);

        if (staticOverridesFile.exists()) {
            // Only save overrides if there is a static overrides file.
            saveOverrides();
        }
    }

    private void loadOverrides(File overridesFile) {
        if (!overridesFile.exists()) {
            // Overrides file doesn't exist.
            return;
        }

        try (InputStream in = new BufferedInputStream(new FileInputStream(overridesFile))) {
            Overrides overrides = com.android.server.compat.overrides.XmlParser.read(in);
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
        if (mOverridesFile == null) {
            return;
        }
        synchronized (mChanges) {
            // Create the file if it doesn't already exist
            try {
                mOverridesFile.createNewFile();
            } catch (IOException e) {
                Slog.e(TAG, "Could not create override config file: " + e.toString());
                return;
            }
            try (PrintWriter out = new PrintWriter(mOverridesFile)) {
                XmlWriter writer = new XmlWriter(out);
                Overrides overrides = new Overrides();
                List<ChangeOverrides> changeOverridesList = overrides.getChangeOverrides();
                for (int idx = 0; idx < mChanges.size(); ++idx) {
                    CompatChange c = mChanges.valueAt(idx);
                    ChangeOverrides changeOverrides = c.saveOverrides();
                    if (changeOverrides != null) {
                        changeOverridesList.add(changeOverrides);
                    }
                }
                XmlWriter.write(writer, overrides);
            } catch (IOException e) {
                Slog.e(TAG, e.toString());
            }
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
        synchronized (mChanges) {
            boolean shouldInvalidateCache = false;
            for (int idx = 0; idx < mChanges.size(); ++idx) {
                CompatChange c = mChanges.valueAt(idx);
                if (!c.hasPackageOverride(packageName)) {
                    continue;
                }
                OverrideAllowedState allowedState =
                        mOverrideValidator.getOverrideAllowedStateForRecheck(c.getId(),
                                packageName);
                shouldInvalidateCache |= c.recheckOverride(packageName, allowedState, versionCode);
            }
            if (shouldInvalidateCache) {
                invalidateCache();
            }
        }
    }

    @Nullable
    private Long getVersionCodeOrNull(String packageName) {
        try {
            ApplicationInfo applicationInfo = mContext.getPackageManager().getApplicationInfo(
                    packageName, 0);
            return applicationInfo.longVersionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    void registerContentObserver() {
        mOverrideValidator.registerContentObserver();
    }
}
