/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.appbinding;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;

/**
 * Utility class to find a persistent bound service within an app.
 */
public class AppBindingUtils {
    private static final String TAG = "AppBindingUtils";
    private AppBindingUtils() {
    }

    /**
     * Find a service with the action {@code serviceAction} in the package {@code packageName}.
     * Returns null in any of the following cases.
     * - No service with the action is found.
     * - More than 1 service with the action is found.
     * - Found service is not protected with the permission {@code servicePermission}.
     */
    @Nullable
    public static ServiceInfo findService(@NonNull String packageName, int userId,
            String serviceAction, String servicePermission,
            Class<?> serviceClassForLogging,
            IPackageManager ipm,
            StringBuilder errorMessage) {
        final String simpleClassName = serviceClassForLogging.getSimpleName();
        final Intent intent = new Intent(serviceAction);
        intent.setPackage(packageName);

        errorMessage.setLength(0); // Clear it.
        try {
            final ParceledListSlice<ResolveInfo> pls = ipm
                    .queryIntentServices(intent, null, /* flags=*/ 0, userId);
            if (pls == null || pls.getList().size() == 0) {
                errorMessage.append("Service with " + serviceAction + " not found.");
                return null;
            }
            final List<ResolveInfo> list = pls.getList();
            // Note if multiple services are found, that's an error, even if only one of them
            // is exported.
            if (list.size() > 1) {
                errorMessage.append("More than one " + simpleClassName + "'s found in package "
                                + packageName + ".  They'll all be ignored.");
                Log.e(TAG, errorMessage.toString());
                return null;
            }
            final ServiceInfo si = list.get(0).serviceInfo;

            if (!servicePermission.equals(si.permission)) {
                errorMessage.append(simpleClassName + " "
                        + si.getComponentName().flattenToShortString()
                        + " must be protected with " + servicePermission
                        + ".");
                Log.e(TAG, errorMessage.toString());
                return null;
            }
            return si;
        } catch (RemoteException e) {
            // Shouldn't happen
        }
        return null;
    }
}
