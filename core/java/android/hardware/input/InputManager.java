/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.hardware.input;

import android.Manifest;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.ActivityThread;
import android.compat.annotation.ChangeId;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.hardware.BatteryState;
import android.hardware.SensorManager;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.hardware.lights.LightsManager;
import android.hardware.lights.LightsRequest;
import android.os.Binder;
import android.os.BlockUntrustedTouchesMode;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.Handler;
import android.os.IBinder;
import android.os.IVibratorStateListener;
import android.os.InputEventInjectionSync;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.Log;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputMonitor;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.VerifiedInputEvent;
import android.view.WindowManager.LayoutParams;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.ArrayUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Provides information about input devices and available key layouts.
 */
@SystemService(Context.INPUT_SERVICE)
public final class InputManager {
    private static final String TAG = "InputManager";
    private static final boolean DEBUG = false;

    private static final int MSG_DEVICE_ADDED = 1;
    private static final int MSG_DEVICE_REMOVED = 2;
    private static final int MSG_DEVICE_CHANGED = 3;

    /** @hide */
    public static final int[] BLOCK_UNTRUSTED_TOUCHES_MODES = {
            BlockUntrustedTouchesMode.DISABLED,
            BlockUntrustedTouchesMode.PERMISSIVE,
            BlockUntrustedTouchesMode.BLOCK
    };

    private static InputManager sInstance;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final IInputManager mIm;

    // Guarded by mInputDevicesLock
    private final Object mInputDevicesLock = new Object();
    private SparseArray<InputDevice> mInputDevices;
    private InputDevicesChangedListener mInputDevicesChangedListener;
    private final ArrayList<InputDeviceListenerDelegate> mInputDeviceListeners =
            new ArrayList<InputDeviceListenerDelegate>();

    // Guarded by mTabletModeLock
    private final Object mTabletModeLock = new Object();
    private TabletModeChangedListener mTabletModeChangedListener;
    private List<OnTabletModeChangedListenerDelegate> mOnTabletModeChangedListeners;

    private final Object mBatteryListenersLock = new Object();
    // Maps a deviceId whose battery is currently being monitored to an entry containing the
    // registered listeners for that device.
    @GuardedBy("mBatteryListenersLock")
    private SparseArray<RegisteredBatteryListeners> mBatteryListeners;
    @GuardedBy("mBatteryListenersLock")
    private IInputDeviceBatteryListener mInputDeviceBatteryListener;

    private InputDeviceSensorManager mInputDeviceSensorManager;
    /**
     * Broadcast Action: Query available keyboard layouts.
     * <p>
     * The input manager service locates available keyboard layouts
     * by querying broadcast receivers that are registered for this action.
     * An application can offer additional keyboard layouts to the user
     * by declaring a suitable broadcast receiver in its manifest.
     * </p><p>
     * Here is an example broadcast receiver declaration that an application
     * might include in its AndroidManifest.xml to advertise keyboard layouts.
     * The meta-data specifies a resource that contains a description of each keyboard
     * layout that is provided by the application.
     * <pre><code>
     * &lt;receiver android:name=".InputDeviceReceiver"
     *         android:label="@string/keyboard_layouts_label">
     *     &lt;intent-filter>
     *         &lt;action android:name="android.hardware.input.action.QUERY_KEYBOARD_LAYOUTS" />
     *     &lt;/intent-filter>
     *     &lt;meta-data android:name="android.hardware.input.metadata.KEYBOARD_LAYOUTS"
     *             android:resource="@xml/keyboard_layouts" />
     * &lt;/receiver>
     * </code></pre>
     * </p><p>
     * In the above example, the <code>@xml/keyboard_layouts</code> resource refers to
     * an XML resource whose root element is <code>&lt;keyboard-layouts></code> that
     * contains zero or more <code>&lt;keyboard-layout></code> elements.
     * Each <code>&lt;keyboard-layout></code> element specifies the name, label, and location
     * of a key character map for a particular keyboard layout.  The label on the receiver
     * is used to name the collection of keyboard layouts provided by this receiver in the
     * keyboard layout settings.
     * <pre><code>
     * &lt;?xml version="1.0" encoding="utf-8"?>
     * &lt;keyboard-layouts xmlns:android="http://schemas.android.com/apk/res/android">
     *     &lt;keyboard-layout android:name="keyboard_layout_english_us"
     *             android:label="@string/keyboard_layout_english_us_label"
     *             android:keyboardLayout="@raw/keyboard_layout_english_us" />
     * &lt;/keyboard-layouts>
     * </pre></code>
     * </p><p>
     * The <code>android:name</code> attribute specifies an identifier by which
     * the keyboard layout will be known in the package.
     * The <code>android:label</code> attribute specifies a human-readable descriptive
     * label to describe the keyboard layout in the user interface, such as "English (US)".
     * The <code>android:keyboardLayout</code> attribute refers to a
     * <a href="http://source.android.com/tech/input/key-character-map-files.html">
     * key character map</a> resource that defines the keyboard layout.
     * </p>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_QUERY_KEYBOARD_LAYOUTS =
            "android.hardware.input.action.QUERY_KEYBOARD_LAYOUTS";

    /**
     * Metadata Key: Keyboard layout metadata associated with
     * {@link #ACTION_QUERY_KEYBOARD_LAYOUTS}.
     * <p>
     * Specifies the resource id of a XML resource that describes the keyboard
     * layouts that are provided by the application.
     * </p>
     */
    public static final String META_DATA_KEYBOARD_LAYOUTS =
            "android.hardware.input.metadata.KEYBOARD_LAYOUTS";

    /**
     * Pointer Speed: The minimum (slowest) pointer speed (-7).
     * @hide
     */
    public static final int MIN_POINTER_SPEED = -7;

    /**
     * Pointer Speed: The maximum (fastest) pointer speed (7).
     * @hide
     */
    public static final int MAX_POINTER_SPEED = 7;

    /**
     * Pointer Speed: The default pointer speed (0).
     * @hide
     */
    public static final int DEFAULT_POINTER_SPEED = 0;

    /**
     * The maximum allowed obscuring opacity by UID to propagate touches (0 <= x <= 1).
     * @hide
     */
    public static final float DEFAULT_MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH = .8f;

    /**
     * Default mode of the block untrusted touches mode feature.
     * @hide
     */
    @BlockUntrustedTouchesMode
    public static final int DEFAULT_BLOCK_UNTRUSTED_TOUCHES_MODE =
            BlockUntrustedTouchesMode.BLOCK;

    /**
     * Prevent touches from being consumed by apps if these touches passed through a non-trusted
     * window from a different UID and are considered unsafe.
     *
     * @hide
     */
    @TestApi
    @ChangeId
    public static final long BLOCK_UNTRUSTED_TOUCHES = 158002302L;

    /**
     * Input Event Injection Synchronization Mode: None.
     * Never blocks.  Injection is asynchronous and is assumed always to be successful.
     * @hide
     */
    public static final int INJECT_INPUT_EVENT_MODE_ASYNC = InputEventInjectionSync.NONE;

    /**
     * Input Event Injection Synchronization Mode: Wait for result.
     * Waits for previous events to be dispatched so that the input dispatcher can
     * determine whether input event injection will be permitted based on the current
     * input focus.  Does not wait for the input event to finish being handled
     * by the application.
     * @hide
     */
    public static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT =
            InputEventInjectionSync.WAIT_FOR_RESULT;

