/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.hdmi;

import android.hardware.hdmi.HdmiDeviceInfo;

/**
 * Callback interface definition for HDMI client to get informed of
 * the CEC logical device status change event.
 *
 * @hide
 */
oneway interface IHdmiDeviceEventListener {

    /**
     * @param deviceInfo {@link HdmiDeviceInfo} of the logical device whose
     *                   status has changed
     * @param status device status. It should be one of the following values
     *        <ul>
     *        <li>{@link HdmiControlManager#DEVICE_EVENT_ADD_DEVICE}
     *        <li>{@link HdmiControlManager#DEVICE_EVENT_REMOVE_DEVICE}
     *        <li>{@link HdmiControlManager#DEVICE_EVENT_UPDATE_DEVICE}
     *        </ul>
     */
    void onStatusChanged(in HdmiDeviceInfo deviceInfo, in int status);
}
