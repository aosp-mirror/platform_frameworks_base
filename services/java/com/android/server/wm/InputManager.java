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

package com.android.server.wm;

import com.android.internal.util.XmlUtils;
import com.android.server.Watchdog;

import org.xmlpull.v1.XmlPullParser;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.os.Environment;
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
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;

/*
 * Wraps the C++ InputManager and provides its callbacks.
 */
public class InputManager implements Watchdog.Monitor {
    static final String TAG = "InputManager";
    
    private static final boolean DEBUG = false;

    private final Callbacks mCallbacks;
    private final Context mContext;
    private final WindowManagerService mWindowManagerService;

    private static native void nativeInit(Context context,
            Callbacks callbacks, MessageQueue messageQueue);
    private static native void nativeStart();
    private static native void nativeSetDisplaySize(int displayId, int width, int height,
            int externalWidth, int externalHeight);
    private static native void nativeSetDisplayOrientation(int displayId, int rotation);
    
    private static native int nativeGetScanCodeState(int deviceId, int sourceMask,
            int scanCode);
    private static native int nativeGetKeyCodeState(int deviceId, int sourceMask,
            int keyCode);
    private static native int nativeGetSwitchState(int deviceId, int sourceMask,
            int sw);
    private static native boolean nativeHasKeys(int deviceId, int sourceMask,
            int[] keyCodes, boolean[] keyExists);
    private static native void nativeRegisterInputChannel(InputChannel inputChannel,
            InputWindowHandle inputWindowHandle, boolean monitor);
    private static native void nativeUnregisterInputChannel(InputChannel inputChannel);
    private static native void nativeSetInputFilterEnabled(boolean enable);
    private static native int nativeInjectInputEvent(InputEvent event,
            int injectorPid, int injectorUid, int syncMode, int timeoutMillis,
            int policyFlags);
    private static native void nativeSetInputWindows(InputWindowHandle[] windowHandles);
    private static native void nativeSetInputDispatchMode(boolean enabled, boolean frozen);
    private static native void nativeSetSystemUiVisibility(int visibility);
    private static native void nativeSetFocusedApplication(InputApplicationHandle application);
    private static native InputDevice nativeGetInputDevice(int deviceId);
    private static native void nativeGetInputConfiguration(Configuration configuration);
    private static native int[] nativeGetInputDeviceIds();
    private static native boolean nativeTransferTouchFocus(InputChannel fromChannel,
            InputChannel toChannel);
    private static native void nativeSetPointerSpeed(int speed);
    private static native void nativeSetShowTouches(boolean enabled);
    private static native String nativeDump();
    private static native void nativeMonitor();
    
    // Input event injection constants defined in InputDispatcher.h.
    static final int INPUT_EVENT_INJECTION_SUCCEEDED = 0;
    static final int INPUT_EVENT_INJECTION_PERMISSION_DENIED = 1;
    static final int INPUT_EVENT_INJECTION_FAILED = 2;
    static final int INPUT_EVENT_INJECTION_TIMED_OUT = 3;
    
    // Input event injection synchronization modes defined in InputDispatcher.h
    static final int INPUT_EVENT_INJECTION_SYNC_NONE = 0;
    static final int INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_RESULT = 1;
    static final int INPUT_EVENT_INJECTION_SYNC_WAIT_FOR_FINISH = 2;
    
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

