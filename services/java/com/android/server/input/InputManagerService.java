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

import com.android.internal.util.XmlUtils;
import com.android.server.Watchdog;
import com.android.server.input.InputFilter.Host;
import com.android.server.wm.WindowManagerService;

import org.xmlpull.v1.XmlPullParser;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.hardware.input.IInputManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.SystemProperties;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.Slog;
import android.util.Xml;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;

/*
 * Wraps the C++ InputManager and provides its callbacks.
 */
public class InputManagerService extends IInputManager.Stub implements Watchdog.Monitor {
    static final String TAG = "InputManager";
    static final boolean DEBUG = false;

    private static final String EXCLUDED_DEVICES_PATH = "etc/excluded-input-devices.xml";

    // Pointer to native input manager service object.
    private final int mPtr;

    private final Context mContext;
    private final Callbacks mCallbacks;
    private final Handler mHandler;

    private static native int nativeInit(InputManagerService service,
            Context context, MessageQueue messageQueue);
    private static native void nativeStart(int ptr);
    private static native void nativeSetDisplaySize(int ptr, int displayId,
            int width, int height, int externalWidth, int externalHeight);
    private static native void nativeSetDisplayOrientation(int ptr, int displayId, int rotation);
    
    private static native int nativeGetScanCodeState(int ptr,
            int deviceId, int sourceMask, int scanCode);
    private static native int nativeGetKeyCodeState(int ptr,
            int deviceId, int sourceMask, int keyCode);
    private static native int nativeGetSwitchState(int ptr,
            int deviceId, int sourceMask, int sw);
    private static native boolean nativeHasKeys(int ptr,
            int deviceId, int sourceMask, int[] keyCodes, boolean[] keyExists);
    private static native void nativeRegisterInputChannel(int ptr, InputChannel inputChannel,
            InputWindowHandle inputWindowHandle, boolean monitor);
    private static native void nativeUnregisterInputChannel(int ptr, InputChannel inputChannel);
    private static native void nativeSetInputFilterEnabled(int ptr, boolean enable);
    private static native int nativeInjectInputEvent(int ptr, InputEvent event,
            int injectorPid, int injectorUid, int syncMode, int timeoutMillis,
            int policyFlags);
    private static native void nativeSetInputWindows(int ptr, InputWindowHandle[] windowHandles);
    private static native void nativeSetInputDispatchMode(int ptr, boolean enabled, boolean frozen);
    private static native void nativeSetSystemUiVisibility(int ptr, int visibility);
    private static native void nativeSetFocusedApplication(int ptr,
            InputApplicationHandle application);
    private static native InputDevice nativeGetInputDevice(int ptr, int deviceId);
    private static native void nativeGetInputConfiguration(int ptr, Configuration configuration);
    private static native int[] nativeGetInputDeviceIds(int ptr);
    private static native boolean nativeTransferTouchFocus(int ptr,
            InputChannel fromChannel, InputChannel toChannel);
    private static native void nativeSetPointerSpeed(int ptr, int speed);
    private static native void nativeSetShowTouches(int ptr, boolean enabled);
    private static native String nativeDump(int ptr);
    private static native void nativeMonitor(int ptr);
    
    // Input event injection constants defined in InputDispatcher.h.
    public static final int INPUT_EVENT_INJECTION_SUCCEEDED = 0;
    public static final int INPUT_EVENT_INJECTION_PERMISSION_DENIED = 1;
    public static final int INPUT_EVENT_INJECTION_FAILED = 2;
    public static final int INPUT_EVENT_INJECTION_TIMED_OUT = 3;

    // Input event injection synchronization modes defined in InputDispatcher.h
    public static final int INPUT_EVENT_INJECTION_SYNC_NONE = 0;
    public static final int INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_RESULT = 1;
    public static final int INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_FINISH = 2;
    
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

    // State for the currently installed input filter.
    final Object mInputFilterLock = new Object();
    InputFilter mInputFilter;
    InputFilterHost mInputFilterHost;

    public InputManagerService(Context context, Callbacks callbacks) {
        this.mContext = context;
        this.mCallbacks = callbacks;
        this.mHandler = new Handler();

        Slog.i(TAG, "Initializing input manager");
        mPtr = nativeInit(this, mContext, mHandler.getLooper().getQueue());
    }

    public void start() {
        Slog.i(TAG, "Starting input manager");
        nativeStart(mPtr);

        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);

