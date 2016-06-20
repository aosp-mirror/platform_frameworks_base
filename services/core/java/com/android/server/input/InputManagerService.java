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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Build;
import android.os.LocaleList;
import android.util.Log;
import android.view.Display;
import com.android.internal.inputmethod.InputMethodSubtypeHandle;
import com.android.internal.os.SomeArgs;
import com.android.internal.R;
import com.android.internal.util.XmlUtils;
import com.android.server.DisplayThread;
import com.android.server.LocalServices;
import com.android.server.Watchdog;

import org.xmlpull.v1.XmlPullParser;

import android.Manifest;
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
import android.content.pm.ResolveInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.database.ContentObserver;
import android.hardware.display.DisplayViewport;
import android.hardware.input.IInputDevicesChangedListener;
import android.hardware.input.IInputManager;
import android.hardware.input.InputDeviceIdentifier;
import android.hardware.input.InputManager;
import android.hardware.input.InputManagerInternal;
import android.hardware.input.ITabletModeChangedListener;
import android.hardware.input.KeyboardLayout;
import android.hardware.input.TouchCalibration;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import android.view.IInputFilter;
import android.view.IInputFilterHost;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.ViewConfiguration;
import android.view.WindowManagerPolicy;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.Toast;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import libcore.io.IoUtils;
import libcore.io.Streams;
import libcore.util.Objects;

/*
 * Wraps the C++ InputManager and provides its callbacks.
 */
