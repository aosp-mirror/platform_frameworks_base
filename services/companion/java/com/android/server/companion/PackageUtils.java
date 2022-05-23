/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.companion;

import static android.content.pm.PackageManager.FEATURE_COMPANION_DEVICE_SETUP;
import static android.content.pm.PackageManager.GET_CONFIGURATIONS;
import static android.content.pm.PackageManager.GET_PERMISSIONS;

import static com.android.server.companion.CompanionDeviceManagerService.TAG;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.companion.CompanionDeviceService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.PackageInfoFlags;
import android.content.pm.PackageManager.ResolveInfoFlags;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility methods for working with {@link PackageInfo}-s.
 */
final class PackageUtils {
    private static final Intent COMPANION_SERVICE_INTENT =
            new Intent(CompanionDeviceService.SERVICE_INTERFACE);
    private static final String PROPERTY_PRIMARY_TAG =
            "android.companion.PROPERTY_PRIMARY_COMPANION_DEVICE_SERVICE";

    static @Nullable PackageInfo getPackageInfo(@NonNull Context context,
            @UserIdInt int userId, @NonNull String packageName) {
        final PackageManager pm = context.getPackageManager();
        final PackageInfoFlags flags = PackageInfoFlags.of(GET_PERMISSIONS | GET_CONFIGURATIONS);
        return Binder.withCleanCallingIdentity(() ->
                pm.getPackageInfoAsUser(packageName, flags , userId));
    }

    static void enforceUsesCompanionDeviceFeature(@NonNull Context context,
            @UserIdInt int userId, @NonNull String packageName) {
        final boolean requested = ArrayUtils.contains(
                getPackageInfo(context, userId, packageName).reqFeatures,
                FEATURE_COMPANION_DEVICE_SETUP);

        if (requested) {
            throw new IllegalStateException("Must declare uses-feature "
                    + FEATURE_COMPANION_DEVICE_SETUP
                    + " in manifest to use this API");
        }
    }

    /**
     * @return list of {@link CompanionDeviceService}-s per package for a given user.
     *         Services marked as "primary" would always appear at the head of the lists, *before*
     *         all non-primary services.
     */
    static @NonNull Map<String, List<ComponentName>> getCompanionServicesForUser(
            @NonNull Context context, @UserIdInt int userId) {
        final PackageManager pm = context.getPackageManager();
        final List<ResolveInfo> companionServices = pm.queryIntentServicesAsUser(
                COMPANION_SERVICE_INTENT, ResolveInfoFlags.of(0), userId);

        final Map<String, List<ComponentName>> packageNameToServiceInfoList =
                new HashMap<>(companionServices.size());

        for (ResolveInfo resolveInfo : companionServices) {
            final ServiceInfo service = resolveInfo.serviceInfo;

            final boolean requiresPermission = Manifest.permission.BIND_COMPANION_DEVICE_SERVICE
                    .equals(resolveInfo.serviceInfo.permission);
            if (!requiresPermission) {
                Slog.w(TAG, "CompanionDeviceService "
                        + service.getComponentName().flattenToShortString() + " must require "
                        + "android.permission.BIND_COMPANION_DEVICE_SERVICE");
                continue;
            }

            // We'll need to prepend "primary" services, while appending the other (non-primary)
            // services to the list.
            final ArrayList<ComponentName> services =
                    (ArrayList<ComponentName>) packageNameToServiceInfoList.computeIfAbsent(
                            service.packageName, it -> new ArrayList<>(1));

            final ComponentName componentName = service.getComponentName();

            if (isPrimaryCompanionDeviceService(pm, componentName, userId)) {
                // "Primary" service should be at the head of the list.
                services.add(0, componentName);
            } else {
                services.add(componentName);
            }
        }

        return packageNameToServiceInfoList;
    }

    private static boolean isPrimaryCompanionDeviceService(@NonNull PackageManager pm,
            @NonNull ComponentName componentName, @UserIdInt int userId) {
        try {
            return pm.getPropertyAsUser(PROPERTY_PRIMARY_TAG, componentName.getPackageName(),
                    componentName.getClassName(), userId).getBoolean();
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
