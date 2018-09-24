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
package android.app;

import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;

/**
 * If the default SMS app has a service that extends this class, the system always tries to bind
 * it so that the process is always running, which allows the app to have a persistent connection
 * to the server.
 *
 * <p>The service must have {@link android.telephony.TelephonyManager#ACTION_SMS_APP_SERVICE}
 * action in the intent handler, and be protected with
 * {@link android.Manifest.permission#BIND_SMS_APP_SERVICE}. However the service does not have to
 * be exported.
 *
 * <p>Apps can use
 * {@link android.content.pm.PackageManager#setComponentEnabledSetting(ComponentName, int, int)}
 * to disable/enable the service. Apps should use it to disable the service when it no longer needs
 * to be running.
 *
 * <p>When the owner process crashes, the service will be re-bound automatically after a
 * back-off.
 *
 * <p>Note the process may still be killed if the system is under heavy memory pressure, in which
 * case the process will be re-started later.
 */
public class SmsAppService extends Service {
    private final ISmsAppService mImpl;

    public SmsAppService() {
        mImpl = new ISmsAppServiceImpl();
    }

    @Override
    public final IBinder onBind(Intent intent) {
        return mImpl.asBinder();
    }

    private class ISmsAppServiceImpl extends ISmsAppService.Stub {
    }
}
