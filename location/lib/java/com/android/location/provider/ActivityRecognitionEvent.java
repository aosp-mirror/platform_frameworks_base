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

package com.android.location.provider;

/**
 * A class that represents an Activity Recognition Event.
 */
public class ActivityRecognitionEvent {
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

    @Override
    public String toString() {
        String eventString;
        switch (mEventType) {
            case ActivityRecognitionProvider.EVENT_TYPE_ENTER:
                eventString = "Enter";
                break;
            case ActivityRecognitionProvider.EVENT_TYPE_EXIT:
                eventString = "Exit";
                break;
            case ActivityRecognitionProvider.EVENT_TYPE_FLUSH_COMPLETE:
                eventString = "FlushComplete";
                break;
            default:
                eventString = "<Invalid>";
                break;
        }

        return String.format(
                "Activity='%s', EventType=%s(%s), TimestampNs=%s",
                mActivity,
                eventString,
                mEventType,
                mTimestampNs);
    }
}
