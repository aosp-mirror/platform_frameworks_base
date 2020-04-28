/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.view;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;


/**
 * Base class for verified events.
 * Verified events contain the subset of an InputEvent that the system can verify.
 * Data contained inside VerifiedInputEvent's should be considered trusted and contain only
 * the original event data that first came from the system.
 *
 * @see android.hardware.input.InputManager#verifyInputEvent(InputEvent)
 */
@SuppressLint("ParcelNotFinal")
public abstract class VerifiedInputEvent implements Parcelable {
    private static final String TAG = "VerifiedInputEvent";

    /** @hide */
    protected static final int VERIFIED_KEY = 1;
    /** @hide */
    protected static final int VERIFIED_MOTION = 2;

    /** @hide */
    @Retention(SOURCE)
    @IntDef(prefix = "VERIFIED", value = {VERIFIED_KEY, VERIFIED_MOTION})
    public @interface VerifiedInputEventType {};

    @VerifiedInputEventType
    private int mType;

    private int mDeviceId;
    private long mEventTimeNanos;
    private int mSource;
    private int mDisplayId;

    /** @hide */
    protected VerifiedInputEvent(int type, int deviceId, long eventTimeNanos, int source,
            int displayId) {
        mType = type;
        mDeviceId = deviceId;
        mEventTimeNanos = eventTimeNanos;
        mSource = source;
        mDisplayId = displayId;
    }
    /** @hide */
    protected VerifiedInputEvent(@NonNull Parcel in, int expectedType) {
        mType = in.readInt();
        if (mType != expectedType) {
            throw new IllegalArgumentException("Unexpected input event type token in parcel.");
        }
        mDeviceId = in.readInt();
        mEventTimeNanos = in.readLong();
        mSource = in.readInt();
        mDisplayId = in.readInt();
    }

    /**
     * Get the id of the device that generated this event.
     *
     * @see InputEvent#getDeviceId()
     */
    public int getDeviceId() {
        return mDeviceId;
    }

    /**
     * Get the time this event occurred, in the {@link android.os.SystemClock#uptimeMillis()}
     * time base.
     *
     * @see InputEvent#getEventTime()
     */
    @SuppressLint("MethodNameUnits")
    public long getEventTimeNanos() {
        return mEventTimeNanos;
    }

    /**
     * Get the source of the event.
     *
     * @see InputEvent#getSource()
     */
    public int getSource() {
        return mSource;
    }

    /**
     * Get the display id that is associated with this event.
     *
     * @see Display#getDisplayId()
     */
    public int getDisplayId() {
        return mDisplayId;
    }

    /** {@inheritDoc} */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeInt(mDeviceId);
        dest.writeLong(mEventTimeNanos);
        dest.writeInt(mSource);
        dest.writeInt(mDisplayId);
    }

    /** {@inheritDoc} */
    @Override
    public int describeContents() {
        return 0;
    }

    private static int peekInt(@NonNull Parcel parcel) {
        final int initialDataPosition = parcel.dataPosition();
        int data = parcel.readInt();
        parcel.setDataPosition(initialDataPosition);
        return data;
    }

    public static final @NonNull Parcelable.Creator<VerifiedInputEvent> CREATOR =
            new Parcelable.Creator<VerifiedInputEvent>() {
        @Override
        public VerifiedInputEvent[] newArray(int size) {
            return new VerifiedInputEvent[size];
        }

        @Override
        public VerifiedInputEvent createFromParcel(@NonNull Parcel in) {
            final int type = peekInt(in);
            if (type == VERIFIED_KEY) {
                return VerifiedKeyEvent.CREATOR.createFromParcel(in);
            } else if (type == VERIFIED_MOTION) {
                return VerifiedMotionEvent.CREATOR.createFromParcel(in);
            }
            throw new IllegalArgumentException("Unexpected input event type in parcel.");
        }
    };
}
