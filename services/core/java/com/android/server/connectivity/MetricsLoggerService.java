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

package com.android.server.connectivity;

import com.android.server.SystemService;

import android.content.Context;
import android.net.ConnectivityMetricsEvent;
import android.net.ConnectivityMetricsLogger;
import android.net.IConnectivityMetricsLogger;
import android.net.IConnectivityMetricsLoggerSubscriber;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/** {@hide} */
public class MetricsLoggerService extends SystemService {
    private static String TAG = "ConnectivityMetricsLoggerService";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    public MetricsLoggerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            Log.d(TAG, "onBootPhase: PHASE_SYSTEM_SERVICES_READY");
            publishBinderService(ConnectivityMetricsLogger.CONNECTIVITY_METRICS_LOGGER_SERVICE,
                    mBinder);
        }
    }

    private final int MAX_NUMBER_OF_EVENTS = 100;
    private final int MAX_TIME_OFFSET = 15*60*1000; // 15 minutes
    private final List<ConnectivityMetricsEvent> mEvents = new ArrayList<>();
    private long mLastSentEventTimeMillis = System.currentTimeMillis();

    private final void enforceConnectivityInternalPermission() {
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.CONNECTIVITY_INTERNAL,
                "MetricsLoggerService");
    }

    /**
     * Implementation of the IConnectivityMetricsLogger interface.
     */
    private final IConnectivityMetricsLogger.Stub mBinder = new IConnectivityMetricsLogger.Stub() {

        private final ArrayMap<IConnectivityMetricsLoggerSubscriber,
                IBinder.DeathRecipient> mSubscribers = new ArrayMap<>();


        private ConnectivityMetricsEvent[] prepareEventsToSendIfReady() {
            ConnectivityMetricsEvent[] eventsToSend = null;
            final long currentTimeMillis = System.currentTimeMillis();
            final long timeOffset = currentTimeMillis - mLastSentEventTimeMillis;
            if (timeOffset >= MAX_TIME_OFFSET
                    || timeOffset < 0 // system time has changed
                    || mEvents.size() >= MAX_NUMBER_OF_EVENTS) {
                // batch events
                mLastSentEventTimeMillis = currentTimeMillis;
                eventsToSend = new ConnectivityMetricsEvent[mEvents.size()];
                mEvents.toArray(eventsToSend);
                mEvents.clear();
            }
            return eventsToSend;
        }

        private void maybeSendEventsToSubscribers(ConnectivityMetricsEvent[] eventsToSend) {
            if (eventsToSend == null || eventsToSend.length == 0) return;
            synchronized (mSubscribers) {
                for (IConnectivityMetricsLoggerSubscriber s : mSubscribers.keySet()) {
                    try {
                        s.onEvents(eventsToSend);
                    } catch (RemoteException ex) {
                        Log.e(TAG, "RemoteException " + ex);
                    }
                }
            }
        }

        public void logEvent(ConnectivityMetricsEvent event) {
            ConnectivityMetricsEvent[] events = new ConnectivityMetricsEvent[]{event};
            logEvents(events);
        }

        public void logEvents(ConnectivityMetricsEvent[] events) {
            enforceConnectivityInternalPermission();
            ConnectivityMetricsEvent[] eventsToSend;

            if (VDBG) {
                for (ConnectivityMetricsEvent e : events) {
                    Log.v(TAG, "writeEvent(" + e.toString() + ")");
                }
            }

            synchronized (mEvents) {
                for (ConnectivityMetricsEvent e : events) {
                    mEvents.add(e);
                }

                eventsToSend = prepareEventsToSendIfReady();
            }

            maybeSendEventsToSubscribers(eventsToSend);
        }

        public boolean subscribe(IConnectivityMetricsLoggerSubscriber subscriber) {
            enforceConnectivityInternalPermission();
            if (VDBG) Log.v(TAG, "subscribe");

            synchronized (mSubscribers) {
                if (mSubscribers.containsKey(subscriber)) {
                    Log.e(TAG, "subscriber is already subscribed");
                    return false;
                }
                final IConnectivityMetricsLoggerSubscriber s = subscriber;
                IBinder.DeathRecipient dr = new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        if (VDBG) Log.v(TAG, "subscriber died");
                        synchronized (mSubscribers) {
                            mSubscribers.remove(s);
                        }
                    }
                };

                try {
                    subscriber.asBinder().linkToDeath(dr, 0);
                    mSubscribers.put(subscriber, dr);
                } catch (RemoteException e) {
                    Log.e(TAG, "subscribe failed: " + e);
                    return false;
                }
            }

            return true;
        }

        public void unsubscribe(IConnectivityMetricsLoggerSubscriber subscriber) {
            enforceConnectivityInternalPermission();
            if (VDBG) Log.v(TAG, "unsubscribe");
            synchronized (mSubscribers) {
                IBinder.DeathRecipient dr = mSubscribers.remove(subscriber);
                if (dr == null) {
                    Log.e(TAG, "subscriber is not subscribed");
                    return;
                }
                subscriber.asBinder().unlinkToDeath(dr, 0);
            }
        }
    };
}
