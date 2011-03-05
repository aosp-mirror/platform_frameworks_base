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

#ifndef _ANDROID_INPUT_H
#define _ANDROID_INPUT_H

/******************************************************************
 *
 * IMPORTANT NOTICE:
 *
 *   This file is part of Android's set of stable system headers
 *   exposed by the Android NDK (Native Development Kit).
 *
 *   Third-party source AND binary code relies on the definitions
 *   here to be FROZEN ON ALL UPCOMING PLATFORM RELEASES.
 *
 *   - DO NOT MODIFY ENUMS (EXCEPT IF YOU ADD NEW 32-BIT VALUES)
 *   - DO NOT MODIFY CONSTANTS OR FUNCTIONAL MACROS
 *   - DO NOT CHANGE THE SIGNATURE OF FUNCTIONS IN ANY WAY
 *   - DO NOT CHANGE THE LAYOUT OR SIZE OF STRUCTURES
 */

/*
 * Structures and functions to receive and process input events in
 * native code.
 *
 * NOTE: These functions MUST be implemented by /system/lib/libui.so
 */

#include <stdint.h>
#include <sys/types.h>
#include <android/keycodes.h>
#include <android/looper.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Key states (may be returned by queries about the current state of a
 * particular key code, scan code or switch).
 */
enum {
    /* The key state is unknown or the requested key itself is not supported. */
    AKEY_STATE_UNKNOWN = -1,

    /* The key is up. */
    AKEY_STATE_UP = 0,

    /* The key is down. */
    AKEY_STATE_DOWN = 1,

    /* The key is down but is a virtual key press that is being emulated by the system. */
    AKEY_STATE_VIRTUAL = 2
};

/*
 * Meta key / modifer state.
 */
enum {
    /* No meta keys are pressed. */
    AMETA_NONE = 0,

    /* This mask is used to check whether one of the ALT meta keys is pressed. */
    AMETA_ALT_ON = 0x02,

    /* This mask is used to check whether the left ALT meta key is pressed. */
    AMETA_ALT_LEFT_ON = 0x10,

    /* This mask is used to check whether the right ALT meta key is pressed. */
    AMETA_ALT_RIGHT_ON = 0x20,

    /* This mask is used to check whether one of the SHIFT meta keys is pressed. */
    AMETA_SHIFT_ON = 0x01,

    /* This mask is used to check whether the left SHIFT meta key is pressed. */
    AMETA_SHIFT_LEFT_ON = 0x40,

    /* This mask is used to check whether the right SHIFT meta key is pressed. */
    AMETA_SHIFT_RIGHT_ON = 0x80,

    /* This mask is used to check whether the SYM meta key is pressed. */
    AMETA_SYM_ON = 0x04,

    /* This mask is used to check whether the FUNCTION meta key is pressed. */
    AMETA_FUNCTION_ON = 0x08,

    /* This mask is used to check whether one of the CTRL meta keys is pressed. */
    AMETA_CTRL_ON = 0x1000,

    /* This mask is used to check whether the left CTRL meta key is pressed. */
    AMETA_CTRL_LEFT_ON = 0x2000,

    /* This mask is used to check whether the right CTRL meta key is pressed. */
    AMETA_CTRL_RIGHT_ON = 0x4000,

    /* This mask is used to check whether one of the META meta keys is pressed. */
    AMETA_META_ON = 0x10000,

    /* This mask is used to check whether the left META meta key is pressed. */
    AMETA_META_LEFT_ON = 0x20000,

    /* This mask is used to check whether the right META meta key is pressed. */
    AMETA_META_RIGHT_ON = 0x40000,

    /* This mask is used to check whether the CAPS LOCK meta key is on. */
    AMETA_CAPS_LOCK_ON = 0x100000,

    /* This mask is used to check whether the NUM LOCK meta key is on. */
    AMETA_NUM_LOCK_ON = 0x200000,

    /* This mask is used to check whether the SCROLL LOCK meta key is on. */
    AMETA_SCROLL_LOCK_ON = 0x400000,
};

/*
 * Input events.
 *
 * Input events are opaque structures.  Use the provided accessors functions to
 * read their properties.
 */
struct AInputEvent;
typedef struct AInputEvent AInputEvent;

/*
 * Input event types.
 */
enum {
    /* Indicates that the input event is a key event. */
    AINPUT_EVENT_TYPE_KEY = 1,

