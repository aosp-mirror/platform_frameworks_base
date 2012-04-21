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

import android.graphics.Matrix;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.SparseArray;

/**
 * Object used to report movement (mouse, pen, finger, trackball) events.
 * Motion events may hold either absolute or relative movements and other data,
 * depending on the type of device.
 *
 * <h3>Overview</h3>
 * <p>
 * Motion events describe movements in terms of an action code and a set of axis values.
 * The action code specifies the state change that occurred such as a pointer going
 * down or up.  The axis values describe the position and other movement properties.
 * </p><p>
 * For example, when the user first touches the screen, the system delivers a touch
 * event to the appropriate {@link View} with the action code {@link #ACTION_DOWN}
 * and a set of axis values that include the X and Y coordinates of the touch and
 * information about the pressure, size and orientation of the contact area.
 * </p><p>
 * Some devices can report multiple movement traces at the same time.  Multi-touch
 * screens emit one movement trace for each finger.  The individual fingers or
 * other objects that generate movement traces are referred to as <em>pointers</em>.
 * Motion events contain information about all of the pointers that are currently active
 * even if some of them have not moved since the last event was delivered.
 * </p><p>
 * The number of pointers only ever changes by one as individual pointers go up and down,
 * except when the gesture is canceled.
 * </p><p>
 * Each pointer has a unique id that is assigned when it first goes down
 * (indicated by {@link #ACTION_DOWN} or {@link #ACTION_POINTER_DOWN}).  A pointer id
 * remains valid until the pointer eventually goes up (indicated by {@link #ACTION_UP}
 * or {@link #ACTION_POINTER_UP}) or when the gesture is canceled (indicated by
 * {@link #ACTION_CANCEL}).
 * </p><p>
 * The MotionEvent class provides many methods to query the position and other properties of
 * pointers, such as {@link #getX(int)}, {@link #getY(int)}, {@link #getAxisValue},
 * {@link #getPointerId(int)}, {@link #getToolType(int)}, and many others.  Most of these
 * methods accept the pointer index as a parameter rather than the pointer id.
 * The pointer index of each pointer in the event ranges from 0 to one less than the value
 * returned by {@link #getPointerCount()}.
 * </p><p>
 * The order in which individual pointers appear within a motion event is undefined.
 * Thus the pointer index of a pointer can change from one event to the next but
 * the pointer id of a pointer is guaranteed to remain constant as long as the pointer
 * remains active.  Use the {@link #getPointerId(int)} method to obtain the
 * pointer id of a pointer to track it across all subsequent motion events in a gesture.
 * Then for successive motion events, use the {@link #findPointerIndex(int)} method
 * to obtain the pointer index for a given pointer id in that motion event.
 * </p><p>
 * Mouse and stylus buttons can be retrieved using {@link #getButtonState()}.  It is a
 * good idea to check the button state while handling {@link #ACTION_DOWN} as part
 * of a touch event.  The application may choose to perform some different action
 * if the touch event starts due to a secondary button click, such as presenting a
 * context menu.
 * </p>
 *
 * <h3>Batching</h3>
 * <p>
 * For efficiency, motion events with {@link #ACTION_MOVE} may batch together
 * multiple movement samples within a single object.  The most current
 * pointer coordinates are available using {@link #getX(int)} and {@link #getY(int)}.
 * Earlier coordinates within the batch are accessed using {@link #getHistoricalX(int, int)}
 * and {@link #getHistoricalY(int, int)}.  The coordinates are "historical" only
 * insofar as they are older than the current coordinates in the batch; however,
 * they are still distinct from any other coordinates reported in prior motion events.
 * To process all coordinates in the batch in time order, first consume the historical
 * coordinates then consume the current coordinates.
 * </p><p>
 * Example: Consuming all samples for all pointers in a motion event in time order.
 * </p><p><pre><code>
 * void printSamples(MotionEvent ev) {
 *     final int historySize = ev.getHistorySize();
 *     final int pointerCount = ev.getPointerCount();
 *     for (int h = 0; h &lt; historySize; h++) {
 *         System.out.printf("At time %d:", ev.getHistoricalEventTime(h));
 *         for (int p = 0; p &lt; pointerCount; p++) {
 *             System.out.printf("  pointer %d: (%f,%f)",
 *                 ev.getPointerId(p), ev.getHistoricalX(p, h), ev.getHistoricalY(p, h));
 *         }
 *     }
 *     System.out.printf("At time %d:", ev.getEventTime());
 *     for (int p = 0; p &lt; pointerCount; p++) {
 *         System.out.printf("  pointer %d: (%f,%f)",
 *             ev.getPointerId(p), ev.getX(p), ev.getY(p));
 *     }
 * }
 * </code></pre></p>
 *
 * <h3>Device Types</h3>
 * <p>
 * The interpretation of the contents of a MotionEvent varies significantly depending
 * on the source class of the device.
 * </p><p>
 * On pointing devices with source class {@link InputDevice#SOURCE_CLASS_POINTER}
 * such as touch screens, the pointer coordinates specify absolute
 * positions such as view X/Y coordinates.  Each complete gesture is represented
 * by a sequence of motion events with actions that describe pointer state transitions
 * and movements.  A gesture starts with a motion event with {@link #ACTION_DOWN}
 * that provides the location of the first pointer down.  As each additional
 * pointer that goes down or up, the framework will generate a motion event with
 * {@link #ACTION_POINTER_DOWN} or {@link #ACTION_POINTER_UP} accordingly.
 * Pointer movements are described by motion events with {@link #ACTION_MOVE}.
 * Finally, a gesture end either when the final pointer goes up as represented
 * by a motion event with {@link #ACTION_UP} or when gesture is canceled
 * with {@link #ACTION_CANCEL}.
 * </p><p>
 * Some pointing devices such as mice may support vertical and/or horizontal scrolling.
 * A scroll event is reported as a generic motion event with {@link #ACTION_SCROLL} that
 * includes the relative scroll offset in the {@link #AXIS_VSCROLL} and
 * {@link #AXIS_HSCROLL} axes.  See {@link #getAxisValue(int)} for information
 * about retrieving these additional axes.
 * </p><p>
 * On trackball devices with source class {@link InputDevice#SOURCE_CLASS_TRACKBALL},
 * the pointer coordinates specify relative movements as X/Y deltas.
 * A trackball gesture consists of a sequence of movements described by motion
 * events with {@link #ACTION_MOVE} interspersed with occasional {@link #ACTION_DOWN}
 * or {@link #ACTION_UP} motion events when the trackball button is pressed or released.
 * </p><p>
 * On joystick devices with source class {@link InputDevice#SOURCE_CLASS_JOYSTICK},
 * the pointer coordinates specify the absolute position of the joystick axes.
 * The joystick axis values are normalized to a range of -1.0 to 1.0 where 0.0 corresponds
 * to the center position.  More information about the set of available axes and the
 * range of motion can be obtained using {@link InputDevice#getMotionRange}.
 * Some common joystick axes are {@link #AXIS_X}, {@link #AXIS_Y},
 * {@link #AXIS_HAT_X}, {@link #AXIS_HAT_Y}, {@link #AXIS_Z} and {@link #AXIS_RZ}.
 * </p><p>
 * Refer to {@link InputDevice} for more information about how different kinds of
 * input devices and sources represent pointer coordinates.
 * </p>
 *
 * <h3>Consistency Guarantees</h3>
 * <p>
 * Motion events are always delivered to views as a consistent stream of events.
 * What constitutes a consistent stream varies depending on the type of device.
 * For touch events, consistency implies that pointers go down one at a time,
 * move around as a group and then go up one at a time or are canceled.
 * </p><p>
 * While the framework tries to deliver consistent streams of motion events to
 * views, it cannot guarantee it.  Some events may be dropped or modified by
 * containing views in the application before they are delivered thereby making
 * the stream of events inconsistent.  Views should always be prepared to
 * handle {@link #ACTION_CANCEL} and should tolerate anomalous
 * situations such as receiving a new {@link #ACTION_DOWN} without first having
 * received an {@link #ACTION_UP} for the prior gesture.
 * </p>
 */
public final class MotionEvent extends InputEvent implements Parcelable {
    private static final long NS_PER_MS = 1000000;

    /**
     * An invalid pointer id.
     *
     * This value (-1) can be used as a placeholder to indicate that a pointer id
     * has not been assigned or is not available.  It cannot appear as
     * a pointer id inside a {@link MotionEvent}.
     */
    public static final int INVALID_POINTER_ID = -1;

    /**
     * Bit mask of the parts of the action code that are the action itself.
     */
    public static final int ACTION_MASK             = 0xff;
    
    /**
     * Constant for {@link #getActionMasked}: A pressed gesture has started, the
     * motion contains the initial starting location.
     * <p>
     * This is also a good time to check the button state to distinguish
     * secondary and tertiary button clicks and handle them appropriately.
     * Use {@link #getButtonState} to retrieve the button state.
     * </p>
     */
    public static final int ACTION_DOWN             = 0;
    
    /**
     * Constant for {@link #getActionMasked}: A pressed gesture has finished, the
     * motion contains the final release location as well as any intermediate
     * points since the last down or move event.
     */
    public static final int ACTION_UP               = 1;
    
    /**
     * Constant for {@link #getActionMasked}: A change has happened during a
     * press gesture (between {@link #ACTION_DOWN} and {@link #ACTION_UP}).
     * The motion contains the most recent point, as well as any intermediate
     * points since the last down or move event.
     */
    public static final int ACTION_MOVE             = 2;
    
    /**
     * Constant for {@link #getActionMasked}: The current gesture has been aborted.
     * You will not receive any more points in it.  You should treat this as
     * an up event, but not perform any action that you normally would.
     */
    public static final int ACTION_CANCEL           = 3;
    
    /**
     * Constant for {@link #getActionMasked}: A movement has happened outside of the
     * normal bounds of the UI element.  This does not provide a full gesture,
     * but only the initial location of the movement/touch.
     */
    public static final int ACTION_OUTSIDE          = 4;

    /**
     * Constant for {@link #getActionMasked}: A non-primary pointer has gone down.
     * <p>
     * Use {@link #getActionIndex} to retrieve the index of the pointer that changed.
     * </p><p>
     * The index is encoded in the {@link #ACTION_POINTER_INDEX_MASK} bits of the
     * unmasked action returned by {@link #getAction}.
     * </p>
     */
    public static final int ACTION_POINTER_DOWN     = 5;
    
    /**
     * Constant for {@link #getActionMasked}: A non-primary pointer has gone up.
     * <p>
     * Use {@link #getActionIndex} to retrieve the index of the pointer that changed.
     * </p><p>
     * The index is encoded in the {@link #ACTION_POINTER_INDEX_MASK} bits of the
     * unmasked action returned by {@link #getAction}.
     * </p>
     */
    public static final int ACTION_POINTER_UP       = 6;

    /**
     * Constant for {@link #getActionMasked}: A change happened but the pointer
     * is not down (unlike {@link #ACTION_MOVE}).  The motion contains the most
     * recent point, as well as any intermediate points since the last
     * hover move event.
     * <p>
     * This action is always delivered to the window or view under the pointer.
     * </p><p>
     * This action is not a touch event so it is delivered to
     * {@link View#onGenericMotionEvent(MotionEvent)} rather than
     * {@link View#onTouchEvent(MotionEvent)}.
     * </p>
     */
    public static final int ACTION_HOVER_MOVE       = 7;

    /**
     * Constant for {@link #getActionMasked}: The motion event contains relative
     * vertical and/or horizontal scroll offsets.  Use {@link #getAxisValue(int)}
     * to retrieve the information from {@link #AXIS_VSCROLL} and {@link #AXIS_HSCROLL}.
     * The pointer may or may not be down when this event is dispatched.
     * <p>
     * This action is always delivered to the window or view under the pointer, which
     * may not be the window or view currently touched.
     * </p><p>
     * This action is not a touch event so it is delivered to
     * {@link View#onGenericMotionEvent(MotionEvent)} rather than
     * {@link View#onTouchEvent(MotionEvent)}.
     * </p>
     */
    public static final int ACTION_SCROLL           = 8;

    /**
     * Constant for {@link #getActionMasked}: The pointer is not down but has entered the
     * boundaries of a window or view.
     * <p>
     * This action is always delivered to the window or view under the pointer.
     * </p><p>
     * This action is not a touch event so it is delivered to
     * {@link View#onGenericMotionEvent(MotionEvent)} rather than
     * {@link View#onTouchEvent(MotionEvent)}.
     * </p>
     */
    public static final int ACTION_HOVER_ENTER      = 9;

    /**
     * Constant for {@link #getActionMasked}: The pointer is not down but has exited the
     * boundaries of a window or view.
     * <p>
     * This action is always delivered to the window or view that was previously under the pointer.
     * </p><p>
     * This action is not a touch event so it is delivered to
     * {@link View#onGenericMotionEvent(MotionEvent)} rather than
     * {@link View#onTouchEvent(MotionEvent)}.
     * </p>
     */
    public static final int ACTION_HOVER_EXIT       = 10;

