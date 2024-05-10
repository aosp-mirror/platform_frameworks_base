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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An event describing a mouse button click interaction originating from a remote device.
 *
 * @hide
 */
@SystemApi
public final class VirtualMouseButtonEvent implements Parcelable {

    /** @hide */
    public static final int ACTION_UNKNOWN = -1;
    /** Action indicating the mouse button has been pressed. */
    public static final int ACTION_BUTTON_PRESS = MotionEvent.ACTION_BUTTON_PRESS;
    /** Action indicating the mouse button has been released. */
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
    /** Action indicating the mouse button involved in this event is in the left position. */
    public static final int BUTTON_PRIMARY = MotionEvent.BUTTON_PRIMARY;
    /** Action indicating the mouse button involved in this event is in the middle position. */
    public static final int BUTTON_TERTIARY = MotionEvent.BUTTON_TERTIARY;
    /** Action indicating the mouse button involved in this event is in the right position. */
    public static final int BUTTON_SECONDARY = MotionEvent.BUTTON_SECONDARY;
    /**
     * Action indicating the mouse button involved in this event is intended to go back to the
     * previous.
     */
    public static final int BUTTON_BACK = MotionEvent.BUTTON_BACK;
    /**
     * Action indicating the mouse button involved in this event is intended to move forward to the
     * next.
     */
    public static final int BUTTON_FORWARD = MotionEvent.BUTTON_FORWARD;
    /** @hide */
    @IntDef(prefix = {"BUTTON_"}, value = {
            BUTTON_UNKNOWN,
            BUTTON_PRIMARY,
            BUTTON_TERTIARY,
            BUTTON_SECONDARY,
            BUTTON_BACK,
            BUTTON_FORWARD,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Button {}

    private final @Action int mAction;
    private final @Button int mButtonCode;
    private final long mEventTimeNanos;

    private VirtualMouseButtonEvent(@Action int action, @Button int buttonCode,
            long eventTimeNanos) {
        mAction = action;
        mButtonCode = buttonCode;
        mEventTimeNanos = eventTimeNanos;
    }

    private VirtualMouseButtonEvent(@NonNull Parcel parcel) {
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

    @Override
    public String toString() {
        return "VirtualMouseButtonEvent("
                + " action=" + MotionEvent.actionToString(mAction)
                + " button=" + MotionEvent.buttonStateToString(mButtonCode)
                + " eventTime(ns)=" + mEventTimeNanos;
    }

    /**
     * Returns the button code associated with this event.
     */
    public @Button int getButtonCode() {
        return mButtonCode;
    }

    /**
     * Returns the action associated with this event.
     */
    public @Action int getAction() {
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
     * Builder for {@link VirtualMouseButtonEvent}.
     */
    public static final class Builder {

        private @Action int mAction = ACTION_UNKNOWN;
        private @Button int mButtonCode = -1;
        private long mEventTimeNanos = 0L;

        /**
         * Creates a {@link VirtualMouseButtonEvent} object with the current builder configuration.
         */
        public @NonNull VirtualMouseButtonEvent build() {
            if (mAction == ACTION_UNKNOWN || mButtonCode == -1) {
                throw new IllegalArgumentException(
                        "Cannot build virtual mouse button event with unset fields");
            }
            return new VirtualMouseButtonEvent(mAction, mButtonCode, mEventTimeNanos);
        }

        /**
         * Sets the button code of the event.
         *
         * @return this builder, to allow for chaining of calls
         */
        public @NonNull Builder setButtonCode(int buttonCode) {
            if (buttonCode != BUTTON_PRIMARY
                    && buttonCode != BUTTON_TERTIARY
                    && buttonCode != BUTTON_SECONDARY
                    && buttonCode != BUTTON_BACK
                    && buttonCode != BUTTON_FORWARD) {
                throw new IllegalArgumentException("Unsupported mouse button code");
            }
            mButtonCode = buttonCode;
            return this;
        }

        /**
         * Sets the action of the event.
         *
         * @return this builder, to allow for chaining of calls
         */
        public @NonNull Builder setAction(@Action int action) {
            if (action != ACTION_BUTTON_PRESS && action != ACTION_BUTTON_RELEASE) {
                throw new IllegalArgumentException("Unsupported mouse button action type");
            }
            mAction = action;
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
            this.mEventTimeNanos = eventTimeNanos;
            return this;
        }
    }

    public static final @NonNull Parcelable.Creator<VirtualMouseButtonEvent> CREATOR =
            new Parcelable.Creator<VirtualMouseButtonEvent>() {
                public VirtualMouseButtonEvent createFromParcel(Parcel source) {
                    return new VirtualMouseButtonEvent(source);
                }

                public VirtualMouseButtonEvent[] newArray(int size) {
                    return new VirtualMouseButtonEvent[size];
                }
            };
}
