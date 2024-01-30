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

package com.android.server.display.state;

import android.hardware.display.DisplayManagerInternal;
import android.util.IndentingPrintWriter;
import android.view.Display;

import com.android.server.display.DisplayPowerProximityStateController;

import java.io.PrintWriter;

/**
 * Maintains the DisplayState of the system.
 * Internally, this accounts for the proximity changes, and notifying the system
 * clients about the changes
 */
public class DisplayStateController {
    private DisplayPowerProximityStateController mDisplayPowerProximityStateController;
    private boolean mPerformScreenOffTransition = false;
    private int mDozeStateOverride = Display.STATE_UNKNOWN;

    public DisplayStateController(DisplayPowerProximityStateController
            displayPowerProximityStateController) {
        this.mDisplayPowerProximityStateController = displayPowerProximityStateController;
    }

    /**
     * Updates the DisplayState and notifies the system. Also accounts for the
     * events being emitted by the proximity sensors
     *
     * @param displayPowerRequest   The request to update the display state
     * @param isDisplayEnabled      A boolean flag representing if the display is enabled
     * @param isDisplayInTransition A boolean flag representing if the display is undergoing the
     *                              transition phase
     */
    public int updateDisplayState(DisplayManagerInternal.DisplayPowerRequest displayPowerRequest,
            boolean isDisplayEnabled, boolean isDisplayInTransition) {
        mPerformScreenOffTransition = false;
        // Compute the basic display state using the policy.
        // We might override this below based on other factors.
        // Initialise brightness as invalid.
        int state;
        switch (displayPowerRequest.policy) {
            case DisplayManagerInternal.DisplayPowerRequest.POLICY_OFF:
                state = Display.STATE_OFF;
                mPerformScreenOffTransition = true;
                break;
            case DisplayManagerInternal.DisplayPowerRequest.POLICY_DOZE:
                if (mDozeStateOverride != Display.STATE_UNKNOWN) {
                    state = mDozeStateOverride;
                } else if (displayPowerRequest.dozeScreenState != Display.STATE_UNKNOWN) {
                    state = displayPowerRequest.dozeScreenState;
                } else {
                    state = Display.STATE_DOZE;
                }
                break;
            case DisplayManagerInternal.DisplayPowerRequest.POLICY_DIM:
            case DisplayManagerInternal.DisplayPowerRequest.POLICY_BRIGHT:
            default:
                state = Display.STATE_ON;
                break;
        }
        assert (state != Display.STATE_UNKNOWN);

        mDisplayPowerProximityStateController.updateProximityState(displayPowerRequest, state);

        if (!isDisplayEnabled || isDisplayInTransition
                || mDisplayPowerProximityStateController.isScreenOffBecauseOfProximity()) {
            state = Display.STATE_OFF;
        }

        return state;
    }

    public void overrideDozeScreenState(int displayState) {
        mDozeStateOverride = displayState;
    }

    /**
     * Checks if the screen off transition is to be performed or not.
     */
    public boolean shouldPerformScreenOffTransition() {
        return mPerformScreenOffTransition;
    }

    /**
     * Used to dump the state.
     *
     * @param pw The PrintWriter used to dump the state.
     */
    public void dumpsys(PrintWriter pw) {
        pw.println();
        pw.println("DisplayStateController:");
        pw.println("  mPerformScreenOffTransition:" + mPerformScreenOffTransition);
        pw.println("  mDozeStateOverride=" + mDozeStateOverride);

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, " ");
        if (mDisplayPowerProximityStateController != null) {
            mDisplayPowerProximityStateController.dumpLocal(ipw);
        }
    }
}
