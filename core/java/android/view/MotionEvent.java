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

/**
 * Object used to report movement (mouse, pen, finger, trackball) events.  This
 * class may hold either absolute or relative movements, depending on what
 * it is being used for.
 * 
 * Refer to {@link InputDevice} for information about how different kinds of
 * input devices and sources represent pointer coordinates.
 */
public final class MotionEvent extends InputEvent implements Parcelable {
    private static final long MS_PER_NS = 1000000;
    
    /**
     * Bit mask of the parts of the action code that are the action itself.
     */
    public static final int ACTION_MASK             = 0xff;
    
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

    /**
     * A non-primary pointer has gone down.  The bits in
     * {@link #ACTION_POINTER_ID_MASK} indicate which pointer changed.
     */
    public static final int ACTION_POINTER_DOWN     = 5;
    
    /**
     * A non-primary pointer has gone up.  The bits in
     * {@link #ACTION_POINTER_ID_MASK} indicate which pointer changed.
     */
    public static final int ACTION_POINTER_UP       = 6;
    
    /**
     * Bits in the action code that represent a pointer index, used with
     * {@link #ACTION_POINTER_DOWN} and {@link #ACTION_POINTER_UP}.  Shifting
     * down by {@link #ACTION_POINTER_INDEX_SHIFT} provides the actual pointer
     * index where the data for the pointer going up or down can be found; you can
     * get its identifier with {@link #getPointerId(int)} and the actual
     * data with {@link #getX(int)} etc.
     */
    public static final int ACTION_POINTER_INDEX_MASK  = 0xff00;
    
    /**
     * Bit shift for the action bits holding the pointer index as
     * defined by {@link #ACTION_POINTER_INDEX_MASK}.
     */
    public static final int ACTION_POINTER_INDEX_SHIFT = 8;
    
    /**
     * @deprecated Use {@link #ACTION_POINTER_INDEX_MASK} to retrieve the
     * data index associated with {@link #ACTION_POINTER_DOWN}.
     */
    @Deprecated
    public static final int ACTION_POINTER_1_DOWN   = ACTION_POINTER_DOWN | 0x0000;
    
    /**
     * @deprecated Use {@link #ACTION_POINTER_INDEX_MASK} to retrieve the
     * data index associated with {@link #ACTION_POINTER_DOWN}.
     */
    @Deprecated
    public static final int ACTION_POINTER_2_DOWN   = ACTION_POINTER_DOWN | 0x0100;
    
    /**
     * @deprecated Use {@link #ACTION_POINTER_INDEX_MASK} to retrieve the
     * data index associated with {@link #ACTION_POINTER_DOWN}.
     */
    @Deprecated
    public static final int ACTION_POINTER_3_DOWN   = ACTION_POINTER_DOWN | 0x0200;
    
    /**
     * @deprecated Use {@link #ACTION_POINTER_INDEX_MASK} to retrieve the
     * data index associated with {@link #ACTION_POINTER_UP}.
     */
    @Deprecated
    public static final int ACTION_POINTER_1_UP     = ACTION_POINTER_UP | 0x0000;
    
    /**
     * @deprecated Use {@link #ACTION_POINTER_INDEX_MASK} to retrieve the
     * data index associated with {@link #ACTION_POINTER_UP}.
     */
    @Deprecated
    public static final int ACTION_POINTER_2_UP     = ACTION_POINTER_UP | 0x0100;
    
    /**
     * @deprecated Use {@link #ACTION_POINTER_INDEX_MASK} to retrieve the
     * data index associated with {@link #ACTION_POINTER_UP}.
     */
    @Deprecated
    public static final int ACTION_POINTER_3_UP     = ACTION_POINTER_UP | 0x0200;
    
    /**
     * @deprecated Renamed to {@link #ACTION_POINTER_INDEX_MASK} to match
     * the actual data contained in these bits.
     */
    @Deprecated
    public static final int ACTION_POINTER_ID_MASK  = 0xff00;
    
    /**
     * @deprecated Renamed to {@link #ACTION_POINTER_INDEX_SHIFT} to match
     * the actual data contained in these bits.
     */
    @Deprecated
    public static final int ACTION_POINTER_ID_SHIFT = 8;
    
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

    /**
     * Offset for the sample's X coordinate.
     * @hide
     */
    static public final int SAMPLE_X = 0;
    
    /**
     * Offset for the sample's Y coordinate.
     * @hide
     */
    static public final int SAMPLE_Y = 1;
    
    /**
     * Offset for the sample's pressure.
     * @hide
     */
    static public final int SAMPLE_PRESSURE = 2;
    
    /**
     * Offset for the sample's size
     * @hide
     */
    static public final int SAMPLE_SIZE = 3;
    
    /**
     * Offset for the sample's touch major axis length.
     * @hide
     */
    static public final int SAMPLE_TOUCH_MAJOR = 4;

    /**
     * Offset for the sample's touch minor axis length.
     * @hide
     */
    static public final int SAMPLE_TOUCH_MINOR = 5;
    
    /**
     * Offset for the sample's tool major axis length.
     * @hide
     */
    static public final int SAMPLE_TOOL_MAJOR = 6;

    /**
     * Offset for the sample's tool minor axis length.
     * @hide
     */
    static public final int SAMPLE_TOOL_MINOR = 7;
    
    /**
     * Offset for the sample's orientation.
     * @hide
     */
    static public final int SAMPLE_ORIENTATION = 8;

    /**
     * Number of data items for each sample.
     * @hide
     */
    static public final int NUM_SAMPLE_DATA = 9;
    
    /**
     * Number of possible pointers.
     * @hide
     */
    static public final int BASE_AVAIL_POINTERS = 5;
    
    static private final int BASE_AVAIL_SAMPLES = 8;
    
    static private final int MAX_RECYCLED = 10;
    static private Object gRecyclerLock = new Object();
    static private int gRecyclerUsed = 0;
    static private MotionEvent gRecyclerTop = null;

    private long mDownTimeNano;
    private int mAction;
    private float mXOffset;
    private float mYOffset;
    private float mXPrecision;
    private float mYPrecision;
    private int mEdgeFlags;
    private int mMetaState;
    