    /* Indicates that the input event is a motion event. */
    AINPUT_EVENT_TYPE_MOTION = 2
};

/*
 * Key event actions.
 */
enum {
    /* The key has been pressed down. */
    AKEY_EVENT_ACTION_DOWN = 0,

    /* The key has been released. */
    AKEY_EVENT_ACTION_UP = 1,

    /* Multiple duplicate key events have occurred in a row, or a complex string is
     * being delivered.  The repeat_count property of the key event contains the number
     * of times the given key code should be executed.
     */
    AKEY_EVENT_ACTION_MULTIPLE = 2
};

/*
 * Key event flags.
 */
enum {
    /* This mask is set if the device woke because of this key event. */
    AKEY_EVENT_FLAG_WOKE_HERE = 0x1,

    /* This mask is set if the key event was generated by a software keyboard. */
    AKEY_EVENT_FLAG_SOFT_KEYBOARD = 0x2,

    /* This mask is set if we don't want the key event to cause us to leave touch mode. */
    AKEY_EVENT_FLAG_KEEP_TOUCH_MODE = 0x4,

    /* This mask is set if an event was known to come from a trusted part
     * of the system.  That is, the event is known to come from the user,
     * and could not have been spoofed by a third party component. */
    AKEY_EVENT_FLAG_FROM_SYSTEM = 0x8,

    /* This mask is used for compatibility, to identify enter keys that are
     * coming from an IME whose enter key has been auto-labelled "next" or
     * "done".  This allows TextView to dispatch these as normal enter keys
     * for old applications, but still do the appropriate action when
     * receiving them. */
    AKEY_EVENT_FLAG_EDITOR_ACTION = 0x10,

    /* When associated with up key events, this indicates that the key press
     * has been canceled.  Typically this is used with virtual touch screen
     * keys, where the user can slide from the virtual key area on to the
     * display: in that case, the application will receive a canceled up
     * event and should not perform the action normally associated with the
     * key.  Note that for this to work, the application can not perform an
     * action for a key until it receives an up or the long press timeout has
     * expired. */
    AKEY_EVENT_FLAG_CANCELED = 0x20,

    /* This key event was generated by a virtual (on-screen) hard key area.
     * Typically this is an area of the touchscreen, outside of the regular
     * display, dedicated to "hardware" buttons. */
    AKEY_EVENT_FLAG_VIRTUAL_HARD_KEY = 0x40,

    /* This flag is set for the first key repeat that occurs after the
     * long press timeout. */
    AKEY_EVENT_FLAG_LONG_PRESS = 0x80,

    /* Set when a key event has AKEY_EVENT_FLAG_CANCELED set because a long
     * press action was executed while it was down. */
    AKEY_EVENT_FLAG_CANCELED_LONG_PRESS = 0x100,

    /* Set for AKEY_EVENT_ACTION_UP when this event's key code is still being
     * tracked from its initial down.  That is, somebody requested that tracking
     * started on the key down and a long press has not caused
     * the tracking to be canceled. */
    AKEY_EVENT_FLAG_TRACKING = 0x200,

    /* Set when a key event has been synthesized to implement default behavior
     * for an event that the application did not handle.
     * Fallback key events are generated by unhandled trackball motions
     * (to emulate a directional keypad) and by certain unhandled key presses
     * that are declared in the key map (such as special function numeric keypad
     * keys when numlock is off). */
    AKEY_EVENT_FLAG_FALLBACK = 0x400,
};

/*
 * Motion event actions.
 */

/* Bit shift for the action bits holding the pointer index as
 * defined by AMOTION_EVENT_ACTION_POINTER_INDEX_MASK.
 */
#define AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT 8

enum {
    /* Bit mask of the parts of the action code that are the action itself.
     */
    AMOTION_EVENT_ACTION_MASK = 0xff,

    /* Bits in the action code that represent a pointer index, used with
     * AMOTION_EVENT_ACTION_POINTER_DOWN and AMOTION_EVENT_ACTION_POINTER_UP.  Shifting
     * down by AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT provides the actual pointer
     * index where the data for the pointer going up or down can be found.
     */
    AMOTION_EVENT_ACTION_POINTER_INDEX_MASK  = 0xff00,

    /* A pressed gesture has started, the motion contains the initial starting location.
     */
    AMOTION_EVENT_ACTION_DOWN = 0,

