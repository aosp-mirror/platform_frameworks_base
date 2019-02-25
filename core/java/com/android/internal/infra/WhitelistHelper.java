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

package com.android.internal.infra;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;

/**
 * Helper class for keeping track of whitelisted packages/activities.
 *
 * @hide
 */
public final class WhitelistHelper {

    private static final String TAG = "WhitelistHelper";

    /**
     * Map of whitelisted packages/activities. The whole package is whitelisted if its
     * corresponding value is {@code null}.
     */
    @Nullable
    private ArrayMap<String, ArraySet<ComponentName>> mWhitelistedPackages;

    /**
     * Sets the whitelist with the given packages and activities. The list is cleared if both
     * packageNames and components are {@code null}.
     *
     * @param packageNames packages to be whitelisted.
     * @param components activities to be whitelisted.
     */
    public void setWhitelist(@Nullable ArraySet<String> packageNames,
            @Nullable ArraySet<ComponentName> components) {
        mWhitelistedPackages = null;
        if (packageNames == null && components == null) return;

        mWhitelistedPackages = new ArrayMap<>();

        if (packageNames != null) {
            for (int i = 0; i < packageNames.size(); i++) {
                mWhitelistedPackages.put(packageNames.valueAt(i), null);
            }
        }

        if (components != null) {
            for (int i = 0; i < components.size(); i++) {
                final ComponentName component = components.valueAt(i);
                if (component == null) {
                    Log.w(TAG, "setWhitelist(): component is null");
                    continue;
                }

                final String packageName = component.getPackageName();
                ArraySet<ComponentName> set = mWhitelistedPackages.get(packageName);
                if (set == null) {
                    set = new ArraySet<>();
                    mWhitelistedPackages.put(packageName, set);
                }
                set.add(component);
            }
        }
    }

    /**
     * Returns {@code true} if the entire package is whitelisted.
     */
    public boolean isWhitelisted(@NonNull String packageName) {
        Preconditions.checkNotNull(packageName);

        if (mWhitelistedPackages == null) return false;

        return mWhitelistedPackages.containsKey(packageName)
                && mWhitelistedPackages.get(packageName) == null;
    }

    /**
     * Returns {@code true} if the specified activity is whitelisted.
     */
    public boolean isWhitelisted(@NonNull ComponentName componentName) {
        Preconditions.checkNotNull(componentName);

        final String packageName = componentName.getPackageName();
        final ArraySet<ComponentName> whitelistedComponents = getWhitelistedComponents(packageName);
        if (whitelistedComponents != null) {
            return whitelistedComponents.contains(componentName);
        }

        return isWhitelisted(packageName);
    }

    /**
     * Returns a set of whitelisted components with the given package, or null if nothing is
     * whitelisted.
     */
    @Nullable
    public ArraySet<ComponentName> getWhitelistedComponents(@NonNull String packageName) {
        Preconditions.checkNotNull(packageName);

        return mWhitelistedPackages == null ? null : mWhitelistedPackages.get(packageName);
    }

    @Override
    public String toString() {
        return "WhitelistHelper[" + mWhitelistedPackages + ']';
    }

    /**
     * Dumps it!
     */
    public void dump(@NonNull String prefix, @NonNull String message, @NonNull PrintWriter pw) {
        if (mWhitelistedPackages == null || mWhitelistedPackages.size() == 0) {
            pw.print(prefix); pw.print(message); pw.println(": (no whitelisted packages)");
            return;
        }

        final int size = mWhitelistedPackages.size();
        pw.print(prefix); pw.print(message); pw.print(": "); pw.print(size);
        pw.println(" packages");
        for (int i = 0; i < mWhitelistedPackages.size(); i++) {
            final String packageName = mWhitelistedPackages.keyAt(i);
            final ArraySet<ComponentName> components = mWhitelistedPackages.valueAt(i);
            pw.print(prefix); pw.print(i); pw.print("."); pw.print(packageName); pw.print(": ");
            if (components == null) {
                pw.println("(whole package)");
                continue;
            }

            pw.print("["); pw.print(components.valueAt(0));
            for (int j = 1; j < components.size(); j++) {
                pw.print(", "); pw.print(components.valueAt(i));
            }
            pw.println("]");
        }
    }
}
