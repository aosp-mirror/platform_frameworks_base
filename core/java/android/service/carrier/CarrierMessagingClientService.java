/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.service.carrier;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;

/**
 * If the default SMS app has a service that extends this class, the system always tries to bind
 * it so that the process is always running, which allows the app to have a persistent connection
 * to the server.
 *
 * <p>The service must have an
 * {@link android.telephony.TelephonyManager#ACTION_CARRIER_MESSAGING_CLIENT_SERVICE}
 * action in the intent handler, and be protected with
 * {@link android.Manifest.permission#BIND_CARRIER_MESSAGING_CLIENT_SERVICE}.
 * However the service does not have to be exported.
 *
 * <p>The service must be associated with a non-main process, meaning it must have an
 * {@code android:process} tag in its manifest entry.
 *
 * <p>An app can use
 * {@link android.content.pm.PackageManager#setComponentEnabledSetting(ComponentName, int, int)}
 * to disable or enable the service. An app should use it to disable the service when it no longer
 * needs to be running.
 *
 * <p>When the owner process crashes, the service will be re-bound automatically after a
 * back-off.
 *
 * <p>Note the process may still be killed if the system is under heavy memory pressure, in which
 * case the process will be re-started later.
 *
 * <p>Example: First, define a subclass in the application:
 * <pre>
 * public class MyCarrierMessagingClientService extends CarrierMessagingClientService {
 * }
 * </pre>
 * Then, declare it in its {@code AndroidManifest.xml}:
 * <pre>
 * &lt;service
 *    android:name=".MyCarrierMessagingClientService"
 *    android:exported="false"
 *    android:process=":persistent"
 *    android:permission="android.permission.BIND_CARRIER_MESSAGING_CLIENT_SERVICE"&gt;
 *    &lt;intent-filter&gt;
 *        &lt;action android:name="android.telephony.action.CARRIER_MESSAGING_CLIENT_SERVICE" /&gt;
 *    &lt;/intent-filter&gt;
 * &lt;/service&gt;
 * </pre>
 */
public class CarrierMessagingClientService extends Service {
    private final ICarrierMessagingClientServiceImpl mImpl;

    public CarrierMessagingClientService() {
        mImpl = new ICarrierMessagingClientServiceImpl();
    }

    @Override
    public final IBinder onBind(Intent intent) {
        return mImpl.asBinder();
    }

    private class ICarrierMessagingClientServiceImpl extends ICarrierMessagingClientService.Stub {
    }
}
