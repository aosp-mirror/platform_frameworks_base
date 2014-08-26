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

import static com.android.server.hdmi.Constants.IRT_MS;

import com.android.internal.util.Preconditions;

/**
 * Feature action that transmits volume change to Audio Receiver.
 * <p>
 * This action is created when a user pressed volume up/down. However, Since Android only provides a
 * listener for delta of some volume change, we will set a target volume, and check reported volume
 * from Audio Receiver(AVR). If TV receives no &lt;Report Audio Status&gt; from AVR, this action
 * will be finished in {@link #IRT_MS} * {@link #VOLUME_CHANGE_TIMEOUT_MAX_COUNT} (ms).
 */
final class VolumeControlAction extends HdmiCecFeatureAction {
    private static final String TAG = "VolumeControlAction";

    private static final int VOLUME_MUTE = 101;
    private static final int VOLUME_RESTORE = 102;
    private static final int MAX_VOLUME = 100;
    private static final int MIN_VOLUME = 0;

    // State where to wait for <Report Audio Status>
    private static final int STATE_WAIT_FOR_REPORT_VOLUME_STATUS = 1;

    // Maximum count of time out used to finish volume action.
    private static final int VOLUME_CHANGE_TIMEOUT_MAX_COUNT = 2;

    private final int mAvrAddress;
    private final int mTargetVolume;
    private final boolean mIsVolumeUp;
    private int mTimeoutCount;

    /**
     * Create a {@link VolumeControlAction} for mute/restore change
     *
     * @param source source device sending volume change
     * @param avrAddress address of audio receiver
     * @param mute whether to mute sound or not. {@code true} for mute on; {@code false} for mute
     *            off, i.e restore volume
     * @return newly created {@link VolumeControlAction}
     */
    public static VolumeControlAction ofMute(HdmiCecLocalDevice source, int avrAddress,
            boolean mute) {
        return new VolumeControlAction(source, avrAddress, mute ? VOLUME_MUTE : VOLUME_RESTORE,
                false);
    }

    /**
     * Create a {@link VolumeControlAction} for volume up/down change
     *
     * @param source source device sending volume change
     * @param avrAddress address of audio receiver
     * @param targetVolume target volume to be set to AVR. It should be in range of [0-100]
     * @param isVolumeUp whether to volume up or not. {@code true} for volume up; {@code false} for
     *            volume down
     * @return newly created {@link VolumeControlAction}
     */
    public static VolumeControlAction ofVolumeChange(HdmiCecLocalDevice source, int avrAddress,
            int targetVolume, boolean isVolumeUp) {
        Preconditions.checkArgumentInRange(targetVolume, MIN_VOLUME, MAX_VOLUME, "volume");
        return new VolumeControlAction(source, avrAddress, targetVolume, isVolumeUp);
    }

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
     * @return a volume value scaled to custom volume range
     */
    public static int scaleToCustomVolume(int cecVolume, int scale) {
        return (cecVolume * scale) / MAX_VOLUME;
    }

    private VolumeControlAction(HdmiCecLocalDevice source, int avrAddress, int targetVolume,
            boolean isVolumeUp) {
        super(source);

        mAvrAddress = avrAddress;
        mTargetVolume = targetVolume;
        mIsVolumeUp = isVolumeUp;
    }

    @Override
    boolean start() {
        if (isForMute()) {
            sendMuteChange(mTargetVolume == VOLUME_MUTE);
            finish();
            return true;
        }

        startVolumeChange();
        return true;
    }


    private boolean isForMute() {
        return mTargetVolume == VOLUME_MUTE || mTargetVolume == VOLUME_RESTORE;
    }

    private void startVolumeChange() {
        mTimeoutCount = 0;
        sendVolumeChange(mIsVolumeUp);
        mState = STATE_WAIT_FOR_REPORT_VOLUME_STATUS;
        addTimer(mState, IRT_MS);
    }

    private void sendVolumeChange(boolean up) {
        sendCommand(HdmiCecMessageBuilder.buildUserControlPressed(getSourceAddress(), mAvrAddress,
                up ? HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP
                        : HdmiCecKeycode.CEC_KEYCODE_VOLUME_DOWN));
    }

    private void sendMuteChange(boolean mute) {
        sendUserControlPressedAndReleased(mAvrAddress,
                mute ? HdmiCecKeycode.CEC_KEYCODE_MUTE_FUNCTION :
                        HdmiCecKeycode.CEC_KEYCODE_RESTORE_VOLUME_FUNCTION);
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        if (mState != STATE_WAIT_FOR_REPORT_VOLUME_STATUS) {
            return false;
        }

        switch (cmd.getOpcode()) {
            case Constants.MESSAGE_REPORT_AUDIO_STATUS:
                handleReportAudioStatus(cmd);
                return true;
            case Constants.MESSAGE_FEATURE_ABORT:
                int originalOpcode = cmd.getParams()[0] & 0xFF;
                if (originalOpcode == Constants.MESSAGE_USER_CONTROL_PRESSED
                        || originalOpcode == Constants.MESSAGE_USER_CONTROL_RELEASED) {
                    // TODO: handle feature abort.
                    finish();
                    return true;
                }
            default:  // fall through
                return false;
        }
    }

    private void handleReportAudioStatus(HdmiCecMessage cmd) {
        byte[] params = cmd.getParams();
        int volume = params[0] & 0x7F;
        // Update volume with new value.
        // Note that it will affect system volume change.
        tv().setAudioStatus(false, volume);
        if (mIsVolumeUp) {
            if (mTargetVolume <= volume) {
                finishWithVolumeChangeRelease();
                return;
            }
        } else {
            if (mTargetVolume >= volume) {
                finishWithVolumeChangeRelease();
                return;
            }
        }

        // Clear action status and send another volume change command.
        clear();
        startVolumeChange();
    }

    private void finishWithVolumeChangeRelease() {
        sendCommand(HdmiCecMessageBuilder.buildUserControlReleased(
                getSourceAddress(), mAvrAddress));
        finish();
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState != STATE_WAIT_FOR_REPORT_VOLUME_STATUS) {
            return;
        }

        // If no report volume action after IRT * VOLUME_CHANGE_TIMEOUT_MAX_COUNT just stop volume
        // action.
        if (++mTimeoutCount == VOLUME_CHANGE_TIMEOUT_MAX_COUNT) {
            finishWithVolumeChangeRelease();
            return;
        }

        sendVolumeChange(mIsVolumeUp);
        addTimer(mState, IRT_MS);
    }
}