    private int mNumPointers;
    private int mNumSamples;
    
    private int mLastDataSampleIndex;
    private int mLastEventTimeNanoSampleIndex;
    
    // Array of mNumPointers size of identifiers for each pointer of data.
    private int[] mPointerIdentifiers;
    
    // Array of (mNumSamples * mNumPointers * NUM_SAMPLE_DATA) size of event data.
    // Samples are ordered from oldest to newest.
    private float[] mDataSamples;
    
    // Array of mNumSamples size of event time stamps in nanoseconds.
    // Samples are ordered from oldest to newest.
    private long[] mEventTimeNanoSamples;

    private MotionEvent mNext;
    private RuntimeException mRecycledLocation;
    private boolean mRecycled;

    private MotionEvent(int pointerCount, int sampleCount) {
        mPointerIdentifiers = new int[pointerCount];
        mDataSamples = new float[pointerCount * sampleCount * NUM_SAMPLE_DATA];
        mEventTimeNanoSamples = new long[sampleCount];
    }

    static private MotionEvent obtain(int pointerCount, int sampleCount) {
        final MotionEvent ev;
        synchronized (gRecyclerLock) {
            if (gRecyclerTop == null) {
                if (pointerCount < BASE_AVAIL_POINTERS) {
                    pointerCount = BASE_AVAIL_POINTERS;
                }
                if (sampleCount < BASE_AVAIL_SAMPLES) {
                    sampleCount = BASE_AVAIL_SAMPLES;
                }
                return new MotionEvent(pointerCount, sampleCount);
            }
            ev = gRecyclerTop;
            gRecyclerTop = ev.mNext;
            gRecyclerUsed -= 1;
        }
        ev.mRecycledLocation = null;
        ev.mRecycled = false;
        ev.mNext = null;
        
        if (ev.mPointerIdentifiers.length < pointerCount) {
            ev.mPointerIdentifiers = new int[pointerCount];
        }
        
        if (ev.mEventTimeNanoSamples.length < sampleCount) {
            ev.mEventTimeNanoSamples = new long[sampleCount];
        }
        
        final int neededDataSamplesLength = pointerCount * sampleCount * NUM_SAMPLE_DATA;
        if (ev.mDataSamples.length < neededDataSamplesLength) {
            ev.mDataSamples = new float[neededDataSamplesLength];
        }
        
        return ev;
    }
    
