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
import android.os.RemoteException;
import android.os.ServiceManager;

/**
 * Describes the capabilities of a particular input device.
 * <p>
 * Each input device may support multiple classes of input.  For example, a multifunction
 * keyboard may compose the capabilities of a standard keyboard together with a track pad mouse
 * or other pointing device.
 * </p><p>
 * Some input devices present multiple distinguishable sources of input.
 * Applications can query the framework about the characteristics of each distinct source.
 * </p><p>
 * As a further wrinkle, different kinds of input sources uses different coordinate systems
 * to describe motion events.  Refer to the comments on the input source constants for
 * the appropriate interpretation.
 * </p>
 */
public final class InputDevice implements Parcelable {
    private int mId;
    private String mName;
    private int mSources;
    private int mKeyboardType;
    
    private MotionRange[] mMotionRanges;
    
    /**
     * A mask for input source classes.
     * 
     * Each distinct input source constant has one or more input source class bits set to
     * specify the desired interpretation for its input events.
     */
    public static final int SOURCE_CLASS_MASK = 0x000000ff;
    
    /**
     * The input source has buttons or keys.
     * Examples: {@link #SOURCE_KEYBOARD}, {@link #SOURCE_DPAD}.
     * 
     * A {@link KeyEvent} should be interpreted as a button or key press.
     * 
     * Use {@link #getKeyCharacterMap} to query the device's button and key mappings.
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
     * A special input source constant that is used when filtering input devices
     * to match devices that provide any type of input source.
     */
    public static final int SOURCE_ANY = 0xffffff00;

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
    
    private static final int MOTION_RANGE_LAST = MOTION_RANGE_ORIENTATION;
    
    /**
     * There is no keyboard.
     */
    public static final int KEYBOARD_TYPE_NONE = 0;
    
    /**
     * The keyboard is not fully alphabetic.  It may be a numeric keypad or an assortment
     * of buttons that are not mapped as alphabetic keys suitable for text input.
     */
    public static final int KEYBOARD_TYPE_NON_ALPHABETIC = 1;
    
    /**
     * The keyboard supports a complement of alphabetic keys.
     */
    public static final int KEYBOARD_TYPE_ALPHABETIC = 2;
    
    // Called by native code.
    private InputDevice() {
        mMotionRanges = new MotionRange[MOTION_RANGE_LAST + 1];
    }

    /**
     * Gets information about the input device with the specified id.
     * @param id The device id.
     * @return The input device or null if not found.
     */
    public static InputDevice getDevice(int id) {
        IWindowManager wm = IWindowManager.Stub.asInterface(ServiceManager.getService("window"));
        try {
            return wm.getInputDevice(id);
        } catch (RemoteException ex) {
            throw new RuntimeException(
                    "Could not get input device information from Window Manager.", ex);
        }
    }
    
    /**
     * Gets the ids of all input devices in the system.
     * @return The input device ids.
     */
    public static int[] getDeviceIds() {
        IWindowManager wm = IWindowManager.Stub.asInterface(ServiceManager.getService("window"));
        try {
            return wm.getInputDeviceIds();
        } catch (RemoteException ex) {
            throw new RuntimeException(
                    "Could not get input device ids from Window Manager.", ex);
        }
    }
    
