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
import android.content.Context;
import android.os.TelephonyServiceManager;
import android.telephony.euicc.EuiccCardManager;
import android.telephony.euicc.EuiccManager;
import android.telephony.ims.ImsManager;

import com.android.internal.util.Preconditions;


/**
 * Class for performing registration for all telephony services.
 *
 * @hide
 */
public class TelephonyFrameworkInitializer {

    private TelephonyFrameworkInitializer() {
    }

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
                context -> new CarrierConfigManager(context)
        );
        SystemServiceRegistry.registerContextAwareService(
                Context.EUICC_SERVICE,
                EuiccManager.class,
                context -> new EuiccManager(context)
        );
        SystemServiceRegistry.registerContextAwareService(
                Context.EUICC_CARD_SERVICE,
                EuiccCardManager.class,
                context -> new EuiccCardManager(context)
        );
        SystemServiceRegistry.registerContextAwareService(
                Context.TELEPHONY_IMS_SERVICE,
                ImsManager.class,
                context -> new ImsManager(context)
        );
        SystemServiceRegistry.registerContextAwareService(
                Context.SMS_SERVICE,
                SmsManager.class,
                context -> SmsManager.getSmsManagerForContextAndSubscriptionId(context,
                        SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)
        );
    }

    /** @hide */
    public static TelephonyServiceManager getTelephonyServiceManager() {
        return sTelephonyServiceManager;
    }
}
