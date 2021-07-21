/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.hardware.tv.cec.V1_0.SendMessageResult;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Feature action that handles System Audio Mode initiated by AVR devices.
 */
public class SystemAudioInitiationActionFromAvr extends HdmiCecFeatureAction {

    // State that waits for <Active Source> once send <Request Active Source>.
    private static final int STATE_WAITING_FOR_ACTIVE_SOURCE = 1;
    // State that waits for TV supporting Audio System Mode or not
    // once received <Active Source>
    private static final int STATE_WAITING_FOR_TV_SUPPORT = 2;
    @VisibleForTesting
    static final int MAX_RETRY_COUNT = 5;

    private int mSendRequestActiveSourceRetryCount = 0;
    private int mSendSetSystemAudioModeRetryCount = 0;

    SystemAudioInitiationActionFromAvr(HdmiCecLocalDevice source) {
        super(source);
    }

    @Override
    boolean start() {
        if (audioSystem().getActiveSource().physicalAddress == Constants.INVALID_PHYSICAL_ADDRESS) {
            mState = STATE_WAITING_FOR_ACTIVE_SOURCE;
            addTimer(mState, HdmiConfig.TIMEOUT_MS);
            sendRequestActiveSource();
        } else {
            mState = STATE_WAITING_FOR_TV_SUPPORT;
            queryTvSystemAudioModeSupport();
        }
        return true;
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        switch (cmd.getOpcode()) {
            case Constants.MESSAGE_ACTIVE_SOURCE:
                // received <Active Source>
                if (mState != STATE_WAITING_FOR_ACTIVE_SOURCE) {
                    return false;
                }
                mActionTimer.clearTimerMessage();
                // Broadcast message is also handled by other device types
                audioSystem().handleActiveSource(cmd);
                mState = STATE_WAITING_FOR_TV_SUPPORT;
                queryTvSystemAudioModeSupport();
                return true;
        }
        return false;
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState != state) {
            return;
        }

        switch (mState) {
            case STATE_WAITING_FOR_ACTIVE_SOURCE:
                handleActiveSourceTimeout();
                break;
        }
    }

    protected void sendRequestActiveSource() {
        sendCommand(HdmiCecMessageBuilder.buildRequestActiveSource(getSourceAddress()),
                result -> {
                    if (result != SendMessageResult.SUCCESS) {
                        if (mSendRequestActiveSourceRetryCount < MAX_RETRY_COUNT) {
                            mSendRequestActiveSourceRetryCount++;
                            sendRequestActiveSource();
                        } else {
                            audioSystem().checkSupportAndSetSystemAudioMode(false);
                            finish();
                        }
                    }
                });
    }

    protected void sendSetSystemAudioMode(boolean on, int dest) {
        sendCommand(HdmiCecMessageBuilder.buildSetSystemAudioMode(getSourceAddress(),
                dest, on), result -> {
                    if (result != SendMessageResult.SUCCESS) {
                        if (mSendSetSystemAudioModeRetryCount < MAX_RETRY_COUNT) {
                            mSendSetSystemAudioModeRetryCount++;
                            sendSetSystemAudioMode(on, dest);
                        } else {
                            audioSystem().checkSupportAndSetSystemAudioMode(false);
                            finish();
                        }
                    }
                });
    }

    private void handleActiveSourceTimeout() {
        HdmiLogger.debug("Cannot get active source.");
        // If not able to find Active Source and the current device has playbcak functionality,
        // claim Active Source and start to query TV system audio mode support.
        if (audioSystem().mService.isPlaybackDevice()) {
            audioSystem().mService.setAndBroadcastActiveSourceFromOneDeviceType(
                    Constants.ADDR_BROADCAST, getSourcePath(),
                    "SystemAudioInitiationActionFromAvr#handleActiveSourceTimeout()");
            mState = STATE_WAITING_FOR_TV_SUPPORT;
            queryTvSystemAudioModeSupport();
        } else {
            audioSystem().checkSupportAndSetSystemAudioMode(false);
        }
        finish();
    }

    private void queryTvSystemAudioModeSupport() {
        audioSystem().queryTvSystemAudioModeSupport(
                supported -> {
                    if (supported) {
                        if (audioSystem().checkSupportAndSetSystemAudioMode(true)) {
                            sendSetSystemAudioMode(true, Constants.ADDR_BROADCAST);
                        }
                        finish();
                    } else {
                        audioSystem().checkSupportAndSetSystemAudioMode(false);
                        finish();
                    }
                });
    }
}
