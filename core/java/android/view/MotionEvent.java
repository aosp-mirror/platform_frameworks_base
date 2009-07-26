/*
 * Copyright (C) 2007 The Android Open Source Project
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
import android.os.SystemClock;
import android.util.Config;

/**
 * Object used to report movement (mouse, pen, finger, trackball) events.  This
 * class may hold either absolute or relative movements, depending on what
 * it is being used for.
 */
public final class MotionEvent implements Parcelable {
    /**
     * Constant for {@link #getAction}: A pressed gesture has started, the
     * motion contains the initial starting location.
     */
    public static final int ACTION_DOWN             = 0;
    /**
     * Constant for {@link #getAction}: A pressed gesture has finished, the
     * motion contains the final release location as well as any intermediate
     * points since the last down or move event.
     */
    public static final int ACTION_UP               = 1;
    /**
     * Constant for {@link #getAction}: A change has happened during a
     * press gesture (between {@link #ACTION_DOWN} and {@link #ACTION_UP}).
     * The motion contains the most recent point, as well as any intermediate
     * points since the last down or move event.
     */
    public static final int ACTION_MOVE             = 2;
    /**
     * Constant for {@link #getAction}: The current gesture has been aborted.
     * You will not receive any more points in it.  You should treat this as
     * an up event, but not perform any action that you normally would.
     */
    public static final int ACTION_CANCEL           = 3;
    /**
     * Constant for {@link #getAction}: A movement has happened outside of the
     * normal bounds of the UI element.  This does not provide a full gesture,
     * but only the initial location of the movement/touch.
     */
    public static final int ACTION_OUTSIDE          = 4;

    private static final boolean TRACK_RECYCLED_LOCATION = false;

    /**
     * Flag indicating the motion event intersected the top edge of the screen.
     */
    public static final int EDGE_TOP = 0x00000001;

    /**
     * Flag indicating the motion event intersected the bottom edge of the screen.
     */
    public static final int EDGE_BOTTOM = 0x00000002;

    /**
     * Flag indicating the motion event intersected the left edge of the screen.
     */
    public static final int EDGE_LEFT = 0x00000004;

    /**
     * Flag indicating the motion event intersected the right edge of the screen.
     */
    public static final int EDGE_RIGHT = 0x00000008;

    static private final int MAX_RECYCLED = 10;
    static private Object gRecyclerLock = new Object();
    static private int gRecyclerUsed = 0;
    static private MotionEvent gRecyclerTop = null;

    private long mDownTime;
    private long mEventTime;
    private int mAction;
    private float mX;
    private float mY;
    private float mRawX;
    private float mRawY;
    private float mPressure;
    private float mSize;
    private int mMetaState;
    private int mNumHistory;
    private float[] mHistory;
    private long[] mHistoryTimes;
    private float mXPrecision;
    private float mYPrecision;
    private int mDeviceId;
    private int mEdgeFlags;

    private MotionEvent mNext;
    private RuntimeException mRecycledLocation;
    private boolean mRecycled;

    private MotionEvent() {
    }

    static private MotionEvent obtain() {
        synchronized (gRecyclerLock) {
            if (gRecyclerTop == null) {
                return new MotionEvent();
            }
            MotionEvent ev = gRecyclerTop;
            gRecyclerTop = ev.mNext;
            gRecyclerUsed--;
            ev.mRecycledLocation = null;
            ev.mRecycled = false;
            return ev;
        }
    }

