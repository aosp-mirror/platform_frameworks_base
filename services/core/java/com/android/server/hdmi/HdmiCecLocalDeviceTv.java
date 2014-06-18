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
import android.hardware.hdmi.IHdmiControlCallback;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.hdmi.DeviceDiscoveryAction.DeviceDiscoveryCallback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Represent a logical device of type TV residing in Android system.
 */
final class HdmiCecLocalDeviceTv extends HdmiCecLocalDevice {
    private static final String TAG = "HdmiCecLocalDeviceTv";

    // Whether ARC is "enabled" or not.
    @GuardedBy("mLock")
    private boolean mArcStatusEnabled = false;

    @GuardedBy("mLock")
    // Whether SystemAudioMode is "On" or not.
    private boolean mSystemAudioMode;

    // Map-like container of all cec devices including local ones.
    // A logical address of device is used as key of container.
    private final SparseArray<HdmiCecDeviceInfo> mDeviceInfos = new SparseArray<>();

    HdmiCecLocalDeviceTv(HdmiControlService service) {
        super(service, HdmiCec.DEVICE_TV);

        // TODO: load system audio mode and set it to mSystemAudioMode.
    }

    @Override
    protected void onAddressAllocated(int logicalAddress) {
        assertRunOnServiceThread();
        // TODO: vendor-specific initialization here.

        mService.sendCecCommand(HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                mAddress, mService.getPhysicalAddress(), mDeviceType));
        mService.sendCecCommand(HdmiCecMessageBuilder.buildDeviceVendorIdCommand(
                mAddress, mService.getVendorId()));

