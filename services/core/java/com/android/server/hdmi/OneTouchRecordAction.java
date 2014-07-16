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

import static com.android.server.hdmi.Constants.RECORDING_TYPE_ANALOGUE_RF;
import static com.android.server.hdmi.Constants.RECORDING_TYPE_DIGITAL_RF;
import static com.android.server.hdmi.Constants.RECORDING_TYPE_EXTERNAL_PHYSICAL_ADDRESS;
import static com.android.server.hdmi.Constants.RECORDING_TYPE_OWN_SOURCE;

/**
 * Feature action that performs one touch record. This class only provides a skeleton of one touch
 * play and has no detail implementation.
 */
public class OneTouchRecordAction extends FeatureAction {
    private final int mRecorderAddress;
    private final int mRecordingType;

    OneTouchRecordAction(HdmiCecLocalDevice source, int recorderAddress, int recordingType) {
        super(source);
        mRecorderAddress = recorderAddress;
        mRecordingType = recordingType;
    }

    @Override
    boolean start() {
        return false;
    }

    private void sendRecordOn(int recordingType) {
        switch (recordingType) {
            case RECORDING_TYPE_DIGITAL_RF:
                break;
            case RECORDING_TYPE_ANALOGUE_RF:
                break;
            case RECORDING_TYPE_EXTERNAL_PHYSICAL_ADDRESS:
                break;
            case RECORDING_TYPE_OWN_SOURCE:
                break;
            // TODO: implement this.
        }
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        return false;
    }

    @Override
    void handleTimerEvent(int state) {
    }

    int getRecorderAddress() {
        return mRecorderAddress;
    }
}