    /**
     * Create a new MotionEvent, filling in all of the basic values that
     * define the motion.
     *
     * @param downTime The time (in ms) when the user originally pressed down to start
     * a stream of position events.  This must be obtained from {@link SystemClock#uptimeMillis()}.
     * @param eventTime  The the time (in ms) when this specific event was generated.  This
     * must be obtained from {@link SystemClock#uptimeMillis()}.
     * @param action The kind of action being performed -- one of either
     * {@link #ACTION_DOWN}, {@link #ACTION_MOVE}, {@link #ACTION_UP}, or
     * {@link #ACTION_CANCEL}.
     * @param x The X coordinate of this event.
     * @param y The Y coordinate of this event.
     * @param pressure The current pressure of this event.  The pressure generally
     * ranges from 0 (no pressure at all) to 1 (normal pressure), however
     * values higher than 1 may be generated depending on the calibration of
     * the input device.
     * @param size A scaled value of the approximate size of the area being pressed when
     * touched with the finger. The actual value in pixels corresponding to the finger
     * touch is normalized with a device specific range of values
     * and scaled to a value between 0 and 1.
     * @param metaState The state of any meta / modifier keys that were in effect when
     * the event was generated.
     * @param xPrecision The precision of the X coordinate being reported.
     * @param yPrecision The precision of the Y coordinate being reported.
     * @param deviceId The id for the device that this event came from.  An id of
     * zero indicates that the event didn't come from a physical device; other
     * numbers are arbitrary and you shouldn't depend on the values.
     * @param edgeFlags A bitfield indicating which edges, if any, where touched by this
     * MotionEvent.
     */
    static public MotionEvent obtain(long downTime, long eventTime, int action,
            float x, float y, float pressure, float size, int metaState,
            float xPrecision, float yPrecision, int deviceId, int edgeFlags) {
        MotionEvent ev = obtain();
        ev.mDeviceId = deviceId;
        ev.mEdgeFlags = edgeFlags;
        ev.mDownTime = downTime;
        ev.mEventTime = eventTime;
        ev.mAction = action;
        ev.mX = ev.mRawX = x;
        ev.mY = ev.mRawY = y;
        ev.mPressure = pressure;
        ev.mSize = size;
        ev.mMetaState = metaState;
        ev.mXPrecision = xPrecision;
        ev.mYPrecision = yPrecision;

        return ev;
    }

    /**
     * Create a new MotionEvent, filling in a subset of the basic motion
     * values.  Those not specified here are: device id (always 0), pressure
     * and size (always 1), x and y precision (always 1), and edgeFlags (always 0).
     *
     * @param downTime The time (in ms) when the user originally pressed down to start
     * a stream of position events.  This must be obtained from {@link SystemClock#uptimeMillis()}.
     * @param eventTime  The the time (in ms) when this specific event was generated.  This
     * must be obtained from {@link SystemClock#uptimeMillis()}.
     * @param action The kind of action being performed -- one of either
     * {@link #ACTION_DOWN}, {@link #ACTION_MOVE}, {@link #ACTION_UP}, or
     * {@link #ACTION_CANCEL}.
     * @param x The X coordinate of this event.
     * @param y The Y coordinate of this event.
     * @param metaState The state of any meta / modifier keys that were in effect when
     * the event was generated.
     */
    static public MotionEvent obtain(long downTime, long eventTime, int action,
            float x, float y, int metaState) {
        MotionEvent ev = obtain();
        ev.mDeviceId = 0;
        ev.mEdgeFlags = 0;
        ev.mDownTime = downTime;
        ev.mEventTime = eventTime;
        ev.mAction = action;
        ev.mX = ev.mRawX = x;
        ev.mY = ev.mRawY = y;
        ev.mPressure = 1.0f;
        ev.mSize = 1.0f;
        ev.mMetaState = metaState;
        ev.mXPrecision = 1.0f;
        ev.mYPrecision = 1.0f;

        return ev;
    }

    /**
     * Scales down the coordination of this event by the given scale.
     *
     * @hide
     */
    public void scale(float scale) {
        mX *= scale;
        mY *= scale;
        mRawX *= scale;
        mRawY *= scale;
        mSize *= scale;
        mXPrecision *= scale;
        mYPrecision *= scale;
        if (mHistory != null) {
            float[] history = mHistory;
            int length = history.length;
            for (int i = 0; i < length; i += 4) {
                history[i] *= scale;        // X
                history[i + 1] *= scale;    // Y
                // no need to scale pressure ([i+2])
                history[i + 3] *= scale;    // Size, TODO: square this?
            }
        }
    }

    /**
     * Translate the coordination of the event by given x and y.
     *
     * @hide
     */
    public void translate(float dx, float dy) {
        mX += dx;
        mY += dy;
        mRawX += dx;
        mRawY += dx;
        if (mHistory != null) {
            float[] history = mHistory;
            int length = history.length;
            for (int i = 0; i < length; i += 4) {
                history[i] += dx;        // X
                history[i + 1] += dy;    // Y
                // no need to translate pressure (i+2) and size (i+3) 
            }
        }
    }

