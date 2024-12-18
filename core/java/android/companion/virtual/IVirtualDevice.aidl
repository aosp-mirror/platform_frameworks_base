/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.companion.virtual;

import android.app.PendingIntent;
import android.companion.virtual.ActivityPolicyExemption;
import android.companion.virtual.IVirtualDeviceActivityListener;
import android.companion.virtual.IVirtualDeviceIntentInterceptor;
import android.companion.virtual.IVirtualDeviceSoundEffectListener;
import android.companion.virtual.audio.IAudioConfigChangedCallback;
import android.companion.virtual.audio.IAudioRoutingCallback;
import android.companion.virtual.sensor.VirtualSensor;
import android.companion.virtual.sensor.VirtualSensorConfig;
import android.companion.virtual.sensor.VirtualSensorEvent;
import android.companion.virtual.camera.VirtualCameraConfig;
import android.content.ComponentName;
import android.content.IntentFilter;
import android.graphics.Point;
import android.graphics.PointF;
import android.hardware.input.VirtualDpadConfig;
import android.hardware.input.VirtualKeyboardConfig;
import android.hardware.input.VirtualKeyEvent;
import android.hardware.input.VirtualMouseButtonEvent;
import android.hardware.input.VirtualMouseConfig;
import android.hardware.input.VirtualMouseRelativeEvent;
import android.hardware.input.VirtualMouseScrollEvent;
import android.hardware.input.VirtualRotaryEncoderConfig;
import android.hardware.input.VirtualRotaryEncoderScrollEvent;
import android.hardware.input.VirtualStylusButtonEvent;
import android.hardware.input.VirtualStylusConfig;
import android.hardware.input.VirtualStylusMotionEvent;
import android.hardware.input.VirtualTouchEvent;
import android.hardware.input.VirtualTouchscreenConfig;
import android.hardware.input.VirtualNavigationTouchpadConfig;
import android.os.ResultReceiver;

/**
 * Interface for a virtual device for communication between the system server and the process of
 * the owner of the virtual device.
 *
 * @hide
 */
interface IVirtualDevice {

    /**
     * Returns the CDM association ID of this virtual device.
     *
     * @see AssociationInfo#getId()
     */
    int getAssociationId();

    /**
     * Returns the unique ID of this virtual device.
     */
    int getDeviceId();

    /**
     * Returns the persistent ID of this virtual device.
     */
    String getPersistentDeviceId();

    /**
     * Returns the IDs of all virtual displays of this device.
     */
    int[] getDisplayIds();

    /**
     * Returns the device policy for the given policy type.
     */
    int getDevicePolicy(int policyType);

    /**
    * Returns whether the device has a valid microphone.
    */
    boolean hasCustomAudioInputSupport();

    /**
     * Closes the virtual device and frees all associated resources.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void close();

    /**
     * Specifies a policy for this virtual device.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void setDevicePolicy(int policyType, int devicePolicy);

    /**
     * Adds an exemption to the default activity launch policy.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void addActivityPolicyExemption(in ActivityPolicyExemption exemption);

    /**
     * Removes an exemption to the default activity launch policy.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void removeActivityPolicyExemption(in ActivityPolicyExemption exemption);

    /**
     * Specifies a policy for this virtual device on the given display.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void setDevicePolicyForDisplay(int displayId, int policyType, int devicePolicy);

    /**
     * Notifies that an audio session being started.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void onAudioSessionStarting(int displayId, IAudioRoutingCallback routingCallback,
            IAudioConfigChangedCallback configChangedCallback);

    /**
     * Notifies that an audio session has ended.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void onAudioSessionEnded();

    /**
     * Creates a new dpad and registers it with the input framework with the given token.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void createVirtualDpad(in VirtualDpadConfig config, IBinder token);

    /**
     * Creates a new keyboard and registers it with the input framework with the given token.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void createVirtualKeyboard(in VirtualKeyboardConfig config, IBinder token);

    /**
     * Creates a new mouse and registers it with the input framework with the given token.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void createVirtualMouse(in VirtualMouseConfig config, IBinder token);

    /**
     * Creates a new touchscreen and registers it with the input framework with the given token.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void createVirtualTouchscreen(in VirtualTouchscreenConfig config, IBinder token);

    /**
     * Creates a new navigation touchpad and registers it with the input framework with the given
     * token.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void createVirtualNavigationTouchpad(in VirtualNavigationTouchpadConfig config, IBinder token);

    /**
     * Creates a new stylus and registers it with the input framework with the given token.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void createVirtualStylus(in VirtualStylusConfig config, IBinder token);

    /**
     * Creates a new rotary encoder and registers it with the input framework with the given token.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void createVirtualRotaryEncoder(in VirtualRotaryEncoderConfig config, IBinder token);

    /**
     * Removes the input device corresponding to the given token from the framework.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void unregisterInputDevice(IBinder token);

    /**
     * Returns the ID of the device corresponding to the given token, as registered with the input
     * framework.
     */
    int getInputDeviceId(IBinder token);

