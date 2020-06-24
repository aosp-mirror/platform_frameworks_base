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
import android.hardware.tv.cec.V1_0.CecMessage;
import android.hardware.tv.cec.V1_0.HotplugEvent;
import android.hardware.tv.cec.V1_0.IHdmiCec;
import android.hardware.tv.cec.V1_0.IHdmiCec.getPhysicalAddressCallback;
import android.hardware.tv.cec.V1_0.IHdmiCecCallback;
import android.hardware.tv.cec.V1_0.Result;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.os.Handler;
import android.os.IHwBinder;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.hdmi.HdmiAnnotations.IoThreadOnly;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;
import com.android.server.hdmi.HdmiControlService.DevicePollingCallback;

import libcore.util.EmptyArray;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Predicate;

import sun.util.locale.LanguageTag;

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

    private static final int MAX_HDMI_MESSAGE_HISTORY = 250;

    private static final int INVALID_PHYSICAL_ADDRESS = 0xFFFF;

    /** Cookie for matching the right end point. */
    protected static final int HDMI_CEC_HAL_DEATH_COOKIE = 353;

    // Predicate for whether the given logical address is remote device's one or not.
    private final Predicate<Integer> mRemoteDeviceAddressPredicate = new Predicate<Integer>() {
        @Override
        public boolean test(Integer address) {
            return !isAllocatedLocalDeviceAddress(address);
        }
    };

    // Predicate whether the given logical address is system audio's one or not
    private final Predicate<Integer> mSystemAudioAddressPredicate = new Predicate<Integer>() {
        @Override
        public boolean test(Integer address) {
            return HdmiUtils.getTypeFromAddress(address) == Constants.ADDR_AUDIO_SYSTEM;
        }
    };

    // Handler instance to process synchronous I/O (mainly send) message.
    private Handler mIoHandler;

    // Handler instance to process various messages coming from other CEC
    // device or issued by internal state change.
    private Handler mControlHandler;

    private final HdmiControlService mService;

    // Stores the local CEC devices in the system. Device type is used for key.
    private final SparseArray<HdmiCecLocalDevice> mLocalDevices = new SparseArray<>();

    // Stores recent CEC messages and HDMI Hotplug event history for debugging purpose.
    private final ArrayBlockingQueue<Dumpable> mMessageHistory =
            new ArrayBlockingQueue<>(MAX_HDMI_MESSAGE_HISTORY);

    private final NativeWrapper mNativeWrapperImpl;

    /** List of logical addresses that should not be assigned to the current device.
     *
     * <p>Parsed from {@link Constants#PROPERTY_HDMI_CEC_NEVER_ASSIGN_LOGICAL_ADDRESSES}
     */
    private final List<Integer> mNeverAssignLogicalAddresses;

    // Private constructor.  Use HdmiCecController.create().
    private HdmiCecController(HdmiControlService service, NativeWrapper nativeWrapper) {
        mService = service;
        mNativeWrapperImpl = nativeWrapper;
        mNeverAssignLogicalAddresses = mService.getIntList(SystemProperties.get(
            Constants.PROPERTY_HDMI_CEC_NEVER_ASSIGN_LOGICAL_ADDRESSES));
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
        return createWithNativeWrapper(service, new NativeWrapperImpl());
    }

    /**
     * A factory method with injection of native methods for testing.
     */
    static HdmiCecController createWithNativeWrapper(
            HdmiControlService service, NativeWrapper nativeWrapper) {
        HdmiCecController controller = new HdmiCecController(service, nativeWrapper);
        String nativePtr = nativeWrapper.nativeInit();
        if (nativePtr == null) {
            HdmiLogger.warning("Couldn't get tv.cec service.");
            return null;
        }
        controller.init(nativeWrapper);
        return controller;
    }

    private void init(NativeWrapper nativeWrapper) {
        mIoHandler = new Handler(mService.getIoLooper());
        mControlHandler = new Handler(mService.getServiceLooper());
        nativeWrapper.setCallback(new HdmiCecCallback());
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
                    && deviceType == HdmiUtils.getTypeFromAddress(curAddress)
                    && !mNeverAssignLogicalAddresses.contains(curAddress)) {
                boolean acked = false;
                for (int j = 0; j < HdmiConfig.ADDRESS_ALLOCATION_RETRY; ++j) {
                    if (sendPollMessage(curAddress, curAddress, 1)) {
                        acked = true;
                        break;
                    }
                }
                // If sending <Polling Message> failed, it becomes new logical address for the
                // device because no device uses it as logical address of the device.
                if (!acked) {
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
        return mNativeWrapperImpl.nativeGetPortInfos();
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
            return mNativeWrapperImpl.nativeAddLogicalAddress(newLogicalAddress);
        } else {
            return Result.FAILURE_INVALID_ARGS;
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
        mNativeWrapperImpl.nativeClearLogicalAddress();
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
        return mNativeWrapperImpl.nativeGetPhysicalAddress();
    }

    /**
     * Return CEC version of the device.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     */
    @ServiceThreadOnly
    int getVersion() {
        assertRunOnServiceThread();
        return mNativeWrapperImpl.nativeGetVersion();
    }

    /**
     * Return vendor id of the device.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     */
    @ServiceThreadOnly
    int getVendorId() {
        assertRunOnServiceThread();
        return mNativeWrapperImpl.nativeGetVendorId();
    }

    /**
     * Set an option to CEC HAL.
     *
     * @param flag key of option
     * @param enabled whether to enable/disable the given option.
     */
    @ServiceThreadOnly
    void setOption(int flag, boolean enabled) {
        assertRunOnServiceThread();
        HdmiLogger.debug("setOption: [flag:%d, enabled:%b]", flag, enabled);
        mNativeWrapperImpl.nativeSetOption(flag, enabled);
    }

    /**
     * Informs CEC HAL about the current system language.
     *
     * @param language Three-letter code defined in ISO/FDIS 639-2. Must be lowercase letters.
     */
    @ServiceThreadOnly
    void setLanguage(String language) {
        assertRunOnServiceThread();
        if (!LanguageTag.isLanguage(language)) {
            return;
        }
        mNativeWrapperImpl.nativeSetLanguage(language);
    }

    /**
     * Configure ARC circuit in the hardware logic to start or stop the feature.
     *
     * @param port ID of HDMI port to which AVR is connected
     * @param enabled whether to enable/disable ARC
     */
    @ServiceThreadOnly
    void enableAudioReturnChannel(int port, boolean enabled) {
        assertRunOnServiceThread();
        mNativeWrapperImpl.nativeEnableAudioReturnChannel(port, enabled);
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
        return mNativeWrapperImpl.nativeIsConnected(port);
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
                    if (pickPredicate.test(i)) {
                        pollingCandidates.add(i);
                    }
                }
                break;
            case Constants.POLL_ITERATION_REVERSE_ORDER:
            default:  // The default is reverse order.
                for (int i = Constants.ADDR_SPECIFIC_USE; i >= Constants.ADDR_TV; --i) {
                    if (pickPredicate.test(i)) {
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
            int ret =
                    mNativeWrapperImpl.nativeSendCecCommand(
                        sourceAddress, destinationAddress, EMPTY_BODY);
            if (ret == SendMessageResult.SUCCESS) {
                return true;
            } else if (ret != SendMessageResult.NACK) {
                // Unusual failure
                HdmiLogger.warning("Failed to send a polling message(%d->%d) with return code %d",
                        sourceAddress, destinationAddress, ret);
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

    @ServiceThreadOnly
    void flush(final Runnable runnable) {
        assertRunOnServiceThread();
        runOnIoThread(new Runnable() {
            @Override
            public void run() {
                // This ensures the runnable for cleanup is performed after all the pending
                // commands are processed by IO thread.
                runOnServiceThread(runnable);
            }
        });
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
        if ((isAcceptableAddress(message.getDestination())
            || !mService.isAddressAllocated())
            && mService.handleCecCommand(message)) {
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
        addCecMessageToHistory(false /* isReceived */, cecMessage);
        runOnIoThread(new Runnable() {
            @Override
            public void run() {
                HdmiLogger.debug("[S]:" + cecMessage);
                byte[] body = buildBody(cecMessage.getOpcode(), cecMessage.getParams());
                int i = 0;
                int errorCode = SendMessageResult.SUCCESS;
                do {
                    errorCode = mNativeWrapperImpl.nativeSendCecCommand(
                        cecMessage.getSource(), cecMessage.getDestination(), body);
                    if (errorCode == SendMessageResult.SUCCESS) {
                        break;
                    }
                } while (i++ < HdmiConfig.RETRANSMISSION_COUNT);

                final int finalError = errorCode;
                if (finalError != SendMessageResult.SUCCESS) {
                    Slog.w(TAG, "Failed to send " + cecMessage + " with errorCode=" + finalError);
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
     * Called when incoming CEC message arrived.
     */
    @ServiceThreadOnly
    private void handleIncomingCecCommand(int srcAddress, int dstAddress, byte[] body) {
        assertRunOnServiceThread();
        HdmiCecMessage command = HdmiCecMessageBuilder.of(srcAddress, dstAddress, body);
        HdmiLogger.debug("[R]:" + command);
        addCecMessageToHistory(true /* isReceived */, command);
        onReceiveCommand(command);
    }

    /**
     * Called when a hotplug event issues.
     */
    @ServiceThreadOnly
    private void handleHotplug(int port, boolean connected) {
        assertRunOnServiceThread();
        HdmiLogger.debug("Hotplug event:[port:%d, connected:%b]", port, connected);
        addHotplugEventToHistory(port, connected);
        mService.onHotplug(port, connected);
    }

    @ServiceThreadOnly
    private void addHotplugEventToHistory(int port, boolean connected) {
        assertRunOnServiceThread();
        addEventToHistory(new HotplugHistoryRecord(port, connected));
    }

    @ServiceThreadOnly
    private void addCecMessageToHistory(boolean isReceived, HdmiCecMessage message) {
        assertRunOnServiceThread();
        addEventToHistory(new MessageHistoryRecord(isReceived, message));
    }

    private void addEventToHistory(Dumpable event) {
        if (!mMessageHistory.offer(event)) {
            mMessageHistory.poll();
            mMessageHistory.offer(event);
        }
    }

    void dump(final IndentingPrintWriter pw) {
        for (int i = 0; i < mLocalDevices.size(); ++i) {
            pw.println("HdmiCecLocalDevice #" + mLocalDevices.keyAt(i) + ":");
            pw.increaseIndent();
            mLocalDevices.valueAt(i).dump(pw);
            pw.decreaseIndent();
        }

        pw.println("CEC message history:");
        pw.increaseIndent();
        final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (Dumpable record : mMessageHistory) {
            record.dump(pw, sdf);
        }
        pw.decreaseIndent();
    }

    protected interface NativeWrapper {
        String nativeInit();
        void setCallback(HdmiCecCallback callback);
        int nativeSendCecCommand(int srcAddress, int dstAddress, byte[] body);
        int nativeAddLogicalAddress(int logicalAddress);
        void nativeClearLogicalAddress();
        int nativeGetPhysicalAddress();
        int nativeGetVersion();
        int nativeGetVendorId();
        HdmiPortInfo[] nativeGetPortInfos();
        void nativeSetOption(int flag, boolean enabled);
        void nativeSetLanguage(String language);
        void nativeEnableAudioReturnChannel(int port, boolean flag);
        boolean nativeIsConnected(int port);
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
    private static native void nativeSetOption(long controllerPtr, int flag, boolean enabled);
    private static native void nativeSetLanguage(long controllerPtr, String language);
    private static native void nativeEnableAudioReturnChannel(long controllerPtr,
        int port, boolean flag);
    private static native boolean nativeIsConnected(long controllerPtr, int port);

    private static final class NativeWrapperImpl implements NativeWrapper,
            IHwBinder.DeathRecipient, getPhysicalAddressCallback {
        private IHdmiCec mHdmiCec;
        private final Object mLock = new Object();
        private int mPhysicalAddress = INVALID_PHYSICAL_ADDRESS;

        @Override
        public String nativeInit() {
            return (connectToHal() ? mHdmiCec.toString() : null);
        }

        boolean connectToHal() {
            try {
                mHdmiCec = IHdmiCec.getService();
                try {
                    mHdmiCec.linkToDeath(this, HDMI_CEC_HAL_DEATH_COOKIE);
                } catch (RemoteException e) {
                    HdmiLogger.error("Couldn't link to death : ", e);
                }
            } catch (RemoteException e) {
                HdmiLogger.error("Couldn't get tv.cec service : ", e);
                return false;
            }
            return true;
        }

        @Override
        public void setCallback(HdmiCecCallback callback) {
            try {
                mHdmiCec.setCallback(callback);
            } catch (RemoteException e) {
                HdmiLogger.error("Couldn't initialise tv.cec callback : ", e);
            }
        }

        @Override
        public int nativeSendCecCommand(int srcAddress, int dstAddress, byte[] body) {
            CecMessage message = new CecMessage();
            message.initiator = srcAddress;
            message.destination = dstAddress;
            message.body = new ArrayList<>(body.length);
            for (byte b : body) {
                message.body.add(b);
            }
            try {
                return mHdmiCec.sendMessage(message);
            } catch (RemoteException e) {
                HdmiLogger.error("Failed to send CEC message : ", e);
                return SendMessageResult.FAIL;
            }
        }

        @Override
        public int nativeAddLogicalAddress(int logicalAddress) {
            try {
                return mHdmiCec.addLogicalAddress(logicalAddress);
            } catch (RemoteException e) {
                HdmiLogger.error("Failed to add a logical address : ", e);
                return Result.FAILURE_INVALID_ARGS;
            }
        }

        @Override
        public void nativeClearLogicalAddress() {
            try {
                mHdmiCec.clearLogicalAddress();
            } catch (RemoteException e) {
                HdmiLogger.error("Failed to clear logical address : ", e);
            }
        }

        @Override
        public int nativeGetPhysicalAddress() {
            try {
                mHdmiCec.getPhysicalAddress(this);
                return mPhysicalAddress;
            } catch (RemoteException e) {
                HdmiLogger.error("Failed to get physical address : ", e);
                return INVALID_PHYSICAL_ADDRESS;
            }
        }

        @Override
        public int nativeGetVersion() {
            try {
                return mHdmiCec.getCecVersion();
            } catch (RemoteException e) {
                HdmiLogger.error("Failed to get cec version : ", e);
                return Result.FAILURE_UNKNOWN;
            }
        }

        @Override
        public int nativeGetVendorId() {
            try {
                return mHdmiCec.getVendorId();
            } catch (RemoteException e) {
                HdmiLogger.error("Failed to get vendor id : ", e);
                return Result.FAILURE_UNKNOWN;
            }
        }

        @Override
        public HdmiPortInfo[] nativeGetPortInfos() {
            try {
                ArrayList<android.hardware.tv.cec.V1_0.HdmiPortInfo> hdmiPortInfos =
                        mHdmiCec.getPortInfo();
                HdmiPortInfo[] hdmiPortInfo = new HdmiPortInfo[hdmiPortInfos.size()];
                int i = 0;
                for (android.hardware.tv.cec.V1_0.HdmiPortInfo portInfo : hdmiPortInfos) {
                    hdmiPortInfo[i] = new HdmiPortInfo(portInfo.portId,
                            portInfo.type,
                            portInfo.physicalAddress,
                            portInfo.cecSupported,
                            false,
                            portInfo.arcSupported);
                    i++;
                }
                return hdmiPortInfo;
            } catch (RemoteException e) {
                HdmiLogger.error("Failed to get port information : ", e);
                return null;
            }
        }

        @Override
        public void nativeSetOption(int flag, boolean enabled) {
            try {
                mHdmiCec.setOption(flag, enabled);
            } catch (RemoteException e) {
                HdmiLogger.error("Failed to set option : ", e);
            }
        }

        @Override
        public void nativeSetLanguage(String language) {
            try {
                mHdmiCec.setLanguage(language);
            } catch (RemoteException e) {
                HdmiLogger.error("Failed to set language : ", e);
            }
        }

        @Override
        public void nativeEnableAudioReturnChannel(int port, boolean flag) {
            try {
                mHdmiCec.enableAudioReturnChannel(port, flag);
            } catch (RemoteException e) {
                HdmiLogger.error("Failed to enable/disable ARC : ", e);
            }
        }

        @Override
        public boolean nativeIsConnected(int port) {
            try {
                return mHdmiCec.isConnected(port);
            } catch (RemoteException e) {
                HdmiLogger.error("Failed to get connection info : ", e);
                return false;
            }
        }

        @Override
        public void serviceDied(long cookie) {
            if (cookie == HDMI_CEC_HAL_DEATH_COOKIE) {
                HdmiLogger.error(TAG, "Service died cokkie : " + cookie + "; reconnecting");
                connectToHal();
            }
        }

        @Override
        public void onValues(int result, short addr) {
            if (result == Result.SUCCESS) {
                synchronized (mLock) {
                    mPhysicalAddress = new Short(addr).intValue();
                }
            }
        }
    }

    final class HdmiCecCallback extends IHdmiCecCallback.Stub {
        @Override
        public void onCecMessage(CecMessage message) throws RemoteException {
            byte[] body = new byte[message.body.size()];
            for (int i = 0; i < message.body.size(); i++) {
                body[i] = message.body.get(i);
            }
            runOnServiceThread(
                    () -> handleIncomingCecCommand(message.initiator, message.destination, body));
        }

        @Override
        public void onHotplugEvent(HotplugEvent event) throws RemoteException {
            runOnServiceThread(() -> handleHotplug(event.portId, event.connected));
        }
    }

    private abstract static class Dumpable {
        protected final long mTime;

        Dumpable() {
            mTime = System.currentTimeMillis();
        }

        abstract void dump(IndentingPrintWriter pw, SimpleDateFormat sdf);
    }

    private static final class MessageHistoryRecord extends Dumpable {
        private final boolean mIsReceived; // true if received message and false if sent message
        private final HdmiCecMessage mMessage;

        MessageHistoryRecord(boolean isReceived, HdmiCecMessage message) {
            super();
            mIsReceived = isReceived;
            mMessage = message;
        }

        @Override
        void dump(final IndentingPrintWriter pw, SimpleDateFormat sdf) {
            pw.print(mIsReceived ? "[R]" : "[S]");
            pw.print(" time=");
            pw.print(sdf.format(new Date(mTime)));
            pw.print(" message=");
            pw.println(mMessage);
        }
    }

    private static final class HotplugHistoryRecord extends Dumpable {
        private final int mPort;
        private final boolean mConnected;

        HotplugHistoryRecord(int port, boolean connected) {
            super();
            mPort = port;
            mConnected = connected;
        }

        @Override
        void dump(final IndentingPrintWriter pw, SimpleDateFormat sdf) {
            pw.print("[H]");
            pw.print(" time=");
            pw.print(sdf.format(new Date(mTime)));
            pw.print(" hotplug port=");
            pw.print(mPort);
            pw.print(" connected=");
            pw.println(mConnected);
        }
    }
}