    /**
     * Create a new MotionEvent, copying from an existing one.
     */
    static public MotionEvent obtain(MotionEvent o) {
        MotionEvent ev = obtain();
        ev.mDeviceId = o.mDeviceId;
        ev.mEdgeFlags = o.mEdgeFlags;
        ev.mDownTime = o.mDownTime;
        ev.mEventTime = o.mEventTime;
        ev.mAction = o.mAction;
        ev.mX = o.mX;
        ev.mRawX = o.mRawX;
        ev.mY = o.mY;
        ev.mRawY = o.mRawY;
        ev.mPressure = o.mPressure;
        ev.mSize = o.mSize;
        ev.mMetaState = o.mMetaState;
        ev.mXPrecision = o.mXPrecision;
        ev.mYPrecision = o.mYPrecision;
        final int N = o.mNumHistory;
        ev.mNumHistory = N;
        if (N > 0) {
            // could be more efficient about this...
            ev.mHistory = (float[])o.mHistory.clone();
            ev.mHistoryTimes = (long[])o.mHistoryTimes.clone();
        }
        return ev;
    }

    /**
     * Recycle the MotionEvent, to be re-used by a later caller.  After calling
     * this function you must not ever touch the event again.
     */
    public void recycle() {
        // Ensure recycle is only called once!
        if (TRACK_RECYCLED_LOCATION) {
            if (mRecycledLocation != null) {
                throw new RuntimeException(toString() + " recycled twice!", mRecycledLocation);
            }
            mRecycledLocation = new RuntimeException("Last recycled here");
        } else if (mRecycled) {
            throw new RuntimeException(toString() + " recycled twice!");
        }

        //Log.w("MotionEvent", "Recycling event " + this, mRecycledLocation);
        synchronized (gRecyclerLock) {
            if (gRecyclerUsed < MAX_RECYCLED) {
                gRecyclerUsed++;
                mNumHistory = 0;
                mNext = gRecyclerTop;
                gRecyclerTop = this;
            }
        }
    }

    /**
     * Return the kind of action being performed -- one of either
     * {@link #ACTION_DOWN}, {@link #ACTION_MOVE}, {@link #ACTION_UP}, or
     * {@link #ACTION_CANCEL}.
     */
    public final int getAction() {
        return mAction;
    }

    /**
     * Returns the time (in ms) when the user originally pressed down to start
     * a stream of position events.
     */
    public final long getDownTime() {
        return mDownTime;
    }

    /**
     * Returns the time (in ms) when this specific event was generated.
     */
    public final long getEventTime() {
        return mEventTime;
    }

    /**
     * Returns the X coordinate of this event.  Whole numbers are pixels; the
     * value may have a fraction for input devices that are sub-pixel precise.
     */
    public final float getX() {
        return mX;
    }

    /**
     * Returns the Y coordinate of this event.  Whole numbers are pixels; the
     * value may have a fraction for input devices that are sub-pixel precise.
     */
    public final float getY() {
        return mY;
    }

    /**
     * Returns the current pressure of this event.  The pressure generally
     * ranges from 0 (no pressure at all) to 1 (normal pressure), however
     * values higher than 1 may be generated depending on the calibration of
     * the input device.
     */
    public final float getPressure() {
        return mPressure;
    }

    /**
     * Returns a scaled value of the approximate size, of the area being pressed when
     * touched with the finger. The actual value in pixels corresponding to the finger
     * touch  is normalized with the device specific range of values
     * and scaled to a value between 0 and 1. The value of size can be used to
     * determine fat touch events.
     */
    public final float getSize() {
        return mSize;
    }

    /**
     * Returns the state of any meta / modifier keys that were in effect when
     * the event was generated.  This is the same values as those
     * returned by {@link KeyEvent#getMetaState() KeyEvent.getMetaState}.
     *
     * @return an integer in which each bit set to 1 represents a pressed
     *         meta key
     *
     * @see KeyEvent#getMetaState()
     */
    public final int getMetaState() {
        return mMetaState;
    }

    /**
     * Returns the original raw X coordinate of this event.  For touch
     * events on the screen, this is the original location of the event
     * on the screen, before it had been adjusted for the containing window
     * and views.
     */
    public final float getRawX() {
        return mRawX;
    }

    /**
     * Returns the original raw Y coordinate of this event.  For touch
     * events on the screen, this is the original location of the event
     * on the screen, before it had been adjusted for the containing window
     * and views.
     */
    public final float getRawY() {
        return mRawY;
    }

