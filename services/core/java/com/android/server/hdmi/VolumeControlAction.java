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

import static com.android.server.hdmi.Constants.MESSAGE_FEATURE_ABORT;
import static com.android.server.hdmi.Constants.MESSAGE_REPORT_AUDIO_STATUS;
import static com.android.server.hdmi.Constants.MESSAGE_USER_CONTROL_PRESSED;
import static com.android.server.hdmi.HdmiConfig.IRT_MS;

import android.media.AudioManager;

/**
 * Feature action that transmits volume change to Audio Receiver.
 * <p>
 * This action is created when a user pressed volume up/down. However, Android only provides a
 * listener for delta of some volume change instead of individual key event. Also it's hard to know
 * Audio Receiver's number of volume steps for a single volume control key. Because of this, it
 * sends key-down event until IRT timeout happens, and it will send key-up event if no additional
 * volume change happens; otherwise, it will send again key-down as press and hold feature does.
 */
final class VolumeControlAction extends HdmiCecFeatureAction {
    private static final String TAG = "VolumeControlAction";

    // State that wait for next volume press.
    private static final int STATE_WAIT_FOR_NEXT_VOLUME_PRESS = 1;
    private static final int MAX_VOLUME = 100;

    private static final int UNKNOWN_AVR_VOLUME = -1;

    private final int mAvrAddress;
    private boolean mIsVolumeUp;
    private long mLastKeyUpdateTime;
    private int mLastAvrVolume;
    private boolean mLastAvrMute;
    private boolean mSentKeyPressed;

    /**
     * Scale a custom volume value to cec volume scale.
     *
     * @param volume volume value in custom scale
     * @param scale scale of volume (max volume)
     * @return a volume scaled to cec volume range
     */
    public static int scaleToCecVolume(int volume, int scale) {
        return (volume * MAX_VOLUME) / scale;
    }

    /**
     * Scale a cec volume which is in range of 0 to 100 to custom volume level.
     *
     * @param cecVolume volume value in cec volume scale. It should be in a range of [0-100]
     * @param scale scale of custom volume (max volume)
     * @return a volume scaled to custom volume range
     */
    public static int scaleToCustomVolume(int cecVolume, int scale) {
        return (cecVolume * scale) / MAX_VOLUME;
    }

    VolumeControlAction(HdmiCecLocalDevice source, int avrAddress, boolean isVolumeUp) {
        super(source);
        mAvrAddress = avrAddress;
        mIsVolumeUp = isVolumeUp;
        mLastAvrVolume = UNKNOWN_AVR_VOLUME;
        mLastAvrMute = false;
        mSentKeyPressed = false;

        updateLastKeyUpdateTime();
    }

    private void updateLastKeyUpdateTime() {
        mLastKeyUpdateTime = System.currentTimeMillis();
    }

    @Override
    boolean start() {
        mState = STATE_WAIT_FOR_NEXT_VOLUME_PRESS;
        sendVolumeKeyPressed();
        resetTimer();
        return true;
    }

    private void sendVolumeKeyPressed() {
        sendCommand(HdmiCecMessageBuilder.buildUserControlPressed(getSourceAddress(), mAvrAddress,
                mIsVolumeUp ? HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP
                        : HdmiCecKeycode.CEC_KEYCODE_VOLUME_DOWN));
        mSentKeyPressed = true;
    }

    private void resetTimer() {
        mActionTimer.clearTimerMessage();
        addTimer(STATE_WAIT_FOR_NEXT_VOLUME_PRESS, IRT_MS);
    }

    void handleVolumeChange(boolean isVolumeUp) {
        if (mIsVolumeUp != isVolumeUp) {
            HdmiLogger.debug("Volume Key Status Changed[old:%b new:%b]", mIsVolumeUp, isVolumeUp);
            sendVolumeKeyReleased();
            mIsVolumeUp = isVolumeUp;
            sendVolumeKeyPressed();
            resetTimer();
        }
        updateLastKeyUpdateTime();
    }

    private void sendVolumeKeyReleased() {
        sendCommand(HdmiCecMessageBuilder.buildUserControlReleased(
                getSourceAddress(), mAvrAddress));
        mSentKeyPressed = false;
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAIT_FOR_NEXT_VOLUME_PRESS || cmd.getSource() != mAvrAddress) {
            return false;
        }

        switch (cmd.getOpcode()) {
            case MESSAGE_REPORT_AUDIO_STATUS:
                return handleReportAudioStatus(cmd);
            case MESSAGE_FEATURE_ABORT:
                return handleFeatureAbort(cmd);
        }
        return false;
    }

    private boolean handleReportAudioStatus(HdmiCecMessage cmd) {
        byte params[] = cmd.getParams();
        boolean mute = HdmiUtils.isAudioStatusMute(cmd);
        int volume = HdmiUtils.getAudioStatusVolume(cmd);
        mLastAvrVolume = volume;
        mLastAvrMute = mute;
        if (shouldUpdateAudioVolume(mute)) {
            HdmiLogger.debug("Force volume change[mute:%b, volume=%d]", mute, volume);
            tv().setAudioStatus(mute, volume);
            mLastAvrVolume = UNKNOWN_AVR_VOLUME;
            mLastAvrMute = false;
        }
        return true;
    }

    private boolean shouldUpdateAudioVolume(boolean mute) {
        // Do nothing if in mute.
        if (mute) {
            return true;
        }

        // Update audio status if current volume position is edge of volume bar,
        // i.e max or min volume.
        AudioManager audioManager = tv().getService().getAudioManager();
        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (mIsVolumeUp) {
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            return currentVolume == maxVolume;
        } else {
            return currentVolume == 0;
        }
    }

    private boolean handleFeatureAbort(HdmiCecMessage cmd) {
        int originalOpcode = cmd.getParams()[0] & 0xFF;
        // Since it sends <User Control Released> only when it finishes this action,
        // it takes care of <User Control Pressed> only here.
        if (originalOpcode == MESSAGE_USER_CONTROL_PRESSED) {
            finish();
            return true;
        }
        return false;
    }

    @Override
    protected void clear() {
        super.clear();
        if (mSentKeyPressed) {
            sendVolumeKeyReleased();
        }
        if (mLastAvrVolume != UNKNOWN_AVR_VOLUME) {
            tv().setAudioStatus(mLastAvrMute, mLastAvrVolume);
            mLastAvrVolume = UNKNOWN_AVR_VOLUME;
            mLastAvrMute = false;
        }
    }

    @Override
    void handleTimerEvent(int state) {
        if (state != STATE_WAIT_FOR_NEXT_VOLUME_PRESS) {
            return;
        }

        if (System.currentTimeMillis() - mLastKeyUpdateTime >= IRT_MS) {
            finish();
        } else {
            sendVolumeKeyPressed();
            resetTimer();
        }
    }
}
