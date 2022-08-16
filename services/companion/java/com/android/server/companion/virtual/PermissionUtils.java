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

package com.android.server.companion.virtual;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.UserHandle;
import android.util.Slog;

/**
 * Utility methods for checking permissions required for VirtualDeviceManager operations.
 */
class PermissionUtils {

    private static final String LOG_TAG = "VDM.PermissionUtils";

    /**
     * Verifies whether the calling package name matches the calling app uid.
     *
     * @param context the context
     * @param callingPackage the calling application package name
     * @return {@code true} if the package name matches {@link Binder#getCallingUid()}, or
     *   {@code false} otherwise
     */
    public static boolean validateCallingPackageName(Context context, String callingPackage) {
        final int callingUid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            int packageUid = context.getPackageManager()
                    .getPackageUidAsUser(callingPackage, UserHandle.getUserId(callingUid));
            if (packageUid != callingUid) {
                Slog.e(LOG_TAG, "validatePackageName: App with package name " + callingPackage
                        + " is UID " + packageUid + " but caller is " + callingUid);
                return false;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(LOG_TAG, "validatePackageName: App with package name " + callingPackage
                    + " does not exist");
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return true;
    }
}