    /**
     * Gets the input device id.
     * @return The input device id.
     */
    public int getId() {
        return mId;
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
     * Gets the keyboard type.
     * @return The keyboard type.
     */
    public int getKeyboardType() {
        return mKeyboardType;
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
     * @param rangeType The motion range constant.
     * @return The range of values, or null if the requested coordinate is not
     * supported by the device.
     */
    public MotionRange getMotionRange(int rangeType) {
        if (rangeType < 0 || rangeType > MOTION_RANGE_LAST) {
            throw new IllegalArgumentException("Requested range is out of bounds.");
        }
        
        return mMotionRanges[rangeType];
    }
    
    private void addMotionRange(int rangeType, float min, float max, float flat, float fuzz) {
        if (rangeType >= 0 && rangeType <= MOTION_RANGE_LAST) {
            MotionRange range = new MotionRange(min, max, flat, fuzz);
            mMotionRanges[rangeType] = range;
        }
    }
    
    /**
     * Provides information about the range of values for a particular {@link MotionEvent}
     * coordinate.
     */
    public static final class MotionRange {
        private float mMin;
        private float mMax;
        private float mFlat;
        private float mFuzz;
        
        private MotionRange(float min, float max, float flat, float fuzz) {
            mMin = min;
            mMax = max;
            mFlat = flat;
            mFuzz = fuzz;
        }
        
        /**
         * Gets the minimum value for the coordinate.
         * @return The minimum value.
         */
        public float getMin() {
            return mMin;
        }
        
        /**
         * Gets the maximum value for the coordinate.
         * @return The minimum value.
         */
        public float getMax() {
            return mMax;
        }
        
        /**
         * Gets the range of the coordinate (difference between maximum and minimum).
         * @return The range of values.
         */
        public float getRange() {
            return mMax - mMin;
        }
        
        /**
         * Gets the extent of the center flat position with respect to this coordinate.
         * For example, a flat value of 8 means that the center position is between -8 and +8.
         * This value is mainly useful for calibrating self-centering devices.
         * @return The extent of the center flat position.
         */
        public float getFlat() {
            return mFlat;
        }
        
        /**
         * Gets the error tolerance for input device measurements with respect to this coordinate.
         * For example, a value of 2 indicates that the measured value may be up to +/- 2 units
         * away from the actual value due to noise and device sensitivity limitations.
         * @return The error tolerance.
         */
        public float getFuzz() {
            return mFuzz;
        }
    }
    
    public static final Parcelable.Creator<InputDevice> CREATOR
            = new Parcelable.Creator<InputDevice>() {
        public InputDevice createFromParcel(Parcel in) {
            InputDevice result = new InputDevice();
            result.readFromParcel(in);
            return result;
        }
        
        public InputDevice[] newArray(int size) {
            return new InputDevice[size];
        }
    };
    
    private void readFromParcel(Parcel in) {
        mId = in.readInt();
        mName = in.readString();
        mSources = in.readInt();
        mKeyboardType = in.readInt();
        
        for (;;) {
            int rangeType = in.readInt();
            if (rangeType < 0) {
                break;
            }
            
            addMotionRange(rangeType,
                    in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
        }
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mId);
        out.writeString(mName);
        out.writeInt(mSources);
        out.writeInt(mKeyboardType);
        
        for (int i = 0; i <= MOTION_RANGE_LAST; i++) {
            MotionRange range = mMotionRanges[i];
            if (range != null) {
                out.writeInt(i);
                out.writeFloat(range.mMin);
                out.writeFloat(range.mMax);
                out.writeFloat(range.mFlat);
                out.writeFloat(range.mFuzz);
            }
        }
        out.writeInt(-1);
    }
    
    @Override
    public int describeContents() {
        return 0;
    }
    
    @Override
    public String toString() {
        StringBuilder description = new StringBuilder();
        description.append("Input Device ").append(mId).append(": ").append(mName).append("\n");
        
        description.append("  Keyboard Type: ");
        switch (mKeyboardType) {
            case KEYBOARD_TYPE_NONE:
                description.append("none");
                break;
            case KEYBOARD_TYPE_NON_ALPHABETIC:
                description.append("non-alphabetic");
                break;
            case KEYBOARD_TYPE_ALPHABETIC:
                description.append("alphabetic");
                break;
        }
        description.append("\n");
        
        description.append("  Sources:");
        appendSourceDescriptionIfApplicable(description, SOURCE_KEYBOARD, "keyboard");
        appendSourceDescriptionIfApplicable(description, SOURCE_DPAD, "dpad");
        appendSourceDescriptionIfApplicable(description, SOURCE_TOUCHSCREEN, "touchscreen");
        appendSourceDescriptionIfApplicable(description, SOURCE_MOUSE, "mouse");
        appendSourceDescriptionIfApplicable(description, SOURCE_TRACKBALL, "trackball");
        appendSourceDescriptionIfApplicable(description, SOURCE_TOUCHPAD, "touchpad");
        description.append("\n");
        
        appendRangeDescriptionIfApplicable(description, MOTION_RANGE_X, "x");
        appendRangeDescriptionIfApplicable(description, MOTION_RANGE_Y, "y");
        appendRangeDescriptionIfApplicable(description, MOTION_RANGE_PRESSURE, "pressure");
        appendRangeDescriptionIfApplicable(description, MOTION_RANGE_SIZE, "size");
        appendRangeDescriptionIfApplicable(description, MOTION_RANGE_TOUCH_MAJOR, "touchMajor");
        appendRangeDescriptionIfApplicable(description, MOTION_RANGE_TOUCH_MINOR, "touchMinor");
        appendRangeDescriptionIfApplicable(description, MOTION_RANGE_TOOL_MAJOR, "toolMajor");
        appendRangeDescriptionIfApplicable(description, MOTION_RANGE_TOOL_MINOR, "toolMinor");
        appendRangeDescriptionIfApplicable(description, MOTION_RANGE_ORIENTATION, "orientation");
        
        return description.toString();
    }
    
    private void appendSourceDescriptionIfApplicable(StringBuilder description, int source,
            String sourceName) {
        if ((mSources & source) == source) {
            description.append(" ");
            description.append(sourceName);
        }
    }
    
    private void appendRangeDescriptionIfApplicable(StringBuilder description,
            int rangeType, String rangeName) {
        MotionRange range = mMotionRanges[rangeType];
        if (range != null) {
            description.append("  Range[").append(rangeName);
            description.append("]: min=").append(range.mMin);
            description.append(" max=").append(range.mMax);
            description.append(" flat=").append(range.mFlat);
            description.append(" fuzz=").append(range.mFuzz);
            description.append("\n");
        }
    }
}
