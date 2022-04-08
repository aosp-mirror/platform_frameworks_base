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
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiControlManager;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.hdmi.HdmiCecLocalDevice.ActiveSource;

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
     * @param newActive new active source information
     * @param deviceType device type of the new active source
     */
    void process(ActiveSource newActive, int deviceType) {
        // Seq #17
        HdmiCecLocalDeviceTv tv = mSource;
        HdmiDeviceInfo device = mService.getDeviceInfo(newActive.logicalAddress);
        if (device == null) {
            tv.startNewDeviceAction(newActive, deviceType);
        }

        if (!tv.isProhibitMode()) {
            ActiveSource old = ActiveSource.of(tv.getActiveSource());
            tv.updateActiveSource(newActive);
            boolean notifyInputChange = (mCallback == null);
            if (!old.equals(newActive)) {
                tv.setPrevPortId(tv.getActivePortId());
            }
            tv.updateActiveInput(newActive.physicalAddress, notifyInputChange);
            invokeCallback(HdmiControlManager.RESULT_SUCCESS);
        } else {
            // TV is in a mode that should keep its current source/input from
            // being changed for its operation. Reclaim the active source
            // or switch the port back to the one used for the current mode.
            ActiveSource current = tv.getActiveSource();
            if (current.logicalAddress == getSourceAddress()) {
                HdmiCecMessage activeSourceCommand = HdmiCecMessageBuilder.buildActiveSource(
                        current.logicalAddress, current.physicalAddress);
                mService.sendCecCommand(activeSourceCommand);
                tv.updateActiveSource(current);
                invokeCallback(HdmiControlManager.RESULT_SUCCESS);
            } else {
                tv.startRoutingControl(newActive.physicalAddress, current.physicalAddress, true,
                        mCallback);
            }
        }
    }

    private final int getSourceAddress() {
        return mSource.getDeviceInfo().getLogicalAddress();
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
