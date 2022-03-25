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
    /** Returns the current device state info. */
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
     * @param token the request token provided
     * @param state the state of device the request is asking for
     * @param flags any flags that correspond to the request
     *
     * @throws IllegalStateException if a callback has not yet been registered for the calling
     *         process.
     * @throws IllegalStateException if the supplied {@code token} has already been registered.
     * @throws IllegalArgumentException if the supplied {@code state} is not supported.
     */
    void requestState(IBinder token, int state, int flags);

    /**
     * Cancels the active request previously submitted with a call to
     * {@link #requestState(IBinder, int, int)}.
     *
     * @throws IllegalStateException if a callback has not yet been registered for the calling
     *         process.
     */
    void cancelStateRequest();
}
