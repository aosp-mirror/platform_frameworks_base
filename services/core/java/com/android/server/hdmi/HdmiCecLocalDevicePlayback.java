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

import android.hardware.hdmi.HdmiCec;
import android.hardware.hdmi.IHdmiControlCallback;
import android.os.RemoteException;
import android.util.Slog;

/**
 * Represent a logical device of type Playback residing in Android system.
 */
final class HdmiCecLocalDevicePlayback extends HdmiCecLocalDevice {
    private static final String TAG = "HdmiCecLocalDevicePlayback";

    HdmiCecLocalDevicePlayback(HdmiControlService service) {
        super(service, HdmiCec.DEVICE_PLAYBACK);
    }

    @Override
    protected void onAddressAllocated(int logicalAddress) {
        mService.sendCecCommand(HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                mAddress, mService.getPhysicalAddress(), mDeviceType));
    }

    void oneTouchPlay(IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        if (hasAction(OneTouchPlayAction.class)) {
            Slog.w(TAG, "oneTouchPlay already in progress");
            invokeCallback(callback, HdmiCec.RESULT_ALREADY_IN_PROGRESS);
            return;
        }

        // TODO: Consider the case of multiple TV sets. For now we always direct the command
        //       to the primary one.
        OneTouchPlayAction action = OneTouchPlayAction.create(this, HdmiCec.ADDR_TV, callback);
        if (action == null) {
            Slog.w(TAG, "Cannot initiate oneTouchPlay");
            invokeCallback(callback, HdmiCec.RESULT_EXCEPTION);
            return;
        }
        addAndStartAction(action);
    }

    void queryDisplayStatus(IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        if (hasAction(DevicePowerStatusAction.class)) {
            Slog.w(TAG, "queryDisplayStatus already in progress");
            invokeCallback(callback, HdmiCec.RESULT_ALREADY_IN_PROGRESS);
            return;
        }
        DevicePowerStatusAction action = DevicePowerStatusAction.create(this,
                HdmiCec.ADDR_TV, callback);
        if (action == null) {
            Slog.w(TAG, "Cannot initiate queryDisplayStatus");
            invokeCallback(callback, HdmiCec.RESULT_EXCEPTION);
            return;
        }
        addAndStartAction(action);
    }

    private void invokeCallback(IHdmiControlCallback callback, int result) {
        try {
            callback.onComplete(result);
        } catch (RemoteException e) {
            Slog.e(TAG, "Invoking callback failed:" + e);
        }
    }

    @Override
    void onHotplug(int portId, boolean connected) {
        // TODO: clear devices connected to the given port id.
        mCecMessageCache.flushAll();
    }
}
