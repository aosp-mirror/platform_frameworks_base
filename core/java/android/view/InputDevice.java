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

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Vibrator;
import android.os.NullVibrator;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes the capabilities of a particular input device.
 * <p>
 * Each input device may support multiple classes of input.  For example, a multi-function
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
    private final int mId;
    private final int mGeneration;
    private final int mControllerNumber;
    private final String mName;
    private final int mVendorId;
    private final int mProductId;
    private final String mDescriptor;
    private final boolean mIsExternal;
    private final int mSources;
    private final int mKeyboardType;
    private final KeyCharacterMap mKeyCharacterMap;
    private final boolean mHasVibrator;
    private final boolean mHasButtonUnderPad;
    private final ArrayList<MotionRange> mMotionRanges = new ArrayList<MotionRange>();

    private Vibrator mVibrator; // guarded by mMotionRanges during initialization

    /**
     * A mask for input source classes.
     * 
     * Each distinct input source constant has one or more input source class bits set to
     * specify the desired interpretation for its input events.
     */
    public static final int SOURCE_CLASS_MASK = 0x000000ff;

    /**
     * The input source has no class.
     *
     * It is up to the application to determine how to handle the device based on the device type.
     */
    public static final int SOURCE_CLASS_NONE = 0x00000000;

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
     * The input source is a joystick.
     *
     * A {@link MotionEvent} should be interpreted as absolute joystick movements.
     *
     * Use {@link #getMotionRange} to query the range of positions.
     */
    public static final int SOURCE_CLASS_JOYSTICK = 0x00000010;

    /**
     * The input source is unknown.
     */
    public static final int SOURCE_UNKNOWN = 0x00000000;
    
    /**
     * The input source is a keyboard.
     *
     * This source indicates pretty much anything that has buttons.  Use
     * {@link #getKeyboardType()} to determine whether the keyboard has alphabetic keys
     * and can be used to enter text.
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
     * The input source is a game pad.
     * (It may also be a {@link #SOURCE_JOYSTICK}).
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
     * The input source is a stylus pointing device.
     * <p>
     * Note that this bit merely indicates that an input device is capable of obtaining
     * input from a stylus.  To determine whether a given touch event was produced
     * by a stylus, examine the tool type returned by {@link MotionEvent#getToolType(int)}
     * for each individual pointer.
     * </p><p>
     * A single touch event may multiple pointers with different tool types,
     * such as an event that has one pointer with tool type
     * {@link MotionEvent#TOOL_TYPE_FINGER} and another pointer with tool type
     * {@link MotionEvent#TOOL_TYPE_STYLUS}.  So it is important to examine
     * the tool type of each pointer, regardless of the source reported
     * by {@link MotionEvent#getSource()}.
     * </p>
     *
     * @see #SOURCE_CLASS_POINTER
     */
    public static final int SOURCE_STYLUS = 0x00004000 | SOURCE_CLASS_POINTER;

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
     * The input source is a touch device whose motions should be interpreted as navigation events.
     *
     * For example, an upward swipe should be as an upward focus traversal in the same manner as
     * pressing up on a D-Pad would be. Swipes to the left, right and down should be treated in a
     * similar manner.
     *
     * @see #SOURCE_CLASS_NONE
     */
    public static final int SOURCE_TOUCH_NAVIGATION = 0x00200000 | SOURCE_CLASS_NONE;

    /**
     * The input source is a joystick.
     * (It may also be a {@link #SOURCE_GAMEPAD}).
     *
     * @see #SOURCE_CLASS_JOYSTICK
     */
    public static final int SOURCE_JOYSTICK = 0x01000000 | SOURCE_CLASS_JOYSTICK;

    /**
     * A special input source constant that is used when filtering input devices
     * to match devices that provide any type of input source.
     */
    public static final int SOURCE_ANY = 0xffffff00;

    /**
     * Constant for retrieving the range of values for {@link MotionEvent#AXIS_X}.
     * 
     * @see #getMotionRange
     * @deprecated Use {@link MotionEvent#AXIS_X} instead.
     */
    @Deprecated
    public static final int MOTION_RANGE_X = MotionEvent.AXIS_X;

    /**
     * Constant for retrieving the range of values for {@link MotionEvent#AXIS_Y}.
     * 
     * @see #getMotionRange
     * @deprecated Use {@link MotionEvent#AXIS_Y} instead.
     */
    @Deprecated
    public static final int MOTION_RANGE_Y = MotionEvent.AXIS_Y;

    /**
     * Constant for retrieving the range of values for {@link MotionEvent#AXIS_PRESSURE}.
     * 
     * @see #getMotionRange
     * @deprecated Use {@link MotionEvent#AXIS_PRESSURE} instead.
     */
    @Deprecated
    public static final int MOTION_RANGE_PRESSURE = MotionEvent.AXIS_PRESSURE;

    /**
     * Constant for retrieving the range of values for {@link MotionEvent#AXIS_SIZE}.
     * 
     * @see #getMotionRange
     * @deprecated Use {@link MotionEvent#AXIS_SIZE} instead.
     */
    @Deprecated
    public static final int MOTION_RANGE_SIZE = MotionEvent.AXIS_SIZE;

    /**
     * Constant for retrieving the range of values for {@link MotionEvent#AXIS_TOUCH_MAJOR}.
     * 
     * @see #getMotionRange
     * @deprecated Use {@link MotionEvent#AXIS_TOUCH_MAJOR} instead.
     */
    @Deprecated
    public static final int MOTION_RANGE_TOUCH_MAJOR = MotionEvent.AXIS_TOUCH_MAJOR;

    /**
     * Constant for retrieving the range of values for {@link MotionEvent#AXIS_TOUCH_MINOR}.
     * 
     * @see #getMotionRange
     * @deprecated Use {@link MotionEvent#AXIS_TOUCH_MINOR} instead.
     */
    @Deprecated
    public static final int MOTION_RANGE_TOUCH_MINOR = MotionEvent.AXIS_TOUCH_MINOR;

    /**
     * Constant for retrieving the range of values for {@link MotionEvent#AXIS_TOOL_MAJOR}.
     * 
     * @see #getMotionRange
     * @deprecated Use {@link MotionEvent#AXIS_TOOL_MAJOR} instead.
     */
    @Deprecated
    public static final int MOTION_RANGE_TOOL_MAJOR = MotionEvent.AXIS_TOOL_MAJOR;

    /**
     * Constant for retrieving the range of values for {@link MotionEvent#AXIS_TOOL_MINOR}.
     * 
     * @see #getMotionRange
     * @deprecated Use {@link MotionEvent#AXIS_TOOL_MINOR} instead.
     */
    @Deprecated
    public static final int MOTION_RANGE_TOOL_MINOR = MotionEvent.AXIS_TOOL_MINOR;

    /**
     * Constant for retrieving the range of values for {@link MotionEvent#AXIS_ORIENTATION}.
     * 
     * @see #getMotionRange
     * @deprecated Use {@link MotionEvent#AXIS_ORIENTATION} instead.
     */
    @Deprecated
    public static final int MOTION_RANGE_ORIENTATION = MotionEvent.AXIS_ORIENTATION;
    
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

    public static final Parcelable.Creator<InputDevice> CREATOR =
            new Parcelable.Creator<InputDevice>() {
        public InputDevice createFromParcel(Parcel in) {
            return new InputDevice(in);
        }
        public InputDevice[] newArray(int size) {
            return new InputDevice[size];
        }
    };

    // Called by native code.
    private InputDevice(int id, int generation, int controllerNumber, String name, int vendorId,
            int productId, String descriptor, boolean isExternal, int sources, int keyboardType,
            KeyCharacterMap keyCharacterMap, boolean hasVibrator, boolean hasButtonUnderPad) {
        mId = id;
        mGeneration = generation;
        mControllerNumber = controllerNumber;
        mName = name;
        mVendorId = vendorId;
        mProductId = productId;
        mDescriptor = descriptor;
        mIsExternal = isExternal;
        mSources = sources;
        mKeyboardType = keyboardType;
        mKeyCharacterMap = keyCharacterMap;
        mHasVibrator = hasVibrator;
        mHasButtonUnderPad = hasButtonUnderPad;
    }

    private InputDevice(Parcel in) {
        mId = in.readInt();
        mGeneration = in.readInt();
        mControllerNumber = in.readInt();
        mName = in.readString();
        mVendorId = in.readInt();
        mProductId = in.readInt();
        mDescriptor = in.readString();
        mIsExternal = in.readInt() != 0;
        mSources = in.readInt();
        mKeyboardType = in.readInt();
        mKeyCharacterMap = KeyCharacterMap.CREATOR.createFromParcel(in);
        mHasVibrator = in.readInt() != 0;
        mHasButtonUnderPad = in.readInt() != 0;

        for (;;) {
            int axis = in.readInt();
            if (axis < 0) {
                break;
            }
            addMotionRange(axis, in.readInt(), in.readFloat(), in.readFloat(), in.readFloat(),
                    in.readFloat(), in.readFloat());
        }
    }

    /**
     * Gets information about the input device with the specified id.
     * @param id The device id.
     * @return The input device or null if not found.
     */
    public static InputDevice getDevice(int id) {
        return InputManager.getInstance().getInputDevice(id);
    }
    
    /**
     * Gets the ids of all input devices in the system.
     * @return The input device ids.
     */
    public static int[] getDeviceIds() {
        return InputManager.getInstance().getInputDeviceIds();
    }

    /**
     * Gets the input device id.
     * <p>
     * Each input device receives a unique id when it is first configured
     * by the system.  The input device id may change when the system is restarted or if the
     * input device is disconnected, reconnected or reconfigured at any time.
     * If you require a stable identifier for a device that persists across
     * boots and reconfigurations, use {@link #getDescriptor()}.
     * </p>
     *
     * @return The input device id.
     */
    public int getId() {
        return mId;
    }

    /**
     * The controller number for a given input device.
     * <p>
     * Each gamepad or joystick is given a unique, positive controller number when initially
     * configured by the system. This number may change due to events such as device disconnects /
     * reconnects or user initiated reassignment. Any change in number will trigger an event that
     * can be observed by registering an {@link InputManager.InputDeviceListener}.
     * </p>
     * <p>
     * All input devices which are not gamepads or joysticks will be assigned a controller number
     * of 0.
     * </p>
     *
     * @return The controller number of the device.
     */
    public int getControllerNumber() {
        return mControllerNumber;
    }

    /**
     * Gets a generation number for this input device.
     * The generation number is incremented whenever the device is reconfigured and its
     * properties may have changed.
     *
     * @return The generation number.
     *
     * @hide
     */
    public int getGeneration() {
        return mGeneration;
    }

    /**
     * Gets the vendor id for the given device, if available.
     * <p>
     * A vendor id uniquely identifies the company who manufactured the device. A value of 0 will
     * be assigned where a vendor id is not available.
     * </p>
     *
     * @return The vendor id of a given device
     */
    public int getVendorId() {
        return mVendorId;
    }

    /**
     * Gets the product id for the given device, if available.
     * <p>
     * A product id uniquely identifies which product within the address space of a given vendor,
     * identified by the device's vendor id. A value of 0 will be assigned where a product id is
     * not available.
     * </p>
     *
     * @return The product id of a given device
     */
    public int getProductId() {
        return mProductId;
    }

    /**
     * Gets the input device descriptor, which is a stable identifier for an input device.
     * <p>
     * An input device descriptor uniquely identifies an input device.  Its value
     * is intended to be persistent across system restarts, and should not change even
     * if the input device is disconnected, reconnected or reconfigured at any time.
     * </p><p>
     * It is possible for there to be multiple {@link InputDevice} instances that have the
     * same input device descriptor.  This might happen in situations where a single
     * human input device registers multiple {@link InputDevice} instances (HID collections)
     * that describe separate features of the device, such as a keyboard that also
     * has a trackpad.  Alternately, it may be that the input devices are simply
     * indistinguishable, such as two keyboards made by the same manufacturer.
     * </p><p>
     * The input device descriptor returned by {@link #getDescriptor} should only be
     * used when an application needs to remember settings associated with a particular
     * input device.  For all other purposes when referring to a logical
     * {@link InputDevice} instance at runtime use the id returned by {@link #getId()}.
     * </p>
     *
     * @return The input device descriptor.
     */
    public String getDescriptor() {
        return mDescriptor;
    }

    /**
     * Returns true if the device is a virtual input device rather than a real one,
     * such as the virtual keyboard (see {@link KeyCharacterMap#VIRTUAL_KEYBOARD}).
     * <p>
     * Virtual input devices are provided to implement system-level functionality
     * and should not be seen or configured by users.
     * </p>
     *
     * @return True if the device is virtual.
     *
     * @see KeyCharacterMap#VIRTUAL_KEYBOARD
     */
    public boolean isVirtual() {
        return mId < 0;
    }

    /**
     * Returns true if the device is external (connected to USB or Bluetooth or some other
     * peripheral bus), otherwise it is built-in.
     *
     * @return True if the device is external.
     *
     * @hide
     */
    public boolean isExternal() {
        return mIsExternal;
    }

    /**
     * Returns true if the device is a full keyboard.
     *
     * @return True if the device is a full keyboard.
     *
     * @hide
     */
    public boolean isFullKeyboard() {
        return (mSources & SOURCE_KEYBOARD) == SOURCE_KEYBOARD
                && mKeyboardType == KEYBOARD_TYPE_ALPHABETIC;
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
        return mKeyCharacterMap;
    }

    /**
     * Gets whether the device is capable of producing the list of keycodes.
     * @param keys The list of android keycodes to check for.
     * @return An array of booleans where each member specifies whether the device is capable of
     * generating the keycode given by the corresponding value at the same index in the keys array.
     */
    public boolean[] hasKeys(int... keys) {
        return InputManager.getInstance().deviceHasKeys(mId, keys);
    }

    /**
     * Gets information about the range of values for a particular {@link MotionEvent} axis.
     * If the device supports multiple sources, the same axis may have different meanings
     * for each source.  Returns information about the first axis found for any source.
     * To obtain information about the axis for a specific source, use
     * {@link #getMotionRange(int, int)}.
     *
     * @param axis The axis constant.
     * @return The range of values, or null if the requested axis is not
     * supported by the device.
     *
     * @see MotionEvent#AXIS_X
     * @see MotionEvent#AXIS_Y
     */
    public MotionRange getMotionRange(int axis) {
        final int numRanges = mMotionRanges.size();
        for (int i = 0; i < numRanges; i++) {
            final MotionRange range = mMotionRanges.get(i);
            if (range.mAxis == axis) {
                return range;
            }
        }
        return null;
    }

    /**
     * Gets information about the range of values for a particular {@link MotionEvent} axis
     * used by a particular source on the device.
     * If the device supports multiple sources, the same axis may have different meanings
     * for each source.
     *
     * @param axis The axis constant.
     * @param source The source for which to return information.
     * @return The range of values, or null if the requested axis is not
     * supported by the device.
     *
     * @see MotionEvent#AXIS_X
     * @see MotionEvent#AXIS_Y
     */
    public MotionRange getMotionRange(int axis, int source) {
        final int numRanges = mMotionRanges.size();
        for (int i = 0; i < numRanges; i++) {
            final MotionRange range = mMotionRanges.get(i);
            if (range.mAxis == axis && range.mSource == source) {
                return range;
            }
        }
        return null;
    }

    /**
     * Gets the ranges for all axes supported by the device.
     * @return The motion ranges for the device.
     *
     * @see #getMotionRange(int, int)
     */
    public List<MotionRange> getMotionRanges() {
        return mMotionRanges;
    }

    // Called from native code.
    private void addMotionRange(int axis, int source,
            float min, float max, float flat, float fuzz, float resolution) {
        mMotionRanges.add(new MotionRange(axis, source, min, max, flat, fuzz, resolution));
    }

    /**
     * Gets the vibrator service associated with the device, if there is one.
     * Even if the device does not have a vibrator, the result is never null.
     * Use {@link Vibrator#hasVibrator} to determine whether a vibrator is
     * present.
     *
     * Note that the vibrator associated with the device may be different from
     * the system vibrator.  To obtain an instance of the system vibrator instead, call
     * {@link Context#getSystemService} with {@link Context#VIBRATOR_SERVICE} as argument.
     *
     * @return The vibrator service associated with the device, never null.
     */
    public Vibrator getVibrator() {
        synchronized (mMotionRanges) {
            if (mVibrator == null) {
                if (mHasVibrator) {
                    mVibrator = InputManager.getInstance().getInputDeviceVibrator(mId);
                } else {
                    mVibrator = NullVibrator.getInstance();
                }
            }
            return mVibrator;
        }
    }

    /**
     * Reports whether the device has a button under its touchpad
     * @return Whether the device has a button under its touchpad
     * @hide
     */
    public boolean hasButtonUnderPad() {
        return mHasButtonUnderPad;
    }

    /**
     * Provides information about the range of values for a particular {@link MotionEvent} axis.
     *
     * @see InputDevice#getMotionRange(int)
     */
    public static final class MotionRange {
        private int mAxis;
        private int mSource;
        private float mMin;
        private float mMax;
        private float mFlat;
        private float mFuzz;
        private float mResolution;

        private MotionRange(int axis, int source, float min, float max, float flat, float fuzz,
                float resolution) {
            mAxis = axis;
            mSource = source;
            mMin = min;
            mMax = max;
            mFlat = flat;
            mFuzz = fuzz;
            mResolution = resolution;
        }

        /**
         * Gets the axis id.
         * @return The axis id.
         */
        public int getAxis() {
            return mAxis;
        }

        /**
         * Gets the source for which the axis is defined.
         * @return The source.
         */
        public int getSource() {
            return mSource;
        }


        /**
         * Determines whether the event is from the given source.
         *
         * @param source The input source to check against. This can be a specific device type,
         * such as {@link InputDevice#SOURCE_TOUCH_NAVIGATION}, or a more generic device class,
         * such as {@link InputDevice#SOURCE_CLASS_POINTER}.
         * @return Whether the event is from the given source.
         */
        public boolean isFromSource(int source) {
            return (getSource() & source) == source;
        }

        /**
         * Gets the inclusive minimum value for the axis.
         * @return The inclusive minimum value.
         */
        public float getMin() {
            return mMin;
        }

        /**
         * Gets the inclusive maximum value for the axis.
         * @return The inclusive maximum value.
         */
        public float getMax() {
            return mMax;
        }

        /**
         * Gets the range of the axis (difference between maximum and minimum).
         * @return The range of values.
         */
        public float getRange() {
            return mMax - mMin;
        }

        /**
         * Gets the extent of the center flat position with respect to this axis.
         * <p>
         * For example, a flat value of 8 means that the center position is between -8 and +8.
         * This value is mainly useful for calibrating self-centering devices.
         * </p>
         * @return The extent of the center flat position.
         */
        public float getFlat() {
            return mFlat;
        }

        /**
         * Gets the error tolerance for input device measurements with respect to this axis.
         * <p>
         * For example, a value of 2 indicates that the measured value may be up to +/- 2 units
         * away from the actual value due to noise and device sensitivity limitations.
         * </p>
         * @return The error tolerance.
         */
        public float getFuzz() {
            return mFuzz;
        }

        /**
         * Gets the resolution for input device measurements with respect to this axis.
         * @return The resolution in units per millimeter, or units per radian for rotational axes.
         */
        public float getResolution() {
            return mResolution;
        }
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mId);
        out.writeInt(mGeneration);
        out.writeInt(mControllerNumber);
        out.writeString(mName);
        out.writeInt(mVendorId);
        out.writeInt(mProductId);
        out.writeString(mDescriptor);
        out.writeInt(mIsExternal ? 1 : 0);
        out.writeInt(mSources);
        out.writeInt(mKeyboardType);
        mKeyCharacterMap.writeToParcel(out, flags);
        out.writeInt(mHasVibrator ? 1 : 0);
        out.writeInt(mHasButtonUnderPad ? 1 : 0);

        final int numRanges = mMotionRanges.size();
        for (int i = 0; i < numRanges; i++) {
            MotionRange range = mMotionRanges.get(i);
            out.writeInt(range.mAxis);
            out.writeInt(range.mSource);
            out.writeFloat(range.mMin);
            out.writeFloat(range.mMax);
            out.writeFloat(range.mFlat);
            out.writeFloat(range.mFuzz);
            out.writeFloat(range.mResolution);
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
        description.append("  Descriptor: ").append(mDescriptor).append("\n");
        description.append("  Generation: ").append(mGeneration).append("\n");
        description.append("  Location: ").append(mIsExternal ? "external" : "built-in").append("\n");

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

        description.append("  Has Vibrator: ").append(mHasVibrator).append("\n");

        description.append("  Sources: 0x").append(Integer.toHexString(mSources)).append(" (");
        appendSourceDescriptionIfApplicable(description, SOURCE_KEYBOARD, "keyboard");
        appendSourceDescriptionIfApplicable(description, SOURCE_DPAD, "dpad");
        appendSourceDescriptionIfApplicable(description, SOURCE_TOUCHSCREEN, "touchscreen");
        appendSourceDescriptionIfApplicable(description, SOURCE_MOUSE, "mouse");
        appendSourceDescriptionIfApplicable(description, SOURCE_STYLUS, "stylus");
        appendSourceDescriptionIfApplicable(description, SOURCE_TRACKBALL, "trackball");
        appendSourceDescriptionIfApplicable(description, SOURCE_TOUCHPAD, "touchpad");
        appendSourceDescriptionIfApplicable(description, SOURCE_JOYSTICK, "joystick");
        appendSourceDescriptionIfApplicable(description, SOURCE_GAMEPAD, "gamepad");
        description.append(" )\n");

        final int numAxes = mMotionRanges.size();
        for (int i = 0; i < numAxes; i++) {
            MotionRange range = mMotionRanges.get(i);
            description.append("    ").append(MotionEvent.axisToString(range.mAxis));
            description.append(": source=0x").append(Integer.toHexString(range.mSource));
            description.append(" min=").append(range.mMin);
            description.append(" max=").append(range.mMax);
            description.append(" flat=").append(range.mFlat);
            description.append(" fuzz=").append(range.mFuzz);
            description.append(" resolution=").append(range.mResolution);
            description.append("\n");
        }
        return description.toString();
    }

    private void appendSourceDescriptionIfApplicable(StringBuilder description, int source,
            String sourceName) {
        if ((mSources & source) == source) {
            description.append(" ");
            description.append(sourceName);
        }
    }
}
