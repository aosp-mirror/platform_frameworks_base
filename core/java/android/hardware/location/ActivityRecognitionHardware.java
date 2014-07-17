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

package android.hardware.location;

import android.Manifest;
import android.content.Context;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

/**
 * A class that implements an {@link IActivityRecognitionHardware} backed up by the Activity
 * Recognition HAL.
 *
 * @hide
 */
public class ActivityRecognitionHardware extends IActivityRecognitionHardware.Stub {
    private static final String TAG = "ActivityRecognitionHardware";

    private static final String HARDWARE_PERMISSION = Manifest.permission.LOCATION_HARDWARE;
    private static final int INVALID_ACTIVITY_TYPE = -1;
    private static final int NATIVE_SUCCESS_RESULT = 0;

    private static ActivityRecognitionHardware sSingletonInstance = null;
    private static final Object sSingletonInstanceLock = new Object();

    private final Context mContext;
    private final String[] mSupportedActivities;

    private final RemoteCallbackList<IActivityRecognitionHardwareSink> mSinks =
            new RemoteCallbackList<IActivityRecognitionHardwareSink>();

    private static class Event {
        public int activity;
        public int type;
        public long timestamp;
    }

    private ActivityRecognitionHardware(Context context) {
        nativeInitialize();

        mContext = context;
        mSupportedActivities = fetchSupportedActivities();
    }

    public static ActivityRecognitionHardware getInstance(Context context) {
        synchronized (sSingletonInstanceLock) {
            if (sSingletonInstance == null) {
                sSingletonInstance = new ActivityRecognitionHardware(context);
            }

            return sSingletonInstance;
        }
    }

    public static boolean isSupported() {
        return nativeIsSupported();
    }

    @Override
    public String[] getSupportedActivities() {
        checkPermissions();
        return mSupportedActivities;
    }

    @Override
    public boolean isActivitySupported(String activity) {
        checkPermissions();
        int activityType = getActivityType(activity);
        return activityType != INVALID_ACTIVITY_TYPE;
    }

    @Override
    public boolean registerSink(IActivityRecognitionHardwareSink sink) {
        checkPermissions();
        return mSinks.register(sink);
    }

    @Override
    public boolean unregisterSink(IActivityRecognitionHardwareSink sink) {
        checkPermissions();
        return mSinks.unregister(sink);
    }

    @Override
    public boolean enableActivityEvent(String activity, int eventType, long reportLatencyNs) {
        checkPermissions();

        int activityType = getActivityType(activity);
        if (activityType == INVALID_ACTIVITY_TYPE) {
            return false;
        }

        int result = nativeEnableActivityEvent(activityType, eventType, reportLatencyNs);
        return result == NATIVE_SUCCESS_RESULT;
    }

    @Override
    public boolean disableActivityEvent(String activity, int eventType) {
        checkPermissions();

        int activityType = getActivityType(activity);
        if (activityType == INVALID_ACTIVITY_TYPE) {
            return false;
        }

        int result = nativeDisableActivityEvent(activityType, eventType);
        return result == NATIVE_SUCCESS_RESULT;
    }

    @Override
    public boolean flush() {
        checkPermissions();
        int result = nativeFlush();
        return result == NATIVE_SUCCESS_RESULT;
    }

    /**
     * Called by the Activity-Recognition HAL.
     */
    private void onActivityChanged(Event[] events) {
        if (events == null || events.length == 0) {
            Log.d(TAG, "No events to broadcast for onActivityChanged.");
            return;
        }

        int eventsLength = events.length;
        ActivityRecognitionEvent activityRecognitionEventArray[] =
                new ActivityRecognitionEvent[eventsLength];
        for (int i = 0; i < eventsLength; ++i) {
            Event event = events[i];
            String activityName = getActivityName(event.activity);
            activityRecognitionEventArray[i] =
                    new ActivityRecognitionEvent(activityName, event.type, event.timestamp);
        }
        ActivityChangedEvent activityChangedEvent =
                new ActivityChangedEvent(activityRecognitionEventArray);

        int size = mSinks.beginBroadcast();
        for (int i = 0; i < size; ++i) {
            IActivityRecognitionHardwareSink sink = mSinks.getBroadcastItem(i);
            try {
                sink.onActivityChanged(activityChangedEvent);
            } catch (RemoteException e) {
                Log.e(TAG, "Error delivering activity changed event.", e);
            }
        }
        mSinks.finishBroadcast();

    }

    private String getActivityName(int activityType) {
        if (activityType < 0 || activityType >= mSupportedActivities.length) {
            String message = String.format(
                    "Invalid ActivityType: %d, SupportedActivities: %d",
                    activityType,
                    mSupportedActivities.length);
            Log.e(TAG, message);
            return null;
        }

        return mSupportedActivities[activityType];
    }

    private int getActivityType(String activity) {
        if (TextUtils.isEmpty(activity)) {
            return INVALID_ACTIVITY_TYPE;
        }

        int supportedActivitiesLength = mSupportedActivities.length;
        for (int i = 0; i < supportedActivitiesLength; ++i) {
            if (activity.equals(mSupportedActivities[i])) {
                return i;
            }
        }

        return INVALID_ACTIVITY_TYPE;
    }

    private void checkPermissions() {
        String message = String.format(
                "Permission '%s' not granted to access ActivityRecognitionHardware",
                HARDWARE_PERMISSION);
        mContext.enforceCallingPermission(HARDWARE_PERMISSION, message);
    }

    private String[] fetchSupportedActivities() {
        String[] supportedActivities = nativeGetSupportedActivities();
        if (supportedActivities != null) {
            return supportedActivities;
        }

        return new String[0];
    }

    // native bindings
    static { nativeClassInit(); }

    private static native void nativeClassInit();
    private static native boolean nativeIsSupported();

    private native void nativeInitialize();
    private native void nativeRelease();
    private native String[] nativeGetSupportedActivities();
    private native int nativeEnableActivityEvent(
            int activityType,
            int eventType,
            long reportLatenceNs);
    private native int nativeDisableActivityEvent(int activityType, int eventType);
    private native int nativeFlush();
}
