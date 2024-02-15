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

import android.graphics.Rect;
import android.hardware.input.HostUsiVersion;
import android.hardware.input.InputDeviceIdentifier;
import android.hardware.input.KeyboardLayout;
import android.hardware.input.IInputDevicesChangedListener;
import android.hardware.input.IInputDeviceBatteryListener;
import android.hardware.input.IInputDeviceBatteryState;
import android.hardware.input.IKeyboardBacklightListener;
import android.hardware.input.IKeyboardBacklightState;
import android.hardware.input.IStickyModifierStateListener;
import android.hardware.input.ITabletModeChangedListener;
import android.hardware.input.TouchCalibration;
import android.os.CombinedVibration;
import android.hardware.input.IInputSensorEventListener;
import android.hardware.input.InputSensorInfo;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.os.IBinder;
import android.os.IVibratorStateListener;
import android.os.VibrationEffect;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputMonitor;
import android.view.PointerIcon;
import android.view.KeyCharacterMap;
import android.view.VerifiedInputEvent;

/** @hide */
interface IInputManager {
    // Gets the current VelocityTracker strategy
    String getVelocityTrackerStrategy();
    // Gets input device information.
    InputDevice getInputDevice(int deviceId);
    int[] getInputDeviceIds();

    // Enable/disable input device.
    boolean isInputDeviceEnabled(int deviceId);
    void enableInputDevice(int deviceId);
    void disableInputDevice(int deviceId);

    // Reports whether the hardware supports the given keys; returns true if successful
    boolean hasKeys(int deviceId, int sourceMask, in int[] keyCodes, out boolean[] keyExists);

    // Returns the keyCode produced when pressing the key at the specified location, given the
    // active keyboard layout.
    int getKeyCodeForKeyLocation(int deviceId, in int locationKeyCode);

    KeyCharacterMap getKeyCharacterMap(String layoutDescriptor);

    // Returns the mouse pointer speed.
    int getMousePointerSpeed();

    // Temporarily changes the pointer speed.
    void tryPointerSpeed(int speed);

    // Injects an input event into the system. The caller must have the INJECT_EVENTS permssion.
    // This method exists only for compatibility purposes and may be removed in a future release.
    @UnsupportedAppUsage
    boolean injectInputEvent(in InputEvent ev, int mode);

    // Injects an input event into the system. The caller must have the INJECT_EVENTS permission.
    // The caller can target windows owned by a certain UID by providing a valid UID, or by
    // providing {@link android.os.Process#INVALID_UID} to target all windows.
    boolean injectInputEventToTarget(in InputEvent ev, int mode, int targetUid);

    VerifiedInputEvent verifyInputEvent(in InputEvent ev);

    // Calibrate input device position
    TouchCalibration getTouchCalibrationForInputDevice(String inputDeviceDescriptor, int rotation);
    void setTouchCalibrationForInputDevice(String inputDeviceDescriptor, int rotation,
            in TouchCalibration calibration);

    // Keyboard layouts configuration.
    KeyboardLayout[] getKeyboardLayouts();

    KeyboardLayout[] getKeyboardLayoutsForInputDevice(in InputDeviceIdentifier identifier);

    KeyboardLayout getKeyboardLayout(String keyboardLayoutDescriptor);

    String getCurrentKeyboardLayoutForInputDevice(in InputDeviceIdentifier identifier);

