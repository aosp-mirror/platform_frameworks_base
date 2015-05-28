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

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PersistableBundle;

/**
 * A service that exposes carrier-specific functionality to the system.
 * <p>
 * To extend this class, you must declare the service in your manifest file to require the
 * {@link android.Manifest.permission#BIND_CARRIER_SERVICES} permission and include an intent
 * filter with the {@link #CONFIG_SERVICE_INTERFACE} action if the service exposes carrier config
 * and the {@link #BIND_SERVICE_INTERFACE} action if the service should have a long-lived binding.
 * For example:
 * </p>
 *
 * <pre>{@code
 * <service android:name=".MyCarrierService"
 *       android:label="@string/service_name"
 *       android:permission="android.permission.BIND_CARRIER_SERVICES">
 *  <intent-filter>
 *      <action android:name="android.service.carrier.ConfigService" />
 *      <action android:name="android.service.carrier.BindService" />
 *  </intent-filter>
 * </service>
 * }</pre>
 */
public abstract class CarrierService extends Service {

    public static final String CONFIG_SERVICE_INTERFACE = "android.service.carrier.ConfigService";
    public static final String BIND_SERVICE_INTERFACE = "android.service.carrier.BindService";

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
     * {@link android.telephony.CarrierConfigManager#reloadCarrierConfigForSubId
     * reloadCarrierConfigForSubId}.</li>
     * </ol>
     * This method can be called after a SIM card loads, which may be before or after boot.
     * </p>
     * <p>
     * This method should not block for a long time. If expensive operations (e.g. network access)
     * are required, this method can schedule the work and return null. Then, use
     * {@link android.telephony.CarrierConfigManager#reloadCarrierConfigForSubId
     * reloadCarrierConfigForSubId} to trigger a reload when the config is ready.
     * </p>
     * <p>
     * Implementations should use the keys defined in {@link android.telephony.CarrierConfigManager
     * CarrierConfigManager}. Any configuration values not set in the returned {@link
     * PersistableBundle} may be overridden by the system's default configuration service.
     * </p>
     *
     * @param id contains details about the current carrier that can be used do decide what
     *            configuration values to return.
     * @return a {@link PersistableBundle} object containing the configuration or null if default
     *         values should be used.
     */
    public abstract PersistableBundle onLoadConfig(CarrierIdentifier id);

    /** @hide */
    @Override
    public final IBinder onBind(Intent intent) {
        if (!CONFIG_SERVICE_INTERFACE.equals(intent.getAction())
            || !BIND_SERVICE_INTERFACE.equals(intent.getAction())) {
            return null;
        }
        return mStubWrapper;
    }

    /**
     * A wrapper around ICarrierService that forwards calls to implementations of
     * {@link CarrierService}.
     *
     * @hide
     */
    private class ICarrierServiceWrapper extends ICarrierService.Stub {

        @Override
        public PersistableBundle getCarrierConfig(CarrierIdentifier id) {
            return CarrierService.this.onLoadConfig(id);
        }
    }
}
