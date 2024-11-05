/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.quality;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * @hide
 */
public final class AmbientBacklightEvent implements Parcelable {

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({AMBIENT_BACKLIGHT_EVENT_ENABLED, AMBIENT_BACKLIGHT_EVENT_DISABLED,
            AMBIENT_BACKLIGHT_EVENT_METADATA,
            AMBIENT_BACKLIGHT_EVENT_INTERRUPTED})
    public @interface AmbientBacklightEventTypes {}

    /**
     * Event type for ambient backlight events. The ambient backlight is enabled.
     */
    public static final int AMBIENT_BACKLIGHT_EVENT_ENABLED = 1;

    /**
     * Event type for ambient backlight events. The ambient backlight is disabled.
     */
    public static final int AMBIENT_BACKLIGHT_EVENT_DISABLED = 2;

    /**
     * Event type for ambient backlight events. The ambient backlight metadata is
     * available.
     */
    public static final int AMBIENT_BACKLIGHT_EVENT_METADATA = 3;

    /**
     * Event type for ambient backlight events. The ambient backlight event is preempted by another
     * application.
     */
    public static final int AMBIENT_BACKLIGHT_EVENT_INTERRUPTED = 4;

    private final int mEventType;
    @Nullable
    private final AmbientBacklightMetadata mMetadata;

    public AmbientBacklightEvent(int eventType,
            @Nullable AmbientBacklightMetadata metadata) {
        mEventType = eventType;
        mMetadata = metadata;
    }

    private AmbientBacklightEvent(Parcel in) {
        mEventType = in.readInt();
        mMetadata = in.readParcelable(AmbientBacklightMetadata.class.getClassLoader());
    }

    public int getEventType() {
        return mEventType;
    }

    @Nullable
    public AmbientBacklightMetadata getMetadata() {
        return mMetadata;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mEventType);
        dest.writeParcelable(mMetadata, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Parcelable.Creator<AmbientBacklightEvent> CREATOR =
            new Parcelable.Creator<AmbientBacklightEvent>() {
                public AmbientBacklightEvent createFromParcel(Parcel in) {
                    return new AmbientBacklightEvent(in);
                }

                public AmbientBacklightEvent[] newArray(int size) {
                    return new AmbientBacklightEvent[size];
                }
            };

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof AmbientBacklightEvent)) {
            return false;
        }

        AmbientBacklightEvent other = (AmbientBacklightEvent) obj;
        return mEventType == other.mEventType
                && Objects.equals(mMetadata, other.mMetadata);
    }

    @Override
    public int hashCode() {
        return mEventType * 31 + (mMetadata != null ? mMetadata.hashCode() : 0);
    }

    @Override
    public String toString() {
        return "AmbientBacklightEvent{"
                + "mEventType=" + mEventType
                + ", mMetadata=" + mMetadata
                + '}';
    }
}
