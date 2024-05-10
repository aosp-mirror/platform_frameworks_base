/**
 * Copyright (c) 2020, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware.devicestate;

import android.hardware.devicestate.DeviceStateInfo;
import android.hardware.devicestate.IDeviceStateManagerCallback;

/** @hide */
interface IDeviceStateManager {
    /**
     * Returns the current device state info. This {@link DeviceStateInfo} object will always
     * include the list of supported states. If there has been no base state or committed state
     * provided yet to the system server, this {@link DeviceStateInfo} object will include
     * {@link DeviceStateManager#INVALID_DEVICE_STATE} for each respectively.
     *
     * This method should not be used to notify callback clients.
     */
    DeviceStateInfo getDeviceStateInfo();

    /**
     * Registers a callback to receive notifications from the device state manager. Only one
     * callback can be registered per-process.
     * <p>
     * As the callback mechanism is used to alert the caller of changes to request status a callback
     * <b>MUST</b> be registered before calling {@link #requestState(IBinder, int, int)} or
     * {@link #cancelRequest(IBinder)}, otherwise an exception will be thrown.
     *
     * @throws SecurityException if a callback is already registered for the calling process.
     */
    void registerCallback(in IDeviceStateManagerCallback callback);

    /**
     * Requests that the device enter the supplied {@code state}. A callback <b>MUST</b> have been
     * previously registered with {@link #registerCallback(IDeviceStateManagerCallback)} before a
     * call to this method.
     *
     * Requesting a state does not cancel a base state override made through
     * {@link #requestBaseStateOverride}, but will still attempt to put the device into the
     * supplied {@code state}.
     *
     * @param token the request token provided
     * @param state the state of device the request is asking for
     * @param flags any flags that correspond to the request
     *
     * @throws IllegalStateException if a callback has not yet been registered for the calling
     *         process.
     * @throws IllegalStateException if the supplied {@code token} has already been registered.
     * @throws IllegalArgumentException if the supplied {@code state} is not supported.
     */
    @JavaPassthrough(annotation=
            "@android.annotation.RequiresPermission(value=android.Manifest.permission.CONTROL_DEVICE_STATE, conditional=true)")
    void requestState(IBinder token, int state, int flags);

    /**
     * Cancels the active request previously submitted with a call to
     * {@link #requestState(IBinder, int, int)}. Will have no effect on any base state override that
     * was previously requested with {@link #requestBaseStateOverride}.
     *
     * @throws IllegalStateException if a callback has not yet been registered for the calling
     *         process.
     */
    @JavaPassthrough(annotation=
            "@android.annotation.RequiresPermission(value=android.Manifest.permission.CONTROL_DEVICE_STATE, conditional=true)")
    void cancelStateRequest();

    /**
     * Requests that the device's base state be overridden to the supplied {@code state}. A callback
     * <b>MUST</b> have been previously registered with
     * {@link #registerCallback(IDeviceStateManagerCallback)} before a call to this method.
     *
     * This method should only be used for testing, when you want to simulate the device physically
     * changing states. If you are looking to change device state for a feature, where the system
     * should still be aware that the physical state is different than the emulated state, use
     * {@link #requestState}.
     *
     * @param token the request token provided
     * @param state the state of device the request is asking for
     * @param flags any flags that correspond to the request
     *
     * @throws IllegalStateException if a callback has not yet been registered for the calling
     *         process.
     * @throws IllegalStateException if the supplied {@code token} has already been registered.
     * @throws IllegalArgumentException if the supplied {@code state} is not supported.
     */
    @JavaPassthrough(annotation=
        "@android.annotation.RequiresPermission(android.Manifest.permission.CONTROL_DEVICE_STATE)")
    void requestBaseStateOverride(IBinder token, int state, int flags);

    /**
     * Cancels the active base state request previously submitted with a call to
     * {@link #overrideBaseState(IBinder, int, int)}.
     *
     * @throws IllegalStateException if a callback has not yet been registered for the calling
     *         process.
     */
    @JavaPassthrough(annotation=
        "@android.annotation.RequiresPermission(android.Manifest.permission.CONTROL_DEVICE_STATE)")
    void cancelBaseStateOverride();

    /**
    * Notifies the system service that the educational overlay that was launched
    * before entering a requested state was dismissed or closed, and provides
    * the system information on if the pairing mode should be canceled or not.
    *
    * This should only be called from the overlay itself.
    */
    @EnforcePermission("CONTROL_DEVICE_STATE")
    @JavaPassthrough(annotation=
        "@android.annotation.RequiresPermission(android.Manifest.permission.CONTROL_DEVICE_STATE)")
    void onStateRequestOverlayDismissed(boolean shouldCancelRequest);
}
