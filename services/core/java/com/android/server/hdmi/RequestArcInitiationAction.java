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

import android.hardware.hdmi.HdmiCecMessage;
import android.util.Slog;

/**
 * Feature action that handles ARC action initiated by TV devices.
 *
 * <p>This action is created by TV's hot plug event of ARC port.
 */
final class RequestArcInitiationAction extends RequestArcAction {
    private static final String TAG = "RequestArcInitiationAction";

    /**
     * @Constructor
     *
     * For more details look at {@link RequestArcAction#RequestArcAction}.
     */
    RequestArcInitiationAction(HdmiControlService service, int sourceAddress, int avrAddress) {
        super(service, sourceAddress, avrAddress);
    }

    @Override
    boolean start() {
        HdmiCecMessage command = HdmiCecMessageBuilder.buildRequestArcInitiation(mSourceAddress,
                mAvrAddress);
        sendCommand(command, new HdmiControlService.SendMessageCallback() {
            @Override
            public void onSendCompleted(int error) {
                // success.
                if (error == 0) {
                    mState = STATE_WATING_FOR_REQUEST_ARC_REQUEST_RESPONSE;
                    addTimer(mState, TIMEOUT_MS);
                } else {
                    Slog.w(TAG, "Failed to send <Request ARC Initiation>");
                    // If failed to send <Request ARC Initiation>, start "Disabled"
                    // ARC transmission action.
                    disableArcTransmission();
                    finish();
                }
            }
        });
        return true;
    }
}
