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
 * Main SatelliteGatewayService implementation, which binds via the Telephony SatelliteController.
 * Services that extend SatelliteGatewayService must register the service in their AndroidManifest
 * to be detected by the framework. The application must declare that they require the
 * "android.permission.BIND_SATELLITE_GATEWAY_SERVICE" permission to ensure that nothing else can
 * bind to their service except the Telephony framework. The SatelliteGatewayService definition in
 * the manifest must follow the following format:
 *
 * ...
 * <service android:name=".EgSatelliteGatewayService"
 *     android:permission="android.permission.BIND_SATELLITE_GATEWAY_SERVICE" >
 *     ...
 *     <intent-filter>
 *         <action android:name="android.telephony.satellite.SatelliteGatewayService" />
 *     </intent-filter>
 * </service>
 * ...
 *
 * The telephony framework will then bind to the SatelliteGatewayService defined in the manifest if
 * it is the default SatelliteGatewayService defined in the device overlay
 * "config_satellite_gateway_service_package".
 * @hide
 */
public abstract class SatelliteGatewayService extends Service {
    private static final String TAG = "SatelliteGatewayService";

    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.telephony.satellite.SatelliteGatewayService";

    private final IBinder mBinder = new ISatelliteGateway.Stub() {};

    /**
     * @hide
     */
    @Override
    public final IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            Rlog.d(TAG, "SatelliteGatewayService bound");
            return mBinder;
        }
        return null;
    }

    /**
     * @return The binder for the ISatelliteGateway.
     * @hide
     */
    public final IBinder getBinder() {
        return mBinder;
    }
}
