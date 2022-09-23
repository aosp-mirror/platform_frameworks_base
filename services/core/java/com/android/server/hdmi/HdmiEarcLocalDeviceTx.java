/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.hdmi;

import static com.android.server.hdmi.Constants.HDMI_EARC_STATUS_ARC_PENDING;
import static com.android.server.hdmi.Constants.HDMI_EARC_STATUS_EARC_CONNECTED;
import static com.android.server.hdmi.Constants.HDMI_EARC_STATUS_IDLE;

import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.AudioDescriptor;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioProfile;
import android.util.IndentingPrintWriter;

import java.util.ArrayList;

/**
 * Represents a local eARC device of type TX residing in the Android system.
 * Only TV panel devices can have a local eARC TX device.
 */
public class HdmiEarcLocalDeviceTx extends HdmiEarcLocalDevice {
    private static final String TAG = "HdmiEarcLocalDeviceTx";

    HdmiEarcLocalDeviceTx(HdmiControlService service) {
        super(service, HdmiDeviceInfo.DEVICE_TV);
    }

    protected void handleEarcStateChange(@Constants.EarcStatus int status) {
        synchronized (mLock) {
            HdmiLogger.debug(TAG, "eARC state change [old:%b new %b]", mEarcStatus,
                    status);
            mEarcStatus = status;
        }
        if (status == HDMI_EARC_STATUS_IDLE) {
            notifyEarcStatusToAudioService(false);
        } else if (status == HDMI_EARC_STATUS_ARC_PENDING) {
            notifyEarcStatusToAudioService(false);
        } else if (status == HDMI_EARC_STATUS_EARC_CONNECTED) {
            notifyEarcStatusToAudioService(true);
        }
    }

    private void notifyEarcStatusToAudioService(boolean enabled) {
        AudioDeviceAttributes attributes = new AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_OUTPUT, AudioDeviceInfo.TYPE_HDMI_EARC, "", "",
                new ArrayList<AudioProfile>(), new ArrayList<AudioDescriptor>());
        mService.getAudioManager().setWiredDeviceConnectionState(attributes, enabled ? 1 : 0);
    }

    /** Dump internal status of HdmiEarcLocalDeviceTx object */
    protected void dump(final IndentingPrintWriter pw) {
        synchronized (mLock) {
            pw.println("TX, mEarcStatus: " + mEarcStatus);
        }
    }
}
