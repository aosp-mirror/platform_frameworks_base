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

import android.hardware.hdmi.HdmiPortInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Predicate;
import com.android.server.hdmi.HdmiAnnotations.IoThreadOnly;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;
import com.android.server.hdmi.HdmiControlService.DevicePollingCallback;

import libcore.util.EmptyArray;

import java.util.ArrayList;
import java.util.LinkedList;
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

    /**
     * Interface to report allocated logical address.
     */
    interface AllocateAddressCallback {
        /**
         * Called when a new logical address is allocated.
         *
         * @param deviceType requested device type to allocate logical address
         * @param logicalAddress allocated logical address. If it is
         *                       {@link Constants#ADDR_UNREGISTERED}, it means that
         *                       it failed to allocate logical address for the given device type
         */
        void onAllocated(int deviceType, int logicalAddress);
    }

    private static final byte[] EMPTY_BODY = EmptyArray.BYTE;

    private static final int NUM_LOGICAL_ADDRESS = 16;

    // Predicate for whether the given logical address is remote device's one or not.
    private final Predicate<Integer> mRemoteDeviceAddressPredicate = new Predicate<Integer>() {
        @Override
        public boolean apply(Integer address) {
            return !isAllocatedLocalDeviceAddress(address);
        }
    };

    // Predicate whether the given logical address is system audio's one or not
    private final Predicate<Integer> mSystemAudioAddressPredicate = new Predicate<Integer>() {
        @Override
        public boolean apply(Integer address) {
            return HdmiUtils.getTypeFromAddress(address) == Constants.ADDR_AUDIO_SYSTEM;
        }
    };

    // Handler instance to process synchronous I/O (mainly send) message.
    private Handler mIoHandler;

    // Handler instance to process various messages coming from other CEC
    // device or issued by internal state change.
    private Handler mControlHandler;

    // Stores the pointer to the native implementation of the service that
    // interacts with HAL.
    private volatile long mNativePtr;

    private final HdmiControlService mService;

    // Stores the local CEC devices in the system. Device type is used for key.
    private final SparseArray<HdmiCecLocalDevice> mLocalDevices = new SparseArray<>();

    // Private constructor.  Use HdmiCecController.create().
    private HdmiCecController(HdmiControlService service) {
        mService = service;
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
        HdmiCecController controller = new HdmiCecController(service);
        long nativePtr = nativeInit(controller, service.getServiceLooper().getQueue());
        if (nativePtr == 0L) {
            controller = null;
            return null;
        }

        controller.init(nativePtr);
        return controller;
    }

    private void init(long nativePtr) {
        mIoHandler = new Handler(mService.getServiceLooper());
        mControlHandler = new Handler(mService.getServiceLooper());
        mNativePtr = nativePtr;
    }

    @ServiceThreadOnly
    void addLocalDevice(int deviceType, HdmiCecLocalDevice device) {
        assertRunOnServiceThread();
        mLocalDevices.put(deviceType, device);
    }

    /**
     * Allocate a new logical address of the given device type. Allocated
     * address will be reported through {@link AllocateAddressCallback}.
     *
     * <p> Declared as package-private, accessed by {@link HdmiControlService} only.
     *
     * @param deviceType type of device to used to determine logical address
     * @param preferredAddress a logical address preferred to be allocated.
     *                         If sets {@link Constants#ADDR_UNREGISTERED}, scans
     *                         the smallest logical address matched with the given device type.
     *                         Otherwise, scan address will start from {@code preferredAddress}
     * @param callback callback interface to report allocated logical address to caller
     */
    @ServiceThreadOnly
    void allocateLogicalAddress(final int deviceType, final int preferredAddress,
            final AllocateAddressCallback callback) {
        assertRunOnServiceThread();

        runOnIoThread(new Runnable() {
            @Override
            public void run() {
                handleAllocateLogicalAddress(deviceType, preferredAddress, callback);
            }
        });
    }

    @IoThreadOnly
    private void handleAllocateLogicalAddress(final int deviceType, int preferredAddress,
            final AllocateAddressCallback callback) {
        assertRunOnIoThread();
        int startAddress = preferredAddress;
        // If preferred address is "unregistered", start address will be the smallest
        // address matched with the given device type.
        if (preferredAddress == Constants.ADDR_UNREGISTERED) {
            for (int i = 0; i < NUM_LOGICAL_ADDRESS; ++i) {
                if (deviceType == HdmiUtils.getTypeFromAddress(i)) {
                    startAddress = i;
                    break;
                }
            }
        }

        int logicalAddress = Constants.ADDR_UNREGISTERED;
        // Iterates all possible addresses which has the same device type.
        for (int i = 0; i < NUM_LOGICAL_ADDRESS; ++i) {
            int curAddress = (startAddress + i) % NUM_LOGICAL_ADDRESS;
            if (curAddress != Constants.ADDR_UNREGISTERED
                    && deviceType == HdmiUtils.getTypeFromAddress(curAddress)) {
                int failedPollingCount = 0;
                for (int j = 0; j < HdmiConfig.ADDRESS_ALLOCATION_RETRY; ++j) {
                    if (!sendPollMessage(curAddress, curAddress, 1)) {
                        failedPollingCount++;
                    }
                }

                // Pick logical address if failed ratio is more than a half of all retries.
                if (failedPollingCount * 2 >  HdmiConfig.ADDRESS_ALLOCATION_RETRY) {
                    logicalAddress = curAddress;
                    break;
                }
            }
        }

        final int assignedAddress = logicalAddress;
        HdmiLogger.debug("New logical address for device [%d]: [preferred:%d, assigned:%d]",
                        deviceType, preferredAddress, assignedAddress);
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


    HdmiPortInfo[] getPortInfos() {
        return nativeGetPortInfos(mNativePtr);
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
    @ServiceThreadOnly
    int addLogicalAddress(int newLogicalAddress) {
        assertRunOnServiceThread();
        if (HdmiUtils.isValidAddress(newLogicalAddress)) {
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
    @ServiceThreadOnly
    void clearLogicalAddress() {
        assertRunOnServiceThread();
        for (int i = 0; i < mLocalDevices.size(); ++i) {
            mLocalDevices.valueAt(i).clearAddress();
        }
        nativeClearLogicalAddress(mNativePtr);
    }

    @ServiceThreadOnly
    void clearLocalDevices() {
        assertRunOnServiceThread();
        mLocalDevices.clear();
    }

    /**
     * Return the physical address of the device.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @return CEC physical address of the device. The range of success address
     *         is between 0x0000 and 0xFFFF. If failed it returns -1
     */
    @ServiceThreadOnly
    int getPhysicalAddress() {
        assertRunOnServiceThread();
        return nativeGetPhysicalAddress(mNativePtr);
    }

    /**
     * Return CEC version of the device.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     */
    @ServiceThreadOnly
    int getVersion() {
        assertRunOnServiceThread();
        return nativeGetVersion(mNativePtr);
    }

    /**
     * Return vendor id of the device.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     */
    @ServiceThreadOnly
    int getVendorId() {
        assertRunOnServiceThread();
        return nativeGetVendorId(mNativePtr);
    }

    /**
     * Set an option to CEC HAL.
     *
     * @param flag key of option
     * @param value value of option
     */
    @ServiceThreadOnly
    void setOption(int flag, int value) {
        assertRunOnServiceThread();
        nativeSetOption(mNativePtr, flag, value);
    }

    /**
     * Configure ARC circuit in the hardware logic to start or stop the feature.
     *
     * @param enabled whether to enable/disable ARC
     */
    @ServiceThreadOnly
    void setAudioReturnChannel(boolean enabled) {
        assertRunOnServiceThread();
        nativeSetAudioReturnChannel(mNativePtr, enabled);
    }

    /**
     * Return the connection status of the specified port
     *
     * @param port port number to check connection status
     * @return true if connected; otherwise, return false
     */
    @ServiceThreadOnly
    boolean isConnected(int port) {
        assertRunOnServiceThread();
        return nativeIsConnected(mNativePtr, port);
    }

    /**
     * Poll all remote devices. It sends &lt;Polling Message&gt; to all remote
     * devices.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @param callback an interface used to get a list of all remote devices' address
     * @param sourceAddress a logical address of source device where sends polling message
     * @param pickStrategy strategy how to pick polling candidates
     * @param retryCount the number of retry used to send polling message to remote devices
     */
    @ServiceThreadOnly
    void pollDevices(DevicePollingCallback callback, int sourceAddress, int pickStrategy,
            int retryCount) {
        assertRunOnServiceThread();

        // Extract polling candidates. No need to poll against local devices.
        List<Integer> pollingCandidates = pickPollCandidates(pickStrategy);
        ArrayList<Integer> allocated = new ArrayList<>();
        runDevicePolling(sourceAddress, pollingCandidates, retryCount, callback, allocated);
    }

    /**
     * Return a list of all {@link HdmiCecLocalDevice}s.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     */
    @ServiceThreadOnly
    List<HdmiCecLocalDevice> getLocalDeviceList() {
        assertRunOnServiceThread();
        return HdmiUtils.sparseArrayToList(mLocalDevices);
    }

    private List<Integer> pickPollCandidates(int pickStrategy) {
        int strategy = pickStrategy & Constants.POLL_STRATEGY_MASK;
        Predicate<Integer> pickPredicate = null;
        switch (strategy) {
            case Constants.POLL_STRATEGY_SYSTEM_AUDIO:
                pickPredicate = mSystemAudioAddressPredicate;
                break;
            case Constants.POLL_STRATEGY_REMOTES_DEVICES:
            default:  // The default is POLL_STRATEGY_REMOTES_DEVICES.
                pickPredicate = mRemoteDeviceAddressPredicate;
                break;
        }

        int iterationStrategy = pickStrategy & Constants.POLL_ITERATION_STRATEGY_MASK;
        LinkedList<Integer> pollingCandidates = new LinkedList<>();
        switch (iterationStrategy) {
            case Constants.POLL_ITERATION_IN_ORDER:
                for (int i = Constants.ADDR_TV; i <= Constants.ADDR_SPECIFIC_USE; ++i) {
                    if (pickPredicate.apply(i)) {
                        pollingCandidates.add(i);
                    }
                }
                break;
            case Constants.POLL_ITERATION_REVERSE_ORDER:
            default:  // The default is reverse order.
                for (int i = Constants.ADDR_SPECIFIC_USE; i >= Constants.ADDR_TV; --i) {
                    if (pickPredicate.apply(i)) {
                        pollingCandidates.add(i);
                    }
                }
                break;
        }
        return pollingCandidates;
    }

    @ServiceThreadOnly
    private boolean isAllocatedLocalDeviceAddress(int address) {
        assertRunOnServiceThread();
        for (int i = 0; i < mLocalDevices.size(); ++i) {
            if (mLocalDevices.valueAt(i).isAddressOf(address)) {
                return true;
            }
        }
        return false;
    }

    @ServiceThreadOnly
    private void runDevicePolling(final int sourceAddress,
            final List<Integer> candidates, final int retryCount,
            final DevicePollingCallback callback, final List<Integer> allocated) {
        assertRunOnServiceThread();
        if (candidates.isEmpty()) {
            if (callback != null) {
                HdmiLogger.debug("[P]:AllocatedAddress=%s", allocated.toString());
                callback.onPollingFinished(allocated);
            }
            return;
        }

        final Integer candidate = candidates.remove(0);
        // Proceed polling action for the next address once polling action for the
        // previous address is done.
        runOnIoThread(new Runnable() {
            @Override
            public void run() {
                if (sendPollMessage(sourceAddress, candidate, retryCount)) {
                    allocated.add(candidate);
                }
                runOnServiceThread(new Runnable() {
                    @Override
                    public void run() {
                        runDevicePolling(sourceAddress, candidates, retryCount, callback,
                                allocated);
                    }
                });
            }
        });
    }

    @IoThreadOnly
    private boolean sendPollMessage(int sourceAddress, int destinationAddress, int retryCount) {
        assertRunOnIoThread();
        for (int i = 0; i < retryCount; ++i) {
            // <Polling Message> is a message which has empty body.
            // If sending <Polling Message> failed (NAK), it becomes
            // new logical address for the device because no device uses
            // it as logical address of the device.
            if (nativeSendCecCommand(mNativePtr, sourceAddress, destinationAddress, EMPTY_BODY)
                    == Constants.SEND_RESULT_SUCCESS) {
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
        if (address == Constants.ADDR_BROADCAST) {
            return true;
        }
        return isAllocatedLocalDeviceAddress(address);
    }

    @ServiceThreadOnly
    private void onReceiveCommand(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (isAcceptableAddress(message.getDestination()) && mService.handleCecCommand(message)) {
            return;
        }
        // Not handled message, so we will reply it with <Feature Abort>.
        maySendFeatureAbortCommand(message, Constants.ABORT_UNRECOGNIZED_OPCODE);
    }

    @ServiceThreadOnly
    void maySendFeatureAbortCommand(HdmiCecMessage message, int reason) {
        assertRunOnServiceThread();
        // Swap the source and the destination.
        int src = message.getDestination();
        int dest = message.getSource();
        if (src == Constants.ADDR_BROADCAST || dest == Constants.ADDR_UNREGISTERED) {
            // Don't reply <Feature Abort> from the unregistered devices or for the broadcasted
            // messages. See CEC 12.2 Protocol General Rules for detail.
            return;
        }
        int originalOpcode = message.getOpcode();
        if (originalOpcode == Constants.MESSAGE_FEATURE_ABORT) {
            return;
        }
        sendCommand(
                HdmiCecMessageBuilder.buildFeatureAbortCommand(src, dest, originalOpcode, reason));
    }

    @ServiceThreadOnly
    void sendCommand(HdmiCecMessage cecMessage) {
        assertRunOnServiceThread();
        sendCommand(cecMessage, null);
    }

    @ServiceThreadOnly
    void sendCommand(final HdmiCecMessage cecMessage,
            final HdmiControlService.SendMessageCallback callback) {
        assertRunOnServiceThread();
        runOnIoThread(new Runnable() {
            @Override
            public void run() {
                HdmiLogger.debug("[S]:" + cecMessage);
                byte[] body = buildBody(cecMessage.getOpcode(), cecMessage.getParams());
                int i = 0;
                int errorCode = Constants.SEND_RESULT_SUCCESS;
                do {
                    errorCode = nativeSendCecCommand(mNativePtr, cecMessage.getSource(),
                            cecMessage.getDestination(), body);
                    if (errorCode == Constants.SEND_RESULT_SUCCESS) {
                        break;
                    }
                } while (i++ < HdmiConfig.RETRANSMISSION_COUNT);

                final int finalError = errorCode;
                if (finalError != Constants.SEND_RESULT_SUCCESS) {
                    Slog.w(TAG, "Failed to send " + cecMessage);
                }
                if (callback != null) {
                    runOnServiceThread(new Runnable() {
                        @Override
                        public void run() {
                            callback.onSendCompleted(finalError);
                        }
                    });
                }
            }
        });
    }

    /**
     * Called by native when incoming CEC message arrived.
     */
    @ServiceThreadOnly
    private void handleIncomingCecCommand(int srcAddress, int dstAddress, byte[] body) {
        assertRunOnServiceThread();
        HdmiCecMessage command = HdmiCecMessageBuilder.of(srcAddress, dstAddress, body);
        HdmiLogger.debug("[R]:" + command);
        onReceiveCommand(command);
    }

    /**
     * Called by native when a hotplug event issues.
     */
    @ServiceThreadOnly
    private void handleHotplug(int port, boolean connected) {
        assertRunOnServiceThread();
        HdmiLogger.debug("Hotplug event:[port:%d, connected:%b]", port, connected);
        mService.onHotplug(port, connected);
    }

    void dump(final IndentingPrintWriter pw) {
        for (int i = 0; i < mLocalDevices.size(); ++i) {
            pw.println("HdmiCecLocalDevice #" + i + ":");
            pw.increaseIndent();
            mLocalDevices.valueAt(i).dump(pw);
            pw.decreaseIndent();
        }
    }

    private static native long nativeInit(HdmiCecController handler, MessageQueue messageQueue);
    private static native int nativeSendCecCommand(long controllerPtr, int srcAddress,
            int dstAddress, byte[] body);
    private static native int nativeAddLogicalAddress(long controllerPtr, int logicalAddress);
    private static native void nativeClearLogicalAddress(long controllerPtr);
    private static native int nativeGetPhysicalAddress(long controllerPtr);
    private static native int nativeGetVersion(long controllerPtr);
    private static native int nativeGetVendorId(long controllerPtr);
    private static native HdmiPortInfo[] nativeGetPortInfos(long controllerPtr);
    private static native void nativeSetOption(long controllerPtr, int flag, int value);
    private static native void nativeSetAudioReturnChannel(long controllerPtr, boolean flag);
    private static native boolean nativeIsConnected(long controllerPtr, int port);
}
