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
import android.hardware.hdmi.HdmiCecDeviceInfo;
import android.hardware.hdmi.HdmiCecMessage;
import android.util.Slog;

/**
 * Class that models a logical CEC device hosted in this system. Handles initialization,
 * CEC commands that call for actions customized per device type.
 */
abstract class HdmiCecLocalDevice {
    private static final String TAG = "HdmiCecLocalDevice";

    protected final HdmiControlService mService;
    protected final int mDeviceType;
    protected int mAddress;
    protected int mPreferredAddress;
    protected HdmiCecDeviceInfo mDeviceInfo;

    protected HdmiCecLocalDevice(HdmiControlService service, int deviceType) {
        mService = service;
        mDeviceType = deviceType;
        mAddress = HdmiCec.ADDR_UNREGISTERED;
    }

    // Factory method that returns HdmiCecLocalDevice of corresponding type.
    static HdmiCecLocalDevice create(HdmiControlService service, int deviceType) {
        switch (deviceType) {
        case HdmiCec.DEVICE_TV:
            return new HdmiCecLocalDeviceTv(service);
        case HdmiCec.DEVICE_PLAYBACK:
            return new HdmiCecLocalDevicePlayback(service);
        default:
            return null;
        }
    }

    void init() {
        mPreferredAddress = HdmiCec.ADDR_UNREGISTERED;
        // TODO: load preferred address from permanent storage.
    }

    /**
     * Called once a logical address of the local device is allocated.
     */
    protected abstract void onAddressAllocated(int logicalAddress);

    /**
     * Dispatch incoming message.
     *
     * @param message incoming message
     * @return true if consumed a message; otherwise, return false.
     */
    final boolean dispatchMessage(HdmiCecMessage message) {
        int dest = message.getDestination();
        if (dest != mAddress && dest != HdmiCec.ADDR_BROADCAST) {
            return false;
        }
        return onMessage(message);
    }

    protected final boolean onMessage(HdmiCecMessage message) {
        switch (message.getOpcode()) {
            case HdmiCec.MESSAGE_GET_MENU_LANGUAGE:
                return handleGetMenuLanguage(message);
            case HdmiCec.MESSAGE_GIVE_PHYSICAL_ADDRESS:
                return handleGivePhysicalAddress();
            case HdmiCec.MESSAGE_GIVE_OSD_NAME:
                return handleGiveOsdName(message);
            case HdmiCec.MESSAGE_GIVE_DEVICE_VENDOR_ID:
                return handleGiveDeviceVendorId();
            case HdmiCec.MESSAGE_GET_CEC_VERSION:
                return handleGetCecVersion(message);
            case HdmiCec.MESSAGE_REPORT_PHYSICAL_ADDRESS:
                return handleReportPhysicalAddress(message);
            default:
                return false;
        }
    }

    protected boolean handleGivePhysicalAddress() {
        int physicalAddress = mService.getPhysicalAddress();
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                mAddress, physicalAddress, mDeviceType);
        mService.sendCecCommand(cecMessage);
        return true;
    }

    protected boolean handleGiveDeviceVendorId() {
        int vendorId = mService.getVendorId();
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildDeviceVendorIdCommand(
                mAddress, vendorId);
        mService.sendCecCommand(cecMessage);
        return true;
    }

    protected boolean handleGetCecVersion(HdmiCecMessage message) {
        int version = mService.getCecVersion();
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildCecVersion(message.getDestination(),
                message.getSource(), version);
        mService.sendCecCommand(cecMessage);
        return true;
    }

    protected boolean handleGetMenuLanguage(HdmiCecMessage message) {
        Slog.w(TAG, "Only TV can handle <Get Menu Language>:" + message.toString());
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildFeatureAbortCommand(mAddress,
                        message.getSource(), HdmiCec.MESSAGE_GET_MENU_LANGUAGE,
                        HdmiConstants.ABORT_UNRECOGNIZED_MODE));
        return true;
    }

    protected boolean handleGiveOsdName(HdmiCecMessage message) {
        // Note that since this method is called after logical address allocation is done,
        // mDeviceInfo should not be null.
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildSetOsdNameCommand(
                mAddress, message.getSource(), mDeviceInfo.getDisplayName());
        if (cecMessage != null) {
            mService.sendCecCommand(cecMessage);
        } else {
            Slog.w(TAG, "Failed to build <Get Osd Name>:" + mDeviceInfo.getDisplayName());
        }
        return true;
    }

    protected boolean handleVendorSpecificCommand(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleReportPhysicalAddress(HdmiCecMessage message) {
        return false;
    }

    final void handleAddressAllocated(int logicalAddress) {
        mAddress = mPreferredAddress = logicalAddress;
        onAddressAllocated(logicalAddress);
    }

    HdmiCecDeviceInfo getDeviceInfo() {
        return mDeviceInfo;
    }

    void setDeviceInfo(HdmiCecDeviceInfo info) {
        mDeviceInfo = info;
    }

    // Returns true if the logical address is same as the argument.
    boolean isAddressOf(int addr) {
        return addr == mAddress;
    }

    // Resets the logical address to unregistered(15), meaning the logical device is invalid.
    void clearAddress() {
        mAddress = HdmiCec.ADDR_UNREGISTERED;
    }

    void setPreferredAddress(int addr) {
        mPreferredAddress = addr;
    }

    int getPreferredAddress() {
        return mPreferredAddress;
    }
}