    /* A pressed gesture has finished, the motion contains the final release location
     * as well as any intermediate points since the last down or move event.
     */
    AMOTION_EVENT_ACTION_UP = 1,

    /* A change has happened during a press gesture (between AMOTION_EVENT_ACTION_DOWN and
     * AMOTION_EVENT_ACTION_UP).  The motion contains the most recent point, as well as
     * any intermediate points since the last down or move event.
     */
    AMOTION_EVENT_ACTION_MOVE = 2,

    /* The current gesture has been aborted.
     * You will not receive any more points in it.  You should treat this as
     * an up event, but not perform any action that you normally would.
     */
    AMOTION_EVENT_ACTION_CANCEL = 3,

    /* A movement has happened outside of the normal bounds of the UI element.
     * This does not provide a full gesture, but only the initial location of the movement/touch.
     */
    AMOTION_EVENT_ACTION_OUTSIDE = 4,

    /* A non-primary pointer has gone down.
     * The bits in AMOTION_EVENT_ACTION_POINTER_INDEX_MASK indicate which pointer changed.
     */
    AMOTION_EVENT_ACTION_POINTER_DOWN = 5,

    /* A non-primary pointer has gone up.
     * The bits in AMOTION_EVENT_ACTION_POINTER_INDEX_MASK indicate which pointer changed.
     */
    AMOTION_EVENT_ACTION_POINTER_UP = 6,

    /* A change happened but the pointer is not down (unlike AMOTION_EVENT_ACTION_MOVE).
     * The motion contains the most recent point, as well as any intermediate points since
     * the last hover move event.
     */
    AMOTION_EVENT_ACTION_HOVER_MOVE = 7,

    /* The motion event contains relative vertical and/or horizontal scroll offsets.
     * Use getAxisValue to retrieve the information from AMOTION_EVENT_AXIS_VSCROLL
     * and AMOTION_EVENT_AXIS_HSCROLL.
     * The pointer may or may not be down when this event is dispatched.
     * This action is always delivered to the winder under the pointer, which
     * may not be the window currently touched.
     */
    AMOTION_EVENT_ACTION_SCROLL = 8,
};

/*
 * Motion event flags.
 */
enum {
    /* This flag indicates that the window that received this motion event is partly
     * or wholly obscured by another visible window above it.  This flag is set to true
     * even if the event did not directly pass through the obscured area.
     * A security sensitive application can check this flag to identify situations in which
     * a malicious application may have covered up part of its content for the purpose
     * of misleading the user or hijacking touches.  An appropriate response might be
     * to drop the suspect touches or to take additional precautions to confirm the user's
     * actual intent.
     */
    AMOTION_EVENT_FLAG_WINDOW_IS_OBSCURED = 0x1,
};

/*
 * Motion event edge touch flags.
 */
enum {
    /* No edges intersected */
    AMOTION_EVENT_EDGE_FLAG_NONE = 0,

    /* Flag indicating the motion event intersected the top edge of the screen. */
    AMOTION_EVENT_EDGE_FLAG_TOP = 0x01,

    /* Flag indicating the motion event intersected the bottom edge of the screen. */
    AMOTION_EVENT_EDGE_FLAG_BOTTOM = 0x02,

    /* Flag indicating the motion event intersected the left edge of the screen. */
    AMOTION_EVENT_EDGE_FLAG_LEFT = 0x04,

    /* Flag indicating the motion event intersected the right edge of the screen. */
    AMOTION_EVENT_EDGE_FLAG_RIGHT = 0x08
};

/*
 * Constants that identify each individual axis of a motion event.
 * Refer to the documentation on the MotionEvent class for descriptions of each axis.
 */