    /**
     * Create a new MotionEvent, filling in all of the basic values that
     * define the motion.
     * 
     * @param downTime The time (in ms) when the user originally pressed down to start 
     * a stream of position events.  This must be obtained from {@link SystemClock#uptimeMillis()}.
     * @param eventTime The the time (in ms) when this specific event was generated.  This 
     * must be obtained from {@link SystemClock#uptimeMillis()}.
     * @param action The kind of action being performed -- one of either
     * {@link #ACTION_DOWN}, {@link #ACTION_MOVE}, {@link #ACTION_UP}, or
     * {@link #ACTION_CANCEL}.
     * @param pointers The number of points that will be in this event.
     * @param pointerIds An array of <em>pointers</em> values providing
     * an identifier for each pointer.
     * @param pointerCoords An array of <em>pointers</em> values providing
     * a {@link PointerCoords} coordinate object for each pointer.
     * @param metaState The state of any meta / modifier keys that were in effect when
     * the event was generated.
     * @param xPrecision The precision of the X coordinate being reported.
     * @param yPrecision The precision of the Y coordinate being reported.
     * @param deviceId The id for the device that this event came from.  An id of
     * zero indicates that the event didn't come from a physical device; other
     * numbers are arbitrary and you shouldn't depend on the values.
     * @param edgeFlags A bitfield indicating which edges, if any, where touched by this
     * MotionEvent.
     * @param source The source of this event.
     */
    static public MotionEvent obtain(long downTime, long eventTime,
            int action, int pointers, int[] pointerIds, PointerCoords[] pointerCoords,
            int metaState, float xPrecision, float yPrecision, int deviceId,
            int edgeFlags, int source) {
        MotionEvent ev = obtain(pointers, 1);
        ev.mDeviceId = deviceId;
        ev.mSource = source;
        ev.mEdgeFlags = edgeFlags;
        ev.mDownTimeNano = downTime * MS_PER_NS;
        ev.mAction = action;
        ev.mMetaState = metaState;
        ev.mXOffset = 0;
        ev.mYOffset = 0;
        ev.mXPrecision = xPrecision;
        ev.mYPrecision = yPrecision;
        
        ev.mNumPointers = pointers;
        ev.mNumSamples = 1;
        
        ev.mLastDataSampleIndex = 0;
        ev.mLastEventTimeNanoSampleIndex = 0;
        
        System.arraycopy(pointerIds, 0, ev.mPointerIdentifiers, 0, pointers);
        
        ev.mEventTimeNanoSamples[0] = eventTime * MS_PER_NS;
        
        ev.setPointerCoordsAtSampleIndex(0, pointerCoords);
        
        return ev;
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
        MotionEvent ev = obtain(1, 1);
        ev.mDeviceId = deviceId;
        ev.mSource = InputDevice.SOURCE_UNKNOWN;
        ev.mEdgeFlags = edgeFlags;
        ev.mDownTimeNano = downTime * MS_PER_NS;
        ev.mAction = action;
        ev.mMetaState = metaState;
        ev.mXOffset = 0;
        ev.mYOffset = 0;
        ev.mXPrecision = xPrecision;
        ev.mYPrecision = yPrecision;
        
        ev.mNumPointers = 1;
        ev.mNumSamples = 1;
        
        ev.mLastDataSampleIndex = 0;
        ev.mLastEventTimeNanoSampleIndex = 0;
        
        ev.mPointerIdentifiers[0] = 0;
        
        ev.mEventTimeNanoSamples[0] = eventTime * MS_PER_NS;
        
        ev.setPointerCoordsAtSampleIndex(0, x, y, pressure, size);
        return ev;
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
     * @param pointers The number of pointers that are active in this event.
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
     * 
     * @deprecated Use {@link #obtain(long, long, int, float, float, float, float, int, float, float, int, int)}
     * instead.
     */
    @Deprecated
    static public MotionEvent obtain(long downTime, long eventTime, int action,
            int pointers, float x, float y, float pressure, float size, int metaState,
            float xPrecision, float yPrecision, int deviceId, int edgeFlags) {
        return obtain(downTime, eventTime, action, x, y, pressure, size,
                metaState, xPrecision, yPrecision, deviceId, edgeFlags);
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
        return obtain(downTime, eventTime, action, x, y, 1.0f, 1.0f,
                metaState, 1.0f, 1.0f, 0, 0);
    }

    /**
     * Create a new MotionEvent, copying from an existing one.
     */
    static public MotionEvent obtain(MotionEvent o) {
        MotionEvent ev = obtain(o.mNumPointers, o.mNumSamples);
        ev.mDeviceId = o.mDeviceId;
        ev.mSource = o.mSource;
        ev.mEdgeFlags = o.mEdgeFlags;
        ev.mDownTimeNano = o.mDownTimeNano;
        ev.mAction = o.mAction;
        ev.mMetaState = o.mMetaState;
        ev.mXOffset = o.mXOffset;
        ev.mYOffset = o.mYOffset;
        ev.mXPrecision = o.mXPrecision;
        ev.mYPrecision = o.mYPrecision;
        int numPointers = ev.mNumPointers = o.mNumPointers;
        int numSamples = ev.mNumSamples = o.mNumSamples;
        
        ev.mLastDataSampleIndex = o.mLastDataSampleIndex;
        ev.mLastEventTimeNanoSampleIndex = o.mLastEventTimeNanoSampleIndex;
        
        System.arraycopy(o.mPointerIdentifiers, 0, ev.mPointerIdentifiers, 0, numPointers);
        
        System.arraycopy(o.mEventTimeNanoSamples, 0, ev.mEventTimeNanoSamples, 0, numSamples);
        
        System.arraycopy(o.mDataSamples, 0, ev.mDataSamples, 0,
                numPointers * numSamples * NUM_SAMPLE_DATA);
        return ev;
    }

    /**
     * Create a new MotionEvent, copying from an existing one, but not including
     * any historical point information.
     */
    static public MotionEvent obtainNoHistory(MotionEvent o) {
        MotionEvent ev = obtain(o.mNumPointers, 1);
        ev.mDeviceId = o.mDeviceId;
        ev.mSource = o.mSource;
        ev.mEdgeFlags = o.mEdgeFlags;
        ev.mDownTimeNano = o.mDownTimeNano;
        ev.mAction = o.mAction;
        ev.mMetaState = o.mMetaState;
        ev.mXOffset = o.mXOffset;
        ev.mYOffset = o.mYOffset;
        ev.mXPrecision = o.mXPrecision;
        ev.mYPrecision = o.mYPrecision;
        
        int numPointers = ev.mNumPointers = o.mNumPointers;
        ev.mNumSamples = 1;
        
        ev.mLastDataSampleIndex = 0;
        ev.mLastEventTimeNanoSampleIndex = 0;
        
        System.arraycopy(o.mPointerIdentifiers, 0, ev.mPointerIdentifiers, 0, numPointers);
        
        ev.mEventTimeNanoSamples[0] = o.mEventTimeNanoSamples[o.mLastEventTimeNanoSampleIndex];
        
        System.arraycopy(o.mDataSamples, o.mLastDataSampleIndex, ev.mDataSamples, 0,
                numPointers * NUM_SAMPLE_DATA);
        return ev;
    }

    /**
     * Recycle the MotionEvent, to be re-used by a later caller.  After calling
     * this function you must not ever touch the event again.
     */
    public final void recycle() {
        // Ensure recycle is only called once!
        if (TRACK_RECYCLED_LOCATION) {
            if (mRecycledLocation != null) {
                throw new RuntimeException(toString() + " recycled twice!", mRecycledLocation);
            }
            mRecycledLocation = new RuntimeException("Last recycled here");
            //Log.w("MotionEvent", "Recycling event " + this, mRecycledLocation);
        } else {
            if (mRecycled) {
                throw new RuntimeException(toString() + " recycled twice!");
            }
            mRecycled = true;
        }

        synchronized (gRecyclerLock) {
            if (gRecyclerUsed < MAX_RECYCLED) {
                gRecyclerUsed++;
                mNumSamples = 0;
                mNext = gRecyclerTop;
                gRecyclerTop = this;
            }
        }
    }
    
    /**
     * Scales down the coordination of this event by the given scale.
     *
     * @hide
     */
    public final void scale(float scale) {
        mXOffset *= scale;
        mYOffset *= scale;
        mXPrecision *= scale;
        mYPrecision *= scale;
        
        float[] history = mDataSamples;
        final int length = mNumPointers * mNumSamples * NUM_SAMPLE_DATA;
        for (int i = 0; i < length; i += NUM_SAMPLE_DATA) {
            history[i + SAMPLE_X] *= scale;
            history[i + SAMPLE_Y] *= scale;
            // no need to scale pressure
            history[i + SAMPLE_SIZE] *= scale;    // TODO: square this?
            history[i + SAMPLE_TOUCH_MAJOR] *= scale;
            history[i + SAMPLE_TOUCH_MINOR] *= scale;
            history[i + SAMPLE_TOOL_MAJOR] *= scale;
            history[i + SAMPLE_TOOL_MINOR] *= scale;
        }
    }

    /**
     * Return the kind of action being performed -- one of either
     * {@link #ACTION_DOWN}, {@link #ACTION_MOVE}, {@link #ACTION_UP}, or
     * {@link #ACTION_CANCEL}.  Consider using {@link #getActionMasked}
     * and {@link #getActionIndex} to retrieve the separate masked action
     * and pointer index.
     */
    public final int getAction() {
        return mAction;
    }

    /**
     * Return the masked action being performed, without pointer index
     * information.  May be any of the actions: {@link #ACTION_DOWN},
     * {@link #ACTION_MOVE}, {@link #ACTION_UP}, {@link #ACTION_CANCEL},
     * {@link #ACTION_POINTER_DOWN}, or {@link #ACTION_POINTER_UP}.
     * Use {@link #getActionIndex} to return the index associated with
     * pointer actions.
     */
    public final int getActionMasked() {
        return mAction & ACTION_MASK;
    }

    /**
     * For {@link #ACTION_POINTER_DOWN} or {@link #ACTION_POINTER_UP}
     * as returned by {@link #getActionMasked}, this returns the associated
     * pointer index.  The index may be used with {@link #getPointerId(int)},
     * {@link #getX(int)}, {@link #getY(int)}, {@link #getPressure(int)},
     * and {@link #getSize(int)} to get information about the pointer that has
     * gone down or up.
     */
    public final int getActionIndex() {
        return (mAction & ACTION_POINTER_INDEX_MASK) >> ACTION_POINTER_INDEX_SHIFT;
    }

    /**
     * Returns the time (in ms) when the user originally pressed down to start
     * a stream of position events.
     */
    public final long getDownTime() {
        return mDownTimeNano / MS_PER_NS;
    }

    /**
     * Returns the time (in ms) when this specific event was generated.
     */
    public final long getEventTime() {
        return mEventTimeNanoSamples[mLastEventTimeNanoSampleIndex] / MS_PER_NS;
    }

    /**
     * Returns the time (in ns) when this specific event was generated.
     * The value is in nanosecond precision but it may not have nanosecond accuracy.
     *
     * @hide
     */
    public final long getEventTimeNano() {
        return mEventTimeNanoSamples[mLastEventTimeNanoSampleIndex];
    }

    /**
     * {@link #getX(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getX() {
        return mDataSamples[mLastDataSampleIndex + SAMPLE_X] + mXOffset;
    }

    /**
     * {@link #getY(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getY() {
        return mDataSamples[mLastDataSampleIndex + SAMPLE_Y] + mYOffset;
    }

    /**
     * {@link #getPressure(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getPressure() {
        return mDataSamples[mLastDataSampleIndex + SAMPLE_PRESSURE];
    }

    /**
     * {@link #getSize(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getSize() {
        return mDataSamples[mLastDataSampleIndex + SAMPLE_SIZE];
    }
    
    /**
     * {@link #getTouchMajor(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getTouchMajor() {
        return mDataSamples[mLastDataSampleIndex + SAMPLE_TOUCH_MAJOR];
    }

    /**
     * {@link #getTouchMinor(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getTouchMinor() {
        return mDataSamples[mLastDataSampleIndex + SAMPLE_TOUCH_MINOR];
    }
    
    /**
     * {@link #getToolMajor(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getToolMajor() {
        return mDataSamples[mLastDataSampleIndex + SAMPLE_TOOL_MAJOR];
    }

    /**
     * {@link #getToolMinor(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getToolMinor() {
        return mDataSamples[mLastDataSampleIndex + SAMPLE_TOOL_MINOR];
    }
    
    /**
     * {@link #getOrientation(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getOrientation() {
        return mDataSamples[mLastDataSampleIndex + SAMPLE_ORIENTATION];
    }

    /**
     * The number of pointers of data contained in this event.  Always
     * >= 1.
     */
    public final int getPointerCount() {
        return mNumPointers;
    }
    
    /**
     * Return the pointer identifier associated with a particular pointer
     * data index is this event.  The identifier tells you the actual pointer
     * number associated with the data, accounting for individual pointers
     * going up and down since the start of the current gesture.
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     */
    public final int getPointerId(int pointerIndex) {
        return mPointerIdentifiers[pointerIndex];
    }
    
    /**
     * Given a pointer identifier, find the index of its data in the event.
     * 
     * @param pointerId The identifier of the pointer to be found.
     * @return Returns either the index of the pointer (for use with
     * {@link #getX(int)} et al.), or -1 if there is no data available for
     * that pointer identifier.
     */
    public final int findPointerIndex(int pointerId) {
        int i = mNumPointers;
        while (i > 0) {
            i--;
            if (mPointerIdentifiers[i] == pointerId) {
                return i;
            }
        }
        return -1;
    }
    
    /**
     * Returns the X coordinate of this event for the given pointer
     * <em>index</em> (use {@link #getPointerId(int)} to find the pointer
     * identifier for this index).
     * Whole numbers are pixels; the 
     * value may have a fraction for input devices that are sub-pixel precise. 
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     */
    public final float getX(int pointerIndex) {
        return mDataSamples[mLastDataSampleIndex
                            + pointerIndex * NUM_SAMPLE_DATA + SAMPLE_X] + mXOffset;
    }

    /**
     * Returns the Y coordinate of this event for the given pointer
     * <em>index</em> (use {@link #getPointerId(int)} to find the pointer
     * identifier for this index).
     * Whole numbers are pixels; the
     * value may have a fraction for input devices that are sub-pixel precise.
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     */
    public final float getY(int pointerIndex) {
        return mDataSamples[mLastDataSampleIndex
                            + pointerIndex * NUM_SAMPLE_DATA + SAMPLE_Y] + mYOffset;
    }

    /**
     * Returns the current pressure of this event for the given pointer
     * <em>index</em> (use {@link #getPointerId(int)} to find the pointer
     * identifier for this index).
     * The pressure generally
     * ranges from 0 (no pressure at all) to 1 (normal pressure), however
     * values higher than 1 may be generated depending on the calibration of
     * the input device.
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     */
    public final float getPressure(int pointerIndex) {
        return mDataSamples[mLastDataSampleIndex
                            + pointerIndex * NUM_SAMPLE_DATA + SAMPLE_PRESSURE];
    }

    /**
     * Returns a scaled value of the approximate size for the given pointer
     * <em>index</em> (use {@link #getPointerId(int)} to find the pointer
     * identifier for this index).
     * This represents some approximation of the area of the screen being
     * pressed; the actual value in pixels corresponding to the
     * touch is normalized with the device specific range of values
     * and scaled to a value between 0 and 1. The value of size can be used to
     * determine fat touch events.
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     */
    public final float getSize(int pointerIndex) {
        return mDataSamples[mLastDataSampleIndex
                            + pointerIndex * NUM_SAMPLE_DATA + SAMPLE_SIZE];
    }
    
    /**
     * Returns the length of the major axis of an ellipse that describes the touch
     * area at the point of contact for the given pointer
     * <em>index</em> (use {@link #getPointerId(int)} to find the pointer
     * identifier for this index).
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     */
    public final float getTouchMajor(int pointerIndex) {
        return mDataSamples[mLastDataSampleIndex
                            + pointerIndex * NUM_SAMPLE_DATA + SAMPLE_TOUCH_MAJOR];
    }
    
    /**
     * Returns the length of the minor axis of an ellipse that describes the touch
     * area at the point of contact for the given pointer
     * <em>index</em> (use {@link #getPointerId(int)} to find the pointer
     * identifier for this index).
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     */
    public final float getTouchMinor(int pointerIndex) {
        return mDataSamples[mLastDataSampleIndex
                            + pointerIndex * NUM_SAMPLE_DATA + SAMPLE_TOUCH_MINOR];
    }
    
    /**
     * Returns the length of the major axis of an ellipse that describes the size of
     * the approaching tool for the given pointer
     * <em>index</em> (use {@link #getPointerId(int)} to find the pointer
     * identifier for this index).
     * The tool area represents the estimated size of the finger or pen that is
     * touching the device independent of its actual touch area at the point of contact.
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     */
    public final float getToolMajor(int pointerIndex) {
        return mDataSamples[mLastDataSampleIndex
                            + pointerIndex * NUM_SAMPLE_DATA + SAMPLE_TOOL_MAJOR];
    }
    
    /**
     * Returns the length of the minor axis of an ellipse that describes the size of
     * the approaching tool for the given pointer
     * <em>index</em> (use {@link #getPointerId(int)} to find the pointer
     * identifier for this index).
     * The tool area represents the estimated size of the finger or pen that is
     * touching the device independent of its actual touch area at the point of contact.
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     */
    public final float getToolMinor(int pointerIndex) {
        return mDataSamples[mLastDataSampleIndex
                            + pointerIndex * NUM_SAMPLE_DATA + SAMPLE_TOOL_MINOR];
    }
    
    /**
     * Returns the orientation of the touch area and tool area in radians clockwise from vertical
     * for the given pointer <em>index</em> (use {@link #getPointerId(int)} to find the pointer
     * identifier for this index).
     * An angle of 0 degrees indicates that the major axis of contact is oriented
     * upwards, is perfectly circular or is of unknown orientation.  A positive angle
     * indicates that the major axis of contact is oriented to the right.  A negative angle
     * indicates that the major axis of contact is oriented to the left.
     * The full range is from -PI/4 radians (finger pointing fully left) to PI/4 radians
     * (finger pointing fully right).
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     */
    public final float getOrientation(int pointerIndex) {
        return mDataSamples[mLastDataSampleIndex
                            + pointerIndex * NUM_SAMPLE_DATA + SAMPLE_ORIENTATION];
    }
    
    /**
     * Populates a {@link PointerCoords} object with pointer coordinate data for
     * the specified pointer index.
     * 
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     * @param outPointerCoords The pointer coordinate object to populate.
     */
    public final void getPointerCoords(int pointerIndex, PointerCoords outPointerCoords) {
        final int sampleIndex = mLastDataSampleIndex + pointerIndex * NUM_SAMPLE_DATA;
        getPointerCoordsAtSampleIndex(sampleIndex, outPointerCoords);
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
        return mDataSamples[mLastDataSampleIndex + SAMPLE_X];
    }
    
    /**
     * Returns the original raw Y coordinate of this event.  For touch
     * events on the screen, this is the original location of the event
     * on the screen, before it had been adjusted for the containing window
     * and views.
     */
    public final float getRawY() {
        return mDataSamples[mLastDataSampleIndex + SAMPLE_Y];
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
        return mLastEventTimeNanoSampleIndex;
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
        return mEventTimeNanoSamples[pos] / MS_PER_NS;
    }

    /**
     * {@link #getHistoricalX(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getHistoricalX(int pos) {
        return mDataSamples[pos * mNumPointers * NUM_SAMPLE_DATA + SAMPLE_X] + mXOffset;
    }

    /**
     * {@link #getHistoricalY(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getHistoricalY(int pos) {
        return mDataSamples[pos * mNumPointers * NUM_SAMPLE_DATA + SAMPLE_Y] + mYOffset;
    }

    /**
     * {@link #getHistoricalPressure(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getHistoricalPressure(int pos) {
        return mDataSamples[pos * mNumPointers * NUM_SAMPLE_DATA + SAMPLE_PRESSURE];
    }

    /**
     * {@link #getHistoricalSize(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getHistoricalSize(int pos) {
        return mDataSamples[pos * mNumPointers * NUM_SAMPLE_DATA + SAMPLE_SIZE];
    }

    /**
     * {@link #getHistoricalTouchMajor(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getHistoricalTouchMajor(int pos) {
        return mDataSamples[pos * mNumPointers * NUM_SAMPLE_DATA + SAMPLE_TOUCH_MAJOR];
    }

    /**
     * {@link #getHistoricalTouchMinor(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getHistoricalTouchMinor(int pos) {
        return mDataSamples[pos * mNumPointers * NUM_SAMPLE_DATA + SAMPLE_TOUCH_MINOR];
    }
    
    /**
     * {@link #getHistoricalToolMajor(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getHistoricalToolMajor(int pos) {
        return mDataSamples[pos * mNumPointers * NUM_SAMPLE_DATA + SAMPLE_TOOL_MAJOR];
    }

    /**
     * {@link #getHistoricalToolMinor(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getHistoricalToolMinor(int pos) {
        return mDataSamples[pos * mNumPointers * NUM_SAMPLE_DATA + SAMPLE_TOOL_MINOR];
    }
    
    /**
     * {@link #getHistoricalOrientation(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getHistoricalOrientation(int pos) {
        return mDataSamples[pos * mNumPointers * NUM_SAMPLE_DATA + SAMPLE_ORIENTATION];
    }
    
    /**
     * Returns a historical X coordinate, as per {@link #getX(int)}, that
     * occurred between this event and the previous event for the given pointer.
     * Only applies to ACTION_MOVE events.
     *
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     *
     * @see #getHistorySize
     * @see #getX
     */
    public final float getHistoricalX(int pointerIndex, int pos) {
        return mDataSamples[(pos * mNumPointers + pointerIndex)
                            * NUM_SAMPLE_DATA + SAMPLE_X] + mXOffset;
    }

    /**
     * Returns a historical Y coordinate, as per {@link #getY(int)}, that
     * occurred between this event and the previous event for the given pointer.
     * Only applies to ACTION_MOVE events.
     *
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     *
     * @see #getHistorySize
     * @see #getY
     */
    public final float getHistoricalY(int pointerIndex, int pos) {
        return mDataSamples[(pos * mNumPointers + pointerIndex)
                            * NUM_SAMPLE_DATA + SAMPLE_Y] + mYOffset;
    }

    /**
     * Returns a historical pressure coordinate, as per {@link #getPressure(int)},
     * that occurred between this event and the previous event for the given
     * pointer.  Only applies to ACTION_MOVE events.
     *
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     * 
     * @see #getHistorySize
     * @see #getPressure
     */
    public final float getHistoricalPressure(int pointerIndex, int pos) {
        return mDataSamples[(pos * mNumPointers + pointerIndex)
                            * NUM_SAMPLE_DATA + SAMPLE_PRESSURE];
    }

    /**
     * Returns a historical size coordinate, as per {@link #getSize(int)}, that
     * occurred between this event and the previous event for the given pointer.
     * Only applies to ACTION_MOVE events.
     *
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     * 
     * @see #getHistorySize
     * @see #getSize
     */
    public final float getHistoricalSize(int pointerIndex, int pos) {
        return mDataSamples[(pos * mNumPointers + pointerIndex)
                            * NUM_SAMPLE_DATA + SAMPLE_SIZE];
    }
    
    /**
     * Returns a historical touch major axis coordinate, as per {@link #getTouchMajor(int)}, that
     * occurred between this event and the previous event for the given pointer.
     * Only applies to ACTION_MOVE events.
     *
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     * 
     * @see #getHistorySize
     * @see #getTouchMajor
     */
    public final float getHistoricalTouchMajor(int pointerIndex, int pos) {
        return mDataSamples[(pos * mNumPointers + pointerIndex)
                            * NUM_SAMPLE_DATA + SAMPLE_TOUCH_MAJOR];
    }

    /**
     * Returns a historical touch minor axis coordinate, as per {@link #getTouchMinor(int)}, that
     * occurred between this event and the previous event for the given pointer.
     * Only applies to ACTION_MOVE events.
     *
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     * 
     * @see #getHistorySize
     * @see #getTouchMinor
     */
    public final float getHistoricalTouchMinor(int pointerIndex, int pos) {
        return mDataSamples[(pos * mNumPointers + pointerIndex)
                            * NUM_SAMPLE_DATA + SAMPLE_TOUCH_MINOR];
    }

    /**
     * Returns a historical tool major axis coordinate, as per {@link #getToolMajor(int)}, that
     * occurred between this event and the previous event for the given pointer.
     * Only applies to ACTION_MOVE events.
     *
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     * 
     * @see #getHistorySize
     * @see #getToolMajor
     */
    public final float getHistoricalToolMajor(int pointerIndex, int pos) {
        return mDataSamples[(pos * mNumPointers + pointerIndex)
                            * NUM_SAMPLE_DATA + SAMPLE_TOOL_MAJOR];
    }

    /**
     * Returns a historical tool minor axis coordinate, as per {@link #getToolMinor(int)}, that
     * occurred between this event and the previous event for the given pointer.
     * Only applies to ACTION_MOVE events.
     *
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     * 
     * @see #getHistorySize
     * @see #getToolMinor
     */
    public final float getHistoricalToolMinor(int pointerIndex, int pos) {
        return mDataSamples[(pos * mNumPointers + pointerIndex)
                            * NUM_SAMPLE_DATA + SAMPLE_TOOL_MINOR];
    }

    /**
     * Returns a historical orientation coordinate, as per {@link #getOrientation(int)}, that
     * occurred between this event and the previous event for the given pointer.
     * Only applies to ACTION_MOVE events.
     *
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     * 
     * @see #getHistorySize
     * @see #getOrientation
     */
    public final float getHistoricalOrientation(int pointerIndex, int pos) {
        return mDataSamples[(pos * mNumPointers + pointerIndex)
                            * NUM_SAMPLE_DATA + SAMPLE_ORIENTATION];
    }

    /**
     * Populates a {@link PointerCoords} object with historical pointer coordinate data,
     * as per {@link #getPointerCoords}, that occurred between this event and the previous
     * event for the given pointer.
     * Only applies to ACTION_MOVE events.
     * 
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     * @param outPointerCoords The pointer coordinate object to populate.
     * 
     * @see #getHistorySize
     * @see #getPointerCoords
     */
    public final void getHistoricalPointerCoords(int pointerIndex, int pos,
            PointerCoords outPointerCoords) {
        final int sampleIndex = (pos * mNumPointers + pointerIndex) * NUM_SAMPLE_DATA;
        getPointerCoordsAtSampleIndex(sampleIndex, outPointerCoords);
    }
    
    /**
     * Returns a bitfield indicating which edges, if any, were touched by this
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
        mXOffset += deltaX;
        mYOffset += deltaY;
    }

    /**
     * Set this event's location.  Applies {@link #offsetLocation} with a
     * delta from the current location to the given new location.
     *
     * @param x New absolute X location.
     * @param y New absolute Y location.
     */
    public final void setLocation(float x, float y) {
        mXOffset = x - mDataSamples[mLastDataSampleIndex + SAMPLE_X];
        mYOffset = y - mDataSamples[mLastDataSampleIndex + SAMPLE_Y];
    }
    
    private final void getPointerCoordsAtSampleIndex(int sampleIndex,
            PointerCoords outPointerCoords) {
        outPointerCoords.x = mDataSamples[sampleIndex + SAMPLE_X] + mXOffset;
        outPointerCoords.y = mDataSamples[sampleIndex + SAMPLE_Y] + mYOffset;
        outPointerCoords.pressure = mDataSamples[sampleIndex + SAMPLE_PRESSURE];
        outPointerCoords.size = mDataSamples[sampleIndex + SAMPLE_SIZE];
        outPointerCoords.touchMajor = mDataSamples[sampleIndex + SAMPLE_TOUCH_MAJOR];
        outPointerCoords.touchMinor = mDataSamples[sampleIndex + SAMPLE_TOUCH_MINOR];
        outPointerCoords.toolMajor = mDataSamples[sampleIndex + SAMPLE_TOOL_MAJOR];
        outPointerCoords.toolMinor = mDataSamples[sampleIndex + SAMPLE_TOOL_MINOR];
        outPointerCoords.orientation = mDataSamples[sampleIndex + SAMPLE_ORIENTATION];
    }
    
    private final void setPointerCoordsAtSampleIndex(int sampleIndex,
            PointerCoords[] pointerCoords) {
        final int numPointers = mNumPointers;
        for (int i = 0; i < numPointers; i++) {
            setPointerCoordsAtSampleIndex(sampleIndex, pointerCoords[i]);
            sampleIndex += NUM_SAMPLE_DATA;
        }
    }
    
    private final void setPointerCoordsAtSampleIndex(int sampleIndex,
            PointerCoords pointerCoords) {
        mDataSamples[sampleIndex + SAMPLE_X] = pointerCoords.x - mXOffset;
        mDataSamples[sampleIndex + SAMPLE_Y] = pointerCoords.y - mYOffset;
        mDataSamples[sampleIndex + SAMPLE_PRESSURE] = pointerCoords.pressure;
        mDataSamples[sampleIndex + SAMPLE_SIZE] = pointerCoords.size;
        mDataSamples[sampleIndex + SAMPLE_TOUCH_MAJOR] = pointerCoords.touchMajor;
        mDataSamples[sampleIndex + SAMPLE_TOUCH_MINOR] = pointerCoords.touchMinor;
        mDataSamples[sampleIndex + SAMPLE_TOOL_MAJOR] = pointerCoords.toolMajor;
        mDataSamples[sampleIndex + SAMPLE_TOOL_MINOR] = pointerCoords.toolMinor;
        mDataSamples[sampleIndex + SAMPLE_ORIENTATION] = pointerCoords.orientation;
    }
    
    private final void setPointerCoordsAtSampleIndex(int sampleIndex,
            float x, float y, float pressure, float size) {
        mDataSamples[sampleIndex + SAMPLE_X] = x - mXOffset;
        mDataSamples[sampleIndex + SAMPLE_Y] = y - mYOffset;
        mDataSamples[sampleIndex + SAMPLE_PRESSURE] = pressure;
        mDataSamples[sampleIndex + SAMPLE_SIZE] = size;
        mDataSamples[sampleIndex + SAMPLE_TOUCH_MAJOR] = pressure;
        mDataSamples[sampleIndex + SAMPLE_TOUCH_MINOR] = pressure;
        mDataSamples[sampleIndex + SAMPLE_TOOL_MAJOR] = size;
        mDataSamples[sampleIndex + SAMPLE_TOOL_MINOR] = size;
        mDataSamples[sampleIndex + SAMPLE_ORIENTATION] = 0;
    }
    
    private final void incrementNumSamplesAndReserveStorage(int dataSampleStride) {
        if (mNumSamples == mEventTimeNanoSamples.length) {
            long[] newEventTimeNanoSamples = new long[mNumSamples + BASE_AVAIL_SAMPLES];
            System.arraycopy(mEventTimeNanoSamples, 0, newEventTimeNanoSamples, 0, mNumSamples);
            mEventTimeNanoSamples = newEventTimeNanoSamples;
        }
        
        int nextDataSampleIndex = mLastDataSampleIndex + dataSampleStride;
        if (nextDataSampleIndex + dataSampleStride > mDataSamples.length) {
            float[] newDataSamples = new float[nextDataSampleIndex
                                               + BASE_AVAIL_SAMPLES * dataSampleStride];
            System.arraycopy(mDataSamples, 0, newDataSamples, 0, nextDataSampleIndex);
            mDataSamples = newDataSamples;
        }
        
        mLastEventTimeNanoSampleIndex = mNumSamples;
        mLastDataSampleIndex = nextDataSampleIndex;
        mNumSamples += 1;
    }

    /**
     * Add a new movement to the batch of movements in this event.  The event's
     * current location, position and size is updated to the new values.
     * The current values in the event are added to a list of historical values.
     * 
     * Only applies to {@link #ACTION_MOVE} events.
     *
     * @param eventTime The time stamp (in ms) for this data.
     * @param x The new X position.
     * @param y The new Y position.
     * @param pressure The new pressure.
     * @param size The new size.
     * @param metaState Meta key state.
     */
    public final void addBatch(long eventTime, float x, float y,
            float pressure, float size, int metaState) {
        incrementNumSamplesAndReserveStorage(NUM_SAMPLE_DATA);
        
        mEventTimeNanoSamples[mLastEventTimeNanoSampleIndex] = eventTime * MS_PER_NS;
        setPointerCoordsAtSampleIndex(mLastDataSampleIndex, x, y, pressure, size);
        
        mMetaState |= metaState;
    }

    /**
     * Add a new movement to the batch of movements in this event.  The event's
     * current location, position and size is updated to the new values.
     * The current values in the event are added to a list of historical values.
     * 
     * Only applies to {@link #ACTION_MOVE} events.
     *
     * @param eventTime The time stamp (in ms) for this data.
     * @param pointerCoords The new pointer coordinates.
     * @param metaState Meta key state.
     */
    public final void addBatch(long eventTime, PointerCoords[] pointerCoords, int metaState) {
        final int dataSampleStride = mNumPointers * NUM_SAMPLE_DATA;
        incrementNumSamplesAndReserveStorage(dataSampleStride);
        
        mEventTimeNanoSamples[mLastEventTimeNanoSampleIndex] = eventTime * MS_PER_NS;
        setPointerCoordsAtSampleIndex(mLastDataSampleIndex, pointerCoords);
        
        mMetaState |= metaState;
    }

    @Override
    public String toString() {
        return "MotionEvent{" + Integer.toHexString(System.identityHashCode(this))
            + " action=" + mAction + " x=" + getX()
            + " y=" + getY() + " pressure=" + getPressure() + " size=" + getSize() + "}";
    }

    public static final Parcelable.Creator<MotionEvent> CREATOR
            = new Parcelable.Creator<MotionEvent>() {
        public MotionEvent createFromParcel(Parcel in) {
            final int NP = in.readInt();
            final int NS = in.readInt();
            final int NI = NP * NS * NUM_SAMPLE_DATA;
            
            MotionEvent ev = obtain(NP, NS);
            ev.mNumPointers = NP;
            ev.mNumSamples = NS;
            
            ev.mDownTimeNano = in.readLong();
            ev.mAction = in.readInt();
            ev.mXOffset = in.readFloat();
            ev.mYOffset = in.readFloat();
            ev.mXPrecision = in.readFloat();
            ev.mYPrecision = in.readFloat();
            ev.mDeviceId = in.readInt();
            ev.mSource = in.readInt();
            ev.mEdgeFlags = in.readInt();
            ev.mMetaState = in.readInt();
            
            final int[] pointerIdentifiers = ev.mPointerIdentifiers;
            for (int i = 0; i < NP; i++) {
                pointerIdentifiers[i] = in.readInt();
            }
            
            final long[] eventTimeNanoSamples = ev.mEventTimeNanoSamples;
            for (int i = 0; i < NS; i++) {
                eventTimeNanoSamples[i] = in.readLong();
            }

            final float[] dataSamples = ev.mDataSamples;
            for (int i = 0; i < NI; i++) {
                dataSamples[i] = in.readFloat();
            }
            
            ev.mLastEventTimeNanoSampleIndex = NS - 1;
            ev.mLastDataSampleIndex = (NS - 1) * NP * NUM_SAMPLE_DATA;
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
        final int NP = mNumPointers;
        final int NS = mNumSamples;
        final int NI = NP * NS * NUM_SAMPLE_DATA;
        
        out.writeInt(NP);
        out.writeInt(NS);
        
        out.writeLong(mDownTimeNano);
        out.writeInt(mAction);
        out.writeFloat(mXOffset);
        out.writeFloat(mYOffset);
        out.writeFloat(mXPrecision);
        out.writeFloat(mYPrecision);
        out.writeInt(mDeviceId);
        out.writeInt(mSource);
        out.writeInt(mEdgeFlags);
        out.writeInt(mMetaState);
        
        final int[] pointerIdentifiers = mPointerIdentifiers;
        for (int i = 0; i < NP; i++) {
            out.writeInt(pointerIdentifiers[i]);
        }
        
        final long[] eventTimeNanoSamples = mEventTimeNanoSamples;
        for (int i = 0; i < NS; i++) {
            out.writeLong(eventTimeNanoSamples[i]);
        }

        final float[] dataSamples = mDataSamples;
        for (int i = 0; i < NI; i++) {
            out.writeFloat(dataSamples[i]);
        }
    }
    
    /**
     * Transfer object for pointer coordinates.
     * 
     * Objects of this type can be used to manufacture new {@link MotionEvent} objects
     * and to query pointer coordinate information in bulk.
     * 
     * Refer to {@link InputDevice} for information about how different kinds of
     * input devices and sources represent pointer coordinates.
     */
    public static final class PointerCoords {
        /**
         * The X coordinate of the pointer movement.
         * The interpretation varies by input source and may represent the position of
         * the center of the contact area, a relative displacement in device-specific units
         * or something else.
         */
        public float x;
        
        /**
         * The Y coordinate of the pointer movement.
         * The interpretation varies by input source and may represent the position of
         * the center of the contact area, a relative displacement in device-specific units
         * or something else.
         */
        public float y;
        
        /**
         * A scaled value that describes the pressure applied to the pointer.
         * The pressure generally ranges from 0 (no pressure at all) to 1 (normal pressure),
         * however values higher than 1 may be generated depending on the calibration of
         * the input device.
         */
        public float pressure;
        
        /**
         * A scaled value of the approximate size of the pointer touch area.
         * This represents some approximation of the area of the screen being
         * pressed; the actual value in pixels corresponding to the
         * touch is normalized with the device specific range of values
         * and scaled to a value between 0 and 1. The value of size can be used to
         * determine fat touch events.
         */
        public float size;
        
        /**
         * The length of the major axis of an ellipse that describes the touch area at
         * the point of contact.
         */
        public float touchMajor;
        
        /**
         * The length of the minor axis of an ellipse that describes the touch area at
         * the point of contact.
         */
        public float touchMinor;
        
        /**
         * The length of the major axis of an ellipse that describes the size of
         * the approaching tool.
         * The tool area represents the estimated size of the finger or pen that is
         * touching the device independent of its actual touch area at the point of contact.
         */
        public float toolMajor;
        
        /**
         * The length of the minor axis of an ellipse that describes the size of
         * the approaching tool.
         * The tool area represents the estimated size of the finger or pen that is
         * touching the device independent of its actual touch area at the point of contact.
         */
        public float toolMinor;
        
        /**
         * The orientation of the touch area and tool area in radians clockwise from vertical.
         * An angle of 0 degrees indicates that the major axis of contact is oriented
         * upwards, is perfectly circular or is of unknown orientation.  A positive angle
         * indicates that the major axis of contact is oriented to the right.  A negative angle
         * indicates that the major axis of contact is oriented to the left.
         * The full range is from -PI/4 radians (finger pointing fully left) to PI/4 radians
         * (finger pointing fully right).
         */
        public float orientation;
        
        /*
        private static final float PI_8 = (float) (Math.PI / 8);
        
        public float getTouchWidth() {
            return Math.abs(orientation) > PI_8 ? touchMajor : touchMinor;
        }
        
        public float getTouchHeight() {
            return Math.abs(orientation) > PI_8 ? touchMinor : touchMajor;
        }
        
        public float getToolWidth() {
            return Math.abs(orientation) > PI_8 ? toolMajor : toolMinor;
        }
        
        public float getToolHeight() {
            return Math.abs(orientation) > PI_8 ? toolMinor : toolMajor;
        }
        */
    }
}
