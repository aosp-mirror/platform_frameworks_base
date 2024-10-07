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
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

/**
 * Action to initiate system audio once AVR is detected on Device discovery action.
 */
// Seq #27
final class SystemAudioAutoInitiationAction extends HdmiCecFeatureAction {
    private final int mAvrAddress;

    // State that waits for <System Audio Mode Status> once send
    // <Give System Audio Mode Status> to AV Receiver.
    private static final int STATE_WAITING_FOR_SYSTEM_AUDIO_MODE_STATUS = 1;

    @VisibleForTesting
    static final int RETRIES_ON_TIMEOUT = 1;

    // On some audio devices the <System Audio Mode Status> message can be delayed as the device
    // is just waking up. Retry the <Give System Audio Mode Status> message to ensure we properly
    // initialize system audio.
    private int mRetriesOnTimeOut = RETRIES_ON_TIMEOUT;

    SystemAudioAutoInitiationAction(HdmiCecLocalDevice source, int avrAddress) {
        super(source);
        mAvrAddress = avrAddress;
    }

    @Override
    boolean start() {
        mState = STATE_WAITING_FOR_SYSTEM_AUDIO_MODE_STATUS;

        addTimer(mState, HdmiConfig.TIMEOUT_MS);
        sendGiveSystemAudioModeStatus();
        return true;
    }

    private void sendGiveSystemAudioModeStatus() {
        sendCommand(HdmiCecMessageBuilder.buildGiveSystemAudioModeStatus(getSourceAddress(),
                mAvrAddress), new SendMessageCallback() {
            @Override
            public void onSendCompleted(int error) {
                if (error != SendMessageResult.SUCCESS) {
                    handleSystemAudioModeStatusTimeout();
                }
            }
        });
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAITING_FOR_SYSTEM_AUDIO_MODE_STATUS
                || mAvrAddress != cmd.getSource()) {
            return false;
        }

        if (cmd.getOpcode() == Constants.MESSAGE_SYSTEM_AUDIO_MODE_STATUS) {
            handleSystemAudioModeStatusMessage(HdmiUtils.parseCommandParamSystemAudioStatus(cmd));
            return true;
        }
        return false;
    }

    private void handleSystemAudioModeStatusMessage(boolean currentSystemAudioMode) {
        if (!canChangeSystemAudio()) {
            HdmiLogger.debug("Cannot change system audio mode in auto initiation action.");
            finish();
            return;
        }

        // If System Audio Control feature is enabled, turn on system audio mode when new AVR is
        // detected. Otherwise, turn off system audio mode.
        // If AVR reports SAM on and it is in standby, the action SystemAudioActionFromTv
        // triggers a <SAM Request> that will wake-up the AVR.
        boolean targetSystemAudioMode = tv().isSystemAudioControlFeatureEnabled();
        if (currentSystemAudioMode != targetSystemAudioMode
                || (currentSystemAudioMode && tv().getAvrDeviceInfo() != null
                && tv().getAvrDeviceInfo().getDevicePowerStatus()
                == HdmiControlManager.POWER_STATUS_STANDBY)) {
            // Start System Audio Control feature actions only if necessary.
            addAndStartAction(
                    new SystemAudioActionFromTv(tv(), mAvrAddress, targetSystemAudioMode, null));
        } else {
            // If AVR already has correct system audio mode, update target system audio mode
            // immediately rather than starting feature action.
            tv().setSystemAudioMode(targetSystemAudioMode);
        }
        finish();
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState != state) {
            return;
        }

        switch (mState) {
            case STATE_WAITING_FOR_SYSTEM_AUDIO_MODE_STATUS:
                if (mRetriesOnTimeOut > 0) {
                    mRetriesOnTimeOut--;
                    addTimer(mState, HdmiConfig.TIMEOUT_MS);
                    sendGiveSystemAudioModeStatus();
                    return;
                }

                handleSystemAudioModeStatusTimeout();
                break;
        }
    }

    private void handleSystemAudioModeStatusTimeout() {
        if (!canChangeSystemAudio()) {
            HdmiLogger.debug("Cannot change system audio mode in auto initiation action.");
            finish();
            return;
        }
        // If we can't get the current system audio mode status, just try to turn on/off system
        // audio mode according to the system audio control setting.
        addAndStartAction(new SystemAudioActionFromTv(tv(), mAvrAddress,
                tv().isSystemAudioControlFeatureEnabled(), null));
        finish();
    }

    private boolean canChangeSystemAudio() {
        return !(tv().hasAction(SystemAudioActionFromTv.class)
               || tv().hasAction(SystemAudioActionFromAvr.class));
    }
}
