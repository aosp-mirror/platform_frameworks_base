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

import com.android.internal.util.Preconditions;
import com.android.server.hdmi.HdmiControlService.DevicePollingCallback;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

/**
 * Feature action that handles device discovery sequences.
 * Device discovery is launched when TV device is woken from "Standby" state
 * or enabled "Control for Hdmi" from disabled state.
 *
 * <p>Device discovery goes through the following steps.
 * <ol>
 *   <li>Poll all non-local devices by sending &lt;Polling Message&gt;
 *   <li>Gather "Physical address" and "device type" of all acknowledged devices
 *   <li>Gather "OSD (display) name" of all acknowledge devices
 *   <li>Gather "Vendor id" of all acknowledge devices
 * </ol>
 */
final class DeviceDiscoveryAction extends HdmiCecFeatureAction {
    private static final String TAG = "DeviceDiscoveryAction";

    // State in which the action is waiting for device polling.
    private static final int STATE_WAITING_FOR_DEVICE_POLLING = 1;
    // State in which the action is waiting for gathering physical address of non-local devices.
    private static final int STATE_WAITING_FOR_PHYSICAL_ADDRESS = 2;
    // State in which the action is waiting for gathering osd name of non-local devices.
    private static final int STATE_WAITING_FOR_OSD_NAME = 3;
    // State in which the action is waiting for gathering vendor id of non-local devices.
    private static final int STATE_WAITING_FOR_VENDOR_ID = 4;

    /**
     * Interface used to report result of device discovery.
     */
    interface DeviceDiscoveryCallback {
        /**
         * Called when device discovery is done.
         *
         * @param deviceInfos a list of all non-local devices. It can be empty list.
         */
        void onDeviceDiscoveryDone(List<HdmiDeviceInfo> deviceInfos);
    }

    // An internal container used to keep track of device information during
    // this action.
    private static final class DeviceInfo {
        private final int mLogicalAddress;

        private int mPhysicalAddress = Constants.INVALID_PHYSICAL_ADDRESS;
        private int mPortId = Constants.INVALID_PORT_ID;
        private int mVendorId = Constants.UNKNOWN_VENDOR_ID;
        private String mDisplayName = "";
        private int mDeviceType = HdmiDeviceInfo.DEVICE_INACTIVE;

        private DeviceInfo(int logicalAddress) {
            mLogicalAddress = logicalAddress;
        }

        private HdmiDeviceInfo toHdmiDeviceInfo() {
            return new HdmiDeviceInfo(mLogicalAddress, mPhysicalAddress, mPortId, mDeviceType,
                    mVendorId, mDisplayName);
        }
    }

    private final ArrayList<DeviceInfo> mDevices = new ArrayList<>();
    private final DeviceDiscoveryCallback mCallback;
    private int mProcessedDeviceCount = 0;

    /**
     * Constructor.
     *
     * @param source an instance of {@link HdmiCecLocalDevice}.
     */
    DeviceDiscoveryAction(HdmiCecLocalDevice source, DeviceDiscoveryCallback callback) {
        super(source);
        mCallback = Preconditions.checkNotNull(callback);
    }

    @Override
    boolean start() {
        mDevices.clear();
        mState = STATE_WAITING_FOR_DEVICE_POLLING;

        pollDevices(new DevicePollingCallback() {
            @Override
            public void onPollingFinished(List<Integer> ackedAddress) {
                if (ackedAddress.isEmpty()) {
                    Slog.v(TAG, "No device is detected.");
                    wrapUpAndFinish();
                    return;
                }

                Slog.v(TAG, "Device detected: " + ackedAddress);
                allocateDevices(ackedAddress);
                startPhysicalAddressStage();
            }
        }, Constants.POLL_ITERATION_REVERSE_ORDER
            | Constants.POLL_STRATEGY_REMOTES_DEVICES, HdmiConfig.DEVICE_POLLING_RETRY);
        return true;
    }

    private void allocateDevices(List<Integer> addresses) {
        for (Integer i : addresses) {
            DeviceInfo info = new DeviceInfo(i);
            mDevices.add(info);
        }
    }

    private void startPhysicalAddressStage() {
        Slog.v(TAG, "Start [Physical Address Stage]:" + mDevices.size());
        mProcessedDeviceCount = 0;
        mState = STATE_WAITING_FOR_PHYSICAL_ADDRESS;

        checkAndProceedStage();
    }

