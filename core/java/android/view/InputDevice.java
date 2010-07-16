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

/**
 * Describes the capabilities of a particular input device.
 * <p>
 * Each input device may support multiple classes of input.  For example, a multifunction
 * keyboard may compose the capabilities of a standard keyboard together with a track pad mouse
 * or other pointing device.
 * </p><p>
 * Some input devices present multiple distinguishable sources of input.  For example, a
 * game pad may have two analog joysticks, a directional pad and a full complement of buttons.
 * Applications can query the framework about the characteristics of each distinct source.
 * </p><p>
 * As a further wrinkle, different kinds of input sources uses different coordinate systems
 * to describe motion events.  Refer to the comments on the input source constants for
 * the appropriate interpretation.
 */
public final class InputDevice {
    private int mId;
    private String mName;
    private int mSources;
    
    /**
     * A mask for input source classes.
     * 
     * Each distinct input source constant has one or more input source class bits set to
     * specify the desired interpretation for its input events.
     */
    public static final int SOURCE_CLASS_MASK = 0x000000ff;
    
    /**
     * The input source has buttons or keys.
     * Examples: {@link #SOURCE_KEYBOARD}, {@link #SOURCE_GAMEPAD}, {@link #SOURCE_DPAD}.
     * 
     * A {@link KeyEvent} should be interpreted as a button or key press.
     * 
     * Use {@link #hasKey} to query whether the device supports a particular button or key.
     */
    public static final int SOURCE_CLASS_BUTTON = 0x00000001;
    
    /**
     * The input source is a pointing device associated with a display.
     * Examples: {@link #SOURCE_TOUCHSCREEN}, {@link #SOURCE_MOUSE}.
     * 
     * A {@link MotionEvent} should be interpreted as absolute coordinates in
     * display units according to the {@link View} hierarchy.  Pointer down/up indicated when
     * the finger touches the display or when the selection button is pressed/released.
     * 
     * Use {@link #getMotionRange} to query the range of the pointing device.  Some devices permit
     * touches outside the display area so the effective range may be somewhat smaller or larger
     * than the actual display size.
     */
    public static final int SOURCE_CLASS_POINTER = 0x00000002;
    
    /**
     * The input source is a trackball navigation device.
     * Examples: {@link #SOURCE_TRACKBALL}.
     * 
     * A {@link MotionEvent} should be interpreted as relative movements in device-specific
     * units used for navigation purposes.  Pointer down/up indicates when the selection button
     * is pressed/released.
     * 
     * Use {@link #getMotionRange} to query the range of motion.
     */
    public static final int SOURCE_CLASS_TRACKBALL = 0x00000004;
    
    /**
     * The input source is an absolute positioning device not associated with a display
     * (unlike {@link #SOURCE_CLASS_POINTER}).
     * 
     * A {@link MotionEvent} should be interpreted as absolute coordinates in
     * device-specific surface units.
     * 
     * Use {@link #getMotionRange} to query the range of positions.
     */
    public static final int SOURCE_CLASS_POSITION = 0x00000008;
    
    /**
     * The input source is a joystick.
     * 
     * A {@link KeyEvent} should be interpreted as a joystick button press.
     * 
     * A {@link MotionEvent} should be interpreted in absolute coordinates as a joystick
     * position in normalized device-specific units nominally between -1.0 and 1.0.
     * 
     * Use {@link #getMotionRange} to query the range and precision of motion.
     */
    public static final int SOURCE_CLASS_JOYSTICK = 0x00000010;
    
    /**
     * The input source is unknown.
     */
    public static final int SOURCE_UNKNOWN = 0x00000000;
    
    /**
     * The input source is a keyboard.
     * 
     * @see #SOURCE_CLASS_BUTTON
     */
    public static final int SOURCE_KEYBOARD = 0x00000100 | SOURCE_CLASS_BUTTON;
    
    /**
     * The input source is a DPad.
     * 
     * @see #SOURCE_CLASS_BUTTON
     */
    public static final int SOURCE_DPAD = 0x00000200 | SOURCE_CLASS_BUTTON;
    
    /**
     * The input source is a gamepad.
     * 
     * @see #SOURCE_CLASS_BUTTON
     */
    public static final int SOURCE_GAMEPAD = 0x00000400 | SOURCE_CLASS_BUTTON;
    
    /**
     * The input source is a touch screen pointing device.
     * 
     * @see #SOURCE_CLASS_POINTER
     */
    public static final int SOURCE_TOUCHSCREEN = 0x00001000 | SOURCE_CLASS_POINTER;
    
    /**
     * The input source is a mouse pointing device.
     * This code is also used for other mouse-like pointing devices such as trackpads
     * and trackpoints.
     * 
     * @see #SOURCE_CLASS_POINTER
     */
    public static final int SOURCE_MOUSE = 0x00002000 | SOURCE_CLASS_POINTER;
    
    /**
     * The input source is a trackball.
     * 
     * @see #SOURCE_CLASS_TRACKBALL
     */
    public static final int SOURCE_TRACKBALL = 0x00010000 | SOURCE_CLASS_TRACKBALL;
    
