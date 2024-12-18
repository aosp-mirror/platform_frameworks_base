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

import static com.android.server.hdmi.HdmiConfig.IRT_MS;

import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.util.Slog;
import android.view.KeyEvent;

import com.android.server.hdmi.HdmiControlService.SendMessageCallback;

/**
 * Feature action that transmits remote control key command (User Control Press/
 * User Control Release) to CEC bus.
 *
 * <p>This action is created when a new key event is passed to CEC service. It optionally
 * does key repeat (a.k.a. press-and-hold) operation until it receives a key release event.
 * If another key press event is received before the key in use is released, CEC service
 * does not create a new action but recycles the current one by updating the key used
 * for press-and-hold operation.
 *
 * <p>Package-private, accessed by {@link HdmiControlService} only.
 */
final class SendKeyAction extends HdmiCecFeatureAction {
    private static final String TAG = "SendKeyAction";

    // If the first key press lasts this much amount of time without any other key event
    // coming down, we trigger the press-and-hold operation. Set to the value slightly
    // shorter than the threshold(500ms) between two successive key press events
    // as specified in the standard for the operation.
    private static final int AWAIT_LONGPRESS_MS = 400;

    // Amount of time this action waits for a new release key input event. When timed out,
    // the action sends out UCR and finishes its lifecycle. Used to deal with missing key release
    // event, which can lead the device on the receiving end to generating unintended key repeats.
    private static final int AWAIT_RELEASE_KEY_MS = 1000;

    // State in which the long press is being checked at the beginning. The state is set in
    // {@link #start()} and lasts for {@link #AWAIT_LONGPRESS_MS}.
    private static final int STATE_CHECKING_LONGPRESS = 1;

    // State in which the action is handling incoming keys. Persists throughout the process
    // till it is set back to {@code STATE_NONE} at the end when a release key event for
    // the last key is processed.
    private static final int STATE_PROCESSING_KEYCODE = 2;

    // Logical address of the device to which the UCP/UCP commands are sent.
    private final int mTargetAddress;

    // The key code of the last key press event the action is passed via processKeyEvent.
    private int mLastKeycode;

    // The time stamp when the last CEC key command was sent. Used to determine the press-and-hold
    // operation.
    private long mLastSendKeyTime;

    /**
     * Constructor.
     *
     * @param source {@link HdmiCecLocalDevice} instance
     * @param targetAddress logical address of the device to send the keys to
     * @param keycode remote control key code as defined in {@link KeyEvent}
     */
    SendKeyAction(HdmiCecLocalDevice source, int targetAddress, int keycode) {
        super(source);
        mTargetAddress = targetAddress;
        mLastKeycode = keycode;
    }

    @Override
    public boolean start() {
        sendKeyDown(mLastKeycode);
        mLastSendKeyTime = getCurrentTime();
        // finish action for non-repeatable key.
        if (!HdmiCecKeycode.isRepeatableKey(mLastKeycode)) {
            sendKeyUp();
            finish();
            return true;
        }
        mState = STATE_CHECKING_LONGPRESS;
        addTimer(mState, AWAIT_LONGPRESS_MS);
        return true;
    }

    private long getCurrentTime() {
        return System.currentTimeMillis();
    }

    /**
     * Called when a key event should be handled for the action.
     *
     * @param keycode key code of {@link KeyEvent} object
     * @param isPressed true if the key event is of {@link KeyEvent#ACTION_DOWN}
     */
    void processKeyEvent(int keycode, boolean isPressed) {
        if (mState != STATE_CHECKING_LONGPRESS && mState != STATE_PROCESSING_KEYCODE) {
            Slog.w(TAG, "Not in a valid state");
            return;
        }
        if (isPressed) {
            // A new key press event that comes in with a key code different from the last
            // one becomes a new key code to be used for press-and-hold operation.
            if (keycode != mLastKeycode) {
                sendKeyDown(keycode);
                mLastSendKeyTime = getCurrentTime();
                if (!HdmiCecKeycode.isRepeatableKey(keycode)) {
                    sendKeyUp();
                    finish();
                    return;
                }
            } else {
                // Press-and-hold key transmission takes place if Android key inputs are
                // repeatedly coming in and more than IRT_MS has passed since the last
                // press-and-hold key transmission.
                if (getCurrentTime() - mLastSendKeyTime >= IRT_MS) {
                    sendKeyDown(keycode);
                    mLastSendKeyTime = getCurrentTime();
                }
            }
            mActionTimer.clearTimerMessage();
            addTimer(mState, AWAIT_RELEASE_KEY_MS);
            mLastKeycode = keycode;
        } else {
            // Key release event indicates that the action shall be finished. Send UCR
            // command and terminate the action. Other release events are ignored.
            if (keycode == mLastKeycode) {
                sendKeyUp();
                finish();
            }
        }
    }

