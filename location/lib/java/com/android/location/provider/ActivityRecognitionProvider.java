/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.location.provider;

import com.android.internal.util.Preconditions;

import android.hardware.location.IActivityRecognitionHardware;
import android.hardware.location.IActivityRecognitionHardwareSink;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

/**
 * A class that exposes {@link IActivityRecognitionHardware} functionality to unbundled services.
 */
public final class ActivityRecognitionProvider {
    private final IActivityRecognitionHardware mService;
    private final HashSet<Sink> mSinkSet = new HashSet<>();

    // the following constants must remain in sync with activity_recognition.h

    public static final String ACTIVITY_IN_VEHICLE = "android.activity_recognition.in_vehicle";
    public static final String ACTIVITY_ON_BICYCLE = "android.activity_recognition.on_bicycle";
    public static final String ACTIVITY_WALKING = "android.activity_recognition.walking";
    public static final String ACTIVITY_RUNNING = "android.activity_recognition.running";
    public static final String ACTIVITY_STILL = "android.activity_recognition.still";
    public static final String ACTIVITY_TILTING = "android.activity_recognition.tilting";

    // NOTE: when adding an additional EVENT_TYPE_, EVENT_TYPE_COUNT needs to be updated in
    // android.hardware.location.ActivityRecognitionHardware
    public static final int EVENT_TYPE_FLUSH_COMPLETE = 0;
    public static final int EVENT_TYPE_ENTER = 1;
    public static final int EVENT_TYPE_EXIT = 2;

    // end constants activity_recognition.h

    /**
     * Used to receive Activity-Recognition events.
     */
    public interface Sink {
        void onActivityChanged(ActivityChangedEvent event);
    }

    public ActivityRecognitionProvider(IActivityRecognitionHardware service)
            throws RemoteException {
        Preconditions.checkNotNull(service);
        mService = service;
        mService.registerSink(new SinkTransport());
    }

    public String[] getSupportedActivities() throws RemoteException {
        return mService.getSupportedActivities();
    }

    public boolean isActivitySupported(String activity) throws RemoteException {
        return mService.isActivitySupported(activity);
    }

    public void registerSink(Sink sink) {
        Preconditions.checkNotNull(sink);
        synchronized (mSinkSet) {
            mSinkSet.add(sink);
        }
    }

    // TODO: if this functionality is exposed to 3rd party developers, handle unregistration (here
    // and in the service) of all sinks while failing to disable all events
    public void unregisterSink(Sink sink) {
        Preconditions.checkNotNull(sink);
        synchronized (mSinkSet) {
            mSinkSet.remove(sink);
        }
    }

    public boolean enableActivityEvent(String activity, int eventType, long reportLatencyNs)
            throws RemoteException {
        return mService.enableActivityEvent(activity, eventType, reportLatencyNs);
    }

    public boolean disableActivityEvent(String activity, int eventType) throws RemoteException {
        return mService.disableActivityEvent(activity, eventType);
    }

    public boolean flush() throws RemoteException {
        return mService.flush();
    }

    private final class SinkTransport extends IActivityRecognitionHardwareSink.Stub {
        @Override
        public void onActivityChanged(android.hardware.location.ActivityChangedEvent event) {
            Collection<Sink> sinks;
            synchronized (mSinkSet) {
                if (mSinkSet.isEmpty()) {
                    return;
                }
                sinks = new ArrayList<>(mSinkSet);
            }

            // translate the event from platform internal and GmsCore types
            ArrayList<ActivityRecognitionEvent> gmsEvents = new ArrayList<>();
            for (android.hardware.location.ActivityRecognitionEvent reportingEvent
                    : event.getActivityRecognitionEvents()) {
                ActivityRecognitionEvent gmsEvent = new ActivityRecognitionEvent(
                        reportingEvent.getActivity(),
                        reportingEvent.getEventType(),
                        reportingEvent.getTimestampNs());
                gmsEvents.add(gmsEvent);
            }
            ActivityChangedEvent gmsEvent = new ActivityChangedEvent(gmsEvents);

            for (Sink sink : sinks) {
                sink.onActivityChanged(gmsEvent);
            }
        }
    }
}