    /**
     * Bits in the action code that represent a pointer index, used with
     * {@link #ACTION_POINTER_DOWN} and {@link #ACTION_POINTER_UP}.  Shifting
     * down by {@link #ACTION_POINTER_INDEX_SHIFT} provides the actual pointer
     * index where the data for the pointer going up or down can be found; you can
     * get its identifier with {@link #getPointerId(int)} and the actual
     * data with {@link #getX(int)} etc.
     *
     * @see #getActionIndex
     */
    public static final int ACTION_POINTER_INDEX_MASK  = 0xff00;
    
    /**
     * Bit shift for the action bits holding the pointer index as
     * defined by {@link #ACTION_POINTER_INDEX_MASK}.
     *
     * @see #getActionIndex
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
    
    /**
     * This flag indicates that the window that received this motion event is partly
     * or wholly obscured by another visible window above it.  This flag is set to true
     * even if the event did not directly pass through the obscured area.
     * A security sensitive application can check this flag to identify situations in which
     * a malicious application may have covered up part of its content for the purpose
     * of misleading the user or hijacking touches.  An appropriate response might be
     * to drop the suspect touches or to take additional precautions to confirm the user's
     * actual intent.
     */
    public static final int FLAG_WINDOW_IS_OBSCURED = 0x1;

