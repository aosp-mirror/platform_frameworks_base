/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware.location;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class that represents an Activity Recognition Event.
 *
 * @hide
 */
public class ActivityRecognitionEvent implements Parcelable {
    private final String mActivity;
    private final int mEventType;
    private final long mTimestampNs;

    public ActivityRecognitionEvent(String activity, int eventType, long timestampNs) {
        mActivity = activity;
        mEventType = eventType;
        mTimestampNs = timestampNs;
    }

    public String getActivity() {
        return mActivity;
    }

    public int getEventType() {
        return mEventType;
    }

    public long getTimestampNs() {
        return mTimestampNs;
    }

    public static final @android.annotation.NonNull Creator<ActivityRecognitionEvent> CREATOR =
            new Creator<ActivityRecognitionEvent>() {
        @Override
        public ActivityRecognitionEvent createFromParcel(Parcel source) {
            String activity = source.readString();
            int eventType = source.readInt();
            long timestampNs = source.readLong();

            return new ActivityRecognitionEvent(activity, eventType, timestampNs);
        }

        @Override
        public ActivityRecognitionEvent[] newArray(int size) {
            return new ActivityRecognitionEvent[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mActivity);
        parcel.writeInt(mEventType);
        parcel.writeLong(mTimestampNs);
    }

    @Override
    public String toString() {
        return String.format(
                "Activity='%s', EventType=%s, TimestampNs=%s",
                mActivity,
                mEventType,
                mTimestampNs);
    }
}