enum {
    AMOTION_EVENT_AXIS_X = 0,
    AMOTION_EVENT_AXIS_Y = 1,
    AMOTION_EVENT_AXIS_PRESSURE = 2,
    AMOTION_EVENT_AXIS_SIZE = 3,
    AMOTION_EVENT_AXIS_TOUCH_MAJOR = 4,
    AMOTION_EVENT_AXIS_TOUCH_MINOR = 5,
    AMOTION_EVENT_AXIS_TOOL_MAJOR = 6,
    AMOTION_EVENT_AXIS_TOOL_MINOR = 7,
    AMOTION_EVENT_AXIS_ORIENTATION = 8,
    AMOTION_EVENT_AXIS_VSCROLL = 9,
    AMOTION_EVENT_AXIS_HSCROLL = 10,
    AMOTION_EVENT_AXIS_Z = 11,
    AMOTION_EVENT_AXIS_RX = 12,
    AMOTION_EVENT_AXIS_RY = 13,
    AMOTION_EVENT_AXIS_RZ = 14,
    AMOTION_EVENT_AXIS_HAT_X = 15,
    AMOTION_EVENT_AXIS_HAT_Y = 16,
    AMOTION_EVENT_AXIS_LTRIGGER = 17,
    AMOTION_EVENT_AXIS_RTRIGGER = 18,
    AMOTION_EVENT_AXIS_THROTTLE = 19,
    AMOTION_EVENT_AXIS_RUDDER = 20,
    AMOTION_EVENT_AXIS_WHEEL = 21,
    AMOTION_EVENT_AXIS_GAS = 22,
    AMOTION_EVENT_AXIS_BRAKE = 23,
    AMOTION_EVENT_AXIS_GENERIC_1 = 32,
    AMOTION_EVENT_AXIS_GENERIC_2 = 33,
    AMOTION_EVENT_AXIS_GENERIC_3 = 34,
    AMOTION_EVENT_AXIS_GENERIC_4 = 35,
    AMOTION_EVENT_AXIS_GENERIC_5 = 36,
    AMOTION_EVENT_AXIS_GENERIC_6 = 37,
    AMOTION_EVENT_AXIS_GENERIC_7 = 38,
    AMOTION_EVENT_AXIS_GENERIC_8 = 39,
    AMOTION_EVENT_AXIS_GENERIC_9 = 40,
    AMOTION_EVENT_AXIS_GENERIC_10 = 41,
    AMOTION_EVENT_AXIS_GENERIC_11 = 42,
    AMOTION_EVENT_AXIS_GENERIC_12 = 43,
    AMOTION_EVENT_AXIS_GENERIC_13 = 44,
    AMOTION_EVENT_AXIS_GENERIC_14 = 45,
    AMOTION_EVENT_AXIS_GENERIC_15 = 46,
    AMOTION_EVENT_AXIS_GENERIC_16 = 47,

    // NOTE: If you add a new axis here you must also add it to several other files.
    //       Refer to frameworks/base/core/java/android/view/MotionEvent.java for the full list.
};

/*
 * Input sources.
 *
 * Refer to the documentation on android.view.InputDevice for more details about input sources
 * and their correct interpretation.
 */
enum {
    AINPUT_SOURCE_CLASS_MASK = 0x000000ff,

    AINPUT_SOURCE_CLASS_BUTTON = 0x00000001,
    AINPUT_SOURCE_CLASS_POINTER = 0x00000002,
    AINPUT_SOURCE_CLASS_NAVIGATION = 0x00000004,
    AINPUT_SOURCE_CLASS_POSITION = 0x00000008,
    AINPUT_SOURCE_CLASS_JOYSTICK = 0x00000010,
};

enum {
    AINPUT_SOURCE_UNKNOWN = 0x00000000,

    AINPUT_SOURCE_KEYBOARD = 0x00000100 | AINPUT_SOURCE_CLASS_BUTTON,
    AINPUT_SOURCE_DPAD = 0x00000200 | AINPUT_SOURCE_CLASS_BUTTON,
    AINPUT_SOURCE_GAMEPAD = 0x00000400 | AINPUT_SOURCE_CLASS_BUTTON,
    AINPUT_SOURCE_TOUCHSCREEN = 0x00001000 | AINPUT_SOURCE_CLASS_POINTER,
    AINPUT_SOURCE_MOUSE = 0x00002000 | AINPUT_SOURCE_CLASS_POINTER,
    AINPUT_SOURCE_TRACKBALL = 0x00010000 | AINPUT_SOURCE_CLASS_NAVIGATION,
    AINPUT_SOURCE_TOUCHPAD = 0x00100000 | AINPUT_SOURCE_CLASS_POSITION,
    AINPUT_SOURCE_JOYSTICK = 0x01000000 | AINPUT_SOURCE_CLASS_JOYSTICK,

    AINPUT_SOURCE_ANY = 0xffffff00,
};

/*
 * Keyboard types.
 *
 * Refer to the documentation on android.view.InputDevice for more details.
 */
