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

/** @hide */
interface IDeviceStateManagerCallback {
    /**
     * Called in response to a change in {@link DeviceStateInfo}.
     *
     * @param info the new device state info.
     *
     * @see DeviceStateInfo
     */
    oneway void onDeviceStateInfoChanged(in DeviceStateInfo info);

    /**
     * Called to notify the callback that a request has become active. Guaranteed to be called
     * after a subsequent call to {@link #onDeviceStateInfoChanged(DeviceStateInfo)} if the request
     * becoming active resulted in a change of device state info.
     *
     * @param token the request token previously registered with
     *        {@link IDeviceStateManager#requestState(IBinder, int, int)}
     */
    oneway void onRequestActive(IBinder token);

    /**
     * Called to notify the callback that a request has become suspended. Guaranteed to be called
     * before a subsequent call to {@link #onDeviceStateInfoChanged(DeviceStateInfo)} if the request
     * becoming suspended resulted in a change of device state info.
     *
     * @param token the request token previously registered with
     *        {@link IDeviceStateManager#requestState(IBinder, int, int)}
     */
    oneway void onRequestSuspended(IBinder token);

    /**
     * Called to notify the callback that a request has become canceled. No further callbacks will
     * be triggered for this request. Guaranteed to be called before a subsequent call to
     * {@link #onDeviceStateInfoChanged(DeviceStateInfo)} if the request becoming canceled resulted
     * in a change of device state info.
     *
     * @param token the request token previously registered with
     *        {@link IDeviceStateManager#requestState(IBinder, int, int)}
     */
    oneway void onRequestCanceled(IBinder token);
}
