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

package android.hardware.input;

import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.companion.virtualdevice.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.view.InputEvent;

import com.android.internal.util.Preconditions;

/**
 * An event describing a rotary encoder scroll interaction originating from a remote device.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_VIRTUAL_ROTARY)
@SystemApi
public final class VirtualRotaryEncoderScrollEvent implements Parcelable {

    private final float mScrollAmount;
    private final long mEventTimeNanos;

    private VirtualRotaryEncoderScrollEvent(float scrollAmount, long eventTimeNanos) {
        mScrollAmount = scrollAmount;
        mEventTimeNanos = eventTimeNanos;
    }

    private VirtualRotaryEncoderScrollEvent(@NonNull Parcel parcel) {
        mScrollAmount = parcel.readFloat();
        mEventTimeNanos = parcel.readLong();
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int parcelableFlags) {
        parcel.writeFloat(mScrollAmount);
        parcel.writeLong(mEventTimeNanos);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "VirtualRotaryScrollEvent("
                + " scrollAmount=" + mScrollAmount
                + " eventTime(ns)=" + mEventTimeNanos;
    }

    /**
     * Returns the scroll amount, normalized from -1.0 to 1.0, inclusive. Positive values
     * indicate scrolling forward (e.g. down in a vertical list); negative values, backward.
     */
    public @FloatRange(from = -1.0f, to = 1.0f) float getScrollAmount() {
        return mScrollAmount;
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
     * Builder for {@link VirtualRotaryEncoderScrollEvent}.
     */
    public static final class Builder {

        private float mScrollAmount;
        private long mEventTimeNanos = 0L;

        /**
         * Creates a {@link VirtualRotaryEncoderScrollEvent} object with the current configuration.
         */
        public @NonNull VirtualRotaryEncoderScrollEvent build() {
            return new VirtualRotaryEncoderScrollEvent(mScrollAmount, mEventTimeNanos);
        }

        /**
         * Sets the scroll amount, normalized from -1.0 to 1.0, inclusive. Positive values
         * indicate scrolling forward (e.g. down in a vertical list); negative values, backward.
         *
         * @return this builder, to allow for chaining of calls
         */
        public @NonNull Builder setScrollAmount(
                @FloatRange(from = -1.0f, to = 1.0f) float scrollAmount) {
            Preconditions.checkArgumentInRange(scrollAmount, -1f, 1f, "scrollAmount");
            mScrollAmount = scrollAmount;
            return this;
        }

        /**
         * Sets the time (in nanoseconds) when this specific event was generated. This may be
         * obtained from {@link SystemClock#uptimeMillis()} (with nanosecond precision instead of
         * millisecond), but can be different depending on the use case.
         * This field is optional and can be omitted.
         *
         * @return this builder, to allow for chaining of calls
         * @see InputEvent#getEventTime()
         */
        public @NonNull Builder setEventTimeNanos(long eventTimeNanos) {
            if (eventTimeNanos < 0L) {
                throw new IllegalArgumentException("Event time cannot be negative");
            }
            mEventTimeNanos = eventTimeNanos;
            return this;
        }
    }

    public static final @NonNull Creator<VirtualRotaryEncoderScrollEvent> CREATOR =
            new Creator<VirtualRotaryEncoderScrollEvent>() {
                public VirtualRotaryEncoderScrollEvent createFromParcel(Parcel source) {
                    return new VirtualRotaryEncoderScrollEvent(source);
                }

                public VirtualRotaryEncoderScrollEvent[] newArray(int size) {
                    return new VirtualRotaryEncoderScrollEvent[size];
                }
            };
}
