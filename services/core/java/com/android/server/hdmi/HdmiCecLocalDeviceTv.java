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

import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.hdmi.HdmiCec;
import android.hardware.hdmi.HdmiCecDeviceInfo;
import android.hardware.hdmi.HdmiCecMessage;
import android.os.RemoteException;
import android.util.Slog;

import java.util.Locale;

/**
 * Represent a logical device of type TV residing in Android system.
 */
final class HdmiCecLocalDeviceTv extends HdmiCecLocalDevice {
    private static final String TAG = "HdmiCecLocalDeviceTv";

    HdmiCecLocalDeviceTv(HdmiControlService service) {
        super(service, HdmiCec.DEVICE_TV);
    }

    @Override
    protected void onAddressAllocated(int logicalAddress) {
        // TODO: vendor-specific initialization here.

        mService.sendCecCommand(HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                mAddress, mService.getPhysicalAddress(), mDeviceType));
        mService.sendCecCommand(HdmiCecMessageBuilder.buildDeviceVendorIdCommand(
                mAddress, mService.getVendorId()));

        mService.launchDeviceDiscovery(mAddress);
        // TODO: Start routing control action, device discovery action.
    }

    @Override
    protected boolean onMessage(HdmiCecMessage message) {
        switch (message.getOpcode()) {
            case HdmiCec.MESSAGE_REPORT_PHYSICAL_ADDRESS:
                return handleReportPhysicalAddress(message);
            default:
                return super.onMessage(message);
        }
    }

    /**
     * Performs the action 'device select', or 'one touch play' initiated by TV.
     *
     * @param targetAddress logical address of the device to select
     * @param callback callback object to report the result with
     */
    void deviceSelect(int targetAddress, IHdmiControlCallback callback) {
        HdmiCecDeviceInfo targetDevice = mService.getDeviceInfo(targetAddress);
        if (targetDevice == null) {
            invokeCallback(callback, HdmiCec.RESULT_TARGET_NOT_AVAILABLE);
            return;
        }
        mService.removeAction(DeviceSelectAction.class);
        mService.addAndStartAction(new DeviceSelectAction(mService, mAddress,
                                                          mService.getPhysicalAddress(), targetDevice, callback));
    }

    private static void invokeCallback(IHdmiControlCallback callback, int result) {
        try {
            callback.onComplete(result);
        } catch (RemoteException e) {
            Slog.e(TAG, "Invoking callback failed:" + e);
        }
    }

    @Override
    protected boolean handleGetMenuLanguage(HdmiCecMessage message) {
        HdmiCecMessage command = HdmiCecMessageBuilder.buildSetMenuLanguageCommand(
                mAddress, Locale.getDefault().getISO3Language());
        // TODO: figure out how to handle failed to get language code.
        if (command != null) {
            mService.sendCecCommand(command);
        } else {
            Slog.w(TAG, "Failed to respond to <Get Menu Language>: " + message.toString());
        }
        return true;
    }

    private boolean handleReportPhysicalAddress(HdmiCecMessage message) {
        // Ignore if [Device Discovery Action] is going on.
        if (mService.hasAction(DeviceDiscoveryAction.class)) {
            Slog.i(TAG, "Ignore unrecognizable <Report Physical Address> "
                    + "because Device Discovery Action is on-going:" + message);
            return true;
        }

        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        mService.addAndStartAction(new NewDeviceAction(mService,
                mAddress, message.getSource(), physicalAddress));

        return true;
    }
}
