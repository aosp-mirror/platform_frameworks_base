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
import android.hardware.hdmi.HdmiCecMessage;
import android.util.Slog;

/**
 * Feature action that handles enabling/disabling of ARC transmission channel.
 * Once TV gets &lt;Initiate ARC&gt;, TV sends &lt;Report ARC Initiated&gt; to AV Receiver.
 * If it fails or it gets &lt;Terminate ARC&gt;, TV just disables ARC.
 */
final class SetArcTransmissionStateAction extends FeatureAction {
    private static final String TAG = "SetArcTransmissionStateAction";

    // State in which the action sent <Rerpot Arc Initiated> and
    // is waiting for time out. If it receives <Feature Abort> within timeout
    // ARC should be disabled.
    private static final int STATE_WAITING_TIMEOUT = 1;

    private final boolean mEnabled;
    private final int mAvrAddress;

    /**
     * @Constructor
     *
     * @param service an instance of {@link HdmiControlService}
     * @param sourceAddress logical address to be used as source address
     * @param enabled whether to enable ARC Transmission channel
     */
    SetArcTransmissionStateAction(HdmiControlService service, int sourceAddress, int avrAddress,
            boolean enabled) {
        super(service, sourceAddress);
        HdmiUtils.verifyAddressType(sourceAddress, HdmiCec.DEVICE_TV);
        HdmiUtils.verifyAddressType(avrAddress, HdmiCec.DEVICE_AUDIO_SYSTEM);
        mAvrAddress = avrAddress;
        mEnabled = enabled;
    }

    @Override
    boolean start() {
        if (mEnabled) {
            sendReportArcInitiated();
        } else {
            setArcStatus(false);
            finish();
        }
        return true;
    }

    private void sendReportArcInitiated() {
        HdmiCecMessage command =
                HdmiCecMessageBuilder.buildReportArcInitiated(mSourceAddress, mAvrAddress);
        sendCommand(command, new HdmiControlService.SendMessageCallback() {
            @Override
            public void onSendCompleted(int error) {
                if (error == HdmiControlService.SEND_RESULT_SUCCESS) {
                    // Enable ARC status immediately after sending <Report Arc Initiated>.
                    // If AVR responds with <Feature Abort>, disable ARC status again.
                    // This is different from spec that says that turns ARC status to
                    // "Enabled" if <Report ARC Initiated> is acknowledged and no
                    // <Feature Abort> is received.
                    // But implemented this way to save the time having to wait for
                    // <Feature Abort>.
                    setArcStatus(true);
                    // If succeeds to send <Report ARC Initiated>, wait general timeout
                    // to check whether there is no <Feature Abort> for <Report ARC Initiated>.
                    mState = STATE_WAITING_TIMEOUT;
                    addTimer(mState, TIMEOUT_MS);
                } else {
                    // If fails to send <Report ARC Initiated>, disable ARC and
                    // send <Report ARC Terminated> directly.
                    setArcStatus(false);
                    finish();
                }
            }
        });
    }

    private void setArcStatus(boolean enabled) {
        boolean wasEnabled = mService.setArcStatus(enabled);
        Slog.i(TAG, "Change arc status [old:" + wasEnabled + " ,new:" + enabled);

        // If enabled before and set to "disabled" and send <Report Arc Terminated> to
        // av reciever.
        if (!enabled && wasEnabled) {
            sendCommand(
                    HdmiCecMessageBuilder.buildReportArcTerminated(mSourceAddress, mAvrAddress));
        }
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAITING_TIMEOUT) {
            return false;
        }

        int opcode = cmd.getOpcode();
        if (opcode == HdmiCec.MESSAGE_FEATURE_ABORT) {
            setArcStatus(false);
        }
        finish();
        return true;
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState != state || mState != STATE_WAITING_TIMEOUT) {
            return;
        }
        // Expire timeout for <Feature Abort>.
        finish();
    }
}
