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

import android.app.compat.ChangeIdStateCache;
import android.compat.Compatibility.ChangeConfig;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Environment;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.LongArray;
import android.util.LongSparseArray;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.compat.AndroidBuildClassifier;
import com.android.internal.compat.CompatibilityChangeConfig;
import com.android.internal.compat.CompatibilityChangeInfo;
import com.android.internal.compat.IOverrideValidator;
import com.android.internal.compat.OverrideAllowedState;
import com.android.server.compat.config.Change;
import com.android.server.compat.config.XmlParser;
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
 * This class maintains state relating to platform compatibility changes.
 *
 * <p>It stores the default configuration for each change, and any per-package overrides that have
 * been configured.
 */
final class CompatConfig {

    private static final String TAG = "CompatConfig";

    @GuardedBy("mChanges")
    private final LongSparseArray<CompatChange> mChanges = new LongSparseArray<>();

    private IOverrideValidator mOverrideValidator;

    @VisibleForTesting
    CompatConfig(AndroidBuildClassifier androidBuildClassifier, Context context) {
        mOverrideValidator = new OverrideValidatorImpl(androidBuildClassifier, context, this);
    }

    /**
     * Add a change. This is intended to be used by code that reads change config from the
     * filesystem. This should be done at system startup time.
     *
     * @param change The change to add. Any change with the same ID will be overwritten.
     */
    void addChange(CompatChange change) {
        synchronized (mChanges) {
            mChanges.put(change.getId(), change);
            invalidateCache();
        }
    }

    /**
     * Retrieves the set of disabled changes for a given app. Any change ID not in the returned
     * array is by default enabled for the app.
     *
     * @param app The app in question
     * @return A sorted long array of change IDs. We use a primitive array to minimize memory
     * footprint: Every app process will store this array statically so we aim to reduce
     * overhead as much as possible.
     */
    long[] getDisabledChanges(ApplicationInfo app) {
        LongArray disabled = new LongArray();
        synchronized (mChanges) {
            for (int i = 0; i < mChanges.size(); ++i) {
                CompatChange c = mChanges.valueAt(i);
                if (!c.isEnabled(app)) {
                    disabled.add(c.getId());
                }
            }
        }
        // Note: we don't need to explicitly sort the array, as the behaviour of LongSparseArray
        // (mChanges) ensures it's already sorted.
        return disabled.toArray();
    }

    /**
     * Look up a change ID by name.
     *
     * @param name Name of the change to look up
     * @return The change ID, or {@code -1} if no change with that name exists.
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
     * Find if a given change is enabled for a given application.
     *
     * @param changeId The ID of the change in question
     * @param app      App to check for
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
            return c.isEnabled(app);
        }
    }

    /**
     * Overrides the enabled state for a given change and app. This method is intended to be used
     * *only* for debugging purposes, ultimately invoked either by an adb command, or from some
     * developer settings UI.
     *
     * <p>Note, package overrides are not persistent and will be lost on system or runtime restart.
     *
     * @param changeId    The ID of the change to be overridden. Note, this call will succeed even
     *                    if
     *                    this change is not known; it will only have any effect if any code in the
     *                    platform is gated on the ID given.
     * @param packageName The app package name to override the change for.
     * @param enabled     If the change should be enabled or disabled.
     * @return {@code true} if the change existed before adding the override.
     */
    boolean addOverride(long changeId, String packageName, boolean enabled)
            throws RemoteException, SecurityException {
        boolean alreadyKnown = true;
        OverrideAllowedState allowedState =
                mOverrideValidator.getOverrideAllowedState(changeId, packageName);
        allowedState.enforce(changeId, packageName);
        synchronized (mChanges) {
            CompatChange c = mChanges.get(changeId);
            if (c == null) {
                alreadyKnown = false;
                c = new CompatChange(changeId);
                addChange(c);
            }
            c.addPackageOverride(packageName, enabled);
            invalidateCache();
        }
        return alreadyKnown;
    }

