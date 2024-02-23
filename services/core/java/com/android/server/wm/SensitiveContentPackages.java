/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.wm;

import static android.permission.flags.Flags.sensitiveNotificationAppProtection;
import static android.view.flags.Flags.sensitiveContentAppProtection;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * Cache of distinct package/uid pairs that require being blocked from screen capture. This class is
 * not threadsafe and any call site should hold {@link WindowManagerGlobalLock}
 */
public class SensitiveContentPackages {
    private final ArraySet<PackageInfo> mProtectedPackages = new ArraySet<>();

    /**
     * Returns {@code true} if package/uid/window combination should be blocked
     * from screen capture.
     */
    public boolean shouldBlockScreenCaptureForApp(String pkg, int uid, IBinder windowToken) {
        if (!(sensitiveContentAppProtection() || sensitiveNotificationAppProtection())) {
            return false;
        }

        for (int i = 0; i < mProtectedPackages.size(); i++) {
            PackageInfo info = mProtectedPackages.valueAt(i);
            if (info != null && info.mPkg.equals(pkg) && info.mUid == uid) {
                // sensitiveContentAppProtection blocks specific window where sensitive content
                // is rendered, whereas sensitiveNotificationAppProtection blocks the package
                // if the package has a sensitive notification.
                if ((sensitiveContentAppProtection() && windowToken == info.getWindowToken())
                        || (sensitiveNotificationAppProtection() && info.getWindowToken() == null)
                ) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Adds the set of package/uid pairs to set that should be blocked from screen capture
     *
     * @param packageInfos packages to be blocked
     * @return {@code true} if packages set is modified, {@code false} otherwise.
     */
    public boolean addBlockScreenCaptureForApps(@NonNull ArraySet<PackageInfo> packageInfos) {
        if (mProtectedPackages.equals(packageInfos)) {
            // new set is equal to current set of packages, no need to update
            return false;
        }
        mProtectedPackages.addAll(packageInfos);
        return true;
    }

    /**
     * Clears apps added to collection of apps in which screen capture should be disabled.
     *
     * @param packageInfos set of {@link PackageInfo} whose windows should be unblocked
     *                     from capture.
     * @return {@code true} if packages set is modified, {@code false} otherwise.
     */
    public boolean removeBlockScreenCaptureForApps(@NonNull ArraySet<PackageInfo> packageInfos) {
        return mProtectedPackages.removeAll(packageInfos);
    }

    /**
     * Clears the set of package/uid pairs that should be blocked from screen capture
     *
     * @return {@code true} if packages set is modified, {@code false} otherwise.
     */
    public boolean clearBlockedApps() {
        if (mProtectedPackages.isEmpty()) {
            return false;
        }
        mProtectedPackages.clear();
        return true;
    }

    /**
     * @return the size of protected packages.
     */
    @VisibleForTesting
    public int size() {
        return mProtectedPackages.size();
    }

    void dump(PrintWriter pw) {
        final String innerPrefix = "  ";
        pw.println("SensitiveContentPackages:");
        pw.println(innerPrefix + "Packages that should block screen capture ("
                + mProtectedPackages.size() + "):");
        for (PackageInfo info : mProtectedPackages) {
            pw.println(innerPrefix + "  package=" + info.mPkg + "  uid=" + info.mUid
                    + " windowToken=" + info.mWindowToken);
        }
    }

    /**
     * Helper class that represents a package, uid, and window token combination, window token
     * is set to block screen capture at window level.
     */
    public static class PackageInfo {
        private final String mPkg;
        private final int mUid;

        @Nullable
        private final IBinder mWindowToken;

        public PackageInfo(String pkg, int uid) {
            this(pkg, uid, null);
        }

        public PackageInfo(String pkg, int uid, IBinder windowToken) {
            this.mPkg = pkg;
            this.mUid = uid;
            this.mWindowToken = windowToken;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PackageInfo)) return false;
            PackageInfo that = (PackageInfo) o;
            return mUid == that.mUid && Objects.equals(mPkg, that.mPkg)
                    && Objects.equals(mWindowToken, that.mWindowToken);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPkg, mUid, mWindowToken);
        }

        public IBinder getWindowToken() {
            return mWindowToken;
        }

        public int getUid() {
            return mUid;
        }

        public String getPkg() {
            return mPkg;
        }

        @Override
        public String toString() {
            return "package=" + mPkg + "  uid=" + mUid + " windowToken=" + mWindowToken;
        }
    }
}
