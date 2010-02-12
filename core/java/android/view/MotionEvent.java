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
import android.util.Log;

/**
 * Object used to report movement (mouse, pen, finger, trackball) events.  This
 * class may hold either absolute or relative movements, depending on what
 * it is being used for.
 */
public final class MotionEvent implements Parcelable {
    static final boolean DEBUG_POINTERS = false;
    
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
     * Offset for the sample's X coordinate.
     * @hide
     */
    static public final int SAMPLE_PRESSURE = 2;
    
    /**
     * Offset for the sample's X coordinate.
     * @hide
     */
    static public final int SAMPLE_SIZE = 3;
    
    /**
     * Number of data items for each sample.
     * @hide
     */
    static public final int NUM_SAMPLE_DATA = 4;
    
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

    private long mDownTime;
    private long mEventTimeNano;
    private int mAction;
    private float mRawX;
    private float mRawY;
    private float mXPrecision;
    private float mYPrecision;
    private int mDeviceId;
    private int mEdgeFlags;
    private int mMetaState;
    
    // Here is the actual event data.  Note that the order of the array
    // is a little odd: the first entry is the most recent, and the ones
    // following it are the historical data from oldest to newest.  This
    // allows us to easily retrieve the most recent data, without having
    // to copy the arrays every time a new sample is added.
    
    private int mNumPointers;
    private int mNumSamples;
    // Array of mNumPointers size of identifiers for each pointer of data.
    private int[] mPointerIdentifiers;
    // Array of (mNumSamples * mNumPointers * NUM_SAMPLE_DATA) size of event data.
    private float[] mDataSamples;
    // Array of mNumSamples size of time stamps.
    private long[] mTimeSamples;

    private MotionEvent mNext;
    private RuntimeException mRecycledLocation;
    private boolean mRecycled;