public class InputManagerService extends IInputManager.Stub
        implements Watchdog.Monitor {
    static final String TAG = "InputManager";
    static final boolean DEBUG = false;

    private static final String EXCLUDED_DEVICES_PATH = "etc/excluded-input-devices.xml";

    private static final int MSG_DELIVER_INPUT_DEVICES_CHANGED = 1;
    private static final int MSG_SWITCH_KEYBOARD_LAYOUT = 2;
    private static final int MSG_RELOAD_KEYBOARD_LAYOUTS = 3;
    private static final int MSG_UPDATE_KEYBOARD_LAYOUTS = 4;
    private static final int MSG_RELOAD_DEVICE_ALIASES = 5;
    private static final int MSG_DELIVER_TABLET_MODE_CHANGED = 6;
    private static final int MSG_INPUT_METHOD_SUBTYPE_CHANGED = 7;

    // Pointer to native input manager service object.
    private final long mPtr;

    private final Context mContext;
    private final InputManagerHandler mHandler;

    private final File mDoubleTouchGestureEnableFile;

    private WindowManagerCallbacks mWindowManagerCallbacks;
    private WiredAccessoryCallbacks mWiredAccessoryCallbacks;
    private boolean mSystemReady;
    private NotificationManager mNotificationManager;

    private final Object mTabletModeLock = new Object();
    // List of currently registered tablet mode changed listeners by process id
    private final SparseArray<TabletModeChangedListenerRecord> mTabletModeChangedListeners =
            new SparseArray<>(); // guarded by mTabletModeLock
    private final List<TabletModeChangedListenerRecord> mTempTabletModeChangedListenersToNotify =
            new ArrayList<>();

    // Persistent data store.  Must be locked each time during use.
    private final PersistentDataStore mDataStore = new PersistentDataStore();

    // List of currently registered input devices changed listeners by process id.
    private Object mInputDevicesLock = new Object();
    private boolean mInputDevicesChangedPending; // guarded by mInputDevicesLock
    private InputDevice[] mInputDevices = new InputDevice[0];
    private final SparseArray<InputDevicesChangedListenerRecord> mInputDevicesChangedListeners =
            new SparseArray<InputDevicesChangedListenerRecord>(); // guarded by mInputDevicesLock
    private final ArrayList<InputDevicesChangedListenerRecord>
            mTempInputDevicesChangedListenersToNotify =
                    new ArrayList<InputDevicesChangedListenerRecord>(); // handler thread only
    private final ArrayList<InputDevice>
            mTempFullKeyboards = new ArrayList<InputDevice>(); // handler thread only
    private boolean mKeyboardLayoutNotificationShown;
    private InputMethodSubtypeHandle mCurrentImeHandle;

    // State for vibrator tokens.
    private Object mVibratorLock = new Object();
    private HashMap<IBinder, VibratorToken> mVibratorTokens =
            new HashMap<IBinder, VibratorToken>();
    private int mNextVibratorTokenValue;

    // State for the currently installed input filter.
    final Object mInputFilterLock = new Object();
    IInputFilter mInputFilter; // guarded by mInputFilterLock
    InputFilterHost mInputFilterHost; // guarded by mInputFilterLock

    private static native long nativeInit(InputManagerService service,
            Context context, MessageQueue messageQueue);
    private static native void nativeStart(long ptr);
    private static native void nativeSetDisplayViewport(long ptr, boolean external,
            int displayId, int rotation,
            int logicalLeft, int logicalTop, int logicalRight, int logicalBottom,
            int physicalLeft, int physicalTop, int physicalRight, int physicalBottom,
            int deviceWidth, int deviceHeight);

    private static native int nativeGetScanCodeState(long ptr,
            int deviceId, int sourceMask, int scanCode);
    private static native int nativeGetKeyCodeState(long ptr,
            int deviceId, int sourceMask, int keyCode);
    private static native int nativeGetSwitchState(long ptr,
            int deviceId, int sourceMask, int sw);
    private static native boolean nativeHasKeys(long ptr,
            int deviceId, int sourceMask, int[] keyCodes, boolean[] keyExists);
    private static native void nativeRegisterInputChannel(long ptr, InputChannel inputChannel,
            InputWindowHandle inputWindowHandle, boolean monitor);
    private static native void nativeUnregisterInputChannel(long ptr, InputChannel inputChannel);
    private static native void nativeSetInputFilterEnabled(long ptr, boolean enable);
    private static native int nativeInjectInputEvent(long ptr, InputEvent event, int displayId,
            int injectorPid, int injectorUid, int syncMode, int timeoutMillis,
            int policyFlags);
    private static native void nativeToggleCapsLock(long ptr, int deviceId);
    private static native void nativeSetInputWindows(long ptr, InputWindowHandle[] windowHandles);
    private static native void nativeSetInputDispatchMode(long ptr, boolean enabled, boolean frozen);
    private static native void nativeSetSystemUiVisibility(long ptr, int visibility);
    private static native void nativeSetFocusedApplication(long ptr,
            InputApplicationHandle application);
    private static native boolean nativeTransferTouchFocus(long ptr,
            InputChannel fromChannel, InputChannel toChannel);
    private static native void nativeSetPointerSpeed(long ptr, int speed);
    private static native void nativeSetShowTouches(long ptr, boolean enabled);
    private static native void nativeSetInteractive(long ptr, boolean interactive);
    private static native void nativeReloadCalibration(long ptr);
    private static native void nativeVibrate(long ptr, int deviceId, long[] pattern,
            int repeat, int token);
    private static native void nativeCancelVibrate(long ptr, int deviceId, int token);
    private static native void nativeReloadKeyboardLayouts(long ptr);
    private static native void nativeReloadDeviceAliases(long ptr);
    private static native String nativeDump(long ptr);
    private static native void nativeMonitor(long ptr);
    private static native void nativeSetPointerIconType(long ptr, int iconId);
    private static native void nativeReloadPointerIcons(long ptr);
    private static native void nativeSetCustomPointerIcon(long ptr, PointerIcon icon);

    // Input event injection constants defined in InputDispatcher.h.
    private static final int INPUT_EVENT_INJECTION_SUCCEEDED = 0;
    private static final int INPUT_EVENT_INJECTION_PERMISSION_DENIED = 1;
    private static final int INPUT_EVENT_INJECTION_FAILED = 2;
    private static final int INPUT_EVENT_INJECTION_TIMED_OUT = 3;

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

    /** Whether to use the dev/input/event or uevent subsystem for the audio jack. */
    final boolean mUseDevInputEventForAudioJack;

    public InputManagerService(Context context) {
        this.mContext = context;
        this.mHandler = new InputManagerHandler(DisplayThread.get().getLooper());

        mUseDevInputEventForAudioJack =
                context.getResources().getBoolean(R.bool.config_useDevInputEventForAudioJack);
        Slog.i(TAG, "Initializing input manager, mUseDevInputEventForAudioJack="
                + mUseDevInputEventForAudioJack);
        mPtr = nativeInit(this, mContext, mHandler.getLooper().getQueue());

        String doubleTouchGestureEnablePath = context.getResources().getString(
                R.string.config_doubleTouchGestureEnableFile);
        mDoubleTouchGestureEnableFile = TextUtils.isEmpty(doubleTouchGestureEnablePath) ? null :
            new File(doubleTouchGestureEnablePath);

        LocalServices.addService(InputManagerInternal.class, new LocalService());
    }

    public void setWindowManagerCallbacks(WindowManagerCallbacks callbacks) {
        mWindowManagerCallbacks = callbacks;
    }

    public void setWiredAccessoryCallbacks(WiredAccessoryCallbacks callbacks) {
        mWiredAccessoryCallbacks = callbacks;
    }

    public void start() {
        Slog.i(TAG, "Starting input manager");
        nativeStart(mPtr);

        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);

        registerPointerSpeedSettingObserver();
        registerShowTouchesSettingObserver();
        registerAccessibilityLargePointerSettingObserver();

        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updatePointerSpeedFromSettings();
                updateShowTouchesFromSettings();
                updateAccessibilityLargePointerFromSettings();
            }
        }, new IntentFilter(Intent.ACTION_USER_SWITCHED), null, mHandler);

        updatePointerSpeedFromSettings();
        updateShowTouchesFromSettings();
        updateAccessibilityLargePointerFromSettings();
    }

    // TODO(BT) Pass in paramter for bluetooth system
    public void systemRunning() {
        if (DEBUG) {
            Slog.d(TAG, "System ready.");
        }
        mNotificationManager = (NotificationManager)mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);
        mSystemReady = true;

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

    private void setDisplayViewportsInternal(DisplayViewport defaultViewport,
            DisplayViewport externalTouchViewport) {
        if (defaultViewport.valid) {
            setDisplayViewport(false, defaultViewport);
        }

        if (externalTouchViewport.valid) {
            setDisplayViewport(true, externalTouchViewport);
        } else if (defaultViewport.valid) {
            setDisplayViewport(true, defaultViewport);
        }
    }

    private void setDisplayViewport(boolean external, DisplayViewport viewport) {
        nativeSetDisplayViewport(mPtr, external,
                viewport.displayId, viewport.orientation,
                viewport.logicalFrame.left, viewport.logicalFrame.top,
                viewport.logicalFrame.right, viewport.logicalFrame.bottom,
                viewport.physicalFrame.left, viewport.physicalFrame.top,
                viewport.physicalFrame.right, viewport.physicalFrame.bottom,
                viewport.deviceWidth, viewport.deviceHeight);
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
        if (keyCodes == null) {
            throw new IllegalArgumentException("keyCodes must not be null.");
        }
        if (keyExists == null || keyExists.length < keyCodes.length) {
            throw new IllegalArgumentException("keyExists must not be null and must be at "
                    + "least as large as keyCodes.");
        }

        return nativeHasKeys(mPtr, deviceId, sourceMask, keyCodes, keyExists);
    }

    /**
     * Creates an input channel that will receive all input from the input dispatcher.
     * @param inputChannelName The input channel name.
     * @return The input channel.
     */
    public InputChannel monitorInput(String inputChannelName) {
        if (inputChannelName == null) {
            throw new IllegalArgumentException("inputChannelName must not be null.");
        }

        InputChannel[] inputChannels = InputChannel.openInputChannelPair(inputChannelName);
        nativeRegisterInputChannel(mPtr, inputChannels[0], null, true);
        inputChannels[0].dispose(); // don't need to retain the Java object reference
        return inputChannels[1];
    }

    /**
     * Registers an input channel so that it can be used as an input event target.
     * @param inputChannel The input channel to register.
     * @param inputWindowHandle The handle of the input window associated with the
     * input channel, or null if none.
     */
    public void registerInputChannel(InputChannel inputChannel,
            InputWindowHandle inputWindowHandle) {
        if (inputChannel == null) {
            throw new IllegalArgumentException("inputChannel must not be null.");
        }

        nativeRegisterInputChannel(mPtr, inputChannel, inputWindowHandle, false);
    }

    /**
     * Unregisters an input channel.
     * @param inputChannel The input channel to unregister.
     */
    public void unregisterInputChannel(InputChannel inputChannel) {
        if (inputChannel == null) {
            throw new IllegalArgumentException("inputChannel must not be null.");
        }

        nativeUnregisterInputChannel(mPtr, inputChannel);
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

    @Override // Binder call
    public boolean injectInputEvent(InputEvent event, int mode) {
        return injectInputEventInternal(event, Display.DEFAULT_DISPLAY, mode);
    }

    private boolean injectInputEventInternal(InputEvent event, int displayId, int mode) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (mode != InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
                && mode != InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
                && mode != InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT) {
            throw new IllegalArgumentException("mode is invalid");
        }

        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        final int result;
        try {
            result = nativeInjectInputEvent(mPtr, event, displayId, pid, uid, mode,
                    INJECTION_TIMEOUT_MILLIS, WindowManagerPolicy.FLAG_DISABLE_KEY_REPEAT);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        switch (result) {
            case INPUT_EVENT_INJECTION_PERMISSION_DENIED:
                Slog.w(TAG, "Input event injection from pid " + pid + " permission denied.");
                throw new SecurityException(
                        "Injecting to another application requires INJECT_EVENTS permission");
            case INPUT_EVENT_INJECTION_SUCCEEDED:
                return true;
            case INPUT_EVENT_INJECTION_TIMED_OUT:
                Slog.w(TAG, "Input event injection from pid " + pid + " timed out.");
                return false;
            case INPUT_EVENT_INJECTION_FAILED:
            default:
                Slog.w(TAG, "Input event injection from pid " + pid + " failed.");
                return false;
        }
    }

    /**
     * Gets information about the input device with the specified id.
     * @param deviceId The device id.
     * @return The input device or null if not found.
     */
    @Override // Binder call
    public InputDevice getInputDevice(int deviceId) {
        synchronized (mInputDevicesLock) {
            final int count = mInputDevices.length;
            for (int i = 0; i < count; i++) {
                final InputDevice inputDevice = mInputDevices[i];
                if (inputDevice.getId() == deviceId) {
                    return inputDevice;
                }
            }
        }
        return null;
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
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

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
        visitAllKeyboardLayouts(new KeyboardLayoutVisitor() {
            @Override
            public void visitKeyboardLayout(Resources resources,
                    int keyboardLayoutResId, KeyboardLayout layout) {
                // Only select a default when we know the layout is appropriate. For now, this
                // means its a custom layout for a specific keyboard.
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
        if (inputDeviceDescriptor == null) {
            throw new IllegalArgumentException("inputDeviceDescriptor must not be null");
        }

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
        if (inputDeviceDescriptor == null) {
            throw new IllegalArgumentException("inputDeviceDescriptor must not be null");
        }
        if (calibration == null) {
            throw new IllegalArgumentException("calibration must not be null");
        }
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
    public void registerTabletModeChangedListener(ITabletModeChangedListener listener) {
        if (!checkCallingPermission(android.Manifest.permission.TABLET_MODE,
                "registerTabletModeChangedListener()")) {
            throw new SecurityException("Requires TABLET_MODE_LISTENER permission");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

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
                    intent, 0, null, UserHandle.CURRENT);

            Resources r = mContext.getResources();
            Notification notification = new Notification.Builder(mContext)
                    .setContentTitle(r.getString(
                            R.string.select_keyboard_layout_notification_title))
                    .setContentText(r.getString(
                            R.string.select_keyboard_layout_notification_message))
                    .setContentIntent(keyboardLayoutIntent)
                    .setSmallIcon(R.drawable.ic_settings_language)
                    .setPriority(Notification.PRIORITY_LOW)
                    .setColor(mContext.getColor(
                            com.android.internal.R.color.system_notification_accent_color))
                    .build();
            mNotificationManager.notifyAsUser(null,
                    R.string.select_keyboard_layout_notification_title,
                    notification, UserHandle.ALL);
            mKeyboardLayoutNotificationShown = true;
        }
    }

    // Must be called on handler.
    private void hideMissingKeyboardLayoutNotification() {
        if (mKeyboardLayoutNotificationShown) {
            mKeyboardLayoutNotificationShown = false;
            mNotificationManager.cancelAsUser(null,
                    R.string.select_keyboard_layout_notification_title,
                    UserHandle.ALL);
        }
    }

    // Must be called on handler.
    private void updateKeyboardLayouts() {
        // Scan all input devices state for keyboard layouts that have been uninstalled.
        final HashSet<String> availableKeyboardLayouts = new HashSet<String>();
        visitAllKeyboardLayouts(new KeyboardLayoutVisitor() {
            @Override
            public void visitKeyboardLayout(Resources resources,
                    int keyboardLayoutResId, KeyboardLayout layout) {
                availableKeyboardLayouts.add(layout.getDescriptor());
            }
        });
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
        final ArrayList<KeyboardLayout> list = new ArrayList<KeyboardLayout>();
        visitAllKeyboardLayouts(new KeyboardLayoutVisitor() {
            @Override
            public void visitKeyboardLayout(Resources resources,
                    int keyboardLayoutResId, KeyboardLayout layout) {
                list.add(layout);
            }
        });
        return list.toArray(new KeyboardLayout[list.size()]);
    }

    @Override // Binder call
    public KeyboardLayout[] getKeyboardLayoutsForInputDevice(
            final InputDeviceIdentifier identifier) {
        final String[] enabledLayoutDescriptors =
            getEnabledKeyboardLayoutsForInputDevice(identifier);
        final ArrayList<KeyboardLayout> enabledLayouts =
            new ArrayList<KeyboardLayout>(enabledLayoutDescriptors.length);
        final ArrayList<KeyboardLayout> potentialLayouts = new ArrayList<KeyboardLayout>();
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
        if (keyboardLayoutDescriptor == null) {
            throw new IllegalArgumentException("keyboardLayoutDescriptor must not be null");
        }

        final KeyboardLayout[] result = new KeyboardLayout[1];
        visitKeyboardLayout(keyboardLayoutDescriptor, new KeyboardLayoutVisitor() {
            @Override
            public void visitKeyboardLayout(Resources resources,
                    int keyboardLayoutResId, KeyboardLayout layout) {
                result[0] = layout;
            }
        });
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
            } catch (NameNotFoundException ex) {
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
            XmlResourceParser parser = resources.getXml(configResId);
            try {
                XmlUtils.beginDocument(parser, "keyboard-layouts");

                for (;;) {
                    XmlUtils.nextElement(parser);
                    String element = parser.getName();
                    if (element == null) {
                        break;
                    }
                    if (element.equals("keyboard-layout")) {
                        TypedArray a = resources.obtainAttributes(
                                parser, com.android.internal.R.styleable.KeyboardLayout);
                        try {
                            String name = a.getString(
                                    com.android.internal.R.styleable.KeyboardLayout_name);
                            String label = a.getString(
                                    com.android.internal.R.styleable.KeyboardLayout_label);
                            int keyboardLayoutResId = a.getResourceId(
                                    com.android.internal.R.styleable.KeyboardLayout_keyboardLayout,
                                    0);
                            String languageTags = a.getString(
                                    com.android.internal.R.styleable.KeyboardLayout_locale);
                            LocaleList locales = getLocalesFromLanguageTags(languageTags);
                            int vid = a.getInt(
                                    com.android.internal.R.styleable.KeyboardLayout_vendorId, -1);
                            int pid = a.getInt(
                                    com.android.internal.R.styleable.KeyboardLayout_productId, -1);

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
            } finally {
                parser.close();
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
        if (identifier == null || identifier.getDescriptor() == null) {
            throw new IllegalArgumentException("identifier and descriptor must not be null");
        }

        if (identifier.getVendorId() == 0 && identifier.getProductId() == 0) {
            return identifier.getDescriptor();
        }
        StringBuilder bob = new StringBuilder();
        bob.append("vendor:").append(identifier.getVendorId());
        bob.append(",product:").append(identifier.getProductId());
        return bob.toString();
    }

    @Override // Binder call
    public String getCurrentKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier) {

        String key = getLayoutDescriptor(identifier);
        synchronized (mDataStore) {
            String layout = null;
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
        if (keyboardLayoutDescriptor == null) {
            throw new IllegalArgumentException("keyboardLayoutDescriptor must not be null");
        }

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
    @Nullable
    public KeyboardLayout getKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            InputMethodInfo imeInfo, InputMethodSubtype imeSubtype) {
        InputMethodSubtypeHandle handle = new InputMethodSubtypeHandle(imeInfo, imeSubtype);
        String key = getLayoutDescriptor(identifier);
        final String keyboardLayoutDescriptor;
        synchronized (mDataStore) {
            keyboardLayoutDescriptor = mDataStore.getKeyboardLayout(key, handle);
        }

        if (keyboardLayoutDescriptor == null) {
            return null;
        }

        final KeyboardLayout[] result = new KeyboardLayout[1];
        visitKeyboardLayout(keyboardLayoutDescriptor, new KeyboardLayoutVisitor() {
            @Override
            public void visitKeyboardLayout(Resources resources,
                    int keyboardLayoutResId, KeyboardLayout layout) {
                result[0] = layout;
            }
        });
        if (result[0] == null) {
            Slog.w(TAG, "Could not get keyboard layout with descriptor '"
                    + keyboardLayoutDescriptor + "'.");
        }
        return result[0];
    }

    @Override
    public void setKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            InputMethodInfo imeInfo, InputMethodSubtype imeSubtype,
            String keyboardLayoutDescriptor) {
        if (!checkCallingPermission(android.Manifest.permission.SET_KEYBOARD_LAYOUT,
                "setKeyboardLayoutForInputDevice()")) {
            throw new SecurityException("Requires SET_KEYBOARD_LAYOUT permission");
        }
        if (keyboardLayoutDescriptor == null) {
            throw new IllegalArgumentException("keyboardLayoutDescriptor must not be null");
        }
        if (imeInfo == null) {
            throw new IllegalArgumentException("imeInfo must not be null");
        }
        InputMethodSubtypeHandle handle = new InputMethodSubtypeHandle(imeInfo, imeSubtype);
        setKeyboardLayoutForInputDeviceInner(identifier, handle, keyboardLayoutDescriptor);
    }

    private void setKeyboardLayoutForInputDeviceInner(InputDeviceIdentifier identifier,
            InputMethodSubtypeHandle imeHandle, String keyboardLayoutDescriptor) {
        String key = getLayoutDescriptor(identifier);
        synchronized (mDataStore) {
            try {
                if (mDataStore.setKeyboardLayout(key, imeHandle, keyboardLayoutDescriptor)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Set keyboard layout " + keyboardLayoutDescriptor +
                                " for subtype " + imeHandle + " and device " + identifier +
                                " using key " + key);
                    }
                    if (imeHandle.equals(mCurrentImeHandle)) {
                        if (DEBUG) {
                            Slog.d(TAG, "Layout for current subtype changed, switching layout");
                        }
                        SomeArgs args = SomeArgs.obtain();
                        args.arg1 = identifier;
                        args.arg2 = imeHandle;
                        mHandler.obtainMessage(MSG_SWITCH_KEYBOARD_LAYOUT, args).sendToTarget();
                    }
                    mHandler.sendEmptyMessage(MSG_RELOAD_KEYBOARD_LAYOUTS);
                }
            } finally {
                mDataStore.saveIfNeeded();
            }
        }
    }

    @Override // Binder call
    public void addKeyboardLayoutForInputDevice(InputDeviceIdentifier identifier,
            String keyboardLayoutDescriptor) {
        if (!checkCallingPermission(android.Manifest.permission.SET_KEYBOARD_LAYOUT,
                "addKeyboardLayoutForInputDevice()")) {
            throw new SecurityException("Requires SET_KEYBOARD_LAYOUT permission");
        }
        if (keyboardLayoutDescriptor == null) {
            throw new IllegalArgumentException("keyboardLayoutDescriptor must not be null");
        }

        String key = getLayoutDescriptor(identifier);
        synchronized (mDataStore) {
            try {
                String oldLayout = mDataStore.getCurrentKeyboardLayout(key);
                if (oldLayout == null && !key.equals(identifier.getDescriptor())) {
                    oldLayout = mDataStore.getCurrentKeyboardLayout(identifier.getDescriptor());
                }
                if (mDataStore.addKeyboardLayout(key, keyboardLayoutDescriptor)
                        && !Objects.equal(oldLayout, mDataStore.getCurrentKeyboardLayout(key))) {
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
        if (keyboardLayoutDescriptor == null) {
            throw new IllegalArgumentException("keyboardLayoutDescriptor must not be null");
        }

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
                if (removed && !Objects.equal(oldLayout,
                                mDataStore.getCurrentKeyboardLayout(key))) {
                    mHandler.sendEmptyMessage(MSG_RELOAD_KEYBOARD_LAYOUTS);
                }
            } finally {
                mDataStore.saveIfNeeded();
            }
        }
    }

    // Must be called on handler.
    private void handleSwitchInputMethodSubtype(int userId,
            @Nullable InputMethodInfo inputMethodInfo, @Nullable InputMethodSubtype subtype) {
        if (DEBUG) {
            Slog.i(TAG, "InputMethodSubtype changed: userId=" + userId
                    + " ime=" + inputMethodInfo + " subtype=" + subtype);
        }
        if (inputMethodInfo == null) {
            Slog.d(TAG, "No InputMethod is running, ignoring change");
            return;
        }
        if (subtype != null && !"keyboard".equals(subtype.getMode())) {
            Slog.d(TAG, "InputMethodSubtype changed to non-keyboard subtype, ignoring change");
            return;
        }
        InputMethodSubtypeHandle handle = new InputMethodSubtypeHandle(inputMethodInfo, subtype);
        if (!handle.equals(mCurrentImeHandle)) {
            mCurrentImeHandle = handle;
            handleSwitchKeyboardLayout(null, handle);
        }
    }

    // Must be called on handler.
    private void handleSwitchKeyboardLayout(@Nullable InputDeviceIdentifier identifier,
            InputMethodSubtypeHandle handle) {
        synchronized (mInputDevicesLock) {
            for (InputDevice device : mInputDevices) {
                if (identifier != null && !device.getIdentifier().equals(identifier) ||
                        !device.isFullKeyboard()) {
                    continue;
                }
                String key = getLayoutDescriptor(device.getIdentifier());
                boolean changed = false;
                synchronized (mDataStore) {
                    try {
                        if (mDataStore.switchKeyboardLayout(key, handle)) {
                            changed = true;
                        }
                    } finally {
                        mDataStore.saveIfNeeded();
                    }
                }
                if (changed) {
                    reloadKeyboardLayouts();
                }
            }
        }
    }

    public void setInputWindows(InputWindowHandle[] windowHandles) {
        nativeSetInputWindows(mPtr, windowHandles);
    }

    public void setFocusedApplication(InputApplicationHandle application) {
        nativeSetFocusedApplication(mPtr, application);
    }

    public void setInputDispatchMode(boolean enabled, boolean frozen) {
        nativeSetInputDispatchMode(mPtr, enabled, frozen);
    }

    public void setSystemUiVisibility(int visibility) {
        nativeSetSystemUiVisibility(mPtr, visibility);
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
     * @return True if the transfer was successful.  False if the window with the
     * specified channel did not actually have touch focus at the time of the request.
     */
    public boolean transferTouchFocus(InputChannel fromChannel, InputChannel toChannel) {
        if (fromChannel == null) {
            throw new IllegalArgumentException("fromChannel must not be null.");
        }
        if (toChannel == null) {
            throw new IllegalArgumentException("toChannel must not be null.");
        }
        return nativeTransferTouchFocus(mPtr, fromChannel, toChannel);
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

    public void updatePointerSpeedFromSettings() {
        int speed = getPointerSpeedSetting();
        setPointerSpeedUnchecked(speed);
    }

    private void setPointerSpeedUnchecked(int speed) {
        speed = Math.min(Math.max(speed, InputManager.MIN_POINTER_SPEED),
                InputManager.MAX_POINTER_SPEED);
        nativeSetPointerSpeed(mPtr, speed);
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
        } catch (SettingNotFoundException snfe) {
        }
        return speed;
    }

    public void updateShowTouchesFromSettings() {
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

    public void updateAccessibilityLargePointerFromSettings() {
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

    private int getShowTouchesSetting(int defaultValue) {
        int result = defaultValue;
        try {
            result = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.SHOW_TOUCHES, UserHandle.USER_CURRENT);
        } catch (SettingNotFoundException snfe) {
        }
        return result;
    }

    // Binder call
    @Override
    public void vibrate(int deviceId, long[] pattern, int repeat, IBinder token) {
        if (repeat >= pattern.length) {
            throw new ArrayIndexOutOfBoundsException();
        }

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

        synchronized (v) {
            v.mVibrating = true;
            nativeVibrate(mPtr, deviceId, pattern, repeat, v.mTokenValue);
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

    // Binder call
    @Override
    public void setPointerIconType(int iconId) {
        nativeSetPointerIconType(mPtr, iconId);
    }

    // Binder call
    @Override
    public void setCustomPointerIcon(PointerIcon icon) {
        nativeSetCustomPointerIcon(mPtr, icon);
    }

    @Override
    public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump InputManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        pw.println("INPUT MANAGER (dumpsys input)\n");
        String dumpStr = nativeDump(mPtr);
        if (dumpStr != null) {
            pw.println(dumpStr);
        }
        pw.println("  Keyboard Layouts:");
        visitAllKeyboardLayouts(new KeyboardLayoutVisitor() {
            @Override
            public void visitKeyboardLayout(Resources resources,
                    int keyboardLayoutResId, KeyboardLayout layout) {
                pw.println("    \"" + layout + "\": " + layout.getDescriptor());
            }
        });
        pw.println();
        synchronized(mDataStore) {
            mDataStore.dump(pw, "  ");
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ResultReceiver resultReceiver) {
        (new Shell()).exec(this, in, out, err, args, resultReceiver);
    }

    public int onShellCommand(Shell shell, String cmd) {
        if (TextUtils.isEmpty(cmd)) {
            shell.onHelp();
            return 1;
        }
        if (cmd.equals("setlayout")) {
            if (!checkCallingPermission(android.Manifest.permission.SET_KEYBOARD_LAYOUT,
                    "onShellCommand()")) {
                throw new SecurityException("Requires SET_KEYBOARD_LAYOUT permission");
            }
            InputMethodSubtypeHandle handle = new InputMethodSubtypeHandle(
                    shell.getNextArgRequired(), Integer.parseInt(shell.getNextArgRequired()));
            String descriptor = shell.getNextArgRequired();
            int vid = Integer.decode(shell.getNextArgRequired());
            int pid = Integer.decode(shell.getNextArgRequired());
            InputDeviceIdentifier id = new InputDeviceIdentifier(descriptor, vid, pid);
            setKeyboardLayoutForInputDeviceInner(id, handle, shell.getNextArgRequired());
        }
        return 0;
    }


    private boolean checkCallingPermission(String permission, String func) {
        // Quick check: if the calling permission is me, it's all okay.
        if (Binder.getCallingPid() == Process.myPid()) {
            return true;
        }

        if (mContext.checkCallingPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            return true;
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
        nativeMonitor(mPtr);
    }

    // Native callback.
    private void notifyConfigurationChanged(long whenNanos) {
        mWindowManagerCallbacks.notifyConfigurationChanged();
    }

    // Native callback.
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
    private void notifySwitch(long whenNanos, int switchValues, int switchMask) {
        if (DEBUG) {
            Slog.d(TAG, "notifySwitch: values=" + Integer.toHexString(switchValues)
                    + ", mask=" + Integer.toHexString(switchMask));
        }

        if ((switchMask & SW_LID_BIT) != 0) {
            final boolean lidOpen = ((switchValues & SW_LID_BIT) == 0);
            mWindowManagerCallbacks.notifyLidSwitchChanged(whenNanos, lidOpen);
        }

        if ((switchMask & SW_CAMERA_LENS_COVER_BIT) != 0) {
            final boolean lensCovered = ((switchValues & SW_CAMERA_LENS_COVER_BIT) != 0);
            mWindowManagerCallbacks.notifyCameraLensCoverSwitchChanged(whenNanos, lensCovered);
        }

        if (mUseDevInputEventForAudioJack && (switchMask & SW_JACK_BITS) != 0) {
            mWiredAccessoryCallbacks.notifyWiredAccessoryChanged(whenNanos, switchValues,
                    switchMask);
        }

        if ((switchMask & SW_TABLET_MODE_BIT) != 0) {
            SomeArgs args = SomeArgs.obtain();
            args.argi1 = (int) (whenNanos & 0xFFFFFFFF);
            args.argi2 = (int) (whenNanos >> 32);
            args.arg1 = Boolean.valueOf((switchValues & SW_TABLET_MODE_BIT) != 0);
            mHandler.obtainMessage(MSG_DELIVER_TABLET_MODE_CHANGED,
                    args).sendToTarget();
        }
    }

    // Native callback.
    private void notifyInputChannelBroken(InputWindowHandle inputWindowHandle) {
        mWindowManagerCallbacks.notifyInputChannelBroken(inputWindowHandle);
    }

    // Native callback.
    private long notifyANR(InputApplicationHandle inputApplicationHandle,
            InputWindowHandle inputWindowHandle, String reason) {
        return mWindowManagerCallbacks.notifyANR(
                inputApplicationHandle, inputWindowHandle, reason);
    }

    // Native callback.
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
    private int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags) {
        return mWindowManagerCallbacks.interceptKeyBeforeQueueing(event, policyFlags);
    }

    // Native callback.
    private int interceptMotionBeforeQueueingNonInteractive(long whenNanos, int policyFlags) {
        return mWindowManagerCallbacks.interceptMotionBeforeQueueingNonInteractive(
                whenNanos, policyFlags);
    }

    // Native callback.
    private long interceptKeyBeforeDispatching(InputWindowHandle focus,
            KeyEvent event, int policyFlags) {
        return mWindowManagerCallbacks.interceptKeyBeforeDispatching(focus, event, policyFlags);
    }

    // Native callback.
    private KeyEvent dispatchUnhandledKey(InputWindowHandle focus,
            KeyEvent event, int policyFlags) {
        return mWindowManagerCallbacks.dispatchUnhandledKey(focus, event, policyFlags);
    }

    // Native callback.
    private boolean checkInjectEventsPermission(int injectorPid, int injectorUid) {
        return mContext.checkPermission(android.Manifest.permission.INJECT_EVENTS,
                injectorPid, injectorUid) == PackageManager.PERMISSION_GRANTED;
    }

    // Native callback.
    private int getVirtualKeyQuietTimeMillis() {
        return mContext.getResources().getInteger(
                com.android.internal.R.integer.config_virtualKeyQuietTimeMillis);
    }

    // Native callback.
    private String[] getExcludedDeviceNames() {
        ArrayList<String> names = new ArrayList<String>();

        // Read partner-provided list of excluded input devices
        XmlPullParser parser = null;
        // Environment.getRootDirectory() is a fancy way of saying ANDROID_ROOT or "/system".
        File confFile = new File(Environment.getRootDirectory(), EXCLUDED_DEVICES_PATH);
        FileReader confreader = null;
        try {
            confreader = new FileReader(confFile);
            parser = Xml.newPullParser();
            parser.setInput(confreader);
            XmlUtils.beginDocument(parser, "devices");

            while (true) {
                XmlUtils.nextElement(parser);
                if (!"device".equals(parser.getName())) {
                    break;
                }
                String name = parser.getAttributeValue(null, "name");
                if (name != null) {
                    names.add(name);
                }
            }
        } catch (FileNotFoundException e) {
            // It's ok if the file does not exist.
        } catch (Exception e) {
            Slog.e(TAG, "Exception while parsing '" + confFile.getAbsolutePath() + "'", e);
        } finally {
            try { if (confreader != null) confreader.close(); } catch (IOException e) { }
        }

        return names.toArray(new String[names.size()]);
    }

    // Native callback.
    private int getKeyRepeatTimeout() {
        return ViewConfiguration.getKeyRepeatTimeout();
    }

    // Native callback.
    private int getKeyRepeatDelay() {
        return ViewConfiguration.getKeyRepeatDelay();
    }

    // Native callback.
    private int getHoverTapTimeout() {
        return ViewConfiguration.getHoverTapTimeout();
    }

    // Native callback.
    private int getHoverTapSlop() {
        return ViewConfiguration.getHoverTapSlop();
    }

    // Native callback.
    private int getDoubleTapTimeout() {
        return ViewConfiguration.getDoubleTapTimeout();
    }

    // Native callback.
    private int getLongPressTimeout() {
        return ViewConfiguration.getLongPressTimeout();
    }

    // Native callback.
    private int getPointerLayer() {
        return mWindowManagerCallbacks.getPointerLayer();
    }

    // Native callback.
    private PointerIcon getPointerIcon() {
        return PointerIcon.getDefaultIcon(mContext);
    }

    // Native callback.
    private String[] getKeyboardLayoutOverlay(InputDeviceIdentifier identifier) {
        if (!mSystemReady) {
            return null;
        }

        String keyboardLayoutDescriptor = getCurrentKeyboardLayoutForInputDevice(identifier);
        if (keyboardLayoutDescriptor == null) {
            return null;
        }

        final String[] result = new String[2];
        visitKeyboardLayout(keyboardLayoutDescriptor, new KeyboardLayoutVisitor() {
            @Override
            public void visitKeyboardLayout(Resources resources,
                    int keyboardLayoutResId, KeyboardLayout layout) {
                try {
                    result[0] = layout.getDescriptor();
                    result[1] = Streams.readFully(new InputStreamReader(
                            resources.openRawResource(keyboardLayoutResId)));
                } catch (IOException ex) {
                } catch (NotFoundException ex) {
                }
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
    public interface WindowManagerCallbacks {
        public void notifyConfigurationChanged();

        public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen);

        public void notifyCameraLensCoverSwitchChanged(long whenNanos, boolean lensCovered);

        public void notifyInputChannelBroken(InputWindowHandle inputWindowHandle);

        public long notifyANR(InputApplicationHandle inputApplicationHandle,
                InputWindowHandle inputWindowHandle, String reason);

        public int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags);

        public int interceptMotionBeforeQueueingNonInteractive(long whenNanos, int policyFlags);

        public long interceptKeyBeforeDispatching(InputWindowHandle focus,
                KeyEvent event, int policyFlags);

        public KeyEvent dispatchUnhandledKey(InputWindowHandle focus,
                KeyEvent event, int policyFlags);

        public int getPointerLayer();
    }

    /**
     * Callback interface implemented by WiredAccessoryObserver.
     */
    public interface WiredAccessoryCallbacks {
        public void notifyWiredAccessoryChanged(long whenNanos, int switchValues, int switchMask);
        public void systemReady();
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
                case MSG_SWITCH_KEYBOARD_LAYOUT: {
                    SomeArgs args = (SomeArgs)msg.obj;
                    handleSwitchKeyboardLayout((InputDeviceIdentifier)args.arg1,
                            (InputMethodSubtypeHandle)args.arg2);
                    break;
                }
                case MSG_RELOAD_KEYBOARD_LAYOUTS:
                    reloadKeyboardLayouts();
                    break;
                case MSG_UPDATE_KEYBOARD_LAYOUTS:
                    updateKeyboardLayouts();
                    break;
                case MSG_RELOAD_DEVICE_ALIASES:
                    reloadDeviceAliases();
                    break;
                case MSG_DELIVER_TABLET_MODE_CHANGED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    long whenNanos = (args.argi1 & 0xFFFFFFFFl) | ((long) args.argi2 << 32);
                    boolean inTabletMode = (boolean) args.arg1;
                    deliverTabletModeChanged(whenNanos, inTabletMode);
                    break;
                }
                case MSG_INPUT_METHOD_SUBTYPE_CHANGED: {
                    final int userId = msg.arg1;
                    final SomeArgs args = (SomeArgs) msg.obj;
                    final InputMethodInfo inputMethodInfo = (InputMethodInfo) args.arg1;
                    final InputMethodSubtype subtype = (InputMethodSubtype) args.arg2;
                    args.recycle();
                    handleSwitchInputMethodSubtype(userId, inputMethodInfo, subtype);
                    break;
                }
            }
        }
    }

    /**
     * Hosting interface for input filters to call back into the input manager.
     */
    private final class InputFilterHost extends IInputFilterHost.Stub {
        private boolean mDisconnected;

        public void disconnectLocked() {
            mDisconnected = true;
        }

        @Override
        public void sendInputEvent(InputEvent event, int policyFlags) {
            if (event == null) {
                throw new IllegalArgumentException("event must not be null");
            }

            synchronized (mInputFilterLock) {
                if (!mDisconnected) {
                    nativeInjectInputEvent(mPtr, event, Display.DEFAULT_DISPLAY, 0, 0,
                            InputManager.INJECT_INPUT_EVENT_MODE_ASYNC, 0,
                            policyFlags | WindowManagerPolicy.FLAG_FILTERED);
                }
            }
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

    private class Shell extends ShellCommand {
        @Override
        public int onCommand(String cmd) {
            return onShellCommand(this, cmd);
        }

        @Override
        public void onHelp() {
            final PrintWriter pw = getOutPrintWriter();
            pw.println("Input manager commands:");
            pw.println("  help");
            pw.println("    Print this help text.");
            pw.println("");
            pw.println("  setlayout IME_ID IME_SUPTYPE_HASH_CODE"
                    + " DEVICE_DESCRIPTOR VENDOR_ID PRODUCT_ID KEYBOARD_DESCRIPTOR");
            pw.println("    Sets a keyboard layout for a given IME subtype and input device pair");
        }
    }

    private final class LocalService extends InputManagerInternal {
        @Override
        public void setDisplayViewports(
                DisplayViewport defaultViewport, DisplayViewport externalTouchViewport) {
            setDisplayViewportsInternal(defaultViewport, externalTouchViewport);
        }

        @Override
        public boolean injectInputEvent(InputEvent event, int displayId, int mode) {
            return injectInputEventInternal(event, displayId, mode);
        }

        @Override
        public void setInteractive(boolean interactive) {
            nativeSetInteractive(mPtr, interactive);
        }

        @Override
        public void onInputMethodSubtypeChanged(int userId,
                @Nullable InputMethodInfo inputMethodInfo, @Nullable InputMethodSubtype subtype) {
            final SomeArgs someArgs = SomeArgs.obtain();
            someArgs.arg1 = inputMethodInfo;
            someArgs.arg2 = subtype;
            mHandler.obtainMessage(MSG_INPUT_METHOD_SUBTYPE_CHANGED, userId, 0, someArgs)
                    .sendToTarget();
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
    }
}
