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
 *
 * <p>Used by feature actions that need to handle the command in their flow.
 */
final class ActiveSourceHandler {
    private static final String TAG = "ActiveSourceHandler";

    private final HdmiControlService mService;
    private final int mSourceAddress;
    private final int mSourcePath;
    @Nullable private final IHdmiControlCallback mCallback;

    static ActiveSourceHandler create(HdmiControlService service, int sourceAddress,
            int sourcePath, IHdmiControlCallback callback) {
        if (service == null) {
            Slog.e(TAG, "Wrong arguments");
            return null;
        }
        return new ActiveSourceHandler(service, sourceAddress, sourcePath, callback);
    }

    private ActiveSourceHandler(HdmiControlService service, int sourceAddress, int sourcePath,
            IHdmiControlCallback callback) {
        mService = service;
        mSourceAddress = sourceAddress;
        mSourcePath = sourcePath;
        mCallback = callback;
    }

    /**
     * Handles the incoming active source command.
     *
     * @param deviceLogicalAddress logical address of the device to be the active source
     * @param routingPath routing path of the device to be the active source
     */
    void process(int deviceLogicalAddress, int routingPath) {
        if (mSourcePath == routingPath && mService.getActiveSource() == mSourceAddress) {
            invokeCallback(HdmiCec.RESULT_SUCCESS);
            return;
        }
        HdmiCecDeviceInfo device = mService.getDeviceInfo(deviceLogicalAddress);
        if (device == null) {
            // "New device action" initiated by <Active Source> does not require
            // "Routing change action".
            mService.addAndStartAction(new NewDeviceAction(mService, mSourceAddress,
                    deviceLogicalAddress, routingPath, false));
        }

        if (!mService.isInPresetInstallationMode()) {
            int prevActiveInput = mService.getActiveInput();
            mService.updateActiveDevice(deviceLogicalAddress, routingPath);
            if (prevActiveInput != mService.getActiveInput()) {
                // TODO: change port input here.
            }
            invokeCallback(HdmiCec.RESULT_SUCCESS);
        } else {
            // TV is in a mode that should keep its current source/input from
            // being changed for its operation. Reclaim the active source
            // or switch the port back to the one used for the current mode.
            if (mService.getActiveSource() == mSourceAddress) {
                HdmiCecMessage activeSource =
                        HdmiCecMessageBuilder.buildActiveSource(mSourceAddress, mSourcePath);
                mService.sendCecCommand(activeSource);
                mService.updateActiveDevice(deviceLogicalAddress, routingPath);
                invokeCallback(HdmiCec.RESULT_SUCCESS);
            } else {
                int activePath = mService.getActivePath();
                mService.sendCecCommand(HdmiCecMessageBuilder.buildRoutingChange(mSourceAddress,
                        routingPath, activePath));
                // TODO: Start port select action here
                // PortSelectAction action = new PortSelectAction(mService, mSourceAddress,
                //        activePath, mCallback);
                // mService.addActionAndStart(action);
            }
        }
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
