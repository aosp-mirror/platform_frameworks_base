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

import android.hardware.hdmi.HdmiDeviceInfo;
import android.util.Slog;

import com.android.server.hdmi.HdmiCecLocalDevice.ActiveSource;

import java.io.UnsupportedEncodingException;

/**
 * Feature action that discovers the information of a newly found logical device.
 *
 * This action is created when receiving &lt;Report Physical Address&gt;, a CEC command a newly
 * connected HDMI-CEC device broadcasts to announce its advent. Additional commands are issued in
 * this action to gather more information on the device such as OSD name and device vendor ID.
 *
 * <p>The result is made in the form of {@link HdmiDeviceInfo} object, and passed to service
 * for the management through its life cycle.
 *
 * <p>Package-private, accessed by {@link HdmiControlService} only.
 */
final class NewDeviceAction extends HdmiCecFeatureAction {

    private static final String TAG = "NewDeviceAction";

    // State in which the action sent <Give OSD Name> and is waiting for <Set OSD Name>
    // that contains the name of the device for display on screen.
    static final int STATE_WAITING_FOR_SET_OSD_NAME = 1;

    // State in which the action sent <Give Device Vendor ID> and is waiting for
    // <Device Vendor ID> that contains the vendor ID of the device.
    static final int STATE_WAITING_FOR_DEVICE_VENDOR_ID = 2;

    private final int mDeviceLogicalAddress;
    private final int mDevicePhysicalAddress;
    private final int mDeviceType;

    private int mVendorId;
    private String mDisplayName;
    private int mTimeoutRetry;

    /**
     * Constructor.
     *
     * @param source {@link HdmiCecLocalDevice} instance
     * @param deviceLogicalAddress logical address of the device in interest
     * @param devicePhysicalAddress physical address of the device in interest
     * @param deviceType type of the device
     */
    NewDeviceAction(HdmiCecLocalDevice source, int deviceLogicalAddress,
            int devicePhysicalAddress, int deviceType) {
        super(source);
        mDeviceLogicalAddress = deviceLogicalAddress;
        mDevicePhysicalAddress = devicePhysicalAddress;
        mDeviceType = deviceType;
        mVendorId = Constants.VENDOR_ID_UNKNOWN;
    }

    @Override
    public boolean start() {
        requestOsdName(true);
        return true;
    }

    private void requestOsdName(boolean firstTry) {
        if (firstTry) {
            mTimeoutRetry = 0;
        }
        mState = STATE_WAITING_FOR_SET_OSD_NAME;
        if (mayProcessCommandIfCached(mDeviceLogicalAddress, Constants.MESSAGE_SET_OSD_NAME)) {
            return;
        }

        sendCommand(HdmiCecMessageBuilder.buildGiveOsdNameCommand(getSourceAddress(),
                mDeviceLogicalAddress));
        addTimer(mState, HdmiConfig.TIMEOUT_MS);
    }

    @Override
    public boolean processCommand(HdmiCecMessage cmd) {
        // For the logical device in interest, we want two more pieces of information -
        // osd name and vendor id. They are requested in sequence. In case we don't
        // get the expected responses (either by timeout or by receiving <feature abort> command),
        // set them to a default osd name and unknown vendor id respectively.
        int opcode = cmd.getOpcode();
        int src = cmd.getSource();
        byte[] params = cmd.getParams();

        if (mDeviceLogicalAddress != src) {
            return false;
        }

        if (mState == STATE_WAITING_FOR_SET_OSD_NAME) {
            if (opcode == Constants.MESSAGE_SET_OSD_NAME) {
                try {
                    mDisplayName = new String(params, "US-ASCII");
                } catch (UnsupportedEncodingException e) {
                    Slog.e(TAG, "Failed to get OSD name: " + e.getMessage());
                }
                requestVendorId(true);
                return true;
            } else if (opcode == Constants.MESSAGE_FEATURE_ABORT) {
                int requestOpcode = params[0] & 0xFF;
                if (requestOpcode == Constants.MESSAGE_GIVE_OSD_NAME) {
                    requestVendorId(true);
                    return true;
                }
            }
        } else if (mState == STATE_WAITING_FOR_DEVICE_VENDOR_ID) {
            if (opcode == Constants.MESSAGE_DEVICE_VENDOR_ID) {
                mVendorId = HdmiUtils.threeBytesToInt(params);
                addDeviceInfo();
                finish();
                return true;
            } else if (opcode == Constants.MESSAGE_FEATURE_ABORT) {
                int requestOpcode = params[0] & 0xFF;
                if (requestOpcode == Constants.MESSAGE_GIVE_DEVICE_VENDOR_ID) {
                    addDeviceInfo();
                    finish();
                    return true;
                }
            }
        }
        return false;
    }