    public InputManager(Context context, WindowManagerService windowManagerService) {
        this.mContext = context;
        this.mWindowManagerService = windowManagerService;
        this.mCallbacks = new Callbacks();

        Looper looper = windowManagerService.mH.getLooper();

        Slog.i(TAG, "Initializing input manager");
        nativeInit(mContext, mCallbacks, looper.getQueue());

        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);
    }

    public void start() {
        Slog.i(TAG, "Starting input manager");
        nativeStart();

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
        nativeSetDisplaySize(displayId, width, height, externalWidth, externalHeight);
    }
    
    public void setDisplayOrientation(int displayId, int rotation) {
        if (rotation < Surface.ROTATION_0 || rotation > Surface.ROTATION_270) {
            throw new IllegalArgumentException("Invalid rotation.");
        }
        
        if (DEBUG) {
            Slog.d(TAG, "Setting display #" + displayId + " orientation to " + rotation);
        }
        nativeSetDisplayOrientation(displayId, rotation);
    }
    
    public void getInputConfiguration(Configuration config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null.");
        }
        
        nativeGetInputConfiguration(config);
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
        return nativeGetKeyCodeState(deviceId, sourceMask, keyCode);
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
        return nativeGetScanCodeState(deviceId, sourceMask, scanCode);
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
        return nativeGetSwitchState(deviceId, sourceMask, switchCode);
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
        
        return nativeHasKeys(deviceId, sourceMask, keyCodes, keyExists);
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
        nativeRegisterInputChannel(inputChannels[0], null, true);
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
        
        nativeRegisterInputChannel(inputChannel, inputWindowHandle, false);
    }
    
    /**
     * Unregisters an input channel.
     * @param inputChannel The input channel to unregister.
     */
    public void unregisterInputChannel(InputChannel inputChannel) {
        if (inputChannel == null) {
            throw new IllegalArgumentException("inputChannel must not be null.");
        }
        
        nativeUnregisterInputChannel(inputChannel);
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

            nativeSetInputFilterEnabled(filter != null);
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

        return nativeInjectInputEvent(event, injectorPid, injectorUid, syncMode, timeoutMillis,
                WindowManagerPolicy.FLAG_DISABLE_KEY_REPEAT);
    }

    /**
     * Gets information about the input device with the specified id.
     * @param id The device id.
     * @return The input device or null if not found.
     */
    public InputDevice getInputDevice(int deviceId) {
        return nativeGetInputDevice(deviceId);
    }
    
    /**
     * Gets the ids of all input devices in the system.
     * @return The input device ids.
     */
    public int[] getInputDeviceIds() {
        return nativeGetInputDeviceIds();
    }
    
    public void setInputWindows(InputWindowHandle[] windowHandles) {
        nativeSetInputWindows(windowHandles);
    }
    
    public void setFocusedApplication(InputApplicationHandle application) {
        nativeSetFocusedApplication(application);
    }
    
    public void setInputDispatchMode(boolean enabled, boolean frozen) {
        nativeSetInputDispatchMode(enabled, frozen);
    }

    public void setSystemUiVisibility(int visibility) {
        nativeSetSystemUiVisibility(visibility);
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
        return nativeTransferTouchFocus(fromChannel, toChannel);
    }

    /**
     * Set the pointer speed.
     * @param speed The pointer speed as a value between -7 (slowest) and 7 (fastest)
     * where 0 is the default speed.
     */
    public void setPointerSpeed(int speed) {
        speed = Math.min(Math.max(speed, -7), 7);
        nativeSetPointerSpeed(speed);
    }

    public void updatePointerSpeedFromSettings() {
        int speed = getPointerSpeedSetting(0);
        setPointerSpeed(speed);
    }

    private void registerPointerSpeedSettingObserver() {
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.POINTER_SPEED), true,
                new ContentObserver(mWindowManagerService.mH) {
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
        nativeSetShowTouches(setting != 0);
    }

    private void registerShowTouchesSettingObserver() {
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SHOW_TOUCHES), true,
                new ContentObserver(mWindowManagerService.mH) {
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

    public void dump(PrintWriter pw) {
        String dumpStr = nativeDump();
        if (dumpStr != null) {
            pw.println(dumpStr);
        }
    }

    // Called by the heartbeat to ensure locks are not held indefnitely (for deadlock detection).
    public void monitor() {
        synchronized (mInputFilterLock) { }
        nativeMonitor();
    }

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
                    nativeInjectInputEvent(event, 0, 0, INPUT_EVENT_INJECTION_SYNC_NONE, 0,
                            policyFlags | WindowManagerPolicy.FLAG_FILTERED);
                }
            }
        }
    }

    /*
     * Callbacks from native.
     */
    private final class Callbacks {
        static final String TAG = "InputManager-Callbacks";
        
        private static final boolean DEBUG_VIRTUAL_KEYS = false;
        private static final String EXCLUDED_DEVICES_PATH = "etc/excluded-input-devices.xml";
        private static final String CALIBRATION_DIR_PATH = "usr/idc/";
        
        @SuppressWarnings("unused")
        public void notifyConfigurationChanged(long whenNanos) {
            mWindowManagerService.mInputMonitor.notifyConfigurationChanged();
        }
        
        @SuppressWarnings("unused")
        public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen) {
            mWindowManagerService.mInputMonitor.notifyLidSwitchChanged(whenNanos, lidOpen);
        }
        
        @SuppressWarnings("unused")
        public void notifyInputChannelBroken(InputWindowHandle inputWindowHandle) {
            mWindowManagerService.mInputMonitor.notifyInputChannelBroken(inputWindowHandle);
        }
        
        @SuppressWarnings("unused")
        public long notifyANR(InputApplicationHandle inputApplicationHandle,
                InputWindowHandle inputWindowHandle) {
            return mWindowManagerService.mInputMonitor.notifyANR(
                    inputApplicationHandle, inputWindowHandle);
        }

        @SuppressWarnings("unused")
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

        @SuppressWarnings("unused")
        public int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags, boolean isScreenOn) {
            return mWindowManagerService.mInputMonitor.interceptKeyBeforeQueueing(
                    event, policyFlags, isScreenOn);
        }

        @SuppressWarnings("unused")
        public int interceptMotionBeforeQueueingWhenScreenOff(int policyFlags) {
            return mWindowManagerService.mInputMonitor.interceptMotionBeforeQueueingWhenScreenOff(
                    policyFlags);
        }

        @SuppressWarnings("unused")
        public long interceptKeyBeforeDispatching(InputWindowHandle focus,
                KeyEvent event, int policyFlags) {
            return mWindowManagerService.mInputMonitor.interceptKeyBeforeDispatching(
                    focus, event, policyFlags);
        }
        
        @SuppressWarnings("unused")
        public KeyEvent dispatchUnhandledKey(InputWindowHandle focus,
                KeyEvent event, int policyFlags) {
            return mWindowManagerService.mInputMonitor.dispatchUnhandledKey(
                    focus, event, policyFlags);
        }
        
        @SuppressWarnings("unused")
        public boolean checkInjectEventsPermission(int injectorPid, int injectorUid) {
            return mContext.checkPermission(
                    android.Manifest.permission.INJECT_EVENTS, injectorPid, injectorUid)
                    == PackageManager.PERMISSION_GRANTED;
        }

        @SuppressWarnings("unused")
        public int getVirtualKeyQuietTimeMillis() {
            return mContext.getResources().getInteger(
                    com.android.internal.R.integer.config_virtualKeyQuietTimeMillis);
        }

        @SuppressWarnings("unused")
        public String[] getExcludedDeviceNames() {
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

        @SuppressWarnings("unused")
        public int getKeyRepeatTimeout() {
            return ViewConfiguration.getKeyRepeatTimeout();
        }

        @SuppressWarnings("unused")
        public int getKeyRepeatDelay() {
            return ViewConfiguration.getKeyRepeatDelay();
        }

        @SuppressWarnings("unused")
        public int getHoverTapTimeout() {
            return ViewConfiguration.getHoverTapTimeout();
        }

        @SuppressWarnings("unused")
        public int getHoverTapSlop() {
            return ViewConfiguration.getHoverTapSlop();
        }

        @SuppressWarnings("unused")
        public int getDoubleTapTimeout() {
            return ViewConfiguration.getDoubleTapTimeout();
        }

        @SuppressWarnings("unused")
        public int getLongPressTimeout() {
            return ViewConfiguration.getLongPressTimeout();
        }

        @SuppressWarnings("unused")
        public int getMaxEventsPerSecond() {
            int result = 0;
            try {
                result = Integer.parseInt(SystemProperties.get("windowsmgr.max_events_per_sec"));
            } catch (NumberFormatException e) {
            }
            if (result < 1) {
                // This number equates to the refresh rate * 1.5. The rate should be at least
                // equal to the screen refresh rate. We increase the rate by 50% to compensate for
                // the discontinuity between the actual rate that events come in at (they do
                // not necessarily come in constantly and are not handled synchronously).
                // Ideally, we would use Display.getRefreshRate(), but as this does not necessarily
                // return a sensible result, we use '60' as our default assumed refresh rate.
                result = 90;
            }
            return result;
        }

        @SuppressWarnings("unused")
        public int getPointerLayer() {
            return mWindowManagerService.mPolicy.windowTypeToLayerLw(
                    WindowManager.LayoutParams.TYPE_POINTER)
                    * WindowManagerService.TYPE_LAYER_MULTIPLIER
                    + WindowManagerService.TYPE_LAYER_OFFSET;
        }

        @SuppressWarnings("unused")
        public PointerIcon getPointerIcon() {
            return PointerIcon.getDefaultIcon(mContext);
        }
    }
}
