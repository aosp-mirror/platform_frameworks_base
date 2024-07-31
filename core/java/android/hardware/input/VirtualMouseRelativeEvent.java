/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.hardware.input;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.view.InputEvent;

/**
 * An event describing a mouse movement interaction originating from a remote device.
 *
 * See {@link android.view.MotionEvent}.
 *
 * @hide
 */
@SystemApi
public final class VirtualMouseRelativeEvent implements Parcelable {

    private final float mRelativeX;
    private final float mRelativeY;
    private final long mEventTimeNanos;

    private VirtualMouseRelativeEvent(float relativeX, float relativeY, long eventTimeNanos) {
        mRelativeX = relativeX;
        mRelativeY = relativeY;
        mEventTimeNanos = eventTimeNanos;
    }

    private VirtualMouseRelativeEvent(@NonNull Parcel parcel) {
        mRelativeX = parcel.readFloat();
        mRelativeY = parcel.readFloat();
        mEventTimeNanos = parcel.readLong();
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int parcelableFlags) {
        parcel.writeFloat(mRelativeX);
        parcel.writeFloat(mRelativeY);
        parcel.writeLong(mEventTimeNanos);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "VirtualMouseRelativeEvent("
                + " x=" + mRelativeX
                + " y=" + mRelativeY
                + " eventTime(ns)=" + mEventTimeNanos;
    }

    /**
     * Returns the relative x-axis movement, in pixels.
     */
    public float getRelativeX() {
        return mRelativeX;
    }

    /**
     * Returns the relative x-axis movement, in pixels.
     */
    public float getRelativeY() {
        return mRelativeY;
    }

    /**
     * Returns the time this event occurred, in the {@link SystemClock#uptimeMillis()} time base but
     * with nanosecond (instead of millisecond) precision.
     *
     * @see InputEvent#getEventTime()
     */
    public long getEventTimeNanos() {
        return mEventTimeNanos;
    }

    /**
     * Builder for {@link VirtualMouseRelativeEvent}.
     */
    public static final class Builder {

        private float mRelativeX;
        private float mRelativeY;
        private long mEventTimeNanos = 0L;

        /**
         * Creates a {@link VirtualMouseRelativeEvent} object with the current builder
         * configuration.
         */
        public @NonNull VirtualMouseRelativeEvent build() {
            return new VirtualMouseRelativeEvent(mRelativeX, mRelativeY, mEventTimeNanos);
        }

        /**
         * Sets the relative x-axis movement, in pixels.
         *
         * @return this builder, to allow for chaining of calls
         */
        public @NonNull Builder setRelativeX(float relativeX) {
            mRelativeX = relativeX;
            return this;
        }

        /**
         * Sets the relative y-axis movement, in pixels.
         *
         * @return this builder, to allow for chaining of calls
         */
        public @NonNull Builder setRelativeY(float relativeY) {
            mRelativeY = relativeY;
            return this;
        }

        /**
         * Sets the time (in nanoseconds) when this specific event was generated. This may be
         * obtained from {@link SystemClock#uptimeMillis()} (with nanosecond precision instead of
         * millisecond), but can be different depending on the use case.
         * This field is optional and can be omitted.
         * <p>
         * If this field is unset, then the time at which this event is sent to the framework would
         * be considered as the event time (even though
         * {@link VirtualMouseRelativeEvent#getEventTimeNanos()}) would return {@code 0L}).
         *
         * @return this builder, to allow for chaining of calls
         * @see InputEvent#getEventTime()
         */
        public @NonNull Builder setEventTimeNanos(long eventTimeNanos) {
            if (eventTimeNanos < 0L) {
                throw new IllegalArgumentException("Event time cannot be negative");
            }
            this.mEventTimeNanos = eventTimeNanos;
            return this;
        }
    }

    public static final @NonNull Parcelable.Creator<VirtualMouseRelativeEvent> CREATOR =
            new Parcelable.Creator<VirtualMouseRelativeEvent>() {
                public VirtualMouseRelativeEvent createFromParcel(Parcel source) {
                    return new VirtualMouseRelativeEvent(source);
                }

                public VirtualMouseRelativeEvent[] newArray(int size) {
                    return new VirtualMouseRelativeEvent[size];
                }
            };
}