        launchDeviceDiscovery();
        // TODO: Start routing control action, device discovery action.
    }

    /**
     * Performs the action 'device select', or 'one touch play' initiated by TV.
     *
     * @param targetAddress logical address of the device to select
     * @param callback callback object to report the result with
     */
    void deviceSelect(int targetAddress, IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        HdmiCecDeviceInfo targetDevice = mService.getDeviceInfo(targetAddress);
        if (targetDevice == null) {
            invokeCallback(callback, HdmiCec.RESULT_TARGET_NOT_AVAILABLE);
            return;
        }
        removeAction(DeviceSelectAction.class);
        addAndStartAction(new DeviceSelectAction(this, targetDevice, callback));
    }

    /**
     * Performs the action routing control.
     *
     * @param portId new HDMI port to route to
     * @param callback callback object to report the result with
     */
    void portSelect(int portId, IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        if (isInPresetInstallationMode()) {
            invokeCallback(callback, HdmiCec.RESULT_INCORRECT_MODE);
            return;
        }
        // Make sure this call does not stem from <Active Source> message reception, in
        // which case the two ports will be the same.
        if (portId == getActivePortId()) {
            invokeCallback(callback, HdmiCec.RESULT_SUCCESS);
            return;
        }
        setActivePortId(portId);

        // TODO: Return immediately if the operation is triggered by <Text/Image View On>
        // TODO: Handle invalid port id / active input which should be treated as an
        //        internal tuner.

        removeAction(RoutingControlAction.class);

        int oldPath = mService.portIdToPath(mService.portIdToPath(getActivePortId()));
        int newPath = mService.portIdToPath(portId);
        HdmiCecMessage routingChange =
                HdmiCecMessageBuilder.buildRoutingChange(mAddress, oldPath, newPath);
        mService.sendCecCommand(routingChange);
        addAndStartAction(new RoutingControlAction(this, newPath, callback));
    }

    /**
     * Sends key to a target CEC device.
     *
     * @param keyCode key code to send. Defined in {@link KeyEvent}.
     * @param isPressed true if this is keypress event
     */
    void sendKeyEvent(int keyCode, boolean isPressed) {
        assertRunOnServiceThread();
        List<SendKeyAction> action = getActions(SendKeyAction.class);
        if (!action.isEmpty()) {
            action.get(0).processKeyEvent(keyCode, isPressed);
        } else {
            if (isPressed) {
                addAndStartAction(new SendKeyAction(this, getActiveSource(), keyCode));
            } else {
                Slog.w(TAG, "Discard key release event");
            }
        }
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
        assertRunOnServiceThread();
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

    @Override
    protected boolean handleReportPhysicalAddress(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Ignore if [Device Discovery Action] is going on.
        if (hasAction(DeviceDiscoveryAction.class)) {
            Slog.i(TAG, "Ignore unrecognizable <Report Physical Address> "
                    + "because Device Discovery Action is on-going:" + message);
            return true;
        }

        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        int logicalAddress = message.getSource();

        // If it is a new device and connected to the tail of active path,
        // it's required to change routing path.
        boolean requireRoutingChange = !isInDeviceList(physicalAddress, logicalAddress)
                && isTailOfActivePath(physicalAddress);
        addAndStartAction(new NewDeviceAction(this, message.getSource(), physicalAddress,
                requireRoutingChange));
        return true;
    }

    @Override
    protected boolean handleVendorSpecificCommand(HdmiCecMessage message) {
        assertRunOnServiceThread();
        List<VendorSpecificAction> actions = Collections.emptyList();
        // TODO: Call mService.getActions(VendorSpecificAction.class) to get all the actions.

        // We assume that there can be multiple vendor-specific command actions running
        // at the same time. Pass the message to each action to see if one of them needs it.
        for (VendorSpecificAction action : actions) {
            if (action.processCommand(message)) {
                return true;
            }
        }
        // Handle the message here if it is not already consumed by one of the running actions.
        // Respond with a appropriate vendor-specific command or <Feature Abort>, or create another
        // vendor-specific action:
        //
        // mService.addAndStartAction(new VendorSpecificAction(mService, mAddress));
        //
        // For now, simply reply with <Feature Abort> and mark it consumed by returning true.
        mService.sendCecCommand(HdmiCecMessageBuilder.buildFeatureAbortCommand(
                message.getDestination(), message.getSource(), message.getOpcode(),
                HdmiConstants.ABORT_REFUSED));
        return true;
    }

    private void launchDeviceDiscovery() {
        assertRunOnServiceThread();
        clearDeviceInfoList();
        DeviceDiscoveryAction action = new DeviceDiscoveryAction(this,
                new DeviceDiscoveryCallback() {
                    @Override
                    public void onDeviceDiscoveryDone(List<HdmiCecDeviceInfo> deviceInfos) {
                        for (HdmiCecDeviceInfo info : deviceInfos) {
                            addCecDevice(info);
                        }

                        // Since we removed all devices when it's start and
                        // device discovery action does not poll local devices,
                        // we should put device info of local device manually here
                        for (HdmiCecLocalDevice device : mService.getAllLocalDevices()) {
                            addCecDevice(device.getDeviceInfo());
                        }

                        addAndStartAction(new HotplugDetectionAction(HdmiCecLocalDeviceTv.this));

                        // If there is AVR, initiate System Audio Auto initiation action,
                        // which turns on and off system audio according to last system
                        // audio setting.
                        HdmiCecDeviceInfo avrInfo = getAvrDeviceInfo();
                        if (avrInfo != null) {
                            addAndStartAction(new SystemAudioAutoInitiationAction(
                                    HdmiCecLocalDeviceTv.this, avrInfo.getLogicalAddress()));
                        }
                    }
                });
        addAndStartAction(action);
    }

    // Clear all device info.
    private void clearDeviceInfoList() {
        assertRunOnServiceThread();
        mDeviceInfos.clear();
    }

    void setSystemAudioMode(boolean on) {
        synchronized (mLock) {
            if (on != mSystemAudioMode) {
                mSystemAudioMode = on;
                // TODO: Need to set the preference for SystemAudioMode.
                // TODO: Need to handle the notification of changing the mode and
                // to identify the notification should be handled in the service or TvSettings.
            }
        }
    }

    boolean getSystemAudioMode() {
        synchronized (mLock) {
            assertRunOnServiceThread();
            return mSystemAudioMode;
        }
    }

    /**
     * Change ARC status into the given {@code enabled} status.
     *
     * @return {@code true} if ARC was in "Enabled" status
     */
    boolean setArcStatus(boolean enabled) {
        synchronized (mLock) {
            boolean oldStatus = mArcStatusEnabled;
            // 1. Enable/disable ARC circuit.
            mService.setAudioReturnChannel(enabled);

            // TODO: notify arc mode change to AudioManager.

            // 2. Update arc status;
            mArcStatusEnabled = enabled;
            return oldStatus;
        }
    }

    /**
     * Returns whether ARC is enabled or not.
     */
    boolean getArcStatus() {
        synchronized (mLock) {
            return mArcStatusEnabled;
        }
    }

    void setAudioStatus(boolean mute, int volume) {
        mService.setAudioStatus(mute, volume);
    }

    @Override
    protected boolean handleInitiateArc(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // In case where <Initiate Arc> is started by <Request ARC Initiation>
        // need to clean up RequestArcInitiationAction.
        removeAction(RequestArcInitiationAction.class);
        SetArcTransmissionStateAction action = new SetArcTransmissionStateAction(this,
                message.getSource(), true);
        addAndStartAction(action);
        return true;
    }

    @Override
    protected boolean handleTerminateArc(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // In case where <Terminate Arc> is started by <Request ARC Termination>
        // need to clean up RequestArcInitiationAction.
        // TODO: check conditions of power status by calling is_connected api
        // to be added soon.
        removeAction(RequestArcTerminationAction.class);
        SetArcTransmissionStateAction action = new SetArcTransmissionStateAction(this,
                message.getSource(), false);
        addAndStartAction(action);
        return true;
    }

    @Override
    protected boolean handleSetSystemAudioMode(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!isMessageForSystemAudio(message)) {
            return false;
        }
        SystemAudioActionFromAvr action = new SystemAudioActionFromAvr(this,
                message.getSource(), HdmiUtils.parseCommandParamSystemAudioStatus(message));
        addAndStartAction(action);
        return true;
    }

    @Override
    protected boolean handleSystemAudioModeStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!isMessageForSystemAudio(message)) {
            return false;
        }
        setSystemAudioMode(HdmiUtils.parseCommandParamSystemAudioStatus(message));
        return true;
    }

    private boolean isMessageForSystemAudio(HdmiCecMessage message) {
        if (message.getSource() != HdmiCec.ADDR_AUDIO_SYSTEM
                || message.getDestination() != HdmiCec.ADDR_TV
                || getAvrDeviceInfo() == null) {
            Slog.w(TAG, "Skip abnormal CecMessage: " + message);
            return false;
        }
        return true;
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
     * Return a list of all {@link HdmiCecDeviceInfo}.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     */
    List<HdmiCecDeviceInfo> getDeviceInfoList(boolean includelLocalDevice) {
        assertRunOnServiceThread();
        if (includelLocalDevice) {
                return HdmiUtils.sparseArrayToList(mDeviceInfos);
        } else {

            ArrayList<HdmiCecDeviceInfo> infoList = new ArrayList<>();
            for (int i = 0; i < mDeviceInfos.size(); ++i) {
                HdmiCecDeviceInfo info = mDeviceInfos.valueAt(i);
                if (!isLocalDeviceAddress(info.getLogicalAddress())) {
                    infoList.add(info);
                }
            }
            return infoList;
        }
    }

    private boolean isLocalDeviceAddress(int address) {
        assertRunOnServiceThread();
        for (HdmiCecLocalDevice device : mService.getAllLocalDevices()) {
            if (device.isAddressOf(address)) {
                return true;
            }
        }
        return false;
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

    HdmiCecDeviceInfo getAvrDeviceInfo() {
        assertRunOnServiceThread();
        return getDeviceInfo(HdmiCec.ADDR_AUDIO_SYSTEM);
    }

    /**
     * Called when a device is newly added or a new device is detected.
     *
     * @param info device info of a new device.
     */
    final void addCecDevice(HdmiCecDeviceInfo info) {
        assertRunOnServiceThread();
        addDeviceInfo(info);

        // TODO: announce new device detection.
    }

    /**
     * Called when a device is removed or removal of device is detected.
     *
     * @param address a logical address of a device to be removed
     */
    final void removeCecDevice(int address) {
        assertRunOnServiceThread();
        removeDeviceInfo(address);
        mCecMessageCache.flushMessagesFrom(address);

        // TODO: announce a device removal.
    }

    /**
     * Returns the {@link HdmiCecDeviceInfo} instance whose physical address matches
     * the given routing path. CEC devices use routing path for its physical address to
     * describe the hierarchy of the devices in the network.
     *
     * @param path routing path or physical address
     * @return {@link HdmiCecDeviceInfo} if the matched info is found; otherwise null
     */
    final HdmiCecDeviceInfo getDeviceInfoByPath(int path) {
        assertRunOnServiceThread();
        for (HdmiCecDeviceInfo info : getDeviceInfoList(false)) {
            if (info.getPhysicalAddress() == path) {
                return info;
            }
        }
        return null;
    }

    /**
     * Whether a device of the specified physical address and logical address exists
     * in a device info list. However, both are minimal condition and it could
     * be different device from the original one.
     *
     * @param physicalAddress physical address of a device to be searched
     * @param logicalAddress logical address of a device to be searched
     * @return true if exist; otherwise false
     */
    boolean isInDeviceList(int physicalAddress, int logicalAddress) {
        assertRunOnServiceThread();
        HdmiCecDeviceInfo device = getDeviceInfo(logicalAddress);
        if (device == null) {
            return false;
        }
        return device.getPhysicalAddress() == physicalAddress;
    }

    @Override
    void onHotplug(int portNo, boolean connected) {
        assertRunOnServiceThread();
        // TODO: delegate onHotplug event to each local device.

        // Tv device will have permanent HotplugDetectionAction.
        List<HotplugDetectionAction> hotplugActions = getActions(HotplugDetectionAction.class);
        if (!hotplugActions.isEmpty()) {
            // Note that hotplug action is single action running on a machine.
            // "pollAllDevicesNow" cleans up timer and start poll action immediately.
            hotplugActions.get(0).pollAllDevicesNow();
        }
    }

    boolean canChangeSystemAudio() {
        // TODO: implement this.
        // return true if no system audio control sequence is running.
        return false;
    }
}
