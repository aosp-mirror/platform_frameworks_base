/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;

import android.annotation.Nullable;
import android.hardware.hdmi.DeviceFeatures;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;

import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Holds information about the current state of the HDMI CEC network. It is the sole source of
 * truth for device information in the CEC network.
 *
 * This information includes:
 * - All local devices
 * - All HDMI ports, their capabilities and status
 * - All devices connected to the CEC bus
 *
 * This class receives all incoming CEC messages and passively listens to device updates to fill
 * out the above information.
 * This class should not take any active action in sending CEC messages.
 *
 * Note that the information cached in this class is not guaranteed to be up-to-date, especially OSD
 * names, power states can be outdated. For local devices, more up-to-date information can be
 * accessed through {@link HdmiCecLocalDevice#getDeviceInfo()}.
 */
@VisibleForTesting
public class HdmiCecNetwork {
    private static final String TAG = "HdmiCecNetwork";

    protected final Object mLock;
    private final HdmiControlService mHdmiControlService;
    private final HdmiCecController mHdmiCecController;
    private final HdmiMhlControllerStub mHdmiMhlController;
    private final Handler mHandler;
    // Stores the local CEC devices in the system. Device type is used for key.
    private final SparseArray<HdmiCecLocalDevice> mLocalDevices = new SparseArray<>();

    // Map-like container of all cec devices including local ones.
    // device id is used as key of container.
    // This is not thread-safe. For external purpose use mSafeDeviceInfos.
    private final SparseArray<HdmiDeviceInfo> mDeviceInfos = new SparseArray<>();
    // Set of physical addresses of CEC switches on the CEC bus. Managed independently from
    // other CEC devices since they might not have logical address.
    private final ArraySet<Integer> mCecSwitches = new ArraySet<>();
    // Copy of mDeviceInfos to guarantee thread-safety.
    @GuardedBy("mLock")
    private List<HdmiDeviceInfo> mSafeAllDeviceInfos = Collections.emptyList();
    // All external cec input(source) devices. Does not include system audio device.
    @GuardedBy("mLock")
    private List<HdmiDeviceInfo> mSafeExternalInputs = Collections.emptyList();
    // HDMI port information. Stored in the unmodifiable list to keep the static information
    // from being modified.
    @GuardedBy("mLock")
    private List<HdmiPortInfo> mPortInfo = Collections.emptyList();

    // Map from path(physical address) to port ID.
    private UnmodifiableSparseIntArray mPortIdMap;

    // Map from port ID to HdmiPortInfo.
    private UnmodifiableSparseArray<HdmiPortInfo> mPortInfoMap;

    // Map from port ID to HdmiDeviceInfo.
    private UnmodifiableSparseArray<HdmiDeviceInfo> mPortDeviceMap;

    // Cached physical address.
    private int mPhysicalAddress = Constants.INVALID_PHYSICAL_ADDRESS;

    HdmiCecNetwork(HdmiControlService hdmiControlService,
            HdmiCecController hdmiCecController,
            HdmiMhlControllerStub hdmiMhlController) {
        mHdmiControlService = hdmiControlService;
        mHdmiCecController = hdmiCecController;
        mHdmiMhlController = hdmiMhlController;
        mHandler = new Handler(mHdmiControlService.getServiceLooper());
        mLock = mHdmiControlService.getServiceLock();
    }

    private static boolean isConnectedToCecSwitch(int path, Collection<Integer> switches) {
        for (int switchPath : switches) {
            if (isParentPath(switchPath, path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isParentPath(int parentPath, int childPath) {
        // (A000, AB00) (AB00, ABC0), (ABC0, ABCD)
        // If child's last non-zero nibble is removed, the result equals to the parent.
        for (int i = 0; i <= 12; i += 4) {
            int nibble = (childPath >> i) & 0xF;
            if (nibble != 0) {
                int parentNibble = (parentPath >> i) & 0xF;
                return parentNibble == 0 && (childPath >> i + 4) == (parentPath >> i + 4);
            }
        }
        return false;
    }

    public void addLocalDevice(int deviceType, HdmiCecLocalDevice device) {
        mLocalDevices.put(deviceType, device);
    }

    /**
     * Return the locally hosted logical device of a given type.
     *
     * @param deviceType logical device type
     * @return {@link HdmiCecLocalDevice} instance if the instance of the type is available;
     * otherwise null.
     */
    HdmiCecLocalDevice getLocalDevice(int deviceType) {
        return mLocalDevices.get(deviceType);
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

    @ServiceThreadOnly
    boolean isAllocatedLocalDeviceAddress(int address) {
        assertRunOnServiceThread();
        for (int i = 0; i < mLocalDevices.size(); ++i) {
            if (mLocalDevices.valueAt(i).isAddressOf(address)) {
                return true;
            }
        }
        return false;
    }

    @ServiceThreadOnly
    void clearLocalDevices() {
        assertRunOnServiceThread();
        mLocalDevices.clear();
    }

    /**
     * Get the device info of a local device or a device in the CEC network by a device id.
     * @param id id of the device to get
     * @return the device with the given id, or {@code null}
     */
    @Nullable
    public HdmiDeviceInfo getDeviceInfo(int id) {
        return mDeviceInfos.get(id);
    }

    /**
     * Add a new {@link HdmiDeviceInfo}. It returns old device info which has the same
     * logical address as new device info's.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @param deviceInfo a new {@link HdmiDeviceInfo} to be added.
     * @return {@code null} if it is new device. Otherwise, returns old {@HdmiDeviceInfo}
     * that has the same logical address as new one has.
     */
    @ServiceThreadOnly
    private HdmiDeviceInfo addDeviceInfo(HdmiDeviceInfo deviceInfo) {
        assertRunOnServiceThread();
        HdmiDeviceInfo oldDeviceInfo = getCecDeviceInfo(deviceInfo.getLogicalAddress());
        mHdmiControlService.checkLogicalAddressConflictAndReallocate(
                deviceInfo.getLogicalAddress(), deviceInfo.getPhysicalAddress());
        if (oldDeviceInfo != null) {
            removeDeviceInfo(deviceInfo.getId());
        }
        mDeviceInfos.append(deviceInfo.getId(), deviceInfo);
        updateSafeDeviceInfoList();
        return oldDeviceInfo;
    }

    /**
     * Remove a device info corresponding to the given {@code logicalAddress}.
     * It returns removed {@link HdmiDeviceInfo} if exists.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @param id id of device to be removed
     * @return removed {@link HdmiDeviceInfo} it exists. Otherwise, returns {@code null}
     */
    @ServiceThreadOnly
    private HdmiDeviceInfo removeDeviceInfo(int id) {
        assertRunOnServiceThread();
        HdmiDeviceInfo deviceInfo = mDeviceInfos.get(id);
        if (deviceInfo != null) {
            mDeviceInfos.remove(id);
        }
        updateSafeDeviceInfoList();
        return deviceInfo;
    }

    /**
     * Return a {@link HdmiDeviceInfo} corresponding to the given {@code logicalAddress}.
     *
     * This is not thread-safe. For thread safety, call {@link #getSafeCecDeviceInfo(int)}.
     *
     * @param logicalAddress logical address of the device to be retrieved
     * @return {@link HdmiDeviceInfo} matched with the given {@code logicalAddress}.
     * Returns null if no logical address matched
     */
    @ServiceThreadOnly
    @Nullable
    HdmiDeviceInfo getCecDeviceInfo(int logicalAddress) {
        assertRunOnServiceThread();
        return mDeviceInfos.get(HdmiDeviceInfo.idForCecDevice(logicalAddress));
    }

    /**
     * Called when a device is newly added or a new device is detected or
     * existing device is updated.
     *
     * @param info device info of a new device.
     */
    @ServiceThreadOnly
    final void addCecDevice(HdmiDeviceInfo info) {
        assertRunOnServiceThread();
        HdmiDeviceInfo old = addDeviceInfo(info);
        if (isLocalDeviceAddress(info.getLogicalAddress())) {
            // The addition of a local device should not notify listeners
            return;
        }
        mHdmiControlService.checkAndUpdateAbsoluteVolumeBehavior();
        if (info.getPhysicalAddress() == HdmiDeviceInfo.PATH_INVALID) {
            // Don't notify listeners of devices that haven't reported their physical address yet
            return;
        } else if (old == null  || old.getPhysicalAddress() == HdmiDeviceInfo.PATH_INVALID) {
            invokeDeviceEventListener(info,
                    HdmiControlManager.DEVICE_EVENT_ADD_DEVICE);
        } else if (!old.equals(info)) {
            invokeDeviceEventListener(old,
                    HdmiControlManager.DEVICE_EVENT_REMOVE_DEVICE);
            invokeDeviceEventListener(info,
                    HdmiControlManager.DEVICE_EVENT_ADD_DEVICE);
        }
    }

    private void invokeDeviceEventListener(HdmiDeviceInfo info, int event) {
        if (!hideDevicesBehindLegacySwitch(info)) {
            mHdmiControlService.invokeDeviceEventListeners(info, event);
        }
    }

    /**
     * Called when a device is updated.
     *
     * @param info device info of the updating device.
     */
    @ServiceThreadOnly
    final void updateCecDevice(HdmiDeviceInfo info) {
        assertRunOnServiceThread();
        HdmiDeviceInfo old = addDeviceInfo(info);

        if (info.getPhysicalAddress() == HdmiDeviceInfo.PATH_INVALID) {
            // Don't notify listeners of devices that haven't reported their physical address yet
            return;
        } else if (old == null  || old.getPhysicalAddress() == HdmiDeviceInfo.PATH_INVALID) {
            invokeDeviceEventListener(info,
                    HdmiControlManager.DEVICE_EVENT_ADD_DEVICE);
        } else if (!old.equals(info)) {
            invokeDeviceEventListener(info,
                    HdmiControlManager.DEVICE_EVENT_UPDATE_DEVICE);
        }
    }

    @ServiceThreadOnly
    private void updateSafeDeviceInfoList() {
        assertRunOnServiceThread();
        List<HdmiDeviceInfo> copiedDevices = HdmiUtils.sparseArrayToList(mDeviceInfos);
        List<HdmiDeviceInfo> externalInputs = getInputDevices();
        mSafeAllDeviceInfos = copiedDevices;
        mSafeExternalInputs = externalInputs;
    }

    /**
     * Return a list of all {@link HdmiDeviceInfo}.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     * This is not thread-safe. For thread safety, call {@link #getSafeExternalInputsLocked} which
     * does not include local device.
     */
    @ServiceThreadOnly
    List<HdmiDeviceInfo> getDeviceInfoList(boolean includeLocalDevice) {
        assertRunOnServiceThread();
        if (includeLocalDevice) {
            return HdmiUtils.sparseArrayToList(mDeviceInfos);
        } else {
            ArrayList<HdmiDeviceInfo> infoList = new ArrayList<>();
            for (int i = 0; i < mDeviceInfos.size(); ++i) {
                HdmiDeviceInfo info = mDeviceInfos.valueAt(i);
                if (!isLocalDeviceAddress(info.getLogicalAddress())) {
                    infoList.add(info);
                }
            }
            return infoList;
        }
    }

    /**
     * Return external input devices.
     */
    @GuardedBy("mLock")
    List<HdmiDeviceInfo> getSafeExternalInputsLocked() {
        return mSafeExternalInputs;
    }

    /**
     * Return a list of external cec input (source) devices.
     *
     * <p>Note that this effectively excludes non-source devices like system audio,
     * secondary TV.
     */
    private List<HdmiDeviceInfo> getInputDevices() {
        ArrayList<HdmiDeviceInfo> infoList = new ArrayList<>();
        for (int i = 0; i < mDeviceInfos.size(); ++i) {
            HdmiDeviceInfo info = mDeviceInfos.valueAt(i);
            if (isLocalDeviceAddress(info.getLogicalAddress())) {
                continue;
            }
            if (info.isSourceType() && !hideDevicesBehindLegacySwitch(info)) {
                infoList.add(info);
            }
        }
        return infoList;
    }

    // Check if we are hiding CEC devices connected to a legacy (non-CEC) switch.
    // This only applies to TV devices.
    // Returns true if the policy is set to true, and the device to check does not have
    // a parent CEC device (which should be the CEC-enabled switch) in the list.
    // Devices with an invalid physical address are assumed to NOT be connected to a legacy switch.
    private boolean hideDevicesBehindLegacySwitch(HdmiDeviceInfo info) {
        return isLocalDeviceAddress(Constants.ADDR_TV)
                && HdmiConfig.HIDE_DEVICES_BEHIND_LEGACY_SWITCH
                && !isConnectedToCecSwitch(info.getPhysicalAddress(), getCecSwitches())
                && info.getPhysicalAddress() != HdmiDeviceInfo.PATH_INVALID;
    }

    /**
     * Called when a device is removed or removal of device is detected.
     *
     * @param address a logical address of a device to be removed
     */
    @ServiceThreadOnly
    final void removeCecDevice(HdmiCecLocalDevice localDevice, int address) {
        assertRunOnServiceThread();
        HdmiDeviceInfo info = removeDeviceInfo(HdmiDeviceInfo.idForCecDevice(address));
        mHdmiControlService.checkAndUpdateAbsoluteVolumeBehavior();
        localDevice.mCecMessageCache.flushMessagesFrom(address);
        if (info.getPhysicalAddress() == HdmiDeviceInfo.PATH_INVALID) {
            // Don't notify listeners of devices that haven't reported their physical address yet
            return;
        }
        invokeDeviceEventListener(info,
                HdmiControlManager.DEVICE_EVENT_REMOVE_DEVICE);
    }

    public void updateDevicePowerStatus(int logicalAddress, int newPowerStatus) {
        HdmiDeviceInfo info = getCecDeviceInfo(logicalAddress);
        if (info == null) {
            Slog.w(TAG, "Can not update power status of non-existing device:" + logicalAddress);
            return;
        }

        if (info.getDevicePowerStatus() == newPowerStatus) {
            return;
        }

        updateCecDevice(info.toBuilder().setDevicePowerStatus(newPowerStatus).build());
    }

    /**
     * Whether a device of the specified physical address is connected to ARC enabled port.
     */
    boolean isConnectedToArcPort(int physicalAddress) {
        int portId = physicalAddressToPortId(physicalAddress);
        if (portId != Constants.INVALID_PORT_ID && portId != Constants.CEC_SWITCH_HOME) {
            return mPortInfoMap.get(portId).isArcSupported();
        }
        return false;
    }


    // Initialize HDMI port information. Combine the information from CEC and MHL HAL and
    // keep them in one place.
    @ServiceThreadOnly
    @VisibleForTesting
    public void initPortInfo() {
        assertRunOnServiceThread();
        HdmiPortInfo[] cecPortInfo = null;
        // CEC HAL provides majority of the info while MHL does only MHL support flag for
        // each port. Return empty array if CEC HAL didn't provide the info.
        if (mHdmiCecController != null) {
            cecPortInfo = mHdmiCecController.getPortInfos();
            // Invalid cached physical address.
            mPhysicalAddress = Constants.INVALID_PHYSICAL_ADDRESS;
        }
        if (cecPortInfo == null) {
            return;
        }

        SparseArray<HdmiPortInfo> portInfoMap = new SparseArray<>();
        SparseIntArray portIdMap = new SparseIntArray();
        SparseArray<HdmiDeviceInfo> portDeviceMap = new SparseArray<>();
        for (HdmiPortInfo info : cecPortInfo) {
            portIdMap.put(info.getAddress(), info.getId());
            portInfoMap.put(info.getId(), info);
            portDeviceMap.put(info.getId(),
                    HdmiDeviceInfo.hardwarePort(info.getAddress(), info.getId()));
        }
        mPortIdMap = new UnmodifiableSparseIntArray(portIdMap);
        mPortInfoMap = new UnmodifiableSparseArray<>(portInfoMap);
        mPortDeviceMap = new UnmodifiableSparseArray<>(portDeviceMap);

        if (mHdmiMhlController == null) {
            return;
        }
        HdmiPortInfo[] mhlPortInfo = mHdmiMhlController.getPortInfos();
        ArraySet<Integer> mhlSupportedPorts = new ArraySet<Integer>(mhlPortInfo.length);
        for (HdmiPortInfo info : mhlPortInfo) {
            if (info.isMhlSupported()) {
                mhlSupportedPorts.add(info.getId());
            }
        }

        // Build HDMI port info list with CEC port info plus MHL supported flag. We can just use
        // cec port info if we do not have have port that supports MHL.
        if (mhlSupportedPorts.isEmpty()) {
            setPortInfo(Collections.unmodifiableList(Arrays.asList(cecPortInfo)));
            return;
        }
        ArrayList<HdmiPortInfo> result = new ArrayList<>(cecPortInfo.length);
        for (HdmiPortInfo info : cecPortInfo) {
            if (mhlSupportedPorts.contains(info.getId())) {
                result.add(new HdmiPortInfo.Builder(info.getId(), info.getType(), info.getAddress())
                        .setCecSupported(info.isCecSupported())
                        .setMhlSupported(true)
                        .setArcSupported(info.isArcSupported())
                        .setEarcSupported(info.isEarcSupported())
                        .build());
            } else {
                result.add(info);
            }
        }
        setPortInfo(Collections.unmodifiableList(result));
    }

    HdmiDeviceInfo getDeviceForPortId(int portId) {
        return mPortDeviceMap.get(portId, HdmiDeviceInfo.INACTIVE_DEVICE);
    }

    /**
     * Whether a device of the specified physical address and logical address exists
     * in a device info list. However, both are minimal condition and it could
     * be different device from the original one.
     *
     * @param logicalAddress  logical address of a device to be searched
     * @param physicalAddress physical address of a device to be searched
     * @return true if exist; otherwise false
     */
    @ServiceThreadOnly
    boolean isInDeviceList(int logicalAddress, int physicalAddress) {
        assertRunOnServiceThread();
        HdmiDeviceInfo device = getCecDeviceInfo(logicalAddress);
        if (device == null) {
            return false;
        }
        return device.getPhysicalAddress() == physicalAddress;
    }

    /**
     * Attempts to deduce the device type of a device given its logical address.
     * If multiple types are possible, returns {@link HdmiDeviceInfo#DEVICE_RESERVED}.
     */
    private static int logicalAddressToDeviceType(int logicalAddress) {
        switch (logicalAddress) {
            case Constants.ADDR_TV:
                return HdmiDeviceInfo.DEVICE_TV;
            case Constants.ADDR_RECORDER_1:
            case Constants.ADDR_RECORDER_2:
            case Constants.ADDR_RECORDER_3:
                return HdmiDeviceInfo.DEVICE_RECORDER;
            case Constants.ADDR_TUNER_1:
            case Constants.ADDR_TUNER_2:
            case Constants.ADDR_TUNER_3:
            case Constants.ADDR_TUNER_4:
                return HdmiDeviceInfo.DEVICE_TUNER;
            case Constants.ADDR_PLAYBACK_1:
            case Constants.ADDR_PLAYBACK_2:
            case Constants.ADDR_PLAYBACK_3:
                return HdmiDeviceInfo.DEVICE_PLAYBACK;
            case Constants.ADDR_AUDIO_SYSTEM:
                return HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM;
            default:
                return HdmiDeviceInfo.DEVICE_RESERVED;
        }
    }

    /**
     * Passively listen to incoming CEC messages.
     *
     * This shall not result in any CEC messages being sent.
     */
    @ServiceThreadOnly
    public void handleCecMessage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Add device by logical address if it's not already known
        int sourceAddress = message.getSource();
        if (getCecDeviceInfo(sourceAddress) == null) {
            HdmiDeviceInfo newDevice = HdmiDeviceInfo.cecDeviceBuilder()
                    .setLogicalAddress(sourceAddress)
                    .setDisplayName(HdmiUtils.getDefaultDeviceName(sourceAddress))
                    .setDeviceType(logicalAddressToDeviceType(sourceAddress))
                    .build();
            addCecDevice(newDevice);
        }

        // If a message type has its own class, all valid messages of that type
        // will be represented by an instance of that class.
        if (message instanceof ReportFeaturesMessage) {
            handleReportFeatures((ReportFeaturesMessage) message);
        }

        switch (message.getOpcode()) {
            case Constants.MESSAGE_FEATURE_ABORT:
                handleFeatureAbort(message);
                break;
            case Constants.MESSAGE_REPORT_PHYSICAL_ADDRESS:
                handleReportPhysicalAddress(message);
                break;
            case Constants.MESSAGE_REPORT_POWER_STATUS:
                handleReportPowerStatus(message);
                break;
            case Constants.MESSAGE_SET_OSD_NAME:
                handleSetOsdName(message);
                break;
            case Constants.MESSAGE_DEVICE_VENDOR_ID:
                handleDeviceVendorId(message);
                break;
            case Constants.MESSAGE_CEC_VERSION:
                handleCecVersion(message);
                break;
        }
    }

    @ServiceThreadOnly
    private void handleReportFeatures(ReportFeaturesMessage message) {
        assertRunOnServiceThread();

        HdmiDeviceInfo currentDeviceInfo = getCecDeviceInfo(message.getSource());
        HdmiDeviceInfo newDeviceInfo = currentDeviceInfo.toBuilder()
                .setCecVersion(message.getCecVersion())
                .updateDeviceFeatures(message.getDeviceFeatures())
                .build();

        updateCecDevice(newDeviceInfo);

        mHdmiControlService.checkAndUpdateAbsoluteVolumeBehavior();
    }

    @ServiceThreadOnly
    private void handleFeatureAbort(HdmiCecMessage message) {
        assertRunOnServiceThread();

        if (message.getParams().length < 2) {
            return;
        }

        int originalOpcode = message.getParams()[0] & 0xFF;
        int reason = message.getParams()[1] & 0xFF;

         // Check if we received <Feature Abort> in response to <Set Audio Volume Level>.
         // This provides information on whether the source supports the message.
        if (originalOpcode == Constants.MESSAGE_SET_AUDIO_VOLUME_LEVEL) {

            @DeviceFeatures.FeatureSupportStatus int featureSupport =
                    reason == Constants.ABORT_UNRECOGNIZED_OPCODE
                            ? DeviceFeatures.FEATURE_NOT_SUPPORTED
                            : DeviceFeatures.FEATURE_SUPPORT_UNKNOWN;

            HdmiDeviceInfo currentDeviceInfo = getCecDeviceInfo(message.getSource());
            HdmiDeviceInfo newDeviceInfo = currentDeviceInfo.toBuilder()
                    .updateDeviceFeatures(
                            currentDeviceInfo.getDeviceFeatures().toBuilder()
                                    .setSetAudioVolumeLevelSupport(featureSupport)
                                    .build()
                    )
                    .build();
            updateCecDevice(newDeviceInfo);

            mHdmiControlService.checkAndUpdateAbsoluteVolumeBehavior();
        }
    }

    @ServiceThreadOnly
    private void handleCecVersion(HdmiCecMessage message) {
        assertRunOnServiceThread();

        int version = Byte.toUnsignedInt(message.getParams()[0]);
        updateDeviceCecVersion(message.getSource(), version);
    }

    @ServiceThreadOnly
    private void handleReportPhysicalAddress(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int logicalAddress = message.getSource();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        int type = message.getParams()[2];

        if (updateCecSwitchInfo(logicalAddress, type, physicalAddress)) return;

        HdmiDeviceInfo deviceInfo = getCecDeviceInfo(logicalAddress);
        if (deviceInfo == null) {
            Slog.i(TAG, "Unknown source device info for <Report Physical Address> " + message);
        } else {
            HdmiDeviceInfo updatedDeviceInfo = deviceInfo.toBuilder()
                    .setPhysicalAddress(physicalAddress)
                    .setPortId(physicalAddressToPortId(physicalAddress))
                    .setDeviceType(type)
                    .build();
            updateCecDevice(updatedDeviceInfo);
        }
    }

    @ServiceThreadOnly
    private void handleReportPowerStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Update power status of device
        int newStatus = message.getParams()[0] & 0xFF;
        updateDevicePowerStatus(message.getSource(), newStatus);

        if (message.getDestination() == Constants.ADDR_BROADCAST) {
            updateDeviceCecVersion(message.getSource(), HdmiControlManager.HDMI_CEC_VERSION_2_0);
        }
    }

    @ServiceThreadOnly
    private void updateDeviceCecVersion(int logicalAddress, int hdmiCecVersion) {
        assertRunOnServiceThread();
        HdmiDeviceInfo deviceInfo = getCecDeviceInfo(logicalAddress);
        if (deviceInfo == null) {
            Slog.w(TAG, "Can not update CEC version of non-existing device:" + logicalAddress);
            return;
        }

        if (deviceInfo.getCecVersion() == hdmiCecVersion) {
            return;
        }

        HdmiDeviceInfo updatedDeviceInfo = deviceInfo.toBuilder()
                .setCecVersion(hdmiCecVersion)
                .build();
        updateCecDevice(updatedDeviceInfo);
    }

    @ServiceThreadOnly
    private void handleSetOsdName(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int logicalAddress = message.getSource();
        String osdName;
        HdmiDeviceInfo deviceInfo = getCecDeviceInfo(logicalAddress);
        // If the device is not in device list, ignore it.
        if (deviceInfo == null) {
            Slog.i(TAG, "No source device info for <Set Osd Name>." + message);
            return;
        }
        try {
            osdName = new String(message.getParams(), "US-ASCII");
        } catch (UnsupportedEncodingException e) {
            Slog.e(TAG, "Invalid <Set Osd Name> request:" + message, e);
            return;
        }

        if (deviceInfo.getDisplayName() != null
                && deviceInfo.getDisplayName().equals(osdName)) {
            Slog.d(TAG, "Ignore incoming <Set Osd Name> having same osd name:" + message);
            return;
        }

        Slog.d(TAG, "Updating device OSD name from "
                + deviceInfo.getDisplayName()
                + " to " + osdName);

        HdmiDeviceInfo updatedDeviceInfo = deviceInfo.toBuilder()
                .setDisplayName(osdName)
                .build();
        updateCecDevice(updatedDeviceInfo);
    }

    @ServiceThreadOnly
    private void handleDeviceVendorId(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int logicalAddress = message.getSource();
        int vendorId = HdmiUtils.threeBytesToInt(message.getParams());

        HdmiDeviceInfo deviceInfo = getCecDeviceInfo(logicalAddress);
        if (deviceInfo == null) {
            Slog.i(TAG, "Unknown source device info for <Device Vendor ID> " + message);
        } else {
            HdmiDeviceInfo updatedDeviceInfo = deviceInfo.toBuilder()
                    .setVendorId(vendorId)
                    .build();
            updateCecDevice(updatedDeviceInfo);
        }
    }

    void addCecSwitch(int physicalAddress) {
        mCecSwitches.add(physicalAddress);
    }

    public ArraySet<Integer> getCecSwitches() {
        return mCecSwitches;
    }

    void removeCecSwitches(int portId) {
        Iterator<Integer> it = mCecSwitches.iterator();
        while (it.hasNext()) {
            int path = it.next();
            int devicePortId = physicalAddressToPortId(path);
            if (devicePortId == portId || devicePortId == Constants.INVALID_PORT_ID) {
                it.remove();
            }
        }
    }

    void removeDevicesConnectedToPort(int portId) {
        removeCecSwitches(portId);

        List<Integer> toRemove = new ArrayList<>();
        for (int i = 0; i < mDeviceInfos.size(); i++) {
            int key = mDeviceInfos.keyAt(i);
            int physicalAddress = mDeviceInfos.get(key).getPhysicalAddress();
            int devicePortId = physicalAddressToPortId(physicalAddress);
            if (devicePortId == portId || devicePortId == Constants.INVALID_PORT_ID) {
                toRemove.add(key);
            }
        }
        for (Integer key : toRemove) {
            removeDeviceInfo(key);
        }
    }

    boolean updateCecSwitchInfo(int address, int type, int path) {
        if (address == Constants.ADDR_UNREGISTERED
                && type == HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH) {
            mCecSwitches.add(path);
            updateSafeDeviceInfoList();
            return true;  // Pure switch does not need further processing. Return here.
        }
        if (type == HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM) {
            mCecSwitches.add(path);
        }
        return false;
    }

    @GuardedBy("mLock")
    List<HdmiDeviceInfo> getSafeCecDevicesLocked() {
        ArrayList<HdmiDeviceInfo> infoList = new ArrayList<>();
        for (HdmiDeviceInfo info : mSafeAllDeviceInfos) {
            if (isLocalDeviceAddress(info.getLogicalAddress())) {
                continue;
            }
            infoList.add(info);
        }
        return infoList;
    }

    /**
     * Thread safe version of {@link #getCecDeviceInfo(int)}.
     *
     * @param logicalAddress logical address to be retrieved
     * @return {@link HdmiDeviceInfo} matched with the given {@code logicalAddress}.
     * Returns null if no logical address matched
     */
    @Nullable
    HdmiDeviceInfo getSafeCecDeviceInfo(int logicalAddress) {
        for (HdmiDeviceInfo info : mSafeAllDeviceInfos) {
            if (info.isCecDevice() && info.getLogicalAddress() == logicalAddress) {
                return info;
            }
        }
        return null;
    }

    /**
     * Returns the {@link HdmiDeviceInfo} instance whose physical address matches
     * the given routing path. CEC devices use routing path for its physical address to
     * describe the hierarchy of the devices in the network.
     *
     * @param path routing path or physical address
     * @return {@link HdmiDeviceInfo} if the matched info is found; otherwise null
     */
    @ServiceThreadOnly
    final HdmiDeviceInfo getDeviceInfoByPath(int path) {
        assertRunOnServiceThread();
        for (HdmiDeviceInfo info : getDeviceInfoList(false)) {
            if (info.getPhysicalAddress() == path) {
                return info;
            }
        }
        return null;
    }

    /**
     * Returns the {@link HdmiDeviceInfo} instance whose physical address matches
     * the given routing path. This is the version accessible safely from threads
     * other than service thread.
     *
     * @param path routing path or physical address
     * @return {@link HdmiDeviceInfo} if the matched info is found; otherwise null
     */
    HdmiDeviceInfo getSafeDeviceInfoByPath(int path) {
        for (HdmiDeviceInfo info : mSafeAllDeviceInfos) {
            if (info.getPhysicalAddress() == path) {
                return info;
            }
        }
        return null;
    }

    public int getPhysicalAddress() {
        if (mPhysicalAddress == Constants.INVALID_PHYSICAL_ADDRESS) {
            mPhysicalAddress = mHdmiCecController.getPhysicalAddress();
        }
        return mPhysicalAddress;
    }

    @ServiceThreadOnly
    public void clear() {
        assertRunOnServiceThread();
        initPortInfo();
        clearDeviceList();
        clearLocalDevices();
    }

    @ServiceThreadOnly
    void removeUnusedLocalDevices(ArrayList<HdmiCecLocalDevice> allocatedDevices) {
        ArrayList<Integer> deviceTypesToRemove = new ArrayList<>();
        for (int i = 0; i < mLocalDevices.size(); i++) {
            int deviceType = mLocalDevices.keyAt(i);
            boolean shouldRemoveLocalDevice = allocatedDevices.stream().noneMatch(
                    localDevice -> localDevice.getDeviceInfo() != null
                    && localDevice.getDeviceInfo().getDeviceType() == deviceType);
            if (shouldRemoveLocalDevice) {
                deviceTypesToRemove.add(deviceType);
            }
        }
        for (Integer deviceType : deviceTypesToRemove) {
            mLocalDevices.remove(deviceType);
        }
    }

    @ServiceThreadOnly
    void removeLocalDeviceWithType(int deviceType) {
        mLocalDevices.remove(deviceType);
    }

    @ServiceThreadOnly
    public void clearDeviceList() {
        assertRunOnServiceThread();
        for (HdmiDeviceInfo info : HdmiUtils.sparseArrayToList(mDeviceInfos)) {
            if (info.getPhysicalAddress() == getPhysicalAddress()
                    || info.getPhysicalAddress() == HdmiDeviceInfo.PATH_INVALID) {
                // Don't notify listeners of local devices or devices that haven't reported their
                // physical address yet
                continue;
            }
            invokeDeviceEventListener(info,
                    HdmiControlManager.DEVICE_EVENT_REMOVE_DEVICE);
        }
        mDeviceInfos.clear();
        updateSafeDeviceInfoList();
    }

    /**
     * Returns HDMI port information for the given port id.
     *
     * @param portId HDMI port id
     * @return {@link HdmiPortInfo} for the given port
     */
    HdmiPortInfo getPortInfo(int portId) {
        return mPortInfoMap.get(portId, null);
    }

    /**
     * Returns the routing path (physical address) of the HDMI port for the given
     * port id.
     */
    int portIdToPath(int portId) {
        if (portId == Constants.CEC_SWITCH_HOME) {
            return getPhysicalAddress();
        }
        HdmiPortInfo portInfo = getPortInfo(portId);
        if (portInfo == null) {
            Slog.e(TAG, "Cannot find the port info: " + portId);
            return Constants.INVALID_PHYSICAL_ADDRESS;
        }
        return portInfo.getAddress();
    }

    /**
     * Returns the id of HDMI port located at the current device that runs this method.
     *
     * For TV with physical address 0x0000, target device 0x1120, we want port physical address
     * 0x1000 to get the correct port id from {@link #mPortIdMap}. For device with Physical Address
     * 0x2000, target device 0x2420, we want port address 0x24000 to get the port id.
     *
     * <p>Return {@link Constants#INVALID_PORT_ID} if target device does not connect to.
     *
     * @param path the target device's physical address.
     * @return the id of the port that the target device eventually connects to
     * on the current device.
     */
    int physicalAddressToPortId(int path) {
        int physicalAddress = getPhysicalAddress();
        if (path == physicalAddress) {
            // The local device isn't connected to any port; assign portId 0
            return Constants.CEC_SWITCH_HOME;
        }
        int mask = 0xF000;
        int finalMask = 0xF000;
        int maskedAddress = physicalAddress;

        while (maskedAddress != 0) {
            maskedAddress = physicalAddress & mask;
            finalMask |= mask;
            mask >>= 4;
        }

        int portAddress = path & finalMask;
        return mPortIdMap.get(portAddress, Constants.INVALID_PORT_ID);
    }

    List<HdmiPortInfo> getPortInfo() {
        return mPortInfo;
    }

    void setPortInfo(List<HdmiPortInfo> portInfo) {
        mPortInfo = portInfo;
    }

    private boolean isLocalDeviceAddress(int address) {
        for (int i = 0; i < mLocalDevices.size(); i++) {
            int key = mLocalDevices.keyAt(i);
            if (mLocalDevices.get(key).getDeviceInfo().getLogicalAddress() == address) {
                return true;
            }
        }
        return false;
    }

    private void assertRunOnServiceThread() {
        if (Looper.myLooper() != mHandler.getLooper()) {
            throw new IllegalStateException("Should run on service thread.");
        }
    }

    protected void dump(IndentingPrintWriter pw) {
        pw.println("HDMI CEC Network");
        pw.increaseIndent();
        HdmiUtils.dumpIterable(pw, "mPortInfo:", mPortInfo);
        for (int i = 0; i < mLocalDevices.size(); ++i) {
            pw.println("HdmiCecLocalDevice #" + mLocalDevices.keyAt(i) + ":");
            pw.increaseIndent();
            mLocalDevices.valueAt(i).dump(pw);

            pw.println("Active Source history:");
            pw.increaseIndent();
            final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            ArrayBlockingQueue<HdmiCecController.Dumpable> activeSourceHistory =
                    mLocalDevices.valueAt(i).getActiveSourceHistory();
            for (HdmiCecController.Dumpable activeSourceEvent : activeSourceHistory) {
                activeSourceEvent.dump(pw, sdf);
            }
            pw.decreaseIndent();
            pw.decreaseIndent();
        }
        HdmiUtils.dumpIterable(pw, "mDeviceInfos:", mSafeAllDeviceInfos);
        pw.decreaseIndent();
    }
}
