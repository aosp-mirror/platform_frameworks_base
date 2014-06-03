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

import android.hardware.hdmi.HdmiCec;

/**
 * Feature action that handles System Audio initiated by AVR devices.
 */
final class SystemAudioActionFromAvr extends SystemAudioAction {
    /**
     * Constructor
     *
     * @param service {@link HdmiControlService} instance
     * @param tvAddress logical address of TV device
     * @param avrAddress logical address of AVR device
     * @param targetStatus Whether to enable the system audio mode or not
     * @throw IllegalArugmentException if device type of tvAddress and avrAddress is invalid
     */
    SystemAudioActionFromAvr(HdmiControlService service, int tvAddress, int avrAddress,
            boolean targetStatus) {
        super(service, tvAddress, avrAddress, targetStatus);
        HdmiUtils.verifyAddressType(tvAddress, HdmiCec.DEVICE_TV);
    }

    @Override
    boolean start() {
        removeSystemAudioActionInProgress();
        handleSystemAudioActionFromAvr();
        return true;
    }

    private void handleSystemAudioActionFromAvr() {
        if (mTargetAudioStatus == mService.getSystemAudioMode()) {
            finish();
            return;
        }
        if (mService.isInPresetInstallationMode()) {
            sendCommand(HdmiCecMessageBuilder.buildFeatureAbortCommand(
                    mSourceAddress, mAvrLogicalAddress,
                    HdmiCec.MESSAGE_SET_SYSTEM_AUDIO_MODE, HdmiConstants.ABORT_REFUSED));
            mTargetAudioStatus = false;
            sendSystemAudioModeRequest();
            return;
        }
        // TODO: Stop the action for System Audio Mode initialization if it is running.
        if (mTargetAudioStatus) {
            setSystemAudioMode(true);
            sendGiveAudioStatus();
        } else {
            setSystemAudioMode(false);
            finish();
        }
    }
}
