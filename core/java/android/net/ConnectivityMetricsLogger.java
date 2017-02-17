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

import android.annotation.SystemApi;
import android.app.PendingIntent;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/** {@hide} */
@SystemApi
public class ConnectivityMetricsLogger {
    private static String TAG = "ConnectivityMetricsLogger";
    private static final boolean DBG = true;

    public static final String CONNECTIVITY_METRICS_LOGGER_SERVICE = "connectivity_metrics_logger";

    // Component Tags
    public static final int COMPONENT_TAG_CONNECTIVITY = 0;
    public static final int COMPONENT_TAG_BLUETOOTH    = 1;
    public static final int COMPONENT_TAG_WIFI         = 2;
    public static final int COMPONENT_TAG_TELECOM      = 3;
    public static final int COMPONENT_TAG_TELEPHONY    = 4;
    public static final int NUMBER_OF_COMPONENTS       = 5;

    // Event Tag
    public static final int TAG_SKIPPED_EVENTS = -1;

    public static final String DATA_KEY_EVENTS_COUNT = "count";

    public ConnectivityMetricsLogger() {
    }

    /**
     * Log a ConnectivityMetricsEvent.
     *
     * This method keeps track of skipped events when MetricsLoggerService throttles input events.
     * It skips logging when MetricsLoggerService is active. When throttling ends, it logs a
     * meta-event containing the number of events dropped. It is not safe to call this method
     * concurrently from different threads.
     *
     * @param timestamp is the epoch timestamp of the event in ms.
     * @param componentTag is the COMPONENT_* constant the event belongs to.
     * @param eventTag is an event type constant whose meaning is specific to the component tag.
     * @param data is a Parcelable instance representing the event.
     */
    public void logEvent(long timestamp, int componentTag, int eventTag, Parcelable data) {
    }

    /**
     * Retrieve events
     *
     * @param reference of the last event previously returned. The function will return
     *                  events following it.
     *                  If 0 then all events will be returned.
     *                  After the function call it will contain reference of the
     *                  last returned event.
     * @return events
     */
    public ConnectivityMetricsEvent[] getEvents(ConnectivityMetricsEvent.Reference reference) {
        return new ConnectivityMetricsEvent[0];
    }

    /**
     * Register PendingIntent which will be sent when new events are ready to be retrieved.
     */
    public boolean register(PendingIntent newEventsIntent) {
        return false;
    }

    public boolean unregister(PendingIntent newEventsIntent) {
        return false;
    }
}