    /**
     * Return the precision of the X coordinates being reported.  You can
     * multiple this number with {@link #getX} to find the actual hardware
     * value of the X coordinate.
     * @return Returns the precision of X coordinates being reported.
     */
    public final float getXPrecision() {
        return mXPrecision;
    }

    /**
     * Return the precision of the Y coordinates being reported.  You can
     * multiple this number with {@link #getY} to find the actual hardware
     * value of the Y coordinate.
     * @return Returns the precision of Y coordinates being reported.
     */
    public final float getYPrecision() {
        return mYPrecision;
    }

    /**
     * Returns the number of historical points in this event.  These are
     * movements that have occurred between this event and the previous event.
     * This only applies to ACTION_MOVE events -- all other actions will have
     * a size of 0.
     *
     * @return Returns the number of historical points in the event.
     */
    public final int getHistorySize() {
        return mNumHistory;
    }

    /**
     * Returns the time that a historical movement occurred between this event
     * and the previous event.  Only applies to ACTION_MOVE events.
     *
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     *
     * @see #getHistorySize
     * @see #getEventTime
     */
    public final long getHistoricalEventTime(int pos) {
        return mHistoryTimes[pos];
    }

    /**
     * Returns a historical X coordinate that occurred between this event
     * and the previous event.  Only applies to ACTION_MOVE events.
     *
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     *
     * @see #getHistorySize
     * @see #getX
     */
    public final float getHistoricalX(int pos) {
        return mHistory[pos*4];
    }

    /**
     * Returns a historical Y coordinate that occurred between this event
     * and the previous event.  Only applies to ACTION_MOVE events.
     *
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     *
     * @see #getHistorySize
     * @see #getY
     */
    public final float getHistoricalY(int pos) {
        return mHistory[pos*4 + 1];
    }

    /**
     * Returns a historical pressure coordinate that occurred between this event
     * and the previous event.  Only applies to ACTION_MOVE events.
     *
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     *
     * @see #getHistorySize
     * @see #getPressure
     */
    public final float getHistoricalPressure(int pos) {
        return mHistory[pos*4 + 2];
    }

    /**
     * Returns a historical size coordinate that occurred between this event
     * and the previous event.  Only applies to ACTION_MOVE events.
     *
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     *
     * @see #getHistorySize
     * @see #getSize
     */
    public final float getHistoricalSize(int pos) {
        return mHistory[pos*4 + 3];
    }

    /**
     * Return the id for the device that this event came from.  An id of
     * zero indicates that the event didn't come from a physical device; other
     * numbers are arbitrary and you shouldn't depend on the values.
     */
    public final int getDeviceId() {
        return mDeviceId;
    }

    /**
     * Returns a bitfield indicating which edges, if any, where touched by this
     * MotionEvent. For touch events, clients can use this to determine if the
     * user's finger was touching the edge of the display.
     *
     * @see #EDGE_LEFT
     * @see #EDGE_TOP
     * @see #EDGE_RIGHT
     * @see #EDGE_BOTTOM
     */
    public final int getEdgeFlags() {
        return mEdgeFlags;
    }


    /**
     * Sets the bitfield indicating which edges, if any, where touched by this
     * MotionEvent.
     *
     * @see #getEdgeFlags()
     */
    public final void setEdgeFlags(int flags) {
        mEdgeFlags = flags;
    }

    /**
     * Sets this event's action.
     */
    public final void setAction(int action) {
        mAction = action;
    }

    /**
     * Adjust this event's location.
     * @param deltaX Amount to add to the current X coordinate of the event.
     * @param deltaY Amount to add to the current Y coordinate of the event.
     */
    public final void offsetLocation(float deltaX, float deltaY) {
        mX += deltaX;
        mY += deltaY;
        final int N = mNumHistory*4;
        if (N <= 0) {
            return;
        }
        final float[] pos = mHistory;
        for (int i=0; i<N; i+=4) {
            pos[i] += deltaX;
            pos[i+1] += deltaY;
        }
    }

    /**
     * Set this event's location.  Applies {@link #offsetLocation} with a
     * delta from the current location to the given new location.
     *
     * @param x New absolute X location.
     * @param y New absolute Y location.
     */
    public final void setLocation(float x, float y) {
        float deltaX = x-mX;
        float deltaY = y-mY;
        if (deltaX != 0 || deltaY != 0) {
            offsetLocation(deltaX, deltaY);
        }
    }