    /**
     * Private flag that indicates when the system has detected that this motion event
     * may be inconsistent with respect to the sequence of previously delivered motion events,
     * such as when a pointer move event is sent but the pointer is not down.
     *
     * @hide
     * @see #isTainted
     * @see #setTainted
     */
    public static final int FLAG_TAINTED = 0x80000000;

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
     * Axis constant: X axis of a motion event.
     * <p>
     * <ul>
     * <li>For a touch screen, reports the absolute X screen position of the center of
     * the touch contact area.  The units are display pixels.
     * <li>For a touch pad, reports the absolute X surface position of the center of the touch
     * contact area.  The units are device-dependent; use {@link InputDevice#getMotionRange(int)}
     * to query the effective range of values.
     * <li>For a mouse, reports the absolute X screen position of the mouse pointer.
     * The units are display pixels.
     * <li>For a trackball, reports the relative horizontal displacement of the trackball.
     * The value is normalized to a range from -1.0 (left) to 1.0 (right).
     * <li>For a joystick, reports the absolute X position of the joystick.
     * The value is normalized to a range from -1.0 (left) to 1.0 (right).
     * </ul>
     * </p>
     *
     * @see #getX(int)
     * @see #getHistoricalX(int, int)
     * @see MotionEvent.PointerCoords#x
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_X = 0;

    /**
     * Axis constant: Y axis of a motion event.
     * <p>
     * <ul>
     * <li>For a touch screen, reports the absolute Y screen position of the center of
     * the touch contact area.  The units are display pixels.
     * <li>For a touch pad, reports the absolute Y surface position of the center of the touch
     * contact area.  The units are device-dependent; use {@link InputDevice#getMotionRange(int)}
     * to query the effective range of values.
     * <li>For a mouse, reports the absolute Y screen position of the mouse pointer.
     * The units are display pixels.
     * <li>For a trackball, reports the relative vertical displacement of the trackball.
     * The value is normalized to a range from -1.0 (up) to 1.0 (down).
     * <li>For a joystick, reports the absolute Y position of the joystick.
     * The value is normalized to a range from -1.0 (up or far) to 1.0 (down or near).
     * </ul>
     * </p>
     *
     * @see #getY(int)
     * @see #getHistoricalY(int, int)
     * @see MotionEvent.PointerCoords#y
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_Y = 1;

    /**
     * Axis constant: Pressure axis of a motion event.
     * <p>
     * <ul>
     * <li>For a touch screen or touch pad, reports the approximate pressure applied to the surface
     * by a finger or other tool.  The value is normalized to a range from
     * 0 (no pressure at all) to 1 (normal pressure), although values higher than 1
     * may be generated depending on the calibration of the input device.
     * <li>For a trackball, the value is set to 1 if the trackball button is pressed
     * or 0 otherwise.
     * <li>For a mouse, the value is set to 1 if the primary mouse button is pressed
     * or 0 otherwise.
     * </ul>
     * </p>
     *
     * @see #getPressure(int)
     * @see #getHistoricalPressure(int, int)
     * @see MotionEvent.PointerCoords#pressure
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_PRESSURE = 2;

    /**
     * Axis constant: Size axis of a motion event.
     * <p>
     * <ul>
     * <li>For a touch screen or touch pad, reports the approximate size of the contact area in
     * relation to the maximum detectable size for the device.  The value is normalized
     * to a range from 0 (smallest detectable size) to 1 (largest detectable size),
     * although it is not a linear scale.  This value is of limited use.
     * To obtain calibrated size information, use
     * {@link #AXIS_TOUCH_MAJOR} or {@link #AXIS_TOOL_MAJOR}.
     * </ul>
     * </p>
     *
     * @see #getSize(int)
     * @see #getHistoricalSize(int, int)
     * @see MotionEvent.PointerCoords#size
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_SIZE = 3;

    /**
     * Axis constant: TouchMajor axis of a motion event.
     * <p>
     * <ul>
     * <li>For a touch screen, reports the length of the major axis of an ellipse that
     * represents the touch area at the point of contact.
     * The units are display pixels.
     * <li>For a touch pad, reports the length of the major axis of an ellipse that
     * represents the touch area at the point of contact.
     * The units are device-dependent; use {@link InputDevice#getMotionRange(int)}
     * to query the effective range of values.
     * </ul>
     * </p>
     *
     * @see #getTouchMajor(int)
     * @see #getHistoricalTouchMajor(int, int)
     * @see MotionEvent.PointerCoords#touchMajor
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_TOUCH_MAJOR = 4;

    /**
     * Axis constant: TouchMinor axis of a motion event.
     * <p>
     * <ul>
     * <li>For a touch screen, reports the length of the minor axis of an ellipse that
     * represents the touch area at the point of contact.
     * The units are display pixels.
     * <li>For a touch pad, reports the length of the minor axis of an ellipse that
     * represents the touch area at the point of contact.
     * The units are device-dependent; use {@link InputDevice#getMotionRange(int)}
     * to query the effective range of values.
     * </ul>
     * </p><p>
     * When the touch is circular, the major and minor axis lengths will be equal to one another.
     * </p>
     *
     * @see #getTouchMinor(int)
     * @see #getHistoricalTouchMinor(int, int)
     * @see MotionEvent.PointerCoords#touchMinor
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_TOUCH_MINOR = 5;

    /**
     * Axis constant: ToolMajor axis of a motion event.
     * <p>
     * <ul>
     * <li>For a touch screen, reports the length of the major axis of an ellipse that
     * represents the size of the approaching finger or tool used to make contact.
     * <li>For a touch pad, reports the length of the major axis of an ellipse that
     * represents the size of the approaching finger or tool used to make contact.
     * The units are device-dependent; use {@link InputDevice#getMotionRange(int)}
     * to query the effective range of values.
     * </ul>
     * </p><p>
     * When the touch is circular, the major and minor axis lengths will be equal to one another.
     * </p><p>
     * The tool size may be larger than the touch size since the tool may not be fully
     * in contact with the touch sensor.
     * </p>
     *
     * @see #getToolMajor(int)
     * @see #getHistoricalToolMajor(int, int)
     * @see MotionEvent.PointerCoords#toolMajor
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_TOOL_MAJOR = 6;

    /**
     * Axis constant: ToolMinor axis of a motion event.
     * <p>
     * <ul>
     * <li>For a touch screen, reports the length of the minor axis of an ellipse that
     * represents the size of the approaching finger or tool used to make contact.
     * <li>For a touch pad, reports the length of the minor axis of an ellipse that
     * represents the size of the approaching finger or tool used to make contact.
     * The units are device-dependent; use {@link InputDevice#getMotionRange(int)}
     * to query the effective range of values.
     * </ul>
     * </p><p>
     * When the touch is circular, the major and minor axis lengths will be equal to one another.
     * </p><p>
     * The tool size may be larger than the touch size since the tool may not be fully
     * in contact with the touch sensor.
     * </p>
     *
     * @see #getToolMinor(int)
     * @see #getHistoricalToolMinor(int, int)
     * @see MotionEvent.PointerCoords#toolMinor
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_TOOL_MINOR = 7;

    /**
     * Axis constant: Orientation axis of a motion event.
     * <p>
     * <ul>
     * <li>For a touch screen or touch pad, reports the orientation of the finger
     * or tool in radians relative to the vertical plane of the device.
     * An angle of 0 radians indicates that the major axis of contact is oriented
     * upwards, is perfectly circular or is of unknown orientation.  A positive angle
     * indicates that the major axis of contact is oriented to the right.  A negative angle
     * indicates that the major axis of contact is oriented to the left.
     * The full range is from -PI/2 radians (finger pointing fully left) to PI/2 radians
     * (finger pointing fully right).
     * <li>For a stylus, the orientation indicates the direction in which the stylus
     * is pointing in relation to the vertical axis of the current orientation of the screen.
     * The range is from -PI radians to PI radians, where 0 is pointing up,
     * -PI/2 radians is pointing left, -PI or PI radians is pointing down, and PI/2 radians
     * is pointing right.  See also {@link #AXIS_TILT}.
     * </ul>
     * </p>
     *
     * @see #getOrientation(int)
     * @see #getHistoricalOrientation(int, int)
     * @see MotionEvent.PointerCoords#orientation
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_ORIENTATION = 8;

    /**
     * Axis constant: Vertical Scroll axis of a motion event.
     * <p>
     * <ul>
     * <li>For a mouse, reports the relative movement of the vertical scroll wheel.
     * The value is normalized to a range from -1.0 (down) to 1.0 (up).
     * </ul>
     * </p><p>
     * This axis should be used to scroll views vertically.
     * </p>
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_VSCROLL = 9;

    /**
     * Axis constant: Horizontal Scroll axis of a motion event.
     * <p>
     * <ul>
     * <li>For a mouse, reports the relative movement of the horizontal scroll wheel.
     * The value is normalized to a range from -1.0 (left) to 1.0 (right).
     * </ul>
     * </p><p>
     * This axis should be used to scroll views horizontally.
     * </p>
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_HSCROLL = 10;

    /**
     * Axis constant: Z axis of a motion event.
     * <p>
     * <ul>
     * <li>For a joystick, reports the absolute Z position of the joystick.
     * The value is normalized to a range from -1.0 (high) to 1.0 (low).
     * <em>On game pads with two analog joysticks, this axis is often reinterpreted
     * to report the absolute X position of the second joystick instead.</em>
     * </ul>
     * </p>
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_Z = 11;

    /**
     * Axis constant: X Rotation axis of a motion event.
     * <p>
     * <ul>
     * <li>For a joystick, reports the absolute rotation angle about the X axis.
     * The value is normalized to a range from -1.0 (counter-clockwise) to 1.0 (clockwise).
     * </ul>
     * </p>
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_RX = 12;

    /**
     * Axis constant: Y Rotation axis of a motion event.
     * <p>
     * <ul>
     * <li>For a joystick, reports the absolute rotation angle about the Y axis.
     * The value is normalized to a range from -1.0 (counter-clockwise) to 1.0 (clockwise).
     * </ul>
     * </p>
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_RY = 13;

    /**
     * Axis constant: Z Rotation axis of a motion event.
     * <p>
     * <ul>
     * <li>For a joystick, reports the absolute rotation angle about the Z axis.
     * The value is normalized to a range from -1.0 (counter-clockwise) to 1.0 (clockwise).
     * <em>On game pads with two analog joysticks, this axis is often reinterpreted
     * to report the absolute Y position of the second joystick instead.</em>
     * </ul>
     * </p>
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_RZ = 14;

    /**
     * Axis constant: Hat X axis of a motion event.
     * <p>
     * <ul>
     * <li>For a joystick, reports the absolute X position of the directional hat control.
     * The value is normalized to a range from -1.0 (left) to 1.0 (right).
     * </ul>
     * </p>
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_HAT_X = 15;

    /**
     * Axis constant: Hat Y axis of a motion event.
     * <p>
     * <ul>
     * <li>For a joystick, reports the absolute Y position of the directional hat control.
     * The value is normalized to a range from -1.0 (up) to 1.0 (down).
     * </ul>
     * </p>
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_HAT_Y = 16;

    /**
     * Axis constant: Left Trigger axis of a motion event.
     * <p>
     * <ul>
     * <li>For a joystick, reports the absolute position of the left trigger control.
     * The value is normalized to a range from 0.0 (released) to 1.0 (fully pressed).
     * </ul>
     * </p>
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_LTRIGGER = 17;

    /**
     * Axis constant: Right Trigger axis of a motion event.
     * <p>
     * <ul>
     * <li>For a joystick, reports the absolute position of the right trigger control.
     * The value is normalized to a range from 0.0 (released) to 1.0 (fully pressed).
     * </ul>
     * </p>
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_RTRIGGER = 18;

    /**
     * Axis constant: Throttle axis of a motion event.
     * <p>
     * <ul>
     * <li>For a joystick, reports the absolute position of the throttle control.
     * The value is normalized to a range from 0.0 (fully open) to 1.0 (fully closed).
     * </ul>
     * </p>
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_THROTTLE = 19;

    /**
     * Axis constant: Rudder axis of a motion event.
     * <p>
     * <ul>
     * <li>For a joystick, reports the absolute position of the rudder control.
     * The value is normalized to a range from -1.0 (turn left) to 1.0 (turn right).
     * </ul>
     * </p>
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_RUDDER = 20;

    /**
     * Axis constant: Wheel axis of a motion event.
     * <p>
     * <ul>
     * <li>For a joystick, reports the absolute position of the steering wheel control.
     * The value is normalized to a range from -1.0 (turn left) to 1.0 (turn right).
     * </ul>
     * </p>
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_WHEEL = 21;

    /**
     * Axis constant: Gas axis of a motion event.
     * <p>
     * <ul>
     * <li>For a joystick, reports the absolute position of the gas (accelerator) control.
     * The value is normalized to a range from 0.0 (no acceleration)
     * to 1.0 (maximum acceleration).
     * </ul>
     * </p>
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_GAS = 22;

    /**
     * Axis constant: Brake axis of a motion event.
     * <p>
     * <ul>
     * <li>For a joystick, reports the absolute position of the brake control.
     * The value is normalized to a range from 0.0 (no braking) to 1.0 (maximum braking).
     * </ul>
     * </p>
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_BRAKE = 23;

    /**
     * Axis constant: Distance axis of a motion event.
     * <p>
     * <ul>
     * <li>For a stylus, reports the distance of the stylus from the screen.
     * A value of 0.0 indicates direct contact and larger values indicate increasing
     * distance from the surface.
     * </ul>
     * </p>
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_DISTANCE = 24;

    /**
     * Axis constant: Tilt axis of a motion event.
     * <p>
     * <ul>
     * <li>For a stylus, reports the tilt angle of the stylus in radians where
     * 0 radians indicates that the stylus is being held perpendicular to the
     * surface, and PI/2 radians indicates that the stylus is being held flat
     * against the surface.
     * </ul>
     * </p>
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int, int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_TILT = 25;

    /**
     * Axis constant: Generic 1 axis of a motion event.
     * The interpretation of a generic axis is device-specific.
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_GENERIC_1 = 32;

    /**
     * Axis constant: Generic 2 axis of a motion event.
     * The interpretation of a generic axis is device-specific.
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_GENERIC_2 = 33;

    /**
     * Axis constant: Generic 3 axis of a motion event.
     * The interpretation of a generic axis is device-specific.
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_GENERIC_3 = 34;

    /**
     * Axis constant: Generic 4 axis of a motion event.
     * The interpretation of a generic axis is device-specific.
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_GENERIC_4 = 35;

    /**
     * Axis constant: Generic 5 axis of a motion event.
     * The interpretation of a generic axis is device-specific.
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_GENERIC_5 = 36;

    /**
     * Axis constant: Generic 6 axis of a motion event.
     * The interpretation of a generic axis is device-specific.
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_GENERIC_6 = 37;

    /**
     * Axis constant: Generic 7 axis of a motion event.
     * The interpretation of a generic axis is device-specific.
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_GENERIC_7 = 38;

    /**
     * Axis constant: Generic 8 axis of a motion event.
     * The interpretation of a generic axis is device-specific.
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_GENERIC_8 = 39;

    /**
     * Axis constant: Generic 9 axis of a motion event.
     * The interpretation of a generic axis is device-specific.
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_GENERIC_9 = 40;

    /**
     * Axis constant: Generic 10 axis of a motion event.
     * The interpretation of a generic axis is device-specific.
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_GENERIC_10 = 41;

    /**
     * Axis constant: Generic 11 axis of a motion event.
     * The interpretation of a generic axis is device-specific.
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_GENERIC_11 = 42;

    /**
     * Axis constant: Generic 12 axis of a motion event.
     * The interpretation of a generic axis is device-specific.
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_GENERIC_12 = 43;

    /**
     * Axis constant: Generic 13 axis of a motion event.
     * The interpretation of a generic axis is device-specific.
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_GENERIC_13 = 44;

    /**
     * Axis constant: Generic 14 axis of a motion event.
     * The interpretation of a generic axis is device-specific.
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_GENERIC_14 = 45;

    /**
     * Axis constant: Generic 15 axis of a motion event.
     * The interpretation of a generic axis is device-specific.
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_GENERIC_15 = 46;

    /**
     * Axis constant: Generic 16 axis of a motion event.
     * The interpretation of a generic axis is device-specific.
     *
     * @see #getAxisValue(int, int)
     * @see #getHistoricalAxisValue(int, int, int)
     * @see MotionEvent.PointerCoords#getAxisValue(int)
     * @see InputDevice#getMotionRange
     */
    public static final int AXIS_GENERIC_16 = 47;

    // NOTE: If you add a new axis here you must also add it to:
    //  native/include/android/input.h
    //  frameworks/base/include/ui/KeycodeLabels.h

    // Symbolic names of all axes.
    private static final SparseArray<String> AXIS_SYMBOLIC_NAMES = new SparseArray<String>();
    static {
        SparseArray<String> names = AXIS_SYMBOLIC_NAMES;
        names.append(AXIS_X, "AXIS_X");
        names.append(AXIS_Y, "AXIS_Y");
        names.append(AXIS_PRESSURE, "AXIS_PRESSURE");
        names.append(AXIS_SIZE, "AXIS_SIZE");
        names.append(AXIS_TOUCH_MAJOR, "AXIS_TOUCH_MAJOR");
        names.append(AXIS_TOUCH_MINOR, "AXIS_TOUCH_MINOR");
        names.append(AXIS_TOOL_MAJOR, "AXIS_TOOL_MAJOR");
        names.append(AXIS_TOOL_MINOR, "AXIS_TOOL_MINOR");
        names.append(AXIS_ORIENTATION, "AXIS_ORIENTATION");
        names.append(AXIS_VSCROLL, "AXIS_VSCROLL");
        names.append(AXIS_HSCROLL, "AXIS_HSCROLL");
        names.append(AXIS_Z, "AXIS_Z");
        names.append(AXIS_RX, "AXIS_RX");
        names.append(AXIS_RY, "AXIS_RY");
        names.append(AXIS_RZ, "AXIS_RZ");
        names.append(AXIS_HAT_X, "AXIS_HAT_X");
        names.append(AXIS_HAT_Y, "AXIS_HAT_Y");
        names.append(AXIS_LTRIGGER, "AXIS_LTRIGGER");
        names.append(AXIS_RTRIGGER, "AXIS_RTRIGGER");
        names.append(AXIS_THROTTLE, "AXIS_THROTTLE");
        names.append(AXIS_RUDDER, "AXIS_RUDDER");
        names.append(AXIS_WHEEL, "AXIS_WHEEL");
        names.append(AXIS_GAS, "AXIS_GAS");
        names.append(AXIS_BRAKE, "AXIS_BRAKE");
        names.append(AXIS_DISTANCE, "AXIS_DISTANCE");
        names.append(AXIS_TILT, "AXIS_TILT");
        names.append(AXIS_GENERIC_1, "AXIS_GENERIC_1");
        names.append(AXIS_GENERIC_2, "AXIS_GENERIC_2");
        names.append(AXIS_GENERIC_3, "AXIS_GENERIC_3");
        names.append(AXIS_GENERIC_4, "AXIS_GENERIC_4");
        names.append(AXIS_GENERIC_5, "AXIS_GENERIC_5");
        names.append(AXIS_GENERIC_6, "AXIS_GENERIC_6");
        names.append(AXIS_GENERIC_7, "AXIS_GENERIC_7");
        names.append(AXIS_GENERIC_8, "AXIS_GENERIC_8");
        names.append(AXIS_GENERIC_9, "AXIS_GENERIC_9");
        names.append(AXIS_GENERIC_10, "AXIS_GENERIC_10");
        names.append(AXIS_GENERIC_11, "AXIS_GENERIC_11");
        names.append(AXIS_GENERIC_12, "AXIS_GENERIC_12");
        names.append(AXIS_GENERIC_13, "AXIS_GENERIC_13");
        names.append(AXIS_GENERIC_14, "AXIS_GENERIC_14");
        names.append(AXIS_GENERIC_15, "AXIS_GENERIC_15");
        names.append(AXIS_GENERIC_16, "AXIS_GENERIC_16");
    }

    /**
     * Button constant: Primary button (left mouse button).
     *
     * This button constant is not set in response to simple touches with a finger
     * or stylus tip.  The user must actually push a button.
     *
     * @see #getButtonState
     */
    public static final int BUTTON_PRIMARY = 1 << 0;

    /**
     * Button constant: Secondary button (right mouse button, stylus first button).
     *
     * @see #getButtonState
     */
    public static final int BUTTON_SECONDARY = 1 << 1;

    /**
     * Button constant: Tertiary button (middle mouse button, stylus second button).
     *
     * @see #getButtonState
     */
    public static final int BUTTON_TERTIARY = 1 << 2;

    /**
     * Button constant: Back button pressed (mouse back button).
     * <p>
     * The system may send a {@link KeyEvent#KEYCODE_BACK} key press to the application
     * when this button is pressed.
     * </p>
     *
     * @see #getButtonState
     */
    public static final int BUTTON_BACK = 1 << 3;

    /**
     * Button constant: Forward button pressed (mouse forward button).
     * <p>
     * The system may send a {@link KeyEvent#KEYCODE_FORWARD} key press to the application
     * when this button is pressed.
     * </p>
     *
     * @see #getButtonState
     */
    public static final int BUTTON_FORWARD = 1 << 4;

    // NOTE: If you add a new axis here you must also add it to:
    //  native/include/android/input.h

    // Symbolic names of all button states in bit order from least significant
    // to most significant.
    private static final String[] BUTTON_SYMBOLIC_NAMES = new String[] {
        "BUTTON_PRIMARY",
        "BUTTON_SECONDARY",
        "BUTTON_TERTIARY",
        "BUTTON_BACK",
        "BUTTON_FORWARD",
        "0x00000020",
        "0x00000040",
        "0x00000080",
        "0x00000100",
        "0x00000200",
        "0x00000400",
        "0x00000800",
        "0x00001000",
        "0x00002000",
        "0x00004000",
        "0x00008000",
        "0x00010000",
        "0x00020000",
        "0x00040000",
        "0x00080000",
        "0x00100000",
        "0x00200000",
        "0x00400000",
        "0x00800000",
        "0x01000000",
        "0x02000000",
        "0x04000000",
        "0x08000000",
        "0x10000000",
        "0x20000000",
        "0x40000000",
        "0x80000000",
    };

    /**
     * Tool type constant: Unknown tool type.
     * This constant is used when the tool type is not known or is not relevant,
     * such as for a trackball or other non-pointing device.
     *
     * @see #getToolType
     */
    public static final int TOOL_TYPE_UNKNOWN = 0;

    /**
     * Tool type constant: The tool is a finger.
     *
     * @see #getToolType
     */
    public static final int TOOL_TYPE_FINGER = 1;

    /**
     * Tool type constant: The tool is a stylus.
     *
     * @see #getToolType
     */
    public static final int TOOL_TYPE_STYLUS = 2;

    /**
     * Tool type constant: The tool is a mouse or trackpad.
     *
     * @see #getToolType
     */
    public static final int TOOL_TYPE_MOUSE = 3;

    /**
     * Tool type constant: The tool is an eraser or a stylus being used in an inverted posture.
     *
     * @see #getToolType
     */
    public static final int TOOL_TYPE_ERASER = 4;

    // NOTE: If you add a new tool type here you must also add it to:
    //  native/include/android/input.h

    // Symbolic names of all tool types.
    private static final SparseArray<String> TOOL_TYPE_SYMBOLIC_NAMES = new SparseArray<String>();
    static {
        SparseArray<String> names = TOOL_TYPE_SYMBOLIC_NAMES;
        names.append(TOOL_TYPE_UNKNOWN, "TOOL_TYPE_UNKNOWN");
        names.append(TOOL_TYPE_FINGER, "TOOL_TYPE_FINGER");
        names.append(TOOL_TYPE_STYLUS, "TOOL_TYPE_STYLUS");
        names.append(TOOL_TYPE_MOUSE, "TOOL_TYPE_MOUSE");
        names.append(TOOL_TYPE_ERASER, "TOOL_TYPE_ERASER");
    }

    // Private value for history pos that obtains the current sample.
    private static final int HISTORY_CURRENT = -0x80000000;

    private static final int MAX_RECYCLED = 10;
    private static final Object gRecyclerLock = new Object();
    private static int gRecyclerUsed;
    private static MotionEvent gRecyclerTop;

    // Shared temporary objects used when translating coordinates supplied by
    // the caller into single element PointerCoords and pointer id arrays.
    private static final Object gSharedTempLock = new Object();
    private static PointerCoords[] gSharedTempPointerCoords;
    private static PointerProperties[] gSharedTempPointerProperties;
    private static int[] gSharedTempPointerIndexMap;

    private static final void ensureSharedTempPointerCapacity(int desiredCapacity) {
        if (gSharedTempPointerCoords == null
                || gSharedTempPointerCoords.length < desiredCapacity) {
            int capacity = gSharedTempPointerCoords != null ? gSharedTempPointerCoords.length : 8;
            while (capacity < desiredCapacity) {
                capacity *= 2;
            }
            gSharedTempPointerCoords = PointerCoords.createArray(capacity);
            gSharedTempPointerProperties = PointerProperties.createArray(capacity);
            gSharedTempPointerIndexMap = new int[capacity];
        }
    }

    // Pointer to the native MotionEvent object that contains the actual data.
    private int mNativePtr;

    private MotionEvent mNext;

    private static native int nativeInitialize(int nativePtr,
            int deviceId, int source, int action, int flags, int edgeFlags,
            int metaState, int buttonState,
            float xOffset, float yOffset, float xPrecision, float yPrecision,
            long downTimeNanos, long eventTimeNanos,
            int pointerCount, PointerProperties[] pointerIds, PointerCoords[] pointerCoords);
    private static native int nativeCopy(int destNativePtr, int sourceNativePtr,
            boolean keepHistory);
    private static native void nativeDispose(int nativePtr);
    private static native void nativeAddBatch(int nativePtr, long eventTimeNanos,
            PointerCoords[] pointerCoords, int metaState);

    private static native int nativeGetDeviceId(int nativePtr);
    private static native int nativeGetSource(int nativePtr);
    private static native int nativeSetSource(int nativePtr, int source);
    private static native int nativeGetAction(int nativePtr);
    private static native void nativeSetAction(int nativePtr, int action);
    private static native boolean nativeIsTouchEvent(int nativePtr);
    private static native int nativeGetFlags(int nativePtr);
    private static native void nativeSetFlags(int nativePtr, int flags);
    private static native int nativeGetEdgeFlags(int nativePtr);
    private static native void nativeSetEdgeFlags(int nativePtr, int action);
    private static native int nativeGetMetaState(int nativePtr);
    private static native int nativeGetButtonState(int nativePtr);
    private static native void nativeOffsetLocation(int nativePtr, float deltaX, float deltaY);
    private static native float nativeGetXOffset(int nativePtr);
    private static native float nativeGetYOffset(int nativePtr);
    private static native float nativeGetXPrecision(int nativePtr);
    private static native float nativeGetYPrecision(int nativePtr);
    private static native long nativeGetDownTimeNanos(int nativePtr);
    private static native void nativeSetDownTimeNanos(int nativePtr, long downTime);

    private static native int nativeGetPointerCount(int nativePtr);
    private static native int nativeGetPointerId(int nativePtr, int pointerIndex);
    private static native int nativeGetToolType(int nativePtr, int pointerIndex);
    private static native int nativeFindPointerIndex(int nativePtr, int pointerId);

    private static native int nativeGetHistorySize(int nativePtr);
    private static native long nativeGetEventTimeNanos(int nativePtr, int historyPos);
    private static native float nativeGetRawAxisValue(int nativePtr,
            int axis, int pointerIndex, int historyPos);
    private static native float nativeGetAxisValue(int nativePtr,
            int axis, int pointerIndex, int historyPos);
    private static native void nativeGetPointerCoords(int nativePtr,
            int pointerIndex, int historyPos, PointerCoords outPointerCoords);
    private static native void nativeGetPointerProperties(int nativePtr,
            int pointerIndex, PointerProperties outPointerProperties);

    private static native void nativeScale(int nativePtr, float scale);
    private static native void nativeTransform(int nativePtr, Matrix matrix);

    private static native int nativeReadFromParcel(int nativePtr, Parcel parcel);
    private static native void nativeWriteToParcel(int nativePtr, Parcel parcel);

    private MotionEvent() {
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mNativePtr != 0) {
                nativeDispose(mNativePtr);
                mNativePtr = 0;
            }
        } finally {
            super.finalize();
        }
    }

    static private MotionEvent obtain() {
        final MotionEvent ev;
        synchronized (gRecyclerLock) {
            ev = gRecyclerTop;
            if (ev == null) {
                return new MotionEvent();
            }
            gRecyclerTop = ev.mNext;
            gRecyclerUsed -= 1;
        }
        ev.mNext = null;
        ev.prepareForReuse();
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
     * @param action The kind of action being performed, such as {@link #ACTION_DOWN}.
     * @param pointerCount The number of pointers that will be in this event.
     * @param pointerProperties An array of <em>pointerCount</em> values providing
     * a {@link PointerProperties} property object for each pointer, which must
     * include the pointer identifier.
     * @param pointerCoords An array of <em>pointerCount</em> values providing
     * a {@link PointerCoords} coordinate object for each pointer.
     * @param metaState The state of any meta / modifier keys that were in effect when
     * the event was generated.
     * @param buttonState The state of buttons that are pressed.
     * @param xPrecision The precision of the X coordinate being reported.
     * @param yPrecision The precision of the Y coordinate being reported.
     * @param deviceId The id for the device that this event came from.  An id of
     * zero indicates that the event didn't come from a physical device; other
     * numbers are arbitrary and you shouldn't depend on the values.
     * @param edgeFlags A bitfield indicating which edges, if any, were touched by this
     * MotionEvent.
     * @param source The source of this event.
     * @param flags The motion event flags.
     */
    static public MotionEvent obtain(long downTime, long eventTime,
            int action, int pointerCount, PointerProperties[] pointerProperties,
            PointerCoords[] pointerCoords, int metaState, int buttonState,
            float xPrecision, float yPrecision, int deviceId,
            int edgeFlags, int source, int flags) {
        MotionEvent ev = obtain();
        ev.mNativePtr = nativeInitialize(ev.mNativePtr,
                deviceId, source, action, flags, edgeFlags, metaState, buttonState,
                0, 0, xPrecision, yPrecision,
                downTime * NS_PER_MS, eventTime * NS_PER_MS,
                pointerCount, pointerProperties, pointerCoords);
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
     * @param action The kind of action being performed, such as {@link #ACTION_DOWN}.
     * @param pointerCount The number of pointers that will be in this event.
     * @param pointerIds An array of <em>pointerCount</em> values providing
     * an identifier for each pointer.
     * @param pointerCoords An array of <em>pointerCount</em> values providing
     * a {@link PointerCoords} coordinate object for each pointer.
     * @param metaState The state of any meta / modifier keys that were in effect when
     * the event was generated.
     * @param xPrecision The precision of the X coordinate being reported.
     * @param yPrecision The precision of the Y coordinate being reported.
     * @param deviceId The id for the device that this event came from.  An id of
     * zero indicates that the event didn't come from a physical device; other
     * numbers are arbitrary and you shouldn't depend on the values.
     * @param edgeFlags A bitfield indicating which edges, if any, were touched by this
     * MotionEvent.
     * @param source The source of this event.
     * @param flags The motion event flags.
     *
     * @deprecated Use {@link #obtain(long, long, int, int, PointerProperties[], PointerCoords[], int, int, float, float, int, int, int, int)}
     * instead.
     */
    @Deprecated
    static public MotionEvent obtain(long downTime, long eventTime,
            int action, int pointerCount, int[] pointerIds, PointerCoords[] pointerCoords,
            int metaState, float xPrecision, float yPrecision, int deviceId,
            int edgeFlags, int source, int flags) {
        synchronized (gSharedTempLock) {
            ensureSharedTempPointerCapacity(pointerCount);
            final PointerProperties[] pp = gSharedTempPointerProperties;
            for (int i = 0; i < pointerCount; i++) {
                pp[i].clear();
                pp[i].id = pointerIds[i];
            }
            return obtain(downTime, eventTime, action, pointerCount, pp,
                    pointerCoords, metaState, 0, xPrecision, yPrecision, deviceId,
                    edgeFlags, source, flags);
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
     * @param action The kind of action being performed, such as {@link #ACTION_DOWN}.
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
     * @param edgeFlags A bitfield indicating which edges, if any, were touched by this
     * MotionEvent.
     */
    static public MotionEvent obtain(long downTime, long eventTime, int action,
            float x, float y, float pressure, float size, int metaState,
            float xPrecision, float yPrecision, int deviceId, int edgeFlags) {
        MotionEvent ev = obtain();
        synchronized (gSharedTempLock) {
            ensureSharedTempPointerCapacity(1);
            final PointerProperties[] pp = gSharedTempPointerProperties;
            pp[0].clear();
            pp[0].id = 0;

            final PointerCoords pc[] = gSharedTempPointerCoords;
            pc[0].clear();
            pc[0].x = x;
            pc[0].y = y;
            pc[0].pressure = pressure;
            pc[0].size = size;

            ev.mNativePtr = nativeInitialize(ev.mNativePtr,
                    deviceId, InputDevice.SOURCE_UNKNOWN, action, 0, edgeFlags, metaState, 0,
                    0, 0, xPrecision, yPrecision,
                    downTime * NS_PER_MS, eventTime * NS_PER_MS,
                    1, pp, pc);
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
     * @param action The kind of action being performed, such as {@link #ACTION_DOWN}.
     * @param pointerCount The number of pointers that are active in this event.
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
     * @param edgeFlags A bitfield indicating which edges, if any, were touched by this
     * MotionEvent.
     * 
     * @deprecated Use {@link #obtain(long, long, int, float, float, float, float, int, float, float, int, int)}
     * instead.
     */
    @Deprecated
    static public MotionEvent obtain(long downTime, long eventTime, int action,
            int pointerCount, float x, float y, float pressure, float size, int metaState,
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
     * @param action The kind of action being performed, such as {@link #ACTION_DOWN}.
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
    static public MotionEvent obtain(MotionEvent other) {
        if (other == null) {
            throw new IllegalArgumentException("other motion event must not be null");
        }

        MotionEvent ev = obtain();
        ev.mNativePtr = nativeCopy(ev.mNativePtr, other.mNativePtr, true /*keepHistory*/);
        return ev;
    }

    /**
     * Create a new MotionEvent, copying from an existing one, but not including
     * any historical point information.
     */
    static public MotionEvent obtainNoHistory(MotionEvent other) {
        if (other == null) {
            throw new IllegalArgumentException("other motion event must not be null");
        }

        MotionEvent ev = obtain();
        ev.mNativePtr = nativeCopy(ev.mNativePtr, other.mNativePtr, false /*keepHistory*/);
        return ev;
    }

    /** @hide */
    @Override
    public MotionEvent copy() {
        return obtain(this);
    }

    /**
     * Recycle the MotionEvent, to be re-used by a later caller.  After calling
     * this function you must not ever touch the event again.
     */
    @Override
    public final void recycle() {
        super.recycle();

        synchronized (gRecyclerLock) {
            if (gRecyclerUsed < MAX_RECYCLED) {
                gRecyclerUsed++;
                mNext = gRecyclerTop;
                gRecyclerTop = this;
            }
        }
    }

    /**
     * Applies a scale factor to all points within this event.
     *
     * This method is used to adjust touch events to simulate different density
     * displays for compatibility mode.  The values returned by {@link #getRawX()},
     * {@link #getRawY()}, {@link #getXPrecision()} and {@link #getYPrecision()}
     * are also affected by the scale factor.
     *
     * @param scale The scale factor to apply.
     * @hide
     */
    public final void scale(float scale) {
        if (scale != 1.0f) {
            nativeScale(mNativePtr, scale);
        }
    }

    /** {@inheritDoc} */
    @Override
    public final int getDeviceId() {
        return nativeGetDeviceId(mNativePtr);
    }

    /** {@inheritDoc} */
    @Override
    public final int getSource() {
        return nativeGetSource(mNativePtr);
    }

    /** {@inheritDoc} */
    @Override
    public final void setSource(int source) {
        nativeSetSource(mNativePtr, source);
    }

    /**
     * Return the kind of action being performed.
     * Consider using {@link #getActionMasked} and {@link #getActionIndex} to retrieve
     * the separate masked action and pointer index.
     * @return The action, such as {@link #ACTION_DOWN} or
     * the combination of {@link #ACTION_POINTER_DOWN} with a shifted pointer index.
     */
    public final int getAction() {
        return nativeGetAction(mNativePtr);
    }

    /**
     * Return the masked action being performed, without pointer index information.
     * Use {@link #getActionIndex} to return the index associated with pointer actions.
     * @return The action, such as {@link #ACTION_DOWN} or {@link #ACTION_POINTER_DOWN}.
     */
    public final int getActionMasked() {
        return nativeGetAction(mNativePtr) & ACTION_MASK;
    }

    /**
     * For {@link #ACTION_POINTER_DOWN} or {@link #ACTION_POINTER_UP}
     * as returned by {@link #getActionMasked}, this returns the associated
     * pointer index.
     * The index may be used with {@link #getPointerId(int)},
     * {@link #getX(int)}, {@link #getY(int)}, {@link #getPressure(int)},
     * and {@link #getSize(int)} to get information about the pointer that has
     * gone down or up.
     * @return The index associated with the action.
     */
    public final int getActionIndex() {
        return (nativeGetAction(mNativePtr) & ACTION_POINTER_INDEX_MASK)
                >> ACTION_POINTER_INDEX_SHIFT;
    }

    /**
     * Returns true if this motion event is a touch event.
     * <p>
     * Specifically excludes pointer events with action {@link #ACTION_HOVER_MOVE},
     * {@link #ACTION_HOVER_ENTER}, {@link #ACTION_HOVER_EXIT}, or {@link #ACTION_SCROLL}
     * because they are not actually touch events (the pointer is not down).
     * </p>
     * @return True if this motion event is a touch event.
     * @hide
     */
    public final boolean isTouchEvent() {
        return nativeIsTouchEvent(mNativePtr);
    }

    /**
     * Gets the motion event flags.
     *
     * @see #FLAG_WINDOW_IS_OBSCURED
     */
    public final int getFlags() {
        return nativeGetFlags(mNativePtr);
    }

    /** @hide */
    @Override
    public final boolean isTainted() {
        final int flags = getFlags();
        return (flags & FLAG_TAINTED) != 0;
    }

    /** @hide */
    @Override
    public final void setTainted(boolean tainted) {
        final int flags = getFlags();
        nativeSetFlags(mNativePtr, tainted ? flags | FLAG_TAINTED : flags & ~FLAG_TAINTED);
    }

    /**
     * Returns the time (in ms) when the user originally pressed down to start
     * a stream of position events.
     */
    public final long getDownTime() {
        return nativeGetDownTimeNanos(mNativePtr) / NS_PER_MS;
    }

    /**
     * Sets the time (in ms) when the user originally pressed down to start
     * a stream of position events.
     *
     * @hide
     */
    public final void setDownTime(long downTime) {
        nativeSetDownTimeNanos(mNativePtr, downTime * NS_PER_MS);
    }

    /**
     * Retrieve the time this event occurred,
     * in the {@link android.os.SystemClock#uptimeMillis} time base.
     *
     * @return Returns the time this event occurred,
     * in the {@link android.os.SystemClock#uptimeMillis} time base.
     */
    @Override
    public final long getEventTime() {
        return nativeGetEventTimeNanos(mNativePtr, HISTORY_CURRENT) / NS_PER_MS;
    }

    /**
     * Retrieve the time this event occurred,
     * in the {@link android.os.SystemClock#uptimeMillis} time base but with
     * nanosecond precision.
     * <p>
     * The value is in nanosecond precision but it may not have nanosecond accuracy.
     * </p>
     *
     * @return Returns the time this event occurred,
     * in the {@link android.os.SystemClock#uptimeMillis} time base but with
     * nanosecond precision.
     *
     * @hide
     */
    @Override
    public final long getEventTimeNano() {
        return nativeGetEventTimeNanos(mNativePtr, HISTORY_CURRENT);
    }

    /**
     * {@link #getX(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @see #AXIS_X
     */
    public final float getX() {
        return nativeGetAxisValue(mNativePtr, AXIS_X, 0, HISTORY_CURRENT);
    }

    /**
     * {@link #getY(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @see #AXIS_Y
     */
    public final float getY() {
        return nativeGetAxisValue(mNativePtr, AXIS_Y, 0, HISTORY_CURRENT);
    }

    /**
     * {@link #getPressure(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @see #AXIS_PRESSURE
     */
    public final float getPressure() {
        return nativeGetAxisValue(mNativePtr, AXIS_PRESSURE, 0, HISTORY_CURRENT);
    }

    /**
     * {@link #getSize(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @see #AXIS_SIZE
     */
    public final float getSize() {
        return nativeGetAxisValue(mNativePtr, AXIS_SIZE, 0, HISTORY_CURRENT);
    }
    
    /**
     * {@link #getTouchMajor(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @see #AXIS_TOUCH_MAJOR
     */
    public final float getTouchMajor() {
        return nativeGetAxisValue(mNativePtr, AXIS_TOUCH_MAJOR, 0, HISTORY_CURRENT);
    }

    /**
     * {@link #getTouchMinor(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @see #AXIS_TOUCH_MINOR
     */
    public final float getTouchMinor() {
        return nativeGetAxisValue(mNativePtr, AXIS_TOUCH_MINOR, 0, HISTORY_CURRENT);
    }
    
    /**
     * {@link #getToolMajor(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @see #AXIS_TOOL_MAJOR
     */
    public final float getToolMajor() {
        return nativeGetAxisValue(mNativePtr, AXIS_TOOL_MAJOR, 0, HISTORY_CURRENT);
    }

    /**
     * {@link #getToolMinor(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @see #AXIS_TOOL_MINOR
     */
    public final float getToolMinor() {
        return nativeGetAxisValue(mNativePtr, AXIS_TOOL_MINOR, 0, HISTORY_CURRENT);
    }

    /**
     * {@link #getOrientation(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @see #AXIS_ORIENTATION
     */
    public final float getOrientation() {
        return nativeGetAxisValue(mNativePtr, AXIS_ORIENTATION, 0, HISTORY_CURRENT);
    }

    /**
     * {@link #getAxisValue(int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @param axis The axis identifier for the axis value to retrieve.
     *
     * @see #AXIS_X
     * @see #AXIS_Y
     */
    public final float getAxisValue(int axis) {
        return nativeGetAxisValue(mNativePtr, axis, 0, HISTORY_CURRENT);
    }

    /**
     * The number of pointers of data contained in this event.  Always
     * >= 1.
     */
    public final int getPointerCount() {
        return nativeGetPointerCount(mNativePtr);
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
        return nativeGetPointerId(mNativePtr, pointerIndex);
    }

    /**
     * Gets the tool type of a pointer for the given pointer index.
     * The tool type indicates the type of tool used to make contact such
     * as a finger or stylus, if known.
     *
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     * @return The tool type of the pointer.
     *
     * @see #TOOL_TYPE_UNKNOWN
     * @see #TOOL_TYPE_FINGER
     * @see #TOOL_TYPE_STYLUS
     * @see #TOOL_TYPE_MOUSE
     * @see #TOOL_TYPE_INDIRECT_FINGER
     * @see #TOOL_TYPE_INDIRECT_STYLUS
     */
    public final int getToolType(int pointerIndex) {
        return nativeGetToolType(mNativePtr, pointerIndex);
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
        return nativeFindPointerIndex(mNativePtr, pointerId);
    }

    /**
     * Returns the X coordinate of this event for the given pointer
     * <em>index</em> (use {@link #getPointerId(int)} to find the pointer
     * identifier for this index).
     * Whole numbers are pixels; the 
     * value may have a fraction for input devices that are sub-pixel precise. 
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     *
     * @see #AXIS_X
     */
    public final float getX(int pointerIndex) {
        return nativeGetAxisValue(mNativePtr, AXIS_X, pointerIndex, HISTORY_CURRENT);
    }

    /**
     * Returns the Y coordinate of this event for the given pointer
     * <em>index</em> (use {@link #getPointerId(int)} to find the pointer
     * identifier for this index).
     * Whole numbers are pixels; the
     * value may have a fraction for input devices that are sub-pixel precise.
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     *
     * @see #AXIS_Y
     */
    public final float getY(int pointerIndex) {
        return nativeGetAxisValue(mNativePtr, AXIS_Y, pointerIndex, HISTORY_CURRENT);
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
     *
     * @see #AXIS_PRESSURE
     */
    public final float getPressure(int pointerIndex) {
        return nativeGetAxisValue(mNativePtr, AXIS_PRESSURE, pointerIndex, HISTORY_CURRENT);
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
     *
     * @see #AXIS_SIZE
     */
    public final float getSize(int pointerIndex) {
        return nativeGetAxisValue(mNativePtr, AXIS_SIZE, pointerIndex, HISTORY_CURRENT);
    }
    
    /**
     * Returns the length of the major axis of an ellipse that describes the touch
     * area at the point of contact for the given pointer
     * <em>index</em> (use {@link #getPointerId(int)} to find the pointer
     * identifier for this index).
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     *
     * @see #AXIS_TOUCH_MAJOR
     */
    public final float getTouchMajor(int pointerIndex) {
        return nativeGetAxisValue(mNativePtr, AXIS_TOUCH_MAJOR, pointerIndex, HISTORY_CURRENT);
    }
    
    /**
     * Returns the length of the minor axis of an ellipse that describes the touch
     * area at the point of contact for the given pointer
     * <em>index</em> (use {@link #getPointerId(int)} to find the pointer
     * identifier for this index).
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     *
     * @see #AXIS_TOUCH_MINOR
     */
    public final float getTouchMinor(int pointerIndex) {
        return nativeGetAxisValue(mNativePtr, AXIS_TOUCH_MINOR, pointerIndex, HISTORY_CURRENT);
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
     *
     * @see #AXIS_TOOL_MAJOR
     */
    public final float getToolMajor(int pointerIndex) {
        return nativeGetAxisValue(mNativePtr, AXIS_TOOL_MAJOR, pointerIndex, HISTORY_CURRENT);
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
     *
     * @see #AXIS_TOOL_MINOR
     */
    public final float getToolMinor(int pointerIndex) {
        return nativeGetAxisValue(mNativePtr, AXIS_TOOL_MINOR, pointerIndex, HISTORY_CURRENT);
    }
    
    /**
     * Returns the orientation of the touch area and tool area in radians clockwise from vertical
     * for the given pointer <em>index</em> (use {@link #getPointerId(int)} to find the pointer
     * identifier for this index).
     * An angle of 0 radians indicates that the major axis of contact is oriented
     * upwards, is perfectly circular or is of unknown orientation.  A positive angle
     * indicates that the major axis of contact is oriented to the right.  A negative angle
     * indicates that the major axis of contact is oriented to the left.
     * The full range is from -PI/2 radians (finger pointing fully left) to PI/2 radians
     * (finger pointing fully right).
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     *
     * @see #AXIS_ORIENTATION
     */
    public final float getOrientation(int pointerIndex) {
        return nativeGetAxisValue(mNativePtr, AXIS_ORIENTATION, pointerIndex, HISTORY_CURRENT);
    }

    /**
     * Returns the value of the requested axis for the given pointer <em>index</em>
     * (use {@link #getPointerId(int)} to find the pointer identifier for this index).
     *
     * @param axis The axis identifier for the axis value to retrieve.
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     * @return The value of the axis, or 0 if the axis is not available.
     *
     * @see #AXIS_X
     * @see #AXIS_Y
     */
    public final float getAxisValue(int axis, int pointerIndex) {
        return nativeGetAxisValue(mNativePtr, axis, pointerIndex, HISTORY_CURRENT);
    }

    /**
     * Populates a {@link PointerCoords} object with pointer coordinate data for
     * the specified pointer index.
     * 
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     * @param outPointerCoords The pointer coordinate object to populate.
     *
     * @see PointerCoords
     */
    public final void getPointerCoords(int pointerIndex, PointerCoords outPointerCoords) {
        nativeGetPointerCoords(mNativePtr, pointerIndex, HISTORY_CURRENT, outPointerCoords);
    }

    /**
     * Populates a {@link PointerProperties} object with pointer properties for
     * the specified pointer index.
     *
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     * @param outPointerProperties The pointer properties object to populate.
     *
     * @see PointerProperties
     */
    public final void getPointerProperties(int pointerIndex,
            PointerProperties outPointerProperties) {
        nativeGetPointerProperties(mNativePtr, pointerIndex, outPointerProperties);
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
        return nativeGetMetaState(mNativePtr);
    }

    /**
     * Gets the state of all buttons that are pressed such as a mouse or stylus button.
     *
     * @return The button state.
     *
     * @see #BUTTON_PRIMARY
     * @see #BUTTON_SECONDARY
     * @see #BUTTON_TERTIARY
     * @see #BUTTON_FORWARD
     * @see #BUTTON_BACK
     */
    public final int getButtonState() {
        return nativeGetButtonState(mNativePtr);
    }

    /**
     * Returns the original raw X coordinate of this event.  For touch
     * events on the screen, this is the original location of the event
     * on the screen, before it had been adjusted for the containing window
     * and views.
     *
     * @see getX()
     * @see #AXIS_X
     */
    public final float getRawX() {
        return nativeGetRawAxisValue(mNativePtr, AXIS_X, 0, HISTORY_CURRENT);
    }

    /**
     * Returns the original raw Y coordinate of this event.  For touch
     * events on the screen, this is the original location of the event
     * on the screen, before it had been adjusted for the containing window
     * and views.
     *
     * @see getY()
     * @see #AXIS_Y
     */
    public final float getRawY() {
        return nativeGetRawAxisValue(mNativePtr, AXIS_Y, 0, HISTORY_CURRENT);
    }

    /**
     * Return the precision of the X coordinates being reported.  You can
     * multiply this number with {@link #getX} to find the actual hardware
     * value of the X coordinate.
     * @return Returns the precision of X coordinates being reported.
     *
     * @see #AXIS_X
     */
    public final float getXPrecision() {
        return nativeGetXPrecision(mNativePtr);
    }

    /**
     * Return the precision of the Y coordinates being reported.  You can
     * multiply this number with {@link #getY} to find the actual hardware
     * value of the Y coordinate.
     * @return Returns the precision of Y coordinates being reported.
     *
     * @see #AXIS_Y
     */
    public final float getYPrecision() {
        return nativeGetYPrecision(mNativePtr);
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
        return nativeGetHistorySize(mNativePtr);
    }

    /**
     * Returns the time that a historical movement occurred between this event
     * and the previous event, in the {@link android.os.SystemClock#uptimeMillis} time base.
     * <p>
     * This only applies to ACTION_MOVE events.
     * </p>
     *
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     * @return Returns the time that a historical movement occurred between this
     * event and the previous event,
     * in the {@link android.os.SystemClock#uptimeMillis} time base.
     *
     * @see #getHistorySize
     * @see #getEventTime
     */
    public final long getHistoricalEventTime(int pos) {
        return nativeGetEventTimeNanos(mNativePtr, pos) / NS_PER_MS;
    }

    /**
     * Returns the time that a historical movement occurred between this event
     * and the previous event, in the {@link android.os.SystemClock#uptimeMillis} time base
     * but with nanosecond (instead of millisecond) precision.
     * <p>
     * This only applies to ACTION_MOVE events.
     * </p><p>
     * The value is in nanosecond precision but it may not have nanosecond accuracy.
     * </p>
     *
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     * @return Returns the time that a historical movement occurred between this
     * event and the previous event,
     * in the {@link android.os.SystemClock#uptimeMillis} time base but with
     * nanosecond (instead of millisecond) precision.
     *
     * @see #getHistorySize
     * @see #getEventTime
     *
     * @hide
     */
    public final long getHistoricalEventTimeNano(int pos) {
        return nativeGetEventTimeNanos(mNativePtr, pos);
    }

    /**
     * {@link #getHistoricalX(int, int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     *
     * @see #getHistorySize
     * @see #getX()
     * @see #AXIS_X
     */
    public final float getHistoricalX(int pos) {
        return nativeGetAxisValue(mNativePtr, AXIS_X, 0, pos);
    }

    /**
     * {@link #getHistoricalY(int, int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     *
     * @see #getHistorySize
     * @see #getY()
     * @see #AXIS_Y
     */
    public final float getHistoricalY(int pos) {
        return nativeGetAxisValue(mNativePtr, AXIS_Y, 0, pos);
    }

    /**
     * {@link #getHistoricalPressure(int, int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     *
     * @see #getHistorySize
     * @see #getPressure()
     * @see #AXIS_PRESSURE
     */
    public final float getHistoricalPressure(int pos) {
        return nativeGetAxisValue(mNativePtr, AXIS_PRESSURE, 0, pos);
    }

    /**
     * {@link #getHistoricalSize(int, int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     *
     * @see #getHistorySize
     * @see #getSize()
     * @see #AXIS_SIZE
     */
    public final float getHistoricalSize(int pos) {
        return nativeGetAxisValue(mNativePtr, AXIS_SIZE, 0, pos);
    }

    /**
     * {@link #getHistoricalTouchMajor(int, int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     *
     * @see #getHistorySize
     * @see #getTouchMajor()
     * @see #AXIS_TOUCH_MAJOR
     */
    public final float getHistoricalTouchMajor(int pos) {
        return nativeGetAxisValue(mNativePtr, AXIS_TOUCH_MAJOR, 0, pos);
    }

    /**
     * {@link #getHistoricalTouchMinor(int, int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     *
     * @see #getHistorySize
     * @see #getTouchMinor()
     * @see #AXIS_TOUCH_MINOR
     */
    public final float getHistoricalTouchMinor(int pos) {
        return nativeGetAxisValue(mNativePtr, AXIS_TOUCH_MINOR, 0, pos);
    }
    
    /**
     * {@link #getHistoricalToolMajor(int, int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     *
     * @see #getHistorySize
     * @see #getToolMajor()
     * @see #AXIS_TOOL_MAJOR
     */
    public final float getHistoricalToolMajor(int pos) {
        return nativeGetAxisValue(mNativePtr, AXIS_TOOL_MAJOR, 0, pos);
    }

    /**
     * {@link #getHistoricalToolMinor(int, int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     *
     * @see #getHistorySize
     * @see #getToolMinor()
     * @see #AXIS_TOOL_MINOR
     */
    public final float getHistoricalToolMinor(int pos) {
        return nativeGetAxisValue(mNativePtr, AXIS_TOOL_MINOR, 0, pos);
    }
    
    /**
     * {@link #getHistoricalOrientation(int, int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     *
     * @see #getHistorySize
     * @see #getOrientation()
     * @see #AXIS_ORIENTATION
     */
    public final float getHistoricalOrientation(int pos) {
        return nativeGetAxisValue(mNativePtr, AXIS_ORIENTATION, 0, pos);
    }

    /**
     * {@link #getHistoricalAxisValue(int, int, int)} for the first pointer index (may be an
     * arbitrary pointer identifier).
     *
     * @param axis The axis identifier for the axis value to retrieve.
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     *
     * @see #getHistorySize
     * @see #getAxisValue(int)
     * @see #AXIS_X
     * @see #AXIS_Y
     */
    public final float getHistoricalAxisValue(int axis, int pos) {
        return nativeGetAxisValue(mNativePtr, axis, 0, pos);
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
     * @see #getX(int)
     * @see #AXIS_X
     */
    public final float getHistoricalX(int pointerIndex, int pos) {
        return nativeGetAxisValue(mNativePtr, AXIS_X, pointerIndex, pos);
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
     * @see #getY(int)
     * @see #AXIS_Y
     */
    public final float getHistoricalY(int pointerIndex, int pos) {
        return nativeGetAxisValue(mNativePtr, AXIS_Y, pointerIndex, pos);
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
     * @see #getPressure(int)
     * @see #AXIS_PRESSURE
     */
    public final float getHistoricalPressure(int pointerIndex, int pos) {
        return nativeGetAxisValue(mNativePtr, AXIS_PRESSURE, pointerIndex, pos);
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
     * @see #getSize(int)
     * @see #AXIS_SIZE
     */
    public final float getHistoricalSize(int pointerIndex, int pos) {
        return nativeGetAxisValue(mNativePtr, AXIS_SIZE, pointerIndex, pos);
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
     * @see #getTouchMajor(int)
     * @see #AXIS_TOUCH_MAJOR
     */
    public final float getHistoricalTouchMajor(int pointerIndex, int pos) {
        return nativeGetAxisValue(mNativePtr, AXIS_TOUCH_MAJOR, pointerIndex, pos);
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
     * @see #getTouchMinor(int)
     * @see #AXIS_TOUCH_MINOR
     */
    public final float getHistoricalTouchMinor(int pointerIndex, int pos) {
        return nativeGetAxisValue(mNativePtr, AXIS_TOUCH_MINOR, pointerIndex, pos);
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
     * @see #getToolMajor(int)
     * @see #AXIS_TOOL_MAJOR
     */
    public final float getHistoricalToolMajor(int pointerIndex, int pos) {
        return nativeGetAxisValue(mNativePtr, AXIS_TOOL_MAJOR, pointerIndex, pos);
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
     * @see #getToolMinor(int)
     * @see #AXIS_TOOL_MINOR
     */
    public final float getHistoricalToolMinor(int pointerIndex, int pos) {
        return nativeGetAxisValue(mNativePtr, AXIS_TOOL_MINOR, pointerIndex, pos);
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
     * @see #getOrientation(int)
     * @see #AXIS_ORIENTATION
     */
    public final float getHistoricalOrientation(int pointerIndex, int pos) {
        return nativeGetAxisValue(mNativePtr, AXIS_ORIENTATION, pointerIndex, pos);
    }

    /**
     * Returns the historical value of the requested axis, as per {@link #getAxisValue(int, int)},
     * occurred between this event and the previous event for the given pointer.
     * Only applies to ACTION_MOVE events.
     *
     * @param axis The axis identifier for the axis value to retrieve.
     * @param pointerIndex Raw index of pointer to retrieve.  Value may be from 0
     * (the first pointer that is down) to {@link #getPointerCount()}-1.
     * @param pos Which historical value to return; must be less than
     * {@link #getHistorySize}
     * @return The value of the axis, or 0 if the axis is not available.
     *
     * @see #AXIS_X
     * @see #AXIS_Y
     */
    public final float getHistoricalAxisValue(int axis, int pointerIndex, int pos) {
        return nativeGetAxisValue(mNativePtr, axis, pointerIndex, pos);
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
     * @see PointerCoords
     */
    public final void getHistoricalPointerCoords(int pointerIndex, int pos,
            PointerCoords outPointerCoords) {
        nativeGetPointerCoords(mNativePtr, pointerIndex, pos, outPointerCoords);
    }
    
    /**
     * Returns a bitfield indicating which edges, if any, were touched by this
     * MotionEvent. For touch events, clients can use this to determine if the
     * user's finger was touching the edge of the display.
     *
     * This property is only set for {@link #ACTION_DOWN} events.
     *
     * @see #EDGE_LEFT
     * @see #EDGE_TOP
     * @see #EDGE_RIGHT
     * @see #EDGE_BOTTOM
     */
    public final int getEdgeFlags() {
        return nativeGetEdgeFlags(mNativePtr);
    }

    /**
     * Sets the bitfield indicating which edges, if any, were touched by this
     * MotionEvent.
     *
     * @see #getEdgeFlags()
     */
    public final void setEdgeFlags(int flags) {
        nativeSetEdgeFlags(mNativePtr, flags);
    }

    /**
     * Sets this event's action.
     */
    public final void setAction(int action) {
        nativeSetAction(mNativePtr, action);
    }

    /**
     * Adjust this event's location.
     * @param deltaX Amount to add to the current X coordinate of the event.
     * @param deltaY Amount to add to the current Y coordinate of the event.
     */
    public final void offsetLocation(float deltaX, float deltaY) {
        if (deltaX != 0.0f || deltaY != 0.0f) {
            nativeOffsetLocation(mNativePtr, deltaX, deltaY);
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
        float oldX = getX();
        float oldY = getY();
        offsetLocation(x - oldX, y - oldY);
    }
    
    /**
     * Applies a transformation matrix to all of the points in the event.
     *
     * @param matrix The transformation matrix to apply.
     */
    public final void transform(Matrix matrix) {
        if (matrix == null) {
            throw new IllegalArgumentException("matrix must not be null");
        }

        nativeTransform(mNativePtr, matrix);
    }

    /**
     * Add a new movement to the batch of movements in this event.  The event's
     * current location, position and size is updated to the new values.
     * The current values in the event are added to a list of historical values.
     *
     * Only applies to {@link #ACTION_MOVE} or {@link #ACTION_HOVER_MOVE} events.
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
        synchronized (gSharedTempLock) {
            ensureSharedTempPointerCapacity(1);
            final PointerCoords[] pc = gSharedTempPointerCoords;
            pc[0].clear();
            pc[0].x = x;
            pc[0].y = y;
            pc[0].pressure = pressure;
            pc[0].size = size;

            nativeAddBatch(mNativePtr, eventTime * NS_PER_MS, pc, metaState);
        }
    }

    /**
     * Add a new movement to the batch of movements in this event.  The event's
     * current location, position and size is updated to the new values.
     * The current values in the event are added to a list of historical values.
     *
     * Only applies to {@link #ACTION_MOVE} or {@link #ACTION_HOVER_MOVE} events.
     *
     * @param eventTime The time stamp (in ms) for this data.
     * @param pointerCoords The new pointer coordinates.
     * @param metaState Meta key state.
     */
    public final void addBatch(long eventTime, PointerCoords[] pointerCoords, int metaState) {
        nativeAddBatch(mNativePtr, eventTime * NS_PER_MS, pointerCoords, metaState);
    }

    /**
     * Adds all of the movement samples of the specified event to this one if
     * it is compatible.  To be compatible, the event must have the same device id,
     * source, action, flags, pointer count, pointer properties.
     *
     * Only applies to {@link #ACTION_MOVE} or {@link #ACTION_HOVER_MOVE} events.
     *
     * @param event The event whose movements samples should be added to this one
     * if possible.
     * @return True if batching was performed or false if batching was not possible.
     * @hide
     */
    public final boolean addBatch(MotionEvent event) {
        final int action = nativeGetAction(mNativePtr);
        if (action != ACTION_MOVE && action != ACTION_HOVER_MOVE) {
            return false;
        }
        if (action != nativeGetAction(event.mNativePtr)) {
            return false;
        }

        if (nativeGetDeviceId(mNativePtr) != nativeGetDeviceId(event.mNativePtr)
                || nativeGetSource(mNativePtr) != nativeGetSource(event.mNativePtr)
                || nativeGetFlags(mNativePtr) != nativeGetFlags(event.mNativePtr)) {
            return false;
        }

        final int pointerCount = nativeGetPointerCount(mNativePtr);
        if (pointerCount != nativeGetPointerCount(event.mNativePtr)) {
            return false;
        }

        synchronized (gSharedTempLock) {
            ensureSharedTempPointerCapacity(Math.max(pointerCount, 2));
            final PointerProperties[] pp = gSharedTempPointerProperties;
            final PointerCoords[] pc = gSharedTempPointerCoords;

            for (int i = 0; i < pointerCount; i++) {
                nativeGetPointerProperties(mNativePtr, i, pp[0]);
                nativeGetPointerProperties(event.mNativePtr, i, pp[1]);
                if (!pp[0].equals(pp[1])) {
                    return false;
                }
            }

            final int metaState = nativeGetMetaState(event.mNativePtr);
            final int historySize = nativeGetHistorySize(event.mNativePtr);
            for (int h = 0; h <= historySize; h++) {
                final int historyPos = (h == historySize ? HISTORY_CURRENT : h);

                for (int i = 0; i < pointerCount; i++) {
                    nativeGetPointerCoords(event.mNativePtr, i, historyPos, pc[i]);
                }

                final long eventTimeNanos = nativeGetEventTimeNanos(event.mNativePtr, historyPos);
                nativeAddBatch(mNativePtr, eventTimeNanos, pc, metaState);
            }
        }
        return true;
    }

    /**
     * Returns true if all points in the motion event are completely within the specified bounds.
     * @hide
     */
    public final boolean isWithinBoundsNoHistory(float left, float top,
            float right, float bottom) {
        final int pointerCount = nativeGetPointerCount(mNativePtr);
        for (int i = 0; i < pointerCount; i++) {
            final float x = nativeGetAxisValue(mNativePtr, AXIS_X, i, HISTORY_CURRENT);
            final float y = nativeGetAxisValue(mNativePtr, AXIS_Y, i, HISTORY_CURRENT);
            if (x < left || x > right || y < top || y > bottom) {
                return false;
            }
        }
        return true;
    }

    private static final float clamp(float value, float low, float high) {
        if (value < low) {
            return low;
        } else if (value > high) {
            return high;
        }
        return value;
    }

    /**
     * Returns a new motion events whose points have been clamped to the specified bounds.
     * @hide
     */
    public final MotionEvent clampNoHistory(float left, float top, float right, float bottom) {
        MotionEvent ev = obtain();
        synchronized (gSharedTempLock) {
            final int pointerCount = nativeGetPointerCount(mNativePtr);

            ensureSharedTempPointerCapacity(pointerCount);
            final PointerProperties[] pp = gSharedTempPointerProperties;
            final PointerCoords[] pc = gSharedTempPointerCoords;

            for (int i = 0; i < pointerCount; i++) {
                nativeGetPointerProperties(mNativePtr, i, pp[i]);
                nativeGetPointerCoords(mNativePtr, i, HISTORY_CURRENT, pc[i]);
                pc[i].x = clamp(pc[i].x, left, right);
                pc[i].y = clamp(pc[i].y, top, bottom);
            }
            ev.mNativePtr = nativeInitialize(ev.mNativePtr,
                    nativeGetDeviceId(mNativePtr), nativeGetSource(mNativePtr),
                    nativeGetAction(mNativePtr), nativeGetFlags(mNativePtr),
                    nativeGetEdgeFlags(mNativePtr), nativeGetMetaState(mNativePtr),
                    nativeGetButtonState(mNativePtr),
                    nativeGetXOffset(mNativePtr), nativeGetYOffset(mNativePtr),
                    nativeGetXPrecision(mNativePtr), nativeGetYPrecision(mNativePtr),
                    nativeGetDownTimeNanos(mNativePtr),
                    nativeGetEventTimeNanos(mNativePtr, HISTORY_CURRENT),
                    pointerCount, pp, pc);
            return ev;
        }
    }

    /**
     * Gets an integer where each pointer id present in the event is marked as a bit.
     * @hide
     */
    public final int getPointerIdBits() {
        int idBits = 0;
        final int pointerCount = nativeGetPointerCount(mNativePtr);
        for (int i = 0; i < pointerCount; i++) {
            idBits |= 1 << nativeGetPointerId(mNativePtr, i);
        }
        return idBits;
    }

    /**
     * Splits a motion event such that it includes only a subset of pointer ids.
     * @hide
     */
    public final MotionEvent split(int idBits) {
        MotionEvent ev = obtain();
        synchronized (gSharedTempLock) {
            final int oldPointerCount = nativeGetPointerCount(mNativePtr);
            ensureSharedTempPointerCapacity(oldPointerCount);
            final PointerProperties[] pp = gSharedTempPointerProperties;
            final PointerCoords[] pc = gSharedTempPointerCoords;
            final int[] map = gSharedTempPointerIndexMap;

            final int oldAction = nativeGetAction(mNativePtr);
            final int oldActionMasked = oldAction & ACTION_MASK;
            final int oldActionPointerIndex = (oldAction & ACTION_POINTER_INDEX_MASK)
                    >> ACTION_POINTER_INDEX_SHIFT;
            int newActionPointerIndex = -1;
            int newPointerCount = 0;
            int newIdBits = 0;
            for (int i = 0; i < oldPointerCount; i++) {
                nativeGetPointerProperties(mNativePtr, i, pp[newPointerCount]);
                final int idBit = 1 << pp[newPointerCount].id;
                if ((idBit & idBits) != 0) {
                    if (i == oldActionPointerIndex) {
                        newActionPointerIndex = newPointerCount;
                    }
                    map[newPointerCount] = i;
                    newPointerCount += 1;
                    newIdBits |= idBit;
                }
            }

            if (newPointerCount == 0) {
                throw new IllegalArgumentException("idBits did not match any ids in the event");
            }

            final int newAction;
            if (oldActionMasked == ACTION_POINTER_DOWN || oldActionMasked == ACTION_POINTER_UP) {
                if (newActionPointerIndex < 0) {
                    // An unrelated pointer changed.
                    newAction = ACTION_MOVE;
                } else if (newPointerCount == 1) {
                    // The first/last pointer went down/up.
                    newAction = oldActionMasked == ACTION_POINTER_DOWN
                            ? ACTION_DOWN : ACTION_UP;
                } else {
                    // A secondary pointer went down/up.
                    newAction = oldActionMasked
                            | (newActionPointerIndex << ACTION_POINTER_INDEX_SHIFT);
                }
            } else {
                // Simple up/down/cancel/move or other motion action.
                newAction = oldAction;
            }

            final int historySize = nativeGetHistorySize(mNativePtr);
            for (int h = 0; h <= historySize; h++) {
                final int historyPos = h == historySize ? HISTORY_CURRENT : h;

                for (int i = 0; i < newPointerCount; i++) {
                    nativeGetPointerCoords(mNativePtr, map[i], historyPos, pc[i]);
                }

                final long eventTimeNanos = nativeGetEventTimeNanos(mNativePtr, historyPos);
                if (h == 0) {
                    ev.mNativePtr = nativeInitialize(ev.mNativePtr,
                            nativeGetDeviceId(mNativePtr), nativeGetSource(mNativePtr),
                            newAction, nativeGetFlags(mNativePtr),
                            nativeGetEdgeFlags(mNativePtr), nativeGetMetaState(mNativePtr),
                            nativeGetButtonState(mNativePtr),
                            nativeGetXOffset(mNativePtr), nativeGetYOffset(mNativePtr),
                            nativeGetXPrecision(mNativePtr), nativeGetYPrecision(mNativePtr),
                            nativeGetDownTimeNanos(mNativePtr), eventTimeNanos,
                            newPointerCount, pp, pc);
                } else {
                    nativeAddBatch(ev.mNativePtr, eventTimeNanos, pc, 0);
                }
            }
            return ev;
        }
    }

    @Override
    public String toString() {
        StringBuilder msg = new StringBuilder();
        msg.append("MotionEvent { action=").append(actionToString(getAction()));

        final int pointerCount = getPointerCount();
        for (int i = 0; i < pointerCount; i++) {
            msg.append(", id[").append(i).append("]=").append(getPointerId(i));
            msg.append(", x[").append(i).append("]=").append(getX(i));
            msg.append(", y[").append(i).append("]=").append(getY(i));
            msg.append(", toolType[").append(i).append("]=").append(
                    toolTypeToString(getToolType(i)));
        }

        msg.append(", buttonState=").append(MotionEvent.buttonStateToString(getButtonState()));
        msg.append(", metaState=").append(KeyEvent.metaStateToString(getMetaState()));
        msg.append(", flags=0x").append(Integer.toHexString(getFlags()));
        msg.append(", edgeFlags=0x").append(Integer.toHexString(getEdgeFlags()));
        msg.append(", pointerCount=").append(pointerCount);
        msg.append(", historySize=").append(getHistorySize());
        msg.append(", eventTime=").append(getEventTime());
        msg.append(", downTime=").append(getDownTime());
        msg.append(", deviceId=").append(getDeviceId());
        msg.append(", source=0x").append(Integer.toHexString(getSource()));
        msg.append(" }");
        return msg.toString();
    }

    /**
     * Returns a string that represents the symbolic name of the specified action
     * such as "ACTION_DOWN", "ACTION_POINTER_DOWN(3)" or an equivalent numeric constant
     * such as "35" if unknown.
     *
     * @param action The action.
     * @return The symbolic name of the specified action.
     * @hide
     */
    public static String actionToString(int action) {
        switch (action) {
            case ACTION_DOWN:
                return "ACTION_DOWN";
            case ACTION_UP:
                return "ACTION_UP";
            case ACTION_CANCEL:
                return "ACTION_CANCEL";
            case ACTION_OUTSIDE:
                return "ACTION_OUTSIDE";
            case ACTION_MOVE:
                return "ACTION_MOVE";
            case ACTION_HOVER_MOVE:
                return "ACTION_HOVER_MOVE";
            case ACTION_SCROLL:
                return "ACTION_SCROLL";
            case ACTION_HOVER_ENTER:
                return "ACTION_HOVER_ENTER";
            case ACTION_HOVER_EXIT:
                return "ACTION_HOVER_EXIT";
        }
        int index = (action & ACTION_POINTER_INDEX_MASK) >> ACTION_POINTER_INDEX_SHIFT;
        switch (action & ACTION_MASK) {
            case ACTION_POINTER_DOWN:
                return "ACTION_POINTER_DOWN(" + index + ")";
            case ACTION_POINTER_UP:
                return "ACTION_POINTER_UP(" + index + ")";
            default:
                return Integer.toString(action);
        }
    }

    /**
     * Returns a string that represents the symbolic name of the specified axis
     * such as "AXIS_X" or an equivalent numeric constant such as "42" if unknown.
     *
     * @param axis The axis
     * @return The symbolic name of the specified axis.
     */
    public static String axisToString(int axis) {
        String symbolicName = AXIS_SYMBOLIC_NAMES.get(axis);
        return symbolicName != null ? symbolicName : Integer.toString(axis);
    }

    /**
     * Gets an axis by its symbolic name such as "AXIS_X" or an
     * equivalent numeric constant such as "42".
     *
     * @param symbolicName The symbolic name of the axis.
     * @return The axis or -1 if not found.
     * @see #keycodeToString
     */
    public static int axisFromString(String symbolicName) {
        if (symbolicName == null) {
            throw new IllegalArgumentException("symbolicName must not be null");
        }

        final int count = AXIS_SYMBOLIC_NAMES.size();
        for (int i = 0; i < count; i++) {
            if (symbolicName.equals(AXIS_SYMBOLIC_NAMES.valueAt(i))) {
                return i;
            }
        }

        try {
            return Integer.parseInt(symbolicName, 10);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /**
     * Returns a string that represents the symbolic name of the specified combined
     * button state flags such as "0", "BUTTON_PRIMARY",
     * "BUTTON_PRIMARY|BUTTON_SECONDARY" or an equivalent numeric constant such as "0x10000000"
     * if unknown.
     *
     * @param buttonState The button state.
     * @return The symbolic name of the specified combined button state flags.
     * @hide
     */
    public static String buttonStateToString(int buttonState) {
        if (buttonState == 0) {
            return "0";
        }
        StringBuilder result = null;
        int i = 0;
        while (buttonState != 0) {
            final boolean isSet = (buttonState & 1) != 0;
            buttonState >>>= 1; // unsigned shift!
            if (isSet) {
                final String name = BUTTON_SYMBOLIC_NAMES[i];
                if (result == null) {
                    if (buttonState == 0) {
                        return name;
                    }
                    result = new StringBuilder(name);
                } else {
                    result.append('|');
                    result.append(name);
                }
            }
            i += 1;
        }
        return result.toString();
    }

    /**
     * Returns a string that represents the symbolic name of the specified tool type
     * such as "TOOL_TYPE_FINGER" or an equivalent numeric constant such as "42" if unknown.
     *
     * @param toolType The tool type.
     * @return The symbolic name of the specified tool type.
     * @hide
     */
    public static String toolTypeToString(int toolType) {
        String symbolicName = TOOL_TYPE_SYMBOLIC_NAMES.get(toolType);
        return symbolicName != null ? symbolicName : Integer.toString(toolType);
    }

    public static final Parcelable.Creator<MotionEvent> CREATOR
            = new Parcelable.Creator<MotionEvent>() {
        public MotionEvent createFromParcel(Parcel in) {
            in.readInt(); // skip token, we already know this is a MotionEvent
            return MotionEvent.createFromParcelBody(in);
        }

        public MotionEvent[] newArray(int size) {
            return new MotionEvent[size];
        }
    };

    /** @hide */
    public static MotionEvent createFromParcelBody(Parcel in) {
        MotionEvent ev = obtain();
        ev.mNativePtr = nativeReadFromParcel(ev.mNativePtr, in);
        return ev;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(PARCEL_TOKEN_MOTION_EVENT);
        nativeWriteToParcel(mNativePtr, out);
    }

    /**
     * Transfer object for pointer coordinates.
     * 
     * Objects of this type can be used to specify the pointer coordinates when
     * creating new {@link MotionEvent} objects and to query pointer coordinates
     * in bulk.
     * 
     * Refer to {@link InputDevice} for information about how different kinds of
     * input devices and sources represent pointer coordinates.
     */
    public static final class PointerCoords {
        private static final int INITIAL_PACKED_AXIS_VALUES = 8;
        private long mPackedAxisBits;
        private float[] mPackedAxisValues;

        /**
         * Creates a pointer coords object with all axes initialized to zero.
         */
        public PointerCoords() {
        }

        /**
         * Creates a pointer coords object as a copy of the
         * contents of another pointer coords object.
         *
         * @param other The pointer coords object to copy.
         */
        public PointerCoords(PointerCoords other) {
            copyFrom(other);
        }

        /** @hide */
        public static PointerCoords[] createArray(int size) {
            PointerCoords[] array = new PointerCoords[size];
            for (int i = 0; i < size; i++) {
                array[i] = new PointerCoords();
            }
            return array;
        }

        /**
         * The X component of the pointer movement.
         *
         * @see MotionEvent#AXIS_X
         */
        public float x;
        
        /**
         * The Y component of the pointer movement.
         *
         * @see MotionEvent#AXIS_Y
         */
        public float y;
        
        /**
         * A normalized value that describes the pressure applied to the device
         * by a finger or other tool.
         * The pressure generally ranges from 0 (no pressure at all) to 1 (normal pressure),
         * although values higher than 1 may be generated depending on the calibration of
         * the input device.
         *
         * @see MotionEvent#AXIS_PRESSURE
         */
        public float pressure;
        
        /**
         * A normalized value that describes the approximate size of the pointer touch area
         * in relation to the maximum detectable size of the device.
         * It represents some approximation of the area of the screen being
         * pressed; the actual value in pixels corresponding to the
         * touch is normalized with the device specific range of values
         * and scaled to a value between 0 and 1. The value of size can be used to
         * determine fat touch events.
         *
         * @see MotionEvent#AXIS_SIZE
         */
        public float size;
        
        /**
         * The length of the major axis of an ellipse that describes the touch area at
         * the point of contact.
         * If the device is a touch screen, the length is reported in pixels, otherwise it is
         * reported in device-specific units.
         *
         * @see MotionEvent#AXIS_TOUCH_MAJOR
         */
        public float touchMajor;
        
        /**
         * The length of the minor axis of an ellipse that describes the touch area at
         * the point of contact.
         * If the device is a touch screen, the length is reported in pixels, otherwise it is
         * reported in device-specific units.
         *
         * @see MotionEvent#AXIS_TOUCH_MINOR
         */
        public float touchMinor;
        
        /**
         * The length of the major axis of an ellipse that describes the size of
         * the approaching tool.
         * The tool area represents the estimated size of the finger or pen that is
         * touching the device independent of its actual touch area at the point of contact.
         * If the device is a touch screen, the length is reported in pixels, otherwise it is
         * reported in device-specific units.
         *
         * @see MotionEvent#AXIS_TOOL_MAJOR
         */
        public float toolMajor;
        
        /**
         * The length of the minor axis of an ellipse that describes the size of
         * the approaching tool.
         * The tool area represents the estimated size of the finger or pen that is
         * touching the device independent of its actual touch area at the point of contact.
         * If the device is a touch screen, the length is reported in pixels, otherwise it is
         * reported in device-specific units.
         *
         * @see MotionEvent#AXIS_TOOL_MINOR
         */
        public float toolMinor;
        
        /**
         * The orientation of the touch area and tool area in radians clockwise from vertical.
         * An angle of 0 radians indicates that the major axis of contact is oriented
         * upwards, is perfectly circular or is of unknown orientation.  A positive angle
         * indicates that the major axis of contact is oriented to the right.  A negative angle
         * indicates that the major axis of contact is oriented to the left.
         * The full range is from -PI/2 radians (finger pointing fully left) to PI/2 radians
         * (finger pointing fully right).
         *
         * @see MotionEvent#AXIS_ORIENTATION
         */
        public float orientation;

        /**
         * Clears the contents of this object.
         * Resets all axes to zero.
         */
        public void clear() {
            mPackedAxisBits = 0;

            x = 0;
            y = 0;
            pressure = 0;
            size = 0;
            touchMajor = 0;
            touchMinor = 0;
            toolMajor = 0;
            toolMinor = 0;
            orientation = 0;
        }

        /**
         * Copies the contents of another pointer coords object.
         *
         * @param other The pointer coords object to copy.
         */
        public void copyFrom(PointerCoords other) {
            final long bits = other.mPackedAxisBits;
            mPackedAxisBits = bits;
            if (bits != 0) {
                final float[] otherValues = other.mPackedAxisValues;
                final int count = Long.bitCount(bits);
                float[] values = mPackedAxisValues;
                if (values == null || count > values.length) {
                    values = new float[otherValues.length];
                    mPackedAxisValues = values;
                }
                System.arraycopy(otherValues, 0, values, 0, count);
            }

            x = other.x;
            y = other.y;
            pressure = other.pressure;
            size = other.size;
            touchMajor = other.touchMajor;
            touchMinor = other.touchMinor;
            toolMajor = other.toolMajor;
            toolMinor = other.toolMinor;
            orientation = other.orientation;
        }

        /**
         * Gets the value associated with the specified axis.
         *
         * @param axis The axis identifier for the axis value to retrieve.
         * @return The value associated with the axis, or 0 if none.
         *
         * @see MotionEvent#AXIS_X
         * @see MotionEvent#AXIS_Y
         */
        public float getAxisValue(int axis) {
            switch (axis) {
                case AXIS_X:
                    return x;
                case AXIS_Y:
                    return y;
                case AXIS_PRESSURE:
                    return pressure;
                case AXIS_SIZE:
                    return size;
                case AXIS_TOUCH_MAJOR:
                    return touchMajor;
                case AXIS_TOUCH_MINOR:
                    return touchMinor;
                case AXIS_TOOL_MAJOR:
                    return toolMajor;
                case AXIS_TOOL_MINOR:
                    return toolMinor;
                case AXIS_ORIENTATION:
                    return orientation;
                default: {
                    if (axis < 0 || axis > 63) {
                        throw new IllegalArgumentException("Axis out of range.");
                    }
                    final long bits = mPackedAxisBits;
                    final long axisBit = 1L << axis;
                    if ((bits & axisBit) == 0) {
                        return 0;
                    }
                    final int index = Long.bitCount(bits & (axisBit - 1L));
                    return mPackedAxisValues[index];
                }
            }
        }

        /**
         * Sets the value associated with the specified axis.
         *
         * @param axis The axis identifier for the axis value to assign.
         * @param value The value to set.
         *
         * @see MotionEvent#AXIS_X
         * @see MotionEvent#AXIS_Y
         */
        public void setAxisValue(int axis, float value) {
            switch (axis) {
                case AXIS_X:
                    x = value;
                    break;
                case AXIS_Y:
                    y = value;
                    break;
                case AXIS_PRESSURE:
                    pressure = value;
                    break;
                case AXIS_SIZE:
                    size = value;
                    break;
                case AXIS_TOUCH_MAJOR:
                    touchMajor = value;
                    break;
                case AXIS_TOUCH_MINOR:
                    touchMinor = value;
                    break;
                case AXIS_TOOL_MAJOR:
                    toolMajor = value;
                    break;
                case AXIS_TOOL_MINOR:
                    toolMinor = value;
                    break;
                case AXIS_ORIENTATION:
                    orientation = value;
                    break;
                default: {
                    if (axis < 0 || axis > 63) {
                        throw new IllegalArgumentException("Axis out of range.");
                    }
                    final long bits = mPackedAxisBits;
                    final long axisBit = 1L << axis;
                    final int index = Long.bitCount(bits & (axisBit - 1L));
                    float[] values = mPackedAxisValues;
                    if ((bits & axisBit) == 0) {
                        if (values == null) {
                            values = new float[INITIAL_PACKED_AXIS_VALUES];
                            mPackedAxisValues = values;
                        } else {
                            final int count = Long.bitCount(bits);
                            if (count < values.length) {
                                if (index != count) {
                                    System.arraycopy(values, index, values, index + 1,
                                            count - index);
                                }
                            } else {
                                float[] newValues = new float[count * 2];
                                System.arraycopy(values, 0, newValues, 0, index);
                                System.arraycopy(values, index, newValues, index + 1,
                                        count - index);
                                values = newValues;
                                mPackedAxisValues = values;
                            }
                        }
                        mPackedAxisBits = bits | axisBit;
                    }
                    values[index] = value;
                }
            }
        }
    }

    /**
     * Transfer object for pointer properties.
     *
     * Objects of this type can be used to specify the pointer id and tool type
     * when creating new {@link MotionEvent} objects and to query pointer properties in bulk.
     */
    public static final class PointerProperties {
        /**
         * Creates a pointer properties object with an invalid pointer id.
         */
        public PointerProperties() {
            clear();
        }

        /**
         * Creates a pointer properties object as a copy of the contents of
         * another pointer properties object.
         * @param other
         */
        public PointerProperties(PointerProperties other) {
            copyFrom(other);
        }

        /** @hide */
        public static PointerProperties[] createArray(int size) {
            PointerProperties[] array = new PointerProperties[size];
            for (int i = 0; i < size; i++) {
                array[i] = new PointerProperties();
            }
            return array;
        }

        /**
         * The pointer id.
         * Initially set to {@link #INVALID_POINTER_ID} (-1).
         *
         * @see MotionEvent#getPointerId(int)
         */
        public int id;

        /**
         * The pointer tool type.
         * Initially set to 0.
         *
         * @see MotionEvent#getToolType(int)
         */
        public int toolType;

        /**
         * Resets the pointer properties to their initial values.
         */
        public void clear() {
            id = INVALID_POINTER_ID;
            toolType = TOOL_TYPE_UNKNOWN;
        }

        /**
         * Copies the contents of another pointer properties object.
         *
         * @param other The pointer properties object to copy.
         */
        public void copyFrom(PointerProperties other) {
            id = other.id;
            toolType = other.toolType;
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof PointerProperties) {
                return equals((PointerProperties)other);
            }
            return false;
        }

        private boolean equals(PointerProperties other) {
            return other != null && id == other.id && toolType == other.toolType;
        }

        @Override
        public int hashCode() {
            return id | (toolType << 8);
        }
    }
}
