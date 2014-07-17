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

import android.annotation.NonNull;

import java.security.InvalidParameterException;
import java.util.List;

/**
 * A class representing an event for Activity changes.
 */
public class ActivityChangedEvent {
    private final List<ActivityRecognitionEvent> mActivityRecognitionEvents;

    public ActivityChangedEvent(List<ActivityRecognitionEvent> activityRecognitionEvents) {
        if (activityRecognitionEvents == null) {
            throw new InvalidParameterException(
                    "Parameter 'activityRecognitionEvents' must not be null.");
        }

        mActivityRecognitionEvents = activityRecognitionEvents;
    }

    @NonNull
    public Iterable<ActivityRecognitionEvent> getActivityRecognitionEvents() {
        return mActivityRecognitionEvents;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("[ ActivityChangedEvent:");

        for (ActivityRecognitionEvent event : mActivityRecognitionEvents) {
            builder.append("\n    ");
            builder.append(event.toString());
        }
        builder.append("\n]");

        return builder.toString();
    }
}
