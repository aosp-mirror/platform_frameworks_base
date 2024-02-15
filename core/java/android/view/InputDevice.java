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

import static com.android.input.flags.Flags.FLAG_INPUT_DEVICE_VIEW_BEHAVIOR_API;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.hardware.BatteryState;
import android.hardware.SensorManager;
import android.hardware.input.HostUsiVersion;
import android.hardware.input.InputDeviceIdentifier;
import android.hardware.input.InputManager;
import android.hardware.input.InputManagerGlobal;
import android.hardware.lights.LightsManager;
import android.icu.util.ULocale;
import android.os.Build;
import android.os.NullVibrator;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.text.TextUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
    private final int mDeviceBus;
    private final String mDescriptor;
    private final InputDeviceIdentifier mIdentifier;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private final boolean mIsExternal;
    private final int mSources;
    private final int mKeyboardType;
    private final KeyCharacterMap mKeyCharacterMap;
    @Nullable
    private final String mKeyboardLanguageTag;
    @Nullable
    private final String mKeyboardLayoutType;
    private final boolean mHasVibrator;
    private final boolean mHasMicrophone;
    private final boolean mHasButtonUnderPad;
    private final boolean mHasSensor;
    private final boolean mHasBattery;
    private final HostUsiVersion mHostUsiVersion;
    private final int mAssociatedDisplayId;
    private final ArrayList<MotionRange> mMotionRanges = new ArrayList<MotionRange>();

    private final ViewBehavior mViewBehavior = new ViewBehavior(this);

    @GuardedBy("mMotionRanges")
    private Vibrator mVibrator; // guarded by mMotionRanges during initialization

    @GuardedBy("mMotionRanges")
    private VibratorManager mVibratorManager;

    @GuardedBy("mMotionRanges")
    private SensorManager mSensorManager;

    @GuardedBy("mMotionRanges")
    private LightsManager mLightsManager;

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

    /** @hide */
    @IntDef(flag = true, prefix = { "SOURCE_CLASS_" }, value = {
            SOURCE_CLASS_NONE,
            SOURCE_CLASS_BUTTON,
            SOURCE_CLASS_POINTER,
            SOURCE_CLASS_TRACKBALL,
            SOURCE_CLASS_POSITION,
            SOURCE_CLASS_JOYSTICK
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface InputSourceClass {}

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
     * This value is also used for other mouse-like pointing devices such as touchpads and pointing
     * sticks. When used in combination with {@link #SOURCE_STYLUS}, it denotes an external drawing
     * tablet.
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
     * The input device is a Bluetooth stylus.
     * <p>
     * Note that this bit merely indicates that an input device is capable of
     * obtaining input from a Bluetooth stylus.  To determine whether a given
     * touch event was produced by a stylus, examine the tool type returned by
     * {@link MotionEvent#getToolType(int)} for each individual pointer.
     * </p><p>
     * A single touch event may multiple pointers with different tool types,
     * such as an event that has one pointer with tool type
     * {@link MotionEvent#TOOL_TYPE_FINGER} and another pointer with tool type
     * {@link MotionEvent#TOOL_TYPE_STYLUS}.  So it is important to examine
     * the tool type of each pointer, regardless of the source reported
     * by {@link MotionEvent#getSource()}.
     * </p><p>
     * A bluetooth stylus generally receives its pressure and button state
     * information from the stylus itself, and derives the rest from another
     * source. For example, a Bluetooth stylus used in conjunction with a
     * touchscreen would derive its contact position and pointer size from the
     * touchscreen and may not be any more accurate than other tools such as
     * fingers.
     * </p>
     *
     * @see #SOURCE_STYLUS
     * @see #SOURCE_CLASS_POINTER
     */
    public static final int SOURCE_BLUETOOTH_STYLUS =
            0x00008000 | SOURCE_STYLUS;

    /**
     * The input source is a trackball.
     *
     * @see #SOURCE_CLASS_TRACKBALL
     */
    public static final int SOURCE_TRACKBALL = 0x00010000 | SOURCE_CLASS_TRACKBALL;

    /**
     * The input source is a mouse device whose relative motions should be interpreted as
     * navigation events.
     *
     * @see #SOURCE_CLASS_TRACKBALL
     */
    public static final int SOURCE_MOUSE_RELATIVE = 0x00020000 | SOURCE_CLASS_TRACKBALL;

    /**
     * The input source is a touchpad (also known as a trackpad). Touchpads that are used to move
     * the mouse cursor will also have {@link #SOURCE_MOUSE}.
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
     * The input source is a rotating encoder device whose motions should be interpreted as akin to
     * those of a scroll wheel.
     *
     * @see #SOURCE_CLASS_NONE
     */
    public static final int SOURCE_ROTARY_ENCODER = 0x00400000 | SOURCE_CLASS_NONE;

    /**
     * The input source is a joystick.
     * (It may also be a {@link #SOURCE_GAMEPAD}).
     *
     * @see #SOURCE_CLASS_JOYSTICK
     */
    public static final int SOURCE_JOYSTICK = 0x01000000 | SOURCE_CLASS_JOYSTICK;

    /**
     * The input source is a device connected through HDMI-based bus.
     *
     * The key comes in through HDMI-CEC or MHL signal line, and is treated as if it were
     * generated by a locally connected DPAD or keyboard.
     */
    public static final int SOURCE_HDMI = 0x02000000 | SOURCE_CLASS_BUTTON;

    /**
     * The input source is a sensor associated with the input device.
     *
     * @see #SOURCE_CLASS_NONE
     */
    public static final int SOURCE_SENSOR = 0x04000000 | SOURCE_CLASS_NONE;

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

    // Cap motion ranges to prevent attacks (b/25637534)
    private static final int MAX_RANGES = 1000;

    private static final int VIBRATOR_ID_ALL = -1;

    public static final @android.annotation.NonNull Parcelable.Creator<InputDevice> CREATOR =
            new Parcelable.Creator<InputDevice>() {
        public InputDevice createFromParcel(Parcel in) {
            return new InputDevice(in);
        }
        public InputDevice[] newArray(int size) {
            return new InputDevice[size];
        }
    };

    /**
     * Called by native code
     */
    private InputDevice(int id, int generation, int controllerNumber, String name, int vendorId,
            int productId, int deviceBus, String descriptor, boolean isExternal, int sources,
            int keyboardType, KeyCharacterMap keyCharacterMap, @Nullable String keyboardLanguageTag,
            @Nullable String keyboardLayoutType, boolean hasVibrator, boolean hasMicrophone,
            boolean hasButtonUnderPad, boolean hasSensor, boolean hasBattery, int usiVersionMajor,
            int usiVersionMinor, int associatedDisplayId) {
        mId = id;
        mGeneration = generation;
        mControllerNumber = controllerNumber;
        mName = name;
        mVendorId = vendorId;
        mProductId = productId;
        mDeviceBus = deviceBus;
        mDescriptor = descriptor;
        mIsExternal = isExternal;
        mSources = sources;
        mKeyboardType = keyboardType;
        mKeyCharacterMap = keyCharacterMap;
        if (!TextUtils.isEmpty(keyboardLanguageTag)) {
            String langTag;
            langTag = ULocale
                    .createCanonical(ULocale.forLanguageTag(keyboardLanguageTag))
                    .toLanguageTag();
            mKeyboardLanguageTag = TextUtils.equals(langTag, "und") ? null : langTag;
        } else {
            mKeyboardLanguageTag = null;
        }
        mKeyboardLayoutType = keyboardLayoutType;
        mHasVibrator = hasVibrator;
        mHasMicrophone = hasMicrophone;
        mHasButtonUnderPad = hasButtonUnderPad;
        mHasSensor = hasSensor;
        mHasBattery = hasBattery;
        mIdentifier = new InputDeviceIdentifier(descriptor, vendorId, productId);
        mHostUsiVersion = new HostUsiVersion(usiVersionMajor, usiVersionMinor);
        mAssociatedDisplayId = associatedDisplayId;
    }

    private InputDevice(Parcel in) {
        mKeyCharacterMap = KeyCharacterMap.CREATOR.createFromParcel(in);
        mId = in.readInt();
        mGeneration = in.readInt();
        mControllerNumber = in.readInt();
        mName = in.readString();
        mVendorId = in.readInt();
        mProductId = in.readInt();
        mDeviceBus = in.readInt();
        mDescriptor = in.readString();
        mIsExternal = in.readInt() != 0;
        mSources = in.readInt();
        mKeyboardType = in.readInt();
        mKeyboardLanguageTag = in.readString8();
        mKeyboardLayoutType = in.readString8();
        mHasVibrator = in.readInt() != 0;
        mHasMicrophone = in.readInt() != 0;
        mHasButtonUnderPad = in.readInt() != 0;
        mHasSensor = in.readInt() != 0;
        mHasBattery = in.readInt() != 0;
        mHostUsiVersion = HostUsiVersion.CREATOR.createFromParcel(in);
        mAssociatedDisplayId = in.readInt();
        mIdentifier = new InputDeviceIdentifier(mDescriptor, mVendorId, mProductId);

        int numRanges = in.readInt();
        if (numRanges > MAX_RANGES) {
            numRanges = MAX_RANGES;
        }

        for (int i = 0; i < numRanges; i++) {
            addMotionRange(in.readInt(), in.readInt(), in.readFloat(), in.readFloat(),
                    in.readFloat(), in.readFloat(), in.readFloat());
        }

        mViewBehavior.mShouldSmoothScroll = in.readBoolean();
    }

    /**
     * InputDevice builder used to create an InputDevice for tests in Java.
     *
     * @hide
     */
    @VisibleForTesting
    public static class Builder {
        private int mId = 0;
        private int mGeneration = 0;
        private int mControllerNumber = 0;
        private String mName = "";
        private int mVendorId = 0;
        private int mProductId = 0;
        private int mDeviceBus = 0;
        private String mDescriptor = "";
        private boolean mIsExternal = false;
        private int mSources = 0;
        private int mKeyboardType = 0;
        private KeyCharacterMap mKeyCharacterMap = null;
        private boolean mHasVibrator = false;
        private boolean mHasMicrophone = false;
        private boolean mHasButtonUnderPad = false;
        private boolean mHasSensor = false;
        private boolean mHasBattery = false;
        private String mKeyboardLanguageTag = null;
        private String mKeyboardLayoutType = null;
        private int mUsiVersionMajor = -1;
        private int mUsiVersionMinor = -1;
        private int mAssociatedDisplayId = Display.INVALID_DISPLAY;
        private List<MotionRange> mMotionRanges = new ArrayList<>();
        private boolean mShouldSmoothScroll;

        /** @see InputDevice#getId() */
        public Builder setId(int id) {
            mId = id;
            return this;
        }

        /** @see InputDevice#getGeneration() */
        public Builder setGeneration(int generation) {
            mGeneration = generation;
            return this;
        }

        /** @see InputDevice#getControllerNumber() */
        public Builder setControllerNumber(int controllerNumber) {
            mControllerNumber = controllerNumber;
            return this;
        }

        /** @see InputDevice#getName() */
        public Builder setName(String name) {
            mName = name;
            return this;
        }

        /** @see InputDevice#getVendorId() */
        public Builder setVendorId(int vendorId) {
            mVendorId = vendorId;
            return this;
        }

        /** @see InputDevice#getProductId() */
        public Builder setProductId(int productId) {
            mProductId = productId;
            return this;
        }

        /** @see InputDevice#getDeviceBus() */
        public Builder setDeviceBus(int deviceBus) {
            mDeviceBus = deviceBus;
            return this;
        }

        /** @see InputDevice#getDescriptor() */
        public Builder setDescriptor(String descriptor) {
            mDescriptor = descriptor;
            return this;
        }

        /** @see InputDevice#isExternal() */
        public Builder setExternal(boolean external) {
            mIsExternal = external;
            return this;
        }

        /** @see InputDevice#getSources() */
        public Builder setSources(int sources) {
            mSources = sources;
            return this;
        }

        /** @see InputDevice#getKeyboardType() */
        public Builder setKeyboardType(int keyboardType) {
            mKeyboardType = keyboardType;
            return this;
        }

        /** @see InputDevice#getKeyCharacterMap() */
        public Builder setKeyCharacterMap(KeyCharacterMap keyCharacterMap) {
            mKeyCharacterMap = keyCharacterMap;
            return this;
        }

        /** @see InputDevice#getVibrator() */
        public Builder setHasVibrator(boolean hasVibrator) {
            mHasVibrator = hasVibrator;
            return this;
        }

        /** @see InputDevice#hasMicrophone() */
        public Builder setHasMicrophone(boolean hasMicrophone) {
            mHasMicrophone = hasMicrophone;
            return this;
        }

        /** @see InputDevice#hasButtonUnderPad() */
        public Builder setHasButtonUnderPad(boolean hasButtonUnderPad) {
            mHasButtonUnderPad = hasButtonUnderPad;
            return this;
        }

        /** @see InputDevice#hasSensor() */
        public Builder setHasSensor(boolean hasSensor) {
            mHasSensor = hasSensor;
            return this;
        }

        /** @see InputDevice#hasBattery() */
        public Builder setHasBattery(boolean hasBattery) {
            mHasBattery = hasBattery;
            return this;
        }

        /** @see InputDevice#getKeyboardLanguageTag() */
        public Builder setKeyboardLanguageTag(String keyboardLanguageTag) {
            mKeyboardLanguageTag = keyboardLanguageTag;
            return this;
        }

        /** @see InputDevice#getKeyboardLayoutType() */
        public Builder setKeyboardLayoutType(String keyboardLayoutType) {
            mKeyboardLayoutType = keyboardLayoutType;
            return this;
        }

        /** @see InputDevice#getHostUsiVersion() */
        public Builder setUsiVersion(@Nullable HostUsiVersion usiVersion) {
            mUsiVersionMajor = usiVersion != null ? usiVersion.getMajorVersion() : -1;
            mUsiVersionMinor = usiVersion != null ? usiVersion.getMinorVersion() : -1;
            return this;
        }

        /** @see InputDevice#getAssociatedDisplayId() */
        public Builder setAssociatedDisplayId(int displayId) {
            mAssociatedDisplayId = displayId;
            return this;
        }

        /** @see InputDevice#getMotionRanges() */
        public Builder addMotionRange(int axis, int source,
                float min, float max, float flat, float fuzz, float resolution) {
            mMotionRanges.add(new MotionRange(axis, source, min, max, flat, fuzz, resolution));
            return this;
        }

        /**
         * Sets the view behavior for smooth scrolling ({@code false} by default).
         *
         * @see ViewBehavior#shouldSmoothScroll(int, int)
         */
        public Builder setShouldSmoothScroll(boolean shouldSmoothScroll) {
            mShouldSmoothScroll = shouldSmoothScroll;
            return this;
        }

        /** Build {@link InputDevice}. */
        public InputDevice build() {
            InputDevice device = new InputDevice(
                    mId,
                    mGeneration,
                    mControllerNumber,
                    mName,
                    mVendorId,
                    mProductId,
                    mDeviceBus,
                    mDescriptor,
                    mIsExternal,
                    mSources,
                    mKeyboardType,
                    mKeyCharacterMap,
                    mKeyboardLanguageTag,
                    mKeyboardLayoutType,
                    mHasVibrator,
                    mHasMicrophone,
                    mHasButtonUnderPad,
                    mHasSensor,
                    mHasBattery,
                    mUsiVersionMajor,
                    mUsiVersionMinor,
                    mAssociatedDisplayId);

            final int numRanges = mMotionRanges.size();
            for (int i = 0; i < numRanges; i++) {
                final MotionRange range = mMotionRanges.get(i);
                device.addMotionRange(
                        range.getAxis(),
                        range.getSource(),
                        range.getMin(),
                        range.getMax(),
                        range.getFlat(),
                        range.getFuzz(),
                        range.getResolution());
            }

            device.setShouldSmoothScroll(mShouldSmoothScroll);

            return device;
        }
    }

    /**
     * Gets information about the input device with the specified id.
     * @param id The device id.
     * @return The input device or null if not found.
     */
    @Nullable
    public static InputDevice getDevice(int id) {
        return InputManagerGlobal.getInstance().getInputDevice(id);
    }

    /**
     * Gets the ids of all input devices in the system.
     * @return The input device ids.
     */
    public static int[] getDeviceIds() {
        return InputManagerGlobal.getInstance().getInputDeviceIds();
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
     * can be observed by registering an
     * {@link android.hardware.input.InputManager.InputDeviceListener}.
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
     * The set of identifying information for type of input device. This
     * information can be used by the system to configure appropriate settings
     * for the device.
     *
     * @return The identifier object for this device
     * @hide
     */
    @TestApi
    @NonNull
    public InputDeviceIdentifier getIdentifier() {
        return mIdentifier;
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
     * Gets the device bus used by given device, if available.
     * <p>
     * The device bus is the communication system used for transferring data
     * (e.g. USB, Bluetooth etc.). This value comes from the kernel (from input.h).
     * A value of 0 will be assigned where the device bus is not available.
     * </p>
     *
     * @return The device bus of a given device
     * @hide
     */
    public int getDeviceBus() {
        return mDeviceBus;
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
     * has a touchpad.  Alternately, it may be that the input devices are simply
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
     * Determines whether the input device supports the given source or sources.
     *
     * @param source The input source or sources to check against. This can be a generic device
     * type such as {@link InputDevice#SOURCE_MOUSE}, a more generic device class, such as
     * {@link InputDevice#SOURCE_CLASS_POINTER}, or a combination of sources bitwise ORed together.
     * @return Whether the device can produce all of the given sources.
     */
    public boolean supportsSource(int source) {
        return (mSources & source) == source;
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
     * Returns the keyboard language as an IETF
     * <a href="https://tools.ietf.org/html/bcp47">BCP-47</a>
     * conformant tag if available.
     *
     * @hide
     */
    @Nullable
    @TestApi
    public String getKeyboardLanguageTag() {
        return mKeyboardLanguageTag;
    }

    /**
     * Returns the keyboard layout type if available.
     *
     * @hide
     */
    @Nullable
    @TestApi
    public String getKeyboardLayoutType() {
        return mKeyboardLayoutType;
    }

    /**
     * Gets whether the device is capable of producing the list of keycodes.
     *
     * @param keys The list of android keycodes to check for.
     * @return An array of booleans where each member specifies whether the device is capable of
     * generating the keycode given by the corresponding value at the same index in the keys array.
     */
    public boolean[] hasKeys(int... keys) {
        return InputManagerGlobal.getInstance().deviceHasKeys(mId, keys);
    }

    /**
     * Gets the {@link android.view.KeyEvent key code} produced by the given location on a reference
     * QWERTY keyboard layout.
     * <p>
     * This API is useful for querying the physical location of keys that change the character
     * produced based on the current locale and keyboard layout.
     * <p>
     * The following table provides a non-exhaustive list of examples:
     * <table border="2" width="85%" align="center" cellpadding="5">
     *     <thead>
     *         <tr><th>Active Keyboard Layout</th> <th>Input Parameter</th>
     *         <th>Return Value</th></tr>
     *     </thead>
     *
     *     <tbody>
     *     <tr>
     *         <td>French AZERTY</td>
     *         <td><code>{@link KeyEvent#KEYCODE_Q}</code></td>
     *         <td><code>{@link KeyEvent#KEYCODE_A}</code></td>
     *     </tr>
     *     <tr>
     *         <td>German QWERTZ</td>
     *         <td><code>{@link KeyEvent#KEYCODE_Y}</code></td>
     *         <td><code>{@link KeyEvent#KEYCODE_Z}</code></td>
     *     </tr>
     *     <tr>
     *         <td>US QWERTY</td>
     *         <td><code>{@link KeyEvent#KEYCODE_B}</code></td>
     *         <td><code>{@link KeyEvent#KEYCODE_B}</code></td>
     *     </tr>
     *     </tbody>
     * </table>
     *
     * @param locationKeyCode The location of a key specified as a key code on the QWERTY layout.
     * This provides a consistent way of referring to the physical location of a key independently
     * of the current keyboard layout. Also see the
     * <a href="https://www.w3.org/TR/2017/CR-uievents-code-20170601/#key-alphanumeric-writing-system">
     * hypothetical keyboard</a> provided by the W3C, which may be helpful for identifying the
     * physical location of a key.
     * @return The key code produced by the key at the specified location, given the current
     * keyboard layout. Returns {@link KeyEvent#KEYCODE_UNKNOWN} if the device does not specify
     * {@link InputDevice#SOURCE_KEYBOARD} or the requested mapping cannot be determined.
     */
    public int getKeyCodeForKeyLocation(int locationKeyCode) {
        return InputManagerGlobal.getInstance()
                .getKeyCodeForKeyLocation(mId, locationKeyCode);
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

    /**
     * Provides the {@link ViewBehavior} for the device.
     *
     * <p>This behavior is designed to be obtained using the
     * {@link InputManager#getInputDeviceViewBehavior(int)} API, to allow associating the behavior
     * with a {@link Context} (since input device is not associated with a context).
     * The ability to associate the behavior with a context opens capabilities like linking the
     * behavior to user settings, for example.
     *
     * @hide
     */
    @NonNull
    public ViewBehavior getViewBehavior() {
        return mViewBehavior;
    }

    // Called from native code.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void addMotionRange(int axis, int source,
            float min, float max, float flat, float fuzz, float resolution) {
        mMotionRanges.add(new MotionRange(axis, source, min, max, flat, fuzz, resolution));
    }

    // Called from native code.
    private void setShouldSmoothScroll(boolean shouldSmoothScroll) {
        mViewBehavior.mShouldSmoothScroll = shouldSmoothScroll;
    }

    /**
     * Returns the Bluetooth address of this input device, if known.
     *
     * The returned string is always null if this input device is not connected
     * via Bluetooth, or if the Bluetooth address of the device cannot be
     * determined. The returned address will look like: "11:22:33:44:55:66".
     * @hide
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    @Nullable
    public String getBluetoothAddress() {
        // We query the address via a separate InputManagerGlobal API
        // instead of pre-populating it in this class to avoid
        // leaking it to apps that do not have sufficient permissions.
        return InputManagerGlobal.getInstance()
                .getInputDeviceBluetoothAddress(mId);
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
     * @deprecated Use {@link #getVibratorManager()} to retrieve the default device vibrator.
     */
    @Deprecated
    public Vibrator getVibrator() {
        synchronized (mMotionRanges) {
            if (mVibrator == null) {
                if (mHasVibrator) {
                    mVibrator = InputManagerGlobal.getInstance()
                            .getInputDeviceVibrator(mId,
                            VIBRATOR_ID_ALL);
                } else {
                    mVibrator = NullVibrator.getInstance();
                }
            }
            return mVibrator;
        }
    }

    /**
     * Gets the vibrator manager associated with the device.
     * Even if the device does not have a vibrator manager, the result is never null.
     * Use {@link VibratorManager#getVibratorIds} to determine whether any vibrator is
     * present.
     *
     * @return The vibrator manager associated with the device, never null.
     */
    @NonNull
    public VibratorManager getVibratorManager() {
        synchronized (mMotionRanges) {
            if (mVibratorManager == null) {
                mVibratorManager = InputManagerGlobal.getInstance()
                        .getInputDeviceVibratorManager(mId);
            }
        }
        return mVibratorManager;
    }

    /**
     * Gets the battery state object associated with the device, if there is one.
     * Even if the device does not have a battery, the result is never null.
     * Use {@link BatteryState#isPresent} to determine whether a battery is
     * present.
     *
     * @return The battery object associated with the device, never null.
     */
    @NonNull
    public BatteryState getBatteryState() {
        return InputManagerGlobal.getInstance()
                .getInputDeviceBatteryState(mId, mHasBattery);
    }

    /**
     * Gets the lights manager associated with the device, if there is one.
     * Even if the device does not have lights, the result is never null.
     * Use {@link LightsManager#getLights} to determine whether any lights is
     * present.
     *
     * @return The lights manager associated with the device, never null.
     */
    @NonNull
    public LightsManager getLightsManager() {
        synchronized (mMotionRanges) {
            if (mLightsManager == null) {
                mLightsManager = InputManagerGlobal.getInstance()
                        .getInputDeviceLightsManager(mId);
            }
        }
        return mLightsManager;
    }

    /**
     * Gets the sensor manager service associated with the input device.
     * Even if the device does not have a sensor, the result is never null.
     * Use {@link SensorManager#getSensorList} to get a full list of all supported sensors.
     *
     * Note that the sensors associated with the device may be different from
     * the system sensors, as typically they are builtin sensors physically attached to
     * input devices.
     *
     * @return The sensor manager service associated with the device, never null.
     */
    @NonNull
    public SensorManager getSensorManager() {
        synchronized (mMotionRanges) {
            if (mSensorManager == null) {
                mSensorManager = InputManagerGlobal.getInstance()
                        .getInputDeviceSensorManager(mId);
            }
        }
        return mSensorManager;
    }

    /**
     * Returns true if input device is enabled.
     * @return Whether the input device is enabled.
     */
    public boolean isEnabled() {
        return InputManagerGlobal.getInstance().isInputDeviceEnabled(mId);
    }

    /**
     * Enables the input device.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.DISABLE_INPUT_DEVICE)
    @TestApi
    public void enable() {
        InputManagerGlobal.getInstance().enableInputDevice(mId);
    }

    /**
     * Disables the input device.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.DISABLE_INPUT_DEVICE)
    @TestApi
    public void disable() {
        InputManagerGlobal.getInstance().disableInputDevice(mId);
    }

    /**
     * Reports whether the device has a built-in microphone.
     * @return Whether the device has a built-in microphone.
     */
    public boolean hasMicrophone() {
        return mHasMicrophone;
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
     * Reports whether the device has a sensor.
     * @return Whether the device has a sensor.
     * @hide
     */
    public boolean hasSensor() {
        return mHasSensor;
    }

    /**
     * Reports whether the device has a battery.
     * @return true if the device has a battery, false otherwise.
     * @hide
     */
    public boolean hasBattery() {
        return mHasBattery;
    }

    /**
     * Reports the version of the Universal Stylus Initiative (USI) protocol supported by this
     * input device.
     *
     * @return the supported USI version, or null if the device does not support USI
     * @see <a href="https://universalstylus.org">Universal Stylus Initiative</a>
     * @see InputManagerGlobal#getHostUsiVersion(int)
     * @hide
     */
    @Nullable
    public HostUsiVersion getHostUsiVersion() {
        return mHostUsiVersion.isValid() ? mHostUsiVersion : null;
    }

    /** @hide */
    @TestApi
    public int getAssociatedDisplayId() {
        return mAssociatedDisplayId;
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

    /**
     * Provides information on how views processing {@link MotionEvent}s generated by this input
     * device should respond to the events. Use {@link InputManager#getInputDeviceViewBehavior(int)}
     * to get an instance of the view behavior for an input device.
     *
     * <p>See an example below how a {@link View} can use this class to determine and apply the
     * scrolling behavior for a generic {@link MotionEvent}.
     *
     * <pre>{@code
     *     public boolean onGenericMotionEvent(MotionEvent event) {
     *         InputManager manager = context.getSystemService(InputManager.class);
     *         ViewBehavior viewBehavior = manager.getInputDeviceViewBehavior(event.getDeviceId());
     *         // Assume a helper function that tells us which axis to use for scrolling purpose.
     *         int axis = getScrollAxisForGenericMotionEvent(event);
     *         int source = event.getSource();
     *
     *         boolean shouldSmoothScroll =
     *                 viewBehavior != null && viewBehavior.shouldSmoothScroll(axis, source);
     *         // Proceed to running the scrolling logic...
     *     }
     * }</pre>
     *
     * @see InputManager#getInputDeviceViewBehavior(int)
     */
    @FlaggedApi(FLAG_INPUT_DEVICE_VIEW_BEHAVIOR_API)
    public static final class ViewBehavior {
        private static final boolean DEFAULT_SHOULD_SMOOTH_SCROLL = false;

        private final InputDevice mInputDevice;

        // TODO(b/246946631): implement support for InputDevices to adjust this configuration
        // by axis and source. When implemented, the axis/source specific config will take
        // precedence over this global config.
        /** A global smooth scroll configuration applying to all motion axis and input source. */
        private boolean mShouldSmoothScroll = DEFAULT_SHOULD_SMOOTH_SCROLL;

        /** @hide */
        public ViewBehavior(@NonNull InputDevice inputDevice) {
            mInputDevice = inputDevice;
        }

        /**
         * Returns whether a view should smooth scroll when scrolling due to a {@link MotionEvent}
         * generated by the input device.
         *
         * <p>Smooth scroll in this case refers to a scroll that animates the transition between
         * the starting and ending positions of the scroll. When this method returns {@code true},
         * views should try to animate a scroll generated by this device at the given axis and with
         * the given source to produce a good scroll user experience. If this method returns
         * {@code false}, animating scrolls is not necessary.
         *
         * <p>If the input device does not have a {@link MotionRange} with the provided axis and
         * source, this method returns {@code false}.
         *
         * @param axis the {@link MotionEvent} axis whose value is used to get the scroll extent.
         * @param source the {@link InputDevice} source from which the {@link MotionEvent} that
         *      triggers the scroll came.
         * @return {@code true} if smooth scrolling should be used for the scroll, or {@code false}
         *      if smooth scrolling is not necessary, or if the provided axis and source combination
         *      is not available for the input device.
         */
        @FlaggedApi(FLAG_INPUT_DEVICE_VIEW_BEHAVIOR_API)
        public boolean shouldSmoothScroll(int axis, int source) {
            // Note: although we currently do not use axis and source in computing the return value,
            // we will keep the API params to avoid further public API changes when we start
            // supporting axis/source configuration. Also, having these params lets OEMs provide
            // their custom implementation of the API that depends on axis and source.

            // TODO(b/246946631): speed up computation using caching of results.
            if (mInputDevice.getMotionRange(axis, source) == null) {
                return false;
            }
            return mShouldSmoothScroll;
        }
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        mKeyCharacterMap.writeToParcel(out, flags);
        out.writeInt(mId);
        out.writeInt(mGeneration);
        out.writeInt(mControllerNumber);
        out.writeString(mName);
        out.writeInt(mVendorId);
        out.writeInt(mProductId);
        out.writeInt(mDeviceBus);
        out.writeString(mDescriptor);
        out.writeInt(mIsExternal ? 1 : 0);
        out.writeInt(mSources);
        out.writeInt(mKeyboardType);
        out.writeString8(mKeyboardLanguageTag);
        out.writeString8(mKeyboardLayoutType);
        out.writeInt(mHasVibrator ? 1 : 0);
        out.writeInt(mHasMicrophone ? 1 : 0);
        out.writeInt(mHasButtonUnderPad ? 1 : 0);
        out.writeInt(mHasSensor ? 1 : 0);
        out.writeInt(mHasBattery ? 1 : 0);
        mHostUsiVersion.writeToParcel(out, flags);
        out.writeInt(mAssociatedDisplayId);

        int numRanges = mMotionRanges.size();
        numRanges = numRanges > MAX_RANGES ? MAX_RANGES : numRanges;
        out.writeInt(numRanges);
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

        out.writeBoolean(mViewBehavior.mShouldSmoothScroll);
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
        description.append("  Location: ").append(mIsExternal ? "external" : "built-in").append(
                "\n");

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

        description.append("  Has Sensor: ").append(mHasSensor).append("\n");

        description.append("  Has battery: ").append(mHasBattery).append("\n");

        description.append("  Has mic: ").append(mHasMicrophone).append("\n");

        description.append("  USI Version: ").append(getHostUsiVersion()).append("\n");

        if (mKeyboardLanguageTag != null) {
            description.append(" Keyboard language tag: ").append(mKeyboardLanguageTag).append(
                    "\n");
        }

        if (mKeyboardLayoutType != null) {
            description.append(" Keyboard layout type: ").append(mKeyboardLayoutType).append("\n");
        }

        description.append("  Sources: 0x").append(Integer.toHexString(mSources)).append(" (");
        appendSourceDescriptionIfApplicable(description, SOURCE_KEYBOARD, "keyboard");
        appendSourceDescriptionIfApplicable(description, SOURCE_DPAD, "dpad");
        appendSourceDescriptionIfApplicable(description, SOURCE_TOUCHSCREEN, "touchscreen");
        appendSourceDescriptionIfApplicable(description, SOURCE_MOUSE, "mouse");
        appendSourceDescriptionIfApplicable(description, SOURCE_STYLUS, "stylus");
        appendSourceDescriptionIfApplicable(description, SOURCE_TRACKBALL, "trackball");
        appendSourceDescriptionIfApplicable(description, SOURCE_MOUSE_RELATIVE, "mouse_relative");
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