    /**
     * Input Event Injection Synchronization Mode: Wait for finish.
     * Waits for the event to be delivered to the application and handled.
     * @hide
     */
    @UnsupportedAppUsage(trackingBug = 171972397)
    public static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH =
            InputEventInjectionSync.WAIT_FOR_FINISHED;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "SWITCH_STATE_" }, value = {
            SWITCH_STATE_UNKNOWN,
            SWITCH_STATE_OFF,
            SWITCH_STATE_ON
    })
    public @interface SwitchState {}

    /**
     * Switch State: Unknown.
     *
     * The system has yet to report a valid value for the switch.
     * @hide
     */
    public static final int SWITCH_STATE_UNKNOWN = -1;

    /**
     * Switch State: Off.
     * @hide
     */
    public static final int SWITCH_STATE_OFF = 0;

    /**
     * Switch State: On.
     * @hide
     */
    public static final int SWITCH_STATE_ON = 1;

    private InputManager(IInputManager im) {
        mIm = im;
    }

    /**
     * Gets an instance of the input manager.
     *
     * @return The input manager instance.
     *
     * @hide
     */
    @VisibleForTesting
    public static InputManager resetInstance(IInputManager inputManagerService) {
        synchronized (InputManager.class) {
            sInstance = new InputManager(inputManagerService);
            return sInstance;
        }
    }

    /**
     * Clear the instance of the input manager.
     *
     * @hide
     */
    @VisibleForTesting
    public static void clearInstance() {
        synchronized (InputManager.class) {
            sInstance = null;
        }
    }

    /**
     * Gets an instance of the input manager.
     *
     * @return The input manager instance.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static InputManager getInstance() {
        synchronized (InputManager.class) {
            if (sInstance == null) {
                try {
                    sInstance = new InputManager(IInputManager.Stub
                            .asInterface(ServiceManager.getServiceOrThrow(Context.INPUT_SERVICE)));
                } catch (ServiceNotFoundException e) {
                    throw new IllegalStateException(e);
                }
            }
            return sInstance;
        }
    }

    /**
     * Gets information about the input device with the specified id.
     * @param id The device id.
     * @return The input device or null if not found.
     */
    public InputDevice getInputDevice(int id) {
        synchronized (mInputDevicesLock) {
            populateInputDevicesLocked();

            int index = mInputDevices.indexOfKey(id);
            if (index < 0) {
                return null;
            }

            InputDevice inputDevice = mInputDevices.valueAt(index);
            if (inputDevice == null) {
                try {
                    inputDevice = mIm.getInputDevice(id);
                } catch (RemoteException ex) {
                    throw ex.rethrowFromSystemServer();
                }
                if (inputDevice != null) {
                    mInputDevices.setValueAt(index, inputDevice);
                }
            }
            return inputDevice;
        }
    }

    /**
     * Gets information about the input device with the specified descriptor.
     * @param descriptor The input device descriptor.
     * @return The input device or null if not found.
     * @hide
     */
    public InputDevice getInputDeviceByDescriptor(String descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor must not be null.");
        }

        synchronized (mInputDevicesLock) {
            populateInputDevicesLocked();

            int numDevices = mInputDevices.size();
            for (int i = 0; i < numDevices; i++) {
                InputDevice inputDevice = mInputDevices.valueAt(i);
                if (inputDevice == null) {
                    int id = mInputDevices.keyAt(i);
                    try {
                        inputDevice = mIm.getInputDevice(id);
                    } catch (RemoteException ex) {
                        throw ex.rethrowFromSystemServer();
                    }
                    if (inputDevice == null) {
                        continue;
                    }
                    mInputDevices.setValueAt(i, inputDevice);
                }
                if (descriptor.equals(inputDevice.getDescriptor())) {
                    return inputDevice;
                }
            }
            return null;
        }
    }

    /**
     * Gets the ids of all input devices in the system.
     * @return The input device ids.
     */
    public int[] getInputDeviceIds() {
        synchronized (mInputDevicesLock) {
            populateInputDevicesLocked();

            final int count = mInputDevices.size();
            final int[] ids = new int[count];
            for (int i = 0; i < count; i++) {
                ids[i] = mInputDevices.keyAt(i);
            }
            return ids;
        }
    }

    /**
     * Returns true if an input device is enabled. Should return true for most
     * situations. Some system apps may disable an input device, for
     * example to prevent unwanted touch events.
     *
     * @param id The input device Id.
     *
     * @hide
     */
    public boolean isInputDeviceEnabled(int id) {
        try {
            return mIm.isInputDeviceEnabled(id);
        } catch (RemoteException ex) {
            Log.w(TAG, "Could not check enabled status of input device with id = " + id);
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Enables an InputDevice.
     * <p>
     * Requires {@link android.Manifest.permission.DISABLE_INPUT_DEVICE}.
     * </p>
     *
     * @param id The input device Id.
     *
     * @hide
     */
    public void enableInputDevice(int id) {
        try {
            mIm.enableInputDevice(id);
        } catch (RemoteException ex) {
            Log.w(TAG, "Could not enable input device with id = " + id);
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Disables an InputDevice.
     * <p>
     * Requires {@link android.Manifest.permission.DISABLE_INPUT_DEVICE}.
     * </p>
     *
     * @param id The input device Id.
     *
     * @hide
     */
    public void disableInputDevice(int id) {
        try {
            mIm.disableInputDevice(id);
        } catch (RemoteException ex) {
            Log.w(TAG, "Could not disable input device with id = " + id);
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Registers an input device listener to receive notifications about when
     * input devices are added, removed or changed.
     *
     * @param listener The listener to register.
     * @param handler The handler on which the listener should be invoked, or null
     * if the listener should be invoked on the calling thread's looper.
     *
     * @see #unregisterInputDeviceListener
     */
    public void registerInputDeviceListener(InputDeviceListener listener, Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        synchronized (mInputDevicesLock) {
            populateInputDevicesLocked();
            int index = findInputDeviceListenerLocked(listener);
            if (index < 0) {
                mInputDeviceListeners.add(new InputDeviceListenerDelegate(listener, handler));
            }
        }
    }

    /**
     * Unregisters an input device listener.
     *
     * @param listener The listener to unregister.
     *
     * @see #registerInputDeviceListener
     */
    public void unregisterInputDeviceListener(InputDeviceListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        synchronized (mInputDevicesLock) {
            int index = findInputDeviceListenerLocked(listener);
            if (index >= 0) {
                InputDeviceListenerDelegate d = mInputDeviceListeners.get(index);
                d.removeCallbacksAndMessages(null);
                mInputDeviceListeners.remove(index);
            }
        }
    }

    private int findInputDeviceListenerLocked(InputDeviceListener listener) {
        final int numListeners = mInputDeviceListeners.size();
        for (int i = 0; i < numListeners; i++) {
            if (mInputDeviceListeners.get(i).mListener == listener) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Queries whether the device is in tablet mode.
     *
     * @return The tablet switch state which is one of {@link #SWITCH_STATE_UNKNOWN},
     * {@link #SWITCH_STATE_OFF} or {@link #SWITCH_STATE_ON}.
     * @hide
     */
    @SwitchState
    public int isInTabletMode() {
        try {
            return mIm.isInTabletMode();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Register a tablet mode changed listener.
     *
     * @param listener The listener to register.
     * @param handler The handler on which the listener should be invoked, or null
     * if the listener should be invoked on the calling thread's looper.
     * @hide
     */
    public void registerOnTabletModeChangedListener(
            OnTabletModeChangedListener listener, Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        synchronized (mTabletModeLock) {
            if (mOnTabletModeChangedListeners == null) {
                initializeTabletModeListenerLocked();
            }
            int idx = findOnTabletModeChangedListenerLocked(listener);
            if (idx < 0) {
                OnTabletModeChangedListenerDelegate d =
                    new OnTabletModeChangedListenerDelegate(listener, handler);
                mOnTabletModeChangedListeners.add(d);
            }
        }
    }

    /**
     * Unregister a tablet mode changed listener.
     *
     * @param listener The listener to unregister.
     * @hide
     */
    public void unregisterOnTabletModeChangedListener(OnTabletModeChangedListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        synchronized (mTabletModeLock) {
            int idx = findOnTabletModeChangedListenerLocked(listener);
            if (idx >= 0) {
                OnTabletModeChangedListenerDelegate d = mOnTabletModeChangedListeners.remove(idx);
                d.removeCallbacksAndMessages(null);
            }
        }
    }

    private void initializeTabletModeListenerLocked() {
        final TabletModeChangedListener listener = new TabletModeChangedListener();
        try {
            mIm.registerTabletModeChangedListener(listener);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
        mTabletModeChangedListener = listener;
        mOnTabletModeChangedListeners = new ArrayList<>();
    }

    private int findOnTabletModeChangedListenerLocked(OnTabletModeChangedListener listener) {
        final int N = mOnTabletModeChangedListeners.size();
        for (int i = 0; i < N; i++) {
            if (mOnTabletModeChangedListeners.get(i).mListener == listener) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Queries whether the device's microphone is muted
     *
     * @return The mic mute switch state which is one of {@link #SWITCH_STATE_UNKNOWN},
     * {@link #SWITCH_STATE_OFF} or {@link #SWITCH_STATE_ON}.
     * @hide
     */
    @SwitchState
    public int isMicMuted() {
        try {
            return mIm.isMicMuted();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets information about all supported keyboard layouts.
     * <p>
     * The input manager consults the built-in keyboard layouts as well
     * as all keyboard layouts advertised by applications using a
     * {@link #ACTION_QUERY_KEYBOARD_LAYOUTS} broadcast receiver.
     * </p>
     *
     * @return A list of all supported keyboard layouts.
     *
     * @hide
     */
    public KeyboardLayout[] getKeyboardLayouts() {
        try {
            return mIm.getKeyboardLayouts();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the descriptors of all supported keyboard layouts appropriate for the specified
     * input device.
     * <p>
     * The input manager consults the built-in keyboard layouts as well as all keyboard layouts
     * advertised by applications using a {@link #ACTION_QUERY_KEYBOARD_LAYOUTS} broadcast receiver.
     * </p>
     *
     * @param device The input device to query.
     * @return The ids of all keyboard layouts which are supported by the specified input device.
     *
     * @hide
     */
    @TestApi
    @NonNull
    public List<String> getKeyboardLayoutDescriptorsForInputDevice(@NonNull InputDevice device) {
        KeyboardLayout[] layouts = getKeyboardLayoutsForInputDevice(device.getIdentifier());
        List<String> res = new ArrayList<>();
        for (KeyboardLayout kl : layouts) {
            res.add(kl.getDescriptor());
        }
        return res;
    }

    /**
     * Gets information about all supported keyboard layouts appropriate
     * for a specific input device.
     * <p>
     * The input manager consults the built-in keyboard layouts as well
     * as all keyboard layouts advertised by applications using a
     * {@link #ACTION_QUERY_KEYBOARD_LAYOUTS} broadcast receiver.
     * </p>
     *
     * @return A list of all supported keyboard layouts for a specific
     * input device.
     *
     * @hide
     */
    @NonNull
    public KeyboardLayout[] getKeyboardLayoutsForInputDevice(
            @NonNull InputDeviceIdentifier identifier) {
        try {
            return mIm.getKeyboardLayoutsForInputDevice(identifier);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the keyboard layout with the specified descriptor.
     *
     * @param keyboardLayoutDescriptor The keyboard layout descriptor, as returned by
     * {@link KeyboardLayout#getDescriptor()}.
     * @return The keyboard layout, or null if it could not be loaded.
     *
     * @hide
     */
    public KeyboardLayout getKeyboardLayout(String keyboardLayoutDescriptor) {
        if (keyboardLayoutDescriptor == null) {
            throw new IllegalArgumentException("keyboardLayoutDescriptor must not be null");
        }

        try {
            return mIm.getKeyboardLayout(keyboardLayoutDescriptor);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the current keyboard layout descriptor for the specified input device.
     *
     * @param identifier Identifier for the input device
     * @return The keyboard layout descriptor, or null if no keyboard layout has been set.
     *
     * @hide
     */
    @TestApi
    @Nullable
    public String getCurrentKeyboardLayoutForInputDevice(
            @NonNull InputDeviceIdentifier identifier) {
        try {
            return mIm.getCurrentKeyboardLayoutForInputDevice(identifier);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the current keyboard layout descriptor for the specified input device.
     * <p>
     * This method may have the side-effect of causing the input device in question to be
     * reconfigured.
     * </p>
     *
     * @param identifier The identifier for the input device.
     * @param keyboardLayoutDescriptor The keyboard layout descriptor to use, must not be null.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.SET_KEYBOARD_LAYOUT)
    public void setCurrentKeyboardLayoutForInputDevice(@NonNull InputDeviceIdentifier identifier,
            @NonNull String keyboardLayoutDescriptor) {
        if (identifier == null) {
            throw new IllegalArgumentException("identifier must not be null");
        }
        if (keyboardLayoutDescriptor == null) {
            throw new IllegalArgumentException("keyboardLayoutDescriptor must not be null");
        }

        try {
            mIm.setCurrentKeyboardLayoutForInputDevice(identifier,
                    keyboardLayoutDescriptor);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets all keyboard layout descriptors that are enabled for the specified input device.
     *
     * @param identifier The identifier for the input device.
     * @return The keyboard layout descriptors.
     *
     * @hide
     */
    public String[] getEnabledKeyboardLayoutsForInputDevice(InputDeviceIdentifier identifier) {
        if (identifier == null) {
            throw new IllegalArgumentException("inputDeviceDescriptor must not be null");
        }

        try {
            return mIm.getEnabledKeyboardLayoutsForInputDevice(identifier);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Adds the keyboard layout descriptor for the specified input device.
     * <p>
     * This method may have the side-effect of causing the input device in question to be
     * reconfigured.
     * </p>
     *
     * @param identifier The identifier for the input device.
     * @param keyboardLayoutDescriptor The descriptor of the keyboard layout to add.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.SET_KEYBOARD_LAYOUT)
    public void addKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            String keyboardLayoutDescriptor) {
        if (identifier == null) {
            throw new IllegalArgumentException("inputDeviceDescriptor must not be null");
        }
        if (keyboardLayoutDescriptor == null) {
            throw new IllegalArgumentException("keyboardLayoutDescriptor must not be null");
        }

        try {
            mIm.addKeyboardLayoutForInputDevice(identifier, keyboardLayoutDescriptor);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Removes the keyboard layout descriptor for the specified input device.
     * <p>
     * This method may have the side-effect of causing the input device in question to be
     * reconfigured.
     * </p>
     *
     * @param identifier The identifier for the input device.
     * @param keyboardLayoutDescriptor The descriptor of the keyboard layout to remove.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.SET_KEYBOARD_LAYOUT)
    public void removeKeyboardLayoutForInputDevice(@NonNull InputDeviceIdentifier identifier,
            @NonNull String keyboardLayoutDescriptor) {
        if (identifier == null) {
            throw new IllegalArgumentException("inputDeviceDescriptor must not be null");
        }
        if (keyboardLayoutDescriptor == null) {
            throw new IllegalArgumentException("keyboardLayoutDescriptor must not be null");
        }

        try {
            mIm.removeKeyboardLayoutForInputDevice(identifier, keyboardLayoutDescriptor);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the TouchCalibration applied to the specified input device's coordinates.
     *
     * @param inputDeviceDescriptor The input device descriptor.
     * @return The TouchCalibration currently assigned for use with the given
     * input device. If none is set, an identity TouchCalibration is returned.
     *
     * @hide
     */
    public TouchCalibration getTouchCalibration(String inputDeviceDescriptor, int surfaceRotation) {
        try {
            return mIm.getTouchCalibrationForInputDevice(inputDeviceDescriptor, surfaceRotation);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the TouchCalibration to apply to the specified input device's coordinates.
     * <p>
     * This method may have the side-effect of causing the input device in question
     * to be reconfigured. Requires {@link android.Manifest.permission.SET_INPUT_CALIBRATION}.
     * </p>
     *
     * @param inputDeviceDescriptor The input device descriptor.
     * @param calibration The calibration to be applied
     *
     * @hide
     */
    public void setTouchCalibration(String inputDeviceDescriptor, int surfaceRotation,
            TouchCalibration calibration) {
        try {
            mIm.setTouchCalibrationForInputDevice(inputDeviceDescriptor, surfaceRotation, calibration);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the mouse pointer speed.
     * <p>
     * Only returns the permanent mouse pointer speed.  Ignores any temporary pointer
     * speed set by {@link #tryPointerSpeed}.
     * </p>
     *
     * @param context The application context.
     * @return The pointer speed as a value between {@link #MIN_POINTER_SPEED} and
     * {@link #MAX_POINTER_SPEED}, or the default value {@link #DEFAULT_POINTER_SPEED}.
     *
     * @hide
     */
    public int getPointerSpeed(Context context) {
        int speed = DEFAULT_POINTER_SPEED;
        try {
            speed = Settings.System.getInt(context.getContentResolver(),
                    Settings.System.POINTER_SPEED);
        } catch (SettingNotFoundException snfe) {
        }
        return speed;
    }

    /**
     * Sets the mouse pointer speed.
     * <p>
     * Requires {@link android.Manifest.permission.WRITE_SETTINGS}.
     * </p>
     *
     * @param context The application context.
     * @param speed The pointer speed as a value between {@link #MIN_POINTER_SPEED} and
     * {@link #MAX_POINTER_SPEED}, or the default value {@link #DEFAULT_POINTER_SPEED}.
     *
     * @hide
     */
    public void setPointerSpeed(Context context, int speed) {
        if (speed < MIN_POINTER_SPEED || speed > MAX_POINTER_SPEED) {
            throw new IllegalArgumentException("speed out of range");
        }

        Settings.System.putInt(context.getContentResolver(),
                Settings.System.POINTER_SPEED, speed);
    }

    /**
     * Changes the mouse pointer speed temporarily, but does not save the setting.
     * <p>
     * Requires {@link android.Manifest.permission.SET_POINTER_SPEED}.
     * </p>
     *
     * @param speed The pointer speed as a value between {@link #MIN_POINTER_SPEED} and
     * {@link #MAX_POINTER_SPEED}, or the default value {@link #DEFAULT_POINTER_SPEED}.
     *
     * @hide
     */
    public void tryPointerSpeed(int speed) {
        if (speed < MIN_POINTER_SPEED || speed > MAX_POINTER_SPEED) {
            throw new IllegalArgumentException("speed out of range");
        }

        try {
            mIm.tryPointerSpeed(speed);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the maximum allowed obscuring opacity per UID to propagate touches.
     *
     * <p>For certain window types (eg. {@link LayoutParams#TYPE_APPLICATION_OVERLAY}), the decision
     * of honoring {@link LayoutParams#FLAG_NOT_TOUCHABLE} or not depends on the combined obscuring
     * opacity of the windows above the touch-consuming window, per UID. Check documentation of
     * {@link LayoutParams#FLAG_NOT_TOUCHABLE} for more details.
     *
     * <p>The value returned is between 0 (inclusive) and 1 (inclusive).
     *
     * @see LayoutParams#FLAG_NOT_TOUCHABLE
     */
    @FloatRange(from = 0, to = 1)
    public float getMaximumObscuringOpacityForTouch() {
        Context context = ActivityThread.currentApplication();
        return Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH,
                DEFAULT_MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH);
    }

    /**
     * Sets the maximum allowed obscuring opacity by UID to propagate touches.
     *
     * <p>For certain window types (eg. SAWs), the decision of honoring {@link LayoutParams
     * #FLAG_NOT_TOUCHABLE} or not depends on the combined obscuring opacity of the windows
     * above the touch-consuming window.
     *
     * <p>For a certain UID:
     * <ul>
     *     <li>If it's the same as the UID of the touch-consuming window, allow it to propagate
     *     the touch.
     *     <li>Otherwise take all its windows of eligible window types above the touch-consuming
     *     window, compute their combined obscuring opacity considering that {@code
     *     opacity(A, B) = 1 - (1 - opacity(A))*(1 - opacity(B))}. If the computed value is
     *     lesser than or equal to this setting and there are no other windows preventing the
     *     touch, allow the UID to propagate the touch.
     * </ul>
     *
     * <p>This value should be between 0 (inclusive) and 1 (inclusive).
     *
     * @see #getMaximumObscuringOpacityForTouch()
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    public void setMaximumObscuringOpacityForTouch(@FloatRange(from = 0, to = 1) float opacity) {
        if (opacity < 0 || opacity > 1) {
            throw new IllegalArgumentException(
                    "Maximum obscuring opacity for touch should be >= 0 and <= 1");
        }
        Context context = ActivityThread.currentApplication();
        Settings.Global.putFloat(context.getContentResolver(),
                Settings.Global.MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH, opacity);
    }

    /**
     * Returns the current mode of the block untrusted touches feature, one of:
     * <ul>
     *     <li>{@link BlockUntrustedTouchesMode#DISABLED}
     *     <li>{@link BlockUntrustedTouchesMode#PERMISSIVE}
     *     <li>{@link BlockUntrustedTouchesMode#BLOCK}
     * </ul>
     *
     * @hide
     */
    @TestApi
    @BlockUntrustedTouchesMode
    public int getBlockUntrustedTouchesMode(@NonNull Context context) {
        int mode = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.BLOCK_UNTRUSTED_TOUCHES_MODE, DEFAULT_BLOCK_UNTRUSTED_TOUCHES_MODE);
        if (!ArrayUtils.contains(BLOCK_UNTRUSTED_TOUCHES_MODES, mode)) {
            Log.w(TAG, "Unknown block untrusted touches feature mode " + mode + ", using "
                    + "default " + DEFAULT_BLOCK_UNTRUSTED_TOUCHES_MODE);
            return DEFAULT_BLOCK_UNTRUSTED_TOUCHES_MODE;
        }
        return mode;
    }

    /**
     * Sets the mode of the block untrusted touches feature to one of:
     * <ul>
     *     <li>{@link BlockUntrustedTouchesMode#DISABLED}
     *     <li>{@link BlockUntrustedTouchesMode#PERMISSIVE}
     *     <li>{@link BlockUntrustedTouchesMode#BLOCK}
     * </ul>
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    public void setBlockUntrustedTouchesMode(@NonNull Context context,
            @BlockUntrustedTouchesMode int mode) {
        if (!ArrayUtils.contains(BLOCK_UNTRUSTED_TOUCHES_MODES, mode)) {
            throw new IllegalArgumentException("Invalid feature mode " + mode);
        }
        Settings.Global.putInt(context.getContentResolver(),
                Settings.Global.BLOCK_UNTRUSTED_TOUCHES_MODE, mode);
    }

    /**
     * Queries the framework about whether any physical keys exist on any currently attached input
     * devices that are capable of producing the given array of key codes.
     *
     * @param keyCodes The array of key codes to query.
     * @return A new array of the same size as the key codes array whose elements
     * are set to true if at least one attached keyboard supports the corresponding key code
     * at the same index in the key codes array.
     *
     * @hide
     */
    public boolean[] deviceHasKeys(int[] keyCodes) {
        return deviceHasKeys(-1, keyCodes);
    }

    /**
     * Queries the framework about whether any physical keys exist on the specified input device
     * that are capable of producing the given array of key codes.
     *
     * @param id The id of the input device to query or -1 to consult all devices.
     * @param keyCodes The array of key codes to query.
     * @return A new array of the same size as the key codes array whose elements are set to true
     * if the given device could produce the corresponding key code at the same index in the key
     * codes array.
     *
     * @hide
     */
    public boolean[] deviceHasKeys(int id, int[] keyCodes) {
        boolean[] ret = new boolean[keyCodes.length];
        try {
            mIm.hasKeys(id, InputDevice.SOURCE_ANY, keyCodes, ret);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return ret;
    }

    /**
     * Gets the {@link android.view.KeyEvent key code} produced by the given location on a reference
     * QWERTY keyboard layout.
     * <p>
     * This API is useful for querying the physical location of keys that change the character
     * produced based on the current locale and keyboard layout.
     * <p>
     * @see InputDevice#getKeyCodeForKeyLocation(int) for examples.
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
     *
     * @hide
     */
    public int getKeyCodeForKeyLocation(int deviceId, int locationKeyCode) {
        try {
            return mIm.getKeyCodeForKeyLocation(deviceId, locationKeyCode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Injects an input event into the event system, targeting windows owned by the provided uid.
     *
     * If a valid targetUid is provided, the system will only consider injecting the input event
     * into windows owned by the provided uid. If the input event is targeted at a window that is
     * not owned by the provided uid, input injection will fail and a RemoteException will be
     * thrown.
     *
     * The synchronization mode determines whether the method blocks while waiting for
     * input injection to proceed.
     * <p>
     * Requires the {@link android.Manifest.permission.INJECT_EVENTS} permission.
     * </p><p>
     * Make sure you correctly set the event time and input source of the event
     * before calling this method.
     * </p>
     *
     * @param event The event to inject.
     * @param mode The synchronization mode.  One of:
     * {@link android.os.InputEventInjectionSync.NONE},
     * {@link android.os.InputEventInjectionSync.WAIT_FOR_RESULT}, or
     * {@link android.os.InputEventInjectionSync.WAIT_FOR_FINISHED}.
     * @param targetUid The uid to target, or {@link android.os.Process#INVALID_UID} to target all
     *                 windows.
     * @return True if input event injection succeeded.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.INJECT_EVENTS)
    public boolean injectInputEvent(InputEvent event, int mode, int targetUid) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (mode != InputEventInjectionSync.NONE
                && mode != InputEventInjectionSync.WAIT_FOR_FINISHED
                && mode != InputEventInjectionSync.WAIT_FOR_RESULT) {
            throw new IllegalArgumentException("mode is invalid");
        }

        try {
            return mIm.injectInputEventToTarget(event, mode, targetUid);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Injects an input event into the event system on behalf of an application.
     * The synchronization mode determines whether the method blocks while waiting for
     * input injection to proceed.
     * <p>
     * Requires the {@link android.Manifest.permission.INJECT_EVENTS} permission.
     * </p><p>
     * Make sure you correctly set the event time and input source of the event
     * before calling this method.
     * </p>
     *
     * @param event The event to inject.
     * @param mode The synchronization mode.  One of:
     * {@link android.os.InputEventInjectionSync.NONE},
     * {@link android.os.InputEventInjectionSync.WAIT_FOR_RESULT}, or
     * {@link android.os.InputEventInjectionSync.WAIT_FOR_FINISHED}.
     * @return True if input event injection succeeded.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.INJECT_EVENTS)
    @UnsupportedAppUsage
    public boolean injectInputEvent(InputEvent event, int mode) {
        return injectInputEvent(event, mode, Process.INVALID_UID);
    }

    /**
     * Verify the details of an {@link android.view.InputEvent} that came from the system.
     * If the event did not come from the system, or its details could not be verified, then this
     * will return {@code null}. Receiving {@code null} does not mean that the event did not
     * originate from the system, just that we were unable to verify it. This can
     * happen for a number of reasons during normal operation.
     *
     * @param event The {@link android.view.InputEvent} to check
     *
     * @return {@link android.view.VerifiedInputEvent}, which is a subset of the provided
     * {@link android.view.InputEvent}
     *         {@code null} if the event could not be verified.
     */
    public @Nullable VerifiedInputEvent verifyInputEvent(@NonNull InputEvent event) {
        try {
            return mIm.verifyInputEvent(event);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Changes the mouse pointer's icon shape into the specified id.
     *
     * @param iconId The id of the pointer graphic, as a value between
     * {@link PointerIcon.TYPE_ARROW} and {@link PointerIcon.TYPE_GRABBING}.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void setPointerIconType(int iconId) {
        try {
            mIm.setPointerIconType(iconId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void setCustomPointerIcon(PointerIcon icon) {
        try {
            mIm.setCustomPointerIcon(icon);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Request or release pointer capture.
     * <p>
     * When in capturing mode, the pointer icon disappears and all mouse events are dispatched to
     * the window which has requested the capture. Relative position changes are available through
     * {@link MotionEvent#getX} and {@link MotionEvent#getY}.
     *
     * @param enable true when requesting pointer capture, false when releasing.
     *
     * @hide
     */
    public void requestPointerCapture(IBinder windowToken, boolean enable) {
        try {
            mIm.requestPointerCapture(windowToken, enable);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Monitor input on the specified display for gestures.
     *
     * @hide
     */
    public InputMonitor monitorGestureInput(String name, int displayId) {
        try {
            return mIm.monitorGestureInput(new Binder(), name, displayId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Get sensors information as list.
     *
     * @hide
     */
    public InputSensorInfo[] getSensorList(int deviceId) {
        try {
            return mIm.getSensorList(deviceId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Enable input device sensor
     *
     * @hide
     */
    public boolean enableSensor(int deviceId, int sensorType, int samplingPeriodUs,
            int maxBatchReportLatencyUs) {
        try {
            return mIm.enableSensor(deviceId, sensorType, samplingPeriodUs,
                    maxBatchReportLatencyUs);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Enable input device sensor
     *
     * @hide
     */
    public void disableSensor(int deviceId, int sensorType) {
        try {
            mIm.disableSensor(deviceId, sensorType);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Flush input device sensor
     *
     * @hide
     */
    public boolean flushSensor(int deviceId, int sensorType) {
        try {
            return mIm.flushSensor(deviceId, sensorType);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Register input device sensor listener
     *
     * @hide
     */
    public boolean registerSensorListener(IInputSensorEventListener listener) {
        try {
            return mIm.registerSensorListener(listener);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Unregister input device sensor listener
     *
     * @hide
     */
    public void unregisterSensorListener(IInputSensorEventListener listener) {
        try {
            mIm.unregisterSensorListener(listener);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Get the battery status of the input device
     * @param deviceId The input device ID
     * @hide
     */
    public int getBatteryStatus(int deviceId) {
        try {
            return mIm.getBatteryStatus(deviceId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Get the remaining battery capacity of the input device
     * @param deviceId The input device ID
     * @hide
     */
    public int getBatteryCapacity(int deviceId) {
        try {
            return mIm.getBatteryCapacity(deviceId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Add a runtime association between the input port and the display port. This overrides any
     * static associations.
     * @param inputPort The port of the input device.
     * @param displayPort The physical port of the associated display.
     * <p>
     * Requires {@link android.Manifest.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY}.
     * </p>
     * @hide
     */
    public void addPortAssociation(@NonNull String inputPort, int displayPort) {
        try {
            mIm.addPortAssociation(inputPort, displayPort);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Remove the runtime association between the input port and the display port. Any existing
     * static association for the cleared input port will be restored.
     * @param inputPort The port of the input device to be cleared.
     * <p>
     * Requires {@link android.Manifest.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY}.
     * </p>
     * @hide
     */
    public void removePortAssociation(@NonNull String inputPort) {
        try {
            mIm.removePortAssociation(inputPort);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Add a runtime association between the input port and display, by unique id. Input ports are
     * expected to be unique.
     * @param inputPort The port of the input device.
     * @param displayUniqueId The unique id of the associated display.
     * <p>
     * Requires {@link android.Manifest.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY}.
     * </p>
     * @hide
     */
    public void addUniqueIdAssociation(@NonNull String inputPort, @NonNull String displayUniqueId) {
        try {
            mIm.addUniqueIdAssociation(inputPort, displayUniqueId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes a runtime association between the input device and display.
     * @param inputPort The port of the input device.
     * <p>
     * Requires {@link android.Manifest.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY}.
     * </p>
     * @hide
     */
    public void removeUniqueIdAssociation(@NonNull String inputPort) {
        try {
            mIm.removeUniqueIdAssociation(inputPort);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void populateInputDevicesLocked() {
        if (mInputDevicesChangedListener == null) {
            final InputDevicesChangedListener listener = new InputDevicesChangedListener();
            try {
                mIm.registerInputDevicesChangedListener(listener);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
            mInputDevicesChangedListener = listener;
        }

        if (mInputDevices == null) {
            final int[] ids;
            try {
                ids = mIm.getInputDeviceIds();
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }

            mInputDevices = new SparseArray<InputDevice>();
            // TODO(b/223905476): remove when the rootcause is fixed.
            if (ids != null) {
                for (int i = 0; i < ids.length; i++) {
                    mInputDevices.put(ids[i], null);
                }
            }
        }
    }

    private void onInputDevicesChanged(int[] deviceIdAndGeneration) {
        if (DEBUG) {
            Log.d(TAG, "Received input devices changed.");
        }

        synchronized (mInputDevicesLock) {
            for (int i = mInputDevices.size(); --i > 0; ) {
                final int deviceId = mInputDevices.keyAt(i);
                if (!containsDeviceId(deviceIdAndGeneration, deviceId)) {
                    if (DEBUG) {
                        Log.d(TAG, "Device removed: " + deviceId);
                    }
                    mInputDevices.removeAt(i);
                    sendMessageToInputDeviceListenersLocked(MSG_DEVICE_REMOVED, deviceId);
                }
            }

            for (int i = 0; i < deviceIdAndGeneration.length; i += 2) {
                final int deviceId = deviceIdAndGeneration[i];
                int index = mInputDevices.indexOfKey(deviceId);
                if (index >= 0) {
                    final InputDevice device = mInputDevices.valueAt(index);
                    if (device != null) {
                        final int generation = deviceIdAndGeneration[i + 1];
                        if (device.getGeneration() != generation) {
                            if (DEBUG) {
                                Log.d(TAG, "Device changed: " + deviceId);
                            }
                            mInputDevices.setValueAt(index, null);
                            sendMessageToInputDeviceListenersLocked(MSG_DEVICE_CHANGED, deviceId);
                        }
                    }
                } else {
                    if (DEBUG) {
                        Log.d(TAG, "Device added: " + deviceId);
                    }
                    mInputDevices.put(deviceId, null);
                    sendMessageToInputDeviceListenersLocked(MSG_DEVICE_ADDED, deviceId);
                }
            }
        }
    }

    private void sendMessageToInputDeviceListenersLocked(int what, int deviceId) {
        final int numListeners = mInputDeviceListeners.size();
        for (int i = 0; i < numListeners; i++) {
            InputDeviceListenerDelegate listener = mInputDeviceListeners.get(i);
            listener.sendMessage(listener.obtainMessage(what, deviceId, 0));
        }
    }

    private static boolean containsDeviceId(int[] deviceIdAndGeneration, int deviceId) {
        for (int i = 0; i < deviceIdAndGeneration.length; i += 2) {
            if (deviceIdAndGeneration[i] == deviceId) {
                return true;
            }
        }
        return false;
    }


    private void onTabletModeChanged(long whenNanos, boolean inTabletMode) {
        if (DEBUG) {
            Log.d(TAG, "Received tablet mode changed: "
                    + "whenNanos=" + whenNanos + ", inTabletMode=" + inTabletMode);
        }
        synchronized (mTabletModeLock) {
            final int N = mOnTabletModeChangedListeners.size();
            for (int i = 0; i < N; i++) {
                OnTabletModeChangedListenerDelegate listener =
                        mOnTabletModeChangedListeners.get(i);
                listener.sendTabletModeChanged(whenNanos, inTabletMode);
            }
        }
    }

    /**
     * Gets a vibrator service associated with an input device, always creates a new instance.
     * @return The vibrator, never null.
     * @hide
     */
    public Vibrator getInputDeviceVibrator(int deviceId, int vibratorId) {
        return new InputDeviceVibrator(this, deviceId, vibratorId);
    }

    /**
     * Gets a vibrator manager service associated with an input device, always creates a new
     * instance.
     * @return The vibrator manager, never null.
     * @hide
     */
    @NonNull
    public VibratorManager getInputDeviceVibratorManager(int deviceId) {
        return new InputDeviceVibratorManager(InputManager.this, deviceId);
    }

    /*
     * Get the list of device vibrators
     * @return The list of vibrators IDs
     */
    int[] getVibratorIds(int deviceId) {
        try {
            return mIm.getVibratorIds(deviceId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /*
     * Perform vibration effect
     */
    void vibrate(int deviceId, VibrationEffect effect, IBinder token) {
        try {
            mIm.vibrate(deviceId, effect, token);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /*
     * Perform combined vibration effect
     */
    void vibrate(int deviceId, CombinedVibration effect, IBinder token) {
        try {
            mIm.vibrateCombined(deviceId, effect, token);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /*
     * Cancel an ongoing vibration
     */
    void cancelVibrate(int deviceId, IBinder token) {
        try {
            mIm.cancelVibrate(deviceId, token);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /*
     * Check if input device is vibrating
     */
    boolean isVibrating(int deviceId)  {
        try {
            return mIm.isVibrating(deviceId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Register input device vibrator state listener
     */
    boolean registerVibratorStateListener(int deviceId, IVibratorStateListener listener) {
        try {
            return mIm.registerVibratorStateListener(deviceId, listener);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Unregister input device vibrator state listener
     */
    boolean unregisterVibratorStateListener(int deviceId, IVibratorStateListener listener) {
        try {
            return mIm.unregisterVibratorStateListener(deviceId, listener);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Gets a sensor manager service associated with an input device, always creates a new instance.
     * @return The sensor manager, never null.
     * @hide
     */
    @NonNull
    public SensorManager getInputDeviceSensorManager(int deviceId) {
        if (mInputDeviceSensorManager == null) {
            mInputDeviceSensorManager = new InputDeviceSensorManager(this);
        }
        return mInputDeviceSensorManager.getSensorManager(deviceId);
    }

    /**
     * Gets a battery state object associated with an input device, assuming it has one.
     * @return The battery, never null.
     * @hide
     */
    public InputDeviceBatteryState getInputDeviceBatteryState(int deviceId, boolean hasBattery) {
        return new InputDeviceBatteryState(this, deviceId, hasBattery);
    }

    /**
     * Gets a lights manager associated with an input device, always creates a new instance.
     * @return The lights manager, never null.
     * @hide
     */
    @NonNull
    public LightsManager getInputDeviceLightsManager(int deviceId) {
        return new InputDeviceLightsManager(InputManager.this, deviceId);
    }

    /**
     * Gets a list of light objects associated with an input device.
     * @return The list of lights, never null.
     */
    @NonNull List<Light> getLights(int deviceId) {
        try {
            return mIm.getLights(deviceId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the state of an input device light.
     * @return the light state
     */
    @NonNull LightState getLightState(int deviceId, @NonNull Light light) {
        try {
            return mIm.getLightState(deviceId, light.getId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request to modify the states of multiple lights.
     *
     * @param request the settings for lights that should change
     */
    void requestLights(int deviceId, @NonNull LightsRequest request, IBinder token) {
        try {
            List<Integer> lightIdList = request.getLights();
            int[] lightIds = new int[lightIdList.size()];
            for (int i = 0; i < lightIds.length; i++) {
                lightIds[i] = lightIdList.get(i);
            }
            List<LightState> lightStateList = request.getLightStates();
            mIm.setLightStates(deviceId, lightIds,
                    lightStateList.toArray(new LightState[lightStateList.size()]),
                    token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Open light session for input device manager
     *
     * @param token The token for the light session
     */
    void openLightSession(int deviceId, String opPkg, @NonNull IBinder token) {
        try {
            mIm.openLightSession(deviceId, opPkg, token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Close light session
     *
     */
    void closeLightSession(int deviceId, @NonNull IBinder token) {
        try {
            mIm.closeLightSession(deviceId, token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Cancel all ongoing pointer gestures on all displays.
     * @hide
     */
    public void cancelCurrentTouch() {
        try {
            mIm.cancelCurrentTouch();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Adds a battery listener to be notified about {@link BatteryState} changes for an input
     * device. The same listener can be registered for multiple input devices.
     * The listener will be notified of the initial battery state of the device after it is
     * successfully registered.
     * @param deviceId the input device that should be monitored
     * @param executor an executor on which the callback will be called
     * @param listener the {@link InputDeviceBatteryListener}
     * @see #removeInputDeviceBatteryListener(int, InputDeviceBatteryListener)
     * @hide
     */
    public void addInputDeviceBatteryListener(int deviceId, @NonNull Executor executor,
            @NonNull InputDeviceBatteryListener listener) {
        Objects.requireNonNull(executor, "executor should not be null");
        Objects.requireNonNull(listener, "listener should not be null");

        synchronized (mBatteryListenersLock) {
            if (mBatteryListeners == null) {
                mBatteryListeners = new SparseArray<>();
                mInputDeviceBatteryListener = new LocalInputDeviceBatteryListener();
            }
            RegisteredBatteryListeners listenersForDevice = mBatteryListeners.get(deviceId);
            if (listenersForDevice == null) {
                // The deviceId is currently not being monitored for battery changes.
                // Start monitoring the device.
                listenersForDevice = new RegisteredBatteryListeners();
                mBatteryListeners.put(deviceId, listenersForDevice);
                try {
                    mIm.registerBatteryListener(deviceId, mInputDeviceBatteryListener);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            } else {
                // The deviceId is already being monitored for battery changes.
                // Ensure that the listener is not already registered.
                for (InputDeviceBatteryListenerDelegate delegate : listenersForDevice.mDelegates) {
                    if (Objects.equals(listener, delegate.mListener)) {
                        throw new IllegalArgumentException(
                                "Attempting to register an InputDeviceBatteryListener that has "
                                        + "already been registered for deviceId: "
                                        + deviceId);
                    }
                }
            }
            final InputDeviceBatteryListenerDelegate delegate =
                    new InputDeviceBatteryListenerDelegate(listener, executor);
            listenersForDevice.mDelegates.add(delegate);

            // Notify the listener immediately if we already have the latest battery state.
            if (listenersForDevice.mLatestBatteryState != null) {
                delegate.notifyBatteryStateChanged(listenersForDevice.mLatestBatteryState);
            }
        }
    }

    /**
     * Pilfer pointers from an input channel.
     *
     * Takes all the current pointer event streams that are currently being sent to the given
     * input channel and generates appropriate cancellations for all other windows that are
     * receiving these pointers.
     *
     * This API is intended to be used in conjunction with spy windows. When a spy window pilfers
     * pointers, the foreground windows and all other spy windows that are receiving any of the
     * pointers that are currently being dispatched to the pilfering window will have those pointers
     * canceled. Only the pilfering window will continue to receive events for the affected pointers
     * until the pointer is lifted.
     *
     * This method should be used with caution as unexpected pilfering can break fundamental user
     * interactions.
     *
     * @see android.os.InputConfig#SPY
     * @hide
     */
    @RequiresPermission(Manifest.permission.MONITOR_INPUT)
    public void pilferPointers(IBinder inputChannelToken) {
        try {
            mIm.pilferPointers(inputChannelToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes a previously registered battery listener for an input device.
     * @see #addInputDeviceBatteryListener(int, Executor, InputDeviceBatteryListener)
     * @hide
     */
    public void removeInputDeviceBatteryListener(int deviceId,
            @NonNull InputDeviceBatteryListener listener) {
        Objects.requireNonNull(listener, "listener should not be null");

        synchronized (mBatteryListenersLock) {
            if (mBatteryListeners == null) {
                return;
            }
            RegisteredBatteryListeners listenersForDevice = mBatteryListeners.get(deviceId);
            if (listenersForDevice == null) {
                // The deviceId is not currently being monitored.
                return;
            }
            final List<InputDeviceBatteryListenerDelegate> delegates =
                    listenersForDevice.mDelegates;
            for (int i = 0; i < delegates.size();) {
                if (Objects.equals(listener, delegates.get(i).mListener)) {
                    delegates.remove(i);
                    continue;
                }
                i++;
            }
            if (!delegates.isEmpty()) {
                return;
            }

            // There are no more battery listeners for this deviceId. Stop monitoring this device.
            mBatteryListeners.remove(deviceId);
            try {
                mIm.unregisterBatteryListener(deviceId, mInputDeviceBatteryListener);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            if (mBatteryListeners.size() == 0) {
                // There are no more devices being monitored, so the registered
                // IInputDeviceBatteryListener will be automatically dropped by the server.
                mBatteryListeners = null;
                mInputDeviceBatteryListener = null;
            }
        }
    }

    /**
     * Whether stylus has ever been used on device (false by default).
     * @hide
     */
    public boolean isStylusEverUsed(@NonNull Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                        Settings.Global.STYLUS_EVER_USED, 0) == 1;
    }

    /**
     * Set whether stylus has ever been used on device.
     * Should only ever be set to true once after stylus first usage.
     * @hide
     */
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    public void setStylusEverUsed(@NonNull Context context, boolean stylusEverUsed) {
        Settings.Global.putInt(context.getContentResolver(),
                Settings.Global.STYLUS_EVER_USED, stylusEverUsed ? 1 : 0);
    }

    /**
     * A callback used to be notified about battery state changes for an input device. The
     * {@link #onBatteryStateChanged(int, long, BatteryState)} method will be called once after the
     * listener is successfully registered to provide the initial battery state of the device.
     * @see InputDevice#getBatteryState()
     * @see #addInputDeviceBatteryListener(int, Executor, InputDeviceBatteryListener)
     * @see #removeInputDeviceBatteryListener(int, InputDeviceBatteryListener)
     * @hide
     */
    public interface InputDeviceBatteryListener {
        /**
         * Called when the battery state of an input device changes.
         * @param deviceId the input device for which the battery changed.
         * @param eventTimeMillis the time (in ms) when the battery change took place.
         *        This timestamp is in the {@link SystemClock#uptimeMillis()} time base.
         * @param batteryState the new battery state, never null.
         */
        void onBatteryStateChanged(
                int deviceId, long eventTimeMillis, @NonNull BatteryState batteryState);
    }

    /**
     * Listens for changes in input devices.
     */
    public interface InputDeviceListener {
        /**
         * Called whenever an input device has been added to the system.
         * Use {@link InputManager#getInputDevice} to get more information about the device.
         *
         * @param deviceId The id of the input device that was added.
         */
        void onInputDeviceAdded(int deviceId);

        /**
         * Called whenever an input device has been removed from the system.
         *
         * @param deviceId The id of the input device that was removed.
         */
        void onInputDeviceRemoved(int deviceId);

        /**
         * Called whenever the properties of an input device have changed since they
         * were last queried.  Use {@link InputManager#getInputDevice} to get
         * a fresh {@link InputDevice} object with the new properties.
         *
         * @param deviceId The id of the input device that changed.
         */
        void onInputDeviceChanged(int deviceId);
    }

    private final class InputDevicesChangedListener extends IInputDevicesChangedListener.Stub {
        @Override
        public void onInputDevicesChanged(int[] deviceIdAndGeneration) throws RemoteException {
            InputManager.this.onInputDevicesChanged(deviceIdAndGeneration);
        }
    }

    private static final class InputDeviceListenerDelegate extends Handler {
        public final InputDeviceListener mListener;

        public InputDeviceListenerDelegate(InputDeviceListener listener, Handler handler) {
            super(handler != null ? handler.getLooper() : Looper.myLooper());
            mListener = listener;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DEVICE_ADDED:
                    mListener.onInputDeviceAdded(msg.arg1);
                    break;
                case MSG_DEVICE_REMOVED:
                    mListener.onInputDeviceRemoved(msg.arg1);
                    break;
                case MSG_DEVICE_CHANGED:
                    mListener.onInputDeviceChanged(msg.arg1);
                    break;
            }
        }
    }

    /** @hide */
    public interface OnTabletModeChangedListener {
        /**
         * Called whenever the device goes into or comes out of tablet mode.
         *
         * @param whenNanos The time at which the device transitioned into or
         * out of tablet mode. This is given in nanoseconds in the
         * {@link SystemClock#uptimeMillis} time base.
         */
        void onTabletModeChanged(long whenNanos, boolean inTabletMode);
    }

    private final class TabletModeChangedListener extends ITabletModeChangedListener.Stub {
        @Override
        public void onTabletModeChanged(long whenNanos, boolean inTabletMode) {
            InputManager.this.onTabletModeChanged(whenNanos, inTabletMode);
        }
    }

    private static final class OnTabletModeChangedListenerDelegate extends Handler {
        private static final int MSG_TABLET_MODE_CHANGED = 0;

        public final OnTabletModeChangedListener mListener;

        public OnTabletModeChangedListenerDelegate(
                OnTabletModeChangedListener listener, Handler handler) {
            super(handler != null ? handler.getLooper() : Looper.myLooper());
            mListener = listener;
        }

        public void sendTabletModeChanged(long whenNanos, boolean inTabletMode) {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = (int) (whenNanos & 0xFFFFFFFF);
            args.argi2 = (int) (whenNanos >> 32);
            args.arg1 = (Boolean) inTabletMode;
            obtainMessage(MSG_TABLET_MODE_CHANGED, args).sendToTarget();
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_TABLET_MODE_CHANGED:
                    SomeArgs args = (SomeArgs) msg.obj;
                    long whenNanos = (args.argi1 & 0xFFFFFFFFl) | ((long) args.argi2 << 32);
                    boolean inTabletMode = (boolean) args.arg1;
                    mListener.onTabletModeChanged(whenNanos, inTabletMode);
                    break;
            }
        }
    }

    private static final class LocalBatteryState extends BatteryState {
        final int mDeviceId;
        final boolean mIsPresent;
        final int mStatus;
        final float mCapacity;
        final long mEventTime;

        LocalBatteryState(int deviceId, boolean isPresent, int status, float capacity,
                long eventTime) {
            mDeviceId = deviceId;
            mIsPresent = isPresent;
            mStatus = status;
            mCapacity = capacity;
            mEventTime = eventTime;
        }

        @Override
        public boolean isPresent() {
            return mIsPresent;
        }

        @Override
        public int getStatus() {
            return mStatus;
        }

        @Override
        public float getCapacity() {
            return mCapacity;
        }
    }

    private static final class RegisteredBatteryListeners {
        final List<InputDeviceBatteryListenerDelegate> mDelegates = new ArrayList<>();
        LocalBatteryState mLatestBatteryState;
    }

    private static final class InputDeviceBatteryListenerDelegate {
        final InputDeviceBatteryListener mListener;
        final Executor mExecutor;

        InputDeviceBatteryListenerDelegate(InputDeviceBatteryListener listener, Executor executor) {
            mListener = listener;
            mExecutor = executor;
        }

        void notifyBatteryStateChanged(LocalBatteryState batteryState) {
            mExecutor.execute(() ->
                    mListener.onBatteryStateChanged(batteryState.mDeviceId, batteryState.mEventTime,
                            batteryState));
        }
    }

    private class LocalInputDeviceBatteryListener extends IInputDeviceBatteryListener.Stub {
        @Override
        public void onBatteryStateChanged(int deviceId, boolean isBatteryPresent, int status,
                float capacity, long eventTime) {
            synchronized (mBatteryListenersLock) {
                if (mBatteryListeners == null) return;
                final RegisteredBatteryListeners entry = mBatteryListeners.get(deviceId);
                if (entry == null) return;

                entry.mLatestBatteryState =
                        new LocalBatteryState(
                                deviceId, isBatteryPresent, status, capacity, eventTime);
                for (InputDeviceBatteryListenerDelegate delegate : entry.mDelegates) {
                    delegate.notifyBatteryStateChanged(entry.mLatestBatteryState);
                }
            }
        }
    }
}
