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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManagerInternal;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.database.ContentObserver;
import android.graphics.PointF;
import android.hardware.SensorPrivacyManager;
import android.hardware.SensorPrivacyManager.Sensors;
import android.hardware.SensorPrivacyManagerInternal;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayViewport;
import android.hardware.input.IInputDevicesChangedListener;
import android.hardware.input.IInputManager;
import android.hardware.input.IInputSensorEventListener;
import android.hardware.input.ITabletModeChangedListener;
import android.hardware.input.InputDeviceIdentifier;
import android.hardware.input.InputManager;
import android.hardware.input.InputManagerInternal;
import android.hardware.input.InputManagerInternal.LidSwitchCallback;
import android.hardware.input.InputSensorInfo;
import android.hardware.input.KeyboardLayout;
import android.hardware.input.TouchCalibration;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.CombinedVibration;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInputConstants;
import android.os.IVibratorStateListener;
import android.os.InputEventInjectionResult;
import android.os.InputEventInjectionSync;
import android.os.LocaleList;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
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
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.text.TextUtils;
import android.util.ArrayMap;
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
import android.view.KeyEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.VerifiedInputEvent;
import android.view.ViewConfiguration;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.server.DisplayThread;
import com.android.server.LocalServices;
import com.android.server.Watchdog;
import com.android.server.policy.WindowManagerPolicy;

import libcore.io.IoUtils;
import libcore.io.Streams;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/*
 * Wraps the C++ InputManager and provides its callbacks.
 */
