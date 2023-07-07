/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.telephony.satellite.stub;

import android.annotation.SdkConstant;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.android.telephony.Rlog;

/**
 * Main SatelliteService implementation, which binds via the Telephony SatelliteServiceController.
 * Services that extend SatelliteService must register the service in their AndroidManifest to be
 * detected by the framework. First, the application must declare that they use the
 * "android.permission.BIND_SATELLITE_SERVICE" permission. Then, the SatelliteService definition in
 * the manifest must follow the following format:
 *
 * ...
 * <service android:name=".EgSatelliteService"
 *     android:permission="android.permission.BIND_SATELLITE_SERVICE" >
 *     ...
 *     <intent-filter>
 *         <action android:name="android.telephony.satellite.SatelliteService" />
 *     </intent-filter>
 * </service>
 * ...
 *
 * The telephony framework will then bind to the SatelliteService defined in the manifest if it is
 * the default SatelliteService defined in the device overlay "config_satellite_service_package".
 * @hide
 */
public class SatelliteService extends Service {
    private static final String TAG = "SatelliteService";

    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.telephony.satellite.SatelliteService";

    /**
     * @hide
     */
    @Override
    public IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            Rlog.d(TAG, "SatelliteService bound");
            return new SatelliteImplBase(Runnable::run).getBinder();
        }
        return null;
    }
}
