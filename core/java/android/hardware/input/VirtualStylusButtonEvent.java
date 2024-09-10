/*
 * Copyright 2023 The Android Open Source Project
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
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.companion.virtual.flags.Flags;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An event describing a stylus button click interaction originating from a remote device.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_VIRTUAL_STYLUS)
@SystemApi
public final class VirtualStylusButtonEvent implements Parcelable {
    /** @hide */
    public static final int ACTION_UNKNOWN = -1;
    /** Action indicating the stylus button has been pressed. */
    public static final int ACTION_BUTTON_PRESS = MotionEvent.ACTION_BUTTON_PRESS;
    /** Action indicating the stylus button has been released. */
    public static final int ACTION_BUTTON_RELEASE = MotionEvent.ACTION_BUTTON_RELEASE;
    /** @hide */
    @IntDef(prefix = {"ACTION_"}, value = {
            ACTION_UNKNOWN,
            ACTION_BUTTON_PRESS,
            ACTION_BUTTON_RELEASE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Action {}

    /** @hide */
    public static final int BUTTON_UNKNOWN = -1;
    /** Action indicating the stylus button involved in this event is primary. */
    public static final int BUTTON_PRIMARY = MotionEvent.BUTTON_STYLUS_PRIMARY;
    /** Action indicating the stylus button involved in this event is secondary. */
    public static final int BUTTON_SECONDARY = MotionEvent.BUTTON_STYLUS_SECONDARY;
    /** @hide */
    @IntDef(prefix = {"BUTTON_"}, value = {
            BUTTON_UNKNOWN,
            BUTTON_PRIMARY,
            BUTTON_SECONDARY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Button {}

    @Action
    private final int mAction;
    @Button
    private final int mButtonCode;
    private final long mEventTimeNanos;

    private VirtualStylusButtonEvent(@Action int action, @Button int buttonCode,
            long eventTimeNanos) {
        mAction = action;
        mButtonCode = buttonCode;
        mEventTimeNanos = eventTimeNanos;
    }

    private VirtualStylusButtonEvent(@NonNull Parcel parcel) {
        mAction = parcel.readInt();
        mButtonCode = parcel.readInt();
        mEventTimeNanos = parcel.readLong();
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int parcelableFlags) {
        parcel.writeInt(mAction);
        parcel.writeInt(mButtonCode);
        parcel.writeLong(mEventTimeNanos);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Returns the button code associated with this event.
     */
    @Button
    public int getButtonCode() {
        return mButtonCode;
    }

    /**
     * Returns the action associated with this event.
     */
    @Action
    public int getAction() {
        return mAction;
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
     * Builder for {@link VirtualStylusButtonEvent}.
     */
    @FlaggedApi(Flags.FLAG_VIRTUAL_STYLUS)
    public static final class Builder {

        @Action
        private int mAction = ACTION_UNKNOWN;
        @Button
        private int mButtonCode = BUTTON_UNKNOWN;
        private long mEventTimeNanos = 0L;

        /**
         * Creates a {@link VirtualStylusButtonEvent} object with the current builder configuration.
         */
        @NonNull
        public VirtualStylusButtonEvent build() {
            if (mAction == ACTION_UNKNOWN) {
                throw new IllegalArgumentException(
                        "Cannot build stylus button event with unset action");
            }
            if (mButtonCode == BUTTON_UNKNOWN) {
                throw new IllegalArgumentException(
                        "Cannot build stylus button event with unset button code");
            }
            return new VirtualStylusButtonEvent(mAction, mButtonCode, mEventTimeNanos);
        }

        /**
         * Sets the button code of the event.
         *
         * @return this builder, to allow for chaining of calls
         */
        @NonNull
        public Builder setButtonCode(@Button int buttonCode) {
            if (buttonCode != BUTTON_PRIMARY && buttonCode != BUTTON_SECONDARY) {
                throw new IllegalArgumentException(
                        "Unsupported stylus button code : " + buttonCode);
            }
            mButtonCode = buttonCode;
            return this;
        }

        /**
         * Sets the action of the event.
         *
         * @return this builder, to allow for chaining of calls
         */
        @NonNull
        public Builder setAction(@Action int action) {
            if (action != ACTION_BUTTON_PRESS && action != ACTION_BUTTON_RELEASE) {
                throw new IllegalArgumentException("Unsupported stylus button action : " + action);
            }
            mAction = action;
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
         * {@link VirtualStylusButtonEvent#getEventTimeNanos()}) would return {@code 0L}).
         *
         * @return this builder, to allow for chaining of calls
         * @see InputEvent#getEventTime()
         */
        @NonNull
        public Builder setEventTimeNanos(long eventTimeNanos) {
            if (eventTimeNanos < 0L) {
                throw new IllegalArgumentException("Event time cannot be negative");
            }
            this.mEventTimeNanos = eventTimeNanos;
            return this;
        }
    }

    @NonNull
    public static final Parcelable.Creator<VirtualStylusButtonEvent> CREATOR =
            new Parcelable.Creator<>() {
                public VirtualStylusButtonEvent createFromParcel(Parcel source) {
                    return new VirtualStylusButtonEvent(source);
                }

                public VirtualStylusButtonEvent[] newArray(int size) {
                    return new VirtualStylusButtonEvent[size];
                }
            };
}