public class InputManagerService extends IInputManager.Stub
        implements Watchdog.Monitor {
    static final String TAG = "InputManager";
    static final boolean DEBUG = false;

    private static final String EXCLUDED_DEVICES_PATH = "etc/excluded-input-devices.xml";
    private static final String PORT_ASSOCIATIONS_PATH = "etc/input-port-associations.xml";

    // Feature flag name for the deep press feature
    private static final String DEEP_PRESS_ENABLED = "deep_press_enabled";
    // Feature flag name for the strategy to be used in VelocityTracker
    private static final String VELOCITYTRACKER_STRATEGY_PROPERTY = "velocitytracker_strategy";

    private static final int MSG_DELIVER_INPUT_DEVICES_CHANGED = 1;
    private static final int MSG_SWITCH_KEYBOARD_LAYOUT = 2;
    private static final int MSG_RELOAD_KEYBOARD_LAYOUTS = 3;
    private static final int MSG_UPDATE_KEYBOARD_LAYOUTS = 4;
    private static final int MSG_RELOAD_DEVICE_ALIASES = 5;
    private static final int MSG_DELIVER_TABLET_MODE_CHANGED = 6;

    private static final int DEFAULT_VIBRATION_MAGNITUDE = 192;

    /**
     * We know the issue and are working to fix it, so suppressing the toast to not annoy
     * dogfooders.
     *
     * TODO(b/169067926): Remove this
     */
    private static final String[] PACKAGE_BLOCKLIST_FOR_UNTRUSTED_TOUCHES_TOAST = {
            "com.snapchat.android" // b/173297887
    };

    /** TODO(b/169067926): Remove this. */
    private static final boolean UNTRUSTED_TOUCHES_TOAST = false;

    // Pointer to native input manager service object.
    private final long mPtr;

    private final Context mContext;
    private final InputManagerHandler mHandler;

    // Context cache used for loading pointer resources.
    private Context mPointerIconDisplayContext;

    private final File mDoubleTouchGestureEnableFile;

    private WindowManagerCallbacks mWindowManagerCallbacks;
    private WiredAccessoryCallbacks mWiredAccessoryCallbacks;
    private boolean mSystemReady;
    private NotificationManager mNotificationManager;

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
    private final ArrayList<InputDevice> mTempFullKeyboards =
            new ArrayList<>(); // handler thread only
    private boolean mKeyboardLayoutNotificationShown;
    private Toast mSwitchedKeyboardLayoutToast;

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
    @GuardedBy("mAssociationLock")
    private final Map<String, Integer> mRuntimeAssociations = new ArrayMap<>();
    @GuardedBy("mAssociationLock")
    private final Map<String, String> mUniqueIdAssociations = new ArrayMap<>();

    private final Object mAdditionalDisplayInputPropertiesLock = new Object();

    // Forces the MouseCursorController to target a specific display id.
    @GuardedBy("mAdditionalDisplayInputPropertiesLock")
    private int mOverriddenPointerDisplayId = Display.INVALID_DISPLAY;
    @GuardedBy("mAdditionalDisplayInputPropertiesLock")
    private final SparseArray<AdditionalDisplayInputProperties> mAdditionalDisplayInputProperties =
            new SparseArray<>();
    @GuardedBy("mAdditionalDisplayInputPropertiesLock")
    private int mIconType = PointerIcon.TYPE_NOT_SPECIFIED;
    @GuardedBy("mAdditionalDisplayInputPropertiesLock")
    private PointerIcon mIcon;


    // Holds all the registered gesture monitors that are implemented as spy windows. The spy
    // windows are mapped by their InputChannel tokens.
    @GuardedBy("mInputMonitors")
    final Map<IBinder, GestureMonitorSpyWindow> mInputMonitors = new HashMap<>();

    private static native long nativeInit(InputManagerService service,
            Context context, MessageQueue messageQueue);
    private static native void nativeStart(long ptr);
    private static native void nativeSetDisplayViewports(long ptr,
            DisplayViewport[] viewports);

    private static native int nativeGetScanCodeState(long ptr,
            int deviceId, int sourceMask, int scanCode);
    private static native int nativeGetKeyCodeState(long ptr,
            int deviceId, int sourceMask, int keyCode);
    private static native int nativeGetSwitchState(long ptr,
            int deviceId, int sourceMask, int sw);
    private static native boolean nativeHasKeys(long ptr,
            int deviceId, int sourceMask, int[] keyCodes, boolean[] keyExists);
    private static native int nativeGetKeyCodeForKeyLocation(long ptr, int deviceId,
            int locationKeyCode);
    private static native InputChannel nativeCreateInputChannel(long ptr, String name);
    private static native InputChannel nativeCreateInputMonitor(long ptr, int displayId,
            String name, int pid);
    private static native void nativeRemoveInputChannel(long ptr, IBinder connectionToken);
    private static native void nativePilferPointers(long ptr, IBinder token);
    private static native void nativeSetInputFilterEnabled(long ptr, boolean enable);
    private static native boolean nativeSetInTouchMode(long ptr, boolean inTouchMode, int pid,
            int uid, boolean hasPermission);
    private static native void nativeSetMaximumObscuringOpacityForTouch(long ptr, float opacity);
    private static native void nativeSetBlockUntrustedTouchesMode(long ptr, int mode);
    private static native int nativeInjectInputEvent(long ptr, InputEvent event,
            boolean injectIntoUid, int uid, int syncMode, int timeoutMillis, int policyFlags);
    private static native VerifiedInputEvent nativeVerifyInputEvent(long ptr, InputEvent event);
    private static native void nativeToggleCapsLock(long ptr, int deviceId);
    private static native void nativeDisplayRemoved(long ptr, int displayId);
    private static native void nativeSetInputDispatchMode(long ptr, boolean enabled, boolean frozen);
    private static native void nativeSetSystemUiLightsOut(long ptr, boolean lightsOut);
    private static native void nativeSetFocusedApplication(long ptr,
            int displayId, InputApplicationHandle application);
    private static native void nativeSetFocusedDisplay(long ptr, int displayId);
    private static native boolean nativeTransferTouchFocus(long ptr,
            IBinder fromChannelToken, IBinder toChannelToken, boolean isDragDrop);
    private static native boolean nativeTransferTouch(long ptr, IBinder destChannelToken);
    private static native void nativeSetPointerSpeed(long ptr, int speed);
    private static native void nativeSetPointerAcceleration(long ptr, float acceleration);
    private static native void nativeSetShowTouches(long ptr, boolean enabled);
    private static native void nativeSetInteractive(long ptr, boolean interactive);
    private static native void nativeReloadCalibration(long ptr);
    private static native void nativeVibrate(long ptr, int deviceId, long[] pattern,
            int[] amplitudes, int repeat, int token);
    private static native void nativeVibrateCombined(long ptr, int deviceId, long[] pattern,
            SparseArray<int[]> amplitudes, int repeat, int token);
    private static native void nativeCancelVibrate(long ptr, int deviceId, int token);
    private static native boolean nativeIsVibrating(long ptr, int deviceId);
    private static native int[] nativeGetVibratorIds(long ptr, int deviceId);
    private static native int nativeGetBatteryCapacity(long ptr, int deviceId);
    private static native int nativeGetBatteryStatus(long ptr, int deviceId);
    private static native List<Light> nativeGetLights(long ptr, int deviceId);
    private static native int nativeGetLightPlayerId(long ptr, int deviceId, int lightId);
    private static native int nativeGetLightColor(long ptr, int deviceId, int lightId);
    private static native void nativeSetLightPlayerId(long ptr, int deviceId, int lightId,
            int playerId);
    private static native void nativeSetLightColor(long ptr, int deviceId, int lightId, int color);
    private static native void nativeReloadKeyboardLayouts(long ptr);
    private static native void nativeReloadDeviceAliases(long ptr);
    private static native String nativeDump(long ptr);
    private static native void nativeMonitor(long ptr);
    private static native boolean nativeIsInputDeviceEnabled(long ptr, int deviceId);
    private static native void nativeEnableInputDevice(long ptr, int deviceId);
    private static native void nativeDisableInputDevice(long ptr, int deviceId);
    private static native void nativeSetPointerIconType(long ptr, int iconId);
    private static native void nativeReloadPointerIcons(long ptr);
    private static native void nativeSetCustomPointerIcon(long ptr, PointerIcon icon);
    private static native void nativeRequestPointerCapture(long ptr, IBinder windowToken,
            boolean enabled);
    private static native boolean nativeCanDispatchToDisplay(long ptr, int deviceId, int displayId);
    private static native void nativeNotifyPortAssociationsChanged(long ptr);
    private static native void nativeChangeUniqueIdAssociation(long ptr);
    private static native void nativeNotifyPointerDisplayIdChanged(long ptr);
    private static native void nativeSetDisplayEligibilityForPointerCapture(long ptr, int displayId,
            boolean enabled);
    private static native void nativeSetMotionClassifierEnabled(long ptr, boolean enabled);
    private static native InputSensorInfo[] nativeGetSensorList(long ptr, int deviceId);
    private static native boolean nativeFlushSensor(long ptr, int deviceId, int sensorType);
    private static native boolean nativeEnableSensor(long ptr, int deviceId, int sensorType,
            int samplingPeriodUs, int maxBatchReportLatencyUs);
    private static native void nativeDisableSensor(long ptr, int deviceId, int sensorType);
    private static native void nativeCancelCurrentTouch(long ptr);

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

    /** Switch code: Microphone. When set it is off. */
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

    private final String mVelocityTrackerStrategy;

    /** Whether to use the dev/input/event or uevent subsystem for the audio jack. */
    final boolean mUseDevInputEventForAudioJack;

    public InputManagerService(Context context) {
        this.mContext = context;
        this.mHandler = new InputManagerHandler(DisplayThread.get().getLooper());

        mStaticAssociations = loadStaticInputPortAssociations();
        mUseDevInputEventForAudioJack =
                context.getResources().getBoolean(R.bool.config_useDevInputEventForAudioJack);
        Slog.i(TAG, "Initializing input manager, mUseDevInputEventForAudioJack="
                + mUseDevInputEventForAudioJack);
        mPtr = nativeInit(this, mContext, mHandler.getLooper().getQueue());

        String doubleTouchGestureEnablePath = context.getResources().getString(
                R.string.config_doubleTouchGestureEnableFile);
        mDoubleTouchGestureEnableFile = TextUtils.isEmpty(doubleTouchGestureEnablePath) ? null :
            new File(doubleTouchGestureEnablePath);

        mVelocityTrackerStrategy = DeviceConfig.getProperty(
                NAMESPACE_INPUT_NATIVE_BOOT, VELOCITYTRACKER_STRATEGY_PROPERTY);
        LocalServices.addService(InputManagerInternal.class, new LocalService());
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
        nativeStart(mPtr);

        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);

        registerPointerSpeedSettingObserver();
        registerShowTouchesSettingObserver();
        registerAccessibilityLargePointerSettingObserver();
        registerLongPressTimeoutObserver();
        registerMaximumObscuringOpacityForTouchSettingObserver();
        registerBlockUntrustedTouchesModeSettingObserver();

        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updatePointerSpeedFromSettings();
                updateShowTouchesFromSettings();
                updateAccessibilityLargePointerFromSettings();
                updateDeepPressStatusFromSettings("user switched");
            }
        }, new IntentFilter(Intent.ACTION_USER_SWITCHED), null, mHandler);

        updatePointerSpeedFromSettings();
        updateShowTouchesFromSettings();
        updateAccessibilityLargePointerFromSettings();
        updateDeepPressStatusFromSettings("just booted");
        updateMaximumObscuringOpacityForTouchFromSettings();
        updateBlockUntrustedTouchesModeFromSettings();
    }

    // TODO(BT) Pass in parameter for bluetooth system
    public void systemRunning() {
        if (DEBUG) {
            Slog.d(TAG, "System ready.");
        }
        mNotificationManager = (NotificationManager)mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);

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
        if (micMuteState != InputManager.SWITCH_STATE_UNKNOWN) {
            setSensorPrivacy(Sensors.MICROPHONE, micMuteState != InputManager.SWITCH_STATE_OFF);
        }
        // Set the HW camera toggle switch state
        final int cameraMuteState = getSwitchState(-1 /* deviceId */, InputDevice.SOURCE_ANY,
                SW_CAMERA_LENS_COVER);
        if (cameraMuteState != InputManager.SWITCH_STATE_UNKNOWN) {
            setSensorPrivacy(Sensors.CAMERA, cameraMuteState != InputManager.SWITCH_STATE_OFF);
        }

        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateKeyboardLayouts();
            }
        }, filter, null, mHandler);

        filter = new IntentFilter(BluetoothDevice.ACTION_ALIAS_CHANGED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                reloadDeviceAliases();
            }
        }, filter, null, mHandler);

        mHandler.sendEmptyMessage(MSG_RELOAD_DEVICE_ALIASES);
        mHandler.sendEmptyMessage(MSG_UPDATE_KEYBOARD_LAYOUTS);

        if (mWiredAccessoryCallbacks != null) {
            mWiredAccessoryCallbacks.systemReady();
        }
    }

    private void reloadKeyboardLayouts() {
        if (DEBUG) {
            Slog.d(TAG, "Reloading keyboard layouts.");
        }
        nativeReloadKeyboardLayouts(mPtr);
    }

    private void reloadDeviceAliases() {
        if (DEBUG) {
            Slog.d(TAG, "Reloading device names.");
        }
        nativeReloadDeviceAliases(mPtr);
    }

    private void setDisplayViewportsInternal(List<DisplayViewport> viewports) {
        synchronized (mAdditionalDisplayInputPropertiesLock) {
            final DisplayViewport[] vArray = new DisplayViewport[viewports.size()];
            for (int i = viewports.size() - 1; i >= 0; --i) {
                vArray[i] = viewports.get(i);
            }
            nativeSetDisplayViewports(mPtr, vArray);

            if (mOverriddenPointerDisplayId != Display.INVALID_DISPLAY) {
                final AdditionalDisplayInputProperties properties =
                        mAdditionalDisplayInputProperties.get(mOverriddenPointerDisplayId);
                if (properties != null) {
                    updatePointerIconVisibleLocked(properties.pointerIconVisible);
                    updatePointerAccelerationLocked(properties.pointerAcceleration);
                    return;
                }
            }
            updatePointerIconVisibleLocked(
                    AdditionalDisplayInputProperties.DEFAULT_POINTER_ICON_VISIBLE);
            updatePointerAccelerationLocked(IInputConstants.DEFAULT_POINTER_ACCELERATION);
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
        return nativeGetKeyCodeState(mPtr, deviceId, sourceMask, keyCode);
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
        return nativeGetScanCodeState(mPtr, deviceId, sourceMask, scanCode);
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
        return nativeGetSwitchState(mPtr, deviceId, sourceMask, switchCode);
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

        return nativeHasKeys(mPtr, deviceId, sourceMask, keyCodes, keyExists);
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
        return nativeGetKeyCodeForKeyLocation(mPtr, deviceId, locationKeyCode);
    }

    /**
     * Transfer the current touch gesture to the provided window.
     *
     * @param destChannelToken The token of the window or input channel that should receive the
     * gesture
     * @return True if the transfer succeeded, false if there was no active touch gesture happening
     */
    public boolean transferTouch(IBinder destChannelToken) {
        // TODO(b/162194035): Replace this with a SPY window
        Objects.requireNonNull(destChannelToken, "destChannelToken must not be null");
        return nativeTransferTouch(mPtr, destChannelToken);
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

        return nativeCreateInputMonitor(mPtr, displayId, inputChannelName, Binder.getCallingPid());
    }

    @NonNull
    private InputChannel createSpyWindowGestureMonitor(IBinder monitorToken, String name,
            int displayId, int pid, int uid) {
        final SurfaceControl sc = mWindowManagerCallbacks.createSurfaceForGestureMonitor(name,
                displayId);
        if (sc == null) {
            throw new IllegalArgumentException(
                    "Could not create gesture monitor surface on display: " + displayId);
        }
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
            final InputChannel inputChannel =
                            createSpyWindowGestureMonitor(monitorToken, name, displayId, pid, uid);
            return new InputMonitor(inputChannel, new InputMonitorHost(inputChannel.getToken()));
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
        return nativeCreateInputChannel(mPtr, name);
    }

    /**
     * Removes an input channel.
     * @param connectionToken The input channel to unregister.
     */
    public void removeInputChannel(IBinder connectionToken) {
        Objects.requireNonNull(connectionToken, "connectionToken must not be null");
        nativeRemoveInputChannel(mPtr, connectionToken);
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

            nativeSetInputFilterEnabled(mPtr, filter != null);
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
     * @param inTouchMode true if the device is in touch mode
     * @param pid the pid of the process that requested to switch touch mode state
     * @param uid the uid of the process that requested to switch touch mode state
     * @param hasPermission if set to {@code true} then no further authorization will be performed
     * @return {@code true} if the touch mode was successfully changed, {@code false} otherwise
     */
    public boolean setInTouchMode(boolean inTouchMode, int pid, int uid, boolean hasPermission) {
        return nativeSetInTouchMode(mPtr, inTouchMode, pid, uid, hasPermission);
    }

    @Override // Binder call
    public boolean injectInputEvent(InputEvent event, int mode, int targetUid) {
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
            result = nativeInjectInputEvent(mPtr, event, injectIntoUid,
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
                // Attempt to inject into a window owned by the instrumentation source of the caller
                // because it is possible that tests adopt the identity of the shell when launching
                // activities that they would like to inject into.
                final ActivityManagerInternal ami =
                        LocalServices.getService(ActivityManagerInternal.class);
                Objects.requireNonNull(ami, "ActivityManagerInternal should not be null.");
                final int instrUid = ami.getInstrumentationSourceUid(Binder.getCallingUid());
                if (instrUid != Process.INVALID_UID && targetUid != instrUid) {
                    Slog.w(TAG, "Targeted input event was not directed at a window owned by uid "
                            + targetUid + ". Attempting to inject into window owned by "
                            + "instrumentation source uid " + instrUid + ".");
                    return injectInputEvent(event, mode, instrUid);
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
        return nativeVerifyInputEvent(mPtr, event);
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
        return nativeIsInputDeviceEnabled(mPtr, deviceId);
    }

    // Binder call
    @Override
    public void enableInputDevice(int deviceId) {
        if (!checkCallingPermission(android.Manifest.permission.DISABLE_INPUT_DEVICE,
                "enableInputDevice()")) {
            throw new SecurityException("Requires DISABLE_INPUT_DEVICE permission");
        }
        nativeEnableInputDevice(mPtr, deviceId);
    }

    // Binder call
    @Override
    public void disableInputDevice(int deviceId) {
        if (!checkCallingPermission(android.Manifest.permission.DISABLE_INPUT_DEVICE,
                "disableInputDevice()")) {
            throw new SecurityException("Requires DISABLE_INPUT_DEVICE permission");
        }
        nativeDisableInputDevice(mPtr, deviceId);
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
        int numFullKeyboardsAdded = 0;
        mTempInputDevicesChangedListenersToNotify.clear();
        mTempFullKeyboards.clear();
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

                if (!inputDevice.isVirtual() && inputDevice.isFullKeyboard()) {
                    if (!containsInputDeviceWithDescriptor(oldInputDevices,
                            inputDevice.getDescriptor())) {
                        mTempFullKeyboards.add(numFullKeyboardsAdded++, inputDevice);
                    } else {
                        mTempFullKeyboards.add(inputDevice);
                    }
                }
            }
        }

        // Notify listeners.
        for (int i = 0; i < numListeners; i++) {
            mTempInputDevicesChangedListenersToNotify.get(i).notifyInputDevicesChanged(
                    deviceIdAndGeneration);
        }
        mTempInputDevicesChangedListenersToNotify.clear();

        // Check for missing keyboard layouts.
        List<InputDevice> keyboardsMissingLayout = new ArrayList<>();
        final int numFullKeyboards = mTempFullKeyboards.size();
        synchronized (mDataStore) {
            for (int i = 0; i < numFullKeyboards; i++) {
                final InputDevice inputDevice = mTempFullKeyboards.get(i);
                String layout =
                    getCurrentKeyboardLayoutForInputDevice(inputDevice.getIdentifier());
                if (layout == null) {
                    layout = getDefaultKeyboardLayout(inputDevice);
                    if (layout != null) {
                        setCurrentKeyboardLayoutForInputDevice(
                                inputDevice.getIdentifier(), layout);
                    }
                }
                if (layout == null) {
                    keyboardsMissingLayout.add(inputDevice);
                }
            }
        }

        if (mNotificationManager != null) {
            if (!keyboardsMissingLayout.isEmpty()) {
                if (keyboardsMissingLayout.size() > 1) {
                    // We have more than one keyboard missing a layout, so drop the
                    // user at the generic input methods page so they can pick which
                    // one to set.
                    showMissingKeyboardLayoutNotification(null);
                } else {
                    showMissingKeyboardLayoutNotification(keyboardsMissingLayout.get(0));
                }
            } else if (mKeyboardLayoutNotificationShown) {
                hideMissingKeyboardLayoutNotification();
            }
        }
        mTempFullKeyboards.clear();
    }

    private String getDefaultKeyboardLayout(final InputDevice d) {
        final Locale systemLocale = mContext.getResources().getConfiguration().locale;
        // If our locale doesn't have a language for some reason, then we don't really have a
        // reasonable default.
        if (TextUtils.isEmpty(systemLocale.getLanguage())) {
            return null;
        }
        final List<KeyboardLayout> layouts = new ArrayList<>();
        visitAllKeyboardLayouts((resources, keyboardLayoutResId, layout) -> {
            // Only select a default when we know the layout is appropriate. For now, this
            // means it's a custom layout for a specific keyboard.
            if (layout.getVendorId() != d.getVendorId()
                    || layout.getProductId() != d.getProductId()) {
                return;
            }
            final LocaleList locales = layout.getLocales();
            final int numLocales = locales.size();
            for (int localeIndex = 0; localeIndex < numLocales; ++localeIndex) {
                if (isCompatibleLocale(systemLocale, locales.get(localeIndex))) {
                    layouts.add(layout);
                    break;
                }
            }
        });

        if (layouts.isEmpty()) {
            return null;
        }

        // First sort so that ones with higher priority are listed at the top
        Collections.sort(layouts);
        // Next we want to try to find an exact match of language, country and variant.
        final int N = layouts.size();
        for (int i = 0; i < N; i++) {
            KeyboardLayout layout = layouts.get(i);
            final LocaleList locales = layout.getLocales();
            final int numLocales = locales.size();
            for (int localeIndex = 0; localeIndex < numLocales; ++localeIndex) {
                final Locale locale = locales.get(localeIndex);
                if (locale.getCountry().equals(systemLocale.getCountry())
                        && locale.getVariant().equals(systemLocale.getVariant())) {
                    return layout.getDescriptor();
                }
            }
        }
        // Then try an exact match of language and country
        for (int i = 0; i < N; i++) {
            KeyboardLayout layout = layouts.get(i);
            final LocaleList locales = layout.getLocales();
            final int numLocales = locales.size();
            for (int localeIndex = 0; localeIndex < numLocales; ++localeIndex) {
                final Locale locale = locales.get(localeIndex);
                if (locale.getCountry().equals(systemLocale.getCountry())) {
                    return layout.getDescriptor();
                }
            }
        }

        // Give up and just use the highest priority layout with matching language
        return layouts.get(0).getDescriptor();
    }

    private static boolean isCompatibleLocale(Locale systemLocale, Locale keyboardLocale) {
        // Different languages are never compatible
        if (!systemLocale.getLanguage().equals(keyboardLocale.getLanguage())) {
            return false;
        }
        // If both the system and the keyboard layout have a country specifier, they must be equal.
        if (!TextUtils.isEmpty(systemLocale.getCountry())
                && !TextUtils.isEmpty(keyboardLocale.getCountry())
                && !systemLocale.getCountry().equals(keyboardLocale.getCountry())) {
            return false;
        }
        return true;
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
                    nativeReloadCalibration(mPtr);
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

    // Must be called on handler.
    private void showMissingKeyboardLayoutNotification(InputDevice device) {
        if (!mKeyboardLayoutNotificationShown) {
            final Intent intent = new Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS);
            if (device != null) {
                intent.putExtra(Settings.EXTRA_INPUT_DEVICE_IDENTIFIER, device.getIdentifier());
            }
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            final PendingIntent keyboardLayoutIntent = PendingIntent.getActivityAsUser(mContext, 0,
                    intent, PendingIntent.FLAG_IMMUTABLE, null, UserHandle.CURRENT);

            Resources r = mContext.getResources();
            Notification notification =
                    new Notification.Builder(mContext, SystemNotificationChannels.PHYSICAL_KEYBOARD)
                            .setContentTitle(r.getString(
                                    R.string.select_keyboard_layout_notification_title))
                            .setContentText(r.getString(
                                    R.string.select_keyboard_layout_notification_message))
                            .setContentIntent(keyboardLayoutIntent)
                            .setSmallIcon(R.drawable.ic_settings_language)
                            .setColor(mContext.getColor(
                                    com.android.internal.R.color.system_notification_accent_color))
                            .build();
            mNotificationManager.notifyAsUser(null,
                    SystemMessage.NOTE_SELECT_KEYBOARD_LAYOUT,
                    notification, UserHandle.ALL);
            mKeyboardLayoutNotificationShown = true;
        }
    }

    // Must be called on handler.
    private void hideMissingKeyboardLayoutNotification() {
        if (mKeyboardLayoutNotificationShown) {
            mKeyboardLayoutNotificationShown = false;
            mNotificationManager.cancelAsUser(null,
                    SystemMessage.NOTE_SELECT_KEYBOARD_LAYOUT,
                    UserHandle.ALL);
        }
    }

    // Must be called on handler.
    private void updateKeyboardLayouts() {
        // Scan all input devices state for keyboard layouts that have been uninstalled.
        final HashSet<String> availableKeyboardLayouts = new HashSet<String>();
        visitAllKeyboardLayouts((resources, keyboardLayoutResId, layout) ->
                availableKeyboardLayouts.add(layout.getDescriptor()));
        synchronized (mDataStore) {
            try {
                mDataStore.removeUninstalledKeyboardLayouts(availableKeyboardLayouts);
            } finally {
                mDataStore.saveIfNeeded();
            }
        }

        // Reload keyboard layouts.
        reloadKeyboardLayouts();
    }

    private static boolean containsInputDeviceWithDescriptor(InputDevice[] inputDevices,
            String descriptor) {
        final int numDevices = inputDevices.length;
        for (int i = 0; i < numDevices; i++) {
            final InputDevice inputDevice = inputDevices[i];
            if (inputDevice.getDescriptor().equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    @Override // Binder call
    public KeyboardLayout[] getKeyboardLayouts() {
        final ArrayList<KeyboardLayout> list = new ArrayList<>();
        visitAllKeyboardLayouts((resources, keyboardLayoutResId, layout) -> list.add(layout));
        return list.toArray(new KeyboardLayout[list.size()]);
    }

    @Override // Binder call
    public KeyboardLayout[] getKeyboardLayoutsForInputDevice(
            final InputDeviceIdentifier identifier) {
        final String[] enabledLayoutDescriptors =
                getEnabledKeyboardLayoutsForInputDevice(identifier);
        final ArrayList<KeyboardLayout> enabledLayouts =
                new ArrayList<>(enabledLayoutDescriptors.length);
        final ArrayList<KeyboardLayout> potentialLayouts = new ArrayList<>();
        visitAllKeyboardLayouts(new KeyboardLayoutVisitor() {
            boolean mHasSeenDeviceSpecificLayout;

            @Override
            public void visitKeyboardLayout(Resources resources,
                    int keyboardLayoutResId, KeyboardLayout layout) {
                // First check if it's enabled. If the keyboard layout is enabled then we always
                // want to return it as a possible layout for the device.
                for (String s : enabledLayoutDescriptors) {
                    if (s != null && s.equals(layout.getDescriptor())) {
                        enabledLayouts.add(layout);
                        return;
                    }
                }
                // Next find any potential layouts that aren't yet enabled for the device. For
                // devices that have special layouts we assume there's a reason that the generic
                // layouts don't work for them so we don't want to return them since it's likely
                // to result in a poor user experience.
                if (layout.getVendorId() == identifier.getVendorId()
                        && layout.getProductId() == identifier.getProductId()) {
                    if (!mHasSeenDeviceSpecificLayout) {
                        mHasSeenDeviceSpecificLayout = true;
                        potentialLayouts.clear();
                    }
                    potentialLayouts.add(layout);
                } else if (layout.getVendorId() == -1 && layout.getProductId() == -1
                        && !mHasSeenDeviceSpecificLayout) {
                    potentialLayouts.add(layout);
                }
            }
        });
        final int enabledLayoutSize = enabledLayouts.size();
        final int potentialLayoutSize = potentialLayouts.size();
        KeyboardLayout[] layouts = new KeyboardLayout[enabledLayoutSize + potentialLayoutSize];
        enabledLayouts.toArray(layouts);
        for (int i = 0; i < potentialLayoutSize; i++) {
            layouts[enabledLayoutSize + i] = potentialLayouts.get(i);
        }
        return layouts;
    }

    @Override // Binder call
    public KeyboardLayout getKeyboardLayout(String keyboardLayoutDescriptor) {
        Objects.requireNonNull(keyboardLayoutDescriptor,
                "keyboardLayoutDescriptor must not be null");

        final KeyboardLayout[] result = new KeyboardLayout[1];
        visitKeyboardLayout(keyboardLayoutDescriptor,
                (resources, keyboardLayoutResId, layout) -> result[0] = layout);
        if (result[0] == null) {
            Slog.w(TAG, "Could not get keyboard layout with descriptor '"
                    + keyboardLayoutDescriptor + "'.");
        }
        return result[0];
    }

    private void visitAllKeyboardLayouts(KeyboardLayoutVisitor visitor) {
        final PackageManager pm = mContext.getPackageManager();
        Intent intent = new Intent(InputManager.ACTION_QUERY_KEYBOARD_LAYOUTS);
        for (ResolveInfo resolveInfo : pm.queryBroadcastReceivers(intent,
                PackageManager.GET_META_DATA | PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE)) {
            final ActivityInfo activityInfo = resolveInfo.activityInfo;
            final int priority = resolveInfo.priority;
            visitKeyboardLayoutsInPackage(pm, activityInfo, null, priority, visitor);
        }
    }

    private void visitKeyboardLayout(String keyboardLayoutDescriptor,
            KeyboardLayoutVisitor visitor) {
        KeyboardLayoutDescriptor d = KeyboardLayoutDescriptor.parse(keyboardLayoutDescriptor);
        if (d != null) {
            final PackageManager pm = mContext.getPackageManager();
            try {
                ActivityInfo receiver = pm.getReceiverInfo(
                        new ComponentName(d.packageName, d.receiverName),
                        PackageManager.GET_META_DATA
                                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
                visitKeyboardLayoutsInPackage(pm, receiver, d.keyboardLayoutName, 0, visitor);
            } catch (NameNotFoundException ignored) {
            }
        }
    }

    private void visitKeyboardLayoutsInPackage(PackageManager pm, ActivityInfo receiver,
            String keyboardName, int requestedPriority, KeyboardLayoutVisitor visitor) {
        Bundle metaData = receiver.metaData;
        if (metaData == null) {
            return;
        }

        int configResId = metaData.getInt(InputManager.META_DATA_KEYBOARD_LAYOUTS);
        if (configResId == 0) {
            Slog.w(TAG, "Missing meta-data '" + InputManager.META_DATA_KEYBOARD_LAYOUTS
                    + "' on receiver " + receiver.packageName + "/" + receiver.name);
            return;
        }

        CharSequence receiverLabel = receiver.loadLabel(pm);
        String collection = receiverLabel != null ? receiverLabel.toString() : "";
        int priority;
        if ((receiver.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            priority = requestedPriority;
        } else {
            priority = 0;
        }

        try {
            Resources resources = pm.getResourcesForApplication(receiver.applicationInfo);
            try (XmlResourceParser parser = resources.getXml(configResId)) {
                XmlUtils.beginDocument(parser, "keyboard-layouts");

                while (true) {
                    XmlUtils.nextElement(parser);
                    String element = parser.getName();
                    if (element == null) {
                        break;
                    }
                    if (element.equals("keyboard-layout")) {
                        TypedArray a = resources.obtainAttributes(
                                parser, R.styleable.KeyboardLayout);
                        try {
                            String name = a.getString(
                                    R.styleable.KeyboardLayout_name);
                            String label = a.getString(
                                    R.styleable.KeyboardLayout_label);
                            int keyboardLayoutResId = a.getResourceId(
                                    R.styleable.KeyboardLayout_keyboardLayout,
                                    0);
                            String languageTags = a.getString(
                                    R.styleable.KeyboardLayout_locale);
                            LocaleList locales = getLocalesFromLanguageTags(languageTags);
                            int vid = a.getInt(
                                    R.styleable.KeyboardLayout_vendorId, -1);
                            int pid = a.getInt(
                                    R.styleable.KeyboardLayout_productId, -1);

                            if (name == null || label == null || keyboardLayoutResId == 0) {
                                Slog.w(TAG, "Missing required 'name', 'label' or 'keyboardLayout' "
                                        + "attributes in keyboard layout "
                                        + "resource from receiver "
                                        + receiver.packageName + "/" + receiver.name);
                            } else {
                                String descriptor = KeyboardLayoutDescriptor.format(
                                        receiver.packageName, receiver.name, name);
                                if (keyboardName == null || name.equals(keyboardName)) {
                                    KeyboardLayout layout = new KeyboardLayout(
                                            descriptor, label, collection, priority,
                                            locales, vid, pid);
                                    visitor.visitKeyboardLayout(
                                            resources, keyboardLayoutResId, layout);
                                }
                            }
                        } finally {
                            a.recycle();
                        }
                    } else {
                        Slog.w(TAG, "Skipping unrecognized element '" + element
                                + "' in keyboard layout resource from receiver "
                                + receiver.packageName + "/" + receiver.name);
                    }
                }
            }
        } catch (Exception ex) {
            Slog.w(TAG, "Could not parse keyboard layout resource from receiver "
                    + receiver.packageName + "/" + receiver.name, ex);
        }
    }

    @NonNull
    private static LocaleList getLocalesFromLanguageTags(String languageTags) {
        if (TextUtils.isEmpty(languageTags)) {
            return LocaleList.getEmptyLocaleList();
        }
        return LocaleList.forLanguageTags(languageTags.replace('|', ','));
    }

    /**
     * Builds a layout descriptor for the vendor/product. This returns the
     * descriptor for ids that aren't useful (such as the default 0, 0).
     */
    private String getLayoutDescriptor(InputDeviceIdentifier identifier) {
        Objects.requireNonNull(identifier, "identifier must not be null");
        Objects.requireNonNull(identifier.getDescriptor(), "descriptor must not be null");

        if (identifier.getVendorId() == 0 && identifier.getProductId() == 0) {
            return identifier.getDescriptor();
        }
        return "vendor:" + identifier.getVendorId() + ",product:" + identifier.getProductId();
    }

    @Override // Binder call
    public String getCurrentKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier) {

        String key = getLayoutDescriptor(identifier);
        synchronized (mDataStore) {
            String layout;
            // try loading it using the layout descriptor if we have it
            layout = mDataStore.getCurrentKeyboardLayout(key);
            if (layout == null && !key.equals(identifier.getDescriptor())) {
                // if it doesn't exist fall back to the device descriptor
                layout = mDataStore.getCurrentKeyboardLayout(identifier.getDescriptor());
            }
            if (DEBUG) {
                Slog.d(TAG, "Loaded keyboard layout id for " + key + " and got "
                        + layout);
            }
            return layout;
        }
    }

    @Override // Binder call
    public void setCurrentKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            String keyboardLayoutDescriptor) {
        if (!checkCallingPermission(android.Manifest.permission.SET_KEYBOARD_LAYOUT,
                "setCurrentKeyboardLayoutForInputDevice()")) {
            throw new SecurityException("Requires SET_KEYBOARD_LAYOUT permission");
        }

        Objects.requireNonNull(keyboardLayoutDescriptor,
                "keyboardLayoutDescriptor must not be null");

        String key = getLayoutDescriptor(identifier);
        synchronized (mDataStore) {
            try {
                if (mDataStore.setCurrentKeyboardLayout(key, keyboardLayoutDescriptor)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Saved keyboard layout using " + key);
                    }
                    mHandler.sendEmptyMessage(MSG_RELOAD_KEYBOARD_LAYOUTS);
                }
            } finally {
                mDataStore.saveIfNeeded();
            }
        }
    }

    @Override // Binder call
    public String[] getEnabledKeyboardLayoutsForInputDevice(InputDeviceIdentifier identifier) {
        String key = getLayoutDescriptor(identifier);
        synchronized (mDataStore) {
            String[] layouts = mDataStore.getKeyboardLayouts(key);
            if ((layouts == null || layouts.length == 0)
                    && !key.equals(identifier.getDescriptor())) {
                layouts = mDataStore.getKeyboardLayouts(identifier.getDescriptor());
            }
            return layouts;
        }
    }

    @Override // Binder call
    public void addKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            String keyboardLayoutDescriptor) {
        if (!checkCallingPermission(android.Manifest.permission.SET_KEYBOARD_LAYOUT,
                "addKeyboardLayoutForInputDevice()")) {
            throw new SecurityException("Requires SET_KEYBOARD_LAYOUT permission");
        }
        Objects.requireNonNull(keyboardLayoutDescriptor,
                "keyboardLayoutDescriptor must not be null");

        String key = getLayoutDescriptor(identifier);
        synchronized (mDataStore) {
            try {
                String oldLayout = mDataStore.getCurrentKeyboardLayout(key);
                if (oldLayout == null && !key.equals(identifier.getDescriptor())) {
                    oldLayout = mDataStore.getCurrentKeyboardLayout(identifier.getDescriptor());
                }
                if (mDataStore.addKeyboardLayout(key, keyboardLayoutDescriptor)
                        && !Objects.equals(oldLayout,
                                mDataStore.getCurrentKeyboardLayout(key))) {
                    mHandler.sendEmptyMessage(MSG_RELOAD_KEYBOARD_LAYOUTS);
                }
            } finally {
                mDataStore.saveIfNeeded();
            }
        }
    }

    @Override // Binder call
    public void removeKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            String keyboardLayoutDescriptor) {
        if (!checkCallingPermission(android.Manifest.permission.SET_KEYBOARD_LAYOUT,
                "removeKeyboardLayoutForInputDevice()")) {
            throw new SecurityException("Requires SET_KEYBOARD_LAYOUT permission");
        }
        Objects.requireNonNull(keyboardLayoutDescriptor,
                "keyboardLayoutDescriptor must not be null");

        String key = getLayoutDescriptor(identifier);
        synchronized (mDataStore) {
            try {
                String oldLayout = mDataStore.getCurrentKeyboardLayout(key);
                if (oldLayout == null && !key.equals(identifier.getDescriptor())) {
                    oldLayout = mDataStore.getCurrentKeyboardLayout(identifier.getDescriptor());
                }
                boolean removed = mDataStore.removeKeyboardLayout(key, keyboardLayoutDescriptor);
                if (!key.equals(identifier.getDescriptor())) {
                    // We need to remove from both places to ensure it is gone
                    removed |= mDataStore.removeKeyboardLayout(identifier.getDescriptor(),
                            keyboardLayoutDescriptor);
                }
                if (removed && !Objects.equals(oldLayout,
                                mDataStore.getCurrentKeyboardLayout(key))) {
                    mHandler.sendEmptyMessage(MSG_RELOAD_KEYBOARD_LAYOUTS);
                }
            } finally {
                mDataStore.saveIfNeeded();
            }
        }
    }

    public void switchKeyboardLayout(int deviceId, int direction) {
        mHandler.obtainMessage(MSG_SWITCH_KEYBOARD_LAYOUT, deviceId, direction).sendToTarget();
    }

    // Must be called on handler.
    private void handleSwitchKeyboardLayout(int deviceId, int direction) {
        final InputDevice device = getInputDevice(deviceId);
        if (device != null) {
            final boolean changed;
            final String keyboardLayoutDescriptor;

            String key = getLayoutDescriptor(device.getIdentifier());
            synchronized (mDataStore) {
                try {
                    changed = mDataStore.switchKeyboardLayout(key, direction);
                    keyboardLayoutDescriptor = mDataStore.getCurrentKeyboardLayout(
                            key);
                } finally {
                    mDataStore.saveIfNeeded();
                }
            }

            if (changed) {
                if (mSwitchedKeyboardLayoutToast != null) {
                    mSwitchedKeyboardLayoutToast.cancel();
                    mSwitchedKeyboardLayoutToast = null;
                }
                if (keyboardLayoutDescriptor != null) {
                    KeyboardLayout keyboardLayout = getKeyboardLayout(keyboardLayoutDescriptor);
                    if (keyboardLayout != null) {
                        mSwitchedKeyboardLayoutToast = Toast.makeText(
                                mContext, keyboardLayout.getLabel(), Toast.LENGTH_SHORT);
                        mSwitchedKeyboardLayoutToast.show();
                    }
                }

                reloadKeyboardLayouts();
            }
        }
    }

    public void setFocusedApplication(int displayId, InputApplicationHandle application) {
        nativeSetFocusedApplication(mPtr, displayId, application);
    }

    public void setFocusedDisplay(int displayId) {
        nativeSetFocusedDisplay(mPtr, displayId);
    }

    /** Clean up input window handles of the given display. */
    public void onDisplayRemoved(int displayId) {
        if (mPointerIconDisplayContext != null
                && mPointerIconDisplayContext.getDisplay().getDisplayId() == displayId) {
            mPointerIconDisplayContext = null;
        }

        nativeDisplayRemoved(mPtr, displayId);
    }

    @Override
    public void requestPointerCapture(IBinder inputChannelToken, boolean enabled) {
        Objects.requireNonNull(inputChannelToken, "event must not be null");

        nativeRequestPointerCapture(mPtr, inputChannelToken, enabled);
    }

    public void setInputDispatchMode(boolean enabled, boolean frozen) {
        nativeSetInputDispatchMode(mPtr, enabled, frozen);
    }

    public void setSystemUiLightsOut(boolean lightsOut) {
        nativeSetSystemUiLightsOut(mPtr, lightsOut);
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
        return nativeTransferTouchFocus(mPtr, fromChannel.getToken(), toChannel.getToken(),
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
        Objects.nonNull(fromChannelToken);
        Objects.nonNull(toChannelToken);
        return nativeTransferTouchFocus(mPtr, fromChannelToken, toChannelToken,
                false /* isDragDrop */);
    }

    @Override // Binder call
    public void tryPointerSpeed(int speed) {
        if (!checkCallingPermission(android.Manifest.permission.SET_POINTER_SPEED,
                "tryPointerSpeed()")) {
            throw new SecurityException("Requires SET_POINTER_SPEED permission");
        }

        if (speed < InputManager.MIN_POINTER_SPEED || speed > InputManager.MAX_POINTER_SPEED) {
            throw new IllegalArgumentException("speed out of range");
        }

        setPointerSpeedUnchecked(speed);
    }

    private void updatePointerSpeedFromSettings() {
        int speed = getPointerSpeedSetting();
        setPointerSpeedUnchecked(speed);
    }

    private void setPointerSpeedUnchecked(int speed) {
        speed = Math.min(Math.max(speed, InputManager.MIN_POINTER_SPEED),
                InputManager.MAX_POINTER_SPEED);
        nativeSetPointerSpeed(mPtr, speed);
    }

    private void setPointerAcceleration(float acceleration, int displayId) {
        synchronized (mAdditionalDisplayInputPropertiesLock) {
            AdditionalDisplayInputProperties properties =
                    mAdditionalDisplayInputProperties.get(displayId);
            if (properties == null) {
                properties = new AdditionalDisplayInputProperties();
                mAdditionalDisplayInputProperties.put(displayId, properties);
            }
            properties.pointerAcceleration = acceleration;
            if (properties.allDefaults()) {
                mAdditionalDisplayInputProperties.remove(displayId);
            }
            if (mOverriddenPointerDisplayId == displayId) {
                updatePointerAccelerationLocked(acceleration);
            }
        }
    }

    @GuardedBy("mAdditionalDisplayInputPropertiesLock")
    private void updatePointerAccelerationLocked(float acceleration) {
        nativeSetPointerAcceleration(mPtr, acceleration);
    }

    private void setPointerIconVisible(boolean visible, int displayId) {
        synchronized (mAdditionalDisplayInputPropertiesLock) {
            AdditionalDisplayInputProperties properties =
                    mAdditionalDisplayInputProperties.get(displayId);
            if (properties == null) {
                properties = new AdditionalDisplayInputProperties();
                mAdditionalDisplayInputProperties.put(displayId, properties);
            }
            properties.pointerIconVisible = visible;
            if (properties.allDefaults()) {
                mAdditionalDisplayInputProperties.remove(displayId);
            }
            if (mOverriddenPointerDisplayId == displayId) {
                updatePointerIconVisibleLocked(visible);
            }
        }
    }

    @GuardedBy("mAdditionalDisplayInputPropertiesLock")
    private void updatePointerIconVisibleLocked(boolean visible) {
        if (visible) {
            if (mIconType == PointerIcon.TYPE_CUSTOM) {
                nativeSetCustomPointerIcon(mPtr, mIcon);
            } else {
                nativeSetPointerIconType(mPtr, mIconType);
            }
        } else {
            nativeSetPointerIconType(mPtr, PointerIcon.TYPE_NULL);
        }
    }

    private void registerPointerSpeedSettingObserver() {
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.POINTER_SPEED), true,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updatePointerSpeedFromSettings();
                    }
                }, UserHandle.USER_ALL);
    }

    private int getPointerSpeedSetting() {
        int speed = InputManager.DEFAULT_POINTER_SPEED;
        try {
            speed = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.POINTER_SPEED, UserHandle.USER_CURRENT);
        } catch (SettingNotFoundException ignored) {
        }
        return speed;
    }

    private void updateShowTouchesFromSettings() {
        int setting = getShowTouchesSetting(0);
        nativeSetShowTouches(mPtr, setting != 0);
    }

    private void registerShowTouchesSettingObserver() {
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SHOW_TOUCHES), true,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateShowTouchesFromSettings();
                    }
                }, UserHandle.USER_ALL);
    }

    private void updateAccessibilityLargePointerFromSettings() {
        final int accessibilityConfig = Settings.Secure.getIntForUser(
                mContext.getContentResolver(), Settings.Secure.ACCESSIBILITY_LARGE_POINTER_ICON,
                0, UserHandle.USER_CURRENT);
        PointerIcon.setUseLargeIcons(accessibilityConfig == 1);
        nativeReloadPointerIcons(mPtr);
    }

    private void registerAccessibilityLargePointerSettingObserver() {
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_LARGE_POINTER_ICON), true,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateAccessibilityLargePointerFromSettings();
                    }
                }, UserHandle.USER_ALL);
    }

    private void updateDeepPressStatusFromSettings(String reason) {
        // Not using ViewConfiguration.getLongPressTimeout here because it may return a stale value
        final int timeout = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.LONG_PRESS_TIMEOUT, ViewConfiguration.DEFAULT_LONG_PRESS_TIMEOUT,
                UserHandle.USER_CURRENT);
        final boolean featureEnabledFlag =
                DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_INPUT_NATIVE_BOOT,
                        DEEP_PRESS_ENABLED, true /* default */);
        final boolean enabled =
                featureEnabledFlag && timeout <= ViewConfiguration.DEFAULT_LONG_PRESS_TIMEOUT;
        Log.i(TAG,
                (enabled ? "Enabling" : "Disabling") + " motion classifier because " + reason
                + ": feature " + (featureEnabledFlag ? "enabled" : "disabled")
                + ", long press timeout = " + timeout);
        nativeSetMotionClassifierEnabled(mPtr, enabled);
    }

    private void registerLongPressTimeoutObserver() {
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LONG_PRESS_TIMEOUT), true,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateDeepPressStatusFromSettings("timeout changed");
                    }
                }, UserHandle.USER_ALL);
    }

    private void registerBlockUntrustedTouchesModeSettingObserver() {
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.BLOCK_UNTRUSTED_TOUCHES_MODE),
                /* notifyForDescendants */ true,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateBlockUntrustedTouchesModeFromSettings();
                    }
                }, UserHandle.USER_ALL);
    }

    private void updateBlockUntrustedTouchesModeFromSettings() {
        final int mode = InputManager.getInstance().getBlockUntrustedTouchesMode(mContext);
        nativeSetBlockUntrustedTouchesMode(mPtr, mode);
    }

    private void registerMaximumObscuringOpacityForTouchSettingObserver() {
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH),
                /* notifyForDescendants */ true,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateMaximumObscuringOpacityForTouchFromSettings();
                    }
                }, UserHandle.USER_ALL);
    }

    private void updateMaximumObscuringOpacityForTouchFromSettings() {
        final float opacity = InputManager.getInstance().getMaximumObscuringOpacityForTouch();
        if (opacity < 0 || opacity > 1) {
            Log.e(TAG, "Invalid maximum obscuring opacity " + opacity
                    + ", it should be >= 0 and <= 1, rejecting update.");
            return;
        }
        nativeSetMaximumObscuringOpacityForTouch(mPtr, opacity);
    }

    private int getShowTouchesSetting(int defaultValue) {
        int result = defaultValue;
        try {
            result = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.SHOW_TOUCHES, UserHandle.USER_CURRENT);
        } catch (SettingNotFoundException snfe) {
        }
        return result;
    }

    private void setVirtualMousePointerDisplayId(int displayId) {
        synchronized (mAdditionalDisplayInputPropertiesLock) {
            mOverriddenPointerDisplayId = displayId;
            if (displayId != Display.INVALID_DISPLAY) {
                final AdditionalDisplayInputProperties properties =
                        mAdditionalDisplayInputProperties.get(displayId);
                if (properties != null) {
                    updatePointerAccelerationLocked(properties.pointerAcceleration);
                    updatePointerIconVisibleLocked(properties.pointerIconVisible);
                }
            }
        }
        // TODO(b/215597605): trigger MousePositionTracker update
        nativeNotifyPointerDisplayIdChanged(mPtr);
    }

    private int getVirtualMousePointerDisplayId() {
        synchronized (mAdditionalDisplayInputPropertiesLock) {
            return mOverriddenPointerDisplayId;
        }
    }

    private void setDisplayEligibilityForPointerCapture(int displayId, boolean isEligible) {
        nativeSetDisplayEligibilityForPointerCapture(mPtr, displayId, isEligible);
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
            nativeVibrate(mPtr, deviceId, info.getPattern(), info.getAmplitudes(),
                    info.getRepeatIndex(), v.mTokenValue);
        }
    }

    // Binder call
    @Override
    public int[] getVibratorIds(int deviceId) {
        return nativeGetVibratorIds(mPtr, deviceId);
    }

    // Binder call
    @Override
    public boolean isVibrating(int deviceId) {
        return nativeIsVibrating(mPtr, deviceId);
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
                nativeVibrate(mPtr, deviceId, info.getPattern(), info.getAmplitudes(),
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
                nativeVibrateCombined(mPtr, deviceId, pattern, amplitudes, repeat,
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
                nativeCancelVibrate(mPtr, v.mDeviceId, v.mTokenValue);
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
    public int getBatteryStatus(int deviceId) {
        return nativeGetBatteryStatus(mPtr, deviceId);
    }

    // Binder call
    @Override
    public int getBatteryCapacity(int deviceId) {
        return nativeGetBatteryCapacity(mPtr, deviceId);
    }

    // Binder call
    @Override
    public void setPointerIconType(int iconType) {
        if (iconType == PointerIcon.TYPE_CUSTOM) {
            throw new IllegalArgumentException("Use setCustomPointerIcon to set custom pointers");
        }
        synchronized (mAdditionalDisplayInputPropertiesLock) {
            mIcon = null;
            mIconType = iconType;
            if (mOverriddenPointerDisplayId != Display.INVALID_DISPLAY) {
                final AdditionalDisplayInputProperties properties =
                        mAdditionalDisplayInputProperties.get(mOverriddenPointerDisplayId);
                if (properties == null || properties.pointerIconVisible) {
                    nativeSetPointerIconType(mPtr, mIconType);
                }
            } else {
                nativeSetPointerIconType(mPtr, mIconType);
            }
        }
    }

    // Binder call
    @Override
    public void setCustomPointerIcon(PointerIcon icon) {
        Objects.requireNonNull(icon);
        synchronized (mAdditionalDisplayInputPropertiesLock) {
            mIconType = PointerIcon.TYPE_CUSTOM;
            mIcon = icon;
            if (mOverriddenPointerDisplayId != Display.INVALID_DISPLAY) {
                final AdditionalDisplayInputProperties properties =
                        mAdditionalDisplayInputProperties.get(mOverriddenPointerDisplayId);
                if (properties == null || properties.pointerIconVisible) {
                    // Only set the icon if it is not currently hidden; otherwise, it will be set
                    // once it's no longer hidden.
                    nativeSetCustomPointerIcon(mPtr, mIcon);
                }
            } else {
                nativeSetCustomPointerIcon(mPtr, mIcon);
            }
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
        nativeNotifyPortAssociationsChanged(mPtr);
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
        nativeNotifyPortAssociationsChanged(mPtr);
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
        nativeChangeUniqueIdAssociation(mPtr);
    }

    @Override // Binder call
    public void removeUniqueIdAssociation(@NonNull String inputPort) {
        if (!checkCallingPermission(
                android.Manifest.permission.ASSOCIATE_INPUT_DEVICE_TO_DISPLAY,
                "removeUniqueIdAssociation()")) {
            throw new SecurityException(
                    "Requires ASSOCIATE_INPUT_DEVICE_TO_DISPLAY permission");
        }

        Objects.requireNonNull(inputPort);
        synchronized (mAssociationsLock) {
            mUniqueIdAssociations.remove(inputPort);
        }
        nativeChangeUniqueIdAssociation(mPtr);
    }

    @Override // Binder call
    public InputSensorInfo[] getSensorList(int deviceId) {
        return nativeGetSensorList(mPtr, deviceId);
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
                return nativeFlushSensor(mPtr, deviceId, sensorType);
            }
            return false;
        }
    }

    @Override // Binder call
    public boolean enableSensor(int deviceId, int sensorType, int samplingPeriodUs,
            int maxBatchReportLatencyUs) {
        synchronized (mInputDevicesLock) {
            return nativeEnableSensor(mPtr, deviceId, sensorType, samplingPeriodUs,
                    maxBatchReportLatencyUs);
        }
    }

    @Override // Binder call
    public void disableSensor(int deviceId, int sensorType) {
        synchronized (mInputDevicesLock) {
            nativeDisableSensor(mPtr, deviceId, sensorType);
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
        return nativeGetLights(mPtr, deviceId);
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
            nativeSetLightPlayerId(mPtr, deviceId, light.getId(), lightState.getPlayerId());
        } else {
            // Set ARGB format color to input device light
            // Refer to https://developer.android.com/reference/kotlin/android/graphics/Color
            nativeSetLightColor(mPtr, deviceId, light.getId(), lightState.getColor());
        }
    }

    /**
     * Set multiple light states with multiple light ids for a specific input device.
     */
    private void setLightStatesInternal(int deviceId, int[] lightIds, LightState[] lightStates) {
        final List<Light> lights = nativeGetLights(mPtr, deviceId);
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
            int color = nativeGetLightColor(mPtr, deviceId, lightId);
            int playerId = nativeGetLightPlayerId(mPtr, deviceId, lightId);

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

        nativeCancelCurrentTouch(mPtr);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        pw.println("INPUT MANAGER (dumpsys input)\n");
        String dumpStr = nativeDump(mPtr);
        if (dumpStr != null) {
            pw.println(dumpStr);
        }

        pw.println("Input Manager Service (Java) State:");
        dumpAssociations(pw, "  " /*prefix*/);
        dumpSpyWindowGestureMonitors(pw, "  " /*prefix*/);
        dumpDisplayInputPropertiesValues(pw, "  " /* prefix */);
    }

    private void dumpAssociations(PrintWriter pw, String prefix) {
        if (!mStaticAssociations.isEmpty()) {
            pw.println(prefix + "Static Associations:");
            mStaticAssociations.forEach((k, v) -> {
                pw.print(prefix + "  port: " + k);
                pw.println("  display: " + v);
            });
        }

        synchronized (mAssociationsLock) {
            if (!mRuntimeAssociations.isEmpty()) {
                pw.println(prefix + "Runtime Associations:");
                mRuntimeAssociations.forEach((k, v) -> {
                    pw.print(prefix + "  port: " + k);
                    pw.println("  display: " + v);
                });
            }
            if (!mUniqueIdAssociations.isEmpty()) {
                pw.println(prefix + "Unique Id Associations:");
                mUniqueIdAssociations.forEach((k, v) -> {
                    pw.print(prefix + "  port: " + k);
                    pw.println("  uniqueId: " + v);
                });
            }
        }
    }

    private void dumpSpyWindowGestureMonitors(PrintWriter pw, String prefix) {
        synchronized (mInputMonitors) {
            if (mInputMonitors.isEmpty()) return;
            pw.println(prefix + "Gesture Monitors (implemented as spy windows):");
            int i = 0;
            for (final GestureMonitorSpyWindow monitor : mInputMonitors.values()) {
                pw.append(prefix + "  " + i++ + ": ").println(monitor.dump());
            }
        }
    }

    private void dumpDisplayInputPropertiesValues(PrintWriter pw, String prefix) {
        synchronized (mAdditionalDisplayInputPropertiesLock) {
            if (mAdditionalDisplayInputProperties.size() != 0) {
                pw.println(prefix + "mAdditionalDisplayInputProperties:");
                for (int i = 0; i < mAdditionalDisplayInputProperties.size(); i++) {
                    pw.println(prefix + "  displayId: "
                            + mAdditionalDisplayInputProperties.keyAt(i));
                    final AdditionalDisplayInputProperties properties =
                            mAdditionalDisplayInputProperties.valueAt(i);
                    pw.println(prefix + "  pointerAcceleration: " + properties.pointerAcceleration);
                    pw.println(prefix + "  pointerIconVisible: " + properties.pointerIconVisible);
                }
            }
            if (mOverriddenPointerDisplayId != Display.INVALID_DISPLAY) {
                pw.println(prefix + "mOverriddenPointerDisplayId: " + mOverriddenPointerDisplayId);
            }
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
            if (instrumentationUid != Process.INVALID_UID && mContext.checkPermission(permission,
                    -1 /*pid*/, instrumentationUid) == PackageManager.PERMISSION_GRANTED) {
                return true;
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
        nativeMonitor(mPtr);
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

    // Native callback
    @SuppressWarnings("unused")
    private void notifyUntrustedTouch(String packageName) {
        // TODO(b/169067926): Remove toast after gathering feedback on dogfood.
        if (!UNTRUSTED_TOUCHES_TOAST || ArrayUtils.contains(
                PACKAGE_BLOCKLIST_FOR_UNTRUSTED_TOUCHES_TOAST, packageName)) {
            Log.i(TAG, "Suppressing untrusted touch toast for " + packageName);
            return;
        }
        DisplayThread.getHandler().post(() ->
                Toast.makeText(mContext,
                        "Touch obscured by " + packageName
                                + " will be blocked. Check go/untrusted-touches",
                        Toast.LENGTH_SHORT).show());
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
        return mWindowManagerCallbacks.interceptKeyBeforeQueueing(event, policyFlags);
    }

    // Native callback.
    @SuppressWarnings("unused")
    private int interceptMotionBeforeQueueingNonInteractive(int displayId,
            long whenNanos, int policyFlags) {
        return mWindowManagerCallbacks.interceptMotionBeforeQueueingNonInteractive(
                displayId, whenNanos, policyFlags);
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
     * Ports are highly platform-specific, so only allow these to be specified in the vendor
     * directory.
     */
    private static Map<String, Integer> loadStaticInputPortAssociations() {
        final File baseDir = Environment.getVendorDirectory();
        final File confFile = new File(baseDir, PORT_ASSOCIATIONS_PATH);

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

    /**
     * Gets if an input device could dispatch to the given display".
     * @param deviceId The input device id.
     * @param displayId The specific display id.
     * @return True if the device could dispatch to the given display, false otherwise.
     */
    public boolean canDispatchToDisplay(int deviceId, int displayId) {
        return nativeCanDispatchToDisplay(mPtr, deviceId, displayId);
    }

    // Native callback.
    @SuppressWarnings("unused")
    private int getKeyRepeatTimeout() {
        return ViewConfiguration.getKeyRepeatTimeout();
    }

    // Native callback.
    @SuppressWarnings("unused")
    private int getKeyRepeatDelay() {
        return ViewConfiguration.getKeyRepeatDelay();
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
    private PointerIcon getPointerIcon(int displayId) {
        return PointerIcon.getDefaultIcon(getContextForPointerIcon(displayId));
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
    private int getPointerDisplayId() {
        synchronized (mAdditionalDisplayInputPropertiesLock) {
            // Prefer the override to all other displays.
            if (mOverriddenPointerDisplayId != Display.INVALID_DISPLAY) {
                return mOverriddenPointerDisplayId;
            }
        }
        return mWindowManagerCallbacks.getPointerDisplayId();
    }

    // Native callback.
    @SuppressWarnings("unused")
    private String[] getKeyboardLayoutOverlay(InputDeviceIdentifier identifier) {
        if (!mSystemReady) {
            return null;
        }

        String keyboardLayoutDescriptor = getCurrentKeyboardLayoutForInputDevice(identifier);
        if (keyboardLayoutDescriptor == null) {
            return null;
        }

        final String[] result = new String[2];
        visitKeyboardLayout(keyboardLayoutDescriptor,
                (resources, keyboardLayoutResId, layout) -> {
                    try (InputStreamReader stream = new InputStreamReader(
                            resources.openRawResource(keyboardLayoutResId))) {
                        result[0] = layout.getDescriptor();
                        result[1] = Streams.readFully(stream);
                    } catch (IOException | NotFoundException ignored) {
                    }
                });
        if (result[0] == null) {
            Slog.w(TAG, "Could not get keyboard layout with descriptor '"
                    + keyboardLayoutDescriptor + "'.");
            return null;
        }
        return result;
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

    /**
     * Callback interface implemented by the Window Manager.
     */
    public interface WindowManagerCallbacks extends LidSwitchCallback {
        /**
         * This callback is invoked when the configuration changes.
         */
        void notifyConfigurationChanged();

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
        int interceptMotionBeforeQueueingNonInteractive(int displayId, long whenNanos,
                int policyFlags);

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

        /** Gets the x and y coordinates of the cursor's current position. */
        PointF getCursorPosition();

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
                case MSG_SWITCH_KEYBOARD_LAYOUT:
                    handleSwitchKeyboardLayout(msg.arg1, msg.arg2);
                    break;
                case MSG_RELOAD_KEYBOARD_LAYOUTS:
                    reloadKeyboardLayouts();
                    break;
                case MSG_UPDATE_KEYBOARD_LAYOUTS:
                    updateKeyboardLayouts();
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
                    nativeInjectInputEvent(mPtr, event, false /* injectIntoUid */, -1 /* uid */,
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
            nativePilferPointers(mPtr, mInputChannelToken);
        }

        @Override
        public void dispose() {
            removeSpyWindowGestureMonitor(mInputChannelToken);
        }
    }

    private static final class KeyboardLayoutDescriptor {
        public String packageName;
        public String receiverName;
        public String keyboardLayoutName;

        public static String format(String packageName,
                String receiverName, String keyboardName) {
            return packageName + "/" + receiverName + "/" + keyboardName;
        }

        public static KeyboardLayoutDescriptor parse(String descriptor) {
            int pos = descriptor.indexOf('/');
            if (pos < 0 || pos + 1 == descriptor.length()) {
                return null;
            }
            int pos2 = descriptor.indexOf('/', pos + 1);
            if (pos2 < pos + 2 || pos2 + 1 == descriptor.length()) {
                return null;
            }

            KeyboardLayoutDescriptor result = new KeyboardLayoutDescriptor();
            result.packageName = descriptor.substring(0, pos);
            result.receiverName = descriptor.substring(pos + 1, pos2);
            result.keyboardLayoutName = descriptor.substring(pos2 + 1);
            return result;
        }
    }

    private interface KeyboardLayoutVisitor {
        void visitKeyboardLayout(Resources resources,
                int keyboardLayoutResId, KeyboardLayout layout);
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
            nativeSetInteractive(mPtr, interactive);
        }

        @Override
        public void toggleCapsLock(int deviceId) {
            nativeToggleCapsLock(mPtr, deviceId);
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
        public void setVirtualMousePointerDisplayId(int pointerDisplayId) {
            InputManagerService.this.setVirtualMousePointerDisplayId(pointerDisplayId);
        }

        @Override
        public int getVirtualMousePointerDisplayId() {
            return InputManagerService.this.getVirtualMousePointerDisplayId();
        }

        @Override
        public PointF getCursorPosition() {
            return mWindowManagerCallbacks.getCursorPosition();
        }

        @Override
        public void setPointerAcceleration(float acceleration, int displayId) {
            InputManagerService.this.setPointerAcceleration(acceleration, displayId);
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
            nativePilferPointers(mPtr, token);
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new InputShellCommand().exec(this, in, out, err, args, callback, resultReceiver);
    }

    private static class AdditionalDisplayInputProperties {

        static final boolean DEFAULT_POINTER_ICON_VISIBLE = true;
        static final float DEFAULT_POINTER_ACCELERATION =
                (float) IInputConstants.DEFAULT_POINTER_ACCELERATION;

        // The pointer acceleration for this display.
        public float pointerAcceleration = DEFAULT_POINTER_ACCELERATION;

        // Whether the pointer icon should be visible or hidden on this display.
        public boolean pointerIconVisible = DEFAULT_POINTER_ICON_VISIBLE;

        public boolean allDefaults() {
            return Float.compare(pointerAcceleration, DEFAULT_POINTER_ACCELERATION) == 0
                    && pointerIconVisible == DEFAULT_POINTER_ICON_VISIBLE;
        }
    }
}
