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

package android.media;

import android.util.SparseIntArray;

/**
 * @hide
 * CANDIDATE FOR PUBLIC API
 */
public class AudioDevice {

    public static final int DEVICE_TYPE_UNKNOWN          = 0;
    public static final int DEVICE_TYPE_BUILTIN_EARPIECE = 1;
    public static final int DEVICE_TYPE_BUILTIN_SPEAKER  = 2;
    public static final int DEVICE_TYPE_WIRED_HEADSET    = 3;
    public static final int DEVICE_TYPE_WIRED_HEADPHONES = 4;
    public static final int DEVICE_TYPE_LINE_ANALOG      = 5;
    public static final int DEVICE_TYPE_LINE_DIGITAL     = 6;
    public static final int DEVICE_TYPE_BLUETOOTH_SCO    = 7;
    public static final int DEVICE_TYPE_BLUETOOTH_A2DP   = 8;
    public static final int DEVICE_TYPE_HDMI             = 9;
    public static final int DEVICE_TYPE_HDMI_ARC         = 10;
    public static final int DEVICE_TYPE_USB_DEVICE       = 11;
    public static final int DEVICE_TYPE_USB_ACCESSORY    = 12;
    public static final int DEVICE_TYPE_DOCK             = 13;
    public static final int DEVICE_TYPE_FM               = 14;
    public static final int DEVICE_TYPE_BUILTIN_MIC      = 15;
    public static final int DEVICE_TYPE_FM_TUNER         = 16;
    public static final int DEVICE_TYPE_TV_TUNER         = 17;
    public static final int DEVICE_TYPE_TELEPHONY        = 18;

    AudioDevicePortConfig mConfig;

    AudioDevice(AudioDevicePortConfig config) {
        mConfig = new AudioDevicePortConfig(config);
    }

    public boolean isInputDevice() {
        return (mConfig.port().role() == AudioPort.ROLE_SOURCE);
    }

    public boolean isOutputDevice() {
        return (mConfig.port().role() == AudioPort.ROLE_SINK);
    }

    public int getDeviceType() {
        return INT_TO_EXT_DEVICE_MAPPING.get(mConfig.port().type(), DEVICE_TYPE_UNKNOWN);
    }

    public String getAddress() {
        return mConfig.port().address();
    }

    /** @hide */
    public static int convertDeviceTypeToInternalDevice(int deviceType) {
        return EXT_TO_INT_DEVICE_MAPPING.get(deviceType, AudioSystem.DEVICE_NONE);
    }

    /** @hide */
    public static int convertInternalDeviceToDeviceType(int intDevice) {
        return INT_TO_EXT_DEVICE_MAPPING.get(intDevice, DEVICE_TYPE_UNKNOWN);
    }

    private static final SparseIntArray INT_TO_EXT_DEVICE_MAPPING;

    private static final SparseIntArray EXT_TO_INT_DEVICE_MAPPING;