        registerPointerSpeedSettingObserver();
        registerShowTouchesSettingObserver();

        updatePointerSpeedFromSettings();
        updateShowTouchesFromSettings();
    }
    
    public void setDisplaySize(int displayId, int width, int height,
            int externalWidth, int externalHeight) {
        if (width <= 0 || height <= 0 || externalWidth <= 0 || externalHeight <= 0) {
            throw new IllegalArgumentException("Invalid display id or dimensions.");
        }
        
        if (DEBUG) {
            Slog.d(TAG, "Setting display #" + displayId + " size to " + width + "x" + height
                    + " external size " + externalWidth + "x" + externalHeight);
        }
        nativeSetDisplaySize(mPtr, displayId, width, height, externalWidth, externalHeight);
    }
    
    public void setDisplayOrientation(int displayId, int rotation) {
        if (rotation < Surface.ROTATION_0 || rotation > Surface.ROTATION_270) {
            throw new IllegalArgumentException("Invalid rotation.");
        }
        
        if (DEBUG) {
            Slog.d(TAG, "Setting display #" + displayId + " orientation to " + rotation);
        }
        nativeSetDisplayOrientation(mPtr, displayId, rotation);
    }
    
    public void getInputConfiguration(Configuration config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null.");
        }
        
        nativeGetInputConfiguration(mPtr, config);
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
    public void setInputFilter(InputFilter filter) {
        synchronized (mInputFilterLock) {
            final InputFilter oldFilter = mInputFilter;
            if (oldFilter == filter) {
                return; // nothing to do
            }

            if (oldFilter != null) {
                mInputFilter = null;
                mInputFilterHost.disconnectLocked();
                mInputFilterHost = null;
                oldFilter.uninstall();
            }

            if (filter != null) {
                mInputFilter = filter;
                mInputFilterHost = new InputFilterHost();
                filter.install(mInputFilterHost);
            }

            nativeSetInputFilterEnabled(mPtr, filter != null);
        }
    }

    /**
     * Injects an input event into the event system on behalf of an application.
     * The synchronization mode determines whether the method blocks while waiting for
     * input injection to proceed.
     * 
     * {@link #INPUT_EVENT_INJECTION_SYNC_NONE} never blocks.  Injection is asynchronous and
     * is assumed always to be successful.
     * 
     * {@link #INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_RESULT} waits for previous events to be
     * dispatched so that the input dispatcher can determine whether input event injection will
     * be permitted based on the current input focus.  Does not wait for the input event to
     * finish processing.
     * 
     * {@link #INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_FINISH} waits for the input event to
     * be completely processed.
     * 
     * @param event The event to inject.
     * @param injectorPid The pid of the injecting application.
     * @param injectorUid The uid of the injecting application.
     * @param syncMode The synchronization mode.
     * @param timeoutMillis The injection timeout in milliseconds.
     * @return One of the INPUT_EVENT_INJECTION_XXX constants.
     */
    public int injectInputEvent(InputEvent event, int injectorPid, int injectorUid,
            int syncMode, int timeoutMillis) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (injectorPid < 0 || injectorUid < 0) {
            throw new IllegalArgumentException("injectorPid and injectorUid must not be negative.");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }

        return nativeInjectInputEvent(mPtr, event, injectorPid, injectorUid, syncMode,
                timeoutMillis, WindowManagerPolicy.FLAG_DISABLE_KEY_REPEAT);
    }

    /**
     * Gets information about the input device with the specified id.
     * @param id The device id.
     * @return The input device or null if not found.
     */
    public InputDevice getInputDevice(int deviceId) {
        return nativeGetInputDevice(mPtr, deviceId);
    }
    
    /**
     * Gets the ids of all input devices in the system.
     * @return The input device ids.
     */
    public int[] getInputDeviceIds() {
        return nativeGetInputDeviceIds(mPtr);
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

    /**
     * Set the pointer speed.
     * @param speed The pointer speed as a value between -7 (slowest) and 7 (fastest)
     * where 0 is the default speed.
     */
    public void setPointerSpeed(int speed) {
        speed = Math.min(Math.max(speed, -7), 7);
        nativeSetPointerSpeed(mPtr, speed);
    }

    public void updatePointerSpeedFromSettings() {
        int speed = getPointerSpeedSetting(0);
        setPointerSpeed(speed);
    }

    private void registerPointerSpeedSettingObserver() {
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.POINTER_SPEED), true,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updatePointerSpeedFromSettings();
                    }
                });
    }

    private int getPointerSpeedSetting(int defaultValue) {
        int speed = defaultValue;
        try {
            speed = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.POINTER_SPEED);
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
                });
    }

    private int getShowTouchesSetting(int defaultValue) {
        int result = defaultValue;
        try {
            result = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.SHOW_TOUCHES);
        } catch (SettingNotFoundException snfe) {
        }
        return result;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission("android.permission.DUMP")
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
    }

    // Called by the heartbeat to ensure locks are not held indefinitely (for deadlock detection).
    public void monitor() {
        synchronized (mInputFilterLock) { }
        nativeMonitor(mPtr);
    }

    // Native callback.
    private void notifyConfigurationChanged(long whenNanos) {
        mCallbacks.notifyConfigurationChanged();
    }

    // Native callback.
    private void notifyLidSwitchChanged(long whenNanos, boolean lidOpen) {
        mCallbacks.notifyLidSwitchChanged(whenNanos, lidOpen);
    }

    // Native callback.
    private void notifyInputChannelBroken(InputWindowHandle inputWindowHandle) {
        mCallbacks.notifyInputChannelBroken(inputWindowHandle);
    }

    // Native callback.
    private long notifyANR(InputApplicationHandle inputApplicationHandle,
            InputWindowHandle inputWindowHandle) {
        return mCallbacks.notifyANR(inputApplicationHandle, inputWindowHandle);
    }

    // Native callback.
    final boolean filterInputEvent(InputEvent event, int policyFlags) {
        synchronized (mInputFilterLock) {
            if (mInputFilter != null) {
                mInputFilter.filterInputEvent(event, policyFlags);
                return false;
            }
        }
        event.recycle();
        return true;
    }

    // Native callback.
    private int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags, boolean isScreenOn) {
        return mCallbacks.interceptKeyBeforeQueueing(
                event, policyFlags, isScreenOn);
    }

    // Native callback.
    private int interceptMotionBeforeQueueingWhenScreenOff(int policyFlags) {
        return mCallbacks.interceptMotionBeforeQueueingWhenScreenOff(policyFlags);
    }

    // Native callback.
    private long interceptKeyBeforeDispatching(InputWindowHandle focus,
            KeyEvent event, int policyFlags) {
        return mCallbacks.interceptKeyBeforeDispatching(focus, event, policyFlags);
    }

    // Native callback.
    private KeyEvent dispatchUnhandledKey(InputWindowHandle focus,
            KeyEvent event, int policyFlags) {
        return mCallbacks.dispatchUnhandledKey(focus, event, policyFlags);
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
        return mCallbacks.getPointerLayer();
    }

    // Native callback.
    private PointerIcon getPointerIcon() {
        return PointerIcon.getDefaultIcon(mContext);
    }

    /**
     * Callback interface implemented by the Window Manager.
     */
    public interface Callbacks {
        public void notifyConfigurationChanged();

        public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen);

        public void notifyInputChannelBroken(InputWindowHandle inputWindowHandle);

        public long notifyANR(InputApplicationHandle inputApplicationHandle,
                InputWindowHandle inputWindowHandle);

        public int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags, boolean isScreenOn);

        public int interceptMotionBeforeQueueingWhenScreenOff(int policyFlags);

        public long interceptKeyBeforeDispatching(InputWindowHandle focus,
                KeyEvent event, int policyFlags);

        public KeyEvent dispatchUnhandledKey(InputWindowHandle focus,
                KeyEvent event, int policyFlags);

        public int getPointerLayer();
    }

    /**
     * Hosting interface for input filters to call back into the input manager.
     */
    private final class InputFilterHost implements InputFilter.Host {
        private boolean mDisconnected;

        public void disconnectLocked() {
            mDisconnected = true;
        }

        public void sendInputEvent(InputEvent event, int policyFlags) {
            if (event == null) {
                throw new IllegalArgumentException("event must not be null");
            }

            synchronized (mInputFilterLock) {
                if (!mDisconnected) {
                    nativeInjectInputEvent(mPtr, event, 0, 0, INPUT_EVENT_INJECTION_SYNC_NONE, 0,
                            policyFlags | WindowManagerPolicy.FLAG_FILTERED);
                }
            }
        }
    }
}
