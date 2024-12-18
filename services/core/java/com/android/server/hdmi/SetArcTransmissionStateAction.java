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
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.util.Slog;

import java.util.List;

/**
 * Feature action that handles enabling/disabling of ARC transmission channel.
 * Once TV gets &lt;Initiate ARC&gt;, TV sends &lt;Report ARC Initiated&gt; to AV Receiver.
 * If it fails or it gets &lt;Terminate ARC&gt;, TV just disables ARC.
 */
final class SetArcTransmissionStateAction extends HdmiCecFeatureAction {
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
     * @param source {@link HdmiCecLocalDevice} instance
     * @param enabled whether to enable ARC Transmission channel
     */
    SetArcTransmissionStateAction(HdmiCecLocalDevice source, int avrAddress,
            boolean enabled) {
        super(source);
        if (!HdmiUtils.verifyAddressType(getSourceAddress(), HdmiDeviceInfo.DEVICE_TV) ||
                !HdmiUtils.verifyAddressType(avrAddress, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM)) {
            Slog.w(TAG, "Device type mismatch, stop the action.");
            finish();
        }
        mAvrAddress = avrAddress;
        mEnabled = enabled;
    }

    @Override
    boolean start() {
        // Seq #37.
        if (mEnabled) {
            // Avoid triggering duplicate RequestSadAction events.
            // This could lead to unexpected responses from the AVR and cause the TV to receive data
            // out of order. The SAD report does not provide information about the order of events.
            if ((tv().hasAction(RequestSadAction.class))) {
                return true;
            }
            // Request SADs before enabling ARC
            RequestSadAction action = new RequestSadAction(
                    localDevice(), Constants.ADDR_AUDIO_SYSTEM,
                    new RequestSadAction.RequestSadCallback() {
                        @Override
                        public void onRequestSadDone(List<byte[]> supportedSads) {
                            // Enable ARC status immediately before sending <Report Arc Initiated>.
                            // If AVR responds with <Feature Abort>, disable ARC status again.
                            // This is different from spec that says that turns ARC status to
                            // "Enabled" if <Report ARC Initiated> is acknowledged and no
                            // <Feature Abort> is received.
                            // But implemented this way to save the time having to wait for
                            // <Feature Abort>.
                            Slog.i(TAG, "Enabling ARC");
                            tv().enableArc(supportedSads);
                            // If succeeds to send <Report ARC Initiated>, wait general timeout to
                            // check whether there is no <Feature Abort> for <Report ARC Initiated>.
                            mState = STATE_WAITING_TIMEOUT;
                            addTimer(mState, HdmiConfig.TIMEOUT_MS);
                            sendReportArcInitiated();
                        }
                    });
            addAndStartAction(action);
        } else {
            disableArc();
            finish();
        }
        return true;
    }

    private void sendReportArcInitiated() {
        HdmiCecMessage command =
                HdmiCecMessageBuilder.buildReportArcInitiated(getSourceAddress(), mAvrAddress);
        sendCommand(command, new HdmiControlService.SendMessageCallback() {
            @Override
            public void onSendCompleted(int error) {
                switch (error) {
                    case SendMessageResult.SUCCESS:
                    case SendMessageResult.BUSY:
                    case SendMessageResult.FAIL:
                        // The result of the command transmission, unless it is an obvious
                        // failure indicated by the target device (or lack thereof), should
                        // not affect the ARC status. Ignores it silently.
                        break;
                    case SendMessageResult.NACK:
                        // If <Report ARC Initiated> is negatively ack'ed, disable ARC and
                        // send <Report ARC Terminated> directly.
                        disableArc();
                        HdmiLogger.debug("Failed to send <Report Arc Initiated>.");
                        finish();
                        break;
                }
            }
        });
    }

    private void disableArc() {
        Slog.i(TAG, "Disabling ARC");

        tv().disableArc();
        sendCommand(HdmiCecMessageBuilder.buildReportArcTerminated(getSourceAddress(),
                mAvrAddress));
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAITING_TIMEOUT) {
            return false;
        }

        int opcode = cmd.getOpcode();
        if (opcode == Constants.MESSAGE_FEATURE_ABORT) {
            int originalOpcode = cmd.getParams()[0] & 0xFF;
            if (originalOpcode == Constants.MESSAGE_REPORT_ARC_INITIATED) {
                HdmiLogger.debug("Feature aborted for <Report Arc Initiated>");
                disableArc();
                finish();
                return true;
            }
        }
        return false;
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
