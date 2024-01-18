/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.server.pm.ShortcutService.DumpFilter;

import java.io.PrintWriter;

/**
 * This class holds per-user information for {@link ShortcutService} that doesn't have to be
 * persisted and is kept in-memory.
 *
 * The access to it must be guarded with the shortcut manager lock.
 */
public class ShortcutNonPersistentUser {

    private final int mUserId;

    /**
     * Keep track of additional packages that other parts of the system have said are
     * allowed to access shortcuts.  The key is the part of the system it came from,
     * the value is the package name that has access.  We don't persist these because
     * at boot all relevant system services will push this data back to us they do their
     * normal evaluation of the state of the world.
     */
    private final ArrayMap<String, String> mHostPackages = new ArrayMap<>();

    /**
     * Set of package name values from above.
     */
    private final ArraySet<String> mHostPackageSet = new ArraySet<>();

    public ShortcutNonPersistentUser(int userId) {
        mUserId = userId;
    }

    public int getUserId() {
        return mUserId;
    }

    public void setShortcutHostPackage(@NonNull String type, @Nullable String packageName) {
        if (packageName != null) {
            mHostPackages.put(type, packageName);
        } else {
            mHostPackages.remove(type);
        }

        mHostPackageSet.clear();
        for (int i = 0; i < mHostPackages.size(); i++) {
            mHostPackageSet.add(mHostPackages.valueAt(i));
        }
    }

    public boolean hasHostPackage(@NonNull String packageName) {
        return mHostPackageSet.contains(packageName);
    }

    public void dump(@NonNull PrintWriter pw, @NonNull String prefix, DumpFilter filter) {
        if (filter.shouldDumpDetails()) {
            if (mHostPackages.size() > 0) {
                pw.print(prefix);
                pw.print("Non-persistent: user ID:");
                pw.println(mUserId);

                pw.print(prefix);
                pw.println("  Host packages:");
                for (int i = 0; i < mHostPackages.size(); i++) {
                    pw.print(prefix);
                    pw.print("    ");
                    pw.print(mHostPackages.keyAt(i));
                    pw.print(": ");
                    pw.println(mHostPackages.valueAt(i));
                }
                pw.println();
            }
        }
    }
}
