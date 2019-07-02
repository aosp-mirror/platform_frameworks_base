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

import android.hardware.hdmi.HdmiDeviceInfo;

/**
 * Base feature action class for &lt;Request ARC Initiation&gt;/&lt;Request ARC Termination&gt;.
 */
abstract class RequestArcAction extends HdmiCecFeatureAction {
    private static final String TAG = "RequestArcAction";

    // State in which waits for ARC response.
    protected static final int STATE_WATING_FOR_REQUEST_ARC_REQUEST_RESPONSE = 1;

    // Logical address of AV Receiver.
    protected final int mAvrAddress;

    /**
     * @Constructor
     *
     * @param source {@link HdmiCecLocalDevice} instance
     * @param avrAddress address of AV receiver. It should be AUDIO_SYSTEM type
     * @throws IllegalArgumentException if device type of sourceAddress and avrAddress
     *                      is invalid
     */
    RequestArcAction(HdmiCecLocalDevice source, int avrAddress) {
        super(source);
        HdmiUtils.verifyAddressType(getSourceAddress(), HdmiDeviceInfo.DEVICE_TV);
        HdmiUtils.verifyAddressType(avrAddress, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mAvrAddress = avrAddress;
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WATING_FOR_REQUEST_ARC_REQUEST_RESPONSE
                || !HdmiUtils.checkCommandSource(cmd, mAvrAddress, TAG)) {
            return false;
        }
        int opcode = cmd.getOpcode();
        switch (opcode) {
            // Handles only <Feature Abort> here and, both <Initiate ARC> and <Terminate ARC>
            // are handled in HdmiControlService itself because both can be
            // received without <Request ARC Initiation> or <Request ARC Termination>.
            case Constants.MESSAGE_FEATURE_ABORT:
                int originalOpcode = cmd.getParams()[0] & 0xFF;
                if (originalOpcode == Constants.MESSAGE_REQUEST_ARC_TERMINATION) {
                    disableArcTransmission();
                    finish();
                    return true;
                } else if (originalOpcode == Constants.MESSAGE_REQUEST_ARC_INITIATION) {
                    tv().setArcStatus(false);
                    finish();
                    return true;
                }
                return false;
        }
        return false;
    }

    protected final void disableArcTransmission() {
        // Start Set ARC Transmission State action.
        SetArcTransmissionStateAction action = new SetArcTransmissionStateAction(localDevice(),
                mAvrAddress, false);
        addAndStartAction(action);
    }

    @Override
    final void handleTimerEvent(int state) {
        if (mState != state || state != STATE_WATING_FOR_REQUEST_ARC_REQUEST_RESPONSE) {
            return;
        }
        HdmiLogger.debug("[T] RequestArcAction.");
        disableArcTransmission();
        finish();
    }
}
