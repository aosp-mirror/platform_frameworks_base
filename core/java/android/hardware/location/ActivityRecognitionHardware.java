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
    private static final String TAG = "ActivityRecognitionHW";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String HARDWARE_PERMISSION = Manifest.permission.LOCATION_HARDWARE;
    private static final String ENFORCE_HW_PERMISSION_MESSAGE = "Permission '"
            + HARDWARE_PERMISSION + "' not granted to access ActivityRecognitionHardware";

    private static final int INVALID_ACTIVITY_TYPE = -1;
    private static final int NATIVE_SUCCESS_RESULT = 0;
    private static final int EVENT_TYPE_DISABLED = 0;
    private static final int EVENT_TYPE_ENABLED = 1;

    /**
     * Contains the number of supported Event Types.
     *
     * NOTE: increment this counter every time a new EVENT_TYPE_ is added to
     *       com.android.location.provider.ActivityRecognitionProvider
     */
    private static final int EVENT_TYPE_COUNT = 3;

    private static ActivityRecognitionHardware sSingletonInstance;
    private static final Object sSingletonInstanceLock = new Object();

    private final Context mContext;
    private final int mSupportedActivitiesCount;
    private final String[] mSupportedActivities;
    private final int[][] mSupportedActivitiesEnabledEvents;
    private final SinkList mSinks = new SinkList();

    private static class Event {
        public int activity;
        public int type;
        public long timestamp;
    }

    private ActivityRecognitionHardware(Context context) {
        nativeInitialize();

        mContext = context;
        mSupportedActivities = fetchSupportedActivities();
        mSupportedActivitiesCount = mSupportedActivities.length;
        mSupportedActivitiesEnabledEvents = new int[mSupportedActivitiesCount][EVENT_TYPE_COUNT];
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
        if (result == NATIVE_SUCCESS_RESULT) {
            mSupportedActivitiesEnabledEvents[activityType][eventType] = EVENT_TYPE_ENABLED;
            return true;
        }
        return false;
    }

    @Override
    public boolean disableActivityEvent(String activity, int eventType) {
        checkPermissions();

        int activityType = getActivityType(activity);
        if (activityType == INVALID_ACTIVITY_TYPE) {
            return false;
        }

        int result = nativeDisableActivityEvent(activityType, eventType);
        if (result == NATIVE_SUCCESS_RESULT) {
            mSupportedActivitiesEnabledEvents[activityType][eventType] = EVENT_TYPE_DISABLED;
            return true;
        }
        return false;
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
            if (DEBUG) Log.d(TAG, "No events to broadcast for onActivityChanged.");
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
        mContext.enforceCallingPermission(HARDWARE_PERMISSION, ENFORCE_HW_PERMISSION_MESSAGE);
    }

    private String[] fetchSupportedActivities() {
        String[] supportedActivities = nativeGetSupportedActivities();
        if (supportedActivities != null) {
            return supportedActivities;
        }

        return new String[0];
    }

    private class SinkList extends RemoteCallbackList<IActivityRecognitionHardwareSink> {
        @Override
        public void onCallbackDied(IActivityRecognitionHardwareSink callback) {
            int callbackCount = mSinks.getRegisteredCallbackCount();
            if (DEBUG) Log.d(TAG, "RegisteredCallbackCount: " + callbackCount);
            if (callbackCount != 0) {
                return;
            }
            // currently there is only one client for this, so if all its sinks have died, we clean
            // up after them, this ensures that the AR HAL is not out of sink
            for (int activity = 0; activity < mSupportedActivitiesCount; ++activity) {
                for (int event = 0; event < EVENT_TYPE_COUNT; ++event) {
                    disableActivityEventIfEnabled(activity, event);
                }
            }
        }

        private void disableActivityEventIfEnabled(int activityType, int eventType) {
            if (mSupportedActivitiesEnabledEvents[activityType][eventType] != EVENT_TYPE_ENABLED) {
                return;
            }

            int result = nativeDisableActivityEvent(activityType, eventType);
            mSupportedActivitiesEnabledEvents[activityType][eventType] = EVENT_TYPE_DISABLED;
            String message = String.format(
                    "DisableActivityEvent: activityType=%d, eventType=%d, result=%d",
                    activityType,
                    eventType,
                    result);
            Log.e(TAG, message);
        }
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
