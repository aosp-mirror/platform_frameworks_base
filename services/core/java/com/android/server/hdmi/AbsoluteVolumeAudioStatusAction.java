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

/**
 * Action to query and track the audio status of the System Audio device when enabling or using
 * Absolute Volume Control. Must be removed when AVC is disabled. Performs two main functions:
 * 1. When enabling AVC: queries the starting audio status of the System Audio device and
 *    enables the feature upon receiving a response.
 * 2. While AVC is enabled: monitors <Report Audio Status> messages from the System Audio device and
 *    notifies AudioService if the audio status changes.
 */
final class AbsoluteVolumeAudioStatusAction extends HdmiCecFeatureAction {
    private static final String TAG = "AbsoluteVolumeAudioStatusAction";

    private int mInitialAudioStatusRetriesLeft = 2;

    private static final int STATE_WAIT_FOR_INITIAL_AUDIO_STATUS = 1;
    private static final int STATE_MONITOR_AUDIO_STATUS = 2;

    private final int mTargetAddress;

    private AudioStatus mLastAudioStatus;

    AbsoluteVolumeAudioStatusAction(HdmiCecLocalDevice source, int targetAddress) {
        super(source);
        mTargetAddress = targetAddress;
    }

    @Override
    boolean start() {
        mState = STATE_WAIT_FOR_INITIAL_AUDIO_STATUS;
        sendGiveAudioStatus();
        return true;
    }

    void updateVolume(int volumeIndex) {
        mLastAudioStatus = new AudioStatus(volumeIndex, mLastAudioStatus.getMute());
    }

    private void sendGiveAudioStatus() {
        addTimer(mState, HdmiConfig.TIMEOUT_MS);
        sendCommand(HdmiCecMessageBuilder.buildGiveAudioStatus(getSourceAddress(), mTargetAddress));
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        switch (cmd.getOpcode()) {
            case Constants.MESSAGE_REPORT_AUDIO_STATUS:
                return handleReportAudioStatus(cmd);
        }

        return false;
    }

    private boolean handleReportAudioStatus(HdmiCecMessage cmd) {
        if (mTargetAddress != cmd.getSource() || cmd.getParams().length == 0) {
            return false;
        }

        boolean mute = HdmiUtils.isAudioStatusMute(cmd);
        int volume = HdmiUtils.getAudioStatusVolume(cmd);
        AudioStatus audioStatus = new AudioStatus(volume, mute);
        if (mState == STATE_WAIT_FOR_INITIAL_AUDIO_STATUS) {
            localDevice().getService().enableAbsoluteVolumeControl(audioStatus);
            mState = STATE_MONITOR_AUDIO_STATUS;
        } else if (mState == STATE_MONITOR_AUDIO_STATUS) {
            if (audioStatus.getVolume() != mLastAudioStatus.getVolume()) {
                localDevice().getService().notifyAvcVolumeChange(audioStatus.getVolume());
            }
            if (audioStatus.getMute() != mLastAudioStatus.getMute()) {
                localDevice().getService().notifyAvcMuteChange(audioStatus.getMute());
            }
        }
        mLastAudioStatus = audioStatus;

        return true;
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState != state) {
            return;
        } else if (mInitialAudioStatusRetriesLeft > 0) {
            mInitialAudioStatusRetriesLeft--;
            sendGiveAudioStatus();
        }
    }
}
