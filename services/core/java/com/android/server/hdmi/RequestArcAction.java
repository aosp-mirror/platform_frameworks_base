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

import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.util.Slog;

/**
 * Base feature action class for &lt;Request ARC Initiation&gt;/&lt;Request ARC Termination&gt;.
 */
abstract class RequestArcAction extends HdmiCecFeatureAction {
    private static final String TAG = "RequestArcAction";

    // State in which waits for ARC response.
    protected static final int STATE_WATING_FOR_REQUEST_ARC_REQUEST_RESPONSE = 1;

    // Logical address of AV Receiver.
    protected final int mAvrAddress;

    /**
     * @Constructor
     *
     * @param source {@link HdmiCecLocalDevice} instance
     * @param avrAddress address of AV receiver. It should be AUDIO_SYSTEM type
     * @param callback callback to inform about the status of the action
     */
    RequestArcAction(HdmiCecLocalDevice source, int avrAddress, IHdmiControlCallback callback) {
        super(source, callback);
        if (!HdmiUtils.verifyAddressType(getSourceAddress(), HdmiDeviceInfo.DEVICE_TV) ||
                !HdmiUtils.verifyAddressType(avrAddress, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM)) {
            Slog.w(TAG, "Device type mismatch, stop the action.");
            finish();
        }
        mAvrAddress = avrAddress;
    }

    RequestArcAction(HdmiCecLocalDevice source, int avrAddress) {
        this(source, avrAddress, null);
    }

    protected final void disableArcTransmission() {
        // Start Set ARC Transmission State action.
        SetArcTransmissionStateAction action = new SetArcTransmissionStateAction(localDevice(),
                mAvrAddress, false);
        addAndStartAction(action);
    }

    @Override
    final void handleTimerEvent(int state) {
        if (mState != state || state != STATE_WATING_FOR_REQUEST_ARC_REQUEST_RESPONSE) {
            return;
        }
        HdmiLogger.debug("[T] RequestArcAction.");
        disableArcTransmission();
        finishWithCallback(HdmiControlManager.RESULT_TIMEOUT);
    }
}
