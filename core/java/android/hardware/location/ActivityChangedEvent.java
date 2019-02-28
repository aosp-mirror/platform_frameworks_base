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

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.security.InvalidParameterException;
import java.util.Arrays;
import java.util.List;

/**
 * A class representing an event for Activity changes.
 *
 * @hide
 */
public class ActivityChangedEvent implements Parcelable {
    private final List<ActivityRecognitionEvent> mActivityRecognitionEvents;

    public ActivityChangedEvent(ActivityRecognitionEvent[] activityRecognitionEvents) {
        if (activityRecognitionEvents == null) {
            throw new InvalidParameterException(
                    "Parameter 'activityRecognitionEvents' must not be null.");
        }

        mActivityRecognitionEvents = Arrays.asList(activityRecognitionEvents);
    }

    @NonNull
    public Iterable<ActivityRecognitionEvent> getActivityRecognitionEvents() {
        return mActivityRecognitionEvents;
    }

    public static final @android.annotation.NonNull Creator<ActivityChangedEvent> CREATOR =
            new Creator<ActivityChangedEvent>() {
        @Override
        public ActivityChangedEvent createFromParcel(Parcel source) {
            int activityRecognitionEventsLength = source.readInt();
            ActivityRecognitionEvent[] activityRecognitionEvents =
                    new ActivityRecognitionEvent[activityRecognitionEventsLength];
            source.readTypedArray(activityRecognitionEvents, ActivityRecognitionEvent.CREATOR);

            return new ActivityChangedEvent(activityRecognitionEvents);
        }

        @Override
        public ActivityChangedEvent[] newArray(int size) {
            return new ActivityChangedEvent[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        ActivityRecognitionEvent[] activityRecognitionEventArray =
                mActivityRecognitionEvents.toArray(new ActivityRecognitionEvent[0]);
        parcel.writeInt(activityRecognitionEventArray.length);
        parcel.writeTypedArray(activityRecognitionEventArray, flags);
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
