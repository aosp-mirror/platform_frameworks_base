/*
 * Copyright (C) 2018 The Android Open Source Project
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

/**
 * HdmiAudioSystemClient represents HDMI-CEC logical device of type Audio System
 * in the Android system which acts as an audio system device such as sound bar.
 *
 * <p>HdmiAudioSystemClient provides methods that control, get information from TV/Display device
 * connected through HDMI bus.
 *
 * @hide
 */
public final class HdmiAudioSystemClient extends HdmiClient {
    private static final String TAG = "HdmiAudioSystemClient";

    /* package */ HdmiAudioSystemClient(IHdmiControlService service) {
        super(service);
    }

    /**
     * TODO(b/110094868): unhide and add @SystemApi for Q
     * @hide
     */
    @Override
    public int getDeviceType() {
        return HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM;
    }
}
