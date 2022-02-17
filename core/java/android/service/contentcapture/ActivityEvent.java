/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.service.contentcapture;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.usage.UsageEvents.Event;
import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents an activity-level event that is not associated with a session.
 *
 * @hide
 */
@SystemApi
public final class ActivityEvent implements Parcelable {

    /**
     * The activity resumed.
     */
    public static final int TYPE_ACTIVITY_RESUMED = Event.ACTIVITY_RESUMED;

    /**
     * The activity paused.
     */
    public static final int TYPE_ACTIVITY_PAUSED = Event.ACTIVITY_PAUSED;

    /**
     * The activity stopped.
     */
    public static final int TYPE_ACTIVITY_STOPPED = Event.ACTIVITY_STOPPED;

    /**
     * The activity was destroyed.
     */
    public static final int TYPE_ACTIVITY_DESTROYED = Event.ACTIVITY_DESTROYED;

    /**
     * TODO: change to public field.
     * The activity was started.
     *
     * <p>There are some reason, ACTIVITY_START cannot be added into UsageStats. We don't depend on
     * UsageEvents for Activity start.
     * </p>
     *
     * @hide
     */
    public static final int TYPE_ACTIVITY_STARTED = 10000;

    /** @hide */
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_ACTIVITY_RESUMED,
            TYPE_ACTIVITY_PAUSED,
            TYPE_ACTIVITY_STOPPED,
            TYPE_ACTIVITY_DESTROYED,
            TYPE_ACTIVITY_STARTED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ActivityEventType{}

    private final @NonNull ComponentName mComponentName;
    private final @ActivityEventType int mType;

    /** @hide */
    public ActivityEvent(@NonNull ComponentName componentName, @ActivityEventType int type) {
        mComponentName = componentName;
        mType = type;
    }

    /**
     * Gests the {@link ComponentName} of the activity associated with the event.
     */
    @NonNull
    public ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * Gets the event type.
     *
     * @return either {@link #TYPE_ACTIVITY_RESUMED}, {@value #TYPE_ACTIVITY_PAUSED},
     * {@value #TYPE_ACTIVITY_STOPPED}, {@value #TYPE_ACTIVITY_DESTROYED} or 10000 if the Activity
     * was started.
     */
    @ActivityEventType
    public int getEventType() {
        return mType;
    }

    /** @hide */
    public static String getTypeAsString(@ActivityEventType int type) {
        switch (type) {
            case TYPE_ACTIVITY_RESUMED:
                return "ACTIVITY_RESUMED";
            case TYPE_ACTIVITY_PAUSED:
                return "ACTIVITY_PAUSED";
            case TYPE_ACTIVITY_STOPPED:
                return "ACTIVITY_STOPPED";
            case TYPE_ACTIVITY_DESTROYED:
                return "ACTIVITY_DESTROYED";
            case TYPE_ACTIVITY_STARTED:
                return "ACTIVITY_STARTED";
            default:
                return "UKNOWN_TYPE: " + type;
        }
    }

    @NonNull
    @Override
    public String toString() {
        return new StringBuilder("ActivityEvent[").append(mComponentName.toShortString())
                .append("]:").append(getTypeAsString(mType)).toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeParcelable(mComponentName, flags);
        parcel.writeInt(mType);
    }

    public static final @android.annotation.NonNull Creator<ActivityEvent> CREATOR =
            new Creator<ActivityEvent>() {

        @Override
        @NonNull
        public ActivityEvent createFromParcel(@NonNull Parcel parcel) {
            final ComponentName componentName = parcel.readParcelable(null, android.content.ComponentName.class);
            final int eventType = parcel.readInt();
            return new ActivityEvent(componentName, eventType);
        }

        @Override
        @NonNull
        public ActivityEvent[] newArray(int size) {
            return new ActivityEvent[size];
        }
    };
}
