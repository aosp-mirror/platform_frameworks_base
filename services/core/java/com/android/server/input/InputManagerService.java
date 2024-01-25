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

package com.android.server.input;

import static android.provider.DeviceConfig.NAMESPACE_INPUT_NATIVE_BOOT;
import static android.view.KeyEvent.KEYCODE_UNKNOWN;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import android.Manifest;
import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.hardware.SensorPrivacyManager;
import android.hardware.SensorPrivacyManager.Sensors;
import android.hardware.SensorPrivacyManagerInternal;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayViewport;
import android.hardware.input.HostUsiVersion;
import android.hardware.input.IInputDeviceBatteryListener;
import android.hardware.input.IInputDeviceBatteryState;
import android.hardware.input.IInputDevicesChangedListener;
import android.hardware.input.IInputManager;
import android.hardware.input.IInputSensorEventListener;
import android.hardware.input.IKeyboardBacklightListener;
import android.hardware.input.IStickyModifierStateListener;
import android.hardware.input.ITabletModeChangedListener;
import android.hardware.input.InputDeviceIdentifier;
import android.hardware.input.InputManager;
import android.hardware.input.InputSensorInfo;
import android.hardware.input.InputSettings;
import android.hardware.input.KeyboardLayout;
import android.hardware.input.TouchCalibration;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.media.AudioManager;
import android.os.Binder;
import android.os.CombinedVibration;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.IVibratorStateListener;
import android.os.InputEventInjectionResult;
import android.os.InputEventInjectionSync;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.IInputFilter;
import android.view.IInputFilterHost;
import android.view.IInputMonitorHost;
import android.view.InputApplicationHandle;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputMonitor;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.VerifiedInputEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.inputmethod.InputMethodSubtypeHandle;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.DisplayThread;
import com.android.server.LocalServices;
import com.android.server.Watchdog;
import com.android.server.input.InputManagerInternal.LidSwitchCallback;
import com.android.server.input.debug.FocusEventDebugView;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.policy.WindowManagerPolicy;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;