    /**
     * Check whether the change is known to the compat config.
     *
     * @return {@code true} if the change is known.
     */
    boolean isKnownChangeId(long changeId) {
        synchronized (mChanges) {
            CompatChange c = mChanges.get(changeId);
            return c != null;
        }
    }

    /**
     * Returns the minimum sdk version for which this change should be enabled (or 0 if it is not
     * target sdk gated).
     */
    int minTargetSdkForChangeId(long changeId) {
        synchronized (mChanges) {
            CompatChange c = mChanges.get(changeId);
            if (c == null) {
                return 0;
            }
            return c.getEnableAfterTargetSdk();
        }
    }

    /**
     * Returns whether the change is marked as logging only.
     */
    boolean isLoggingOnly(long changeId) {
        synchronized (mChanges) {
            CompatChange c = mChanges.get(changeId);
            if (c == null) {
                return false;
            }
            return c.getLoggingOnly();
        }
    }

    /**
     * Returns whether the change is marked as disabled.
     */
    boolean isDisabled(long changeId) {
        synchronized (mChanges) {
            CompatChange c = mChanges.get(changeId);
            if (c == null) {
                return false;
            }
            return c.getDisabled();
        }
    }

    /**
     * Removes an override previously added via {@link #addOverride(long, String, boolean)}. This
     * restores the default behaviour for the given change and app, once any app processes have been
     * restarted.
     *
     * @param changeId    The ID of the change that was overridden.
     * @param packageName The app package name that was overridden.
     * @return {@code true} if an override existed;
     */
    boolean removeOverride(long changeId, String packageName)
            throws RemoteException, SecurityException {
        boolean overrideExists = false;
        synchronized (mChanges) {
            CompatChange c = mChanges.get(changeId);
            try {
                if (c != null) {
                    overrideExists = c.hasOverride(packageName);
                    if (overrideExists) {
                        OverrideAllowedState allowedState =
                                mOverrideValidator.getOverrideAllowedState(changeId, packageName);
                        allowedState.enforce(changeId, packageName);
                        c.removePackageOverride(packageName);
                    }
                }
            } catch (RemoteException e) {
                // Should never occur, since validator is in the same process.
                throw new RuntimeException("Unable to call override validator!", e);
            }
            invalidateCache();
        }
        return overrideExists;
    }

    /**
     * Overrides the enabled state for a given change and app.
     *
     * <p>Note, package overrides are not persistent and will be lost on system or runtime restart.
     *
     * @param overrides   list of overrides to default changes config.
     * @param packageName app for which the overrides will be applied.
     */
    void addOverrides(CompatibilityChangeConfig overrides, String packageName)
            throws RemoteException, SecurityException {
        synchronized (mChanges) {
            for (Long changeId : overrides.enabledChanges()) {
                addOverride(changeId, packageName, true);
            }
            for (Long changeId : overrides.disabledChanges()) {
                addOverride(changeId, packageName, false);

            }
            invalidateCache();
        }
    }

    /**
     * Removes all overrides previously added via {@link #addOverride(long, String, boolean)} or
     * {@link #addOverrides(CompatibilityChangeConfig, String)} for a certain package.
     *
     * <p>This restores the default behaviour for the given change and app, once any app
     * processes have been restarted.
     *
     * @param packageName The package for which the overrides should be purged.
     */
    void removePackageOverrides(String packageName) throws RemoteException, SecurityException {
        synchronized (mChanges) {
            for (int i = 0; i < mChanges.size(); ++i) {
                try {
                    CompatChange change = mChanges.valueAt(i);
                    if (change.hasOverride(packageName)) {
                        OverrideAllowedState allowedState =
                                mOverrideValidator.getOverrideAllowedState(change.getId(),
                                        packageName);
                        allowedState.enforce(change.getId(), packageName);
                        if (change != null) {
                            mChanges.valueAt(i).removePackageOverride(packageName);
                        }
                    }
                } catch (RemoteException e) {
                    // Should never occur, since validator is in the same process.
                    throw new RuntimeException("Unable to call override validator!", e);
                }
            }
            invalidateCache();
        }
    }

