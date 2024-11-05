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

package android.net.vcn;

import android.annotation.Nullable;
import android.app.SystemServiceRegistry;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemProperties;

/**
 * Class for performing registration for VCN service.
 *
 * @hide
 */
// TODO: Expose it as @SystemApi(client = MODULE_LIBRARIES)
public final class VcnFrameworkInitializer {
    /**
     * Starting with {@link VANILLA_ICE_CREAM}, Telephony feature flags (e.g. {@link
     * PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}) are being checked before returning managers
     * that depend on them. If the feature is missing, {@link Context#getSystemService} will return
     * null.
     *
     * <p>This change is specific to VcnManager.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private static final long ENABLE_CHECKING_TELEPHONY_FEATURES_FOR_VCN = 330902016;

    /**
     * The corresponding vendor API for Android V
     *
     * <p>Starting with Android V, the vendor API format has switched to YYYYMM.
     *
     * @see <a href="https://preview.source.android.com/docs/core/architecture/api-flags">Vendor API
     *     level</a>
     */
    private static final int VENDOR_API_FOR_ANDROID_V = 202404;

    private VcnFrameworkInitializer() {}

    // Suppressing AndroidFrameworkCompatChange because we're querying vendor
    // partition SDK level, not application's target SDK version (which BTW we
    // also check through Compatibility framework a few lines below).
    @Nullable
    private static String getVcnFeatureDependency() {
        // Check SDK version of the client app. Apps targeting pre-V SDK might
        // have not checked for existence of these features.
        if (!Compatibility.isChangeEnabled(ENABLE_CHECKING_TELEPHONY_FEATURES_FOR_VCN)) {
            return null;
        }

        // Check SDK version of the vendor partition. Pre-V devices might have
        // incorrectly under-declared telephony features.
        final int vendorApiLevel =
                SystemProperties.getInt(
                        "ro.vendor.api_level", Build.VERSION.DEVICE_INITIAL_SDK_INT);
        if (vendorApiLevel < VENDOR_API_FOR_ANDROID_V) {
            return PackageManager.FEATURE_TELEPHONY;
        } else {
            return PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION;
        }
    }

    /**
     * Register VCN service to {@link Context}, so that {@link Context#getSystemService} can return
     * a VcnManager.
     *
     * @throws IllegalStateException if this is called anywhere besides {@link
     *     SystemServiceRegistry}.
     */
    public static void registerServiceWrappers() {
        SystemServiceRegistry.registerContextAwareService(
                VcnManager.VCN_MANAGEMENT_SERVICE_STRING,
                VcnManager.class,
                (context, serviceBinder) -> {
                    final String telephonyFeatureToCheck = getVcnFeatureDependency();
                    if (telephonyFeatureToCheck != null
                            && !context.getPackageManager()
                                    .hasSystemFeature(telephonyFeatureToCheck)) {
                        return null;
                    }
                    IVcnManagementService service =
                            IVcnManagementService.Stub.asInterface(serviceBinder);
                    return new VcnManager(context, service);
                });
    }
}
