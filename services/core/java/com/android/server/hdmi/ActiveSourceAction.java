/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.hardware.hdmi.HdmiDeviceInfo;

/**
 * Action that sends {@code <Active Source>} to make this device the currently active source.
 *
 * Playback devices will also send {@code <Report Menu Status>} to make them a target for {@code
 * <User Control Pressed>} messages.
 */
public class ActiveSourceAction extends HdmiCecFeatureAction {

    private static final int STATE_STARTED = 1;
    private static final int STATE_FINISHED = 2;

    private final int mDestination;

    ActiveSourceAction(HdmiCecLocalDevice source, int destination) {
        super(source);
        mDestination = destination;
    }

    @Override
    boolean start() {
        mState = STATE_STARTED;
        int logicalAddress = getSourceAddress();
        int physicalAddress = getSourcePath();

        sendCommand(HdmiCecMessageBuilder.buildActiveSource(logicalAddress, physicalAddress));

        if (source().getType() == HdmiDeviceInfo.DEVICE_PLAYBACK) {
            // Reports menu-status active to receive <User Control Pressed>.
            sendCommand(
                    HdmiCecMessageBuilder.buildReportMenuStatus(logicalAddress, mDestination,
                            Constants.MENU_STATE_ACTIVATED));
        }

        source().setActiveSource(logicalAddress, physicalAddress, "ActiveSourceAction");
        mState = STATE_FINISHED;
        finish();
        return true;
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        return false;
    }

    @Override
    void handleTimerEvent(int state) {
        // No response expected
    }
}