    /**
     * Injects a key event to the virtual dpad corresponding to the given token.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    boolean sendDpadKeyEvent(IBinder token, in VirtualKeyEvent event);

    /**
     * Injects a key event to the virtual keyboard corresponding to the given token.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    boolean sendKeyEvent(IBinder token, in VirtualKeyEvent event);

    /**
     * Injects a button event to the virtual mouse corresponding to the given token.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    boolean sendButtonEvent(IBinder token, in VirtualMouseButtonEvent event);

    /**
     * Injects a relative event to the virtual mouse corresponding to the given token.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    boolean sendRelativeEvent(IBinder token, in VirtualMouseRelativeEvent event);

    /**
     * Injects a scroll event to the virtual mouse corresponding to the given token.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    boolean sendScrollEvent(IBinder token, in VirtualMouseScrollEvent event);

    /**
    * Injects a touch event to the virtual touch input device corresponding to the given token.
    */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    boolean sendTouchEvent(IBinder token, in VirtualTouchEvent event);

    /**
     * Injects a motion event from the virtual stylus input device corresponding to the given token.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    boolean sendStylusMotionEvent(IBinder token, in VirtualStylusMotionEvent event);

    /**
     * Injects a button event from the virtual stylus input device corresponding to the given token.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    boolean sendStylusButtonEvent(IBinder token, in VirtualStylusButtonEvent event);

    /**
     * Injects a scroll event from the virtual rotary encoder corresponding to the given token.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    boolean sendRotaryEncoderScrollEvent(IBinder token, in VirtualRotaryEncoderScrollEvent event);

    /**
     * Returns all virtual sensors created for this device.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    List<VirtualSensor> getVirtualSensorList();

    /**
     * Sends an event to the virtual sensor corresponding to the given token.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    boolean sendSensorEvent(IBinder token, in VirtualSensorEvent event);

    /**
     * Launches a pending intent on the given display that is owned by this virtual device.
     */
    void launchPendingIntent(int displayId, in PendingIntent pendingIntent,
            in ResultReceiver resultReceiver);

    /**
     * Returns the current cursor position of the mouse corresponding to the given token, in x and y
     * coordinates.
     */
    PointF getCursorPosition(IBinder token);

    /** Sets whether to show or hide the cursor while this virtual device is active. */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void setShowPointerIcon(boolean showPointerIcon);

    /** Sets an IME policy for the given display. */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void setDisplayImePolicy(int displayId, int policy);

    /**
     * Registers an intent interceptor that will intercept an intent attempting to launch
     * when matching the provided IntentFilter and calls the callback with the intercepted
     * intent.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void registerIntentInterceptor(in IVirtualDeviceIntentInterceptor intentInterceptor,
            in IntentFilter filter);

    /**
     * Unregisters a previously registered intent interceptor.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void unregisterIntentInterceptor(in IVirtualDeviceIntentInterceptor intentInterceptor);

    /**
     * Creates a new virtual camera and registers it with the virtual camera service.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void registerVirtualCamera(in VirtualCameraConfig camera);

    /**
     * Destroys the virtual camera with given config and unregisters it from the virtual camera
     * service.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void unregisterVirtualCamera(in VirtualCameraConfig camera);

    /**
     * Returns the id of the virtual camera with given config.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    String getVirtualCameraId(in VirtualCameraConfig camera);

    /**
     * Setter for listeners that live in the client process, namely in
     * {@link android.companion.virtual.VirtualDeviceInternal}.
     *
     * This is needed for virtual devices that are created by the system, as the VirtualDeviceImpl
     * object is created before the returned VirtualDeviceInternal one.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void setListeners(in IVirtualDeviceActivityListener activityListener,
            in IVirtualDeviceSoundEffectListener soundEffectListener);
}
