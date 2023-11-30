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
import android.annotation.IntRange;
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
 * An event describing a stylus interaction originating from a remote device.
 *
 * The tool type, location and action are required; tilts and pressure are optional.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_VIRTUAL_STYLUS)
@SystemApi
public final class VirtualStylusMotionEvent implements Parcelable {
    private static final int TILT_MIN = -90;
    private static final int TILT_MAX = 90;
    private static final int PRESSURE_MIN = 0;
    private static final int PRESSURE_MAX = 255;

    /** @hide */
    public static final int TOOL_TYPE_UNKNOWN = MotionEvent.TOOL_TYPE_UNKNOWN;
    /** Tool type indicating that a stylus is the origin of the event. */
    public static final int TOOL_TYPE_STYLUS = MotionEvent.TOOL_TYPE_STYLUS;
    /** Tool type indicating that an eraser is the origin of the event. */
    public static final int TOOL_TYPE_ERASER = MotionEvent.TOOL_TYPE_ERASER;
    /** @hide */
    @IntDef(prefix = { "TOOL_TYPE_" }, value = {
            TOOL_TYPE_UNKNOWN,
            TOOL_TYPE_STYLUS,
            TOOL_TYPE_ERASER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ToolType {}

    /** @hide */
    public static final int ACTION_UNKNOWN = -1;
    /**
     * Action indicating the stylus has been pressed down to the screen. ACTION_DOWN with pressure
     * {@code 0} indicates that the stylus is hovering over the screen, and non-zero pressure
     * indicates that the stylus is touching the screen.
     */
    public static final int ACTION_DOWN = MotionEvent.ACTION_DOWN;
    /** Action indicating the stylus has been lifted from the screen. */
    public static final int ACTION_UP = MotionEvent.ACTION_UP;
    /** Action indicating the stylus has been moved along the screen. */
    public static final int ACTION_MOVE = MotionEvent.ACTION_MOVE;
    /** @hide */
    @IntDef(prefix = { "ACTION_" }, value = {
            ACTION_UNKNOWN,
            ACTION_DOWN,
            ACTION_UP,
            ACTION_MOVE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Action {}

    @ToolType
    private final int mToolType;
    @Action
    private final int mAction;
    private final int mX;
    private final int mY;
    private final int mPressure;
    private final int mTiltX;
    private final int mTiltY;
    private final long mEventTimeNanos;

    private VirtualStylusMotionEvent(@ToolType int toolType, @Action int action, int x, int y,
            int pressure, int tiltX, int tiltY, long eventTimeNanos) {
        mToolType = toolType;
        mAction = action;
        mX = x;
        mY = y;
        mPressure = pressure;
        mTiltX = tiltX;
        mTiltY = tiltY;
        mEventTimeNanos = eventTimeNanos;
    }

    private VirtualStylusMotionEvent(@NonNull Parcel parcel) {
        mToolType = parcel.readInt();
        mAction = parcel.readInt();
        mX = parcel.readInt();
        mY = parcel.readInt();
        mPressure = parcel.readInt();
        mTiltX = parcel.readInt();
        mTiltY = parcel.readInt();
        mEventTimeNanos = parcel.readLong();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mToolType);
        dest.writeInt(mAction);
        dest.writeInt(mX);
        dest.writeInt(mY);
        dest.writeInt(mPressure);
        dest.writeInt(mTiltX);
        dest.writeInt(mTiltY);
        dest.writeLong(mEventTimeNanos);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Returns the tool type associated with this event.
     */
    @ToolType
    public int getToolType() {
        return mToolType;
    }

    /**
     * Returns the action associated with this event.
     */
    @Action
    public int getAction() {
        return mAction;
    }

    /**
     * Returns the x-axis location associated with this event.
     */
    public int getX() {
        return mX;
    }

    /**
     * Returns the y-axis location associated with this event.
     */
    public int getY() {
        return mY;
    }

    /**
     * Returns the pressure associated with this event. {@code 0} pressure indicates that the stylus
     * is hovering, otherwise the stylus is touching the screen. Returns {@code 255} if omitted.
     */
    public int getPressure() {
        return mPressure;
    }

    /**
     * Returns the plane angle (in degrees, in the range of [{@code -90}, {@code 90}]) between the
     * y-z plane and the plane containing both the stylus axis and the y axis. A positive tiltX is
     * to the right, in the direction of increasing x values. {@code 0} tilt indicates that the
     * stylus is perpendicular to the x-axis. Returns {@code 0} if omitted.
     *
     * @see Builder#setTiltX
     */
    public int getTiltX() {
        return mTiltX;
    }

    /**
     * Returns the plane angle (in degrees, in the range of [{@code -90}, {@code 90}]) between the
     * x-z plane and the plane containing both the stylus axis and the x axis. A positive tiltY is
     * towards the user, in the direction of increasing y values. {@code 0} tilt indicates that the
     * stylus is perpendicular to the y-axis. Returns {@code 0} if omitted.
     *
     * @see Builder#setTiltY
     */
    public int getTiltY() {
        return mTiltY;
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
     * Builder for {@link VirtualStylusMotionEvent}.
     */
    @FlaggedApi(Flags.FLAG_VIRTUAL_STYLUS)
    public static final class Builder {

        @ToolType
        private int mToolType = TOOL_TYPE_UNKNOWN;
        @Action
        private int mAction = ACTION_UNKNOWN;
        private int mX = 0;
        private int mY = 0;
        private boolean mIsXSet = false;
        private boolean mIsYSet = false;
        private int mPressure = PRESSURE_MAX;
        private int mTiltX = 0;
        private int mTiltY = 0;
        private long mEventTimeNanos = 0L;

        /**
         * Creates a {@link VirtualStylusMotionEvent} object with the current builder configuration.
         *
         * @throws IllegalArgumentException if one of the required arguments (action, tool type,
         * x-axis location and y-axis location) is missing.
         * {@link VirtualStylusMotionEvent} for a detailed explanation.
         */
        @NonNull
        public VirtualStylusMotionEvent build() {
            if (mToolType == TOOL_TYPE_UNKNOWN) {
                throw new IllegalArgumentException(
                        "Cannot build stylus motion event with unset tool type");
            }
            if (mAction == ACTION_UNKNOWN) {
                throw new IllegalArgumentException(
                        "Cannot build stylus motion event with unset action");
            }
            if (!mIsXSet) {
                throw new IllegalArgumentException(
                        "Cannot build stylus motion event with unset x-axis location");
            }
            if (!mIsYSet) {
                throw new IllegalArgumentException(
                        "Cannot build stylus motion event with unset y-axis location");
            }
            return new VirtualStylusMotionEvent(mToolType, mAction, mX, mY, mPressure, mTiltX,
                    mTiltY, mEventTimeNanos);
        }

        /**
         * Sets the tool type of the event.
         *
         * @return this builder, to allow for chaining of calls
         */
        @NonNull
        public Builder setToolType(@ToolType int toolType) {
            if (toolType != TOOL_TYPE_STYLUS && toolType != TOOL_TYPE_ERASER) {
                throw new IllegalArgumentException("Unsupported stylus tool type: " + toolType);
            }
            mToolType = toolType;
            return this;
        }

        /**
         * Sets the action of the event.
         *
         * @return this builder, to allow for chaining of calls
         */
        @NonNull
        public Builder setAction(@Action int action) {
            if (action != ACTION_DOWN && action != ACTION_UP && action != ACTION_MOVE) {
                throw new IllegalArgumentException("Unsupported stylus action : " + action);
            }
            mAction = action;
            return this;
        }

        /**
         * Sets the x-axis location of the event.
         *
         * @return this builder, to allow for chaining of calls
         */
        @NonNull
        public Builder setX(int absX) {
            mX = absX;
            mIsXSet = true;
            return this;
        }

        /**
         * Sets the y-axis location of the event.
         *
         * @return this builder, to allow for chaining of calls
         */
        @NonNull
        public Builder setY(int absY) {
            mY = absY;
            mIsYSet = true;
            return this;
        }

        /**
         * Sets the pressure of the event. {@code 0} pressure indicates that the stylus is hovering,
         * otherwise the stylus is touching the screen. This field is optional and can be omitted
         * (defaults to {@code 255}).
         *
         * @param pressure The pressure of the stylus.
         *
         * @throws IllegalArgumentException if the pressure is smaller than 0 or greater than 255.
         *
         * @return this builder, to allow for chaining of calls
         */
        @NonNull
        public Builder setPressure(
                @IntRange(from = PRESSURE_MIN, to = PRESSURE_MAX) int pressure) {
            if (pressure < PRESSURE_MIN || pressure > PRESSURE_MAX) {
                throw new IllegalArgumentException(
                        "Pressure should be between " + PRESSURE_MIN + " and " + PRESSURE_MAX);
            }
            mPressure = pressure;
            return this;
        }

        /**
         * Sets the x-axis tilt of the event in degrees. {@code 0} tilt indicates that the stylus is
         * perpendicular to the x-axis. This field is optional and can be omitted (defaults to
         * {@code 0}). Both x-axis tilt and y-axis tilt are used to derive the tilt and orientation
         * of the stylus, given by {@link MotionEvent#AXIS_TILT} and
         * {@link MotionEvent#AXIS_ORIENTATION} respectively.
         *
         * @throws IllegalArgumentException if the tilt is smaller than -90 or greater than 90.
         *
         * @return this builder, to allow for chaining of calls
         *
         * @see VirtualStylusMotionEvent#getTiltX
         * @see <a href="https://source.android.com/docs/core/interaction/input/touch-devices#orientation-and-tilt-fields">
         *     Stylus tilt and orientation</a>
         */
        @NonNull
        public Builder setTiltX(@IntRange(from = TILT_MIN, to = TILT_MAX) int tiltX) {
            validateTilt(tiltX);
            mTiltX = tiltX;
            return this;
        }

        /**
         * Sets the y-axis tilt of the event in degrees. {@code 0} tilt indicates that the stylus is
         * perpendicular to the y-axis. This field is optional and can be omitted (defaults to
         * {@code 0}). Both x-axis tilt and y-axis tilt are used to derive the tilt and orientation
         * of the stylus, given by {@link MotionEvent#AXIS_TILT} and
         * {@link MotionEvent#AXIS_ORIENTATION} respectively.
         *
         * @throws IllegalArgumentException if the tilt is smaller than -90 or greater than 90.
         *
         * @return this builder, to allow for chaining of calls
         *
         * @see VirtualStylusMotionEvent#getTiltY
         * @see <a href="https://source.android.com/docs/core/interaction/input/touch-devices#orientation-and-tilt-fields">
         *     Stylus tilt and orientation</a>
         */
        @NonNull
        public Builder setTiltY(@IntRange(from = TILT_MIN, to = TILT_MAX) int tiltY) {
            validateTilt(tiltY);
            mTiltY = tiltY;
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
        @NonNull
        public Builder setEventTimeNanos(long eventTimeNanos) {
            if (eventTimeNanos < 0L) {
                throw new IllegalArgumentException("Event time cannot be negative");
            }
            mEventTimeNanos = eventTimeNanos;
            return this;
        }

        private void validateTilt(int tilt) {
            if (tilt < TILT_MIN || tilt > TILT_MAX) {
                throw new IllegalArgumentException(
                        "Tilt must be between " + TILT_MIN + " and " + TILT_MAX);
            }
        }
    }

    @NonNull
    public static final Parcelable.Creator<VirtualStylusMotionEvent> CREATOR =
            new Parcelable.Creator<>() {
                public VirtualStylusMotionEvent createFromParcel(Parcel source) {
                    return new VirtualStylusMotionEvent(source);
                }
                public VirtualStylusMotionEvent[] newArray(int size) {
                    return new VirtualStylusMotionEvent[size];
                }
            };
}
