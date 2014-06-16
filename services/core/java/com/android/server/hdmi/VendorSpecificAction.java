package com.android.server.hdmi;

import android.hardware.hdmi.HdmiCecMessage;

/**
 * Handles vendor-specific commands that require a sequence of command exchange,
 * or need to manage some states to complete the processing.
 */
public class VendorSpecificAction extends FeatureAction {

    // Sample state this action can be in.
    private static final int STATE_1 = 1;
    private static final int STATE_2 = 2;

    VendorSpecificAction(HdmiCecLocalDevice source) {
        super(source);
        // Modify the constructor if additional arguments are necessary.
    }

    @Override
    boolean start() {
        // Do initialization step and update the state accordingly here.
        mState = STATE_1;
        addTimer(STATE_1, TIMEOUT_MS);
        return true;
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        // Returns true if the command was consumed. Otherwise return false for other
        // actions in progress can be given its turn to process it.
        return false;
    }

    @Override
    void handleTimerEvent(int state) {
        // Ignore the timer event if the current state and the state this event should be
        // handled in are different. Could be an outdated event which should have been cleared by
        // calling {@code mActionTimer.clearTimerMessage()}.
        if (mState != state) {
            return;
        }

        switch (state) {
            case STATE_1:
                mState = STATE_2;
                addTimer(STATE_2, TIMEOUT_MS);
                break;
            case STATE_2:
                finish();
                break;
        }
    }
}
