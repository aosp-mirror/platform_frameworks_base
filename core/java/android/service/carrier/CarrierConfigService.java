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
 * A service that sets carrier configuration for telephony services.
 * <p>
 * To extend this class, you must declare the service in your manifest file to require the
 * {@link android.Manifest.permission#BIND_CARRIER_CONFIG_SERVICE} permission and include an intent
 * filter with the {@link #SERVICE_INTERFACE} action. For example:
 * </p>
 *
 * <pre>{@code
 * <service android:name=".MyCarrierConfigService"
 *       android:label="@string/service_name"
 *       android:permission="android.permission.BIND_CARRIER_CONFIG_SERVICE">
 *  <intent-filter>
 *      <action android:name="android.service.carrier.CarrierConfigService" />
 *  </intent-filter>
 * </service>
 * }</pre>
 */
public abstract class CarrierConfigService extends Service {

    public static final String SERVICE_INTERFACE = "android.service.carrier.CarrierConfigService";

    private final ICarrierConfigService.Stub mStubWrapper;

    public CarrierConfigService() {
        mStubWrapper = new ICarrierConfigServiceWrapper();
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
        if (!SERVICE_INTERFACE.equals(intent.getAction())) {
            return null;
        }
        return mStubWrapper;
    }

    /**
     * A wrapper around ICarrierConfigService that forwards calls to implementations of
     * {@link CarrierConfigService}.
     *
     * @hide
     */
    private class ICarrierConfigServiceWrapper extends ICarrierConfigService.Stub {

        @Override
        public PersistableBundle getCarrierConfig(CarrierIdentifier id) {
            return CarrierConfigService.this.onLoadConfig(id);
        }
    }
}
