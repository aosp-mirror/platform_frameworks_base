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
import android.companion.virtual.IVirtualDeviceIntentInterceptor;
import android.companion.virtual.audio.IAudioConfigChangedCallback;
import android.companion.virtual.audio.IAudioRoutingCallback;
import android.companion.virtual.sensor.IVirtualSensorStateChangeCallback;
import android.companion.virtual.sensor.VirtualSensorConfig;
import android.companion.virtual.sensor.VirtualSensorEvent;
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
import android.hardware.input.VirtualTouchEvent;
import android.hardware.input.VirtualTouchscreenConfig;
import android.hardware.input.VirtualNavigationTouchpadConfig;
import android.os.ResultReceiver;

/**
 * Interface for a virtual device.
 *
 * @hide
 */
interface IVirtualDevice {

    /**
     * Returns the association ID for this virtual device.
     *
     * @see AssociationInfo#getId()
     */
    int getAssociationId();

    /**
     * Returns the unique device ID for this virtual device.
     */
    int getDeviceId();

    /**
     * Closes the virtual device and frees all associated resources.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void close();

    /**
     * Notifies of an audio session being started.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void onAudioSessionStarting(
            int displayId,
            IAudioRoutingCallback routingCallback,
            IAudioConfigChangedCallback configChangedCallback);

    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void onAudioSessionEnded();

    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void createVirtualDpad(
            in VirtualDpadConfig config,
            IBinder token);
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void createVirtualKeyboard(
            in VirtualKeyboardConfig config,
            IBinder token);
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void createVirtualMouse(
            in VirtualMouseConfig config,
            IBinder token);
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void createVirtualTouchscreen(
            in VirtualTouchscreenConfig config,
            IBinder token);
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void createVirtualNavigationTouchpad(
            in VirtualNavigationTouchpadConfig config,
            IBinder token);
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void unregisterInputDevice(IBinder token);
    int getInputDeviceId(IBinder token);
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    boolean sendDpadKeyEvent(IBinder token, in VirtualKeyEvent event);
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    boolean sendKeyEvent(IBinder token, in VirtualKeyEvent event);
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    boolean sendButtonEvent(IBinder token, in VirtualMouseButtonEvent event);
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    boolean sendRelativeEvent(IBinder token, in VirtualMouseRelativeEvent event);
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    boolean sendScrollEvent(IBinder token, in VirtualMouseScrollEvent event);
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    boolean sendTouchEvent(IBinder token, in VirtualTouchEvent event);

    /**
     * Creates a virtual sensor, capable of injecting sensor events into the system.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void createVirtualSensor(IBinder tokenm, in VirtualSensorConfig config);

    /**
     * Removes the sensor corresponding to the given token from the system.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void unregisterSensor(IBinder token);

    /**
     * Sends an event to the virtual sensor corresponding to the given token.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    boolean sendSensorEvent(IBinder token, in VirtualSensorEvent event);

    /**
     * Launches a pending intent on the given display that is owned by this virtual device.
     */
    void launchPendingIntent(
            int displayId, in PendingIntent pendingIntent, in ResultReceiver resultReceiver);
    PointF getCursorPosition(IBinder token);

    /** Sets whether to show or hide the cursor while this virtual device is active. */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void setShowPointerIcon(boolean showPointerIcon);

    /**
     * Registers an intent interceptor that will intercept an intent attempting to launch
     * when matching the provided IntentFilter and calls the callback with the intercepted
     * intent.
     */
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void registerIntentInterceptor(
            in IVirtualDeviceIntentInterceptor intentInterceptor, in IntentFilter filter);
    @EnforcePermission("CREATE_VIRTUAL_DEVICE")
    void unregisterIntentInterceptor(in IVirtualDeviceIntentInterceptor intentInterceptor);
}
