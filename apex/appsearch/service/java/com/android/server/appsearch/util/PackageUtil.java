/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.appsearch.util;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.UserHandle;

/**
 * Utilities for interacting with {@link android.content.pm.PackageManager},
 * {@link android.os.UserHandle}, and other parts of dealing with apps and binder.
 *
 * @hide
 */
public class PackageUtil {
    private PackageUtil() {}

    /**
     * Finds the UID of the {@code packageName}. Returns {@link Process#INVALID_UID} if unable to
     * find the UID.
     */
    public static int getPackageUidAsUser(
            @NonNull Context context, @NonNull String packageName, @NonNull UserHandle user) {
        Context userContext = context.createContextAsUser(user, /*flags=*/ 0);
        return getPackageUid(userContext, packageName);
    }

    /**
     * Finds the UID of the {@code packageName} in the given {@code context}. Returns
     * {@link Process#INVALID_UID} if unable to find the UID.
     */
    public static int getPackageUid(@NonNull Context context, @NonNull String packageName) {
        try {
            return context.getPackageManager().getPackageUid(packageName, /*flags=*/ 0);
        } catch (PackageManager.NameNotFoundException e) {
            return Process.INVALID_UID;
        }
    }
}
