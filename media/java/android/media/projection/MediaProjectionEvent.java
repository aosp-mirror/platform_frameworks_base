/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.media.projection;

import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/** @hide */
public final class MediaProjectionEvent implements Parcelable {

    /**
     * Represents various media projection events.
     */
    @IntDef({PROJECTION_STARTED_DURING_CALL_AND_ACTIVE_POST_CALL})
    @Retention(RetentionPolicy.SOURCE)
    public @interface EventType {}

    /** Event type for when a call ends but the session is still active. */
    public static final int PROJECTION_STARTED_DURING_CALL_AND_ACTIVE_POST_CALL = 0;

    private final @EventType int mEventType;
    private final long mTimestampMillis;

    public MediaProjectionEvent(@EventType int eventType, long timestampMillis) {
        mEventType = eventType;
        mTimestampMillis = timestampMillis;
    }

    private MediaProjectionEvent(Parcel in) {
        mEventType = in.readInt();
        mTimestampMillis = in.readLong();
    }

    public @EventType int getEventType() {
        return mEventType;
    }

    public long getTimestampMillis() {
        return mTimestampMillis;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof MediaProjectionEvent other) {
            return mEventType == other.mEventType && mTimestampMillis == other.mTimestampMillis;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mEventType, mTimestampMillis);
    }

    @Override
    public String toString() {
        return "MediaProjectionEvent{mEventType=" + mEventType + ", mTimestampMillis="
                + mTimestampMillis + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mEventType);
        out.writeLong(mTimestampMillis);
    }

    public static final Parcelable.Creator<MediaProjectionEvent> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public MediaProjectionEvent createFromParcel(Parcel in) {
                    return new MediaProjectionEvent(in);
                }

                @Override
                public MediaProjectionEvent[] newArray(int size) {
                    return new MediaProjectionEvent[size];
                }
            };
}