enum {
    AINPUT_KEYBOARD_TYPE_NONE = 0,
    AINPUT_KEYBOARD_TYPE_NON_ALPHABETIC = 1,
    AINPUT_KEYBOARD_TYPE_ALPHABETIC = 2,
};

/*
 * Constants used to retrieve information about the range of motion for a particular
 * coordinate of a motion event.
 *
 * Refer to the documentation on android.view.InputDevice for more details about input sources
 * and their correct interpretation.
 *
 * DEPRECATION NOTICE: These constants are deprecated.  Use AMOTION_EVENT_AXIS_* constants instead.
 */
enum {
    AINPUT_MOTION_RANGE_X = AMOTION_EVENT_AXIS_X,
    AINPUT_MOTION_RANGE_Y = AMOTION_EVENT_AXIS_Y,
    AINPUT_MOTION_RANGE_PRESSURE = AMOTION_EVENT_AXIS_PRESSURE,
    AINPUT_MOTION_RANGE_SIZE = AMOTION_EVENT_AXIS_SIZE,
    AINPUT_MOTION_RANGE_TOUCH_MAJOR = AMOTION_EVENT_AXIS_TOUCH_MAJOR,
    AINPUT_MOTION_RANGE_TOUCH_MINOR = AMOTION_EVENT_AXIS_TOUCH_MINOR,
    AINPUT_MOTION_RANGE_TOOL_MAJOR = AMOTION_EVENT_AXIS_TOOL_MAJOR,
    AINPUT_MOTION_RANGE_TOOL_MINOR = AMOTION_EVENT_AXIS_TOOL_MINOR,
    AINPUT_MOTION_RANGE_ORIENTATION = AMOTION_EVENT_AXIS_ORIENTATION,
} __attribute__ ((deprecated));


/*
 * Input event accessors.
 *
 * Note that most functions can only be used on input events that are of a given type.
 * Calling these functions on input events of other types will yield undefined behavior.
 */

/*** Accessors for all input events. ***/

/* Get the input event type. */
int32_t AInputEvent_getType(const AInputEvent* event);

/* Get the id for the device that an input event came from.
 *
 * Input events can be generated by multiple different input devices.
 * Use the input device id to obtain information about the input
 * device that was responsible for generating a particular event.
 *
 * An input device id of 0 indicates that the event didn't come from a physical device;
 * other numbers are arbitrary and you shouldn't depend on the values.
 * Use the provided input device query API to obtain information about input devices.
 */
int32_t AInputEvent_getDeviceId(const AInputEvent* event);

/* Get the input event source. */
int32_t AInputEvent_getSource(const AInputEvent* event);

/*** Accessors for key events only. ***/

/* Get the key event action. */
int32_t AKeyEvent_getAction(const AInputEvent* key_event);

/* Get the key event flags. */
int32_t AKeyEvent_getFlags(const AInputEvent* key_event);

/* Get the key code of the key event.
 * This is the physical key that was pressed, not the Unicode character. */
int32_t AKeyEvent_getKeyCode(const AInputEvent* key_event);

/* Get the hardware key id of this key event.
 * These values are not reliable and vary from device to device. */
int32_t AKeyEvent_getScanCode(const AInputEvent* key_event);

/* Get the meta key state. */
int32_t AKeyEvent_getMetaState(const AInputEvent* key_event);

/* Get the repeat count of the event.
 * For both key up an key down events, this is the number of times the key has
 * repeated with the first down starting at 0 and counting up from there.  For
 * multiple key events, this is the number of down/up pairs that have occurred. */
int32_t AKeyEvent_getRepeatCount(const AInputEvent* key_event);

/* Get the time of the most recent key down event, in the
 * java.lang.System.nanoTime() time base.  If this is a down event,
 * this will be the same as eventTime.
 * Note that when chording keys, this value is the down time of the most recently
 * pressed key, which may not be the same physical key of this event. */
int64_t AKeyEvent_getDownTime(const AInputEvent* key_event);

/* Get the time this event occurred, in the
 * java.lang.System.nanoTime() time base. */
int64_t AKeyEvent_getEventTime(const AInputEvent* key_event);

/*** Accessors for motion events only. ***/

/* Get the combined motion event action code and pointer index. */
int32_t AMotionEvent_getAction(const AInputEvent* motion_event);

