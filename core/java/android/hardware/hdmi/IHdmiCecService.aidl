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

import android.hardware.hdmi.HdmiCecMessage;
import android.hardware.hdmi.IHdmiCecListener;
import android.os.IBinder;

/**
 * Binder interface that components running in the appplication process
 * will use to enable HDMI-CEC protocol exchange with other devices.
 *
 * @hide
 */
interface IHdmiCecService {
    IBinder allocateLogicalDevice(int type, IHdmiCecListener listener);
    void removeServiceListener(IBinder b, IHdmiCecListener listener);
    void setOsdName(IBinder b, String name);
    void sendActiveSource(IBinder b);
    void sendInactiveSource(IBinder b);
    void sendImageViewOn(IBinder b);
    void sendTextViewOn(IBinder b);
    void sendGiveDevicePowerStatus(IBinder b, int address);
    void sendMessage(IBinder b, in HdmiCecMessage message);
}

