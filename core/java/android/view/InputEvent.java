/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.os.Parcel;
import android.os.Parcelable;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Common base class for input events.
 */
public abstract class InputEvent implements Parcelable {
    /** @hide */
    protected static final int PARCEL_TOKEN_MOTION_EVENT = 1;
    /** @hide */
    protected static final int PARCEL_TOKEN_KEY_EVENT = 2;

    // Next sequence number.
    private static final AtomicInteger mNextSeq = new AtomicInteger();

    /** @hide */
    protected int mSeq;

    /** @hide */
    protected boolean mRecycled;

    private static final boolean TRACK_RECYCLED_LOCATION = false;
    private RuntimeException mRecycledLocation;

    /*package*/ InputEvent() {
        mSeq = mNextSeq.getAndIncrement();
    }

    /**
     * Gets the id for the device that this event came from.  An id of
     * zero indicates that the event didn't come from a physical device
     * and maps to the default keymap.  The other numbers are arbitrary and
     * you shouldn't depend on the values.
     * 
     * @return The device id.
     * @see InputDevice#getDevice
     */
    public abstract int getDeviceId();

    /**
     * Gets the device that this event came from.
     * 
     * @return The device, or null if unknown.
     */
    public final InputDevice getDevice() {
        return InputDevice.getDevice(getDeviceId());
    }

    /**
     * Gets the source of the event.
     * 
     * @return The event source or {@link InputDevice#SOURCE_UNKNOWN} if unknown.
     * @see InputDevice#getSourceInfo
     */
    public abstract int getSource();

    /**
     * Modifies the source of the event.
     *
     * @param source The new source.
     * @hide
     */
    public abstract void setSource(int source);

    /**
     * Copies the event.
     *
     * @return A deep copy of the event.
     * @hide
     */
    public abstract InputEvent copy();

    /**
     * Recycles the event.
     * This method should only be used by the system since applications do not
     * expect {@link KeyEvent} objects to be recycled, although {@link MotionEvent}
     * objects are fine.  See {@link KeyEvent#recycle()} for details.
     * @hide
     */
    public void recycle() {
        if (TRACK_RECYCLED_LOCATION) {
            if (mRecycledLocation != null) {
                throw new RuntimeException(toString() + " recycled twice!", mRecycledLocation);
            }
            mRecycledLocation = new RuntimeException("Last recycled here");
        } else {
            if (mRecycled) {
                throw new RuntimeException(toString() + " recycled twice!");
            }
            mRecycled = true;
        }
    }

    /**
     * Conditionally recycled the event if it is appropriate to do so after
     * dispatching the event to an application.
     *
     * If the event is a {@link MotionEvent} then it is recycled.
     *
     * If the event is a {@link KeyEvent} then it is NOT recycled, because applications
     * expect key events to be immutable so once the event has been dispatched to
     * the application we can no longer recycle it.
     * @hide
     */
    public void recycleIfNeededAfterDispatch() {
        recycle();
    }

    /**
     * Reinitializes the event on reuse (after recycling).
     * @hide
     */
    protected void prepareForReuse() {
        mRecycled = false;
        mRecycledLocation = null;
        mSeq = mNextSeq.getAndIncrement();
    }

    /**
     * Gets a private flag that indicates when the system has detected that this input event
     * may be inconsistent with respect to the sequence of previously delivered input events,
     * such as when a key up event is sent but the key was not down or when a pointer
     * move event is sent but the pointer is not down.
     *
     * @return True if this event is tainted.
     * @hide
     */
    public abstract boolean isTainted();

    /**
     * Sets a private flag that indicates when the system has detected that this input event
     * may be inconsistent with respect to the sequence of previously delivered input events,
     * such as when a key up event is sent but the key was not down or when a pointer
     * move event is sent but the pointer is not down.
     *
     * @param tainted True if this event is tainted.
     * @hide
     */
    public abstract void setTainted(boolean tainted);

    /**
     * Retrieve the time this event occurred,
     * in the {@link android.os.SystemClock#uptimeMillis} time base.
     *
     * @return Returns the time this event occurred,
     * in the {@link android.os.SystemClock#uptimeMillis} time base.
     */
    public abstract long getEventTime();

    /**
     * Retrieve the time this event occurred,
     * in the {@link android.os.SystemClock#uptimeMillis} time base but with
     * nanosecond (instead of millisecond) precision.
     * <p>
     * The value is in nanosecond precision but it may not have nanosecond accuracy.
     * </p>
     *
     * @return Returns the time this event occurred,
     * in the {@link android.os.SystemClock#uptimeMillis} time base but with
     * nanosecond (instead of millisecond) precision.
     *
     * @hide
     */
    public abstract long getEventTimeNano();

    /**
     * Gets the unique sequence number of this event.
     * Every input event that is created or received by a process has a
     * unique sequence number.  Moreover, a new sequence number is obtained
     * each time an event object is recycled.
     *
     * Sequence numbers are only guaranteed to be locally unique within a process.
     * Sequence numbers are not preserved when events are parceled.
     *
     * @return The unique sequence number of this event.
     * @hide
     */
    public int getSequenceNumber() {
        return mSeq;
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<InputEvent> CREATOR
            = new Parcelable.Creator<InputEvent>() {
        public InputEvent createFromParcel(Parcel in) {
            int token = in.readInt();
            if (token == PARCEL_TOKEN_KEY_EVENT) {
                return KeyEvent.createFromParcelBody(in);
            } else if (token == PARCEL_TOKEN_MOTION_EVENT) {
                return MotionEvent.createFromParcelBody(in);
            } else {
                throw new IllegalStateException("Unexpected input event type token in parcel.");
            }
        }
        
        public InputEvent[] newArray(int size) {
            return new InputEvent[size];
        }
    };
}
