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

package com.android.server;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Environment;
import android.os.SystemProperties;
import android.util.Slog;
import android.util.Xml;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Properties;

/*
 * Wraps the C++ InputManager and provides its callbacks.
 */
public class InputManager {
    static final String TAG = "InputManager";
    
    private static final boolean DEBUG = false;

    private final Callbacks mCallbacks;
    private final Context mContext;
    private final WindowManagerService mWindowManagerService;
    
    private static native void nativeInit(Callbacks callbacks);
    private static native void nativeStart();
    private static native void nativeSetDisplaySize(int displayId, int width, int height);
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
            boolean monitor);
    private static native void nativeUnregisterInputChannel(InputChannel inputChannel);
    private static native int nativeInjectInputEvent(InputEvent event,
            int injectorPid, int injectorUid, int syncMode, int timeoutMillis);
    private static native void nativeSetInputWindows(InputWindow[] windows);
    private static native void nativeSetInputDispatchMode(boolean enabled, boolean frozen);
    private static native void nativeSetFocusedApplication(InputApplication application);
    private static native InputDevice nativeGetInputDevice(int deviceId);
    private static native void nativeGetInputConfiguration(Configuration configuration);
    private static native int[] nativeGetInputDeviceIds();
    private static native String nativeDump();
    
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

    public InputManager(Context context, WindowManagerService windowManagerService) {
        this.mContext = context;
        this.mWindowManagerService = windowManagerService;
        
        this.mCallbacks = new Callbacks();
        
        init();
    }
    
    private void init() {
        Slog.i(TAG, "Initializing input manager");
        nativeInit(mCallbacks);
    }
    
    public void start() {
        Slog.i(TAG, "Starting input manager");
        nativeStart();
    }
    
    public void setDisplaySize(int displayId, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid display id or dimensions.");
        }
        
        if (DEBUG) {
            Slog.d(TAG, "Setting display #" + displayId + " size to " + width + "x" + height);
        }
        nativeSetDisplaySize(displayId, width, height);
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
        nativeRegisterInputChannel(inputChannels[0], true);
        inputChannels[0].dispose(); // don't need to retain the Java object reference
        return inputChannels[1];
    }

    /**
     * Registers an input channel so that it can be used as an input event target.
     * @param inputChannel The input channel to register.
     */
    public void registerInputChannel(InputChannel inputChannel) {
        if (inputChannel == null) {
            throw new IllegalArgumentException("inputChannel must not be null.");
        }
        
        nativeRegisterInputChannel(inputChannel, false);
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

        return nativeInjectInputEvent(event, injectorPid, injectorUid, syncMode, timeoutMillis);
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
    
    public void setInputWindows(InputWindow[] windows) {
        nativeSetInputWindows(windows);
    }
    
    public void setFocusedApplication(InputApplication application) {
        nativeSetFocusedApplication(application);
    }
    
    public void setInputDispatchMode(boolean enabled, boolean frozen) {
        nativeSetInputDispatchMode(enabled, frozen);
    }
    
    public void dump(PrintWriter pw) {
        String dumpStr = nativeDump();
        if (dumpStr != null) {
            pw.println(dumpStr);
        }
    }
    
    private static final class VirtualKeyDefinition {
        public int scanCode;
        
        // configured position data, specified in display coords
        public int centerX;
        public int centerY;
        public int width;
        public int height;
    }
    
    private static final class InputDeviceCalibration {
        public String[] keys;
        public String[] values;
    }
    
    /*
     * Callbacks from native.
     */
    private class Callbacks {
        static final String TAG = "InputManager-Callbacks";
        
        private static final boolean DEBUG_VIRTUAL_KEYS = false;
        private static final String EXCLUDED_DEVICES_PATH = "etc/excluded-input-devices.xml";
        private static final String CALIBRATION_DIR_PATH = "usr/idc/";
        
        @SuppressWarnings("unused")
        public void notifyConfigurationChanged(long whenNanos) {
            mWindowManagerService.sendNewConfiguration();
        }
        
        @SuppressWarnings("unused")
        public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen) {
            mWindowManagerService.mInputMonitor.notifyLidSwitchChanged(whenNanos, lidOpen);
        }
        
        @SuppressWarnings("unused")
        public void notifyInputChannelBroken(InputChannel inputChannel) {
            mWindowManagerService.mInputMonitor.notifyInputChannelBroken(inputChannel);
        }
        
        @SuppressWarnings("unused")
        public long notifyANR(Object token, InputChannel inputChannel) {
            return mWindowManagerService.mInputMonitor.notifyANR(token, inputChannel);
        }
        
        @SuppressWarnings("unused")
        public int interceptKeyBeforeQueueing(long whenNanos, int action, int flags,
                int keyCode, int scanCode, int policyFlags, boolean isScreenOn) {
            return mWindowManagerService.mInputMonitor.interceptKeyBeforeQueueing(
                    whenNanos, action, flags, keyCode, scanCode, policyFlags, isScreenOn);
        }
        
        @SuppressWarnings("unused")
        public boolean interceptKeyBeforeDispatching(InputChannel focus, int action,
                int flags, int keyCode, int scanCode, int metaState, int repeatCount,
                int policyFlags) {
            return mWindowManagerService.mInputMonitor.interceptKeyBeforeDispatching(focus,
                    action, flags, keyCode, scanCode, metaState, repeatCount, policyFlags);
        }
        
        @SuppressWarnings("unused")
        public boolean checkInjectEventsPermission(int injectorPid, int injectorUid) {
            return mContext.checkPermission(
                    android.Manifest.permission.INJECT_EVENTS, injectorPid, injectorUid)
                    == PackageManager.PERMISSION_GRANTED;
        }
        
        @SuppressWarnings("unused")
        public boolean filterTouchEvents() {
            return mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_filterTouchEvents);
        }
        
        @SuppressWarnings("unused")
        public boolean filterJumpyTouchEvents() {
            return mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_filterJumpyTouchEvents);
        }

        @SuppressWarnings("unused")
        public int getVirtualKeyQuietTimeMillis() {
            return mContext.getResources().getInteger(
                    com.android.internal.R.integer.config_virtualKeyQuietTimeMillis);
        }

        @SuppressWarnings("unused")
        public VirtualKeyDefinition[] getVirtualKeyDefinitions(String deviceName) {
            ArrayList<VirtualKeyDefinition> keys = new ArrayList<VirtualKeyDefinition>();
            
            try {
                FileInputStream fis = new FileInputStream(
                        "/sys/board_properties/virtualkeys." + deviceName);
                InputStreamReader isr = new InputStreamReader(fis);
                BufferedReader br = new BufferedReader(isr, 2048);
                String str = br.readLine();
                if (str != null) {
                    String[] it = str.split(":");
                    if (DEBUG_VIRTUAL_KEYS) Slog.v(TAG, "***** VIRTUAL KEYS: " + it);
                    final int N = it.length-6;
                    for (int i=0; i<=N; i+=6) {
                        if (!"0x01".equals(it[i])) {
                            Slog.w(TAG, "Unknown virtual key type at elem #"
                                    + i + ": " + it[i] + " for device " + deviceName);
                            continue;
                        }
                        try {
                            VirtualKeyDefinition key = new VirtualKeyDefinition();
                            key.scanCode = Integer.parseInt(it[i+1]);
                            key.centerX = Integer.parseInt(it[i+2]);
                            key.centerY = Integer.parseInt(it[i+3]);
                            key.width = Integer.parseInt(it[i+4]);
                            key.height = Integer.parseInt(it[i+5]);
                            if (DEBUG_VIRTUAL_KEYS) Slog.v(TAG, "Virtual key "
                                    + key.scanCode + ": center=" + key.centerX + ","
                                    + key.centerY + " size=" + key.width + "x"
                                    + key.height);
                            keys.add(key);
                        } catch (NumberFormatException e) {
                            Slog.w(TAG, "Bad number in virtual key definition at region "
                                    + i + " in: " + str + " for device " + deviceName, e);
                        }
                    }
                }
                br.close();
            } catch (FileNotFoundException e) {
                Slog.i(TAG, "No virtual keys found for device " + deviceName + ".");
            } catch (IOException e) {
                Slog.w(TAG, "Error reading virtual keys for device " + deviceName + ".", e);
            }
            
            return keys.toArray(new VirtualKeyDefinition[keys.size()]);
        }
        
        @SuppressWarnings("unused")
        public InputDeviceCalibration getInputDeviceCalibration(String deviceName) {
            // Calibration is specified as a sequence of colon-delimited key value pairs.
            Properties properties = new Properties();
            File calibrationFile = new File(Environment.getRootDirectory(),
                    CALIBRATION_DIR_PATH + deviceName + ".idc");
            if (calibrationFile.exists()) {
                try {
                    properties.load(new FileInputStream(calibrationFile));
                } catch (IOException ex) {
                    Slog.w(TAG, "Error reading input device calibration properties for device "
                            + deviceName + " from " + calibrationFile + ".", ex);
                }
            } else {
                Slog.i(TAG, "No input device calibration properties found for device "
                        + deviceName + ".");
                return null;
            }
            
            InputDeviceCalibration calibration = new InputDeviceCalibration();
            calibration.keys = properties.keySet().toArray(new String[properties.size()]);
            calibration.values = properties.values().toArray(new String[properties.size()]);
            return calibration;
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
        public int getMaxEventsPerSecond() {
            int result = 0;
            try {
                result = Integer.parseInt(SystemProperties.get("windowsmgr.max_events_per_sec"));
            } catch (NumberFormatException e) {
            }
            if (result < 1) {
                result = 60;
            }
            return result;
        }
    }
}
