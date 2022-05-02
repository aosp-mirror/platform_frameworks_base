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

package com.android.server.hdmi;

import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.IHdmiControlCallback;

/**
 * Feature action that handles System Audio initiated by AVR devices.
 */
// Seq #33
final class SystemAudioActionFromAvr extends SystemAudioAction {
    /**
     * Constructor
     *
     * @param source {@link HdmiCecLocalDevice} instance
     * @param avrAddress logical address of AVR device
     * @param targetStatus Whether to enable the system audio mode or not
     * @param callback callback interface to be notified when it's done
     * @throws IllegalArgumentException if device type of tvAddress and avrAddress is invalid
     */
    SystemAudioActionFromAvr(HdmiCecLocalDevice source, int avrAddress,
            boolean targetStatus, IHdmiControlCallback callback) {
        super(source, avrAddress, targetStatus, callback);
        HdmiUtils.verifyAddressType(getSourceAddress(), HdmiDeviceInfo.DEVICE_TV);
    }

    @Override
    boolean start() {
        removeSystemAudioActionInProgress();
        handleSystemAudioActionFromAvr();
        return true;
    }

    private void handleSystemAudioActionFromAvr() {
        if (mTargetAudioStatus == tv().isSystemAudioActivated()) {
            finishWithCallback(HdmiControlManager.RESULT_SUCCESS);
            return;
        }
        if (tv().isProhibitMode()) {
            sendCommand(HdmiCecMessageBuilder.buildFeatureAbortCommand(
                    getSourceAddress(), mAvrLogicalAddress,
                    Constants.MESSAGE_SET_SYSTEM_AUDIO_MODE, Constants.ABORT_REFUSED));
            mTargetAudioStatus = false;
            sendSystemAudioModeRequest();
            return;
        }

        removeAction(SystemAudioAutoInitiationAction.class);

        if (mTargetAudioStatus) {
            setSystemAudioMode(true);
            finish();
        } else {
            setSystemAudioMode(false);
            finishWithCallback(HdmiControlManager.RESULT_SUCCESS);
        }
    }
}