    private long[] getAllowedChangesAfterTargetSdkForPackage(String packageName,
                                                             int targetSdkVersion)
                    throws RemoteException {
        LongArray allowed = new LongArray();
        synchronized (mChanges) {
            for (int i = 0; i < mChanges.size(); ++i) {
                try {
                    CompatChange change = mChanges.valueAt(i);
                    if (change.getEnableAfterTargetSdk() != targetSdkVersion) {
                        continue;
                    }
                    OverrideAllowedState allowedState =
                            mOverrideValidator.getOverrideAllowedState(change.getId(),
                                                                       packageName);
                    if (allowedState.state == OverrideAllowedState.ALLOWED) {
                        allowed.add(change.getId());
                    }
                } catch (RemoteException e) {
                    // Should never occur, since validator is in the same process.
                    throw new RuntimeException("Unable to call override validator!", e);
                }
            }
        }
        return allowed.toArray();
    }

    /**
     * Enables all changes with enabledAfterTargetSdk == {@param targetSdkVersion} for
     * {@param packageName}.
     *
     * @return The number of changes that were toggled.
     */
    int enableTargetSdkChangesForPackage(String packageName, int targetSdkVersion)
            throws RemoteException {
        long[] changes = getAllowedChangesAfterTargetSdkForPackage(packageName, targetSdkVersion);
        for (long changeId : changes) {
            addOverride(changeId, packageName, true);
        }
        return changes.length;
    }


    /**
     * Disables all changes with enabledAfterTargetSdk == {@param targetSdkVersion} for
     * {@param packageName}.
     *
     * @return The number of changes that were toggled.
     */
    int disableTargetSdkChangesForPackage(String packageName, int targetSdkVersion)
            throws RemoteException {
        long[] changes = getAllowedChangesAfterTargetSdkForPackage(packageName, targetSdkVersion);
        for (long changeId : changes) {
            addOverride(changeId, packageName, false);
        }
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

    @VisibleForTesting
    void clearChanges() {
        synchronized (mChanges) {
            mChanges.clear();
        }
    }

    /**
     * Dumps the current list of compatibility config information.
     *
     * @param pw The {@link PrintWriter} instance to which the information will be dumped.
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
     * Get the config for a given app.
     *
     * @param applicationInfo the {@link ApplicationInfo} for which the info should be dumped.
     * @return A {@link CompatibilityChangeConfig} which contains the compat config info for the
     * given app.
     */

    CompatibilityChangeConfig getAppConfig(ApplicationInfo applicationInfo) {
        Set<Long> enabled = new HashSet<>();
        Set<Long> disabled = new HashSet<>();
        synchronized (mChanges) {
            for (int i = 0; i < mChanges.size(); ++i) {
                CompatChange c = mChanges.valueAt(i);
                if (c.isEnabled(applicationInfo)) {
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
     * @return An array of {@link CompatibilityChangeInfo} with the current changes.
     */
    CompatibilityChangeInfo[] dumpChanges() {
        synchronized (mChanges) {
            CompatibilityChangeInfo[] changeInfos = new CompatibilityChangeInfo[mChanges.size()];
            for (int i = 0; i < mChanges.size(); ++i) {
                CompatChange change = mChanges.valueAt(i);
                changeInfos[i] = new CompatibilityChangeInfo(change.getId(),
                        change.getName(),
                        change.getEnableAfterTargetSdk(),
                        change.getDisabled(),
                        change.getLoggingOnly(),
                        change.getDescription());
            }
            return changeInfos;
        }
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
        config.invalidateCache();
        return config;
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
            for (Change change : XmlParser.read(in).getCompatChange()) {
                Slog.d(TAG, "Adding: " + change.toString());
                addChange(new CompatChange(change));
            }
        } catch (IOException | DatatypeConfigurationException | XmlPullParserException e) {
            Slog.e(TAG, "Encountered an error while reading/parsing compat config file", e);
        }
    }

    IOverrideValidator getOverrideValidator() {
        return mOverrideValidator;
    }

    private void invalidateCache() {
        ChangeIdStateCache.invalidate();
    }
}
