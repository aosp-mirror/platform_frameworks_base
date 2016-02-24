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
package android.net;

import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/** {@hide} */
public class ConnectivityMetricsLogger {
    private static String TAG = "ConnectivityMetricsLogger";
    private static final boolean DBG = true;

    public static final String CONNECTIVITY_METRICS_LOGGER_SERVICE = "connectivity_metrics_logger";

    // Component Tags
    public static final int COMPONENT_TAG_CONNECTIVITY = 1;
    public static final int COMPONENT_TAG_BLUETOOTH = 2;
    public static final int COMPONENT_TAG_WIFI = 3;
    public static final int COMPONENT_TAG_TELECOM = 4;
    public static final int COMPONENT_TAG_TELEPHONY = 5;

    private IConnectivityMetricsLogger mService;

    public ConnectivityMetricsLogger() {
        mService = IConnectivityMetricsLogger.Stub.asInterface(ServiceManager.getService(
                CONNECTIVITY_METRICS_LOGGER_SERVICE));
    }

    public void logEvent(long timestamp, int componentTag, int eventTag, Parcelable data) {
        if (mService == null) {
            if (DBG) {
                Log.d(TAG, "logEvent(" + componentTag + "," + eventTag + ") Service not ready");
            }
        } else {
            try {
                mService.logEvent(new ConnectivityMetricsEvent(timestamp, componentTag, eventTag, data));
            } catch (RemoteException e) {
                Log.e(TAG, "Error logging event " + e.getMessage());
            }
        }
    }
}
