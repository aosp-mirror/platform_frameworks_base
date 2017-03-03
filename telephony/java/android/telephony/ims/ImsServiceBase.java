/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package android.telephony.ims;

import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * Base ImsService Implementation, which is used by the ImsResolver to bind. ImsServices that do not
 * need to provide an ImsService implementation but still wish to be managed by the ImsResolver
 * lifecycle may implement this class directly.
 * @hide
 */
@SystemApi
public class ImsServiceBase extends Service {

    /**
     * Binder connection that does nothing but keep the connection between this Service and the
     * framework active. If this service crashes, the framework will be notified.
     */
    private IBinder mConnection = new Binder();

    @Override
    public IBinder onBind(Intent intent) {
        return mConnection;
    }

}