    private MotionEvent() {
        mPointerIdentifiers = new int[BASE_AVAIL_POINTERS];
        mDataSamples = new float[BASE_AVAIL_POINTERS*BASE_AVAIL_SAMPLES*NUM_SAMPLE_DATA];
        mTimeSamples = new long[BASE_AVAIL_SAMPLES];
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
     * @param eventTimeNano  The the time (in ns) when this specific event was generated.  This 
     * must be obtained from {@link System#nanoTime()}.
     * @param action The kind of action being performed -- one of either
     * {@link #ACTION_DOWN}, {@link #ACTION_MOVE}, {@link #ACTION_UP}, or
     * {@link #ACTION_CANCEL}.
     * @param pointers The number of points that will be in this event.
     * @param inPointerIds An array of <em>pointers</em> values providing
     * an identifier for each pointer.
     * @param inData An array of <em>pointers*NUM_SAMPLE_DATA</em> of initial
     * data samples for the event.
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
     * @hide
     */
    static public MotionEvent obtainNano(long downTime, long eventTime, long eventTimeNano,
            int action, int pointers, int[] inPointerIds, float[] inData, int metaState,
            float xPrecision, float yPrecision, int deviceId, int edgeFlags) {
        MotionEvent ev = obtain();
        ev.mDeviceId = deviceId;
        ev.mEdgeFlags = edgeFlags;
        ev.mDownTime = downTime;
        ev.mEventTimeNano = eventTimeNano;
        ev.mAction = action;
        ev.mMetaState = metaState;
        ev.mRawX = inData[SAMPLE_X];
        ev.mRawY = inData[SAMPLE_Y];
        ev.mXPrecision = xPrecision;
        ev.mYPrecision = yPrecision;
        ev.mNumPointers = pointers;
        ev.mNumSamples = 1;
        
        int[] pointerIdentifiers = ev.mPointerIdentifiers;
        if (pointerIdentifiers.length < pointers) {
            ev.mPointerIdentifiers = pointerIdentifiers = new int[pointers];
        }
        System.arraycopy(inPointerIds, 0, pointerIdentifiers, 0, pointers);
        
        final int ND = pointers * NUM_SAMPLE_DATA;
        float[] dataSamples = ev.mDataSamples;
        if (dataSamples.length < ND) {
            ev.mDataSamples = dataSamples = new float[ND];
        }
        System.arraycopy(inData, 0, dataSamples, 0, ND);
        
        ev.mTimeSamples[0] = eventTime;

        if (DEBUG_POINTERS) {
            StringBuilder sb = new StringBuilder(128);
            sb.append("New:");
            for (int i=0; i<pointers; i++) {
                sb.append(" #");
                sb.append(ev.mPointerIdentifiers[i]);
                sb.append("(");
                sb.append(ev.mDataSamples[(i*NUM_SAMPLE_DATA) + SAMPLE_X]);
                sb.append(",");
                sb.append(ev.mDataSamples[(i*NUM_SAMPLE_DATA) + SAMPLE_Y]);
                sb.append(")");
            }
            Log.v("MotionEvent", sb.toString());
        }
        
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
        MotionEvent ev = obtain();
        ev.mDeviceId = deviceId;
        ev.mEdgeFlags = edgeFlags;
        ev.mDownTime = downTime;
        ev.mEventTimeNano = eventTime * 1000000;
        ev.mAction = action;
        ev.mMetaState = metaState;
        ev.mXPrecision = xPrecision;
        ev.mYPrecision = yPrecision;

        ev.mNumPointers = 1;
        ev.mNumSamples = 1;
        int[] pointerIds = ev.mPointerIdentifiers;
        pointerIds[0] = 0;
        float[] data = ev.mDataSamples;
        data[SAMPLE_X] = ev.mRawX = x;
        data[SAMPLE_Y] = ev.mRawY = y;
        data[SAMPLE_PRESSURE] = pressure;
        data[SAMPLE_SIZE] = size;
        ev.mTimeSamples[0] = eventTime;

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
     */
    static public MotionEvent obtain(long downTime, long eventTime, int action,
            int pointers, float x, float y, float pressure, float size, int metaState,
            float xPrecision, float yPrecision, int deviceId, int edgeFlags) {
        MotionEvent ev = obtain();
        ev.mDeviceId = deviceId;
        ev.mEdgeFlags = edgeFlags;
        ev.mDownTime = downTime;
        ev.mEventTimeNano = eventTime * 1000000;
        ev.mAction = action;
        ev.mNumPointers = pointers;
        ev.mMetaState = metaState;
        ev.mXPrecision = xPrecision;
        ev.mYPrecision = yPrecision;

        ev.mNumPointers = 1;
        ev.mNumSamples = 1;
        int[] pointerIds = ev.mPointerIdentifiers;
        pointerIds[0] = 0;
        float[] data = ev.mDataSamples;
        data[SAMPLE_X] = ev.mRawX = x;
        data[SAMPLE_Y] = ev.mRawY = y;
        data[SAMPLE_PRESSURE] = pressure;
        data[SAMPLE_SIZE] = size;
        ev.mTimeSamples[0] = eventTime;

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
        ev.mEventTimeNano = eventTime * 1000000;
        ev.mAction = action;
        ev.mNumPointers = 1;
        ev.mMetaState = metaState;
        ev.mXPrecision = 1.0f;
        ev.mYPrecision = 1.0f;

        ev.mNumPointers = 1;
        ev.mNumSamples = 1;
        int[] pointerIds = ev.mPointerIdentifiers;
        pointerIds[0] = 0;
        float[] data = ev.mDataSamples;
        data[SAMPLE_X] = ev.mRawX = x;
        data[SAMPLE_Y] = ev.mRawY = y;
        data[SAMPLE_PRESSURE] = 1.0f;
        data[SAMPLE_SIZE] = 1.0f;
        ev.mTimeSamples[0] = eventTime;

        return ev;
    }

    /**
     * Scales down the coordination of this event by the given scale.
     *
     * @hide
     */
    public void scale(float scale) {
        mRawX *= scale;
        mRawY *= scale;
        mXPrecision *= scale;
        mYPrecision *= scale;
        float[] history = mDataSamples;
        final int length = mNumPointers * mNumSamples * NUM_SAMPLE_DATA;
        for (int i = 0; i < length; i += NUM_SAMPLE_DATA) {
            history[i + SAMPLE_X] *= scale;
            history[i + SAMPLE_Y] *= scale;
            // no need to scale pressure
            history[i + SAMPLE_SIZE] *= scale;    // TODO: square this?
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
        ev.mEventTimeNano = o.mEventTimeNano;
        ev.mAction = o.mAction;
        ev.mNumPointers = o.mNumPointers;
        ev.mRawX = o.mRawX;
        ev.mRawY = o.mRawY;
        ev.mMetaState = o.mMetaState;
        ev.mXPrecision = o.mXPrecision;
        ev.mYPrecision = o.mYPrecision;
        
        final int NS = ev.mNumSamples = o.mNumSamples;
        if (ev.mTimeSamples.length >= NS) {
            System.arraycopy(o.mTimeSamples, 0, ev.mTimeSamples, 0, NS);
        } else {
            ev.mTimeSamples = (long[])o.mTimeSamples.clone();
        }
        
        final int NP = (ev.mNumPointers=o.mNumPointers);
        if (ev.mPointerIdentifiers.length >= NP) {
            System.arraycopy(o.mPointerIdentifiers, 0, ev.mPointerIdentifiers, 0, NP);
        } else {
            ev.mPointerIdentifiers = (int[])o.mPointerIdentifiers.clone();
        }
        
        final int ND = NP * NS * NUM_SAMPLE_DATA;
        if (ev.mDataSamples.length >= ND) {
            System.arraycopy(o.mDataSamples, 0, ev.mDataSamples, 0, ND);
        } else {
            ev.mDataSamples = (float[])o.mDataSamples.clone();
        }
        
        return ev;
    }

    /**
     * Create a new MotionEvent, copying from an existing one, but not including
     * any historical point information.
     */
    static public MotionEvent obtainNoHistory(MotionEvent o) {
        MotionEvent ev = obtain();
        ev.mDeviceId = o.mDeviceId;
        ev.mEdgeFlags = o.mEdgeFlags;
        ev.mDownTime = o.mDownTime;
        ev.mEventTimeNano = o.mEventTimeNano;
        ev.mAction = o.mAction;
        ev.mNumPointers = o.mNumPointers;
        ev.mRawX = o.mRawX;
        ev.mRawY = o.mRawY;
        ev.mMetaState = o.mMetaState;
        ev.mXPrecision = o.mXPrecision;
        ev.mYPrecision = o.mYPrecision;
        
        ev.mNumSamples = 1;
        ev.mTimeSamples[0] = o.mTimeSamples[0];
        
        final int NP = (ev.mNumPointers=o.mNumPointers);
        if (ev.mPointerIdentifiers.length >= NP) {
            System.arraycopy(o.mPointerIdentifiers, 0, ev.mPointerIdentifiers, 0, NP);
        } else {
            ev.mPointerIdentifiers = (int[])o.mPointerIdentifiers.clone();
        }
        
        final int ND = NP * NUM_SAMPLE_DATA;
        if (ev.mDataSamples.length >= ND) {
            System.arraycopy(o.mDataSamples, 0, ev.mDataSamples, 0, ND);
        } else {
            ev.mDataSamples = (float[])o.mDataSamples.clone();
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
                mNumSamples = 0;
                mNext = gRecyclerTop;
                gRecyclerTop = this;
            }
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
        return mDownTime;
    }

    /**
     * Returns the time (in ms) when this specific event was generated.
     */
    public final long getEventTime() {
        return mTimeSamples[0];
    }

    /**
     * Returns the time (in ns) when this specific event was generated.
     * The value is in nanosecond precision but it may not have nanosecond accuracy.
     *
     * @hide
     */
    public final long getEventTimeNano() {
        return mEventTimeNano;
    }

    /**
     * {@link #getX(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getX() {
        return mDataSamples[SAMPLE_X];
    }

    /**
     * {@link #getY(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getY() {
        return mDataSamples[SAMPLE_Y];
    }

    /**
     * {@link #getPressure(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getPressure() {
        return mDataSamples[SAMPLE_PRESSURE];
    }

    /**
     * {@link #getSize(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getSize() {
        return mDataSamples[SAMPLE_SIZE];
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
     * {@link #getX(int) et al.), or -1 if there is no data available for
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
        return mDataSamples[(pointerIndex*NUM_SAMPLE_DATA) + SAMPLE_X];
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
        return mDataSamples[(pointerIndex*NUM_SAMPLE_DATA) + SAMPLE_Y];
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
        return mDataSamples[(pointerIndex*NUM_SAMPLE_DATA) + SAMPLE_PRESSURE];
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
        return mDataSamples[(pointerIndex*NUM_SAMPLE_DATA) + SAMPLE_SIZE];
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
        return mNumSamples - 1;
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
        return mTimeSamples[pos + 1];
    }

    /**
     * {@link #getHistoricalX(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getHistoricalX(int pos) {
        return mDataSamples[((pos + 1) * NUM_SAMPLE_DATA * mNumPointers) + SAMPLE_X];
    }

    /**
     * {@link #getHistoricalY(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getHistoricalY(int pos) {
        return mDataSamples[((pos + 1) * NUM_SAMPLE_DATA * mNumPointers) + SAMPLE_Y];
    }

    /**
     * {@link #getHistoricalPressure(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getHistoricalPressure(int pos) {
        return mDataSamples[((pos + 1) * NUM_SAMPLE_DATA * mNumPointers) + SAMPLE_PRESSURE];
    }

    /**
     * {@link #getHistoricalSize(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     */
    public final float getHistoricalSize(int pos) {
        return mDataSamples[((pos + 1) * NUM_SAMPLE_DATA * mNumPointers) + SAMPLE_SIZE];
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
        return mDataSamples[((pos + 1) * NUM_SAMPLE_DATA * mNumPointers)
                            + (pointerIndex * NUM_SAMPLE_DATA) + SAMPLE_X];
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
        return mDataSamples[((pos + 1) * NUM_SAMPLE_DATA * mNumPointers)
                            + (pointerIndex * NUM_SAMPLE_DATA) + SAMPLE_Y];
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
        return mDataSamples[((pos + 1) * NUM_SAMPLE_DATA * mNumPointers)
                            + (pointerIndex * NUM_SAMPLE_DATA) + SAMPLE_PRESSURE];
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
        return mDataSamples[((pos + 1) * NUM_SAMPLE_DATA * mNumPointers)
                            + (pointerIndex * NUM_SAMPLE_DATA) + SAMPLE_SIZE];
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
        final int N = mNumPointers*mNumSamples*4;
        final float[] pos = mDataSamples;
        for (int i=0; i<N; i+=NUM_SAMPLE_DATA) {
            pos[i+SAMPLE_X] += deltaX;
            pos[i+SAMPLE_Y] += deltaY;
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
        float deltaX = x-mDataSamples[SAMPLE_X];
        float deltaY = y-mDataSamples[SAMPLE_Y];
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
     * @param eventTime The time stamp for this data.
     * @param x The new X position.
     * @param y The new Y position.
     * @param pressure The new pressure.
     * @param size The new size.
     * @param metaState Meta key state.
     */
    public final void addBatch(long eventTime, float x, float y,
            float pressure, float size, int metaState) {
        float[] data = mDataSamples;
        long[] times = mTimeSamples;
        
        final int NP = mNumPointers;
        final int NS = mNumSamples;
        final int NI = NP*NS;
        final int ND = NI * NUM_SAMPLE_DATA;
        if (data.length <= ND) {
            final int NEW_ND = ND + (NP * (BASE_AVAIL_SAMPLES * NUM_SAMPLE_DATA));
            float[] newData = new float[NEW_ND];
            System.arraycopy(data, 0, newData, 0, ND);
            mDataSamples = data = newData;
        }
        if (times.length <= NS) {
            final int NEW_NS = NS + BASE_AVAIL_SAMPLES;
            long[] newHistoryTimes = new long[NEW_NS];
            System.arraycopy(times, 0, newHistoryTimes, 0, NS);
            mTimeSamples = times = newHistoryTimes;
        }
        
        times[NS] = times[0];
        times[0] = eventTime;
        
        final int pos = NS*NUM_SAMPLE_DATA;
        data[pos+SAMPLE_X] = data[SAMPLE_X];
        data[pos+SAMPLE_Y] = data[SAMPLE_Y];
        data[pos+SAMPLE_PRESSURE] = data[SAMPLE_PRESSURE];
        data[pos+SAMPLE_SIZE] = data[SAMPLE_SIZE];
        data[SAMPLE_X] = x;
        data[SAMPLE_Y] = y;
        data[SAMPLE_PRESSURE] = pressure;
        data[SAMPLE_SIZE] = size;
        mNumSamples = NS+1;

        mRawX = x;
        mRawY = y;
        mMetaState |= metaState;
    }

    /**
     * Add a new movement to the batch of movements in this event.  The
     * input data must contain (NUM_SAMPLE_DATA * {@link #getPointerCount()})
     * samples of data.
     *
     * @param eventTime The time stamp for this data.
     * @param inData The actual data.
     * @param metaState Meta key state.
     * 
     * @hide
     */
    public final void addBatch(long eventTime, float[] inData, int metaState) {
        float[] data = mDataSamples;
        long[] times = mTimeSamples;
        
        final int NP = mNumPointers;
        final int NS = mNumSamples;
        final int NI = NP*NS;
        final int ND = NI * NUM_SAMPLE_DATA;
        if (data.length < (ND+(NP*NUM_SAMPLE_DATA))) {
            final int NEW_ND = ND + (NP * (BASE_AVAIL_SAMPLES * NUM_SAMPLE_DATA));
            float[] newData = new float[NEW_ND];
            System.arraycopy(data, 0, newData, 0, ND);
            mDataSamples = data = newData;
        }
        if (times.length < (NS+1)) {
            final int NEW_NS = NS + BASE_AVAIL_SAMPLES;
            long[] newHistoryTimes = new long[NEW_NS];
            System.arraycopy(times, 0, newHistoryTimes, 0, NS);
            mTimeSamples = times = newHistoryTimes;
        }
        
        times[NS] = times[0];
        times[0] = eventTime;
        
        System.arraycopy(data, 0, data, ND, mNumPointers*NUM_SAMPLE_DATA);
        System.arraycopy(inData, 0, data, 0, mNumPointers*NUM_SAMPLE_DATA);
        
        mNumSamples = NS+1;

        mRawX = inData[SAMPLE_X];
        mRawY = inData[SAMPLE_Y];
        mMetaState |= metaState;
        
        if (DEBUG_POINTERS) {
            StringBuilder sb = new StringBuilder(128);
            sb.append("Add:");
            for (int i=0; i<mNumPointers; i++) {
                sb.append(" #");
                sb.append(mPointerIdentifiers[i]);
                sb.append("(");
                sb.append(mDataSamples[(i*NUM_SAMPLE_DATA) + SAMPLE_X]);
                sb.append(",");
                sb.append(mDataSamples[(i*NUM_SAMPLE_DATA) + SAMPLE_Y]);
                sb.append(")");
            }
            Log.v("MotionEvent", sb.toString());
        }
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
        out.writeLong(mEventTimeNano);
        out.writeInt(mAction);
        out.writeInt(mMetaState);
        out.writeFloat(mRawX);
        out.writeFloat(mRawY);
        final int NP = mNumPointers;
        out.writeInt(NP);
        final int NS = mNumSamples;
        out.writeInt(NS);
        final int NI = NP*NS;
        if (NI > 0) {
            int i;
            int[] state = mPointerIdentifiers;
            for (i=0; i<NP; i++) {
                out.writeInt(state[i]);
            }
            final int ND = NI*NUM_SAMPLE_DATA;
            float[] history = mDataSamples;
            for (i=0; i<ND; i++) {
                out.writeFloat(history[i]);
            }
            long[] times = mTimeSamples;
            for (i=0; i<NS; i++) {
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
        mEventTimeNano = in.readLong();
        mAction = in.readInt();
        mMetaState = in.readInt();
        mRawX = in.readFloat();
        mRawY = in.readFloat();
        final int NP = in.readInt();
        mNumPointers = NP;
        final int NS = in.readInt();
        mNumSamples = NS;
        final int NI = NP*NS;
        if (NI > 0) {
            int[] ids = mPointerIdentifiers;
            if (ids.length < NP) {
                mPointerIdentifiers = ids = new int[NP];
            }
            for (int i=0; i<NP; i++) {
                ids[i] = in.readInt();
            }
            float[] history = mDataSamples;
            final int ND = NI*NUM_SAMPLE_DATA;
            if (history.length < ND) {
                mDataSamples = history = new float[ND];
            }
            for (int i=0; i<ND; i++) {
                history[i] = in.readFloat();
            }
            long[] times = mTimeSamples;
            if (times == null || times.length < NS) {
                mTimeSamples = times = new long[NS];
            }
            for (int i=0; i<NS; i++) {
                times[i] = in.readLong();
            }
        }
        mXPrecision = in.readFloat();
        mYPrecision = in.readFloat();
        mDeviceId = in.readInt();
        mEdgeFlags = in.readInt();
    }

}
