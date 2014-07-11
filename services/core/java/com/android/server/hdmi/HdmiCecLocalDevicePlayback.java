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

import android.hardware.hdmi.HdmiCecDeviceInfo;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.IHdmiControlCallback;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;

/**
 * Represent a logical device of type Playback residing in Android system.
 */
final class HdmiCecLocalDevicePlayback extends HdmiCecLocalDevice {
    private static final String TAG = "HdmiCecLocalDevicePlayback";

    private boolean mIsActiveSource = false;

    HdmiCecLocalDevicePlayback(HdmiControlService service) {
        super(service, HdmiCecDeviceInfo.DEVICE_PLAYBACK);
    }

    @Override
    @ServiceThreadOnly
    protected void onAddressAllocated(int logicalAddress, boolean fromBootup) {
        assertRunOnServiceThread();
        mService.sendCecCommand(HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                mAddress, mService.getPhysicalAddress(), mDeviceType));
    }

    @ServiceThreadOnly
    void oneTouchPlay(IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        if (hasAction(OneTouchPlayAction.class)) {
            Slog.w(TAG, "oneTouchPlay already in progress");
            invokeCallback(callback, HdmiControlManager.RESULT_ALREADY_IN_PROGRESS);
            return;
        }

        // TODO: Consider the case of multiple TV sets. For now we always direct the command
        //       to the primary one.
        OneTouchPlayAction action = OneTouchPlayAction.create(this, Constants.ADDR_TV,
                callback);
        if (action == null) {
            Slog.w(TAG, "Cannot initiate oneTouchPlay");
            invokeCallback(callback, HdmiControlManager.RESULT_EXCEPTION);
            return;
        }
        addAndStartAction(action);
    }

    @ServiceThreadOnly
    void queryDisplayStatus(IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        if (hasAction(DevicePowerStatusAction.class)) {
            Slog.w(TAG, "queryDisplayStatus already in progress");
            invokeCallback(callback, HdmiControlManager.RESULT_ALREADY_IN_PROGRESS);
            return;
        }
        DevicePowerStatusAction action = DevicePowerStatusAction.create(this,
                Constants.ADDR_TV, callback);
        if (action == null) {
            Slog.w(TAG, "Cannot initiate queryDisplayStatus");
            invokeCallback(callback, HdmiControlManager.RESULT_EXCEPTION);
            return;
        }
        addAndStartAction(action);
    }

    @ServiceThreadOnly
    private void invokeCallback(IHdmiControlCallback callback, int result) {
        assertRunOnServiceThread();
        try {
            callback.onComplete(result);
        } catch (RemoteException e) {
            Slog.e(TAG, "Invoking callback failed:" + e);
        }
    }

    @Override
    @ServiceThreadOnly
    void onHotplug(int portId, boolean connected) {
        assertRunOnServiceThread();
        mCecMessageCache.flushAll();
        mIsActiveSource = false;
        if (connected && mService.isPowerStandbyOrTransient()) {
            mService.wakeUp();
        }
    }

    @ServiceThreadOnly
    void markActiveSource() {
        assertRunOnServiceThread();
        mIsActiveSource = true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleActiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        if (physicalAddress != mService.getPhysicalAddress()) {
            mIsActiveSource = false;
            if (mService.isPowerOnOrTransient()) {
                mService.standby();
            }
            return true;
        }
        return false;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleSetStreamPath(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        if (physicalAddress == mService.getPhysicalAddress()) {
            if (mService.isPowerStandbyOrTransient()) {
                mService.wakeUp();
            }
            return true;
        }
        return false;
    }

    @Override
    @ServiceThreadOnly
    protected void disableDevice(boolean initiatedByCec, PendingActionClearedCallback callback) {
        super.disableDevice(initiatedByCec, callback);

        assertRunOnServiceThread();
        if (!initiatedByCec && mIsActiveSource) {
            mService.sendCecCommand(HdmiCecMessageBuilder.buildInactiveSource(
                    mAddress, mService.getPhysicalAddress()));
        }
        mIsActiveSource = false;
        checkIfPendingActionsCleared();
    }
}
