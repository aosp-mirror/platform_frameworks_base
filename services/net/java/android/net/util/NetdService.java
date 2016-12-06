/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.util;

import android.net.INetd;
import android.os.ServiceManager;
import android.util.Log;


/**
 * @hide
 */
public class NetdService {
    private static final String TAG = NetdService.class.getSimpleName();
    private static final String NETD_SERVICE_NAME = "netd";

    /**
     * It is the caller's responsibility to check for a null return value
     * and to handle RemoteException errors from invocations on the returned
     * interface if, for example, netd dies and is restarted.
     *
     * @return an INetd instance or null.
     */
    public static INetd getInstance() {
        final INetd netdInstance = INetd.Stub.asInterface(
                ServiceManager.getService(NETD_SERVICE_NAME));
        if (netdInstance == null) {
            Log.w(TAG, "WARNING: returning null INetd instance.");
        }
        return netdInstance;
    }
}
