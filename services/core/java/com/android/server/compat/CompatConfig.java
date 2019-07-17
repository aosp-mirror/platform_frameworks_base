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

import android.content.pm.ApplicationInfo;
import android.text.TextUtils;
import android.util.LongArray;
import android.util.LongSparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

/**
 * This class maintains state relating to platform compatibility changes.
 *
 * <p>It stores the default configuration for each change, and any per-package overrides that have
 * been configured.
 */
public final class CompatConfig {

    private static final CompatConfig sInstance = new CompatConfig();

    @GuardedBy("mChanges")
    private final LongSparseArray<CompatChange> mChanges = new LongSparseArray<>();

    @VisibleForTesting
    public CompatConfig() {
    }

    /**
     * @return The static instance of this class to be used within the system server.
     */
    public static CompatConfig get() {
        return sInstance;
    }

    /**
     * Add a change. This is intended to be used by code that reads change config from the
     * filesystem. This should be done at system startup time.
     *
     * @param change The change to add. Any change with the same ID will be overwritten.
     */
    public void addChange(CompatChange change) {
        synchronized (mChanges) {
            mChanges.put(change.getId(), change);
        }
    }

    /**
     * Retrieves the set of disabled changes for a given app. Any change ID not in the returned
     * array is by default enabled for the app.
     *
     * @param app The app in question
     * @return A sorted long array of change IDs. We use a primitive array to minimize memory
     *      footprint: Every app process will store this array statically so we aim to reduce
     *      overhead as much as possible.
     */
    public long[] getDisabledChanges(ApplicationInfo app) {
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
    public long lookupChangeId(String name) {
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
     * @param app App to check for
     * @return {@code true} if the change is enabled for this app. Also returns {@code true} if the
     *      change ID is not known, as unknown changes are enabled by default.
     */
    public boolean isChangeEnabled(long changeId, ApplicationInfo app) {
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
     * @param changeId The ID of the change to be overridden. Note, this call will succeed even if
     *                 this change is not known; it will only have any affect if any code in the
     *                 platform is gated on the ID given.
     * @param packageName The app package name to override the change for.
     * @param enabled If the change should be enabled or disabled.
     */
    public void addOverride(long changeId, String packageName, boolean enabled) {
        synchronized (mChanges) {
            CompatChange c = mChanges.get(changeId);
            if (c == null) {
                c = new CompatChange(changeId);
                addChange(c);
            }
            c.addPackageOverride(packageName, enabled);
        }
    }

    /**
     * Removes an override previously added via {@link #addOverride(long, String, boolean)}. This
     * restores the default behaviour for the given change and app, once any app processes have been
     * restarted.
     *
     * @param changeId The ID of the change that was overridden.
     * @param packageName The app package name that was overridden.
     */
    public void removeOverride(long changeId, String packageName) {
        synchronized (mChanges) {
            CompatChange c = mChanges.get(changeId);
            if (c != null) {
                c.removePackageOverride(packageName);
            }
        }
    }

}
