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

import android.annotation.NonNull;
import android.util.ArraySet;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.Set;

/**
 * Cache of distinct package/uid pairs that require being blocked from screen capture. This class is
 * not threadsafe and any call site should hold {@link WindowManagerGlobalLock}
 */
public class SensitiveContentPackages {
    private final ArraySet<PackageInfo> mProtectedPackages = new ArraySet<>();

    /** Returns {@code true} if package/uid pair should be blocked from screen capture */
    public boolean shouldBlockScreenCaptureForApp(String pkg, int uid) {
        for (int i = 0; i < mProtectedPackages.size(); i++) {
            PackageInfo info = mProtectedPackages.valueAt(i);
            if (info != null && info.mPkg.equals(pkg) && info.mUid == uid) {
                return true;
            }
        }
        return false;
    }

    /** Replaces the set of package/uid pairs to set that should be blocked from screen capture */
    public void setShouldBlockScreenCaptureForApp(@NonNull Set<PackageInfo> packageInfos) {
        mProtectedPackages.clear();
        mProtectedPackages.addAll(packageInfos);
    }

    void dump(PrintWriter pw) {
        final String innerPrefix = "  ";
        pw.println("SensitiveContentPackages:");
        pw.println(innerPrefix + "Packages that should block screen capture ("
                + mProtectedPackages.size() + "):");
        for (PackageInfo info : mProtectedPackages) {
            pw.println(innerPrefix + "  package=" + info.mPkg + "  uid=" + info.mUid);
        }
    }

    /** Helper class that represents a package/uid pair */
    public static class PackageInfo {
        private String mPkg;
        private int mUid;

        public PackageInfo(String pkg, int uid) {
            this.mPkg = pkg;
            this.mUid = uid;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PackageInfo)) return false;
            PackageInfo that = (PackageInfo) o;
            return mUid == that.mUid && Objects.equals(mPkg, that.mPkg);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPkg, mUid);
        }
    }
}
