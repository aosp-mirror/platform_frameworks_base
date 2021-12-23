/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package android.service.carrier;

import android.annotation.CallSuper;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.ResultReceiver;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyRegistryManager;
import android.util.Log;

import com.android.internal.util.ArrayUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * A service that exposes carrier-specific functionality to the system.
 * <p>
 * To extend this class, you must declare the service in your manifest file to require the
 * {@link android.Manifest.permission#BIND_CARRIER_SERVICES} permission and include an intent
 * filter with the {@link #CARRIER_SERVICE_INTERFACE}. If the service should have a long-lived
 * binding, set <code>android.service.carrier.LONG_LIVED_BINDING</code> to <code>true</code> in the
 * service's metadata. For example:
 * </p>
 *
 * <pre>{@code
 * <service android:name=".MyCarrierService"
 *       android:label="@string/service_name"
 *       android:permission="android.permission.BIND_CARRIER_SERVICES">
 *  <intent-filter>
 *      <action android:name="android.service.carrier.CarrierService" />
 *  </intent-filter>
 *  <meta-data android:name="android.service.carrier.LONG_LIVED_BINDING"
 *             android:value="true" />
 * </service>
 * }</pre>
 */
public abstract class CarrierService extends Service {

    private static final String LOG_TAG = "CarrierService";

    public static final String CARRIER_SERVICE_INTERFACE = "android.service.carrier.CarrierService";

    private final ICarrierService.Stub mStubWrapper;

    public CarrierService() {
        mStubWrapper = new ICarrierServiceWrapper();
    }

    /**
     * Override this method to set carrier configuration.
     * <p>
     * This method will be called by telephony services to get carrier-specific configuration
     * values. The returned config will be saved by the system until,
     * <ol>
     * <li>The carrier app package is updated, or</li>
     * <li>The carrier app requests a reload with
     * {@link android.telephony.CarrierConfigManager#notifyConfigChangedForSubId
     * notifyConfigChangedForSubId}.</li>
     * </ol>
     * This method can be called after a SIM card loads, which may be before or after boot.
     * </p>
     * <p>
     * This method should not block for a long time. If expensive operations (e.g. network access)
     * are required, this method can schedule the work and return null. Then, use
     * {@link android.telephony.CarrierConfigManager#notifyConfigChangedForSubId
     * notifyConfigChangedForSubId} to trigger a reload when the config is ready.
     * </p>
     * <p>
     * Implementations should use the keys defined in {@link android.telephony.CarrierConfigManager
     * CarrierConfigManager}. Any configuration values not set in the returned {@link
     * PersistableBundle} may be overridden by the system's default configuration service.
     * </p>
     *
     * @param id contains details about the current carrier that can be used to decide what
     *           configuration values to return. Instead of using details like MCCMNC to decide
     *           current carrier, it also contains subscription carrier id
     *           {@link android.telephony.TelephonyManager#getSimCarrierId()}, a platform-wide
     *           unique identifier for each carrier, CarrierConfigService can directly use carrier
     *           id as the key to look up the carrier info.
     * @return a {@link PersistableBundle} object containing the configuration or null if default
     *         values should be used.
     * @deprecated use {@link #onLoadConfig(int, CarrierIdentifier)} instead.
     */
    @Deprecated
    public abstract PersistableBundle onLoadConfig(CarrierIdentifier id);

    /**
     * Override this method to set carrier configuration on the given {@code subscriptionId}.
     * <p>
     * This method will be called by telephony services to get carrier-specific configuration
     * values. The returned config will be saved by the system until,
     * <ol>
     * <li>The carrier app package is updated, or</li>
     * <li>The carrier app requests a reload with
     * {@link android.telephony.CarrierConfigManager#notifyConfigChangedForSubId
     * notifyConfigChangedForSubId}.</li>
     * </ol>
     * This method can be called after a SIM card loads, which may be before or after boot.
     * </p>
     * <p>
     * This method should not block for a long time. If expensive operations (e.g. network access)
     * are required, this method can schedule the work and return null. Then, use
     * {@link android.telephony.CarrierConfigManager#notifyConfigChangedForSubId
     * notifyConfigChangedForSubId} to trigger a reload when the config is ready.
     * </p>
     * <p>
     * Implementations should use the keys defined in {@link android.telephony.CarrierConfigManager
     * CarrierConfigManager}. Any configuration values not set in the returned {@link
     * PersistableBundle} may be overridden by the system's default configuration service.
     * </p>
     * <p>
     * By default, this method just calls {@link #onLoadConfig(CarrierIdentifier)} with specified
     * CarrierIdentifier {@code id}. Carrier app with target SDK
     * {@link android.os.Build.VERSION_CODES#TIRAMISU} and above should override this method to
     * load carrier configuration on the given {@code subscriptionId}.
     * Note that {@link #onLoadConfig(CarrierIdentifier)} is still called prior to
     * {@link android.os.Build.VERSION_CODES#TIRAMISU}.
     * </p>
     *
     * @param subscriptionId the subscription on which the carrier app should load configuration
     * @param id contains details about the current carrier that can be used to decide what
     *           configuration values to return. Instead of using details like MCCMNC to decide
     *           current carrier, it also contains subscription carrier id
     *           {@link android.telephony.TelephonyManager#getSimCarrierId()}, a platform-wide
     *           unique identifier for each carrier, CarrierConfigService can directly use carrier
     *           id as the key to look up the carrier info.
     * @return a {@link PersistableBundle} object containing the configuration or null if default
     *         values should be used.
     */
    @SuppressLint("NullableCollection")
    @Nullable
    public PersistableBundle onLoadConfig(int subscriptionId, @Nullable CarrierIdentifier id) {
        return onLoadConfig(id);
    }

    /**
     * Informs the system of an intentional upcoming carrier network change by
     * a carrier app. This call is optional and is only used to allow the
     * system to provide alternative UI while telephony is performing an action
     * that may result in intentional, temporary network lack of connectivity.
     * <p>
     * Based on the active parameter passed in, this method will either show or
     * hide the alternative UI. There is no timeout associated with showing
     * this UX, so a carrier app must be sure to call with active set to false
     * sometime after calling with it set to true.
     * <p>
     * Requires Permission: calling app has carrier privileges.
     *
     * @param active Whether the carrier network change is or shortly will be
     *               active. Set this value to true to begin showing
     *               alternative UI and false to stop.
     * @see android.telephony.TelephonyManager#hasCarrierPrivileges
     * @deprecated use {@link #notifyCarrierNetworkChange(int, boolean)} instead.
     *             With no parameter to specify the subscription, this API will
     *             apply to all subscriptions that the carrier app has carrier
     *             privileges on.
     */
    @Deprecated
    public final void notifyCarrierNetworkChange(boolean active) {
        TelephonyRegistryManager telephonyRegistryMgr =
            (TelephonyRegistryManager) this.getSystemService(
                Context.TELEPHONY_REGISTRY_SERVICE);
        if (telephonyRegistryMgr != null) {
            telephonyRegistryMgr.notifyCarrierNetworkChange(active);
        }
    }

    /**
     * Informs the system of an intentional upcoming carrier network change by a carrier app on the
     * given {@code subscriptionId}. This call is optional and is only used to allow the system to
     * provide alternative UI while telephony is performing an action that may result in
     * intentional, temporary network lack of connectivity.
     *
     * <p>Based on the active parameter passed in, this method will either show or hide the
     * alternative UI. There is no timeout associated with showing this UX, so a carrier app must
     * be sure to call with active set to false sometime after calling with it set to true.
     *
     * <p>Requires Permission: calling app has carrier privileges.
     *
     * @param subscriptionId the subscription of the carrier network that trigger the change.
     * @param active whether the carrier network change is or shortly will be active. Set this
     *               value to true to begin showing alternative UI and false to stop.
     * @see android.telephony.TelephonyManager#hasCarrierPrivileges
     */
    public final void notifyCarrierNetworkChange(int subscriptionId, boolean active) {
        TelephonyRegistryManager telephonyRegistryMgr = this.getSystemService(
                TelephonyRegistryManager.class);
        if (telephonyRegistryMgr != null) {
            telephonyRegistryMgr.notifyCarrierNetworkChange(subscriptionId, active);
        }
    }

    /**
     * If overriding this method, call through to the super method for any unknown actions.
     * {@inheritDoc}
     */
    @Override
    @CallSuper
    public IBinder onBind(Intent intent) {
        return mStubWrapper;
    }

    /**
     * A wrapper around ICarrierService that forwards calls to implementations of
     * {@link CarrierService}.
     * @hide
     */
    public class ICarrierServiceWrapper extends ICarrierService.Stub {
        /** @hide */
        public static final int RESULT_OK = 0;
        /** @hide */
        public static final int RESULT_ERROR = 1;
        /** @hide */
        public static final String KEY_CONFIG_BUNDLE = "config_bundle";

        @Override
        public void getCarrierConfig(int phoneId, CarrierIdentifier id, ResultReceiver result) {
            try {
                int subId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
                int[] subIds = SubscriptionManager.getSubId(phoneId);
                if (!ArrayUtils.isEmpty(subIds)) {
                    // There should be at most one active subscription mapping to the phoneId.
                    subId = subIds[0];
                }
                Bundle data = new Bundle();
                data.putParcelable(KEY_CONFIG_BUNDLE, CarrierService.this.onLoadConfig(subId, id));
                result.send(RESULT_OK, data);
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error in onLoadConfig: " + e.getMessage(), e);
                result.send(RESULT_ERROR, null);
            }
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            CarrierService.this.dump(fd, pw, args);
        }
    }
}
