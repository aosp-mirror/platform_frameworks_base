/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.telephony;

import android.annotation.NonNull;
import android.app.SystemServiceRegistry;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemProperties;
import android.os.TelephonyServiceManager;
import android.telephony.euicc.EuiccCardManager;
import android.telephony.euicc.EuiccManager;
import android.telephony.ims.ImsManager;
import android.telephony.satellite.SatelliteManager;

import com.android.internal.telephony.flags.Flags;
import com.android.internal.util.Preconditions;


/**
 * Class for performing registration for all telephony services.
 *
 * @hide
 */
public class TelephonyFrameworkInitializer {

    private TelephonyFrameworkInitializer() {
    }

    /**
     * Starting with {@link VANILLA_ICE_CREAM}, Telephony feature flags
     * (e.g. {@link PackageManager#FEATURE_TELEPHONY_SUBSCRIPTION}) are being checked before
     * returning managers that depend on them. If the feature is missing,
     * {@link Context#getSystemService} will return null.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    static final long ENABLE_CHECKING_TELEPHONY_FEATURES = 330583731;

    private static volatile TelephonyServiceManager sTelephonyServiceManager;

    /**
     * Sets an instance of {@link TelephonyServiceManager} that allows
     * the telephony mainline module to register/obtain telephony binder services. This is called
     * by the platform during the system initialization.
     *
     * @param telephonyServiceManager instance of {@link TelephonyServiceManager} that allows
     * the telephony mainline module to register/obtain telephony binder services.
     */
    public static void setTelephonyServiceManager(
            @NonNull TelephonyServiceManager telephonyServiceManager) {
        Preconditions.checkState(sTelephonyServiceManager == null,
                "setTelephonyServiceManager called twice!");
        sTelephonyServiceManager = Preconditions.checkNotNull(telephonyServiceManager);
    }

    // Suppressing AndroidFrameworkCompatChange because we're querying vendor
    // partition SDK level, not application's target SDK version (which BTW we
    // also check through Compatibility framework a few lines below).
    @SuppressWarnings("AndroidFrameworkCompatChange")
    private static boolean hasSystemFeature(Context context, String feature) {
        // Check release status of this change in behavior.
        if (!Flags.minimalTelephonyManagersConditionalOnFeatures()) return true;

        // Check SDK version of the vendor partition.
        final int vendorApiLevel = SystemProperties.getInt(
                "ro.vendor.api_level", Build.VERSION.DEVICE_INITIAL_SDK_INT);
        if (vendorApiLevel < Build.VENDOR_API_2024_Q2) return true;

        // Check SDK version of the client app.
        if (!Compatibility.isChangeEnabled(ENABLE_CHECKING_TELEPHONY_FEATURES)) return true;

        // Finally, check if the system feature is actually present.
        return context.getPackageManager().hasSystemFeature(feature);
    }

    /**
     * Called by {@link SystemServiceRegistry}'s static initializer and registers all telephony
     * services to {@link Context}, so that {@link Context#getSystemService} can return them.
     *
     * @throws IllegalStateException if this is called from anywhere besides
     * {@link SystemServiceRegistry}
     */
    public static void registerServiceWrappers() {
        SystemServiceRegistry.registerContextAwareService(
                Context.TELEPHONY_SERVICE,
                TelephonyManager.class,
                context -> new TelephonyManager(context)
        );
        SystemServiceRegistry.registerContextAwareService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE,
                SubscriptionManager.class,
                context -> new SubscriptionManager(context)
        );
        SystemServiceRegistry.registerContextAwareService(
                Context.CARRIER_CONFIG_SERVICE,
                CarrierConfigManager.class,
                context -> hasSystemFeature(context, PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)
                        ? new CarrierConfigManager(context) : null
        );
        SystemServiceRegistry.registerContextAwareService(
                Context.EUICC_SERVICE,
                EuiccManager.class,
                context -> hasSystemFeature(context, PackageManager.FEATURE_TELEPHONY_EUICC)
                        ? new EuiccManager(context) : null
        );
        SystemServiceRegistry.registerContextAwareService(
                Context.EUICC_CARD_SERVICE,
                EuiccCardManager.class,
                context -> hasSystemFeature(context, PackageManager.FEATURE_TELEPHONY_EUICC)
                        ? new EuiccCardManager(context) : null
        );
        SystemServiceRegistry.registerContextAwareService(
                Context.TELEPHONY_IMS_SERVICE,
                ImsManager.class,
                context -> hasSystemFeature(context, PackageManager.FEATURE_TELEPHONY_IMS)
                        ? new ImsManager(context) : null
        );
        SystemServiceRegistry.registerContextAwareService(
                Context.SMS_SERVICE,
                SmsManager.class,
                context -> hasSystemFeature(context, PackageManager.FEATURE_TELEPHONY_MESSAGING)
                        ? SmsManager.getSmsManagerForContextAndSubscriptionId(context,
                                SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) : null
        );
        SystemServiceRegistry.registerContextAwareService(
                Context.SATELLITE_SERVICE,
                SatelliteManager.class,
                context -> hasSystemFeature(context, PackageManager.FEATURE_TELEPHONY_SATELLITE)
                        ? new SatelliteManager(context) : null
        );
    }

    /** @hide */
    public static TelephonyServiceManager getTelephonyServiceManager() {
        return sTelephonyServiceManager;
    }
}