/* Get the motion event flags. */
int32_t AMotionEvent_getFlags(const AInputEvent* motion_event);

/* Get the state of any meta / modifier keys that were in effect when the
 * event was generated. */
int32_t AMotionEvent_getMetaState(const AInputEvent* motion_event);

/* Get a bitfield indicating which edges, if any, were touched by this motion event.
 * For touch events, clients can use this to determine if the user's finger was
 * touching the edge of the display. */
int32_t AMotionEvent_getEdgeFlags(const AInputEvent* motion_event);

/* Get the time when the user originally pressed down to start a stream of
 * position events, in the java.lang.System.nanoTime() time base. */
int64_t AMotionEvent_getDownTime(const AInputEvent* motion_event);

/* Get the time when this specific event was generated,
 * in the java.lang.System.nanoTime() time base. */
int64_t AMotionEvent_getEventTime(const AInputEvent* motion_event);

/* Get the X coordinate offset.
 * For touch events on the screen, this is the delta that was added to the raw
 * screen coordinates to adjust for the absolute position of the containing windows
 * and views. */
float AMotionEvent_getXOffset(const AInputEvent* motion_event);

/* Get the precision of the Y coordinates being reported.
 * For touch events on the screen, this is the delta that was added to the raw
 * screen coordinates to adjust for the absolute position of the containing windows
 * and views. */
float AMotionEvent_getYOffset(const AInputEvent* motion_event);

/* Get the precision of the X coordinates being reported.
 * You can multiply this number with an X coordinate sample to find the
 * actual hardware value of the X coordinate. */
float AMotionEvent_getXPrecision(const AInputEvent* motion_event);

/* Get the precision of the Y coordinates being reported.
 * You can multiply this number with a Y coordinate sample to find the
 * actual hardware value of the Y coordinate. */
float AMotionEvent_getYPrecision(const AInputEvent* motion_event);

/* Get the number of pointers of data contained in this event.
 * Always >= 1. */
size_t AMotionEvent_getPointerCount(const AInputEvent* motion_event);

/* Get the pointer identifier associated with a particular pointer
 * data index is this event.  The identifier tells you the actual pointer
 * number associated with the data, accounting for individual pointers
 * going up and down since the start of the current gesture. */
int32_t AMotionEvent_getPointerId(const AInputEvent* motion_event, size_t pointer_index);

/* Get the original raw X coordinate of this event.
 * For touch events on the screen, this is the original location of the event
 * on the screen, before it had been adjusted for the containing window
 * and views. */
float AMotionEvent_getRawX(const AInputEvent* motion_event, size_t pointer_index);

/* Get the original raw X coordinate of this event.
 * For touch events on the screen, this is the original location of the event
 * on the screen, before it had been adjusted for the containing window
 * and views. */
float AMotionEvent_getRawY(const AInputEvent* motion_event, size_t pointer_index);

/* Get the current X coordinate of this event for the given pointer index.
 * Whole numbers are pixels; the value may have a fraction for input devices
 * that are sub-pixel precise. */
float AMotionEvent_getX(const AInputEvent* motion_event, size_t pointer_index);

/* Get the current Y coordinate of this event for the given pointer index.
 * Whole numbers are pixels; the value may have a fraction for input devices
 * that are sub-pixel precise. */
float AMotionEvent_getY(const AInputEvent* motion_event, size_t pointer_index);

/* Get the current pressure of this event for the given pointer index.
 * The pressure generally ranges from 0 (no pressure at all) to 1 (normal pressure),
 * although values higher than 1 may be generated depending on the calibration of
 * the input device. */
float AMotionEvent_getPressure(const AInputEvent* motion_event, size_t pointer_index);

/* Get the current scaled value of the approximate size for the given pointer index.
 * This represents some approximation of the area of the screen being
 * pressed; the actual value in pixels corresponding to the
 * touch is normalized with the device specific range of values
 * and scaled to a value between 0 and 1.  The value of size can be used to
 * determine fat touch events. */
float AMotionEvent_getSize(const AInputEvent* motion_event, size_t pointer_index);

/* Get the current length of the major axis of an ellipse that describes the touch area
 * at the point of contact for the given pointer index. */
float AMotionEvent_getTouchMajor(const AInputEvent* motion_event, size_t pointer_index);

