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

import android.util.Slog;
import android.view.KeyEvent;

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

    // State in which the action is at work. The state is set in {@link #start()} and
    // persists throughout the process till it is set back to {@code STATE_NONE} at the end.
    private static final int STATE_PROCESSING_KEYCODE = 1;

    // Logical address of the device to which the UCP/UCP commands are sent.
    private final int mTargetAddress;

    // The key code of the last key press event the action is passed via processKeyEvent.
    private int mLastKeycode;

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
        // finish action for non-repeatable key.
        if (!HdmiCecKeycode.isRepeatableKey(mLastKeycode)) {
            sendKeyUp();
            finish();
            return true;
        }
        mState = STATE_PROCESSING_KEYCODE;
        addTimer(mState, IRT_MS);
        return true;
    }

    /**
     * Called when a key event should be handled for the action.
     *
     * @param keycode key code of {@link KeyEvent} object
     * @param isPressed true if the key event is of {@link KeyEvent#ACTION_DOWN}
     */
    void processKeyEvent(int keycode, boolean isPressed) {
        if (mState != STATE_PROCESSING_KEYCODE) {
            Slog.w(TAG, "Not in a valid state");
            return;
        }
        // A new key press event that comes in with a key code different from the last
        // one sets becomes a new key code to be used for press-and-hold operation.
        // Removes any pending timer and starts a new timer for itself.
        // Key release event indicates that the action shall be finished. Send UCR
        // command and terminate the action. Other release events are ignored.
        if (isPressed) {
            if (keycode != mLastKeycode) {
                sendKeyDown(keycode);
                if (!HdmiCecKeycode.isRepeatableKey(keycode)) {
                    sendKeyUp();
                    finish();
                    return;
                }
                mActionTimer.clearTimerMessage();
                addTimer(mState, IRT_MS);
                mLastKeycode = keycode;
            }
        } else {
            if (keycode == mLastKeycode) {
                sendKeyUp();
                finish();
            }
        }
    }

    private void sendKeyDown(int keycode) {
        int cecKeycode = HdmiCecKeycode.androidKeyToCecKey(keycode);
        if (cecKeycode == HdmiCecKeycode.UNSUPPORTED_KEYCODE) {
            return;
        }
        sendCommand(HdmiCecMessageBuilder.buildUserControlPressed(getSourceAddress(),
                mTargetAddress, new byte[] { (byte) (cecKeycode & 0xFF) }));
    }

    private void sendKeyUp() {
        sendCommand(HdmiCecMessageBuilder.buildUserControlReleased(getSourceAddress(),
                mTargetAddress));
    }

    @Override
    public boolean processCommand(HdmiCecMessage cmd) {
        // Send key action doesn't need any incoming CEC command, hence does not consume it.
        return false;
    }

    @Override
    public void handleTimerEvent(int state) {
        // Timer event occurs every IRT_MS milliseconds to perform key-repeat (or press-and-hold)
        // operation. If the last received key code is as same as the one with which the action
        // is started, plus there was no key release event in last IRT_MS timeframe, send a UCP
        // command and start another timer to schedule the next press-and-hold command.
        if (mState != STATE_PROCESSING_KEYCODE) {
            Slog.w(TAG, "Not in a valid state");
            return;
        }
        sendKeyDown(mLastKeycode);
        addTimer(mState, IRT_MS);
    }
}
