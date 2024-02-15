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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.tv.cec.V1_0.HotplugEvent;
import android.hardware.tv.cec.V1_0.IHdmiCec.getPhysicalAddressCallback;
import android.hardware.tv.cec.V1_0.OptionKey;
import android.hardware.tv.cec.V1_0.Result;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.hardware.tv.hdmi.cec.CecMessage;
import android.hardware.tv.hdmi.cec.IHdmiCec;
import android.hardware.tv.hdmi.cec.IHdmiCecCallback;
import android.hardware.tv.hdmi.connection.IHdmiConnection;
import android.hardware.tv.hdmi.connection.IHdmiConnectionCallback;
import android.icu.util.IllformedLocaleException;
import android.icu.util.ULocale;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IHwBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.stats.hdmi.HdmiStatsEnums;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.hdmi.HdmiAnnotations.IoThreadOnly;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;
import com.android.server.hdmi.HdmiControlService.DevicePollingCallback;

import libcore.util.EmptyArray;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Predicate;

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
 *
 * <p>Also manages HDMI HAL methods that are shared between CEC and eARC. To make eARC
 * fully independent of the presence of a CEC HAL, we should split this class into HdmiCecController
 * and HdmiController TODO(b/255751565).
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

    private static final int MAX_DEDICATED_ADDRESS = 11;

    private static final int INITIAL_HDMI_MESSAGE_HISTORY_SIZE = 250;

    private static final int INVALID_PHYSICAL_ADDRESS = 0xFFFF;

    /*
     * The three flags below determine the action when a message is received. If CEC_DISABLED_IGNORE
     * bit is set in ACTION_ON_RECEIVE_MSG, then the message is forwarded irrespective of whether
     * CEC is enabled or disabled. The other flags/bits are also ignored.
     */
    private static final int CEC_DISABLED_IGNORE = 1 << 0;

    /* If CEC_DISABLED_LOG_WARNING bit is set, a warning message is printed if CEC is disabled. */
    private static final int CEC_DISABLED_LOG_WARNING = 1 << 1;

    /* If CEC_DISABLED_DROP_MSG bit is set, the message is dropped if CEC is disabled. */
    private static final int CEC_DISABLED_DROP_MSG = 1 << 2;

    private static final int ACTION_ON_RECEIVE_MSG = CEC_DISABLED_LOG_WARNING;

    /** Cookie for matching the right end point. */
    protected static final int HDMI_CEC_HAL_DEATH_COOKIE = 353;

    // Predicate for whether the given logical address is remote device's one or not.
    private final Predicate<Integer> mRemoteDeviceAddressPredicate = new Predicate<Integer>() {
        @Override
        public boolean test(Integer address) {
            return !mService.getHdmiCecNetwork().isAllocatedLocalDeviceAddress(address);
        }
    };

    // Predicate whether the given logical address is system audio's one or not
    private final Predicate<Integer> mSystemAudioAddressPredicate = new Predicate<Integer>() {
        @Override
        public boolean test(Integer address) {
            return HdmiUtils.isEligibleAddressForDevice(Constants.ADDR_AUDIO_SYSTEM, address);
        }
    };

    // Handler instance to process synchronous I/O (mainly send) message.
    private Handler mIoHandler;

    // Handler instance to process various messages coming from other CEC
    // device or issued by internal state change.
    private Handler mControlHandler;

    private final HdmiControlService mService;

    // Stores recent CEC messages and HDMI Hotplug event history for debugging purpose.
    private ArrayBlockingQueue<Dumpable> mMessageHistory =
            new ArrayBlockingQueue<>(INITIAL_HDMI_MESSAGE_HISTORY_SIZE);

    private final Object mMessageHistoryLock = new Object();

    private final NativeWrapper mNativeWrapperImpl;

    private final HdmiCecAtomWriter mHdmiCecAtomWriter;

    // This variable is used for testing, in order to delay the logical address allocation.
    private long mLogicalAddressAllocationDelay = 0;

    // This variable is used for testing, in order to delay polling devices.
    private long mPollDevicesDelay = 0;

    // Private constructor.  Use HdmiCecController.create().
    private HdmiCecController(
            HdmiControlService service, NativeWrapper nativeWrapper, HdmiCecAtomWriter atomWriter) {
        mService = service;
        mNativeWrapperImpl = nativeWrapper;
        mHdmiCecAtomWriter = atomWriter;
    }

    /**
     * A factory method to get {@link HdmiCecController}. If it fails to initialize
     * inner device or has no device it will return {@code null}.
     *
     * <p>Declared as package-private, accessed by {@link HdmiControlService} only.
     * @param service    {@link HdmiControlService} instance used to create internal handler
     *                   and to pass callback for incoming message or event.
     * @param atomWriter {@link HdmiCecAtomWriter} instance for writing atoms for metrics.
     * @return {@link HdmiCecController} if device is initialized successfully. Otherwise,
     *         returns {@code null}.
     */
    static HdmiCecController create(HdmiControlService service, HdmiCecAtomWriter atomWriter) {
        HdmiCecController controller =
                createWithNativeWrapper(service, new NativeWrapperImplAidl(), atomWriter);
        if (controller != null) {
            return controller;
        }
        HdmiLogger.warning("Unable to use CEC and HDMI Connection AIDL HALs");

        controller = createWithNativeWrapper(service, new NativeWrapperImpl11(), atomWriter);
        if (controller != null) {
            return controller;
        }
        HdmiLogger.warning("Unable to use cec@1.1");
        return createWithNativeWrapper(service, new NativeWrapperImpl(), atomWriter);
    }

    /**
     * A factory method with injection of native methods for testing.
     */
    static HdmiCecController createWithNativeWrapper(
            HdmiControlService service, NativeWrapper nativeWrapper, HdmiCecAtomWriter atomWriter) {
        HdmiCecController controller = new HdmiCecController(service, nativeWrapper, atomWriter);
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

        mIoHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                handleAllocateLogicalAddress(deviceType, preferredAddress, callback);
            }
        }, mLogicalAddressAllocationDelay);
    }

    /**
     * Address allocation will check the following addresses (in order):
     * <ul>
     *     <li>Given preferred logical address (if the address is valid for the given device
     *     type)</li>
     *     <li>All dedicated logical addresses for the given device type</li>
     *     <li>Backup addresses, if valid for the given device type</li>
     * </ul>
     */
    @IoThreadOnly
    private void handleAllocateLogicalAddress(final int deviceType, int preferredAddress,
            final AllocateAddressCallback callback) {
        assertRunOnIoThread();
        List<Integer> logicalAddressesToPoll = new ArrayList<>();
        if (HdmiUtils.isEligibleAddressForDevice(deviceType, preferredAddress)) {
            logicalAddressesToPoll.add(preferredAddress);
        }
        for (int i = 0; i < NUM_LOGICAL_ADDRESS; ++i) {
            if (!logicalAddressesToPoll.contains(i) && HdmiUtils.isEligibleAddressForDevice(
                    deviceType, i) && HdmiUtils.isEligibleAddressForCecVersion(
                    mService.getCecVersion(), i)) {
                logicalAddressesToPoll.add(i);
            }
        }

        int logicalAddress = Constants.ADDR_UNREGISTERED;
        for (Integer logicalAddressToPoll : logicalAddressesToPoll) {
            boolean acked = false;
            for (int j = 0; j < HdmiConfig.ADDRESS_ALLOCATION_RETRY; ++j) {
                if (sendPollMessage(logicalAddressToPoll, logicalAddressToPoll, 1)) {
                    acked = true;
                    break;
                }
            }
            // If sending <Polling Message> failed, it becomes new logical address for the
            // device because no device uses it as logical address of the device.
            if (!acked) {
                logicalAddress = logicalAddressToPoll;
                break;
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
        mNativeWrapperImpl.nativeClearLogicalAddress();
    }

    /**
     * Return the physical address of the device.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} and
     * {@link HdmiCecNetwork} only.
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
     * Return highest CEC version supported by this device.
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
     * Configures the TV panel device wakeup behaviour in standby mode when it receives an OTP
     * (One Touch Play) from a source device.
     *
     * @param enabled If true, the TV device will wake up when OTP is received and if false, the TV
     *     device will not wake up for an OTP.
     */
    @ServiceThreadOnly
    void enableWakeupByOtp(boolean enabled) {
        assertRunOnServiceThread();
        HdmiLogger.debug("enableWakeupByOtp: %b", enabled);
        mNativeWrapperImpl.enableWakeupByOtp(enabled);
    }

    /**
     * Switch to enable or disable CEC on the device.
     *
     * @param enabled If true, the device will have all CEC functionalities and if false, the device
     *     will not perform any CEC functions.
     */
    @ServiceThreadOnly
    void enableCec(boolean enabled) {
        assertRunOnServiceThread();
        HdmiLogger.debug("enableCec: %b", enabled);
        mNativeWrapperImpl.enableCec(enabled);
    }

    /**
     * Configures the module that processes CEC messages - the Android framework or the HAL.
     *
     * @param enabled If true, the Android framework will actively process CEC messages.
     *                If false, only the HAL will process the CEC messages.
     */
    @ServiceThreadOnly
    void enableSystemCecControl(boolean enabled) {
        assertRunOnServiceThread();
        HdmiLogger.debug("enableSystemCecControl: %b", enabled);
        mNativeWrapperImpl.enableSystemCecControl(enabled);
    }

    /**
     * Configures the type of HDP signal that the driver and HAL use for actions other than eARC,
     * such as signaling EDID updates.
     */
    @ServiceThreadOnly
    void setHpdSignalType(@Constants.HpdSignalType int signal, int portId) {
        assertRunOnServiceThread();
        HdmiLogger.debug("setHpdSignalType: portId %b, signal %b", portId, signal);
        mNativeWrapperImpl.nativeSetHpdSignalType(signal, portId);
    }

    /**
     * Gets the type of the HDP signal that the driver and HAL use for actions other than eARC,
     * such as signaling EDID updates.
     */
    @ServiceThreadOnly
    @Constants.HpdSignalType
    int getHpdSignalType(int portId) {
        assertRunOnServiceThread();
        HdmiLogger.debug("getHpdSignalType: portId %b ", portId);
        return mNativeWrapperImpl.nativeGetHpdSignalType(portId);
    }

    /**
     * Informs CEC HAL about the current system language.
     *
     * @param language Three-letter code defined in ISO/FDIS 639-2. Must be lowercase letters.
     */
    @ServiceThreadOnly
    void setLanguage(String language) {
        assertRunOnServiceThread();
        if (!isLanguage(language)) {
            return;
        }
        mNativeWrapperImpl.nativeSetLanguage(language);
    }

    /**
     * This method is used for testing, in order to delay the logical address allocation.
     */
    @VisibleForTesting
    void setLogicalAddressAllocationDelay(long delay) {
        mLogicalAddressAllocationDelay = delay;
    }

    /**
     * This method is used for testing, in order to delay polling devices.
     */
    @VisibleForTesting
    void setPollDevicesDelay(long delay) {
        mPollDevicesDelay = delay;
    }

    /**
     * Returns true if the language code is well-formed.
     */
    @VisibleForTesting static boolean isLanguage(String language) {
        // Handle null and empty string because because ULocale.Builder#setLanguage accepts them.
        if (language == null || language.isEmpty()) {
            return false;
        }

        ULocale.Builder builder = new ULocale.Builder();
        try {
            builder.setLanguage(language);
            return true;
        } catch (IllformedLocaleException e) {
            return false;
        }
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
        mControlHandler.postDelayed(
                () -> runDevicePolling(
                        sourceAddress, pollingCandidates, retryCount, callback, allocated),
                mPollDevicesDelay);
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
        ArrayList<Integer> pollingCandidates = new ArrayList<>();
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
    @VisibleForTesting
    void runOnIoThread(Runnable runnable) {
        mIoHandler.post(new WorkSourceUidPreservingRunnable(runnable));
    }

    @VisibleForTesting
    void runOnServiceThread(Runnable runnable) {
        mControlHandler.post(new WorkSourceUidPreservingRunnable(runnable));
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
        return mService.getHdmiCecNetwork().isAllocatedLocalDeviceAddress(address);
    }

    @ServiceThreadOnly
    @VisibleForTesting
    void onReceiveCommand(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (((ACTION_ON_RECEIVE_MSG & CEC_DISABLED_IGNORE) == 0)
                && !mService.isCecControlEnabled()
                && !HdmiCecMessage.isCecTransportMessage(message.getOpcode())) {
            if ((ACTION_ON_RECEIVE_MSG & CEC_DISABLED_LOG_WARNING) != 0) {
                HdmiLogger.warning("Message " + message + " received when cec disabled");
            }
            if ((ACTION_ON_RECEIVE_MSG & CEC_DISABLED_DROP_MSG) != 0) {
                return;
            }
        }
        if (mService.isAddressAllocated() && !isAcceptableAddress(message.getDestination())) {
            return;
        }
        @Constants.HandleMessageResult int messageState = mService.handleCecCommand(message);
        if (messageState == Constants.NOT_HANDLED) {
            // Message was not handled
            maySendFeatureAbortCommand(message, Constants.ABORT_UNRECOGNIZED_OPCODE);
        } else if (messageState != Constants.HANDLED) {
            // Message handler wants to send a feature abort
            maySendFeatureAbortCommand(message, messageState);
        }
    }

    @ServiceThreadOnly
    void maySendFeatureAbortCommand(HdmiCecMessage message, @Constants.AbortReason int reason) {
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

    /**
     * Returns the calling UID of the original Binder call that triggered this code.
     * If this code was not triggered by a Binder call, returns the UID of this process.
     */
    private int getCallingUid() {
        int workSourceUid = Binder.getCallingWorkSourceUid();
        if (workSourceUid == -1) {
            return Binder.getCallingUid();
        }
        return workSourceUid;
    }

    @ServiceThreadOnly
    void sendCommand(final HdmiCecMessage cecMessage,
            final HdmiControlService.SendMessageCallback callback) {
        assertRunOnServiceThread();
        List<String> sendResults = new ArrayList<>();
        runOnIoThread(new Runnable() {
            @Override
            public void run() {
                HdmiLogger.debug("[S]:" + cecMessage);
                byte[] body = buildBody(cecMessage.getOpcode(), cecMessage.getParams());
                int retransmissionCount = 0;
                int errorCode = SendMessageResult.SUCCESS;
                do {
                    errorCode = mNativeWrapperImpl.nativeSendCecCommand(
                        cecMessage.getSource(), cecMessage.getDestination(), body);
                    switch (errorCode) {
                        case SendMessageResult.SUCCESS: sendResults.add("ACK"); break;
                        case SendMessageResult.FAIL: sendResults.add("FAIL"); break;
                        case SendMessageResult.NACK: sendResults.add("NACK"); break;
                        case SendMessageResult.BUSY: sendResults.add("BUSY"); break;
                    }
                    if (errorCode == SendMessageResult.SUCCESS) {
                        break;
                    }
                } while (retransmissionCount++ < HdmiConfig.RETRANSMISSION_COUNT);

                final int finalError = errorCode;
                if (finalError != SendMessageResult.SUCCESS) {
                    Slog.w(TAG, "Failed to send " + cecMessage + " with errorCode=" + finalError);
                }
                runOnServiceThread(new Runnable() {
                    @Override
                    public void run() {
                        mHdmiCecAtomWriter.messageReported(
                                cecMessage,
                                FrameworkStatsLog.HDMI_CEC_MESSAGE_REPORTED__DIRECTION__OUTGOING,
                                getCallingUid(),
                                finalError
                        );
                        if (callback != null) {
                            callback.onSendCompleted(finalError);
                        }
                    }
                });
            }
        });

        addCecMessageToHistory(false /* isReceived */, cecMessage, sendResults);
    }

    /**
     * Called when incoming CEC message arrived.
     */
    @ServiceThreadOnly
    private void handleIncomingCecCommand(int srcAddress, int dstAddress, byte[] body) {
        assertRunOnServiceThread();

        if (body.length == 0) {
            Slog.e(TAG, "Message with empty body received.");
            return;
        }

        HdmiCecMessage command = HdmiCecMessage.build(srcAddress, dstAddress, body[0],
                Arrays.copyOfRange(body, 1, body.length));

        if (command.getValidationResult() != HdmiCecMessageValidator.OK) {
            Slog.e(TAG, "Invalid message received: " + command);
        }

        HdmiLogger.debug("[R]:" + command);
        addCecMessageToHistory(true /* isReceived */, command, null);

        mHdmiCecAtomWriter.messageReported(command,
                incomingMessageDirection(srcAddress, dstAddress), getCallingUid());

        onReceiveCommand(command);
    }

    /**
     * Computes the direction of an incoming message, as implied by the source and
     * destination addresses. This will usually return INCOMING; if not, it can indicate a bug.
     */
    private int incomingMessageDirection(int srcAddress, int dstAddress) {
        boolean sourceIsLocal = false;
        boolean destinationIsLocal = dstAddress == Constants.ADDR_BROADCAST;
        for (HdmiCecLocalDevice localDevice : mService.getHdmiCecNetwork().getLocalDeviceList()) {
            int logicalAddress = localDevice.getDeviceInfo().getLogicalAddress();
            if (logicalAddress == srcAddress) {
                sourceIsLocal = true;
            }
            if (logicalAddress == dstAddress) {
                destinationIsLocal = true;
            }
        }

        if (!sourceIsLocal && destinationIsLocal) {
            return HdmiStatsEnums.INCOMING;
        } else if (sourceIsLocal && destinationIsLocal) {
            return HdmiStatsEnums.TO_SELF;
        }
        return HdmiStatsEnums.MESSAGE_DIRECTION_OTHER;
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
    private void addCecMessageToHistory(boolean isReceived, HdmiCecMessage message,
            List<String> sendResults) {
        assertRunOnServiceThread();
        addEventToHistory(new MessageHistoryRecord(isReceived, message, sendResults));
    }

    private void addEventToHistory(Dumpable event) {
        synchronized (mMessageHistoryLock) {
            if (!mMessageHistory.offer(event)) {
                mMessageHistory.poll();
                mMessageHistory.offer(event);
            }
        }
    }

    int getMessageHistorySize() {
        synchronized (mMessageHistoryLock) {
            return mMessageHistory.size() + mMessageHistory.remainingCapacity();
        }
    }

    boolean setMessageHistorySize(int newSize) {
        if (newSize < INITIAL_HDMI_MESSAGE_HISTORY_SIZE) {
            return false;
        }
        ArrayBlockingQueue<Dumpable> newMessageHistory = new ArrayBlockingQueue<>(newSize);

        synchronized (mMessageHistoryLock) {
            if (newSize < mMessageHistory.size()) {
                for (int i = 0; i < mMessageHistory.size() - newSize; i++) {
                    mMessageHistory.poll();
                }
            }

            newMessageHistory.addAll(mMessageHistory);
            mMessageHistory = newMessageHistory;
        }
        return true;
    }

    void dump(final IndentingPrintWriter pw) {
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

        void enableWakeupByOtp(boolean enabled);

        void enableCec(boolean enabled);

        void enableSystemCecControl(boolean enabled);

        void nativeSetLanguage(String language);
        void nativeEnableAudioReturnChannel(int port, boolean flag);
        boolean nativeIsConnected(int port);
        void nativeSetHpdSignalType(int signal, int portId);
        int nativeGetHpdSignalType(int portId);
    }

    private static final class NativeWrapperImplAidl
            implements NativeWrapper, IBinder.DeathRecipient {
        private IHdmiCec mHdmiCec;
        private IHdmiConnection mHdmiConnection;
        @Nullable private HdmiCecCallback mCallback;

        private final Object mLock = new Object();

        @Override
        public String nativeInit() {
            return connectToHal() ? mHdmiCec.toString() + " " + mHdmiConnection.toString() : null;
        }

        boolean connectToHal() {
            mHdmiCec =
                    IHdmiCec.Stub.asInterface(
                            ServiceManager.getService(IHdmiCec.DESCRIPTOR + "/default"));
            if (mHdmiCec == null) {
                HdmiLogger.error("Could not initialize HDMI CEC AIDL HAL");
                return false;
            }
            try {
                mHdmiCec.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                HdmiLogger.error("Couldn't link to death : ", e);
            }

            mHdmiConnection =
                    IHdmiConnection.Stub.asInterface(
                            ServiceManager.getService(IHdmiConnection.DESCRIPTOR + "/default"));
            if (mHdmiConnection == null) {
                HdmiLogger.error("Could not initialize HDMI Connection AIDL HAL");
                return false;
            }
            try {
                mHdmiConnection.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                HdmiLogger.error("Couldn't link to death : ", e);
            }
            return true;
        }

        @Override
        public void binderDied() {
            // One of the services died, try to reconnect to both.
            mHdmiCec.asBinder().unlinkToDeath(this, 0);
            mHdmiConnection.asBinder().unlinkToDeath(this, 0);
            HdmiLogger.error("HDMI Connection or CEC service died, reconnecting");
            connectToHal();
            // Reconnect the callback
            if (mCallback != null) {
                setCallback(mCallback);
            }
        }

        @Override
        public void setCallback(HdmiCecCallback callback) {
            mCallback = callback;
            try {
                // Create an AIDL callback that can callback onCecMessage
                mHdmiCec.setCallback(new HdmiCecCallbackAidl(callback));
            } catch (RemoteException e) {
                HdmiLogger.error("Couldn't initialise tv.cec callback : ", e);
            }
            try {
                // Create an AIDL callback that can callback onHotplugEvent
                mHdmiConnection.setCallback(new HdmiConnectionCallbackAidl(callback));
            } catch (RemoteException e) {
                HdmiLogger.error("Couldn't initialise tv.hdmi callback : ", e);
            }
        }

        @Override
        public int nativeSendCecCommand(int srcAddress, int dstAddress, byte[] body) {
            CecMessage message = new CecMessage();
            message.initiator = (byte) (srcAddress & 0xF);
            message.destination = (byte) (dstAddress & 0xF);
            message.body = body;
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
                return mHdmiCec.addLogicalAddress((byte) logicalAddress);
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
                return mHdmiCec.getPhysicalAddress();
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
        public void enableWakeupByOtp(boolean enabled) {
            try {
                mHdmiCec.enableWakeupByOtp(enabled);
            } catch (RemoteException e) {
                HdmiLogger.error("Failed call to enableWakeupByOtp : ", e);
            }
        }

        @Override
        public void enableCec(boolean enabled) {
            try {
                mHdmiCec.enableCec(enabled);
            } catch (RemoteException e) {
                HdmiLogger.error("Failed call to enableCec : ", e);
            }
        }

        @Override
        public void enableSystemCecControl(boolean enabled) {
            try {
                mHdmiCec.enableSystemCecControl(enabled);
            } catch (RemoteException e) {
                HdmiLogger.error("Failed call to enableSystemCecControl : ", e);
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
        public HdmiPortInfo[] nativeGetPortInfos() {
            try {
                android.hardware.tv.hdmi.connection.HdmiPortInfo[] hdmiPortInfos =
                        mHdmiConnection.getPortInfo();
                HdmiPortInfo[] hdmiPortInfo = new HdmiPortInfo[hdmiPortInfos.length];
                int i = 0;
                for (android.hardware.tv.hdmi.connection.HdmiPortInfo portInfo : hdmiPortInfos) {
                    hdmiPortInfo[i] = new HdmiPortInfo.Builder(
                                    portInfo.portId,
                                    portInfo.type,
                                    portInfo.physicalAddress)
                                    .setCecSupported(portInfo.cecSupported)
                                    .setMhlSupported(false)
                                    .setArcSupported(portInfo.arcSupported)
                                    .setEarcSupported(portInfo.eArcSupported)
                                    .build();
                    i++;
                }
                return hdmiPortInfo;
            } catch (RemoteException e) {
                HdmiLogger.error("Failed to get port information : ", e);
                return null;
            }
        }

        @Override
        public boolean nativeIsConnected(int port) {
            try {
                return mHdmiConnection.isConnected(port);
            } catch (RemoteException e) {
                HdmiLogger.error("Failed to get connection info : ", e);
                return false;
            }
        }

        @Override
        public void nativeSetHpdSignalType(int signal, int portId) {
            try {
                mHdmiConnection.setHpdSignal((byte) signal, portId);
            } catch (ServiceSpecificException sse) {
                HdmiLogger.error(
                        "Could not set HPD signal type for portId " + portId + " to " + signal
                                + ". Error: ", sse.errorCode);
            } catch (RemoteException e) {
                HdmiLogger.error(
                        "Could not set HPD signal type for portId " + portId + " to " + signal
                                + ". Exception: ", e);
            }
        }

        @Override
        public int nativeGetHpdSignalType(int portId) {
            try {
                return mHdmiConnection.getHpdSignal(portId);
            } catch (RemoteException e) {
                HdmiLogger.error(
                        "Could not get HPD signal type for portId " + portId + ". Exception: ", e);
                return Constants.HDMI_HPD_TYPE_PHYSICAL;
            }
        }
    }

    private static final class NativeWrapperImpl11 implements NativeWrapper,
            IHwBinder.DeathRecipient, getPhysicalAddressCallback {
        private android.hardware.tv.cec.V1_1.IHdmiCec mHdmiCec;
        @Nullable private HdmiCecCallback mCallback;

        private final Object mLock = new Object();
        private int mPhysicalAddress = INVALID_PHYSICAL_ADDRESS;

        @Override
        public String nativeInit() {
            return (connectToHal() ? mHdmiCec.toString() : null);
        }

        boolean connectToHal() {
            try {
                mHdmiCec = android.hardware.tv.cec.V1_1.IHdmiCec.getService(true);
                try {
                    mHdmiCec.linkToDeath(this, HDMI_CEC_HAL_DEATH_COOKIE);
                } catch (RemoteException e) {
                    HdmiLogger.error("Couldn't link to death : ", e);
                }
            } catch (RemoteException | NoSuchElementException e) {
                HdmiLogger.error("Couldn't connect to cec@1.1", e);
                return false;
            }
            return true;
        }

        @Override
        public void onValues(int result, short addr) {
            if (result == Result.SUCCESS) {
                synchronized (mLock) {
                    mPhysicalAddress = new Short(addr).intValue();
                }
            }
        }

        @Override
        public void serviceDied(long cookie) {
            if (cookie == HDMI_CEC_HAL_DEATH_COOKIE) {
                HdmiLogger.error("Service died cookie : " + cookie + "; reconnecting");
                connectToHal();
                // Reconnect the callback
                if (mCallback != null) {
                    setCallback(mCallback);
                }
            }
        }

        @Override
        public void setCallback(HdmiCecCallback callback) {
            mCallback = callback;
            try {
                mHdmiCec.setCallback_1_1(new HdmiCecCallback11(callback));
            } catch (RemoteException e) {
                HdmiLogger.error("Couldn't initialise tv.cec callback : ", e);
            }
        }

        @Override
        public int nativeSendCecCommand(int srcAddress, int dstAddress, byte[] body) {
            android.hardware.tv.cec.V1_1.CecMessage message =
                    new android.hardware.tv.cec.V1_1.CecMessage();
            message.initiator = srcAddress;
            message.destination = dstAddress;
            message.body = new ArrayList<>(body.length);
            for (byte b : body) {
                message.body.add(b);
            }
            try {
                return mHdmiCec.sendMessage_1_1(message);
            } catch (RemoteException e) {
                HdmiLogger.error("Failed to send CEC message : ", e);
                return SendMessageResult.FAIL;
            }
        }

        @Override
        public int nativeAddLogicalAddress(int logicalAddress) {
            try {
                return mHdmiCec.addLogicalAddress_1_1(logicalAddress);
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
                    hdmiPortInfo[i] = new HdmiPortInfo.Builder(
                            portInfo.portId,
                            portInfo.type,
                            portInfo.physicalAddress)
                            .setCecSupported(portInfo.cecSupported)
                            .setMhlSupported(false)
                            .setArcSupported(portInfo.arcSupported)
                            .setEarcSupported(false)
                            .build();
                    i++;
                }
                return hdmiPortInfo;
            } catch (RemoteException e) {
                HdmiLogger.error("Failed to get port information : ", e);
                return null;
            }
        }

        private void nativeSetOption(int flag, boolean enabled) {
            try {
                mHdmiCec.setOption(flag, enabled);
            } catch (RemoteException e) {
                HdmiLogger.error("Failed to set option : ", e);
            }
        }

        @Override
        public void enableWakeupByOtp(boolean enabled) {
            nativeSetOption(OptionKey.WAKEUP, enabled);
        }

        @Override
        public void enableCec(boolean enabled) {
            nativeSetOption(OptionKey.ENABLE_CEC, enabled);
        }

        @Override
        public void enableSystemCecControl(boolean enabled) {
            nativeSetOption(OptionKey.SYSTEM_CEC_CONTROL, enabled);
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
        public void nativeSetHpdSignalType(int signal, int portId) {
            HdmiLogger.error(
                    "Failed to set HPD signal type: not supported by HAL.");
        }

        @Override
        public int nativeGetHpdSignalType(int portId) {
            HdmiLogger.error(
                    "Failed to get HPD signal type: not supported by HAL.");
            return Constants.HDMI_HPD_TYPE_PHYSICAL;
        }
    }

    private static final class NativeWrapperImpl implements NativeWrapper,
            IHwBinder.DeathRecipient, getPhysicalAddressCallback {
        private android.hardware.tv.cec.V1_0.IHdmiCec mHdmiCec;
        @Nullable private HdmiCecCallback mCallback;

        private final Object mLock = new Object();
        private int mPhysicalAddress = INVALID_PHYSICAL_ADDRESS;

        @Override
        public String nativeInit() {
            return (connectToHal() ? mHdmiCec.toString() : null);
        }

        boolean connectToHal() {
            try {
                mHdmiCec = android.hardware.tv.cec.V1_0.IHdmiCec.getService(true);
                try {
                    mHdmiCec.linkToDeath(this, HDMI_CEC_HAL_DEATH_COOKIE);
                } catch (RemoteException e) {
                    HdmiLogger.error("Couldn't link to death : ", e);
                }
            } catch (RemoteException | NoSuchElementException e) {
                HdmiLogger.error("Couldn't connect to cec@1.0", e);
                return false;
            }
            return true;
        }

        @Override
        public void setCallback(@NonNull HdmiCecCallback callback) {
            mCallback = callback;
            try {
                mHdmiCec.setCallback(new HdmiCecCallback10(callback));
            } catch (RemoteException e) {
                HdmiLogger.error("Couldn't initialise tv.cec callback : ", e);
            }
        }

        @Override
        public int nativeSendCecCommand(int srcAddress, int dstAddress, byte[] body) {
            android.hardware.tv.cec.V1_0.CecMessage message =
                    new android.hardware.tv.cec.V1_0.CecMessage();
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
                    hdmiPortInfo[i] = new HdmiPortInfo.Builder(
                            portInfo.portId,
                            portInfo.type,
                            portInfo.physicalAddress)
                            .setCecSupported(portInfo.cecSupported)
                            .setMhlSupported(false)
                            .setArcSupported(portInfo.arcSupported)
                            .setEarcSupported(false)
                            .build();
                    i++;
                }
                return hdmiPortInfo;
            } catch (RemoteException e) {
                HdmiLogger.error("Failed to get port information : ", e);
                return null;
            }
        }

        private void nativeSetOption(int flag, boolean enabled) {
            try {
                mHdmiCec.setOption(flag, enabled);
            } catch (RemoteException e) {
                HdmiLogger.error("Failed to set option : ", e);
            }
        }

        @Override
        public void enableWakeupByOtp(boolean enabled) {
            nativeSetOption(OptionKey.WAKEUP, enabled);
        }

        @Override
        public void enableCec(boolean enabled) {
            nativeSetOption(OptionKey.ENABLE_CEC, enabled);
        }

        @Override
        public void enableSystemCecControl(boolean enabled) {
            nativeSetOption(OptionKey.SYSTEM_CEC_CONTROL, enabled);
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
        public void nativeSetHpdSignalType(int signal, int portId) {
            HdmiLogger.error(
                    "Failed to set HPD signal type: not supported by HAL.");
        }

        @Override
        public int nativeGetHpdSignalType(int portId) {
            HdmiLogger.error(
                    "Failed to get HPD signal type: not supported by HAL.");
            return Constants.HDMI_HPD_TYPE_PHYSICAL;
        }

        @Override
        public void serviceDied(long cookie) {
            if (cookie == HDMI_CEC_HAL_DEATH_COOKIE) {
                HdmiLogger.error("Service died cookie : " + cookie + "; reconnecting");
                connectToHal();
                // Reconnect the callback
                if (mCallback != null) {
                    setCallback(mCallback);
                }
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

    final class HdmiCecCallback {
        @VisibleForTesting
        public void onCecMessage(int initiator, int destination, byte[] body) {
            runOnServiceThread(
                    () -> handleIncomingCecCommand(initiator, destination, body));
        }

        @VisibleForTesting
        public void onHotplugEvent(int portId, boolean connected) {
            runOnServiceThread(() -> handleHotplug(portId, connected));
        }
    }

    private static final class HdmiCecCallback10
            extends android.hardware.tv.cec.V1_0.IHdmiCecCallback.Stub {
        private final HdmiCecCallback mHdmiCecCallback;

        HdmiCecCallback10(HdmiCecCallback hdmiCecCallback) {
            mHdmiCecCallback = hdmiCecCallback;
        }

        @Override
        public void onCecMessage(android.hardware.tv.cec.V1_0.CecMessage message)
                throws RemoteException {
            byte[] body = new byte[message.body.size()];
            for (int i = 0; i < message.body.size(); i++) {
                body[i] = message.body.get(i);
            }
            mHdmiCecCallback.onCecMessage(message.initiator, message.destination, body);
        }

        @Override
        public void onHotplugEvent(HotplugEvent event) throws RemoteException {
            mHdmiCecCallback.onHotplugEvent(event.portId, event.connected);
        }
    }

    private static final class HdmiCecCallback11
            extends android.hardware.tv.cec.V1_1.IHdmiCecCallback.Stub {
        private final HdmiCecCallback mHdmiCecCallback;

        HdmiCecCallback11(HdmiCecCallback hdmiCecCallback) {
            mHdmiCecCallback = hdmiCecCallback;
        }

        @Override
        public void onCecMessage_1_1(android.hardware.tv.cec.V1_1.CecMessage message)
                throws RemoteException {
            byte[] body = new byte[message.body.size()];
            for (int i = 0; i < message.body.size(); i++) {
                body[i] = message.body.get(i);
            }
            mHdmiCecCallback.onCecMessage(message.initiator, message.destination, body);
        }

        @Override
        public void onCecMessage(android.hardware.tv.cec.V1_0.CecMessage message)
                throws RemoteException {
            byte[] body = new byte[message.body.size()];
            for (int i = 0; i < message.body.size(); i++) {
                body[i] = message.body.get(i);
            }
            mHdmiCecCallback.onCecMessage(message.initiator, message.destination, body);
        }

        @Override
        public void onHotplugEvent(HotplugEvent event) throws RemoteException {
            mHdmiCecCallback.onHotplugEvent(event.portId, event.connected);
        }
    }

    private static final class HdmiCecCallbackAidl extends IHdmiCecCallback.Stub {
        private final HdmiCecCallback mHdmiCecCallback;

        HdmiCecCallbackAidl(HdmiCecCallback hdmiCecCallback) {
            mHdmiCecCallback = hdmiCecCallback;
        }

        @Override
        public void onCecMessage(CecMessage message) throws RemoteException {
            mHdmiCecCallback.onCecMessage(message.initiator, message.destination, message.body);
        }

        @Override
        public synchronized String getInterfaceHash() throws android.os.RemoteException {
            return IHdmiCecCallback.Stub.HASH;
        }

        @Override
        public int getInterfaceVersion() throws android.os.RemoteException {
            return IHdmiCecCallback.Stub.VERSION;
        }
    }

    private static final class HdmiConnectionCallbackAidl extends IHdmiConnectionCallback.Stub {
        private final HdmiCecCallback mHdmiCecCallback;

        HdmiConnectionCallbackAidl(HdmiCecCallback hdmiCecCallback) {
            mHdmiCecCallback = hdmiCecCallback;
        }

        @Override
        public void onHotplugEvent(boolean connected, int portId) throws RemoteException {
            mHdmiCecCallback.onHotplugEvent(portId, connected);
        }

        @Override
        public synchronized String getInterfaceHash() throws android.os.RemoteException {
            return IHdmiConnectionCallback.Stub.HASH;
        }

        @Override
        public int getInterfaceVersion() throws android.os.RemoteException {
            return IHdmiConnectionCallback.Stub.VERSION;
        }
    }

    public abstract static class Dumpable {
        protected final long mTime;

        Dumpable() {
            mTime = System.currentTimeMillis();
        }

        abstract void dump(IndentingPrintWriter pw, SimpleDateFormat sdf);
    }

    private static final class MessageHistoryRecord extends Dumpable {
        private final boolean mIsReceived; // true if received message and false if sent message
        private final HdmiCecMessage mMessage;
        private final List<String> mSendResults;

        MessageHistoryRecord(boolean isReceived, HdmiCecMessage message, List<String> sendResults) {
            super();
            mIsReceived = isReceived;
            mMessage = message;
            mSendResults = sendResults;
        }

        @Override
        void dump(final IndentingPrintWriter pw, SimpleDateFormat sdf) {
            pw.print(mIsReceived ? "[R]" : "[S]");
            pw.print(" time=");
            pw.print(sdf.format(new Date(mTime)));
            pw.print(" message=");
            pw.print(mMessage);

            StringBuilder results = new StringBuilder();
            if (!mIsReceived && mSendResults != null) {
                results.append(" (");
                results.append(String.join(", ", mSendResults));
                results.append(")");
            }

            pw.println(results);
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
