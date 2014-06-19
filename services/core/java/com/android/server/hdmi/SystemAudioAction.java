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

/**
 * Base feature action class for SystemAudioActionFromTv and SystemAudioActionFromAvr.
 */
abstract class SystemAudioAction extends FeatureAction {
    private static final String TAG = "SystemAudioAction";

    // State in which waits for <SetSystemAudioMode>.
    private static final int STATE_WAIT_FOR_SET_SYSTEM_AUDIO_MODE = 1;

    private static final int MAX_SEND_RETRY_COUNT = 2;

    private static final int ON_TIMEOUT_MS = 5000;
    private static final int OFF_TIMEOUT_MS = TIMEOUT_MS;

    // Logical address of AV Receiver.
    protected final int mAvrLogicalAddress;

    // The target audio status of the action, whether to enable the system audio mode or not.
    protected boolean mTargetAudioStatus;

    private int mSendRetryCount = 0;

    /**
     * Constructor
     *
     * @param source {@link HdmiCecLocalDevice} instance
     * @param avrAddress logical address of AVR device
     * @param targetStatus Whether to enable the system audio mode or not
     * @throw IllegalArugmentException if device type of sourceAddress and avrAddress is invalid
     */
    SystemAudioAction(HdmiCecLocalDevice source, int avrAddress, boolean targetStatus) {
        super(source);
        HdmiUtils.verifyAddressType(avrAddress, HdmiCec.DEVICE_AUDIO_SYSTEM);
        mAvrLogicalAddress = avrAddress;
        mTargetAudioStatus = targetStatus;
    }

    protected void sendSystemAudioModeRequest() {
        int avrPhysicalAddress = tv().getAvrDeviceInfo().getPhysicalAddress();
        HdmiCecMessage command = HdmiCecMessageBuilder.buildSystemAudioModeRequest(
                getSourceAddress(),
                mAvrLogicalAddress, avrPhysicalAddress, mTargetAudioStatus);
        sendCommand(command, new HdmiControlService.SendMessageCallback() {
            @Override
            public void onSendCompleted(int error) {
                if (error == HdmiConstants.SEND_RESULT_SUCCESS) {
                    mState = STATE_WAIT_FOR_SET_SYSTEM_AUDIO_MODE;
                    addTimer(mState, mTargetAudioStatus ? ON_TIMEOUT_MS : OFF_TIMEOUT_MS);
                } else {
                    setSystemAudioMode(false);
                    finish();
                }
            }
        });
    }

    private void handleSendSystemAudioModeRequestTimeout() {
        if (!mTargetAudioStatus  // Don't retry for Off case.
                || mSendRetryCount++ >= MAX_SEND_RETRY_COUNT) {
            setSystemAudioMode(false);
            finish();
            return;
        }
        sendSystemAudioModeRequest();
    }

    protected void setSystemAudioMode(boolean mode) {
        tv().setSystemAudioMode(mode);
    }

    @Override
    final boolean processCommand(HdmiCecMessage cmd) {
        switch (mState) {
            case STATE_WAIT_FOR_SET_SYSTEM_AUDIO_MODE:
                // TODO: Handle <FeatureAbort> of <SystemAudioModeRequest>
                if (cmd.getOpcode() != HdmiCec.MESSAGE_SET_SYSTEM_AUDIO_MODE
                        || !HdmiUtils.checkCommandSource(cmd, mAvrLogicalAddress, TAG)) {
                    return false;
                }
                boolean receivedStatus = HdmiUtils.parseCommandParamSystemAudioStatus(cmd);
                if (receivedStatus == mTargetAudioStatus) {
                    setSystemAudioMode(receivedStatus);
                    startAudioStatusAction();
                    return true;
                } else {
                    // Unexpected response, consider the request is newly initiated by AVR.
                    // To return 'false' will initiate new SystemAudioActionFromAvr by the control
                    // service.
                    finish();
                    return false;
                }
            default:
                return false;
        }
    }

    protected void startAudioStatusAction() {
        addAndStartAction(new SystemAudioStatusAction(tv(), mAvrLogicalAddress));
        finish();
    }

    protected void removeSystemAudioActionInProgress() {
        removeActionExcept(SystemAudioActionFromTv.class, this);
        removeActionExcept(SystemAudioActionFromAvr.class, this);
    }

    @Override
    final void handleTimerEvent(int state) {
        if (mState != state) {
            return;
        }
        switch (mState) {
            case STATE_WAIT_FOR_SET_SYSTEM_AUDIO_MODE:
                handleSendSystemAudioModeRequestTimeout();
                return;
        }
    }
}
