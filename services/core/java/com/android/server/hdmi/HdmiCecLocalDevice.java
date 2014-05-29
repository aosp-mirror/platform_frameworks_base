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

import com.android.server.hdmi.HdmiCecController.AllocateLogicalAddressCallback;

import android.hardware.hdmi.HdmiCec;
import android.hardware.hdmi.HdmiCecDeviceInfo;

/**
 * Class that models a logical CEC device hosted in this system. Handles initialization,
 * CEC commands that call for actions customized per device type.
 */
abstract class HdmiCecLocalDevice {

    protected final HdmiCecController mController;
    protected final int mDeviceType;
    protected final AddressAllocationCallback mAllocationCallback;
    protected int mAddress;
    protected int mPreferredAddress;
    protected HdmiCecDeviceInfo mDeviceInfo;

    /**
     * Callback interface to notify newly allocated logical address of the given
     * local device.
     */
    interface AddressAllocationCallback {
        /**
         * Called when a logical address of the given device is allocated.
         *
         * @param deviceType original device type
         * @param logicalAddress newly allocated logical address
         */
        void onAddressAllocated(int deviceType, int logicalAddress);
    }

    protected HdmiCecLocalDevice(HdmiCecController controller, int deviceType,
            AddressAllocationCallback callback) {
        mController = controller;
        mDeviceType = deviceType;
        mAllocationCallback = callback;
        mAddress = HdmiCec.ADDR_UNREGISTERED;
    }

    // Factory method that returns HdmiCecLocalDevice of corresponding type.
    static HdmiCecLocalDevice create(HdmiCecController controller, int deviceType,
            AddressAllocationCallback callback) {
        switch (deviceType) {
        case HdmiCec.DEVICE_TV:
            return new HdmiCecLocalDeviceTv(controller, callback);
        case HdmiCec.DEVICE_PLAYBACK:
            return new HdmiCecLocalDevicePlayback(controller, callback);
        default:
            return null;
        }
    }

    abstract void init();

    /**
     * Called when a logical address of the local device is allocated.
     * Note that internal variables are updated before it's called.
     */
    protected abstract void onAddressAllocated(int logicalAddress);

    protected void allocateAddress(int type) {
        mController.allocateLogicalAddress(type, mPreferredAddress,
                new AllocateLogicalAddressCallback() {
            @Override
            public void onAllocated(int deviceType, int logicalAddress) {
                mAddress = mPreferredAddress = logicalAddress;

                // Create and set device info.
                HdmiCecDeviceInfo deviceInfo = createDeviceInfo(mAddress, deviceType);
                setDeviceInfo(deviceInfo);
                mController.addDeviceInfo(deviceInfo);

                mController.addLogicalAddress(logicalAddress);
                onAddressAllocated(logicalAddress);
                if (mAllocationCallback != null) {
                    mAllocationCallback.onAddressAllocated(deviceType, logicalAddress);
                }
            }
        });
    }

    private final HdmiCecDeviceInfo createDeviceInfo(int logicalAddress, int deviceType) {
        int vendorId = mController.getVendorId();
        int physicalAddress = mController.getPhysicalAddress();
        // TODO: get device name read from system configuration.
        String displayName = HdmiCec.getDefaultDeviceName(logicalAddress);
        return new HdmiCecDeviceInfo(logicalAddress,
                physicalAddress, deviceType, vendorId, displayName);
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
}