    static {
        INT_TO_EXT_DEVICE_MAPPING = new SparseIntArray();
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_EARPIECE, DEVICE_TYPE_BUILTIN_EARPIECE);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_SPEAKER, DEVICE_TYPE_BUILTIN_SPEAKER);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_WIRED_HEADSET, DEVICE_TYPE_WIRED_HEADSET);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_WIRED_HEADPHONE, DEVICE_TYPE_WIRED_HEADPHONES);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_BLUETOOTH_SCO, DEVICE_TYPE_BLUETOOTH_SCO);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_HEADSET, DEVICE_TYPE_BLUETOOTH_SCO);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_CARKIT, DEVICE_TYPE_BLUETOOTH_SCO);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, DEVICE_TYPE_BLUETOOTH_A2DP);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES, DEVICE_TYPE_BLUETOOTH_A2DP);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP_SPEAKER, DEVICE_TYPE_BLUETOOTH_A2DP);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_HDMI, DEVICE_TYPE_HDMI);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_ANLG_DOCK_HEADSET, DEVICE_TYPE_DOCK);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_DGTL_DOCK_HEADSET, DEVICE_TYPE_DOCK);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_USB_ACCESSORY, DEVICE_TYPE_USB_ACCESSORY);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_USB_DEVICE, DEVICE_TYPE_USB_DEVICE);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_TELEPHONY_TX, DEVICE_TYPE_TELEPHONY);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_LINE, DEVICE_TYPE_LINE_ANALOG);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_HDMI_ARC, DEVICE_TYPE_HDMI_ARC);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_SPDIF, DEVICE_TYPE_LINE_DIGITAL);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_OUT_FM, DEVICE_TYPE_FM);

        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_BUILTIN_MIC, DEVICE_TYPE_BUILTIN_MIC);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_BLUETOOTH_SCO_HEADSET, DEVICE_TYPE_BLUETOOTH_SCO);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_WIRED_HEADSET, DEVICE_TYPE_WIRED_HEADSET);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_HDMI, DEVICE_TYPE_HDMI);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_TELEPHONY_RX, DEVICE_TYPE_TELEPHONY);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_BACK_MIC, DEVICE_TYPE_BUILTIN_MIC);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_ANLG_DOCK_HEADSET, DEVICE_TYPE_DOCK);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_DGTL_DOCK_HEADSET, DEVICE_TYPE_DOCK);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_USB_ACCESSORY, DEVICE_TYPE_USB_ACCESSORY);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_USB_DEVICE, DEVICE_TYPE_USB_DEVICE);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_FM_TUNER, DEVICE_TYPE_FM_TUNER);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_TV_TUNER, DEVICE_TYPE_TV_TUNER);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_LINE, DEVICE_TYPE_LINE_ANALOG);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_SPDIF, DEVICE_TYPE_LINE_DIGITAL);
        INT_TO_EXT_DEVICE_MAPPING.put(AudioSystem.DEVICE_IN_BLUETOOTH_A2DP, DEVICE_TYPE_BLUETOOTH_A2DP);

        // not covered here, legacy
        //AudioSystem.DEVICE_OUT_REMOTE_SUBMIX
        //AudioSystem.DEVICE_IN_REMOTE_SUBMIX

        // privileges mapping to output device
        EXT_TO_INT_DEVICE_MAPPING = new SparseIntArray();
        EXT_TO_INT_DEVICE_MAPPING.put(DEVICE_TYPE_BUILTIN_EARPIECE, AudioSystem.DEVICE_OUT_EARPIECE);
        EXT_TO_INT_DEVICE_MAPPING.put(DEVICE_TYPE_BUILTIN_SPEAKER, AudioSystem.DEVICE_OUT_SPEAKER);
        EXT_TO_INT_DEVICE_MAPPING.put(DEVICE_TYPE_WIRED_HEADSET, AudioSystem.DEVICE_OUT_WIRED_HEADSET);
        EXT_TO_INT_DEVICE_MAPPING.put(DEVICE_TYPE_WIRED_HEADPHONES, AudioSystem.DEVICE_OUT_WIRED_HEADPHONE);
        EXT_TO_INT_DEVICE_MAPPING.put(DEVICE_TYPE_LINE_ANALOG, AudioSystem.DEVICE_OUT_LINE);
        EXT_TO_INT_DEVICE_MAPPING.put(DEVICE_TYPE_LINE_DIGITAL, AudioSystem.DEVICE_OUT_SPDIF);
        EXT_TO_INT_DEVICE_MAPPING.put(DEVICE_TYPE_BLUETOOTH_SCO, AudioSystem.DEVICE_OUT_BLUETOOTH_SCO);
        EXT_TO_INT_DEVICE_MAPPING.put(DEVICE_TYPE_BLUETOOTH_A2DP, AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP);
        EXT_TO_INT_DEVICE_MAPPING.put(DEVICE_TYPE_HDMI, AudioSystem.DEVICE_OUT_HDMI);
        EXT_TO_INT_DEVICE_MAPPING.put(DEVICE_TYPE_HDMI_ARC, AudioSystem.DEVICE_OUT_HDMI_ARC);
        EXT_TO_INT_DEVICE_MAPPING.put(DEVICE_TYPE_USB_DEVICE, AudioSystem.DEVICE_OUT_USB_DEVICE);
        EXT_TO_INT_DEVICE_MAPPING.put(DEVICE_TYPE_USB_ACCESSORY, AudioSystem.DEVICE_OUT_USB_ACCESSORY);
        EXT_TO_INT_DEVICE_MAPPING.put(DEVICE_TYPE_DOCK, AudioSystem.DEVICE_OUT_ANLG_DOCK_HEADSET);
        EXT_TO_INT_DEVICE_MAPPING.put(DEVICE_TYPE_FM, AudioSystem.DEVICE_OUT_FM);
        EXT_TO_INT_DEVICE_MAPPING.put(DEVICE_TYPE_BUILTIN_MIC, AudioSystem.DEVICE_IN_BUILTIN_MIC);
        EXT_TO_INT_DEVICE_MAPPING.put(DEVICE_TYPE_FM_TUNER, AudioSystem.DEVICE_IN_FM_TUNER);
        EXT_TO_INT_DEVICE_MAPPING.put(DEVICE_TYPE_TV_TUNER, AudioSystem.DEVICE_IN_TV_TUNER);
        EXT_TO_INT_DEVICE_MAPPING.put(DEVICE_TYPE_TELEPHONY, AudioSystem.DEVICE_OUT_TELEPHONY_TX);
    }
}

