/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * the CEC availability change event.
 *
 * @hide
 */
oneway interface IHdmiControlStatusChangeListener {

    /**
     * Called when HDMI Control (CEC) is enabled/disabled.
     *
     * @param isCecEnabled status of HDMI Control
     * {@link android.provider.Settings.Global#HDMI_CONTROL_ENABLED}: {@code true} if enabled.
     * @param isCecAvailable status of CEC support of the connected display (the TV).
     * {@code true} if supported.
     *
     * Note: Value of isCecAvailable is only valid when isCecEnabled is true.
     **/
    void onStatusChange(boolean isCecEnabled, boolean isCecAvailable);
}