    /**
     * The input source is a touch pad or digitizer tablet that is not
     * associated with a display (unlike {@link #SOURCE_TOUCHSCREEN}).
     * 
     * @see #SOURCE_CLASS_POSITION
     */
    public static final int SOURCE_TOUCHPAD = 0x00100000 | SOURCE_CLASS_POSITION;

    /**
     * The input source is a joystick mounted on the left or is a standalone joystick.
     * 
     * @see #SOURCE_CLASS_JOYSTICK
     */
    public static final int SOURCE_JOYSTICK_LEFT = 0x01000000 | SOURCE_CLASS_JOYSTICK;
    
    /**
     * The input source is a joystick mounted on the right.
     * 
     * @see #SOURCE_CLASS_JOYSTICK
     */
    public static final int SOURCE_JOYSTICK_RIGHT = 0x02000000 | SOURCE_CLASS_JOYSTICK;

    /**
     * Constant for retrieving the range of values for {@link MotionEvent.PointerCoords#x}.
     * 
     * @see #getMotionRange
     */
    public static final int MOTION_RANGE_X = 0;
    
    /**
     * Constant for retrieving the range of values for {@link MotionEvent.PointerCoords#y}.
     * 
     * @see #getMotionRange
     */
    public static final int MOTION_RANGE_Y = 1;
    
    /**
     * Constant for retrieving the range of values for {@link MotionEvent.PointerCoords#pressure}.
     * 
     * @see #getMotionRange
     */
    public static final int MOTION_RANGE_PRESSURE = 2;
    
    /**
     * Constant for retrieving the range of values for {@link MotionEvent.PointerCoords#size}.
     * 
     * @see #getMotionRange
     */
    public static final int MOTION_RANGE_SIZE = 3;
    
    /**
     * Constant for retrieving the range of values for {@link MotionEvent.PointerCoords#touchMajor}.
     * 
     * @see #getMotionRange
     */
    public static final int MOTION_RANGE_TOUCH_MAJOR = 4;
    
    /**
     * Constant for retrieving the range of values for {@link MotionEvent.PointerCoords#touchMinor}.
     * 
     * @see #getMotionRange
     */
    public static final int MOTION_RANGE_TOUCH_MINOR = 5;
    
    /**
     * Constant for retrieving the range of values for {@link MotionEvent.PointerCoords#toolMajor}.
     * 
     * @see #getMotionRange
     */
    public static final int MOTION_RANGE_TOOL_MAJOR = 6;
    
    /**
     * Constant for retrieving the range of values for {@link MotionEvent.PointerCoords#toolMinor}.
     * 
     * @see #getMotionRange
     */
    public static final int MOTION_RANGE_TOOL_MINOR = 7;
    
    /**
     * Constant for retrieving the range of values for
     * {@link MotionEvent.PointerCoords#orientation}.
     * 
     * @see #getMotionRange
     */
    public static final int MOTION_RANGE_ORIENTATION = 8;

    /**
     * Gets information about the input device with the specified id.
     * @param id The device id.
     * @return The input device or null if not found.
     */
    public static InputDevice getDevice(int id) {
        // TODO
        return null;
    }
    
    /**
     * Gets the name of this input device.
     * @return The input device name.
     */
    public String getName() {
        return mName;
    }
    
    /**
     * Gets the input sources supported by this input device as a combined bitfield.
     * @return The supported input sources.
     */
    public int getSources() {
        return mSources;
    }
    
    /**
     * Gets the key character map associated with this input device.
     * @return The key character map.
     */
    public KeyCharacterMap getKeyCharacterMap() {
        return KeyCharacterMap.load(mId);
    }
    
    /**
     * Gets information about the range of values for a particular {@link MotionEvent}
     * coordinate.
     * @param range The motion range constant.
     * @return The range of values, or null if the requested coordinate is not
     * supported by the device.
     */
    public MotionRange getMotionRange(int range) {
        // TODO
        return null;
    }
    
    /**
     * Returns true if the device supports a particular button or key.
     * @param keyCode The key code.
     * @return True if the device supports the key.
     */
    public boolean hasKey(int keyCode) {
        // TODO
        return false;
    }
    
    /**
     * Provides information about the range of values for a particular {@link MotionEvent}
     * coordinate.
     */
    public static final class MotionRange {
        /**
         * Gets the minimum value for the coordinate.
         * @return The minimum value.
         */
        public float getMin() {
            // TODO
            return 0;
        }
        
        /**
         * Gets the maximum value for the coordinate.
         * @return The minimum value.
         */
        public float getMax() {
            // TODO
            return 0;
        }
        
        /**
         * Gets the range of the coordinate (difference between maximum and minimum).
         * @return The range of values.
         */
        public float getRange() {
            // TODO
            return 0;
        }
        
        /**
         * Gets the extent of the center flat position with respect to this coordinate.
         * For example, a flat value of 8 means that the center position is between -8 and +8.
         * This value is mainly useful for calibrating joysticks.
         * @return The extent of the center flat position.
         */
        public float getFlat() {
            // TODO
            return 0;
        }
        
        /**
         * Gets the error tolerance for input device measurements with respect to this coordinate.
         * For example, a value of 2 indicates that the measured value may be up to +/- 2 units
         * away from the actual value due to noise and device sensitivity limitations.
         * @return The error tolerance.
         */
        public float getFuzz() {
            // TODO
            return 0;
        }
    }
}
