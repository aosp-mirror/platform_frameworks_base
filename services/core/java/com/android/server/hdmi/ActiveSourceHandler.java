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
 * Used by feature actions that need to handle the command in their flow.
 */
final class ActiveSourceHandler {
    private static final String TAG = "ActiveSourceHandler";

    private final HdmiCecLocalDevice mSource;
    private final HdmiControlService mService;
    @Nullable
    private final IHdmiControlCallback mCallback;

    static ActiveSourceHandler create(HdmiCecLocalDevice source,
            IHdmiControlCallback callback) {
        if (source == null) {
            Slog.e(TAG, "Wrong arguments");
            return null;
        }
        return new ActiveSourceHandler(source, callback);
    }

    private ActiveSourceHandler(HdmiCecLocalDevice source, IHdmiControlCallback callback) {
        mSource = source;
        mService = mSource.getService();
        mCallback = callback;
    }

    /**
     * Handles the incoming active source command.
     *
     * @param deviceLogicalAddress logical address of the device to be the active source
     * @param routingPath routing path of the device to be the active source
     */
    void process(int deviceLogicalAddress, int routingPath) {
        if (getSourcePath() == routingPath && mSource.getActiveSource() == getSourceAddress()) {
            invokeCallback(HdmiCec.RESULT_SUCCESS);
            return;
        }
        HdmiCecDeviceInfo device = mService.getDeviceInfo(deviceLogicalAddress);
        if (device == null) {
            // "New device action" initiated by <Active Source> does not require
            // "Routing change action".
            mSource.addAndStartAction(new NewDeviceAction(mSource, deviceLogicalAddress,
                    routingPath, false));
        }

        if (!mSource.isInPresetInstallationMode()) {
            int prevActiveInput = mSource.getActivePortId();
            mSource.updateActiveDevice(deviceLogicalAddress, routingPath);
            if (prevActiveInput != mSource.getActivePortId()) {
                // TODO: change port input here.
            }
            invokeCallback(HdmiCec.RESULT_SUCCESS);
        } else {
            // TV is in a mode that should keep its current source/input from
            // being changed for its operation. Reclaim the active source
            // or switch the port back to the one used for the current mode.
            if (mSource.getActiveSource() == getSourceAddress()) {
                HdmiCecMessage activeSource =
                        HdmiCecMessageBuilder.buildActiveSource(getSourceAddress(),
                                getSourcePath());
                mService.sendCecCommand(activeSource);
                mSource.updateActiveDevice(deviceLogicalAddress, routingPath);
                invokeCallback(HdmiCec.RESULT_SUCCESS);
            } else {
                int activePath = mSource.getActivePath();
                mService.sendCecCommand(HdmiCecMessageBuilder.buildRoutingChange(getSourceAddress(),
                        routingPath, activePath));
                // TODO: Start port select action here
                // PortSelectAction action = new PortSelectAction(mService, getSourceAddress(),
                // activePath, mCallback);
                // mService.addActionAndStart(action);
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