/** The system implementation of {@link IInputManager} that manages input devices. */
public class InputManagerService extends IInputManager.Stub
        implements Watchdog.Monitor {
    static final String TAG = "InputManager";
    // To enable these logs, run: 'adb shell setprop log.tag.InputManager DEBUG' (requires restart)
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String EXCLUDED_DEVICES_PATH = "etc/excluded-input-devices.xml";
    private static final String PORT_ASSOCIATIONS_PATH = "etc/input-port-associations.xml";

    // Feature flag name for the strategy to be used in VelocityTracker
    private static final String VELOCITYTRACKER_STRATEGY_PROPERTY = "velocitytracker_strategy";

    private static final int MSG_DELIVER_INPUT_DEVICES_CHANGED = 1;
    private static final int MSG_RELOAD_DEVICE_ALIASES = 2;
    private static final int MSG_DELIVER_TABLET_MODE_CHANGED = 3;
    private static final int MSG_POINTER_DISPLAY_ID_CHANGED = 4;

    private static final int DEFAULT_VIBRATION_MAGNITUDE = 192;
    private static final AdditionalDisplayInputProperties
            DEFAULT_ADDITIONAL_DISPLAY_INPUT_PROPERTIES = new AdditionalDisplayInputProperties();

    private final NativeInputManagerService mNative;

    private final Context mContext;
    private final InputManagerHandler mHandler;
    private DisplayManagerInternal mDisplayManagerInternal;

    private InputMethodManagerInternal mInputMethodManagerInternal;

    // Context cache used for loading pointer resources.
    private Context mPointerIconDisplayContext;

    private final File mDoubleTouchGestureEnableFile;

    private WindowManagerCallbacks mWindowManagerCallbacks;
    private WiredAccessoryCallbacks mWiredAccessoryCallbacks;
    private boolean mSystemReady;

    private final Object mTabletModeLock = new Object();
    // List of currently registered tablet mode changed listeners by process id
    @GuardedBy("mTabletModeLock")
    private final SparseArray<TabletModeChangedListenerRecord> mTabletModeChangedListeners =
            new SparseArray<>();
    private final List<TabletModeChangedListenerRecord> mTempTabletModeChangedListenersToNotify =
            new ArrayList<>();

    private final Object mSensorEventLock = new Object();
    // List of currently registered sensor event listeners by process id
    @GuardedBy("mSensorEventLock")
    private final SparseArray<SensorEventListenerRecord> mSensorEventListeners =
            new SparseArray<>();
    private final List<SensorEventListenerRecord> mSensorEventListenersToNotify =
            new ArrayList<>();
    private final List<SensorEventListenerRecord> mSensorAccuracyListenersToNotify =
            new ArrayList<>();

    // Persistent data store.  Must be locked each time during use.
    private final PersistentDataStore mDataStore = new PersistentDataStore();

    // List of currently registered input devices changed listeners by process id.
    private final Object mInputDevicesLock = new Object();
    @GuardedBy("mInputDevicesLock")
    private boolean mInputDevicesChangedPending;
    @GuardedBy("mInputDevicesLock")
    private InputDevice[] mInputDevices = new InputDevice[0];
    @GuardedBy("mInputDevicesLock")
    private final SparseArray<InputDevicesChangedListenerRecord> mInputDevicesChangedListeners =
            new SparseArray<>();
    private final ArrayList<InputDevicesChangedListenerRecord>
            mTempInputDevicesChangedListenersToNotify = new ArrayList<>(); // handler thread only

    // State for vibrator tokens.
    private final Object mVibratorLock = new Object();
    private final Map<IBinder, VibratorToken> mVibratorTokens = new ArrayMap<>();
    private int mNextVibratorTokenValue;

    // List of currently registered vibrator state changed listeners by device id.
    @GuardedBy("mVibratorLock")
    private final SparseArray<RemoteCallbackList<IVibratorStateListener>> mVibratorStateListeners =
            new SparseArray<>();
    // List of vibrator states by device id.
    @GuardedBy("mVibratorLock")
    private final SparseBooleanArray mIsVibrating = new SparseBooleanArray();
    private final Object mLightLock = new Object();
    // State for light tokens. A light token marks a lights manager session, it is generated
    // by light session open() and deleted in session close().
    // When lights session requests light states, the token will be used to find the light session.
    @GuardedBy("mLightLock")
    private final ArrayMap<IBinder, LightSession> mLightSessions = new ArrayMap<>();

    // State for lid switch
    // Lock for the lid switch state. Held when triggering callbacks to guarantee lid switch events
    // are delivered in order. For ex, when a new lid switch callback is registered the lock is held
    // while the callback is processing the initial lid switch event which guarantees that any
    // events that occur at the same time are delivered after the callback has returned.
    private final Object mLidSwitchLock = new Object();
    @GuardedBy("mLidSwitchLock")
    private final List<LidSwitchCallback> mLidSwitchCallbacks = new ArrayList<>();

    // State for the currently installed input filter.
    final Object mInputFilterLock = new Object();
    @GuardedBy("mInputFilterLock")
    IInputFilter mInputFilter;
    @GuardedBy("mInputFilterLock")
    InputFilterHost mInputFilterHost;

    // The associations of input devices to displays by port. Maps from input device port (String)
    // to display id (int). Currently only accessed by InputReader.
    private final Map<String, Integer> mStaticAssociations;
    private final Object mAssociationsLock = new Object();
    @GuardedBy("mAssociationsLock")
    private final Map<String, Integer> mRuntimeAssociations = new ArrayMap<>();
    @GuardedBy("mAssociationsLock")
    private final Map<String, String> mUniqueIdAssociations = new ArrayMap<>();
    // The map from input port (String) to the keyboard layout identifiers (comma separated string
    // containing language tag and layout type) associated with the corresponding keyboard device.
    // Currently only accessed by InputReader.
    @GuardedBy("mAssociationsLock")
    private final Map<String, String> mKeyboardLayoutAssociations = new ArrayMap<>();

    // Stores input ports associated with device types. For example, adding an association
    // {"123", "touchNavigation"} here would mean that a touch device appearing at port "123" would
    // enumerate as a "touch navigation" device rather than the default "touchpad as a mouse
    // pointer" device.
    @GuardedBy("mAssociationsLock")
    private final Map<String, String> mDeviceTypeAssociations = new ArrayMap<>();

    // Guards per-display input properties and properties relating to the mouse pointer.
    // Threads can wait on this lock to be notified the next time the display on which the mouse
    // pointer is shown has changed.
    // WARNING: Do not call other services outside of input while holding this lock.
    private final Object mAdditionalDisplayInputPropertiesLock = new Object();

    // Forces the PointerController to target a specific display id.
    @GuardedBy("mAdditionalDisplayInputPropertiesLock")
    private int mOverriddenPointerDisplayId = Display.INVALID_DISPLAY;

    // PointerController is the source of truth of the pointer display. This is the value of the
    // latest pointer display id reported by PointerController.
    @GuardedBy("mAdditionalDisplayInputPropertiesLock")
    private int mAcknowledgedPointerDisplayId = Display.INVALID_DISPLAY;
    // This is the latest display id that IMS has requested PointerController to use. If there are
    // no devices that can control the pointer, PointerController may end up disregarding this
    // value.
    @GuardedBy("mAdditionalDisplayInputPropertiesLock")
    private int mRequestedPointerDisplayId = Display.INVALID_DISPLAY;
    @GuardedBy("mAdditionalDisplayInputPropertiesLock")
    private final SparseArray<AdditionalDisplayInputProperties> mAdditionalDisplayInputProperties =
            new SparseArray<>();
    // This contains the per-display properties that are currently applied by native code. It should
    // be kept in sync with the properties for mRequestedPointerDisplayId.
    @GuardedBy("mAdditionalDisplayInputPropertiesLock")
    private final AdditionalDisplayInputProperties mCurrentDisplayProperties =
            new AdditionalDisplayInputProperties();
    // TODO(b/293587049): Pointer Icon Refactor: There can be more than one pointer icon
    // visible at once. Update this to support multi-pointer use cases.
    @GuardedBy("mAdditionalDisplayInputPropertiesLock")
    private int mPointerIconType = PointerIcon.TYPE_NOT_SPECIFIED;
    @GuardedBy("mAdditionalDisplayInputPropertiesLock")
    private PointerIcon mPointerIcon;

    // Holds all the registered gesture monitors that are implemented as spy windows. The spy
    // windows are mapped by their InputChannel tokens.
    @GuardedBy("mInputMonitors")
    final Map<IBinder, GestureMonitorSpyWindow> mInputMonitors = new HashMap<>();

    // Watches for settings changes and updates the native side appropriately.
    private final InputSettingsObserver mSettingsObserver;

    // Manages Keyboard layouts for Physical keyboards
    private final KeyboardLayoutManager mKeyboardLayoutManager;

    // Manages battery state for input devices.
    private final BatteryController mBatteryController;

    // Manages Keyboard backlight
    private final KeyboardBacklightControllerInterface mKeyboardBacklightController;

    // Manages Sticky modifier state
    private final StickyModifierStateController mStickyModifierStateController;

    // Manages Keyboard modifier keys remapping
    private final KeyRemapper mKeyRemapper;

    // Maximum number of milliseconds to wait for input event injection.
    private static final int INJECTION_TIMEOUT_MILLIS = 30 * 1000;

    // Key states (may be returned by queries about the current state of a
    // particular key code, scan code or switch).

    /** The key state is unknown or the requested key itself is not supported. */
    public static final int KEY_STATE_UNKNOWN = -1;

    /** The key is up. /*/
    public static final int KEY_STATE_UP = 0;

    /** The key is down. */
    public static final int KEY_STATE_DOWN = 1;

    /** The key is down but is a virtual key press that is being emulated by the system. */
    public static final int KEY_STATE_VIRTUAL = 2;

    /** Scan code: Mouse / trackball button. */
    public static final int BTN_MOUSE = 0x110;

    // Switch code values must match bionic/libc/kernel/common/linux/input.h
    /** Switch code: Lid switch.  When set, lid is shut. */
    public static final int SW_LID = 0x00;

    /** Switch code: Tablet mode switch.
     * When set, the device is in tablet mode (i.e. no keyboard is connected).
     */
    public static final int SW_TABLET_MODE = 0x01;

    /** Switch code: Keypad slide.  When set, keyboard is exposed. */
    public static final int SW_KEYPAD_SLIDE = 0x0a;

    /** Switch code: Headphone.  When set, headphone is inserted. */
    public static final int SW_HEADPHONE_INSERT = 0x02;

    /** Switch code: Microphone.  When set, microphone is inserted. */
    public static final int SW_MICROPHONE_INSERT = 0x04;

    /** Switch code: Line out.  When set, Line out (hi-Z) is inserted. */
    public static final int SW_LINEOUT_INSERT = 0x06;

    /** Switch code: Headphone/Microphone Jack.  When set, something is inserted. */
    public static final int SW_JACK_PHYSICAL_INSERT = 0x07;

    /** Switch code: Camera lens cover. When set the lens is covered. */
    public static final int SW_CAMERA_LENS_COVER = 0x09;

    /** Switch code: Microphone. When set, the mic is muted. */
    public static final int SW_MUTE_DEVICE = 0x0e;

    public static final int SW_LID_BIT = 1 << SW_LID;
    public static final int SW_TABLET_MODE_BIT = 1 << SW_TABLET_MODE;
    public static final int SW_KEYPAD_SLIDE_BIT = 1 << SW_KEYPAD_SLIDE;
    public static final int SW_HEADPHONE_INSERT_BIT = 1 << SW_HEADPHONE_INSERT;
    public static final int SW_MICROPHONE_INSERT_BIT = 1 << SW_MICROPHONE_INSERT;
    public static final int SW_LINEOUT_INSERT_BIT = 1 << SW_LINEOUT_INSERT;
    public static final int SW_JACK_PHYSICAL_INSERT_BIT = 1 << SW_JACK_PHYSICAL_INSERT;
    public static final int SW_JACK_BITS =
            SW_HEADPHONE_INSERT_BIT | SW_MICROPHONE_INSERT_BIT | SW_JACK_PHYSICAL_INSERT_BIT | SW_LINEOUT_INSERT_BIT;
    public static final int SW_CAMERA_LENS_COVER_BIT = 1 << SW_CAMERA_LENS_COVER;
    public static final int SW_MUTE_DEVICE_BIT = 1 << SW_MUTE_DEVICE;

    // The following are layer numbers used for z-ordering the input overlay layers on the display.
    // This is used for ordering layers inside {@code DisplayContent#getInputOverlayLayer()}.
    //
    // The layer where gesture monitors are added.
    public static final int INPUT_OVERLAY_LAYER_GESTURE_MONITOR = 1;
    // Place the handwriting layer above gesture monitors so that styluses cannot trigger
    // system gestures (e.g. navigation bar, edge-back, etc) while there is an active
    // handwriting session.
    public static final int INPUT_OVERLAY_LAYER_HANDWRITING_SURFACE = 2;


    private final String mVelocityTrackerStrategy;

    /** Whether to use the dev/input/event or uevent subsystem for the audio jack. */
    final boolean mUseDevInputEventForAudioJack;

    private final Object mFocusEventDebugViewLock = new Object();
    @GuardedBy("mFocusEventDebugViewLock")
    @Nullable
    private FocusEventDebugView mFocusEventDebugView;
    private boolean mShowKeyPresses = false;
    private boolean mShowRotaryInput = false;

    @GuardedBy("mLoadedPointerIconsByDisplayAndType")
    final SparseArray<SparseArray<PointerIcon>> mLoadedPointerIconsByDisplayAndType =
            new SparseArray<>();
    @GuardedBy("mLoadedPointerIconsByDisplayAndType")
    boolean mUseLargePointerIcons = false;

    final DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {

        }

        @Override
        public void onDisplayRemoved(int displayId) {
            synchronized (mLoadedPointerIconsByDisplayAndType) {
                mLoadedPointerIconsByDisplayAndType.remove(displayId);
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
            synchronized (mLoadedPointerIconsByDisplayAndType) {
                // The display density could have changed, so force all cached pointer icons to be
                // reloaded for the display.
                var iconsByType = mLoadedPointerIconsByDisplayAndType.get(displayId);
                if (iconsByType == null) {
                    return;
                }
                iconsByType.clear();
            }
            mNative.reloadPointerIcons();
        }
    };

    /** Point of injection for test dependencies. */
    @VisibleForTesting
    static class Injector {
        private final Context mContext;
        private final Looper mLooper;
        private final UEventManager mUEventManager;

        Injector(Context context, Looper looper, UEventManager uEventManager) {
            mContext = context;
            mLooper = looper;
            mUEventManager = uEventManager;
        }

        Context getContext() {
            return mContext;
        }

        Looper getLooper() {
            return mLooper;
        }

        UEventManager getUEventManager() {
            return mUEventManager;
        }

        NativeInputManagerService getNativeService(InputManagerService service) {
            return new NativeInputManagerService.NativeImpl(service, mLooper.getQueue());
        }

        void registerLocalService(InputManagerInternal localService) {
            LocalServices.addService(InputManagerInternal.class, localService);
        }
    }

    public InputManagerService(Context context) {
        this(new Injector(context, DisplayThread.get().getLooper(), new UEventManager() {}));
    }

    @VisibleForTesting
    InputManagerService(Injector injector) {
        // The static association map is accessed by both java and native code, so it must be
        // initialized before initializing the native service.
        mStaticAssociations = loadStaticInputPortAssociations();

        mContext = injector.getContext();
        mHandler = new InputManagerHandler(injector.getLooper());
        mNative = injector.getNativeService(this);
        mSettingsObserver = new InputSettingsObserver(mContext, mHandler, this, mNative);
        mKeyboardLayoutManager = new KeyboardLayoutManager(mContext, mNative, mDataStore,
                injector.getLooper());
        mBatteryController = new BatteryController(mContext, mNative, injector.getLooper(),
                injector.getUEventManager());
        mKeyboardBacklightController = InputFeatureFlagProvider.isKeyboardBacklightControlEnabled()
                ? new KeyboardBacklightController(mContext, mNative, mDataStore,
                        injector.getLooper(), injector.getUEventManager())
                : new KeyboardBacklightControllerInterface() {};
        mStickyModifierStateController = new StickyModifierStateController();
        mKeyRemapper = new KeyRemapper(mContext, mNative, mDataStore, injector.getLooper());

        mUseDevInputEventForAudioJack =
                mContext.getResources().getBoolean(R.bool.config_useDevInputEventForAudioJack);
        Slog.i(TAG, "Initializing input manager, mUseDevInputEventForAudioJack="
                + mUseDevInputEventForAudioJack);

        String doubleTouchGestureEnablePath = mContext.getResources().getString(
                R.string.config_doubleTouchGestureEnableFile);
        mDoubleTouchGestureEnableFile = TextUtils.isEmpty(doubleTouchGestureEnablePath) ? null :
            new File(doubleTouchGestureEnablePath);

        mVelocityTrackerStrategy = DeviceConfig.getProperty(
                NAMESPACE_INPUT_NATIVE_BOOT, VELOCITYTRACKER_STRATEGY_PROPERTY);

        injector.registerLocalService(new LocalService());
    }

    public void setWindowManagerCallbacks(WindowManagerCallbacks callbacks) {
        if (mWindowManagerCallbacks != null) {
            unregisterLidSwitchCallbackInternal(mWindowManagerCallbacks);
        }
        mWindowManagerCallbacks = callbacks;
        registerLidSwitchCallbackInternal(mWindowManagerCallbacks);
    }

    public void setWiredAccessoryCallbacks(WiredAccessoryCallbacks callbacks) {
        mWiredAccessoryCallbacks = callbacks;
    }

    void registerLidSwitchCallbackInternal(@NonNull LidSwitchCallback callback) {
        synchronized (mLidSwitchLock) {
            mLidSwitchCallbacks.add(callback);

            // Skip triggering the initial callback if the system is not yet ready as the switch
            // state will be reported as KEY_STATE_UNKNOWN. The callback will be triggered in
            // systemRunning().
            if (mSystemReady) {
                boolean lidOpen = getSwitchState(-1 /* deviceId */, InputDevice.SOURCE_ANY, SW_LID)
                        == KEY_STATE_UP;
                callback.notifyLidSwitchChanged(0 /* whenNanos */, lidOpen);
            }
        }
    }

    void unregisterLidSwitchCallbackInternal(@NonNull LidSwitchCallback callback) {
        synchronized (mLidSwitchLock) {
            mLidSwitchCallbacks.remove(callback);
        }
    }

    public void start() {
        Slog.i(TAG, "Starting input manager");
        mNative.start();

        // Add ourselves to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);
    }

    // TODO(BT) Pass in parameter for bluetooth system
    public void systemRunning() {
        if (DEBUG) {
            Slog.d(TAG, "System ready.");
        }

        mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
        mInputMethodManagerInternal =
                LocalServices.getService(InputMethodManagerInternal.class);

        mSettingsObserver.registerAndUpdate();

        synchronized (mLidSwitchLock) {
            mSystemReady = true;

            // Send the initial lid switch state to any callback registered before the system was
            // ready.
            int switchState = getSwitchState(-1 /* deviceId */, InputDevice.SOURCE_ANY, SW_LID);
            for (int i = 0; i < mLidSwitchCallbacks.size(); i++) {
                LidSwitchCallback callback = mLidSwitchCallbacks.get(i);
                callback.notifyLidSwitchChanged(0 /* whenNanos */, switchState == KEY_STATE_UP);
            }
        }

        // Set the HW mic toggle switch state
        final int micMuteState = getSwitchState(-1 /* deviceId */, InputDevice.SOURCE_ANY,
                SW_MUTE_DEVICE);
        if (micMuteState == InputManager.SWITCH_STATE_ON) {
            setSensorPrivacy(Sensors.MICROPHONE, true);
        }
        // Set the HW camera toggle switch state
        final int cameraMuteState = getSwitchState(-1 /* deviceId */, InputDevice.SOURCE_ANY,
                SW_CAMERA_LENS_COVER);
        if (cameraMuteState == InputManager.SWITCH_STATE_ON) {
            setSensorPrivacy(Sensors.CAMERA, true);
        }

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_ALIAS_CHANGED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                reloadDeviceAliases();
            }
        }, filter, null, mHandler);

        mHandler.sendEmptyMessage(MSG_RELOAD_DEVICE_ALIASES);

        if (mWiredAccessoryCallbacks != null) {
            mWiredAccessoryCallbacks.systemReady();
        }

        Objects.requireNonNull(
                mContext.getSystemService(DisplayManager.class)).registerDisplayListener(
                mDisplayListener, mHandler);

        mKeyboardLayoutManager.systemRunning();
        mBatteryController.systemRunning();
        mKeyboardBacklightController.systemRunning();
        mKeyRemapper.systemRunning();

        mNative.setStylusPointerIconEnabled(
                Objects.requireNonNull(mContext.getSystemService(InputManager.class))
                        .isStylusPointerIconEnabled());
    }

    private void reloadDeviceAliases() {
        if (DEBUG) {
            Slog.d(TAG, "Reloading device names.");
        }
        mNative.reloadDeviceAliases();
    }

    private void setDisplayViewportsInternal(List<DisplayViewport> viewports) {
        final DisplayViewport[] vArray = new DisplayViewport[viewports.size()];
        for (int i = viewports.size() - 1; i >= 0; --i) {
            vArray[i] = viewports.get(i);
        }
        mNative.setDisplayViewports(vArray);

        // Attempt to update the pointer display when viewports change when there is no override.
        // Take care to not make calls to window manager while holding internal locks.
        final int pointerDisplayId = mWindowManagerCallbacks.getPointerDisplayId();
        synchronized (mAdditionalDisplayInputPropertiesLock) {
            if (mOverriddenPointerDisplayId == Display.INVALID_DISPLAY) {
                updatePointerDisplayIdLocked(pointerDisplayId);
            }
        }
    }

    /**
     * Gets the current state of a key or button by key code.
     * @param deviceId The input device id, or -1 to consult all devices.
     * @param sourceMask The input sources to consult, or {@link InputDevice#SOURCE_ANY} to
     * consider all input sources.  An input device is consulted if at least one of its
     * non-class input source bits matches the specified source mask.
     * @param keyCode The key code to check.
     * @return The key state.
     */
    public int getKeyCodeState(int deviceId, int sourceMask, int keyCode) {
        return mNative.getKeyCodeState(deviceId, sourceMask, keyCode);
    }

    /**
     * Gets the current state of a key or button by scan code.
     * @param deviceId The input device id, or -1 to consult all devices.
     * @param sourceMask The input sources to consult, or {@link InputDevice#SOURCE_ANY} to
     * consider all input sources.  An input device is consulted if at least one of its
     * non-class input source bits matches the specified source mask.
     * @param scanCode The scan code to check.
     * @return The key state.
     */
    public int getScanCodeState(int deviceId, int sourceMask, int scanCode) {
        return mNative.getScanCodeState(deviceId, sourceMask, scanCode);
    }

    /**
     * Gets the current state of a switch by switch code.
     * @param deviceId The input device id, or -1 to consult all devices.
     * @param sourceMask The input sources to consult, or {@link InputDevice#SOURCE_ANY} to
     * consider all input sources.  An input device is consulted if at least one of its
     * non-class input source bits matches the specified source mask.
     * @param switchCode The switch code to check.
     * @return The switch state.
     */
    public int getSwitchState(int deviceId, int sourceMask, int switchCode) {
        return mNative.getSwitchState(deviceId, sourceMask, switchCode);
    }

    /**
     * Determines whether the specified key codes are supported by a particular device.
     * @param deviceId The input device id, or -1 to consult all devices.
     * @param sourceMask The input sources to consult, or {@link InputDevice#SOURCE_ANY} to
     * consider all input sources.  An input device is consulted if at least one of its
     * non-class input source bits matches the specified source mask.
     * @param keyCodes The array of key codes to check.
     * @param keyExists An array at least as large as keyCodes whose entries will be set
     * to true or false based on the presence or absence of support for the corresponding
     * key codes.
     * @return True if the lookup was successful, false otherwise.
     */
    @Override // Binder call
    public boolean hasKeys(int deviceId, int sourceMask, int[] keyCodes, boolean[] keyExists) {
        Objects.requireNonNull(keyCodes, "keyCodes must not be null");
        Objects.requireNonNull(keyExists, "keyExists must not be null");
        if (keyExists.length < keyCodes.length) {
            throw new IllegalArgumentException("keyExists must be at least as large as keyCodes");
        }

        return mNative.hasKeys(deviceId, sourceMask, keyCodes, keyExists);
    }

    /**
     * Returns the keyCode generated by the specified location on a US keyboard layout.
     * This takes into consideration the currently active keyboard layout.
     *
     * @param deviceId The input device id.
     * @param locationKeyCode The location of a key on a US keyboard layout.
     * @return The KeyCode this physical key location produces.
     */
    @Override // Binder call
    public int getKeyCodeForKeyLocation(int deviceId, int locationKeyCode) {
        if (locationKeyCode <= KEYCODE_UNKNOWN || locationKeyCode > KeyEvent.getMaxKeyCode()) {
            return KEYCODE_UNKNOWN;
        }
        return mNative.getKeyCodeForKeyLocation(deviceId, locationKeyCode);
    }

    @Override // Binder call
    public KeyCharacterMap getKeyCharacterMap(@NonNull String layoutDescriptor) {
        Objects.requireNonNull(layoutDescriptor, "layoutDescriptor must not be null");
        return mKeyboardLayoutManager.getKeyCharacterMap(layoutDescriptor);
    }

    /**
     * Transfer the current touch gesture to the provided window.
     *
     * @param destChannelToken The token of the window or input channel that should receive the
     * gesture
     * @return True if the transfer succeeded, false if there was no active touch gesture happening
     */
    public boolean transferTouch(IBinder destChannelToken, int displayId) {
        // TODO(b/162194035): Replace this with a SPY window
        Objects.requireNonNull(destChannelToken, "destChannelToken must not be null");
        return mNative.transferTouch(destChannelToken, displayId);
    }

    /**
     * Creates an input channel that will receive all input from the input dispatcher.
     * @param inputChannelName The input channel name.
     * @param displayId Target display id.
     * @return The input channel.
     */
    public InputChannel monitorInput(String inputChannelName, int displayId) {
        Objects.requireNonNull(inputChannelName, "inputChannelName not be null");

        if (displayId < Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException("displayId must >= 0.");
        }

        return mNative.createInputMonitor(displayId, inputChannelName, Binder.getCallingPid());
    }

    @NonNull
    private InputChannel createSpyWindowGestureMonitor(IBinder monitorToken, String name,
            SurfaceControl sc, int displayId, int pid, int uid) {
        final InputChannel channel = createInputChannel(name);

        try {
            monitorToken.linkToDeath(() -> removeSpyWindowGestureMonitor(channel.getToken()), 0);
        } catch (RemoteException e) {
            Slog.i(TAG, "Client died before '" + name + "' could be created.");
            return null;
        }
        synchronized (mInputMonitors) {
            mInputMonitors.put(channel.getToken(),
                    new GestureMonitorSpyWindow(monitorToken, name, displayId, pid, uid, sc,
                            channel));
        }

        final InputChannel outInputChannel = new InputChannel();
        channel.copyTo(outInputChannel);
        return outInputChannel;
    }

    private void removeSpyWindowGestureMonitor(IBinder inputChannelToken) {
        final GestureMonitorSpyWindow monitor;
        synchronized (mInputMonitors) {
            monitor = mInputMonitors.remove(inputChannelToken);
        }
        removeInputChannel(inputChannelToken);
        if (monitor == null) return;
        monitor.remove();
    }

    /**
     * Creates an input monitor that will receive pointer events for the purposes of system-wide
     * gesture interpretation.
     *
     * @param requestedName The input channel name.
     * @param displayId Target display id.
     * @return The input channel.
     */
    @Override // Binder call
    public InputMonitor monitorGestureInput(IBinder monitorToken, @NonNull String requestedName,
            int displayId) {
        if (!checkCallingPermission(android.Manifest.permission.MONITOR_INPUT,
                "monitorGestureInput()")) {
            throw new SecurityException("Requires MONITOR_INPUT permission");
        }
        Objects.requireNonNull(requestedName, "name must not be null.");
        Objects.requireNonNull(monitorToken, "token must not be null.");

        if (displayId < Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException("displayId must >= 0.");
        }
        final String name = "[Gesture Monitor] " + requestedName;
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();

        final long ident = Binder.clearCallingIdentity();
        try {
            final SurfaceControl sc = mWindowManagerCallbacks.createSurfaceForGestureMonitor(name,
                    displayId);
            if (sc == null) {
                throw new IllegalArgumentException(
                        "Could not create gesture monitor surface on display: " + displayId);
            }

            final InputChannel inputChannel = createSpyWindowGestureMonitor(
                    monitorToken, name, sc, displayId, pid, uid);
            return new InputMonitor(inputChannel,
                new InputMonitorHost(inputChannel.getToken()),
                new SurfaceControl(sc, "IMS.monitorGestureInput"));
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Creates an input channel to be used as an input event target.
     *
     * @param name The name of this input channel
     */
    public InputChannel createInputChannel(String name) {
        return mNative.createInputChannel(name);
    }

    /**
     * Removes an input channel.
     * @param connectionToken The input channel to unregister.
     */
    public void removeInputChannel(IBinder connectionToken) {
        Objects.requireNonNull(connectionToken, "connectionToken must not be null");
        mNative.removeInputChannel(connectionToken);
    }

    /**
     * Sets an input filter that will receive all input events before they are dispatched.
     * The input filter may then reinterpret input events or inject new ones.
     *
     * To ensure consistency, the input dispatcher automatically drops all events
     * in progress whenever an input filter is installed or uninstalled.  After an input
     * filter is uninstalled, it can no longer send input events unless it is reinstalled.
     * Any events it attempts to send after it has been uninstalled will be dropped.
     *
     * @param filter The input filter, or null to remove the current filter.
     */
    public void setInputFilter(IInputFilter filter) {
        synchronized (mInputFilterLock) {
            final IInputFilter oldFilter = mInputFilter;
            if (oldFilter == filter) {
                return; // nothing to do
            }

            if (oldFilter != null) {
                mInputFilter = null;
                mInputFilterHost.disconnectLocked();
                mInputFilterHost = null;
                try {
                    oldFilter.uninstall();
                } catch (RemoteException re) {
                    /* ignore */
                }
            }

            if (filter != null) {
                mInputFilter = filter;
                mInputFilterHost = new InputFilterHost();
                try {
                    filter.install(mInputFilterHost);
                } catch (RemoteException re) {
                    /* ignore */
                }
            }

            mNative.setInputFilterEnabled(filter != null);
        }
    }

    /**
     * Set the state of the touch mode.
     *
     * WindowManager remains the source of truth of the touch mode state.
     * However, we need to keep a copy of this state in input.
     *
     * The apps determine the touch mode state. Therefore, a single app will
     * affect the global state. That state change needs to be propagated to
     * other apps, when they become focused.
     *
     * When input dispatches focus to the apps, the touch mode state
     * will be sent together with the focus change (but each one in its own event).
     *
     * @param inTouchMode   true if the device is in touch mode
     * @param pid           the pid of the process that requested to switch touch mode state
     * @param uid           the uid of the process that requested to switch touch mode state
     * @param hasPermission if set to {@code true} then no further authorization will be performed
     * @param displayId     the target display (ignored if device is configured with per display
     *                      touch mode enabled)
     * @return {@code true} if the touch mode was successfully changed, {@code false} otherwise
     */
    public boolean setInTouchMode(boolean inTouchMode, int pid, int uid, boolean hasPermission,
            int displayId) {
        return mNative.setInTouchMode(inTouchMode, pid, uid, hasPermission, displayId);
    }

    @Override // Binder call
    public boolean injectInputEvent(InputEvent event, int mode) {
        return injectInputEventToTarget(event, mode, Process.INVALID_UID);
    }

    @Override // Binder call
    public boolean injectInputEventToTarget(InputEvent event, int mode, int targetUid) {
        if (!checkCallingPermission(android.Manifest.permission.INJECT_EVENTS,
                "injectInputEvent()", true /*checkInstrumentationSource*/)) {
            throw new SecurityException(
                    "Injecting input events requires the caller (or the source of the "
                            + "instrumentation, if any) to have the INJECT_EVENTS permission.");
        }
        // We are not checking if targetUid matches the callingUid, since having the permission
        // already means you can inject into any window.
        Objects.requireNonNull(event, "event must not be null");
        if (mode != InputEventInjectionSync.NONE
                && mode != InputEventInjectionSync.WAIT_FOR_FINISHED
                && mode != InputEventInjectionSync.WAIT_FOR_RESULT) {
            throw new IllegalArgumentException("mode is invalid");
        }

        final int pid = Binder.getCallingPid();
        final long ident = Binder.clearCallingIdentity();
        final boolean injectIntoUid = targetUid != Process.INVALID_UID;
        final int result;
        try {
            result = mNative.injectInputEvent(event, injectIntoUid,
                    targetUid, mode, INJECTION_TIMEOUT_MILLIS,
                    WindowManagerPolicy.FLAG_DISABLE_KEY_REPEAT);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        switch (result) {
            case InputEventInjectionResult.SUCCEEDED:
                return true;
            case InputEventInjectionResult.TARGET_MISMATCH:
                if (!injectIntoUid) {
                    throw new IllegalStateException("Injection should not result in TARGET_MISMATCH"
                            + " when it is not targeted into to a specific uid.");
                }
                throw new IllegalArgumentException(
                    "Targeted input event injection from pid " + pid
                            + " was not directed at a window owned by uid "
                            + targetUid + ".");
            case InputEventInjectionResult.TIMED_OUT:
                Slog.w(TAG, "Input event injection from pid " + pid + " timed out.");
                return false;
            case InputEventInjectionResult.FAILED:
            default:
                Slog.w(TAG, "Input event injection from pid " + pid + " failed.");
                return false;
        }
    }

    @Override // Binder call
    public VerifiedInputEvent verifyInputEvent(InputEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        return mNative.verifyInputEvent(event);
    }

    @Override // Binder call
    public String getVelocityTrackerStrategy() {
        return mVelocityTrackerStrategy;
    }

    /**
     * Gets information about the input device with the specified id.
     * @param deviceId The device id.
     * @return The input device or null if not found.
     */
    @Override // Binder call
    public InputDevice getInputDevice(int deviceId) {
        synchronized (mInputDevicesLock) {
            for (final InputDevice inputDevice : mInputDevices) {
                if (inputDevice.getId() == deviceId) {
                    return inputDevice;
                }
            }
        }
        return null;
    }

    // Binder call
    @Override
    public boolean isInputDeviceEnabled(int deviceId) {
        return mNative.isInputDeviceEnabled(deviceId);
    }

    // Binder call
    @Override
    public void enableInputDevice(int deviceId) {
        if (!checkCallingPermission(android.Manifest.permission.DISABLE_INPUT_DEVICE,
                "enableInputDevice()")) {
            throw new SecurityException("Requires DISABLE_INPUT_DEVICE permission");
        }
        mNative.enableInputDevice(deviceId);
    }

    // Binder call
    @Override
    public void disableInputDevice(int deviceId) {
        if (!checkCallingPermission(android.Manifest.permission.DISABLE_INPUT_DEVICE,
                "disableInputDevice()")) {
            throw new SecurityException("Requires DISABLE_INPUT_DEVICE permission");
        }
        mNative.disableInputDevice(deviceId);
    }

    /**
     * Gets the ids of all input devices in the system.
     * @return The input device ids.
     */
    @Override // Binder call
    public int[] getInputDeviceIds() {
        synchronized (mInputDevicesLock) {
            final int count = mInputDevices.length;
            int[] ids = new int[count];
            for (int i = 0; i < count; i++) {
                ids[i] = mInputDevices[i].getId();
            }
            return ids;
        }
    }

    /**
     * Gets all input devices in the system.
     * @return The array of input devices.
     */
    public InputDevice[] getInputDevices() {
        synchronized (mInputDevicesLock) {
            return mInputDevices;
        }
    }

    @Override // Binder call
    public void registerInputDevicesChangedListener(IInputDevicesChangedListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");

        synchronized (mInputDevicesLock) {
            int callingPid = Binder.getCallingPid();
            if (mInputDevicesChangedListeners.get(callingPid) != null) {
                throw new SecurityException("The calling process has already "
                        + "registered an InputDevicesChangedListener.");
            }

            InputDevicesChangedListenerRecord record =
                    new InputDevicesChangedListenerRecord(callingPid, listener);
            try {
                IBinder binder = listener.asBinder();
                binder.linkToDeath(record, 0);
            } catch (RemoteException ex) {
                // give up
                throw new RuntimeException(ex);
            }

            mInputDevicesChangedListeners.put(callingPid, record);
        }
    }

    private void onInputDevicesChangedListenerDied(int pid) {
        synchronized (mInputDevicesLock) {
            mInputDevicesChangedListeners.remove(pid);
        }
    }

    // Must be called on handler.
    private void deliverInputDevicesChanged(InputDevice[] oldInputDevices) {
        // Scan for changes.
        mTempInputDevicesChangedListenersToNotify.clear();
        final int numListeners;
        final int[] deviceIdAndGeneration;
        synchronized (mInputDevicesLock) {
            if (!mInputDevicesChangedPending) {
                return;
            }
            mInputDevicesChangedPending = false;

            numListeners = mInputDevicesChangedListeners.size();
            for (int i = 0; i < numListeners; i++) {
                mTempInputDevicesChangedListenersToNotify.add(
                        mInputDevicesChangedListeners.valueAt(i));
            }

            final int numDevices = mInputDevices.length;
            deviceIdAndGeneration = new int[numDevices * 2];
            for (int i = 0; i < numDevices; i++) {
                final InputDevice inputDevice = mInputDevices[i];
                deviceIdAndGeneration[i * 2] = inputDevice.getId();
                deviceIdAndGeneration[i * 2 + 1] = inputDevice.getGeneration();
                if (DEBUG) {
                    Log.d(TAG, "device " + inputDevice.getId() + " generation "
                            + inputDevice.getGeneration());
                }
            }
        }

        // Notify listeners.
        for (int i = 0; i < numListeners; i++) {
            mTempInputDevicesChangedListenersToNotify.get(i).notifyInputDevicesChanged(
                    deviceIdAndGeneration);
        }
        mTempInputDevicesChangedListenersToNotify.clear();
    }

    @Override // Binder call & native callback
    public TouchCalibration getTouchCalibrationForInputDevice(String inputDeviceDescriptor,
            int surfaceRotation) {
        Objects.requireNonNull(inputDeviceDescriptor, "inputDeviceDescriptor must not be null");

        synchronized (mDataStore) {
            return mDataStore.getTouchCalibration(inputDeviceDescriptor, surfaceRotation);
        }
    }

    @Override // Binder call
    public void setTouchCalibrationForInputDevice(String inputDeviceDescriptor, int surfaceRotation,
            TouchCalibration calibration) {
        if (!checkCallingPermission(android.Manifest.permission.SET_INPUT_CALIBRATION,
                "setTouchCalibrationForInputDevice()")) {
            throw new SecurityException("Requires SET_INPUT_CALIBRATION permission");
        }
        Objects.requireNonNull(inputDeviceDescriptor, "inputDeviceDescriptor must not be null");
        Objects.requireNonNull(calibration, "calibration must not be null");
        if (surfaceRotation < Surface.ROTATION_0 || surfaceRotation > Surface.ROTATION_270) {
            throw new IllegalArgumentException("surfaceRotation value out of bounds");
        }

        synchronized (mDataStore) {
            try {
                if (mDataStore.setTouchCalibration(inputDeviceDescriptor, surfaceRotation,
                        calibration)) {
                    mNative.reloadCalibration();
                }
            } finally {
                mDataStore.saveIfNeeded();
            }
        }
    }

    @Override // Binder call
    public int isInTabletMode() {
        if (!checkCallingPermission(android.Manifest.permission.TABLET_MODE,
                "isInTabletMode()")) {
            throw new SecurityException("Requires TABLET_MODE permission");
        }
        return getSwitchState(-1, InputDevice.SOURCE_ANY, SW_TABLET_MODE);
    }

    @Override // Binder call
    public int isMicMuted() {
        return getSwitchState(-1, InputDevice.SOURCE_ANY, SW_MUTE_DEVICE);
    }

    @Override // Binder call
    public void registerTabletModeChangedListener(ITabletModeChangedListener listener) {
        if (!checkCallingPermission(android.Manifest.permission.TABLET_MODE,
                "registerTabletModeChangedListener()")) {
            throw new SecurityException("Requires TABLET_MODE_LISTENER permission");
        }
        Objects.requireNonNull(listener, "event must not be null");

        synchronized (mTabletModeLock) {
            final int callingPid = Binder.getCallingPid();
            if (mTabletModeChangedListeners.get(callingPid) != null) {
                throw new IllegalStateException("The calling process has already registered "
                        + "a TabletModeChangedListener.");
            }
            TabletModeChangedListenerRecord record =
                    new TabletModeChangedListenerRecord(callingPid, listener);
            try {
                IBinder binder = listener.asBinder();
                binder.linkToDeath(record, 0);
            } catch (RemoteException ex) {
                throw new RuntimeException(ex);
            }
            mTabletModeChangedListeners.put(callingPid, record);
        }
    }

    private void onTabletModeChangedListenerDied(int pid) {
        synchronized (mTabletModeLock) {
            mTabletModeChangedListeners.remove(pid);
        }
    }

    // Must be called on handler
    private void deliverTabletModeChanged(long whenNanos, boolean inTabletMode) {
        mTempTabletModeChangedListenersToNotify.clear();
        final int numListeners;
        synchronized (mTabletModeLock) {
            numListeners = mTabletModeChangedListeners.size();
            for (int i = 0; i < numListeners; i++) {
                mTempTabletModeChangedListenersToNotify.add(
                        mTabletModeChangedListeners.valueAt(i));
            }
        }
        for (int i = 0; i < numListeners; i++) {
            mTempTabletModeChangedListenersToNotify.get(i).notifyTabletModeChanged(
                    whenNanos, inTabletMode);
        }
    }

    @Override // Binder call
    public KeyboardLayout[] getKeyboardLayouts() {
        return mKeyboardLayoutManager.getKeyboardLayouts();
    }

    @Override // Binder call
    public KeyboardLayout[] getKeyboardLayoutsForInputDevice(
            final InputDeviceIdentifier identifier) {
        return mKeyboardLayoutManager.getKeyboardLayoutsForInputDevice(identifier);
    }

    @Override // Binder call
    public KeyboardLayout getKeyboardLayout(String keyboardLayoutDescriptor) {
        return mKeyboardLayoutManager.getKeyboardLayout(keyboardLayoutDescriptor);
    }

    @Override // Binder call
    public String getCurrentKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier) {
        return mKeyboardLayoutManager.getCurrentKeyboardLayoutForInputDevice(identifier);
    }

    @EnforcePermission(Manifest.permission.SET_KEYBOARD_LAYOUT)
    @Override // Binder call
    public void setCurrentKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            String keyboardLayoutDescriptor) {
        super.setCurrentKeyboardLayoutForInputDevice_enforcePermission();
        mKeyboardLayoutManager.setCurrentKeyboardLayoutForInputDevice(identifier,
                keyboardLayoutDescriptor);
    }

    @Override // Binder call
    public String[] getEnabledKeyboardLayoutsForInputDevice(InputDeviceIdentifier identifier) {
        return mKeyboardLayoutManager.getEnabledKeyboardLayoutsForInputDevice(identifier);
    }

    @EnforcePermission(Manifest.permission.SET_KEYBOARD_LAYOUT)
    @Override // Binder call
    public void addKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            String keyboardLayoutDescriptor) {
        super.addKeyboardLayoutForInputDevice_enforcePermission();
        mKeyboardLayoutManager.addKeyboardLayoutForInputDevice(identifier,
                keyboardLayoutDescriptor);
    }

    @EnforcePermission(Manifest.permission.SET_KEYBOARD_LAYOUT)
    @Override // Binder call
    public void removeKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            String keyboardLayoutDescriptor) {
        super.removeKeyboardLayoutForInputDevice_enforcePermission();
        mKeyboardLayoutManager.removeKeyboardLayoutForInputDevice(identifier,
                keyboardLayoutDescriptor);
    }

    @Override // Binder call
    public String getKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            @UserIdInt int userId, @NonNull InputMethodInfo imeInfo,
            @Nullable InputMethodSubtype imeSubtype) {
        return mKeyboardLayoutManager.getKeyboardLayoutForInputDevice(identifier, userId,
                imeInfo, imeSubtype);
    }

    @EnforcePermission(Manifest.permission.SET_KEYBOARD_LAYOUT)
    @Override // Binder call
    public void setKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            @UserIdInt int userId, @NonNull InputMethodInfo imeInfo,
            @Nullable InputMethodSubtype imeSubtype, String keyboardLayoutDescriptor) {
        super.setKeyboardLayoutForInputDevice_enforcePermission();
        mKeyboardLayoutManager.setKeyboardLayoutForInputDevice(identifier, userId, imeInfo,
                imeSubtype, keyboardLayoutDescriptor);
    }

    @Override // Binder call
    public KeyboardLayout[] getKeyboardLayoutListForInputDevice(InputDeviceIdentifier identifier,
            @UserIdInt int userId, @NonNull InputMethodInfo imeInfo,
            @Nullable InputMethodSubtype imeSubtype) {
        return mKeyboardLayoutManager.getKeyboardLayoutListForInputDevice(identifier, userId,
                imeInfo, imeSubtype);
    }


    public void switchKeyboardLayout(int deviceId, int direction) {
        mKeyboardLayoutManager.switchKeyboardLayout(deviceId, direction);
    }

    public void setFocusedApplication(int displayId, InputApplicationHandle application) {
        mNative.setFocusedApplication(displayId, application);
    }

    public void setFocusedDisplay(int displayId) {
        mNative.setFocusedDisplay(displayId);
    }

    /** Clean up input window handles of the given display. */
    public void onDisplayRemoved(int displayId) {
        if (mPointerIconDisplayContext != null
                && mPointerIconDisplayContext.getDisplay().getDisplayId() == displayId) {
            mPointerIconDisplayContext = null;
        }

        updateAdditionalDisplayInputProperties(displayId, AdditionalDisplayInputProperties::reset);

        // TODO(b/320763728): Rely on WindowInfosListener to determine when a display has been
        //  removed in InputDispatcher instead of this callback.
        mNative.displayRemoved(displayId);
    }

    @Override
    public void requestPointerCapture(IBinder inputChannelToken, boolean enabled) {
        Objects.requireNonNull(inputChannelToken, "event must not be null");

        mNative.requestPointerCapture(inputChannelToken, enabled);
    }

    public void setInputDispatchMode(boolean enabled, boolean frozen) {
        mNative.setInputDispatchMode(enabled, frozen);
    }

    public void setSystemUiLightsOut(boolean lightsOut) {
        mNative.setSystemUiLightsOut(lightsOut);
    }

    /**
     * Atomically transfers touch focus from one window to another as identified by
     * their input channels.  It is possible for multiple windows to have
     * touch focus if they support split touch dispatch
     * {@link android.view.WindowManager.LayoutParams#FLAG_SPLIT_TOUCH} but this
     * method only transfers touch focus of the specified window without affecting
     * other windows that may also have touch focus at the same time.
     * @param fromChannel The channel of a window that currently has touch focus.
     * @param toChannel The channel of the window that should receive touch focus in
     * place of the first.
     * @param isDragDrop True if transfer touch focus for drag and drop.
     * @return True if the transfer was successful.  False if the window with the
     * specified channel did not actually have touch focus at the time of the request.
     */
    public boolean transferTouchFocus(@NonNull InputChannel fromChannel,
            @NonNull InputChannel toChannel, boolean isDragDrop) {
        return mNative.transferTouchFocus(fromChannel.getToken(), toChannel.getToken(),
                isDragDrop);
    }

    /**
     * Atomically transfers touch focus from one window to another as identified by
     * their input channels.  It is possible for multiple windows to have
     * touch focus if they support split touch dispatch
     * {@link android.view.WindowManager.LayoutParams#FLAG_SPLIT_TOUCH} but this
     * method only transfers touch focus of the specified window without affecting
     * other windows that may also have touch focus at the same time.
     * @param fromChannelToken The channel token of a window that currently has touch focus.
     * @param toChannelToken The channel token of the window that should receive touch focus in
     * place of the first.
     * @return True if the transfer was successful.  False if the window with the
     * specified channel did not actually have touch focus at the time of the request.
     */
    public boolean transferTouchFocus(@NonNull IBinder fromChannelToken,
            @NonNull IBinder toChannelToken) {
        Objects.requireNonNull(fromChannelToken);
        Objects.requireNonNull(toChannelToken);
        return mNative.transferTouchFocus(fromChannelToken, toChannelToken,
                false /* isDragDrop */);
    }

    @Override // Binder call
    public void tryPointerSpeed(int speed) {
        if (!checkCallingPermission(android.Manifest.permission.SET_POINTER_SPEED,
                "tryPointerSpeed()")) {
            throw new SecurityException("Requires SET_POINTER_SPEED permission");
        }

        if (speed < InputSettings.MIN_POINTER_SPEED || speed > InputSettings.MAX_POINTER_SPEED) {
            throw new IllegalArgumentException("speed out of range");
        }

        setPointerSpeedUnchecked(speed);
    }

    private void setPointerSpeedUnchecked(int speed) {
        speed = Math.min(Math.max(speed, InputSettings.MIN_POINTER_SPEED),
                InputSettings.MAX_POINTER_SPEED);
        mNative.setPointerSpeed(speed);
    }

    private void setMousePointerAccelerationEnabled(boolean enabled, int displayId) {
        updateAdditionalDisplayInputProperties(displayId,
                properties -> properties.mousePointerAccelerationEnabled = enabled);
    }

    private void setPointerIconVisible(boolean visible, int displayId) {
        updateAdditionalDisplayInputProperties(displayId,
                properties -> properties.pointerIconVisible = visible);
    }

    /**
     * Update the display on which the mouse pointer is shown.
     *
     * @return true if the pointer displayId changed, false otherwise.
     */
    @GuardedBy("mAdditionalDisplayInputPropertiesLock")
    private boolean updatePointerDisplayIdLocked(int pointerDisplayId) {
        if (mRequestedPointerDisplayId == pointerDisplayId) {
            return false;
        }
        mRequestedPointerDisplayId = pointerDisplayId;
        mNative.setPointerDisplayId(pointerDisplayId);
        applyAdditionalDisplayInputProperties();
        return true;
    }

    private void handlePointerDisplayIdChanged(PointerDisplayIdChangedArgs args) {
        synchronized (mAdditionalDisplayInputPropertiesLock) {
            mAcknowledgedPointerDisplayId = args.mPointerDisplayId;
            // Notify waiting threads that the display of the mouse pointer has changed.
            mAdditionalDisplayInputPropertiesLock.notifyAll();
        }
        mWindowManagerCallbacks.notifyPointerDisplayIdChanged(
                args.mPointerDisplayId, args.mXPosition, args.mYPosition);
    }

    private boolean setVirtualMousePointerDisplayIdBlocking(int overrideDisplayId) {
        final boolean isRemovingOverride = overrideDisplayId == Display.INVALID_DISPLAY;

        // Take care to not make calls to window manager while holding internal locks.
        final int resolvedDisplayId = isRemovingOverride
                ? mWindowManagerCallbacks.getPointerDisplayId()
                : overrideDisplayId;

        synchronized (mAdditionalDisplayInputPropertiesLock) {
            mOverriddenPointerDisplayId = overrideDisplayId;

            if (!updatePointerDisplayIdLocked(resolvedDisplayId)
                    && mAcknowledgedPointerDisplayId == resolvedDisplayId) {
                // The requested pointer display is already set.
                return true;
            }
            if (isRemovingOverride && mAcknowledgedPointerDisplayId == Display.INVALID_DISPLAY) {
                // The pointer display override is being removed, but the current pointer display
                // is already invalid. This can happen when the PointerController is destroyed as a
                // result of the removal of all input devices that can control the pointer.
                return true;
            }
            try {
                // The pointer display changed, so wait until the change has propagated.
                mAdditionalDisplayInputPropertiesLock.wait(5_000 /*mills*/);
            } catch (InterruptedException ignored) {
            }
            // This request succeeds in two cases:
            // - This request was to remove the override, in which case the new pointer display
            //   could be anything that WM has set.
            // - We are setting a new override, in which case the request only succeeds if the
            //   reported new displayId is the one we requested. This check ensures that if two
            //   competing overrides are requested in succession, the caller can be notified if one
            //   of them fails.
            return  isRemovingOverride || mAcknowledgedPointerDisplayId == overrideDisplayId;
        }
    }

    private int getVirtualMousePointerDisplayId() {
        synchronized (mAdditionalDisplayInputPropertiesLock) {
            return mOverriddenPointerDisplayId;
        }
    }

    private void setDisplayEligibilityForPointerCapture(int displayId, boolean isEligible) {
        mNative.setDisplayEligibilityForPointerCapture(displayId, isEligible);
    }

    private static class VibrationInfo {
        private final long[] mPattern;
        private final int[] mAmplitudes;
        private final int mRepeat;

        public long[] getPattern() {
            return mPattern;
        }

        public int[] getAmplitudes() {
            return mAmplitudes;
        }

        public int getRepeatIndex() {
            return mRepeat;
        }

        VibrationInfo(VibrationEffect effect) {
            long[] pattern = null;
            int[] amplitudes = null;
            int patternRepeatIndex = -1;
            int amplitudeCount = -1;

            if (effect instanceof VibrationEffect.Composed) {
                VibrationEffect.Composed composed = (VibrationEffect.Composed) effect;
                int segmentCount = composed.getSegments().size();
                pattern = new long[segmentCount];
                amplitudes = new int[segmentCount];
                patternRepeatIndex = composed.getRepeatIndex();
                amplitudeCount = 0;
                for (int i = 0; i < segmentCount; i++) {
                    VibrationEffectSegment segment = composed.getSegments().get(i);
                    if (composed.getRepeatIndex() == i) {
                        patternRepeatIndex = amplitudeCount;
                    }
                    if (!(segment instanceof StepSegment)) {
                        Slog.w(TAG, "Input devices don't support segment " + segment);
                        amplitudeCount = -1;
                        break;
                    }
                    float amplitude = ((StepSegment) segment).getAmplitude();
                    if (Float.compare(amplitude, VibrationEffect.DEFAULT_AMPLITUDE) == 0) {
                        amplitudes[amplitudeCount] = DEFAULT_VIBRATION_MAGNITUDE;
                    } else {
                        amplitudes[amplitudeCount] =
                                (int) (amplitude * VibrationEffect.MAX_AMPLITUDE);
                    }
                    pattern[amplitudeCount++] = segment.getDuration();
                }
            }

            if (amplitudeCount < 0) {
                Slog.w(TAG, "Only oneshot and step waveforms are supported on input devices");
                mPattern = new long[0];
                mAmplitudes = new int[0];
                mRepeat = -1;
            } else {
                mRepeat = patternRepeatIndex;
                mPattern = new long[amplitudeCount];
                mAmplitudes = new int[amplitudeCount];
                System.arraycopy(pattern, 0, mPattern, 0, amplitudeCount);
                System.arraycopy(amplitudes, 0, mAmplitudes, 0, amplitudeCount);
                if (mRepeat >= mPattern.length) {
                    throw new ArrayIndexOutOfBoundsException("Repeat index " + mRepeat
                            + " must be within the bounds of the pattern.length "
                            + mPattern.length);
                }
            }
        }
    }

    private VibratorToken getVibratorToken(int deviceId, IBinder token) {
        VibratorToken v;
        synchronized (mVibratorLock) {
            v = mVibratorTokens.get(token);
            if (v == null) {
                v = new VibratorToken(deviceId, token, mNextVibratorTokenValue++);
                try {
                    token.linkToDeath(v, 0);
                } catch (RemoteException ex) {
                    // give up
                    throw new RuntimeException(ex);
                }
                mVibratorTokens.put(token, v);
            }
        }
        return v;
    }

    // Binder call
    @Override
    public void vibrate(int deviceId, VibrationEffect effect, IBinder token) {
        VibrationInfo info = new VibrationInfo(effect);
        VibratorToken v = getVibratorToken(deviceId, token);
        synchronized (v) {
            v.mVibrating = true;
            mNative.vibrate(deviceId, info.getPattern(), info.getAmplitudes(),
                    info.getRepeatIndex(), v.mTokenValue);
        }
    }

    // Binder call
    @Override
    public int[] getVibratorIds(int deviceId) {
        return mNative.getVibratorIds(deviceId);
    }

    // Binder call
    @Override
    public boolean isVibrating(int deviceId) {
        return mNative.isVibrating(deviceId);
    }

    // Binder call
    @Override
    public void vibrateCombined(int deviceId, CombinedVibration effect, IBinder token) {
        VibratorToken v = getVibratorToken(deviceId, token);
        synchronized (v) {
            if (!(effect instanceof CombinedVibration.Mono)
                    && !(effect instanceof CombinedVibration.Stereo)) {
                Slog.e(TAG, "Only Mono and Stereo effects are supported");
                return;
            }

            v.mVibrating = true;
            if (effect instanceof CombinedVibration.Mono) {
                CombinedVibration.Mono mono = (CombinedVibration.Mono) effect;
                VibrationInfo info = new VibrationInfo(mono.getEffect());
                mNative.vibrate(deviceId, info.getPattern(), info.getAmplitudes(),
                        info.getRepeatIndex(), v.mTokenValue);
            } else if (effect instanceof CombinedVibration.Stereo) {
                CombinedVibration.Stereo stereo = (CombinedVibration.Stereo) effect;
                SparseArray<VibrationEffect> effects = stereo.getEffects();
                long[] pattern = new long[0];
                int repeat = Integer.MIN_VALUE;
                SparseArray<int[]> amplitudes = new SparseArray<>(effects.size());
                for (int i = 0; i < effects.size(); i++) {
                    VibrationInfo info = new VibrationInfo(effects.valueAt(i));
                    // Pattern of all effects should be same
                    if (pattern.length == 0) {
                        pattern = info.getPattern();
                    }
                    if (repeat == Integer.MIN_VALUE) {
                        repeat = info.getRepeatIndex();
                    }
                    amplitudes.put(effects.keyAt(i), info.getAmplitudes());
                }
                mNative.vibrateCombined(deviceId, pattern, amplitudes, repeat,
                        v.mTokenValue);
            }
        }
    }

    // Binder call
    @Override
    public void cancelVibrate(int deviceId, IBinder token) {
        VibratorToken v;
        synchronized (mVibratorLock) {
            v = mVibratorTokens.get(token);
            if (v == null || v.mDeviceId != deviceId) {
                return; // nothing to cancel
            }
        }

        cancelVibrateIfNeeded(v);
    }

    void onVibratorTokenDied(VibratorToken v) {
        synchronized (mVibratorLock) {
            mVibratorTokens.remove(v.mToken);
        }

        cancelVibrateIfNeeded(v);
    }

    private void cancelVibrateIfNeeded(VibratorToken v) {
        synchronized (v) {
            if (v.mVibrating) {
                mNative.cancelVibrate(v.mDeviceId, v.mTokenValue);
                v.mVibrating = false;
            }
        }
    }

    // Native callback.
    @SuppressWarnings("unused")
    private void notifyVibratorState(int deviceId, boolean isOn) {
        if (DEBUG) {
            Slog.d(TAG, "notifyVibratorState: deviceId=" + deviceId + " isOn=" + isOn);
        }
        synchronized (mVibratorLock) {
            mIsVibrating.put(deviceId, isOn);
            notifyVibratorStateListenersLocked(deviceId);
        }
    }

    @GuardedBy("mVibratorLock")
    private void notifyVibratorStateListenersLocked(int deviceId) {
        if (!mVibratorStateListeners.contains(deviceId)) {
            if (DEBUG) {
                Slog.v(TAG, "Device " + deviceId + " doesn't have vibrator state listener.");
            }
            return;
        }
        RemoteCallbackList<IVibratorStateListener> listeners =
                mVibratorStateListeners.get(deviceId);
        final int length = listeners.beginBroadcast();
        try {
            for (int i = 0; i < length; i++) {
                notifyVibratorStateListenerLocked(deviceId, listeners.getBroadcastItem(i));
            }
        } finally {
            listeners.finishBroadcast();
        }
    }

    @GuardedBy("mVibratorLock")
    private void notifyVibratorStateListenerLocked(int deviceId, IVibratorStateListener listener) {
        try {
            listener.onVibrating(mIsVibrating.get(deviceId));
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "Vibrator state listener failed to call", e);
        }
    }

    @Override // Binder call
    public boolean registerVibratorStateListener(int deviceId, IVibratorStateListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");

        RemoteCallbackList<IVibratorStateListener> listeners;
        synchronized (mVibratorLock) {
            if (!mVibratorStateListeners.contains(deviceId)) {
                listeners = new RemoteCallbackList<>();
                mVibratorStateListeners.put(deviceId, listeners);
            } else {
                listeners = mVibratorStateListeners.get(deviceId);
            }

            final long token = Binder.clearCallingIdentity();
            try {
                if (!listeners.register(listener)) {
                    Slog.e(TAG, "Could not register vibrator state listener " + listener);
                    return false;
                }
                // Notify its callback after new client registered.
                notifyVibratorStateListenerLocked(deviceId, listener);
                return true;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    @Override // Binder call
    public boolean unregisterVibratorStateListener(int deviceId, IVibratorStateListener listener) {
        synchronized (mVibratorLock) {
            final long token = Binder.clearCallingIdentity();
            try {
                if (!mVibratorStateListeners.contains(deviceId)) {
                    Slog.w(TAG, "Vibrator state listener " + deviceId + " doesn't exist");
                    return false;
                }
                RemoteCallbackList<IVibratorStateListener> listeners =
                        mVibratorStateListeners.get(deviceId);
                return listeners.unregister(listener);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

    // Binder call
    @Override
    public IInputDeviceBatteryState getBatteryState(int deviceId) {
        return mBatteryController.getBatteryState(deviceId);
    }

    // Binder call
    @Override
    public void setPointerIconType(int iconType) {
        if (iconType == PointerIcon.TYPE_CUSTOM) {
            throw new IllegalArgumentException("Use setCustomPointerIcon to set custom pointers");
        }
        synchronized (mAdditionalDisplayInputPropertiesLock) {
            mPointerIcon = null;
            mPointerIconType = iconType;

            if (!mCurrentDisplayProperties.pointerIconVisible) return;

            mNative.setPointerIconType(mPointerIconType);
        }
    }

    // Binder call
    @Override
    public void setCustomPointerIcon(PointerIcon icon) {
        Objects.requireNonNull(icon);
        synchronized (mAdditionalDisplayInputPropertiesLock) {
            mPointerIconType = PointerIcon.TYPE_CUSTOM;
            mPointerIcon = icon;

            if (!mCurrentDisplayProperties.pointerIconVisible) return;

            mNative.setCustomPointerIcon(mPointerIcon);
        }
    }

    // Binder call
    @Override
    public boolean setPointerIcon(PointerIcon icon, int displayId, int deviceId, int pointerId,
            IBinder inputToken) {
        Objects.requireNonNull(icon);
        synchronized (mAdditionalDisplayInputPropertiesLock) {
            mPointerIconType = icon.getType();
            mPointerIcon = mPointerIconType == PointerIcon.TYPE_CUSTOM ? icon : null;

            return mNative.setPointerIcon(icon, displayId, deviceId, pointerId, inputToken);
        }
    }

    /**
     * Add a runtime association between the input port and the display port. This overrides any
     * static associations.
     * @param inputPort The port of the input device.
     * @param displayPort The physical port of the associated display.
     */
    @Override // Binder call
    public void addPortAssociation(@NonNull String inputPort, int displayPort) {
        if (!checkCallingPermission(
                android.Manifest.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY,
                "addPortAssociation()")) {
            throw new SecurityException(
                    "Requires ASSOCIATE_INPUT_DEVICE_TO_DISPLAY permission");
        }

        Objects.requireNonNull(inputPort);
        synchronized (mAssociationsLock) {
            mRuntimeAssociations.put(inputPort, displayPort);
        }
        mNative.notifyPortAssociationsChanged();
    }

    /**
     * Remove the runtime association between the input port and the display port. Any existing
     * static association for the cleared input port will be restored.
     * @param inputPort The port of the input device to be cleared.
     */
    @Override // Binder call
    public void removePortAssociation(@NonNull String inputPort) {
        if (!checkCallingPermission(
                android.Manifest.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY,
                "removePortAssociation()")) {
            throw new SecurityException(
                    "Requires ASSOCIATE_INPUT_DEVICE_TO_DISPLAY permission");
        }

        Objects.requireNonNull(inputPort);
        synchronized (mAssociationsLock) {
            mRuntimeAssociations.remove(inputPort);
        }
        mNative.notifyPortAssociationsChanged();
    }

    @Override // Binder call
    public void addUniqueIdAssociation(@NonNull String inputPort, @NonNull String displayUniqueId) {
        if (!checkCallingPermission(
                android.Manifest.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY,
                "addUniqueIdAssociation()")) {
            throw new SecurityException(
                    "Requires ASSOCIATE_INPUT_DEVICE_TO_DISPLAY permission");
        }

        Objects.requireNonNull(inputPort);
        Objects.requireNonNull(displayUniqueId);
        synchronized (mAssociationsLock) {
            mUniqueIdAssociations.put(inputPort, displayUniqueId);
        }
        mNative.changeUniqueIdAssociation();
    }

    @Override // Binder call
    public void removeUniqueIdAssociation(@NonNull String inputPort) {
        if (!checkCallingPermission(
                android.Manifest.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY,
                "removeUniqueIdAssociation()")) {
            throw new SecurityException("Requires ASSOCIATE_INPUT_DEVICE_TO_DISPLAY permission");
        }

        Objects.requireNonNull(inputPort);
        synchronized (mAssociationsLock) {
            mUniqueIdAssociations.remove(inputPort);
        }
        mNative.changeUniqueIdAssociation();
    }

    void setTypeAssociationInternal(@NonNull String inputPort, @NonNull String type) {
        Objects.requireNonNull(inputPort);
        Objects.requireNonNull(type);
        synchronized (mAssociationsLock) {
            mDeviceTypeAssociations.put(inputPort, type);
        }
        mNative.changeTypeAssociation();
    }

    void unsetTypeAssociationInternal(@NonNull String inputPort) {
        Objects.requireNonNull(inputPort);
        synchronized (mAssociationsLock) {
            mDeviceTypeAssociations.remove(inputPort);
        }
        mNative.changeTypeAssociation();
    }

    private void addKeyboardLayoutAssociation(@NonNull String inputPort,
            @NonNull String languageTag, @NonNull String layoutType) {
        Objects.requireNonNull(inputPort);
        Objects.requireNonNull(languageTag);
        Objects.requireNonNull(layoutType);

        synchronized (mAssociationsLock) {
            mKeyboardLayoutAssociations.put(inputPort,
                    TextUtils.formatSimple("%s,%s", languageTag, layoutType));
        }
        mNative.changeKeyboardLayoutAssociation();
    }

    private void removeKeyboardLayoutAssociation(@NonNull String inputPort) {
        Objects.requireNonNull(inputPort);
        synchronized (mAssociationsLock) {
            mKeyboardLayoutAssociations.remove(inputPort);
        }
        mNative.changeKeyboardLayoutAssociation();
    }

    @Override // Binder call
    public InputSensorInfo[] getSensorList(int deviceId) {
        return mNative.getSensorList(deviceId);
    }

    @Override // Binder call
    public boolean registerSensorListener(IInputSensorEventListener listener) {
        if (DEBUG) {
            Slog.d(TAG, "registerSensorListener: listener=" + listener + " callingPid="
                    + Binder.getCallingPid());
        }
        Objects.requireNonNull(listener, "listener must not be null");

        synchronized (mSensorEventLock) {
            int callingPid = Binder.getCallingPid();
            if (mSensorEventListeners.get(callingPid) != null) {
                Slog.e(TAG, "The calling process " + callingPid + " has already "
                        + "registered an InputSensorEventListener.");
                return false;
            }

            SensorEventListenerRecord record =
                    new SensorEventListenerRecord(callingPid, listener);
            try {
                IBinder binder = listener.asBinder();
                binder.linkToDeath(record, 0);
            } catch (RemoteException ex) {
                // give up
                throw new RuntimeException(ex);
            }

            mSensorEventListeners.put(callingPid, record);
        }
        return true;
    }

    @Override // Binder call
    public void unregisterSensorListener(IInputSensorEventListener listener) {
        if (DEBUG) {
            Slog.d(TAG, "unregisterSensorListener: listener=" + listener + " callingPid="
                    + Binder.getCallingPid());
        }

        Objects.requireNonNull(listener, "listener must not be null");

        synchronized (mSensorEventLock) {
            int callingPid = Binder.getCallingPid();
            if (mSensorEventListeners.get(callingPid) != null) {
                SensorEventListenerRecord record = mSensorEventListeners.get(callingPid);
                if (record.getListener().asBinder() != listener.asBinder()) {
                    throw new IllegalArgumentException("listener is not registered");
                }
                mSensorEventListeners.remove(callingPid);
            }
        }
    }

    @Override // Binder call
    public boolean flushSensor(int deviceId, int sensorType) {
        synchronized (mSensorEventLock) {
            int callingPid = Binder.getCallingPid();
            SensorEventListenerRecord listener = mSensorEventListeners.get(callingPid);
            if (listener != null) {
                return mNative.flushSensor(deviceId, sensorType);
            }
            return false;
        }
    }

    @Override // Binder call
    public boolean enableSensor(int deviceId, int sensorType, int samplingPeriodUs,
            int maxBatchReportLatencyUs) {
        synchronized (mInputDevicesLock) {
            return mNative.enableSensor(deviceId, sensorType, samplingPeriodUs,
                    maxBatchReportLatencyUs);
        }
    }

    @Override // Binder call
    public void disableSensor(int deviceId, int sensorType) {
        synchronized (mInputDevicesLock) {
            mNative.disableSensor(deviceId, sensorType);
        }
    }

    /**
     * LightSession represents a light session for lights manager.
     */
    private final class LightSession implements DeathRecipient {
        private final int mDeviceId;
        private final IBinder mToken;
        private final String mOpPkg;
        // The light ids and states that are requested by the light seesion
        private int[] mLightIds;
        private LightState[] mLightStates;

        LightSession(int deviceId, String opPkg, IBinder token) {
            mDeviceId = deviceId;
            mOpPkg = opPkg;
            mToken = token;
        }

        @Override
        public void binderDied() {
            if (DEBUG) {
                Slog.d(TAG, "Light token died.");
            }
            synchronized (mLightLock) {
                closeLightSession(mDeviceId, mToken);
                mLightSessions.remove(mToken);
            }
        }
    }

    /**
     * Returns the lights available for apps to control on the specified input device.
     * Only lights that aren't reserved for system use are available to apps.
     */
    @Override // Binder call
    public List<Light> getLights(int deviceId) {
        return mNative.getLights(deviceId);
    }

    /**
     * Set specified light state with for a specific input device.
     */
    private void setLightStateInternal(int deviceId, Light light, LightState lightState) {
        Objects.requireNonNull(light, "light does not exist");
        if (DEBUG) {
            Slog.d(TAG, "setLightStateInternal device " + deviceId + " light " + light
                    + "lightState " + lightState);
        }
        if (light.getType() == Light.LIGHT_TYPE_PLAYER_ID) {
            mNative.setLightPlayerId(deviceId, light.getId(), lightState.getPlayerId());
        } else {
            // Set ARGB format color to input device light
            // Refer to https://developer.android.com/reference/kotlin/android/graphics/Color
            mNative.setLightColor(deviceId, light.getId(), lightState.getColor());
        }
    }

    /**
     * Set multiple light states with multiple light ids for a specific input device.
     */
    private void setLightStatesInternal(int deviceId, int[] lightIds, LightState[] lightStates) {
        final List<Light> lights = mNative.getLights(deviceId);
        SparseArray<Light> lightArray = new SparseArray<>();
        for (int i = 0; i < lights.size(); i++) {
            lightArray.put(lights.get(i).getId(), lights.get(i));
        }
        for (int i = 0; i < lightIds.length; i++) {
            if (lightArray.contains(lightIds[i])) {
                setLightStateInternal(deviceId, lightArray.get(lightIds[i]), lightStates[i]);
            }
        }
    }

    /**
     * Set states for multiple lights for an opened light session.
     */
    @Override
    public void setLightStates(int deviceId, int[] lightIds, LightState[] lightStates,
            IBinder token) {
        Preconditions.checkArgument(lightIds.length == lightStates.length,
                "lights and light states are not same length");
        synchronized (mLightLock) {
            LightSession lightSession = mLightSessions.get(token);
            Preconditions.checkArgument(lightSession != null, "not registered");
            Preconditions.checkState(lightSession.mDeviceId == deviceId, "Incorrect device ID");
            lightSession.mLightIds = lightIds.clone();
            lightSession.mLightStates = lightStates.clone();
            if (DEBUG) {
                Slog.d(TAG, "setLightStates for " + lightSession.mOpPkg + " device " + deviceId);
            }
        }
        setLightStatesInternal(deviceId, lightIds, lightStates);
    }

    @Override
    public @Nullable LightState getLightState(int deviceId, int lightId) {
        synchronized (mLightLock) {
            int color = mNative.getLightColor(deviceId, lightId);
            int playerId = mNative.getLightPlayerId(deviceId, lightId);

            return new LightState(color, playerId);
        }
    }

    @Override
    public void openLightSession(int deviceId, String opPkg, IBinder token) {
        Objects.requireNonNull(token);
        synchronized (mLightLock) {
            Preconditions.checkState(mLightSessions.get(token) == null, "already registered");
            LightSession lightSession = new LightSession(deviceId, opPkg, token);
            try {
                token.linkToDeath(lightSession, 0);
            } catch (RemoteException ex) {
                // give up
                ex.rethrowAsRuntimeException();
            }
            mLightSessions.put(token, lightSession);
            if (DEBUG) {
                Slog.d(TAG, "Open light session for " + opPkg + " device " + deviceId);
            }
        }
    }

    @Override
    public void closeLightSession(int deviceId, IBinder token) {
        Objects.requireNonNull(token);
        synchronized (mLightLock) {
            LightSession lightSession = mLightSessions.get(token);
            Preconditions.checkState(lightSession != null, "not registered");
            // Turn off the lights that were previously requested by the session to be closed.
            Arrays.fill(lightSession.mLightStates, new LightState(0));
            setLightStatesInternal(deviceId, lightSession.mLightIds,
                    lightSession.mLightStates);
            mLightSessions.remove(token);
            // If any other session is still pending with light request, apply the first session's
            // request.
            if (!mLightSessions.isEmpty()) {
                LightSession nextSession = mLightSessions.valueAt(0);
                setLightStatesInternal(deviceId, nextSession.mLightIds, nextSession.mLightStates);
            }
        }
    }

    @Override
    public void cancelCurrentTouch() {
        if (!checkCallingPermission(android.Manifest.permission.MONITOR_INPUT,
                "cancelCurrentTouch()")) {
            throw new SecurityException("Requires MONITOR_INPUT permission");
        }

        mNative.cancelCurrentTouch();
    }

    @Override
    public void registerBatteryListener(int deviceId, IInputDeviceBatteryListener listener) {
        Objects.requireNonNull(listener);
        mBatteryController.registerBatteryListener(deviceId, listener, Binder.getCallingPid());
    }

    @Override
    public void unregisterBatteryListener(int deviceId, IInputDeviceBatteryListener listener) {
        Objects.requireNonNull(listener);
        mBatteryController.unregisterBatteryListener(deviceId, listener, Binder.getCallingPid());
    }

    @EnforcePermission(Manifest.permission.BLUETOOTH)
    @Override
    public String getInputDeviceBluetoothAddress(int deviceId) {
        super.getInputDeviceBluetoothAddress_enforcePermission();

        final String address = mNative.getBluetoothAddress(deviceId);
        if (address == null) return null;
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            throw new IllegalStateException("The Bluetooth address of input device " + deviceId
                    + " should not be invalid: address=" + address);
        }
        return address;
    }

    @EnforcePermission(Manifest.permission.MONITOR_INPUT)
    @Override
    public void pilferPointers(IBinder inputChannelToken) {
        super.pilferPointers_enforcePermission();

        Objects.requireNonNull(inputChannelToken);
        mNative.pilferPointers(inputChannelToken);
    }

    @Override
    @EnforcePermission(Manifest.permission.MONITOR_KEYBOARD_BACKLIGHT)
    public void registerKeyboardBacklightListener(IKeyboardBacklightListener listener) {
        super.registerKeyboardBacklightListener_enforcePermission();
        Objects.requireNonNull(listener);
        mKeyboardBacklightController.registerKeyboardBacklightListener(listener,
                Binder.getCallingPid());
    }

    @Override
    @EnforcePermission(Manifest.permission.MONITOR_KEYBOARD_BACKLIGHT)
    public void unregisterKeyboardBacklightListener(IKeyboardBacklightListener listener) {
        super.unregisterKeyboardBacklightListener_enforcePermission();
        Objects.requireNonNull(listener);
        mKeyboardBacklightController.unregisterKeyboardBacklightListener(listener,
                Binder.getCallingPid());
    }

    @Override
    public HostUsiVersion getHostUsiVersionFromDisplayConfig(int displayId) {
        return mDisplayManagerInternal.getHostUsiVersion(displayId);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");

        ipw.println("INPUT MANAGER (dumpsys input)\n");
        String dumpStr = mNative.dump();
        if (dumpStr != null) {
            pw.println(dumpStr);
        }

        ipw.println("Input Manager Service (Java) State:");
        ipw.increaseIndent();
        dumpAssociations(ipw);
        dumpSpyWindowGestureMonitors(ipw);
        dumpDisplayInputPropertiesValues(ipw);
        mBatteryController.dump(ipw);
        mKeyboardBacklightController.dump(ipw);
    }

    private void dumpAssociations(IndentingPrintWriter pw) {
        if (!mStaticAssociations.isEmpty()) {
            pw.println("Static Associations:");
            mStaticAssociations.forEach((k, v) -> {
                pw.print("  port: " + k);
                pw.println("  display: " + v);
            });
        }

        synchronized (mAssociationsLock) {
            if (!mRuntimeAssociations.isEmpty()) {
                pw.println("Runtime Associations:");
                mRuntimeAssociations.forEach((k, v) -> {
                    pw.print("  port: " + k);
                    pw.println("  display: " + v);
                });
            }
            if (!mUniqueIdAssociations.isEmpty()) {
                pw.println("Unique Id Associations:");
                mUniqueIdAssociations.forEach((k, v) -> {
                    pw.print("  port: " + k);
                    pw.println("  uniqueId: " + v);
                });
            }
            if (!mDeviceTypeAssociations.isEmpty()) {
                pw.println("Type Associations:");
                mDeviceTypeAssociations.forEach((k, v) -> {
                    pw.print("  port: " + k);
                    pw.println("  type: " + v);
                });
            }
        }
    }

    private void dumpSpyWindowGestureMonitors(IndentingPrintWriter pw) {
        synchronized (mInputMonitors) {
            if (mInputMonitors.isEmpty()) return;
            pw.println("Gesture Monitors (implemented as spy windows):");
            int i = 0;
            for (final GestureMonitorSpyWindow monitor : mInputMonitors.values()) {
                pw.append("  " + i++ + ": ").println(monitor.dump());
            }
        }
    }

    private void dumpDisplayInputPropertiesValues(IndentingPrintWriter pw) {
        synchronized (mAdditionalDisplayInputPropertiesLock) {
            if (mAdditionalDisplayInputProperties.size() != 0) {
                pw.println("mAdditionalDisplayInputProperties:");
                pw.increaseIndent();
                for (int i = 0; i < mAdditionalDisplayInputProperties.size(); i++) {
                    pw.println("displayId: "
                            + mAdditionalDisplayInputProperties.keyAt(i));
                    final AdditionalDisplayInputProperties properties =
                            mAdditionalDisplayInputProperties.valueAt(i);
                    pw.println("mousePointerAccelerationEnabled: "
                            + properties.mousePointerAccelerationEnabled);
                    pw.println("pointerIconVisible: " + properties.pointerIconVisible);
                }
                pw.decreaseIndent();
            }
            if (mOverriddenPointerDisplayId != Display.INVALID_DISPLAY) {
                pw.println("mOverriddenPointerDisplayId: " + mOverriddenPointerDisplayId);
            }

            pw.println("mAcknowledgedPointerDisplayId=" + mAcknowledgedPointerDisplayId);
            pw.println("mRequestedPointerDisplayId=" + mRequestedPointerDisplayId);
            pw.println("mPointerIconType=" + PointerIcon.typeToString(mPointerIconType));
            pw.println("mPointerIcon=" + mPointerIcon);
        }
    }
    private boolean checkCallingPermission(String permission, String func) {
        return checkCallingPermission(permission, func, false /*checkInstrumentationSource*/);
    }

    private boolean checkCallingPermission(String permission, String func,
            boolean checkInstrumentationSource) {
        // Quick check: if the calling permission is me, it's all okay.
        if (Binder.getCallingPid() == Process.myPid()) {
            return true;
        }

        if (mContext.checkCallingPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }

        if (checkInstrumentationSource) {
            final ActivityManagerInternal ami =
                    LocalServices.getService(ActivityManagerInternal.class);
            Objects.requireNonNull(ami, "ActivityManagerInternal should not be null.");
            final int instrumentationUid = ami.getInstrumentationSourceUid(Binder.getCallingUid());
            if (instrumentationUid != Process.INVALID_UID) {
                // Clear the calling identity when checking if the instrumentation source has
                // permission because PackageManager will deny all permissions to some callers,
                // such as instant apps.
                final long token = Binder.clearCallingIdentity();
                try {
                    if (mContext.checkPermission(permission, -1 /*pid*/, instrumentationUid)
                            == PackageManager.PERMISSION_GRANTED) {
                        return true;
                    }
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        String msg = "Permission Denial: " + func + " from pid="
                + Binder.getCallingPid()
                + ", uid=" + Binder.getCallingUid()
                + " requires " + permission;
        Slog.w(TAG, msg);
        return false;
    }

    // Called by the heartbeat to ensure locks are not held indefinitely (for deadlock detection).
    @Override
    public void monitor() {
        synchronized (mInputFilterLock) { }
        synchronized (mAssociationsLock) { /* Test if blocked by associations lock. */}
        synchronized (mLidSwitchLock) { /* Test if blocked by lid switch lock. */ }
        synchronized (mInputMonitors) { /* Test if blocked by input monitor lock. */ }
        synchronized (mAdditionalDisplayInputPropertiesLock) { /* Test if blocked by props lock */ }
        mBatteryController.monitor();
        mNative.monitor();
    }

    // Native callback.
    @SuppressWarnings("unused")
    private void notifyConfigurationChanged(long whenNanos) {
        mWindowManagerCallbacks.notifyConfigurationChanged();
    }

    // Native callback.
    @SuppressWarnings("unused")
    private void notifyInputDevicesChanged(InputDevice[] inputDevices) {
        synchronized (mInputDevicesLock) {
            if (!mInputDevicesChangedPending) {
                mInputDevicesChangedPending = true;
                mHandler.obtainMessage(MSG_DELIVER_INPUT_DEVICES_CHANGED,
                        mInputDevices).sendToTarget();
            }

            mInputDevices = inputDevices;
        }
    }

    // Native callback.
    @SuppressWarnings("unused")
    private void notifySwitch(long whenNanos, int switchValues, int switchMask) {
        if (DEBUG) {
            Slog.d(TAG, "notifySwitch: values=" + Integer.toHexString(switchValues)
                    + ", mask=" + Integer.toHexString(switchMask));
        }

        if ((switchMask & SW_LID_BIT) != 0) {
            final boolean lidOpen = ((switchValues & SW_LID_BIT) == 0);
            synchronized (mLidSwitchLock) {
                if (mSystemReady) {
                    for (int i = 0; i < mLidSwitchCallbacks.size(); i++) {
                        LidSwitchCallback callbacks = mLidSwitchCallbacks.get(i);
                        callbacks.notifyLidSwitchChanged(whenNanos, lidOpen);
                    }
                }
            }
        }

        if ((switchMask & SW_CAMERA_LENS_COVER_BIT) != 0) {
            final boolean lensCovered = ((switchValues & SW_CAMERA_LENS_COVER_BIT) != 0);
            mWindowManagerCallbacks.notifyCameraLensCoverSwitchChanged(whenNanos, lensCovered);
            // Use SW_CAMERA_LENS_COVER code for camera privacy toggles
            setSensorPrivacy(Sensors.CAMERA, lensCovered);
        }

        if (mUseDevInputEventForAudioJack && (switchMask & SW_JACK_BITS) != 0) {
            mWiredAccessoryCallbacks.notifyWiredAccessoryChanged(whenNanos, switchValues,
                    switchMask);
        }

        if ((switchMask & SW_TABLET_MODE_BIT) != 0) {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = (int) (whenNanos & 0xFFFFFFFF);
            args.argi2 = (int) (whenNanos >> 32);
            args.arg1 = (switchValues & SW_TABLET_MODE_BIT) != 0;
            mHandler.obtainMessage(MSG_DELIVER_TABLET_MODE_CHANGED,
                    args).sendToTarget();
        }

        if ((switchMask & SW_MUTE_DEVICE_BIT) != 0) {
            final boolean micMute = ((switchValues & SW_MUTE_DEVICE_BIT) != 0);
            AudioManager audioManager = mContext.getSystemService(AudioManager.class);
            audioManager.setMicrophoneMuteFromSwitch(micMute);

            setSensorPrivacy(Sensors.MICROPHONE, micMute);
        }
    }

    // Set the sensor privacy state based on the hardware toggles switch states
    private void setSensorPrivacy(@SensorPrivacyManager.Sensors.Sensor int sensor,
            boolean enablePrivacy) {
        final SensorPrivacyManagerInternal sensorPrivacyManagerInternal =
                LocalServices.getService(SensorPrivacyManagerInternal.class);
        sensorPrivacyManagerInternal.setPhysicalToggleSensorPrivacy(UserHandle.USER_CURRENT, sensor,
                enablePrivacy);
    }

    // Native callback.
    @SuppressWarnings("unused")
    private void notifyInputChannelBroken(IBinder token) {
        synchronized (mInputMonitors) {
            if (mInputMonitors.containsKey(token)) {
                removeSpyWindowGestureMonitor(token);
            }
        }
        mWindowManagerCallbacks.notifyInputChannelBroken(token);
    }

    // Native callback
    @SuppressWarnings("unused")
    private void notifyFocusChanged(IBinder oldToken, IBinder newToken) {
        mWindowManagerCallbacks.notifyFocusChanged(oldToken, newToken);
    }

    // Native callback
    @SuppressWarnings("unused")
    private void notifyDropWindow(IBinder token, float x, float y) {
        mWindowManagerCallbacks.notifyDropWindow(token, x, y);
    }

    // Native callback.
    @SuppressWarnings("unused")
    private void notifyNoFocusedWindowAnr(InputApplicationHandle inputApplicationHandle) {
        mWindowManagerCallbacks.notifyNoFocusedWindowAnr(inputApplicationHandle);
    }

    // Native callback
    @SuppressWarnings("unused")
    private void notifyWindowUnresponsive(IBinder token, int pid, boolean isPidValid,
            String reason) {
        mWindowManagerCallbacks.notifyWindowUnresponsive(token,
                isPidValid ? OptionalInt.of(pid) : OptionalInt.empty(), reason);
    }

    // Native callback
    @SuppressWarnings("unused")
    private void notifyWindowResponsive(IBinder token, int pid, boolean isPidValid) {
        mWindowManagerCallbacks.notifyWindowResponsive(token,
                isPidValid ? OptionalInt.of(pid) : OptionalInt.empty());
    }

    // Native callback.
    @SuppressWarnings("unused")
    private void notifySensorEvent(int deviceId, int sensorType, int accuracy, long timestamp,
            float[] values) {
        if (DEBUG) {
            Slog.d(TAG, "notifySensorEvent: deviceId=" + deviceId + " sensorType="
                    + sensorType + " values=" + Arrays.toString(values));
        }
        mSensorEventListenersToNotify.clear();
        final int numListeners;
        synchronized (mSensorEventLock) {
            numListeners = mSensorEventListeners.size();
            for (int i = 0; i < numListeners; i++) {
                mSensorEventListenersToNotify.add(
                        mSensorEventListeners.valueAt(i));
            }
        }
        for (int i = 0; i < numListeners; i++) {
            mSensorEventListenersToNotify.get(i).notifySensorEvent(deviceId, sensorType,
                    accuracy, timestamp, values);
        }
        mSensorEventListenersToNotify.clear();
    }

    // Native callback.
    @SuppressWarnings("unused")
    private void notifySensorAccuracy(int deviceId, int sensorType, int accuracy) {
        mSensorAccuracyListenersToNotify.clear();
        final int numListeners;
        synchronized (mSensorEventLock) {
            numListeners = mSensorEventListeners.size();
            for (int i = 0; i < numListeners; i++) {
                mSensorAccuracyListenersToNotify.add(mSensorEventListeners.valueAt(i));
            }
        }
        for (int i = 0; i < numListeners; i++) {
            mSensorAccuracyListenersToNotify.get(i).notifySensorAccuracy(
                    deviceId, sensorType, accuracy);
        }
        mSensorAccuracyListenersToNotify.clear();
    }

    // Native callback.
    @SuppressWarnings("unused")
    final boolean filterInputEvent(InputEvent event, int policyFlags) {
        synchronized (mInputFilterLock) {
            if (mInputFilter != null) {
                try {
                    mInputFilter.filterInputEvent(event, policyFlags);
                } catch (RemoteException e) {
                    /* ignore */
                }
                return false;
            }
        }
        event.recycle();
        return true;
    }

    // Native callback.
    @SuppressWarnings("unused")
    private int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags) {
        synchronized (mFocusEventDebugViewLock) {
            if (mFocusEventDebugView != null) {
                mFocusEventDebugView.reportKeyEvent(event);
            }
        }
        return mWindowManagerCallbacks.interceptKeyBeforeQueueing(event, policyFlags);
    }

    // Native callback.
    @SuppressWarnings("unused")
    private int interceptMotionBeforeQueueingNonInteractive(int displayId,
            int source, int action, long whenNanos, int policyFlags) {
        return mWindowManagerCallbacks.interceptMotionBeforeQueueingNonInteractive(
                displayId, source, action, whenNanos, policyFlags);
    }

    // Native callback.
    @SuppressWarnings("unused")
    private long interceptKeyBeforeDispatching(IBinder focus, KeyEvent event, int policyFlags) {
        return mWindowManagerCallbacks.interceptKeyBeforeDispatching(focus, event, policyFlags);
    }

    // Native callback.
    @SuppressWarnings("unused")
    private KeyEvent dispatchUnhandledKey(IBinder focus, KeyEvent event, int policyFlags) {
        return mWindowManagerCallbacks.dispatchUnhandledKey(focus, event, policyFlags);
    }

    // Native callback.
    @SuppressWarnings("unused")
    private void onPointerDownOutsideFocus(IBinder touchedToken) {
        mWindowManagerCallbacks.onPointerDownOutsideFocus(touchedToken);
    }

    // Native callback.
    @SuppressWarnings("unused")
    private int getVirtualKeyQuietTimeMillis() {
        return mContext.getResources().getInteger(
                com.android.internal.R.integer.config_virtualKeyQuietTimeMillis);
    }

    // Native callback.
    @SuppressWarnings("unused")
    private static String[] getExcludedDeviceNames() {
        List<String> names = new ArrayList<>();
        // Read partner-provided list of excluded input devices
        // Environment.getRootDirectory() is a fancy way of saying ANDROID_ROOT or "/system".
        final File[] baseDirs = {
            Environment.getRootDirectory(),
            Environment.getVendorDirectory()
        };
        for (File baseDir: baseDirs) {
            File confFile = new File(baseDir, EXCLUDED_DEVICES_PATH);
            try (InputStream stream = new FileInputStream(confFile)) {
                names.addAll(ConfigurationProcessor.processExcludedDeviceNames(stream));
            } catch (FileNotFoundException e) {
                // It's ok if the file does not exist.
            } catch (Exception e) {
                Slog.e(TAG, "Could not parse '" + confFile.getAbsolutePath() + "'", e);
            }
        }
        return names.toArray(new String[0]);
    }

    // Native callback.
    @SuppressWarnings("unused")
    private boolean isPerDisplayTouchModeEnabled() {
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_perDisplayFocusEnabled);
    }

    // Native callback.
    @SuppressWarnings("unused")
    private void notifyStylusGestureStarted(int deviceId, long eventTime) {
        mBatteryController.notifyStylusGestureStarted(deviceId, eventTime);
    }

    /**
     * Flatten a map into a string list, with value positioned directly next to the
     * key.
     * @return Flattened list
     */
    private static <T> String[] flatten(@NonNull Map<String, T> map) {
        final List<String> list = new ArrayList<>(map.size() * 2);
        map.forEach((k, v)-> {
            list.add(k);
            list.add(v.toString());
        });
        return list.toArray(new String[0]);
    }

    /**
     * Ports are highly platform-specific, so allow these to be specified in the odm/vendor
     * directory.
     */
    private static Map<String, Integer> loadStaticInputPortAssociations() {
        File baseDir = Environment.getOdmDirectory();
        File confFile = new File(baseDir, PORT_ASSOCIATIONS_PATH);

        if (!confFile.exists()) {
            baseDir = Environment.getVendorDirectory();
            confFile = new File(baseDir, PORT_ASSOCIATIONS_PATH);
        }

        try (final InputStream stream = new FileInputStream(confFile)) {
            return ConfigurationProcessor.processInputPortAssociations(stream);
        } catch (FileNotFoundException e) {
            // Most of the time, file will not exist, which is expected.
        } catch (Exception e) {
            Slog.e(TAG, "Could not parse '" + confFile.getAbsolutePath() + "'", e);
        }

        return new HashMap<>();
    }

    // Native callback
    @SuppressWarnings("unused")
    private String[] getInputPortAssociations() {
        final Map<String, Integer> associations = new HashMap<>(mStaticAssociations);

        // merge the runtime associations.
        synchronized (mAssociationsLock) {
            associations.putAll(mRuntimeAssociations);
        }

        return flatten(associations);
    }

    // Native callback
    @SuppressWarnings("unused")
    private String[] getInputUniqueIdAssociations() {
        final Map<String, String> associations;
        synchronized (mAssociationsLock) {
            associations = new HashMap<>(mUniqueIdAssociations);
        }

        return flatten(associations);
    }

    // Native callback
    @SuppressWarnings("unused")
    @VisibleForTesting
    String[] getDeviceTypeAssociations() {
        final Map<String, String> associations;
        synchronized (mAssociationsLock) {
            associations = new HashMap<>(mDeviceTypeAssociations);
        }

        return flatten(associations);
    }

    // Native callback
    @SuppressWarnings("unused")
    @VisibleForTesting
    private String[] getKeyboardLayoutAssociations() {
        final Map<String, String> configs = new ArrayMap<>();
        synchronized (mAssociationsLock) {
            configs.putAll(mKeyboardLayoutAssociations);
        }
        return flatten(configs);
    }

    /**
     * Gets if an input device could dispatch to the given display".
     * @param deviceId The input device id.
     * @param displayId The specific display id.
     * @return True if the device could dispatch to the given display, false otherwise.
     */
    public boolean canDispatchToDisplay(int deviceId, int displayId) {
        return mNative.canDispatchToDisplay(deviceId, displayId);
    }

    // Native callback.
    @SuppressWarnings("unused")
    private int getHoverTapTimeout() {
        return ViewConfiguration.getHoverTapTimeout();
    }

    // Native callback.
    @SuppressWarnings("unused")
    private int getHoverTapSlop() {
        return ViewConfiguration.getHoverTapSlop();
    }

    // Native callback.
    @SuppressWarnings("unused")
    private int getDoubleTapTimeout() {
        return ViewConfiguration.getDoubleTapTimeout();
    }

    // Native callback.
    @SuppressWarnings("unused")
    private int getLongPressTimeout() {
        return ViewConfiguration.getLongPressTimeout();
    }

    // Native callback.
    @SuppressWarnings("unused")
    private int getPointerLayer() {
        return mWindowManagerCallbacks.getPointerLayer();
    }

    // Native callback.
    @SuppressWarnings("unused")
    private @NonNull PointerIcon getLoadedPointerIcon(int displayId, int type) {
        synchronized (mLoadedPointerIconsByDisplayAndType) {
            SparseArray<PointerIcon> iconsByType = mLoadedPointerIconsByDisplayAndType.get(
                    displayId);
            if (iconsByType == null) {
                iconsByType = new SparseArray<>();
                mLoadedPointerIconsByDisplayAndType.put(displayId, iconsByType);
            }
            PointerIcon icon = iconsByType.get(type);
            if (icon == null) {
                icon = PointerIcon.getLoadedSystemIcon(getContextForPointerIcon(displayId), type,
                        mUseLargePointerIcons);
                iconsByType.put(type, icon);
            }
            return Objects.requireNonNull(icon);
        }
    }

    // Native callback.
    @SuppressWarnings("unused")
    private long getParentSurfaceForPointers(int displayId) {
        final SurfaceControl sc = mWindowManagerCallbacks.getParentSurfaceForPointers(displayId);
        if (sc == null) {
            return 0;
        }
        return sc.mNativeObject;
    }

    @NonNull
    private Context getContextForPointerIcon(int displayId) {
        if (mPointerIconDisplayContext != null
                && mPointerIconDisplayContext.getDisplay().getDisplayId() == displayId) {
            return mPointerIconDisplayContext;
        }

        // Create and cache context for non-default display.
        mPointerIconDisplayContext = getContextForDisplay(displayId);

        // Fall back to default display if the requested displayId does not exist.
        if (mPointerIconDisplayContext == null) {
            mPointerIconDisplayContext = getContextForDisplay(Display.DEFAULT_DISPLAY);
        }
        return mPointerIconDisplayContext;
    }

    @Nullable
    private Context getContextForDisplay(int displayId) {
        if (displayId == Display.INVALID_DISPLAY) {
            return null;
        }
        if (mContext.getDisplay().getDisplayId() == displayId) {
            return mContext;
        }

        final DisplayManager displayManager = Objects.requireNonNull(
                mContext.getSystemService(DisplayManager.class));
        final Display display = displayManager.getDisplay(displayId);
        if (display == null) {
            return null;
        }

        return mContext.createDisplayContext(display);
    }

    // Native callback.
    @SuppressWarnings("unused")
    private String[] getKeyboardLayoutOverlay(InputDeviceIdentifier identifier, String languageTag,
            String layoutType) {
        if (!mSystemReady) {
            return null;
        }
        return mKeyboardLayoutManager.getKeyboardLayoutOverlay(identifier, languageTag, layoutType);
    }

    @EnforcePermission(Manifest.permission.REMAP_MODIFIER_KEYS)
    @Override // Binder call
    public void remapModifierKey(int fromKey, int toKey) {
        super.remapModifierKey_enforcePermission();
        mKeyRemapper.remapKey(fromKey, toKey);
    }

    @EnforcePermission(Manifest.permission.REMAP_MODIFIER_KEYS)
    @Override // Binder call
    public void clearAllModifierKeyRemappings() {
        super.clearAllModifierKeyRemappings_enforcePermission();
        mKeyRemapper.clearAllKeyRemappings();
    }

    @EnforcePermission(Manifest.permission.REMAP_MODIFIER_KEYS)
    @Override // Binder call
    public Map<Integer, Integer> getModifierKeyRemapping() {
        super.getModifierKeyRemapping_enforcePermission();
        return mKeyRemapper.getKeyRemapping();
    }

    // Native callback.
    @SuppressWarnings("unused")
    private String getDeviceAlias(String uniqueId) {
        if (BluetoothAdapter.checkBluetoothAddress(uniqueId)) {
            // TODO(BT) mBluetoothService.getRemoteAlias(uniqueId)
            return null;
        }
        return null;
    }

    private static class PointerDisplayIdChangedArgs {
        final int mPointerDisplayId;
        final float mXPosition;
        final float mYPosition;
        PointerDisplayIdChangedArgs(int pointerDisplayId, float xPosition, float yPosition) {
            mPointerDisplayId = pointerDisplayId;
            mXPosition = xPosition;
            mYPosition = yPosition;
        }
    }

    // Native callback.
    @SuppressWarnings("unused")
    @VisibleForTesting
    void onPointerDisplayIdChanged(int pointerDisplayId, float xPosition, float yPosition) {
        mHandler.obtainMessage(MSG_POINTER_DISPLAY_ID_CHANGED,
                new PointerDisplayIdChangedArgs(pointerDisplayId, xPosition,
                        yPosition)).sendToTarget();
    }

    @Override
    @EnforcePermission(Manifest.permission.MONITOR_STICKY_MODIFIER_STATE)
    public void registerStickyModifierStateListener(
            @NonNull IStickyModifierStateListener listener) {
        super.registerStickyModifierStateListener_enforcePermission();
        Objects.requireNonNull(listener);
        mStickyModifierStateController.registerStickyModifierStateListener(listener,
                Binder.getCallingPid());
    }

    @Override
    @EnforcePermission(Manifest.permission.MONITOR_STICKY_MODIFIER_STATE)
    public void unregisterStickyModifierStateListener(
            @NonNull IStickyModifierStateListener listener) {
        super.unregisterStickyModifierStateListener_enforcePermission();
        Objects.requireNonNull(listener);
        mStickyModifierStateController.unregisterStickyModifierStateListener(listener,
                Binder.getCallingPid());
    }

    // Native callback
    @SuppressWarnings("unused")
    void notifyStickyModifierStateChanged(int modifierState, int lockedModifierState) {
        mStickyModifierStateController.notifyStickyModifierStateChanged(modifierState,
                lockedModifierState);
    }

    // Native callback.
    @SuppressWarnings("unused")
    boolean isInputMethodConnectionActive() {
        return mInputMethodManagerInternal != null
                && mInputMethodManagerInternal.isAnyInputConnectionActive();
    }

    /**
     * Callback interface implemented by the Window Manager.
     */
    public interface WindowManagerCallbacks extends LidSwitchCallback {
        /**
         * This callback is invoked when the configuration changes.
         */
        void notifyConfigurationChanged();

        /**
         * This callback is invoked when the pointer location changes.
         */
        void notifyPointerLocationChanged(boolean pointerLocationEnabled);

        /**
         * This callback is invoked when the camera lens cover switch changes state.
         * @param whenNanos the time when the change occurred
         * @param lensCovered true is the lens is covered
         */
        void notifyCameraLensCoverSwitchChanged(long whenNanos, boolean lensCovered);

        /**
         * This callback is invoked when an input channel is closed unexpectedly.
         * @param token the connection token of the broken channel
         */
        void notifyInputChannelBroken(IBinder token);

        /**
         * Notify the window manager about the focused application that does not have any focused
         * window and is unable to respond to focused input events.
         */
        void notifyNoFocusedWindowAnr(InputApplicationHandle applicationHandle);

        /**
         * Notify the window manager about a window that is unresponsive.
         *
         * @param token the token that can be used to look up the window
         * @param pid the pid of the window owner, if known
         * @param reason the reason why this connection is unresponsive
         */
        void notifyWindowUnresponsive(@NonNull IBinder token, @NonNull OptionalInt pid,
                @NonNull String reason);

        /**
         * Notify the window manager about a window that has become responsive.
         *
         * @param token the token that can be used to look up the window
         * @param pid the pid of the window owner, if known
         */
        void notifyWindowResponsive(@NonNull IBinder token, @NonNull OptionalInt pid);

        /**
         * This callback is invoked when an event first arrives to InputDispatcher and before it is
         * placed onto InputDispatcher's queue. If this event is intercepted, it will never be
         * processed by InputDispacher.
         * @param event The key event that's arriving to InputDispatcher
         * @param policyFlags The policy flags
         * @return the flags that tell InputDispatcher how to handle the event (for example, whether
         * to pass it to the user)
         */
        int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags);

        /**
         * Provides an opportunity for the window manager policy to intercept early motion event
         * processing when the device is in a non-interactive state since these events are normally
         * dropped.
         */
        int interceptMotionBeforeQueueingNonInteractive(int displayId, int source, int action,
                long whenNanos, int policyFlags);

        /**
         * This callback is invoked just before the key is about to be sent to an application.
         * This allows the policy to make some last minute decisions on whether to intercept this
         * key.
         * @param token the window token that's about to receive this event
         * @param event the key event that's being dispatched
         * @param policyFlags the policy flags
         * @return negative value if the key should be skipped (not sent to the app). 0 if the key
         * should proceed getting dispatched to the app. positive value to indicate the additional
         * time delay, in nanoseconds, to wait before sending this key to the app.
         */
        long interceptKeyBeforeDispatching(IBinder token, KeyEvent event, int policyFlags);

        /**
         * Dispatch unhandled key
         */
        KeyEvent dispatchUnhandledKey(IBinder token, KeyEvent event, int policyFlags);

        int getPointerLayer();

        int getPointerDisplayId();

        /**
         * Notifies window manager that a {@link android.view.MotionEvent#ACTION_DOWN} pointer event
         * occurred on a window that did not have focus.
         *
         * @param touchedToken The token for the window that received the input event.
         */
        void onPointerDownOutsideFocus(IBinder touchedToken);

        /**
         * Called when the focused window has changed.
         */
        void notifyFocusChanged(IBinder oldToken, IBinder newToken);

        /**
         * Called when the drag over window has changed.
         */
        void notifyDropWindow(IBinder token, float x, float y);

        /**
         * Get the {@link SurfaceControl} that should be the parent for the surfaces created for
         * pointers such as the mouse cursor and touch spots for the given display.
         */
        SurfaceControl getParentSurfaceForPointers(int displayId);

        /**
         * Create a {@link SurfaceControl} that can be configured to receive input over the entire
         * display to implement a gesture monitor. The surface will not have a graphical buffer.
         * @param name the name of the gesture monitor
         * @param displayId the display to create the window in
         * @return the SurfaceControl of the new layer container surface
         */
        @Nullable
        SurfaceControl createSurfaceForGestureMonitor(String name, int displayId);

        /**
         * Notify WindowManagerService when the display of the mouse pointer changes.
         * @param displayId The display on which the mouse pointer is shown.
         * @param x The x coordinate of the mouse pointer.
         * @param y The y coordinate of the mouse pointer.
         */
        void notifyPointerDisplayIdChanged(int displayId, float x, float y);
    }

    /**
     * Callback interface implemented by WiredAccessoryObserver.
     */
    public interface WiredAccessoryCallbacks {
        /**
         * Notifies WiredAccessoryObserver that input state for wired accessories has changed
         * @param whenNanos When the wired accessories changed
         * @param switchValues The state of the switches
         * @param switchMask The mask of switches that changed
         */
        void notifyWiredAccessoryChanged(long whenNanos, int switchValues, int switchMask);

        /**
         * Notifies WiredAccessoryObserver that the system is now ready.
         */
        void systemReady();
    }

    /**
     * Private handler for the input manager.
     */
    private final class InputManagerHandler extends Handler {
        public InputManagerHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DELIVER_INPUT_DEVICES_CHANGED:
                    deliverInputDevicesChanged((InputDevice[])msg.obj);
                    break;
                case MSG_RELOAD_DEVICE_ALIASES:
                    reloadDeviceAliases();
                    break;
                case MSG_DELIVER_TABLET_MODE_CHANGED:
                    SomeArgs args = (SomeArgs) msg.obj;
                    long whenNanos = (args.argi1 & 0xFFFFFFFFL) | ((long) args.argi2 << 32);
                    boolean inTabletMode = (boolean) args.arg1;
                    deliverTabletModeChanged(whenNanos, inTabletMode);
                    break;
                case MSG_POINTER_DISPLAY_ID_CHANGED:
                    handlePointerDisplayIdChanged((PointerDisplayIdChangedArgs) msg.obj);
                    break;
            }
        }
    }

    /**
     * Hosting interface for input filters to call back into the input manager.
     */
    private final class InputFilterHost extends IInputFilterHost.Stub {
        @GuardedBy("mInputFilterLock")
        private boolean mDisconnected;

        @GuardedBy("mInputFilterLock")
        public void disconnectLocked() {
            mDisconnected = true;
        }

        @Override
        public void sendInputEvent(InputEvent event, int policyFlags) {
            if (!checkCallingPermission(android.Manifest.permission.INJECT_EVENTS,
                    "sendInputEvent()")) {
                throw new SecurityException(
                        "The INJECT_EVENTS permission is required for injecting input events.");
            }
            Objects.requireNonNull(event, "event must not be null");

            synchronized (mInputFilterLock) {
                if (!mDisconnected) {
                    mNative.injectInputEvent(event, false /* injectIntoUid */, -1 /* uid */,
                            InputManager.INJECT_INPUT_EVENT_MODE_ASYNC, 0 /* timeout */,
                            policyFlags | WindowManagerPolicy.FLAG_FILTERED);
                }
            }
        }
    }

    /**
     * Interface for the system to handle request from InputMonitors.
     */
    private final class InputMonitorHost extends IInputMonitorHost.Stub {
        private final IBinder mInputChannelToken;

        InputMonitorHost(IBinder inputChannelToken) {
            mInputChannelToken = inputChannelToken;
        }

        @Override
        public void pilferPointers() {
            mNative.pilferPointers(mInputChannelToken);
        }

        @Override
        public void dispose() {
            removeSpyWindowGestureMonitor(mInputChannelToken);
        }
    }

    private final class InputDevicesChangedListenerRecord implements DeathRecipient {
        private final int mPid;
        private final IInputDevicesChangedListener mListener;

        public InputDevicesChangedListenerRecord(int pid, IInputDevicesChangedListener listener) {
            mPid = pid;
            mListener = listener;
        }

        @Override
        public void binderDied() {
            if (DEBUG) {
                Slog.d(TAG, "Input devices changed listener for pid " + mPid + " died.");
            }
            onInputDevicesChangedListenerDied(mPid);
        }

        public void notifyInputDevicesChanged(int[] info) {
            try {
                mListener.onInputDevicesChanged(info);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify process "
                        + mPid + " that input devices changed, assuming it died.", ex);
                binderDied();
            }
        }
    }

    private final class TabletModeChangedListenerRecord implements DeathRecipient {
        private final int mPid;
        private final ITabletModeChangedListener mListener;

        public TabletModeChangedListenerRecord(int pid, ITabletModeChangedListener listener) {
            mPid = pid;
            mListener = listener;
        }

        @Override
        public void binderDied() {
            if (DEBUG) {
                Slog.d(TAG, "Tablet mode changed listener for pid " + mPid + " died.");
            }
            onTabletModeChangedListenerDied(mPid);
        }

        public void notifyTabletModeChanged(long whenNanos, boolean inTabletMode) {
            try {
                mListener.onTabletModeChanged(whenNanos, inTabletMode);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify process " + mPid +
                        " that tablet mode changed, assuming it died.", ex);
                binderDied();
            }
        }
    }

    private void onSensorEventListenerDied(int pid) {
        synchronized (mSensorEventLock) {
            mSensorEventListeners.remove(pid);
        }
    }

    private final class SensorEventListenerRecord implements DeathRecipient {
        private final int mPid;
        private final IInputSensorEventListener mListener;

        SensorEventListenerRecord(int pid, IInputSensorEventListener listener) {
            mPid = pid;
            mListener = listener;
        }

        @Override
        public void binderDied() {
            if (DEBUG) {
                Slog.d(TAG, "Sensor event listener for pid " + mPid + " died.");
            }
            onSensorEventListenerDied(mPid);
        }

        public IInputSensorEventListener getListener() {
            return mListener;
        }

        public void notifySensorEvent(int deviceId, int sensorType, int accuracy, long timestamp,
                float[] values) {
            try {
                mListener.onInputSensorChanged(deviceId, sensorType, accuracy, timestamp,
                        values);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify process " + mPid
                        + " that sensor event notified, assuming it died.", ex);
                binderDied();
            }
        }

        public void notifySensorAccuracy(int deviceId, int sensorType, int accuracy) {
            try {
                mListener.onInputSensorAccuracyChanged(deviceId, sensorType, accuracy);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify process " + mPid
                        + " that sensor accuracy notified, assuming it died.", ex);
                binderDied();
            }
        }
    }

    private final class VibratorToken implements DeathRecipient {
        public final int mDeviceId;
        public final IBinder mToken;
        public final int mTokenValue;

        public boolean mVibrating;

        public VibratorToken(int deviceId, IBinder token, int tokenValue) {
            mDeviceId = deviceId;
            mToken = token;
            mTokenValue = tokenValue;
        }

        @Override
        public void binderDied() {
            if (DEBUG) {
                Slog.d(TAG, "Vibrator token died.");
            }
            onVibratorTokenDied(this);
        }
    }

    private final class LocalService extends InputManagerInternal {
        @Override
        public void setDisplayViewports(List<DisplayViewport> viewports) {
            setDisplayViewportsInternal(viewports);
        }

        @Override
        public void setInteractive(boolean interactive) {
            mNative.setInteractive(interactive);
            mBatteryController.onInteractiveChanged(interactive);
            mKeyboardBacklightController.onInteractiveChanged(interactive);
        }

        @Override
        public void toggleCapsLock(int deviceId) {
            mNative.toggleCapsLock(deviceId);
        }

        @Override
        public void setPulseGestureEnabled(boolean enabled) {
            if (mDoubleTouchGestureEnableFile != null) {
                FileWriter writer = null;
                try {
                    writer = new FileWriter(mDoubleTouchGestureEnableFile);
                    writer.write(enabled ? "1" : "0");
                } catch (IOException e) {
                    Log.wtf(TAG, "Unable to setPulseGestureEnabled", e);
                } finally {
                    IoUtils.closeQuietly(writer);
                }
            }
        }

        @Override
        public boolean transferTouchFocus(@NonNull IBinder fromChannelToken,
                @NonNull IBinder toChannelToken) {
            return InputManagerService.this.transferTouchFocus(fromChannelToken, toChannelToken);
        }

        @Override
        public boolean setVirtualMousePointerDisplayId(int pointerDisplayId) {
            return InputManagerService.this
                    .setVirtualMousePointerDisplayIdBlocking(pointerDisplayId);
        }

        @Override
        public int getVirtualMousePointerDisplayId() {
            return InputManagerService.this.getVirtualMousePointerDisplayId();
        }

        @Override
        public PointF getCursorPosition() {
            final float[] p = mNative.getMouseCursorPosition();
            if (p == null || p.length != 2) {
                throw new IllegalStateException("Failed to get mouse cursor position");
            }
            return new PointF(p[0], p[1]);
        }

        @Override
        public void setMousePointerAccelerationEnabled(boolean enabled, int displayId) {
            InputManagerService.this.setMousePointerAccelerationEnabled(enabled, displayId);
        }

        @Override
        public void setDisplayEligibilityForPointerCapture(int displayId, boolean isEligible) {
            InputManagerService.this.setDisplayEligibilityForPointerCapture(displayId, isEligible);
        }

        @Override
        public void setPointerIconVisible(boolean visible, int displayId) {
            InputManagerService.this.setPointerIconVisible(visible, displayId);
        }

        @Override
        public void registerLidSwitchCallback(LidSwitchCallback callbacks) {
            registerLidSwitchCallbackInternal(callbacks);
        }

        @Override
        public void unregisterLidSwitchCallback(LidSwitchCallback callbacks) {
            unregisterLidSwitchCallbackInternal(callbacks);
        }

        @Override
        public InputChannel createInputChannel(String inputChannelName) {
            return InputManagerService.this.createInputChannel(inputChannelName);
        }

        @Override
        public void pilferPointers(IBinder token) {
            mNative.pilferPointers(token);
        }

        @Override
        public void onInputMethodSubtypeChangedForKeyboardLayoutMapping(@UserIdInt int userId,
                @Nullable InputMethodSubtypeHandle subtypeHandle,
                @Nullable InputMethodSubtype subtype) {
            mKeyboardLayoutManager.onInputMethodSubtypeChanged(userId, subtypeHandle, subtype);
        }

        @Override
        public void notifyUserActivity() {
            mKeyboardBacklightController.notifyUserActivity();
        }

        @Override
        public void incrementKeyboardBacklight(int deviceId) {
            mKeyboardBacklightController.incrementKeyboardBacklight(deviceId);
        }

        @Override
        public void decrementKeyboardBacklight(int deviceId) {
            mKeyboardBacklightController.decrementKeyboardBacklight(deviceId);
        }

        @Override
        public void setTypeAssociation(@NonNull String inputPort, @NonNull String type) {
            setTypeAssociationInternal(inputPort, type);
        }

        @Override
        public void unsetTypeAssociation(@NonNull String inputPort) {
            unsetTypeAssociationInternal(inputPort);
        }

        @Override
        public void addKeyboardLayoutAssociation(@NonNull String inputPort,
                @NonNull String languageTag, @NonNull String layoutType) {
            InputManagerService.this.addKeyboardLayoutAssociation(inputPort,
                    languageTag, layoutType);
        }

        @Override
        public void removeKeyboardLayoutAssociation(@NonNull String inputPort) {
            InputManagerService.this.removeKeyboardLayoutAssociation(inputPort);
        }

        @Override
        public void setStylusButtonMotionEventsEnabled(boolean enabled) {
            mNative.setStylusButtonMotionEventsEnabled(enabled);
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new InputShellCommand().exec(this, in, out, err, args, callback, resultReceiver);
    }

    private static class AdditionalDisplayInputProperties {

        static final boolean DEFAULT_POINTER_ICON_VISIBLE = true;
        static final boolean DEFAULT_MOUSE_POINTER_ACCELERATION_ENABLED = true;

        /**
         * Whether to enable mouse pointer acceleration on this display. Note that this only affects
         * pointer movements from mice (that is, pointing devices which send relative motions,
         * including trackballs and pointing sticks), not from other pointer devices such as
         * touchpads and styluses.
         */
        public boolean mousePointerAccelerationEnabled;

        // Whether the pointer icon should be visible or hidden on this display.
        public boolean pointerIconVisible;

        AdditionalDisplayInputProperties() {
            reset();
        }

        public boolean allDefaults() {
            return mousePointerAccelerationEnabled == DEFAULT_MOUSE_POINTER_ACCELERATION_ENABLED
                    && pointerIconVisible == DEFAULT_POINTER_ICON_VISIBLE;
        }

        public void reset() {
            mousePointerAccelerationEnabled = DEFAULT_MOUSE_POINTER_ACCELERATION_ENABLED;
            pointerIconVisible = DEFAULT_POINTER_ICON_VISIBLE;
        }
    }

    private void applyAdditionalDisplayInputProperties() {
        synchronized (mAdditionalDisplayInputPropertiesLock) {
            AdditionalDisplayInputProperties properties =
                    mAdditionalDisplayInputProperties.get(mRequestedPointerDisplayId);
            if (properties == null) properties = DEFAULT_ADDITIONAL_DISPLAY_INPUT_PROPERTIES;
            applyAdditionalDisplayInputPropertiesLocked(properties);
        }
    }

    @GuardedBy("mAdditionalDisplayInputPropertiesLock")
    private void applyAdditionalDisplayInputPropertiesLocked(
            AdditionalDisplayInputProperties properties) {
        // Handle changes to each of the individual properties.
        // TODO(b/293587049): This approach for updating pointer display properties is only for when
        //  PointerChoreographer is disabled. Remove this logic when PointerChoreographer is
        //  permanently enabled.

        if (properties.pointerIconVisible != mCurrentDisplayProperties.pointerIconVisible) {
            mCurrentDisplayProperties.pointerIconVisible = properties.pointerIconVisible;
            if (properties.pointerIconVisible) {
                if (mPointerIconType == PointerIcon.TYPE_CUSTOM) {
                    Objects.requireNonNull(mPointerIcon);
                    mNative.setCustomPointerIcon(mPointerIcon);
                } else {
                    mNative.setPointerIconType(mPointerIconType);
                }
            } else {
                mNative.setPointerIconType(PointerIcon.TYPE_NULL);
            }
        }

        if (properties.mousePointerAccelerationEnabled
                != mCurrentDisplayProperties.mousePointerAccelerationEnabled) {
            mCurrentDisplayProperties.mousePointerAccelerationEnabled =
                    properties.mousePointerAccelerationEnabled;
        }
    }

    private void updateAdditionalDisplayInputProperties(int displayId,
            Consumer<AdditionalDisplayInputProperties> updater) {
        synchronized (mAdditionalDisplayInputPropertiesLock) {
            AdditionalDisplayInputProperties properties =
                    mAdditionalDisplayInputProperties.get(displayId);
            if (properties == null) {
                properties = new AdditionalDisplayInputProperties();
                mAdditionalDisplayInputProperties.put(displayId, properties);
            }
            final boolean oldPointerIconVisible = properties.pointerIconVisible;
            final boolean oldMouseAccelerationEnabled = properties.mousePointerAccelerationEnabled;
            updater.accept(properties);
            if (oldPointerIconVisible != properties.pointerIconVisible) {
                mNative.setPointerIconVisibility(displayId, properties.pointerIconVisible);
            }
            if (oldMouseAccelerationEnabled != properties.mousePointerAccelerationEnabled) {
                mNative.setMousePointerAccelerationEnabled(displayId,
                        properties.mousePointerAccelerationEnabled);
            }
            if (properties.allDefaults()) {
                mAdditionalDisplayInputProperties.remove(displayId);
            }
            if (displayId != mRequestedPointerDisplayId) {
                Log.i(TAG, "Not applying additional properties for display " + displayId
                        + " because the pointer is currently targeting display "
                        + mRequestedPointerDisplayId + ".");
                return;
            }
            applyAdditionalDisplayInputPropertiesLocked(properties);
        }
    }

    void updatePointerLocationEnabled(boolean enabled) {
        mWindowManagerCallbacks.notifyPointerLocationChanged(enabled);
    }

    void updateShowKeyPresses(boolean enabled) {
        if (mShowKeyPresses == enabled) {
            return;
        }

        mShowKeyPresses = enabled;
        updateFocusEventDebugViewEnabled();

        synchronized (mFocusEventDebugViewLock) {
            if (mFocusEventDebugView != null) {
                mFocusEventDebugView.updateShowKeyPresses(enabled);
            }
        }
    }

    void updateShowRotaryInput(boolean enabled) {
        if (mShowRotaryInput == enabled) {
            return;
        }

        mShowRotaryInput = enabled;
        updateFocusEventDebugViewEnabled();

        synchronized (mFocusEventDebugViewLock) {
            if (mFocusEventDebugView != null) {
                mFocusEventDebugView.updateShowRotaryInput(enabled);
            }
        }
    }

    private void updateFocusEventDebugViewEnabled() {
        boolean enabled = mShowKeyPresses || mShowRotaryInput;
        FocusEventDebugView view;
        synchronized (mFocusEventDebugViewLock) {
            if (enabled == (mFocusEventDebugView != null)) {
                return;
            }
            if (enabled) {
                mFocusEventDebugView = new FocusEventDebugView(mContext, this);
                view = mFocusEventDebugView;
            } else {
                view = mFocusEventDebugView;
                mFocusEventDebugView = null;
            }
        }
        Objects.requireNonNull(view);

        // Interact with WM outside the lock, since the lock is part of the input hotpath.
        final WindowManager wm =
                Objects.requireNonNull(mContext.getSystemService(WindowManager.class));
        if (!enabled) {
            wm.removeView(view);
            return;
        }

        // TODO: Support multi display
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.type = WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.setTitle("FocusEventDebugView - display " + mContext.getDisplayId());
        lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;
        wm.addView(view, lp);
    }

    /**
     * Sets Accessibility bounce keys threshold in milliseconds.
     */
    public void setAccessibilityBounceKeysThreshold(int thresholdTimeMs) {
        mNative.setAccessibilityBounceKeysThreshold(thresholdTimeMs);
    }

    /**
     * Sets Accessibility slow keys threshold in milliseconds.
     */
    public void setAccessibilitySlowKeysThreshold(int thresholdTimeMs) {
        mNative.setAccessibilitySlowKeysThreshold(thresholdTimeMs);
    }

    /**
     * Sets whether Accessibility sticky keys is enabled.
     */
    public void setAccessibilityStickyKeysEnabled(boolean enabled) {
        mNative.setAccessibilityStickyKeysEnabled(enabled);
    }

    void setUseLargePointerIcons(boolean useLargeIcons) {
        synchronized (mLoadedPointerIconsByDisplayAndType) {
            if (mUseLargePointerIcons == useLargeIcons) {
                return;
            }
            mUseLargePointerIcons = useLargeIcons;
            // Clear all cached icons on all displays.
            mLoadedPointerIconsByDisplayAndType.clear();
        }
        mNative.reloadPointerIcons();
    }

    interface KeyboardBacklightControllerInterface {
        default void incrementKeyboardBacklight(int deviceId) {}
        default void decrementKeyboardBacklight(int deviceId) {}
        default void registerKeyboardBacklightListener(IKeyboardBacklightListener l, int pid) {}
        default void unregisterKeyboardBacklightListener(IKeyboardBacklightListener l, int pid) {}
        default void onInteractiveChanged(boolean isInteractive) {}
        default void notifyUserActivity() {}
        default void systemRunning() {}
        default void dump(PrintWriter pw) {}
    }
}