    /**
     * Add a new movement to the batch of movements in this event.  The event's
     * current location, position and size is updated to the new values.  In
     * the future, the current values in the event will be added to a list of
     * historic values.
     *
     * @param x The new X position.
     * @param y The new Y position.
     * @param pressure The new pressure.
     * @param size The new size.
     */
    public final void addBatch(long eventTime, float x, float y,
            float pressure, float size, int metaState) {
        float[] history = mHistory;
        long[] historyTimes = mHistoryTimes;
        int N;
        int avail;
        if (history == null) {
            mHistory = history = new float[8*4];
            mHistoryTimes = historyTimes = new long[8];
            mNumHistory = N = 0;
            avail = 8;
        } else {
            N = mNumHistory;
            avail = history.length/4;
            if (N == avail) {
                avail += 8;
                float[] newHistory = new float[avail*4];
                System.arraycopy(history, 0, newHistory, 0, N*4);
                mHistory = history = newHistory;
                long[] newHistoryTimes = new long[avail];
                System.arraycopy(historyTimes, 0, newHistoryTimes, 0, N);
                mHistoryTimes = historyTimes = newHistoryTimes;
            }
        }

        historyTimes[N] = mEventTime;

        final int pos = N*4;
        history[pos] = mX;
        history[pos+1] = mY;
        history[pos+2] = mPressure;
        history[pos+3] = mSize;
        mNumHistory = N+1;

        mEventTime = eventTime;
        mX = mRawX = x;
        mY = mRawY = y;
        mPressure = pressure;
        mSize = size;
        mMetaState |= metaState;
    }

    @Override
    public String toString() {
        return "MotionEvent{" + Integer.toHexString(System.identityHashCode(this))
            + " action=" + mAction + " x=" + mX
            + " y=" + mY + " pressure=" + mPressure + " size=" + mSize + "}";
    }

    public static final Parcelable.Creator<MotionEvent> CREATOR
            = new Parcelable.Creator<MotionEvent>() {
        public MotionEvent createFromParcel(Parcel in) {
            MotionEvent ev = obtain();
            ev.readFromParcel(in);
            return ev;
        }

        public MotionEvent[] newArray(int size) {
            return new MotionEvent[size];
        }
    };

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(mDownTime);
        out.writeLong(mEventTime);
        out.writeInt(mAction);
        out.writeFloat(mX);
        out.writeFloat(mY);
        out.writeFloat(mPressure);
        out.writeFloat(mSize);
        out.writeInt(mMetaState);
        out.writeFloat(mRawX);
        out.writeFloat(mRawY);
        final int N = mNumHistory;
        out.writeInt(N);
        if (N > 0) {
            final int N4 = N*4;
            int i;
            float[] history = mHistory;
            for (i=0; i<N4; i++) {
                out.writeFloat(history[i]);
            }
            long[] times = mHistoryTimes;
            for (i=0; i<N; i++) {
                out.writeLong(times[i]);
            }
        }
        out.writeFloat(mXPrecision);
        out.writeFloat(mYPrecision);
        out.writeInt(mDeviceId);
        out.writeInt(mEdgeFlags);
    }

    private void readFromParcel(Parcel in) {
        mDownTime = in.readLong();
        mEventTime = in.readLong();
        mAction = in.readInt();
        mX = in.readFloat();
        mY = in.readFloat();
        mPressure = in.readFloat();
        mSize = in.readFloat();
        mMetaState = in.readInt();
        mRawX = in.readFloat();
        mRawY = in.readFloat();
        final int N = in.readInt();
        if ((mNumHistory=N) > 0) {
            final int N4 = N*4;
            float[] history = mHistory;
            if (history == null || history.length < N4) {
                mHistory = history = new float[N4 + (4*4)];
            }
            for (int i=0; i<N4; i++) {
                history[i] = in.readFloat();
            }
            long[] times = mHistoryTimes;
            if (times == null || times.length < N) {
                mHistoryTimes = times = new long[N + 4];
            }
            for (int i=0; i<N; i++) {
                times[i] = in.readLong();
            }
        }
        mXPrecision = in.readFloat();
        mYPrecision = in.readFloat();
        mDeviceId = in.readInt();
        mEdgeFlags = in.readInt();
    }

}