    private boolean verifyValidLogicalAddress(int address) {
        return address >= Constants.ADDR_TV && address < Constants.ADDR_UNREGISTERED;
    }

    private void queryPhysicalAddress(int address) {
        if (!verifyValidLogicalAddress(address)) {
            checkAndProceedStage();
            return;
        }

        mActionTimer.clearTimerMessage();

        // Check cache first and send request if not exist.
        if (mayProcessMessageIfCached(address, Constants.MESSAGE_REPORT_PHYSICAL_ADDRESS)) {
            return;
        }
        sendCommand(HdmiCecMessageBuilder.buildGivePhysicalAddress(getSourceAddress(), address));
        addTimer(mState, HdmiConfig.TIMEOUT_MS);
    }

    private void startOsdNameStage() {
        Slog.v(TAG, "Start [Osd Name Stage]:" + mDevices.size());
        mProcessedDeviceCount = 0;
        mState = STATE_WAITING_FOR_OSD_NAME;

        checkAndProceedStage();
    }

    private void queryOsdName(int address) {
        if (!verifyValidLogicalAddress(address)) {
            checkAndProceedStage();
            return;
        }

        mActionTimer.clearTimerMessage();

        if (mayProcessMessageIfCached(address, Constants.MESSAGE_SET_OSD_NAME)) {
            return;
        }
        sendCommand(HdmiCecMessageBuilder.buildGiveOsdNameCommand(getSourceAddress(), address));
        addTimer(mState, HdmiConfig.TIMEOUT_MS);
    }

    private void startVendorIdStage() {
        Slog.v(TAG, "Start [Vendor Id Stage]:" + mDevices.size());

        mProcessedDeviceCount = 0;
        mState = STATE_WAITING_FOR_VENDOR_ID;

        checkAndProceedStage();
    }

    private void queryVendorId(int address) {
        if (!verifyValidLogicalAddress(address)) {
            checkAndProceedStage();
            return;
        }

        mActionTimer.clearTimerMessage();

        if (mayProcessMessageIfCached(address, Constants.MESSAGE_DEVICE_VENDOR_ID)) {
            return;
        }
        sendCommand(
                HdmiCecMessageBuilder.buildGiveDeviceVendorIdCommand(getSourceAddress(), address));
        addTimer(mState, HdmiConfig.TIMEOUT_MS);
    }

    private boolean mayProcessMessageIfCached(int address, int opcode) {
        HdmiCecMessage message = getCecMessageCache().getMessage(address, opcode);
        if (message != null) {
            processCommand(message);
            return true;
        }
        return false;
    }

    @Override
    boolean processCommand(HdmiCecMessage cmd) {
        switch (mState) {
            case STATE_WAITING_FOR_PHYSICAL_ADDRESS:
                if (cmd.getOpcode() == Constants.MESSAGE_REPORT_PHYSICAL_ADDRESS) {
                    handleReportPhysicalAddress(cmd);
                    return true;
                }
                return false;
            case STATE_WAITING_FOR_OSD_NAME:
                if (cmd.getOpcode() == Constants.MESSAGE_SET_OSD_NAME) {
                    handleSetOsdName(cmd);
                    return true;
                }
                return false;
            case STATE_WAITING_FOR_VENDOR_ID:
                if (cmd.getOpcode() == Constants.MESSAGE_DEVICE_VENDOR_ID) {
                    handleVendorId(cmd);
                    return true;
                }
                return false;
            case STATE_WAITING_FOR_DEVICE_POLLING:
                // Fall through.
            default:
                return false;
        }
    }

    private void handleReportPhysicalAddress(HdmiCecMessage cmd) {
        Preconditions.checkState(mProcessedDeviceCount < mDevices.size());

        DeviceInfo current = mDevices.get(mProcessedDeviceCount);
        if (current.mLogicalAddress != cmd.getSource()) {
            Slog.w(TAG, "Unmatched address[expected:" + current.mLogicalAddress + ", actual:" +
                    cmd.getSource());
            return;
        }

        byte params[] = cmd.getParams();
        current.mPhysicalAddress = HdmiUtils.twoBytesToInt(params);
        current.mPortId = getPortId(current.mPhysicalAddress);
        current.mDeviceType = params[2] & 0xFF;

        tv().updateCecSwitchInfo(current.mLogicalAddress, current.mDeviceType,
                    current.mPhysicalAddress);

        increaseProcessedDeviceCount();
        checkAndProceedStage();
    }