/* Get the current length of the minor axis of an ellipse that describes the touch area
 * at the point of contact for the given pointer index. */
float AMotionEvent_getTouchMinor(const AInputEvent* motion_event, size_t pointer_index);

/* Get the current length of the major axis of an ellipse that describes the size
 * of the approaching tool for the given pointer index.
 * The tool area represents the estimated size of the finger or pen that is
 * touching the device independent of its actual touch area at the point of contact. */
float AMotionEvent_getToolMajor(const AInputEvent* motion_event, size_t pointer_index);

/* Get the current length of the minor axis of an ellipse that describes the size
 * of the approaching tool for the given pointer index.
 * The tool area represents the estimated size of the finger or pen that is
 * touching the device independent of its actual touch area at the point of contact. */
float AMotionEvent_getToolMinor(const AInputEvent* motion_event, size_t pointer_index);

/* Get the current orientation of the touch area and tool area in radians clockwise from
 * vertical for the given pointer index.
 * An angle of 0 degrees indicates that the major axis of contact is oriented
 * upwards, is perfectly circular or is of unknown orientation.  A positive angle
 * indicates that the major axis of contact is oriented to the right.  A negative angle
 * indicates that the major axis of contact is oriented to the left.
 * The full range is from -PI/2 radians (finger pointing fully left) to PI/2 radians
 * (finger pointing fully right). */
float AMotionEvent_getOrientation(const AInputEvent* motion_event, size_t pointer_index);

/* Get the value of the request axis for the given pointer index. */
float AMotionEvent_getAxisValue(const AInputEvent* motion_event,
        int32_t axis, size_t pointer_index);

/* Get the number of historical points in this event.  These are movements that
 * have occurred between this event and the previous event.  This only applies
 * to AMOTION_EVENT_ACTION_MOVE events -- all other actions will have a size of 0.
 * Historical samples are indexed from oldest to newest. */
size_t AMotionEvent_getHistorySize(const AInputEvent* motion_event);

/* Get the time that a historical movement occurred between this event and
 * the previous event, in the java.lang.System.nanoTime() time base. */
int64_t AMotionEvent_getHistoricalEventTime(AInputEvent* motion_event,
        size_t history_index);

/* Get the historical raw X coordinate of this event for the given pointer index that
 * occurred between this event and the previous motion event.
 * For touch events on the screen, this is the original location of the event
 * on the screen, before it had been adjusted for the containing window
 * and views.
 * Whole numbers are pixels; the value may have a fraction for input devices
 * that are sub-pixel precise. */
float AMotionEvent_getHistoricalRawX(const AInputEvent* motion_event, size_t pointer_index);

/* Get the historical raw Y coordinate of this event for the given pointer index that
 * occurred between this event and the previous motion event.
 * For touch events on the screen, this is the original location of the event
 * on the screen, before it had been adjusted for the containing window
 * and views.
 * Whole numbers are pixels; the value may have a fraction for input devices
 * that are sub-pixel precise. */
float AMotionEvent_getHistoricalRawY(const AInputEvent* motion_event, size_t pointer_index);

/* Get the historical X coordinate of this event for the given pointer index that
 * occurred between this event and the previous motion event.
 * Whole numbers are pixels; the value may have a fraction for input devices
 * that are sub-pixel precise. */
float AMotionEvent_getHistoricalX(AInputEvent* motion_event, size_t pointer_index,
        size_t history_index);

/* Get the historical Y coordinate of this event for the given pointer index that
 * occurred between this event and the previous motion event.
 * Whole numbers are pixels; the value may have a fraction for input devices
 * that are sub-pixel precise. */
float AMotionEvent_getHistoricalY(AInputEvent* motion_event, size_t pointer_index,
        size_t history_index);

/* Get the historical pressure of this event for the given pointer index that
 * occurred between this event and the previous motion event.
 * The pressure generally ranges from 0 (no pressure at all) to 1 (normal pressure),
 * although values higher than 1 may be generated depending on the calibration of
 * the input device. */
float AMotionEvent_getHistoricalPressure(AInputEvent* motion_event, size_t pointer_index,
        size_t history_index);

/* Get the current scaled value of the approximate size for the given pointer index that
 * occurred between this event and the previous motion event.
 * This represents some approximation of the area of the screen being
 * pressed; the actual value in pixels corresponding to the
 * touch is normalized with the device specific range of values
 * and scaled to a value between 0 and 1.  The value of size can be used to
 * determine fat touch events. */