    private void sendKeyDown(int keycode) {
        byte[] cecKeycodeAndParams = HdmiCecKeycode.androidKeyToCecKey(keycode);
        if (cecKeycodeAndParams == null) {
            return;
        }
        // Devices that are not directly connected with audio system device can't detect if the
        // audio system device is still plugged in. Framework checks if the volume key forwarding is
        // successful or not every time to make sure the System Audio Mode status is still updated.
        if (mTargetAddress == Constants.ADDR_AUDIO_SYSTEM
                && localDevice().getDeviceInfo().getLogicalAddress() != Constants.ADDR_TV) {
            sendCommand(HdmiCecMessageBuilder.buildUserControlPressed(getSourceAddress(),
                mTargetAddress, cecKeycodeAndParams), new SendMessageCallback() {
                @Override
                public void onSendCompleted(int error) {
                    // Disable System Audio Mode, if the AVR doesn't acknowledge
                    // a <User Control Pressed> message.
                    if (error == SendMessageResult.NACK) {
                        HdmiLogger.debug(
                            "AVR did not acknowledge <User Control Pressed>");
                        localDevice().mService.setSystemAudioActivated(false);
                    }
                }
            });
        } else {
            sendCommand(HdmiCecMessageBuilder.buildUserControlPressed(getSourceAddress(),
                    mTargetAddress, cecKeycodeAndParams));
        }
    }

    private void sendKeyUp() {
        // When using absolute volume behavior, query audio status after a volume key is released.
        // This allows us to notify AudioService of the resulting volume or mute status changes.
        if (HdmiCecKeycode.isVolumeKeycode(mLastKeycode)
                && localDevice().getService().isAbsoluteVolumeBehaviorEnabled()) {
            sendCommand(HdmiCecMessageBuilder.buildUserControlReleased(getSourceAddress(),
                    mTargetAddress),
                    __ -> queryAvrAudioStatus());
        } else {
            sendCommand(HdmiCecMessageBuilder.buildUserControlReleased(getSourceAddress(),
                    mTargetAddress));
        }
    }

    private void queryAvrAudioStatus() {
        localDevice().mService.runOnServiceThreadDelayed(
                () -> sendCommand(HdmiCecMessageBuilder.buildGiveAudioStatus(
                        getSourceAddress(),
                        localDevice().findAudioReceiverAddress())),
                DELAY_GIVE_AUDIO_STATUS);

    }

    @Override
    public boolean processCommand(HdmiCecMessage cmd) {
        // Send key action doesn't need any incoming CEC command, hence does not consume it.
        return false;
    }

    @Override
    public void handleTimerEvent(int state) {
        switch (mState) {
            case STATE_CHECKING_LONGPRESS:
                // The first key press lasts long enough to start press-and-hold.
                mActionTimer.clearTimerMessage();
                mState = STATE_PROCESSING_KEYCODE;
                sendKeyDown(mLastKeycode);
                mLastSendKeyTime = getCurrentTime();
                addTimer(mState, AWAIT_RELEASE_KEY_MS);
                break;
            case STATE_PROCESSING_KEYCODE:
                // Timeout on waiting for the release key event. Send UCR and quit the action.
                sendKeyUp();
                finish();
                break;
            default:
                Slog.w(TAG, "Not in a valid state");
                break;
        }
    }
}
