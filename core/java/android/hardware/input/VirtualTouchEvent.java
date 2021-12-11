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

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.MotionEvent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An event describing a touchscreen interaction originating from a remote device.
 *
 * The pointer id, tool type, action, and location are required; pressure and main axis size are
 * optional.
 *
 * @hide
 */
@SystemApi
public final class VirtualTouchEvent implements Parcelable {

    /** @hide */
    public static final int TOOL_TYPE_UNKNOWN = MotionEvent.TOOL_TYPE_UNKNOWN;
    /** Tool type indicating that the user's finger is the origin of the event. */
    public static final int TOOL_TYPE_FINGER = MotionEvent.TOOL_TYPE_FINGER;
    /**
     * Tool type indicating that a user's palm (or other input mechanism to be rejected) is the
     * origin of the event.
     */
    public static final int TOOL_TYPE_PALM = MotionEvent.TOOL_TYPE_PALM;
    /** @hide */
    @IntDef(prefix = { "TOOL_TYPE_" }, value = {
            TOOL_TYPE_UNKNOWN,
            TOOL_TYPE_FINGER,
            TOOL_TYPE_PALM,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ToolType {}

    /** @hide */
    public static final int ACTION_UNKNOWN = -1;
    /** Action indicating the tool has been pressed down to the touchscreen. */
    public static final int ACTION_DOWN = MotionEvent.ACTION_DOWN;
    /** Action indicating the tool has been lifted from the touchscreen. */
    public static final int ACTION_UP = MotionEvent.ACTION_UP;
    /** Action indicating the tool has been moved along the face of the touchscreen. */
    public static final int ACTION_MOVE = MotionEvent.ACTION_MOVE;
    /** Action indicating the tool cancelled the current movement. */
    public static final int ACTION_CANCEL = MotionEvent.ACTION_CANCEL;
    /** @hide */
    @IntDef(prefix = { "ACTION_" }, value = {
            ACTION_UNKNOWN,
            ACTION_DOWN,
            ACTION_UP,
            ACTION_MOVE,
            ACTION_CANCEL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Action {}

    private final int mPointerId;
    private final @ToolType int mToolType;
    private final @Action int mAction;
    private final float mX;
    private final float mY;
    private final float mPressure;
    private final float mMajorAxisSize;

    private VirtualTouchEvent(int pointerId, @ToolType int toolType, @Action int action,
            float x, float y, float pressure, float majorAxisSize) {
        mPointerId = pointerId;
        mToolType = toolType;
        mAction = action;
        mX = x;
        mY = y;
        mPressure = pressure;
        mMajorAxisSize = majorAxisSize;
    }

    private VirtualTouchEvent(@NonNull Parcel parcel) {
        mPointerId = parcel.readInt();
        mToolType = parcel.readInt();
        mAction = parcel.readInt();
        mX = parcel.readFloat();
        mY = parcel.readFloat();
        mPressure = parcel.readFloat();
        mMajorAxisSize = parcel.readFloat();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mPointerId);
        dest.writeInt(mToolType);
        dest.writeInt(mAction);
        dest.writeFloat(mX);
        dest.writeFloat(mY);
        dest.writeFloat(mPressure);
        dest.writeFloat(mMajorAxisSize);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Returns the pointer id associated with this event.
     */
    public int getPointerId() {
        return mPointerId;
    }

    /**
     * Returns the tool type associated with this event.
     */
    public @ToolType int getToolType() {
        return mToolType;
    }

    /**
     * Returns the action associated with this event.
     */
    public @Action int getAction() {
        return mAction;
    }

    /**
     * Returns the x-axis location associated with this event.
     */
    public float getX() {
        return mX;
    }

    /**
     * Returns the y-axis location associated with this event.
     */
    public float getY() {
        return mY;
    }

    /**
     * Returns the pressure associated with this event. Returns {@link Float#NaN} if omitted.
     */
    public float getPressure() {
        return mPressure;
    }

    /**
     * Returns the major axis size associated with this event. Returns {@link Float#NaN} if omitted.
     */
    public float getMajorAxisSize() {
        return mMajorAxisSize;
    }

    /**
     * Builder for {@link VirtualTouchEvent}.
     */
    public static final class Builder {

        private @ToolType int mToolType = TOOL_TYPE_UNKNOWN;
        private int mPointerId = MotionEvent.INVALID_POINTER_ID;
        private @Action int mAction = ACTION_UNKNOWN;
        private float mX = Float.NaN;
        private float mY = Float.NaN;
        private float mPressure = Float.NaN;
        private float mMajorAxisSize = Float.NaN;

        /**
         * Creates a {@link VirtualTouchEvent} object with the current builder configuration.
         */
        public @NonNull VirtualTouchEvent build() {
            if (mToolType == TOOL_TYPE_UNKNOWN || mPointerId == MotionEvent.INVALID_POINTER_ID
                    || mAction == ACTION_UNKNOWN || Float.isNaN(mX) || Float.isNaN(mY)) {
                throw new IllegalArgumentException(
                        "Cannot build virtual touch event with unset required fields");
            }
            if ((mToolType == TOOL_TYPE_PALM && mAction != ACTION_CANCEL)
                    || (mAction == ACTION_CANCEL && mToolType != TOOL_TYPE_PALM)) {
                throw new IllegalArgumentException(
                        "ACTION_CANCEL and TOOL_TYPE_PALM must always appear together");
            }
            return new VirtualTouchEvent(mPointerId, mToolType, mAction, mX, mY, mPressure,
                    mMajorAxisSize);
        }

        /**
         * Sets the pointer id of the event.
         *
         * @return this builder, to allow for chaining of calls
         */
        public @NonNull Builder setPointerId(int pointerId) {
            mPointerId = pointerId;
            return this;
        }

        /**
         * Sets the tool type of the event.
         *
         * @return this builder, to allow for chaining of calls
         */
        public @NonNull Builder setToolType(@ToolType int toolType) {
            if (toolType != TOOL_TYPE_FINGER && toolType != TOOL_TYPE_PALM) {
                throw new IllegalArgumentException("Unsupported touch event tool type");
            }
            mToolType = toolType;
            return this;
        }

        /**
         * Sets the action of the event.
         *
         * @return this builder, to allow for chaining of calls
         */
        public @NonNull Builder setAction(@Action int action) {
            if (action != ACTION_DOWN && action != ACTION_UP && action != ACTION_MOVE
                    && action != ACTION_CANCEL) {
                throw new IllegalArgumentException("Unsupported touch event action type");
            }
            mAction = action;
            return this;
        }

        /**
         * Sets the x-axis location of the event.
         *
         * @return this builder, to allow for chaining of calls
         */
        public @NonNull Builder setX(float absX) {
            mX = absX;
            return this;
        }

        /**
         * Sets the y-axis location of the event.
         *
         * @return this builder, to allow for chaining of calls
         */
        public @NonNull Builder setY(float absY) {
            mY = absY;
            return this;
        }

        /**
         * Sets the pressure of the event. This field is optional and can be omitted.
         *
         * @return this builder, to allow for chaining of calls
         */
        public @NonNull Builder setPressure(@FloatRange(from = 0f) float pressure) {
            if (pressure < 0f) {
                throw new IllegalArgumentException("Touch event pressure cannot be negative");
            }
            mPressure = pressure;
            return this;
        }

        /**
         * Sets the major axis size of the event. This field is optional and can be omitted.
         *
         * @return this builder, to allow for chaining of calls
         */
        public @NonNull Builder setMajorAxisSize(@FloatRange(from = 0f) float majorAxisSize) {
            if (majorAxisSize < 0f) {
                throw new IllegalArgumentException(
                        "Touch event major axis size cannot be negative");
            }
            mMajorAxisSize = majorAxisSize;
            return this;
        }
    }

    public static final @NonNull Parcelable.Creator<VirtualTouchEvent> CREATOR =
            new Parcelable.Creator<VirtualTouchEvent>() {
        public VirtualTouchEvent createFromParcel(Parcel source) {
            return new VirtualTouchEvent(source);
        }
        public VirtualTouchEvent[] newArray(int size) {
            return new VirtualTouchEvent[size];
        }
    };
}
