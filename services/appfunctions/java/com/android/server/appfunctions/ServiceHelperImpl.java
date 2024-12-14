/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.appfunctions;

import android.Manifest;
import android.annotation.NonNull;
import android.app.appfunctions.AppFunctionService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.UserHandle;

import java.util.Objects;

class ServiceHelperImpl implements ServiceHelper {
    private final Context mContext;

    // TODO(b/357551503): Keep track of unlocked users.

    ServiceHelperImpl(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
    }

    @Override
    public Intent resolveAppFunctionService(
            @NonNull String targetPackageName, @NonNull UserHandle targetUser) {
        Intent serviceIntent = new Intent(AppFunctionService.SERVICE_INTERFACE);
        serviceIntent.setPackage(targetPackageName);
        ResolveInfo resolveInfo =
                mContext.createContextAsUser(targetUser, /* flags= */ 0)
                        .getPackageManager()
                        .resolveService(serviceIntent, 0);
        if (resolveInfo == null || resolveInfo.serviceInfo == null) {
            return null;
        }

        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        if (!Manifest.permission.BIND_APP_FUNCTION_SERVICE.equals(serviceInfo.permission)) {
            return null;
        }
        serviceIntent.setComponent(new ComponentName(serviceInfo.packageName, serviceInfo.name));

        return serviceIntent;
    }
}
