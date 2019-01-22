/*
 * Copyright (C) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/license/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware.location;

import android.hardware.location.IActivityRecognitionHardwareSink;

/**
 * Activity Recognition Hardware provider interface.
 * This interface can be used to implement hardware based activity recognition.
 *
 * @hide
 */
interface IActivityRecognitionHardware {
    /**
     * Gets an array of supported activities by hardware.
     */
    String[] getSupportedActivities();

    /**
     * Returns true if the given activity is supported, false otherwise.
     */
    boolean isActivitySupported(in String activityType);

    /**
     * Registers a sink with Hardware Activity-Recognition.
     */
    boolean registerSink(in IActivityRecognitionHardwareSink sink);

    /**
     * Unregisters a sink with Hardware Activity-Recognition.
     */
    boolean unregisterSink(in IActivityRecognitionHardwareSink sink);

    /**
     * Enables tracking of a given activity/event type, if the activity is supported.
     */
    boolean enableActivityEvent(in String activityType, int eventType, long reportLatencyNs);

    /**
     * Disables tracking of a given activity/eventy type.
     */
    boolean disableActivityEvent(in String activityType, int eventType);

    /**
     * Requests hardware for all the activity events detected up to the given point in time.
     */
    boolean flush();
}