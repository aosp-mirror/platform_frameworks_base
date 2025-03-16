/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.tv.extension.signal;

import android.media.tv.extension.signal.IHdmiSignalInfoListener;
import android.os.Bundle;

/**
 * @hide
 */
interface IHdmiSignalInterface {
    // Register a listener for Hdmi Signal Info updates.
    void addHdmiSignalInfoListener(String inputId, in IHdmiSignalInfoListener listener);
    // Remove a listener for Hdmi Signal Info update notifications.
    void removeHdmiSignalInfoListener(String inputId, in IHdmiSignalInfoListener listener);
    // Obtain HdmiSignalInfo based on the inputId and sessionToken.
    Bundle getHdmiSignalInfo(String sessionToken);
    // Enable/disable low-latency decoding mode.
    void setLowLatency(String sessionToken, int mode);
    // Enable/disable force-VRR mode.
    void setForceVrr(String sessionToken, int mode);
}