float AMotionEvent_getHistoricalSize(AInputEvent* motion_event, size_t pointer_index,
        size_t history_index);

/* Get the historical length of the major axis of an ellipse that describes the touch area
 * at the point of contact for the given pointer index that
 * occurred between this event and the previous motion event. */
float AMotionEvent_getHistoricalTouchMajor(const AInputEvent* motion_event, size_t pointer_index,
        size_t history_index);

/* Get the historical length of the minor axis of an ellipse that describes the touch area
 * at the point of contact for the given pointer index that
 * occurred between this event and the previous motion event. */
float AMotionEvent_getHistoricalTouchMinor(const AInputEvent* motion_event, size_t pointer_index,
        size_t history_index);

/* Get the historical length of the major axis of an ellipse that describes the size
 * of the approaching tool for the given pointer index that
 * occurred between this event and the previous motion event.
 * The tool area represents the estimated size of the finger or pen that is
 * touching the device independent of its actual touch area at the point of contact. */
float AMotionEvent_getHistoricalToolMajor(const AInputEvent* motion_event, size_t pointer_index,
        size_t history_index);

/* Get the historical length of the minor axis of an ellipse that describes the size
 * of the approaching tool for the given pointer index that
 * occurred between this event and the previous motion event.
 * The tool area represents the estimated size of the finger or pen that is
 * touching the device independent of its actual touch area at the point of contact. */
float AMotionEvent_getHistoricalToolMinor(const AInputEvent* motion_event, size_t pointer_index,
        size_t history_index);

/* Get the historical orientation of the touch area and tool area in radians clockwise from
 * vertical for the given pointer index that
 * occurred between this event and the previous motion event.
 * An angle of 0 degrees indicates that the major axis of contact is oriented
 * upwards, is perfectly circular or is of unknown orientation.  A positive angle
 * indicates that the major axis of contact is oriented to the right.  A negative angle
 * indicates that the major axis of contact is oriented to the left.
 * The full range is from -PI/2 radians (finger pointing fully left) to PI/2 radians
 * (finger pointing fully right). */
float AMotionEvent_getHistoricalOrientation(const AInputEvent* motion_event, size_t pointer_index,
        size_t history_index);

/* Get the historical value of the request axis for the given pointer index
 * that occurred between this event and the previous motion event. */
float AMotionEvent_getHistoricalAxisValue(const AInputEvent* motion_event,
        int32_t axis, size_t pointer_index, size_t history_index);


/*
 * Input queue
 *
 * An input queue is the facility through which you retrieve input
 * events.
 */
struct AInputQueue;
typedef struct AInputQueue AInputQueue;

/*
 * Add this input queue to a looper for processing.  See
 * ALooper_addFd() for information on the ident, callback, and data params.
 */
void AInputQueue_attachLooper(AInputQueue* queue, ALooper* looper,
        int ident, ALooper_callbackFunc callback, void* data);

/*
 * Remove the input queue from the looper it is currently attached to.
 */
void AInputQueue_detachLooper(AInputQueue* queue);

/*
 * Returns true if there are one or more events available in the
 * input queue.  Returns 1 if the queue has events; 0 if
 * it does not have events; and a negative value if there is an error.
 */
int32_t AInputQueue_hasEvents(AInputQueue* queue);

/*
 * Returns the next available event from the queue.  Returns a negative
 * value if no events are available or an error has occurred.
 */
int32_t AInputQueue_getEvent(AInputQueue* queue, AInputEvent** outEvent);

/*
 * Sends the key for standard pre-dispatching -- that is, possibly deliver
 * it to the current IME to be consumed before the app.  Returns 0 if it
 * was not pre-dispatched, meaning you can process it right now.  If non-zero
 * is returned, you must abandon the current event processing and allow the
 * event to appear again in the event queue (if it does not get consumed during
 * pre-dispatching).
 */
int32_t AInputQueue_preDispatchEvent(AInputQueue* queue, AInputEvent* event);

/*
 * Report that dispatching has finished with the given event.
 * This must be called after receiving an event with AInputQueue_get_event().
 */
void AInputQueue_finishEvent(AInputQueue* queue, AInputEvent* event, int handled);

#ifdef __cplusplus
}
#endif

#endif // _ANDROID_INPUT_H