    private boolean mayProcessCommandIfCached(int destAddress, int opcode) {
        HdmiCecMessage message = getCecMessageCache().getMessage(destAddress, opcode);
        if (message != null) {
            return processCommand(message);
        }
        return false;
    }

    private void requestVendorId(boolean firstTry) {
        if (firstTry) {
            mTimeoutRetry = 0;
        }
        // At first, transit to waiting status for <Device Vendor Id>.
        mState = STATE_WAITING_FOR_DEVICE_VENDOR_ID;
        // If the message is already in cache, process it.
        if (mayProcessCommandIfCached(mDeviceLogicalAddress,
                Constants.MESSAGE_DEVICE_VENDOR_ID)) {
            return;
        }
        sendCommand(HdmiCecMessageBuilder.buildGiveDeviceVendorIdCommand(getSourceAddress(),
                mDeviceLogicalAddress));
        addTimer(mState, HdmiConfig.TIMEOUT_MS);
    }

    private void addDeviceInfo() {
        // The device should be in the device list with default information.
        if (!localDevice().mService.getHdmiCecNetwork().isInDeviceList(mDeviceLogicalAddress,
                mDevicePhysicalAddress)) {
            Slog.w(TAG, String.format("Device not found (%02x, %04x)",
                    mDeviceLogicalAddress, mDevicePhysicalAddress));
            return;
        }
        if (mDisplayName == null) {
            mDisplayName = "";
        }
        HdmiDeviceInfo deviceInfo = HdmiDeviceInfo.cecDeviceBuilder()
                .setLogicalAddress(mDeviceLogicalAddress)
                .setPhysicalAddress(mDevicePhysicalAddress)
                .setPortId(tv().getPortId(mDevicePhysicalAddress))
                .setDeviceType(mDeviceType)
                .setVendorId(mVendorId)
                .setDisplayName(mDisplayName)
                .build();
        localDevice().mService.getHdmiCecNetwork().updateCecDevice(deviceInfo);

        // Consume CEC messages we already got for this newly found device.
        tv().processDelayedMessages(mDeviceLogicalAddress);

        if (HdmiUtils.isEligibleAddressForDevice(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM,
                mDeviceLogicalAddress)) {
            tv().onNewAvrAdded(deviceInfo);
        }
    }

    @Override
    public void handleTimerEvent(int state) {
        if (mState == STATE_NONE || mState != state) {
            return;
        }
        if (state == STATE_WAITING_FOR_SET_OSD_NAME) {
            if (++mTimeoutRetry < HdmiConfig.TIMEOUT_RETRY) {
                requestOsdName(false);
                return;
            }
            // Osd name request timed out. Try vendor id
            requestVendorId(true);
        } else if (state == STATE_WAITING_FOR_DEVICE_VENDOR_ID) {
            if (++mTimeoutRetry < HdmiConfig.TIMEOUT_RETRY) {
                requestVendorId(false);
                return;
            }
            // vendor id timed out. Go ahead creating the device info what we've got so far.
            addDeviceInfo();
            finish();
        }
    }

    boolean isActionOf(ActiveSource activeSource) {
        return (mDeviceLogicalAddress == activeSource.logicalAddress)
                && (mDevicePhysicalAddress == activeSource.physicalAddress);
    }
}
