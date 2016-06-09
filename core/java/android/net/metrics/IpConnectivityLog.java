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

package android.net.metrics;

import android.net.ConnectivityMetricsEvent;
import android.net.ConnectivityMetricsLogger;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

/**
 * Specialization of the ConnectivityMetricsLogger class for recording IP connectivity events.
 * {@hide}
 */
class IpConnectivityLog extends ConnectivityMetricsLogger {
    private static String TAG = "IpConnectivityMetricsLogger";
    private static final boolean DBG = false;

    /**
     * Log an IpConnectivity event. Contrary to logEvent(), this method does not
     * keep track of skipped events and is thread-safe for callers.
     *
     * @param timestamp is the epoch timestamp of the event in ms.
     * @param data is a Parcelable IpConnectivityEvent instance representing the event.
     *
     * @return true if the event was successfully logged.
     */
    public <T extends IpConnectivityEvent & Parcelable> boolean log(long timestamp, T data) {
        if (mService == null) {
            if (DBG) {
                Log.d(TAG, CONNECTIVITY_METRICS_LOGGER_SERVICE + " service not ready");
            }
            return false;
        }

        if (System.currentTimeMillis() < mServiceUnblockedTimestampMillis) {
            if (DBG) {
                Log.d(TAG, "skipping logging due to throttling for IpConnectivity component");
            }
            return false;
        }

        try {
            final ConnectivityMetricsEvent event =
                new ConnectivityMetricsEvent(timestamp, COMPONENT_TAG_CONNECTIVITY, 0, data);
            final long result = mService.logEvent(event);
            if (result >= 0) {
                mServiceUnblockedTimestampMillis = result;
            }
            return (result == 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Error logging event", e);
            return false;
        }
    }
}
