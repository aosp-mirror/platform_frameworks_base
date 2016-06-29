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

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;

import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityMetricsEvent;
import android.net.ConnectivityMetricsLogger;
import android.net.IConnectivityMetricsLogger;
import android.os.Binder;
import android.os.Parcel;
import android.text.format.DateUtils;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;

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
        resetThrottlingCounters(System.currentTimeMillis());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            if (DBG) Log.d(TAG, "onBootPhase: PHASE_SYSTEM_SERVICES_READY");
            publishBinderService(ConnectivityMetricsLogger.CONNECTIVITY_METRICS_LOGGER_SERVICE,
                    mBinder);
            mDnsListener = new DnsEventListenerService(getContext());
            publishBinderService(mDnsListener.SERVICE_NAME, mDnsListener);
        }
    }

    // TODO: read these constants from system property
    private final int EVENTS_NOTIFICATION_THRESHOLD                   = 300;
    private final int MAX_NUMBER_OF_EVENTS                            = 1000;
    private final int THROTTLING_MAX_NUMBER_OF_MESSAGES_PER_COMPONENT = 1000;
    private final long THROTTLING_TIME_INTERVAL_MILLIS                = DateUtils.HOUR_IN_MILLIS;

    private int mEventCounter = 0;

    /**
     * Reference of the last event in the list of cached events.
     *
     * When client of this service retrieves events by calling getEvents, it is passing
     * ConnectivityMetricsEvent.Reference object. After getEvents returns, that object will
     * contain this reference. The client can save it and use next time it calls getEvents.
     * This way only new events will be returned.
     */
    private long mLastEventReference = 0;

    private final int mThrottlingCounters[] =
            new int[ConnectivityMetricsLogger.NUMBER_OF_COMPONENTS];

    private long mThrottlingIntervalBoundaryMillis;

    private final ArrayDeque<ConnectivityMetricsEvent> mEvents = new ArrayDeque<>();

    private DnsEventListenerService mDnsListener;

    private void enforceConnectivityInternalPermission() {
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.CONNECTIVITY_INTERNAL,
                "MetricsLoggerService");
    }

    private void enforceDumpPermission() {
        getContext().enforceCallingOrSelfPermission(
                android.Manifest.permission.DUMP,
                "MetricsLoggerService");
    }

    private void resetThrottlingCounters(long currentTimeMillis) {
        synchronized (mThrottlingCounters) {
            for (int i = 0; i < mThrottlingCounters.length; i++) {
                mThrottlingCounters[i] = 0;
            }
            mThrottlingIntervalBoundaryMillis =
                    currentTimeMillis + THROTTLING_TIME_INTERVAL_MILLIS;
        }
    }

    private void addEvent(ConnectivityMetricsEvent e) {
        if (VDBG) {
            Log.v(TAG, "writeEvent(" + e.toString() + ")");
        }

        while (mEvents.size() >= MAX_NUMBER_OF_EVENTS) {
            mEvents.removeFirst();
        }

        mEvents.addLast(e);
    }

    @VisibleForTesting
    final MetricsLoggerImpl mBinder = new MetricsLoggerImpl();

    /**
     * Implementation of the IConnectivityMetricsLogger interface.
     */
    final class MetricsLoggerImpl extends IConnectivityMetricsLogger.Stub {

        private final ArrayList<PendingIntent> mPendingIntents = new ArrayList<>();

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (getContext().checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump ConnectivityMetricsLoggerService " +
                        "from from pid=" + Binder.getCallingPid() + ", uid=" +
                        Binder.getCallingUid());
                return;
            }

            boolean dumpSerializedSize = false;
            boolean dumpEvents = false;
            boolean dumpDebugInfo = false;
            for (String arg : args) {
                switch (arg) {
                    case "--debug":
                        dumpDebugInfo = true;
                        break;

                    case "--events":
                        dumpEvents = true;
                        break;

                    case "--size":
                        dumpSerializedSize = true;
                        break;

                    case "--all":
                        dumpDebugInfo = true;
                        dumpEvents = true;
                        dumpSerializedSize = true;
                        break;
                }
            }

            synchronized (mEvents) {
                pw.println("Number of events: " + mEvents.size());
                pw.println("Counter: " + mEventCounter);
                if (mEvents.size() > 0) {
                    pw.println("Time span: " +
                            DateUtils.formatElapsedTime(
                                    (System.currentTimeMillis() - mEvents.peekFirst().timestamp)
                                            / 1000));
                }

                if (dumpSerializedSize) {
                    Parcel p = Parcel.obtain();
                    for (ConnectivityMetricsEvent e : mEvents) {
                        p.writeParcelable(e, 0);
                    }
                    pw.println("Serialized data size: " + p.dataSize());
                    p.recycle();
                }

                if (dumpEvents) {
                    pw.println();
                    pw.println("Events:");
                    for (ConnectivityMetricsEvent e : mEvents) {
                        pw.println(e.toString());
                    }
                }
            }

            if (dumpDebugInfo) {
                synchronized (mThrottlingCounters) {
                    pw.println();
                    for (int i = 0; i < ConnectivityMetricsLogger.NUMBER_OF_COMPONENTS; i++) {
                        if (mThrottlingCounters[i] > 0) {
                            pw.println("Throttling Counter #" + i + ": " + mThrottlingCounters[i]);
                        }
                    }
                    pw.println("Throttling Time Remaining: " +
                            DateUtils.formatElapsedTime(
                                    (mThrottlingIntervalBoundaryMillis - System.currentTimeMillis())
                                            / 1000));
                }
            }

            synchronized (mPendingIntents) {
                if (!mPendingIntents.isEmpty()) {
                    pw.println();
                    pw.println("Pending intents:");
                    for (PendingIntent pi : mPendingIntents) {
                        pw.println(pi.toString());
                    }
                }
            }

            pw.println();
            if (mDnsListener != null) {
                mDnsListener.dump(pw);
            }
        }

        public long logEvent(ConnectivityMetricsEvent event) {
            ConnectivityMetricsEvent[] events = new ConnectivityMetricsEvent[]{event};
            return logEvents(events);
        }

        /**
         * @param events
         *
         * Note: All events must belong to the same component.
         *
         * @return 0 on success
         *        <0 if error happened
         *        >0 timestamp after which new events will be accepted
         */
        public long logEvents(ConnectivityMetricsEvent[] events) {
            enforceConnectivityInternalPermission();

            if (events == null || events.length == 0) {
                Log.wtf(TAG, "No events passed to logEvents()");
                return -1;
            }

            int componentTag = events[0].componentTag;
            if (componentTag < 0 ||
                    componentTag >= ConnectivityMetricsLogger.NUMBER_OF_COMPONENTS) {
                Log.wtf(TAG, "Unexpected tag: " + componentTag);
                return -1;
            }

            synchronized (mThrottlingCounters) {
                long currentTimeMillis = System.currentTimeMillis();
                if (currentTimeMillis > mThrottlingIntervalBoundaryMillis) {
                    resetThrottlingCounters(currentTimeMillis);
                }

                mThrottlingCounters[componentTag] += events.length;

                if (mThrottlingCounters[componentTag] >
                        THROTTLING_MAX_NUMBER_OF_MESSAGES_PER_COMPONENT) {
                    Log.w(TAG, "Too many events from #" + componentTag +
                            ". Block until " + mThrottlingIntervalBoundaryMillis);

                    return mThrottlingIntervalBoundaryMillis;
                }
            }

            boolean sendPendingIntents = false;

            synchronized (mEvents) {
                for (ConnectivityMetricsEvent e : events) {
                    if (e.componentTag != componentTag) {
                        Log.wtf(TAG, "Unexpected tag: " + e.componentTag);
                        return -1;
                    }

                    addEvent(e);
                }

                mLastEventReference += events.length;

                mEventCounter += events.length;
                if (mEventCounter >= EVENTS_NOTIFICATION_THRESHOLD) {
                    mEventCounter = 0;
                    sendPendingIntents = true;
                }
            }

            if (sendPendingIntents) {
                synchronized (mPendingIntents) {
                    for (PendingIntent pi : mPendingIntents) {
                        if (VDBG) Log.v(TAG, "Send pending intent");
                        try {
                            pi.send(getContext(), 0, null, null, null);
                        } catch (PendingIntent.CanceledException e) {
                            Log.e(TAG, "Pending intent canceled: " + pi);
                            mPendingIntents.remove(pi);
                        }
                    }
                }
            }

            return 0;
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
            enforceDumpPermission();
            long ref = reference.getValue();
            if (VDBG) Log.v(TAG, "getEvents(" + ref + ")");

            ConnectivityMetricsEvent[] result;
            synchronized (mEvents) {
                if (ref > mLastEventReference) {
                    Log.e(TAG, "Invalid reference");
                    reference.setValue(mLastEventReference);
                    return null;
                }
                if (ref < mLastEventReference - mEvents.size()) {
                    ref = mLastEventReference - mEvents.size();
                }

                int numEventsToSkip =
                        mEvents.size() // Total number of events
                        - (int)(mLastEventReference - ref); // Number of events to return

                result = new ConnectivityMetricsEvent[mEvents.size() - numEventsToSkip];
                int i = 0;
                for (ConnectivityMetricsEvent e : mEvents) {
                    if (numEventsToSkip > 0) {
                        numEventsToSkip--;
                    } else {
                        result[i++] = e;
                    }
                }

                reference.setValue(mLastEventReference);
            }

            return result;
        }

        public boolean register(PendingIntent newEventsIntent) {
            enforceDumpPermission();
            if (VDBG) Log.v(TAG, "register(" + newEventsIntent + ")");

            synchronized (mPendingIntents) {
                if (mPendingIntents.remove(newEventsIntent)) {
                    Log.w(TAG, "Replacing registered pending intent");
                }
                mPendingIntents.add(newEventsIntent);
            }

            return true;
        }

        public void unregister(PendingIntent newEventsIntent) {
            enforceDumpPermission();
            if (VDBG) Log.v(TAG, "unregister(" + newEventsIntent + ")");

            synchronized (mPendingIntents) {
                if (!mPendingIntents.remove(newEventsIntent)) {
                    Log.e(TAG, "Pending intent is not registered");
                }
            }
        }
    };
}
