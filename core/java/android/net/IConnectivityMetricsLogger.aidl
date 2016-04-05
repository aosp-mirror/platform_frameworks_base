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

import android.app.PendingIntent;
import android.net.ConnectivityMetricsEvent;

/** {@hide} */
interface IConnectivityMetricsLogger {

    /**
     * @return 0 on success
     *        <0 if error happened
     *        >0 timestamp after which new events will be accepted
     */
    long logEvent(in ConnectivityMetricsEvent event);
    long logEvents(in ConnectivityMetricsEvent[] events);

    /**
     * @param reference of the last event previously returned. The function will return
     *                  events following it.
     *                  If 0 then all events will be returned.
     *                  After the function call it will contain reference of the last event.
     */
    ConnectivityMetricsEvent[] getEvents(inout ConnectivityMetricsEvent.Reference reference);

    boolean register(in PendingIntent newEventsIntent);
    void unregister(in PendingIntent newEventsIntent);
}
