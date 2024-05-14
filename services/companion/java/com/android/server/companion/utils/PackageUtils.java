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

package com.android.server.companion.utils;

import static android.content.pm.PackageManager.FEATURE_COMPANION_DEVICE_SETUP;
import static android.content.pm.PackageManager.GET_CONFIGURATIONS;
import static android.content.pm.PackageManager.GET_PERMISSIONS;
import static android.os.Binder.getCallingUid;

import static com.android.internal.R.array.config_companionDeviceCerts;
import static com.android.internal.R.array.config_companionDevicePackages;
import static com.android.internal.R.array.config_companionPermSyncEnabledCerts;
import static com.android.internal.R.array.config_companionPermSyncEnabledPackages;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.app.ecm.EnhancedConfirmationManager;
import android.companion.CompanionDeviceService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.PackageInfoFlags;
import android.content.pm.PackageManager.ResolveInfoFlags;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Process;
import android.permission.flags.Flags;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility methods for working with {@link PackageInfo}.
 */
public final class PackageUtils {

    private static final String TAG = "CDM_PackageUtils";

    private static final Intent COMPANION_SERVICE_INTENT =
            new Intent(CompanionDeviceService.SERVICE_INTERFACE);
    private static final String PROPERTY_PRIMARY_TAG =
            "android.companion.PROPERTY_PRIMARY_COMPANION_DEVICE_SERVICE";

    /**
     * Get package info
     */
    @Nullable
    public static PackageInfo getPackageInfo(@NonNull Context context,
            @UserIdInt int userId, @NonNull String packageName) {
        final PackageManager pm = context.getPackageManager();
        final PackageInfoFlags flags = PackageInfoFlags.of(GET_PERMISSIONS | GET_CONFIGURATIONS);
        return Binder.withCleanCallingIdentity(() -> {
            try {
                return pm.getPackageInfoAsUser(packageName, flags, userId);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e(TAG, "Package [" + packageName + "] is not found.");
                return null;
            }
        });
    }

    /**
     * Require the app to declare the companion device feature.
     */
    public static void enforceUsesCompanionDeviceFeature(@NonNull Context context,
            @UserIdInt int userId, @NonNull String packageName) {
        // Allow system server to create CDM associations without FEATURE_COMPANION_DEVICE_SETUP
        if (getCallingUid() == Process.SYSTEM_UID) {
            return;
        }

        PackageInfo packageInfo = getPackageInfo(context, userId, packageName);
        if (packageInfo == null) {
            throw new IllegalArgumentException("Package " + packageName + " doesn't exist.");
        }

        FeatureInfo[] requestedFeatures = packageInfo.reqFeatures;
        if (requestedFeatures != null) {
            for (FeatureInfo requestedFeature : requestedFeatures) {
                if (FEATURE_COMPANION_DEVICE_SETUP.equals(requestedFeature.name)) {
                    return;
                }
            }
        }

        throw new IllegalStateException("Must declare uses-feature "
                + FEATURE_COMPANION_DEVICE_SETUP
                + " in manifest to use this API");
    }

    /**
     * @return list of {@link CompanionDeviceService}-s per package for a given user.
     *         Services marked as "primary" would always appear at the head of the lists, *before*
     *         all non-primary services.
     */
    public static @NonNull Map<String, List<ComponentName>> getCompanionServicesForUser(
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

    /**
     * Check if the package is allowlisted in the overlay config.
     * For this we'll check to config arrays:
     *   - com.android.internal.R.array.config_companionDevicePackages
     * and
     *   - com.android.internal.R.array.config_companionDeviceCerts.
     * Both arrays are expected to contain similar number of entries.
     * config_companionDevicePackages contains package names of the allowlisted packages.
     * config_companionDeviceCerts contains SHA256 digests of the signatures of the
     * corresponding packages.
     * If a package is signed with one of several certificates, its package name would
     * appear multiple times in the config_companionDevicePackages, with different entries
     * (one for each of the valid signing certificates) at the corresponding positions in
     * config_companionDeviceCerts.
     */
    public static boolean isPackageAllowlisted(Context context,
            PackageManagerInternal packageManagerInternal, @NonNull String packageName) {
        return isPackageAllowlisted(context, packageManagerInternal, packageName,
                config_companionDevicePackages, config_companionDeviceCerts);
    }

    /**
     * Check if perm sync is allowlisted and auto-enabled for the package.
     */
    public static boolean isPermSyncAutoEnabled(Context context,
            PackageManagerInternal packageManagerInternal, String packageName) {
        return isPackageAllowlisted(context, packageManagerInternal, packageName,
                config_companionPermSyncEnabledPackages, config_companionPermSyncEnabledCerts);
    }

    private static boolean isPackageAllowlisted(Context context,
            PackageManagerInternal packageManagerInternal, String packageName,
            int packagesConfig, int certsConfig) {
        final String[] allowlistedPackages = context.getResources().getStringArray(packagesConfig);
        if (!ArrayUtils.contains(allowlistedPackages, packageName)) {
            Slog.d(TAG, packageName + " is not allowlisted.");
            return false;
        }

        final String[] allowlistedPackagesSignatureDigests = context.getResources()
                .getStringArray(certsConfig);
        final Set<String> allowlistedSignatureDigestsForRequestingPackage = new HashSet<>();
        for (int i = 0; i < allowlistedPackages.length; i++) {
            if (allowlistedPackages[i].equals(packageName)) {
                final String digest = allowlistedPackagesSignatureDigests[i].replaceAll(":", "");
                allowlistedSignatureDigestsForRequestingPackage.add(digest);
            }
        }

        final Signature[] requestingPackageSignatures = packageManagerInternal.getPackage(
                        packageName)
                .getSigningDetails().getSignatures();
        final String[] requestingPackageSignatureDigests =
                android.util.PackageUtils.computeSignaturesSha256Digests(
                        requestingPackageSignatures);

        boolean requestingPackageSignatureAllowlisted = false;
        for (String signatureDigest : requestingPackageSignatureDigests) {
            if (allowlistedSignatureDigestsForRequestingPackage.contains(signatureDigest)) {
                requestingPackageSignatureAllowlisted = true;
                break;
            }
        }

        if (!requestingPackageSignatureAllowlisted) {
            Slog.w(TAG, "Certificate mismatch for allowlisted package " + packageName);
        }

        return requestingPackageSignatureAllowlisted;
    }

    /**
     * Check if restricted settings is enabled for a side-loaded app.
     */
    public static boolean isRestrictedSettingsAllowed(
            Context context, String packageName, int uid) {
        if (Flags.enhancedConfirmationModeApisEnabled()) {
            EnhancedConfirmationManager ecm = context.getSystemService(
                    EnhancedConfirmationManager.class);
            try {
                return !ecm.isRestricted(packageName, AppOpsManager.OPSTR_ACCESS_NOTIFICATIONS);
            } catch (PackageManager.NameNotFoundException e) {
                return true;
            }
        } else {
            final int mode = context.getSystemService(AppOpsManager.class).noteOpNoThrow(
                    AppOpsManager.OP_ACCESS_RESTRICTED_SETTINGS, uid,
                    packageName, /* attributionTag= */ null, /* message= */ null);
            return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT;
        }
    }
}
