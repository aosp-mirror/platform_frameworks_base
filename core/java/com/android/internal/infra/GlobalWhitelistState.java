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
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.List;

/**
 * Helper class used to manage a {@link WhitelistHelper} per user instance when the main service
 * cannot hold a lock when external entities (typically {@code ActivityManagerService}) needs to
 * get allowlist info.
 *
 * <p>This class is thread safe.
 */
// TODO: add unit tests
public class GlobalWhitelistState {

    // Uses full-name to avoid collision with service-provided mLock
    protected final Object mGlobalWhitelistStateLock = new Object();

    // TODO: should not be exposed directly
    @Nullable
    @GuardedBy("mGlobalWhitelistStateLock")
    protected SparseArray<WhitelistHelper> mWhitelisterHelpers;

    /**
     * Sets the allowlist for the given user.
     */
    public void setWhitelist(@UserIdInt int userId, @Nullable List<String> packageNames,
            @Nullable List<ComponentName> components) {
        synchronized (mGlobalWhitelistStateLock) {
            if (mWhitelisterHelpers == null) {
                mWhitelisterHelpers = new SparseArray<>(1);
            }
            WhitelistHelper helper = mWhitelisterHelpers.get(userId);
            if (helper == null) {
                helper = new WhitelistHelper();
                mWhitelisterHelpers.put(userId, helper);
            }
            helper.setWhitelist(packageNames, components);
        }
    }

    /**
     * Checks if the given package is allowlisted for the given user.
     */
    public boolean isWhitelisted(@UserIdInt int userId, @NonNull String packageName) {
        synchronized (mGlobalWhitelistStateLock) {
            if (mWhitelisterHelpers == null) return false;
            final WhitelistHelper helper = mWhitelisterHelpers.get(userId);
            return helper == null ? false : helper.isWhitelisted(packageName);
        }
    }

    /**
     * Checks if the given component is allowlisted for the given user.
     */
    public boolean isWhitelisted(@UserIdInt int userId, @NonNull ComponentName componentName) {
        synchronized (mGlobalWhitelistStateLock) {
            if (mWhitelisterHelpers == null) return false;
            final WhitelistHelper helper = mWhitelisterHelpers.get(userId);
            return helper == null ? false : helper.isWhitelisted(componentName);
        }
    }

    /**
     * Gets the allowlisted components for the given package and user.
     */
    public ArraySet<ComponentName> getWhitelistedComponents(@UserIdInt int userId,
            @NonNull String packageName) {
        synchronized (mGlobalWhitelistStateLock) {
            if (mWhitelisterHelpers == null) return null;
            final WhitelistHelper helper = mWhitelisterHelpers.get(userId);
            return helper == null ? null : helper.getWhitelistedComponents(packageName);
        }
    }

    /**
     * Gets packages that are either entirely allowlisted or have components that are allowlisted
     * for the given user.
     */
    public ArraySet<String> getWhitelistedPackages(@UserIdInt int userId) {
        synchronized (mGlobalWhitelistStateLock) {
            if (mWhitelisterHelpers == null) return null;
            final WhitelistHelper helper = mWhitelisterHelpers.get(userId);
            return helper == null ? null : helper.getWhitelistedPackages();
        }
    }

    /**
     * Resets the allowlist for the given user.
     */
    public void resetWhitelist(@NonNull int userId) {
        synchronized (mGlobalWhitelistStateLock) {
            if (mWhitelisterHelpers == null) return;
            mWhitelisterHelpers.remove(userId);
            if (mWhitelisterHelpers.size() == 0) {
                mWhitelisterHelpers = null;
            }
        }
    }

    /**
     * Dumps it!
     */
    public void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
        pw.print(prefix); pw.print("State: ");
        synchronized (mGlobalWhitelistStateLock) {
            if (mWhitelisterHelpers == null) {
                pw.println("empty");
                return;
            }
            pw.print(mWhitelisterHelpers.size()); pw.println(" services");
            final String prefix2 = prefix + "  ";
            for (int i = 0; i < mWhitelisterHelpers.size(); i++) {
                final int userId  = mWhitelisterHelpers.keyAt(i);
                final WhitelistHelper helper = mWhitelisterHelpers.valueAt(i);
                helper.dump(prefix2, "Whitelist for userId " + userId, pw);
            }
        }
    }
}
