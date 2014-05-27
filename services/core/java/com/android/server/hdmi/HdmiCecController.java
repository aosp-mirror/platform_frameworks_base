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
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.Slog;
import android.util.SparseArray;

import com.android.server.hdmi.HdmiControlService.DevicePollingCallback;

import libcore.util.EmptyArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages HDMI-CEC command and behaviors. It converts user's command into CEC command
 * and pass it to CEC HAL so that it sends message to other device. For incoming
 * message it translates the message and delegates it to proper module.
 *
 * <p>It should be careful to access member variables on IO thread because
 * it can be accessed from system thread as well.
 *
 * <p>It can be created only by {@link HdmiCecController#create}
 *
 * <p>Declared as package-private, accessed by {@link HdmiControlService} only.
 */
final class HdmiCecController {
    private static final String TAG = "HdmiCecController";

    private static final byte[] EMPTY_BODY = EmptyArray.BYTE;

    // A message to pass cec send command to IO looper.
    private static final int MSG_SEND_CEC_COMMAND = 1;
    // A message to delegate logical allocation to IO looper.
    private static final int MSG_ALLOCATE_LOGICAL_ADDRESS = 2;

    // Message types to handle incoming message in main service looper.
    private final static int MSG_RECEIVE_CEC_COMMAND = 1;
    // A message to report allocated logical address to main control looper.
    private final static int MSG_REPORT_LOGICAL_ADDRESS = 2;

    private static final int NUM_LOGICAL_ADDRESS = 16;

    private static final int RETRY_COUNT_FOR_LOGICAL_ADDRESS_ALLOCATION = 3;

    // Handler instance to process synchronous I/O (mainly send) message.
    private Handler mIoHandler;

    // Handler instance to process various messages coming from other CEC
    // device or issued by internal state change.
    private Handler mControlHandler;

    // Stores the pointer to the native implementation of the service that
    // interacts with HAL.
    private volatile long mNativePtr;

    private HdmiControlService mService;

    // Map-like container of all cec devices including local ones.
    // A logical address of device is used as key of container.
    private final SparseArray<HdmiCecDeviceInfo> mDeviceInfos = new SparseArray<>();

    // Stores the local CEC devices in the system. Device type is used for key.
    private final SparseArray<HdmiCecLocalDevice> mLocalDevices = new SparseArray<>();

    // Private constructor.  Use HdmiCecController.create().
    private HdmiCecController() {
    }

    /**
     * A factory method to get {@link HdmiCecController}. If it fails to initialize
     * inner device or has no device it will return {@code null}.
     *
     * <p>Declared as package-private, accessed by {@link HdmiControlService} only.
     * @param service {@link HdmiControlService} instance used to create internal handler
     *                and to pass callback for incoming message or event.
     * @return {@link HdmiCecController} if device is initialized successfully. Otherwise,
     *         returns {@code null}.
     */
    static HdmiCecController create(HdmiControlService service) {
        HdmiCecController controller = new HdmiCecController();
        long nativePtr = nativeInit(controller, service.getServiceLooper().getQueue());
        if (nativePtr == 0L) {
            controller = null;
            return null;
        }

        controller.init(service, nativePtr);
        return controller;
    }

    private void init(HdmiControlService service, long nativePtr) {
        mService = service;
        mIoHandler = new Handler(service.getServiceLooper());
        mControlHandler = new Handler(service.getServiceLooper());
        mNativePtr = nativePtr;
    }

    /**
     * Perform initialization for each hosted device.
     *
     * @param deviceTypes array of device types
     */
    void initializeLocalDevices(int[] deviceTypes) {
        assertRunOnServiceThread();
        for (int type : deviceTypes) {
            HdmiCecLocalDevice device = HdmiCecLocalDevice.create(this, type);
            if (device == null) {
                continue;
            }
            // TODO: Consider restoring the local device addresses from persistent storage
            //       to allocate the same addresses again if possible.
            device.setPreferredAddress(HdmiCec.ADDR_UNREGISTERED);
            mLocalDevices.put(type, device);
            device.init();
        }
    }

    /**
     * Interface to report allocated logical address.
     */
    interface AllocateLogicalAddressCallback {
        /**
         * Called when a new logical address is allocated.
         *
         * @param deviceType requested device type to allocate logical address
         * @param logicalAddress allocated logical address. If it is
         *                       {@link HdmiCec#ADDR_UNREGISTERED}, it means that
         *                       it failed to allocate logical address for the given device type
         */
        void onAllocated(int deviceType, int logicalAddress);
    }

    /**
     * Allocate a new logical address of the given device type. Allocated
     * address will be reported through {@link AllocateLogicalAddressCallback}.
     *
     * <p> Declared as package-private, accessed by {@link HdmiControlService} only.
     *
     * @param deviceType type of device to used to determine logical address
     * @param preferredAddress a logical address preferred to be allocated.
     *                         If sets {@link HdmiCec#ADDR_UNREGISTERED}, scans
     *                         the smallest logical address matched with the given device type.
     *                         Otherwise, scan address will start from {@code preferredAddress}
     * @param callback callback interface to report allocated logical address to caller
     */
    void allocateLogicalAddress(final int deviceType, final int preferredAddress,
            final AllocateLogicalAddressCallback callback) {
        assertRunOnServiceThread();

        runOnIoThread(new Runnable() {
            @Override
            public void run() {
                handleAllocateLogicalAddress(deviceType, preferredAddress, callback);
            }
        });
    }

    private void handleAllocateLogicalAddress(final int deviceType, int preferredAddress,
            final AllocateLogicalAddressCallback callback) {
        assertRunOnIoThread();
        int startAddress = preferredAddress;
        // If preferred address is "unregistered", start address will be the smallest
        // address matched with the given device type.
        if (preferredAddress == HdmiCec.ADDR_UNREGISTERED) {
            for (int i = 0; i < NUM_LOGICAL_ADDRESS; ++i) {
                if (deviceType == HdmiCec.getTypeFromAddress(i)) {
                    startAddress = i;
                    break;
                }
            }
        }

        int logicalAddress = HdmiCec.ADDR_UNREGISTERED;
        // Iterates all possible addresses which has the same device type.
        for (int i = 0; i < NUM_LOGICAL_ADDRESS; ++i) {
            int curAddress = (startAddress + i) % NUM_LOGICAL_ADDRESS;
            if (curAddress != HdmiCec.ADDR_UNREGISTERED
                    && deviceType == HdmiCec.getTypeFromAddress(curAddress)) {
                if (!sendPollMessage(curAddress, RETRY_COUNT_FOR_LOGICAL_ADDRESS_ALLOCATION)) {
                    logicalAddress = curAddress;
                    break;
                }
            }
        }

        final int assignedAddress = logicalAddress;
        if (callback != null) {
            runOnServiceThread(new Runnable() {
                    @Override
                public void run() {
                    callback.onAllocated(deviceType, assignedAddress);
                }
            });
        }
    }

    private static byte[] buildBody(int opcode, byte[] params) {
        byte[] body = new byte[params.length + 1];
        body[0] = (byte) opcode;
        System.arraycopy(params, 0, body, 1, params.length);
        return body;
    }

    /**
     * Add a new {@link HdmiCecDeviceInfo}. It returns old device info which has the same
     * logical address as new device info's.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @param deviceInfo a new {@link HdmiCecDeviceInfo} to be added.
     * @return {@code null} if it is new device. Otherwise, returns old {@HdmiCecDeviceInfo}
     *         that has the same logical address as new one has.
     */
    HdmiCecDeviceInfo addDeviceInfo(HdmiCecDeviceInfo deviceInfo) {
        assertRunOnServiceThread();
        HdmiCecDeviceInfo oldDeviceInfo = getDeviceInfo(deviceInfo.getLogicalAddress());
        if (oldDeviceInfo != null) {
            removeDeviceInfo(deviceInfo.getLogicalAddress());
        }
        mDeviceInfos.append(deviceInfo.getLogicalAddress(), deviceInfo);
        return oldDeviceInfo;
    }

    /**
     * Remove a device info corresponding to the given {@code logicalAddress}.
     * It returns removed {@link HdmiCecDeviceInfo} if exists.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @param logicalAddress logical address of device to be removed
     * @return removed {@link HdmiCecDeviceInfo} it exists. Otherwise, returns {@code null}
     */
    HdmiCecDeviceInfo removeDeviceInfo(int logicalAddress) {
        assertRunOnServiceThread();
        HdmiCecDeviceInfo deviceInfo = mDeviceInfos.get(logicalAddress);
        if (deviceInfo != null) {
            mDeviceInfos.remove(logicalAddress);
        }
        return deviceInfo;
    }

    /**
     * Clear all device info.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     */
    void clearDeviceInfoList() {
        assertRunOnServiceThread();
        mDeviceInfos.clear();
    }

    /**
     * Return a list of all {@link HdmiCecDeviceInfo}.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     */
    List<HdmiCecDeviceInfo> getDeviceInfoList() {
        assertRunOnServiceThread();
        return sparseArrayToList(mDeviceInfos);
    }

    /**
     * Return a {@link HdmiCecDeviceInfo} corresponding to the given {@code logicalAddress}.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @param logicalAddress logical address to be retrieved
     * @return {@link HdmiCecDeviceInfo} matched with the given {@code logicalAddress}.
     *         Returns null if no logical address matched
     */
    HdmiCecDeviceInfo getDeviceInfo(int logicalAddress) {
        assertRunOnServiceThread();
        return mDeviceInfos.get(logicalAddress);
    }

    /**
     * Return the locally hosted logical device of a given type.
     *
     * @param deviceType logical device type
     * @return {@link HdmiCecLocalDevice} instance if the instance of the type is available;
     *          otherwise null.
     */
    HdmiCecLocalDevice getLocalDevice(int deviceType) {
        return mLocalDevices.get(deviceType);
    }

    /**
     * Add a new logical address to the device. Device's HW should be notified
     * when a new logical address is assigned to a device, so that it can accept
     * a command having available destinations.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @param newLogicalAddress a logical address to be added
     * @return 0 on success. Otherwise, returns negative value
     */
    int addLogicalAddress(int newLogicalAddress) {
        assertRunOnServiceThread();
        if (HdmiCec.isValidAddress(newLogicalAddress)) {
            return nativeAddLogicalAddress(mNativePtr, newLogicalAddress);
        } else {
            return -1;
        }
    }

    /**
     * Clear all logical addresses registered in the device.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     */
    void clearLogicalAddress() {
        assertRunOnServiceThread();
        // TODO: consider to backup logical address so that new logical address
        // allocation can use it as preferred address.
        for (int i = 0; i < mLocalDevices.size(); ++i) {
            mLocalDevices.valueAt(i).clearAddress();
        }
        nativeClearLogicalAddress(mNativePtr);
    }

    /**
     * Return the physical address of the device.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @return CEC physical address of the device. The range of success address
     *         is between 0x0000 and 0xFFFF. If failed it returns -1
     */
    int getPhysicalAddress() {
        assertRunOnServiceThread();
        return nativeGetPhysicalAddress(mNativePtr);
    }

    /**
     * Return CEC version of the device.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     */
    int getVersion() {
        assertRunOnServiceThread();
        return nativeGetVersion(mNativePtr);
    }

    /**
     * Return vendor id of the device.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     */
    int getVendorId() {
        assertRunOnServiceThread();
        return nativeGetVendorId(mNativePtr);
    }

    /**
     * Poll all remote devices. It sends &lt;Polling Message&gt; to all remote
     * devices.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @param callback an interface used to get a list of all remote devices' address
     * @param retryCount the number of retry used to send polling message to remote devices
     */
    void pollDevices(DevicePollingCallback callback, int retryCount) {
        assertRunOnServiceThread();
        // Extract polling candidates. No need to poll against local devices.
        ArrayList<Integer> pollingCandidates = new ArrayList<>();
        for (int i = HdmiCec.ADDR_SPECIFIC_USE; i >= HdmiCec.ADDR_TV; --i) {
            if (!isAllocatedLocalDeviceAddress(i)) {
                pollingCandidates.add(i);
            }
        }

        runDevicePolling(pollingCandidates, retryCount, callback);
    }

    /**
     * Return a list of all {@link HdmiCecLocalDevice}s.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     */
    List<HdmiCecLocalDevice> getLocalDeviceList() {
        assertRunOnServiceThread();
        return sparseArrayToList(mLocalDevices);
    }

    private static <T> List<T> sparseArrayToList(SparseArray<T> array) {
        ArrayList<T> list = new ArrayList<>();
        for (int i = 0; i < array.size(); ++i) {
            list.add(array.valueAt(i));
        }
        return list;
    }

    private boolean isAllocatedLocalDeviceAddress(int address) {
        for (int i = 0; i < mLocalDevices.size(); ++i) {
            if (mLocalDevices.valueAt(i).isAddressOf(address)) {
                return true;
            }
        }
        return false;
    }

    private void runDevicePolling(final List<Integer> candidates, final int retryCount,
            final DevicePollingCallback callback) {
        assertRunOnServiceThread();
        runOnIoThread(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Integer> allocated = new ArrayList<>();
                for (Integer address : candidates) {
                    if (sendPollMessage(address, retryCount)) {
                        allocated.add(address);
                    }
                }
                if (callback != null) {
                    runOnServiceThread(new Runnable() {
                        @Override
                        public void run() {
                            callback.onPollingFinished(allocated);
                        }
                    });
                }
            }
        });
    }

    private boolean sendPollMessage(int address, int retryCount) {
        assertRunOnIoThread();
        for (int i = 0; i < retryCount; ++i) {
            // <Polling Message> is a message which has empty body and
            // uses same address for both source and destination address.
            // If sending <Polling Message> failed (NAK), it becomes
            // new logical address for the device because no device uses
            // it as logical address of the device.
            if (nativeSendCecCommand(mNativePtr, address, address, EMPTY_BODY)
                    == HdmiControlService.SEND_RESULT_SUCCESS) {
                return true;
            }
        }
        return false;
    }

    private void assertRunOnIoThread() {
        if (Looper.myLooper() != mIoHandler.getLooper()) {
            throw new IllegalStateException("Should run on io thread.");
        }
    }

    private void assertRunOnServiceThread() {
        if (Looper.myLooper() != mControlHandler.getLooper()) {
            throw new IllegalStateException("Should run on service thread.");
        }
    }

    // Run a Runnable on IO thread.
    // It should be careful to access member variables on IO thread because
    // it can be accessed from system thread as well.
    private void runOnIoThread(Runnable runnable) {
        mIoHandler.post(runnable);
    }

    private void runOnServiceThread(Runnable runnable) {
        mControlHandler.post(runnable);
    }

    private boolean isAcceptableAddress(int address) {
        // Can access command targeting devices available in local device or broadcast command.
        if (address == HdmiCec.ADDR_BROADCAST) {
            return true;
        }
        return isAllocatedLocalDeviceAddress(address);
    }

    private void onReceiveCommand(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (isAcceptableAddress(message.getDestination())
                && mService.handleCecCommand(message)) {
            return;
        }

        // TODO: Use device's source address for broadcast message.
        int sourceAddress = message.getDestination() != HdmiCec.ADDR_BROADCAST ?
                message.getDestination() : 0;
        // Reply <Feature Abort> to initiator (source) for all requests.
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildFeatureAbortCommand
                (sourceAddress, message.getSource(), message.getOpcode(),
                        HdmiCecMessageBuilder.ABORT_REFUSED);
        sendCommand(cecMessage, null);
    }

    void sendCommand(HdmiCecMessage cecMessage) {
        sendCommand(cecMessage, null);
    }

    void sendCommand(final HdmiCecMessage cecMessage,
            final HdmiControlService.SendMessageCallback callback) {
        runOnIoThread(new Runnable() {
            @Override
            public void run() {
                byte[] body = buildBody(cecMessage.getOpcode(), cecMessage.getParams());
                final int error = nativeSendCecCommand(mNativePtr, cecMessage.getSource(),
                        cecMessage.getDestination(), body);
                if (error != HdmiControlService.SEND_RESULT_SUCCESS) {
                    Slog.w(TAG, "Failed to send " + cecMessage);
                }
                if (callback != null) {
                    runOnServiceThread(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSendCompleted(error);
                        }
                    });
                }
            }
        });
    }

    /**
     * Called by native when incoming CEC message arrived.
     */
    private void handleIncomingCecCommand(int srcAddress, int dstAddress, byte[] body) {
        assertRunOnServiceThread();
        onReceiveCommand(HdmiCecMessageBuilder.of(srcAddress, dstAddress, body));
    }

    /**
     * Called by native when a hotplug event issues.
     */
    private void handleHotplug(boolean connected) {
        // TODO: once add port number to cec HAL interface, pass port number
        // to the service.
        mService.onHotplug(0, connected);
    }

    private static native long nativeInit(HdmiCecController handler, MessageQueue messageQueue);
    private static native int nativeSendCecCommand(long controllerPtr, int srcAddress,
            int dstAddress, byte[] body);
    private static native int nativeAddLogicalAddress(long controllerPtr, int logicalAddress);
    private static native void nativeClearLogicalAddress(long controllerPtr);
    private static native int nativeGetPhysicalAddress(long controllerPtr);
    private static native int nativeGetVersion(long controllerPtr);
    private static native int nativeGetVendorId(long controllerPtr);
}