    @EnforcePermission("SET_KEYBOARD_LAYOUT")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.SET_KEYBOARD_LAYOUT)")
    void setCurrentKeyboardLayoutForInputDevice(in InputDeviceIdentifier identifier,
            String keyboardLayoutDescriptor);

    String[] getEnabledKeyboardLayoutsForInputDevice(in InputDeviceIdentifier identifier);

    @EnforcePermission("SET_KEYBOARD_LAYOUT")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.SET_KEYBOARD_LAYOUT)")
    void addKeyboardLayoutForInputDevice(in InputDeviceIdentifier identifier,
            String keyboardLayoutDescriptor);

    @EnforcePermission("SET_KEYBOARD_LAYOUT")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.SET_KEYBOARD_LAYOUT)")
    void removeKeyboardLayoutForInputDevice(in InputDeviceIdentifier identifier,
            String keyboardLayoutDescriptor);

    // New Keyboard layout config APIs
    String getKeyboardLayoutForInputDevice(in InputDeviceIdentifier identifier, int userId,
            in InputMethodInfo imeInfo, in InputMethodSubtype imeSubtype);

    @EnforcePermission("SET_KEYBOARD_LAYOUT")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.SET_KEYBOARD_LAYOUT)")
    void setKeyboardLayoutForInputDevice(in InputDeviceIdentifier identifier, int userId,
            in InputMethodInfo imeInfo, in InputMethodSubtype imeSubtype,
            String keyboardLayoutDescriptor);

    KeyboardLayout[] getKeyboardLayoutListForInputDevice(in InputDeviceIdentifier identifier,
            int userId, in InputMethodInfo imeInfo, in InputMethodSubtype imeSubtype);

    // Modifier key remapping APIs.
    @EnforcePermission("REMAP_MODIFIER_KEYS")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.REMAP_MODIFIER_KEYS)")
    void remapModifierKey(int fromKey, int toKey);

    @EnforcePermission("REMAP_MODIFIER_KEYS")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.REMAP_MODIFIER_KEYS)")
    void clearAllModifierKeyRemappings();

    @EnforcePermission("REMAP_MODIFIER_KEYS")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.REMAP_MODIFIER_KEYS)")
    Map getModifierKeyRemapping();

    // Registers an input devices changed listener.
    void registerInputDevicesChangedListener(IInputDevicesChangedListener listener);

    // Queries whether the device is currently in tablet mode
    int isInTabletMode();
    // Registers a tablet mode change listener
    void registerTabletModeChangedListener(ITabletModeChangedListener listener);

    // Queries whether the device's microphone is muted by switch
    int isMicMuted();

    // Input device vibrator control.
    void vibrate(int deviceId, in VibrationEffect effect, IBinder token);
    void vibrateCombined(int deviceId, in CombinedVibration vibration, IBinder token);
    void cancelVibrate(int deviceId, IBinder token);
    int[] getVibratorIds(int deviceId);
    boolean isVibrating(int deviceId);
    boolean registerVibratorStateListener(int deviceId, in IVibratorStateListener listener);
    boolean unregisterVibratorStateListener(int deviceId, in IVibratorStateListener listener);

    IInputDeviceBatteryState getBatteryState(int deviceId);

    void setPointerIconType(int typeId);
    void setCustomPointerIcon(in PointerIcon icon);
    boolean setPointerIcon(in PointerIcon icon, int displayId, int deviceId, int pointerId,
            in IBinder inputToken);

    oneway void requestPointerCapture(IBinder inputChannelToken, boolean enabled);

    /** Create an input monitor for gestures. */
    InputMonitor monitorGestureInput(IBinder token, String name, int displayId);

    // Add a runtime association between the input port and the display port. This overrides any
    // static associations.
    void addPortAssociation(in String inputPort, int displayPort);
    // Remove the runtime association between the input port and the display port. Any existing
    // static association for the cleared input port will be restored.
    void removePortAssociation(in String inputPort);

    // Add a runtime association between the input device and display.
    void addUniqueIdAssociation(in String inputPort, in String displayUniqueId);
    // Remove the runtime association between the input device and display.
    void removeUniqueIdAssociation(in String inputPort);

    InputSensorInfo[] getSensorList(int deviceId);

    boolean registerSensorListener(IInputSensorEventListener listener);

    void unregisterSensorListener(IInputSensorEventListener listener);

    boolean enableSensor(int deviceId, int sensorType, int samplingPeriodUs,
                int maxBatchReportLatencyUs);

    void disableSensor(int deviceId, int sensorType);

    boolean flushSensor(int deviceId, int sensorType);

    List<Light> getLights(int deviceId);

    LightState getLightState(int deviceId, int lightId);

    void setLightStates(int deviceId, in int[] lightIds, in LightState[] states, in IBinder token);

    void openLightSession(int deviceId, String opPkg, in IBinder token);

    void closeLightSession(int deviceId, in IBinder token);

    void cancelCurrentTouch();

    void registerBatteryListener(int deviceId, IInputDeviceBatteryListener listener);

    void unregisterBatteryListener(int deviceId, IInputDeviceBatteryListener listener);

    // Get the bluetooth address of an input device if known, returning null if it either is not
    // connected via bluetooth or if the address cannot be determined.
    @EnforcePermission("BLUETOOTH")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.BLUETOOTH)")
    String getInputDeviceBluetoothAddress(int deviceId);

    @EnforcePermission("MONITOR_INPUT")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.MONITOR_INPUT)")
    void pilferPointers(IBinder inputChannelToken);

    @EnforcePermission("MONITOR_KEYBOARD_BACKLIGHT")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.MONITOR_KEYBOARD_BACKLIGHT)")
    void registerKeyboardBacklightListener(IKeyboardBacklightListener listener);

    @EnforcePermission("MONITOR_KEYBOARD_BACKLIGHT")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.MONITOR_KEYBOARD_BACKLIGHT)")
    void unregisterKeyboardBacklightListener(IKeyboardBacklightListener listener);

    HostUsiVersion getHostUsiVersionFromDisplayConfig(int displayId);

    @EnforcePermission("MONITOR_STICKY_MODIFIER_STATE")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.MONITOR_STICKY_MODIFIER_STATE)")
    void registerStickyModifierStateListener(IStickyModifierStateListener listener);

    @EnforcePermission("MONITOR_STICKY_MODIFIER_STATE")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.MONITOR_STICKY_MODIFIER_STATE)")
    void unregisterStickyModifierStateListener(IStickyModifierStateListener listener);
}
