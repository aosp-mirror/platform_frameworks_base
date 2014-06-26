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

import android.annotation.Nullable;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.hdmi.HdmiCec;
import android.hardware.hdmi.HdmiCecDeviceInfo;
import android.hardware.hdmi.HdmiCecMessage;
import android.os.RemoteException;
import android.util.Slog;

/**
 * Handles CEC command &lt;Active Source&gt;.
 * <p>
 * Used by feature actions that need to handle the command in their flow. Only for TV
 * local device.
 */
final class ActiveSourceHandler {
    private static final String TAG = "ActiveSourceHandler";

    private final HdmiCecLocalDeviceTv mSource;
    private final HdmiControlService mService;
    @Nullable
    private final IHdmiControlCallback mCallback;

    static ActiveSourceHandler create(HdmiCecLocalDeviceTv source, IHdmiControlCallback callback) {
        if (source == null) {
            Slog.e(TAG, "Wrong arguments");
            return null;
        }
        return new ActiveSourceHandler(source, callback);
    }

    private ActiveSourceHandler(HdmiCecLocalDeviceTv source, IHdmiControlCallback callback) {
        mSource = source;
        mService = mSource.getService();
        mCallback = callback;
    }

    /**
     * Handles the incoming active source command.
     *
     * @param activeAddress logical address of the device to be the active source
     * @param activePath routing path of the device to be the active source
     */
    void process(int activeAddress, int activePath) {
        // Seq #17
        HdmiCecLocalDeviceTv tv = mSource;
        if (getSourcePath() == activePath && tv.getActiveSource() == getSourceAddress()) {
            invokeCallback(HdmiCec.RESULT_SUCCESS);
            return;
        }
        HdmiCecDeviceInfo device = mService.getDeviceInfo(activeAddress);
        if (device == null) {
            // "New device action" initiated by <Active Source> does not require
            // "Routing change action".
            tv.addAndStartAction(new NewDeviceAction(tv, activeAddress, activePath, false));
        }

        int currentActive = tv.getActiveSource();
        int currentPath = tv.getActivePath();
        if (!tv.isInPresetInstallationMode()) {
            tv.updateActiveSource(activeAddress, activePath);
            if (currentActive != activeAddress && currentPath != activePath) {
                tv.updateActivePortId(mService.pathToPortId(activePath));
            }
            invokeCallback(HdmiCec.RESULT_SUCCESS);
        } else {
            // TV is in a mode that should keep its current source/input from
            // being changed for its operation. Reclaim the active source
            // or switch the port back to the one used for the current mode.
            if (currentActive == getSourceAddress()) {
                HdmiCecMessage activeSource =
                        HdmiCecMessageBuilder.buildActiveSource(currentActive, currentPath);
                mService.sendCecCommand(activeSource);
                tv.updateActiveSource(currentActive, currentPath);
                invokeCallback(HdmiCec.RESULT_SUCCESS);
            } else {
                HdmiCecMessage routingChange = HdmiCecMessageBuilder.buildRoutingChange(
                        getSourceAddress(), activePath, currentPath);
                mService.sendCecCommand(routingChange);
                tv.addAndStartAction(new RoutingControlAction(tv, currentPath, mCallback));
            }
        }
    }

    private final int getSourceAddress() {
        return mSource.getDeviceInfo().getLogicalAddress();
    }

    private final int getSourcePath() {
        return mSource.getDeviceInfo().getPhysicalAddress();
    }

    private void invokeCallback(int result) {
        if (mCallback == null) {
            return;
        }
        try {
            mCallback.onComplete(result);
        } catch (RemoteException e) {
            Slog.e(TAG, "Callback failed:" + e);
        }
    }
}
