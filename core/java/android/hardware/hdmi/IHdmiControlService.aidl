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

import android.hardware.hdmi.HdmiCecDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.hdmi.IHdmiDeviceEventListener;
import android.hardware.hdmi.IHdmiHotplugEventListener;

import java.util.List;

/**
 * Binder interface that clients running in the application process
 * will use to perform HDMI-CEC features by communicating with other devices
 * on the bus.
 *
 * @hide
 */
interface IHdmiControlService {
    int[] getSupportedTypes();
    void oneTouchPlay(IHdmiControlCallback callback);
    void queryDisplayStatus(IHdmiControlCallback callback);
    void addHotplugEventListener(IHdmiHotplugEventListener listener);
    void removeHotplugEventListener(IHdmiHotplugEventListener listener);
    void addDeviceEventListener(IHdmiDeviceEventListener listener);
    void deviceSelect(int logicalAddress, IHdmiControlCallback callback);
    void portSelect(int portId, IHdmiControlCallback callback);
    void sendKeyEvent(int keyCode);
    List<HdmiPortInfo> getPortInfo();
}