    private int getPortId(int physicalAddress) {
        return tv().getPortId(physicalAddress);
    }

    private void handleSetOsdName(HdmiCecMessage cmd) {
        Preconditions.checkState(mProcessedDeviceCount < mDevices.size());

        DeviceInfo current = mDevices.get(mProcessedDeviceCount);
        if (current.mLogicalAddress != cmd.getSource()) {
            Slog.w(TAG, "Unmatched address[expected:" + current.mLogicalAddress + ", actual:" +
                    cmd.getSource());
            return;
        }

        String displayName = null;
        try {
            displayName = new String(cmd.getParams(), "US-ASCII");
        } catch (UnsupportedEncodingException e) {
            Slog.w(TAG, "Failed to decode display name: " + cmd.toString());
            // If failed to get display name, use the default name of device.
            displayName = HdmiUtils.getDefaultDeviceName(current.mLogicalAddress);
        }
        current.mDisplayName = displayName;
        increaseProcessedDeviceCount();
        checkAndProceedStage();
    }

    private void handleVendorId(HdmiCecMessage cmd) {
        Preconditions.checkState(mProcessedDeviceCount < mDevices.size());

        DeviceInfo current = mDevices.get(mProcessedDeviceCount);
        if (current.mLogicalAddress != cmd.getSource()) {
            Slog.w(TAG, "Unmatched address[expected:" + current.mLogicalAddress + ", actual:" +
                    cmd.getSource());
            return;
        }

        byte[] params = cmd.getParams();
        int vendorId = HdmiUtils.threeBytesToInt(params);
        current.mVendorId = vendorId;
        increaseProcessedDeviceCount();
        checkAndProceedStage();
    }

    private void increaseProcessedDeviceCount() {
        mProcessedDeviceCount++;
    }

    private void removeDevice(int index) {
        mDevices.remove(index);
    }

    private void wrapUpAndFinish() {
        Slog.v(TAG, "---------Wrap up Device Discovery:[" + mDevices.size() + "]---------");
        ArrayList<HdmiDeviceInfo> result = new ArrayList<>();
        for (DeviceInfo info : mDevices) {
            HdmiDeviceInfo cecDeviceInfo = info.toHdmiDeviceInfo();
            Slog.v(TAG, " DeviceInfo: " + cecDeviceInfo);
            result.add(cecDeviceInfo);
        }
        Slog.v(TAG, "--------------------------------------------");
        mCallback.onDeviceDiscoveryDone(result);
        finish();
    }

    private void checkAndProceedStage() {
        if (mDevices.isEmpty()) {
            wrapUpAndFinish();
            return;
        }

        // If finished current stage, move on to next stage.
        if (mProcessedDeviceCount == mDevices.size()) {
            mProcessedDeviceCount = 0;
            switch (mState) {
                case STATE_WAITING_FOR_PHYSICAL_ADDRESS:
                    startOsdNameStage();
                    return;
                case STATE_WAITING_FOR_OSD_NAME:
                    startVendorIdStage();
                    return;
                case STATE_WAITING_FOR_VENDOR_ID:
                    wrapUpAndFinish();
                    return;
                default:
                    return;
            }
        } else {
            int address = mDevices.get(mProcessedDeviceCount).mLogicalAddress;
            switch (mState) {
                case STATE_WAITING_FOR_PHYSICAL_ADDRESS:
                    queryPhysicalAddress(address);
                    return;
                case STATE_WAITING_FOR_OSD_NAME:
                    queryOsdName(address);
                    return;
                case STATE_WAITING_FOR_VENDOR_ID:
                    queryVendorId(address);
                default:
                    return;
            }
        }
    }

    @Override
    void handleTimerEvent(int state) {
        if (mState == STATE_NONE || mState != state) {
            return;
        }

        Slog.v(TAG, "Timeout[State=" + mState + ", Processed=" + mProcessedDeviceCount);
        removeDevice(mProcessedDeviceCount);
        checkAndProceedStage();
    }
}
