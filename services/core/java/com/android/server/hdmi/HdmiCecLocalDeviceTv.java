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
import android.media.AudioSystem;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.hdmi.DeviceDiscoveryAction.DeviceDiscoveryCallback;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;

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

    // Whether SystemAudioMode is "On" or not.
    @GuardedBy("mLock")
    private boolean mSystemAudioMode;

    // The previous port id (input) before switching to the new one. This is remembered in order to
    // be able to switch to it upon receiving <Inactive Source> from currently active source.
    // This remains valid only when the active source was switched via one touch play operation
    // (either by TV or source device). Manual port switching invalidates this value to
    // HdmiConstants.PORT_INVALID, for which case <Inactive Source> does not do anything.
    @GuardedBy("mLock")
    private int mPrevPortId;

    @GuardedBy("mLock")
    private int mSystemAudioVolume = HdmiConstants.UNKNOWN_VOLUME;

    @GuardedBy("mLock")
    private boolean mSystemAudioMute = false;

    // Copy of mDeviceInfos to guarantee thread-safety.
    @GuardedBy("mLock")
    private List<HdmiCecDeviceInfo> mSafeAllDeviceInfos = Collections.emptyList();
    // All external cec device which excludes local devices.
    @GuardedBy("mLock")
    private List<HdmiCecDeviceInfo> mSafeExternalDeviceInfos = Collections.emptyList();

    // Map-like container of all cec devices including local ones.
    // A logical address of device is used as key of container.
    // This is not thread-safe. For external purpose use mSafeDeviceInfos.
    private final SparseArray<HdmiCecDeviceInfo> mDeviceInfos = new SparseArray<>();

    HdmiCecLocalDeviceTv(HdmiControlService service) {
        super(service, HdmiCec.DEVICE_TV);
        mPrevPortId = HdmiConstants.INVALID_PORT_ID;
        // TODO: load system audio mode and set it to mSystemAudioMode.
    }

    @Override
    @ServiceThreadOnly
    protected void onAddressAllocated(int logicalAddress) {
        assertRunOnServiceThread();
        // TODO: vendor-specific initialization here.

        mService.sendCecCommand(HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                mAddress, mService.getPhysicalAddress(), mDeviceType));
        mService.sendCecCommand(HdmiCecMessageBuilder.buildDeviceVendorIdCommand(
                mAddress, mService.getVendorId()));

        launchDeviceDiscovery();
        // TODO: Start routing control action
    }

    /**
     * Performs the action 'device select', or 'one touch play' initiated by TV.
     *
     * @param targetAddress logical address of the device to select
     * @param callback callback object to report the result with
     */
    @ServiceThreadOnly
    void deviceSelect(int targetAddress, IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        if (targetAddress == HdmiCec.ADDR_INTERNAL) {
            handleSelectInternalSource(callback);
            return;
        }
        HdmiCecDeviceInfo targetDevice = getDeviceInfo(targetAddress);
        if (targetDevice == null) {
            invokeCallback(callback, HdmiCec.RESULT_TARGET_NOT_AVAILABLE);
            return;
        }
        removeAction(DeviceSelectAction.class);
        addAndStartAction(new DeviceSelectAction(this, targetDevice, callback));
    }

    @ServiceThreadOnly
    private void handleSelectInternalSource(IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        // Seq #18
        if (mService.isControlEnabled() && getActiveSource() != mAddress) {
            updateActiveSource(mAddress, mService.getPhysicalAddress());
            // TODO: Check if this comes from <Text/Image View On> - if true, do nothing.
            HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                    mAddress, mService.getPhysicalAddress());
            mService.sendCecCommand(activeSource);
        }
    }

    @ServiceThreadOnly
    void updateActiveSource(int activeSource, int activePath) {
        assertRunOnServiceThread();
        // Seq #14
        if (activeSource == getActiveSource() && activePath == getActivePath()) {
            return;
        }
        setActiveSource(activeSource);
        setActivePath(activePath);
        if (getDeviceInfo(activeSource) != null && activeSource != mAddress) {
            if (mService.pathToPortId(activePath) == getActivePortId()) {
                setPrevPortId(getActivePortId());
            }
            // TODO: Show the OSD banner related to the new active source device.
        } else {
            // TODO: If displayed, remove the OSD banner related to the previous
            //       active source device.
        }
    }

    /**
     * Returns the previous port id kept to handle input switching on <Inactive Source>.
     */
    int getPrevPortId() {
        synchronized (mLock) {
            return mPrevPortId;
        }
    }

    /**
     * Sets the previous port id. INVALID_PORT_ID invalidates it, hence no actions will be
     * taken for <Inactive Source>.
     */
    void setPrevPortId(int portId) {
        synchronized (mLock) {
            mPrevPortId = portId;
        }
    }

    @ServiceThreadOnly
    void updateActivePortId(int portId) {
        assertRunOnServiceThread();
        // Seq #15
        if (portId == getActivePortId()) {
            return;
        }
        setPrevPortId(portId);
        // TODO: Actually switch the physical port here. Handle PAP/PIP as well.
        //       Show OSD port change banner
    }

    @ServiceThreadOnly
    void doManualPortSwitching(int portId, IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        // Seq #20
        if (!mService.isControlEnabled() || portId == getActivePortId()) {
            invokeCallback(callback, HdmiCec.RESULT_INCORRECT_MODE);
            return;
        }
        // TODO: Make sure this call does not stem from <Active Source> message reception.

        setActivePortId(portId);
        // TODO: Return immediately if the operation is triggered by <Text/Image View On>
        //       and this is the first notification about the active input after power-on.
        // TODO: Handle invalid port id / active input which should be treated as an
        //       internal tuner.

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
     * @param keyCode key code to send. Defined in {@link android.view.KeyEvent}.
     * @param isPressed true if this is keypress event
     */
    @ServiceThreadOnly
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
        if (callback == null) {
            return;
        }
        try {
            callback.onComplete(result);
        } catch (RemoteException e) {
            Slog.e(TAG, "Invoking callback failed:" + e);
        }
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleActiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int address = message.getSource();
        int path = HdmiUtils.twoBytesToInt(message.getParams());
        if (getDeviceInfo(address) == null) {
            handleNewDeviceAtTheTailOfActivePath(address, path);
        } else {
            ActiveSourceHandler.create(this, null).process(address, path);
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleInactiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Seq #10

        // Ignore <Inactive Source> from non-active source device.
        if (getActiveSource() != message.getSource()) {
            return true;
        }
        if (isInPresetInstallationMode()) {
            return true;
        }
        int portId = getPrevPortId();
        if (portId != HdmiConstants.INVALID_PORT_ID) {
            // TODO: Do this only if TV is not showing multiview like PIP/PAP.

            HdmiCecDeviceInfo inactiveSource = getDeviceInfo(message.getSource());
            if (inactiveSource == null) {
                return true;
            }
            if (mService.pathToPortId(inactiveSource.getPhysicalAddress()) == portId) {
                return true;
            }
            // TODO: Switch the TV freeze mode off

            setActivePortId(portId);
            doManualPortSwitching(portId, null);
            setPrevPortId(HdmiConstants.INVALID_PORT_ID);
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRequestActiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Seq #19
        if (mAddress == getActiveSource()) {
            mService.sendCecCommand(
                    HdmiCecMessageBuilder.buildActiveSource(mAddress, getActivePath()));
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
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
    @ServiceThreadOnly
    protected boolean handleReportPhysicalAddress(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Ignore if [Device Discovery Action] is going on.
        if (hasAction(DeviceDiscoveryAction.class)) {
            Slog.i(TAG, "Ignore unrecognizable <Report Physical Address> "
                    + "because Device Discovery Action is on-going:" + message);
            return true;
        }

        int path = HdmiUtils.twoBytesToInt(message.getParams());
        int address = message.getSource();
        if (!isInDeviceList(path, address)) {
            handleNewDeviceAtTheTailOfActivePath(address, path);
        }
        addAndStartAction(new NewDeviceAction(this, address, path));
        return true;
    }

    private void handleNewDeviceAtTheTailOfActivePath(int address, int path) {
        // Seq #22
        if (isTailOfActivePath(path, getActivePath())) {
            removeAction(RoutingControlAction.class);
            int newPath = mService.portIdToPath(getActivePortId());
            mService.sendCecCommand(HdmiCecMessageBuilder.buildRoutingChange(
                    mAddress, getActivePath(), newPath));
            addAndStartAction(new RoutingControlAction(this, getActivePortId(), null));
        }
    }

    /**
     * Whether the given path is located in the tail of current active path.
     *
     * @param path to be tested
     * @param activePath current active path
     * @return true if the given path is located in the tail of current active path; otherwise,
     *         false
     */
    static boolean isTailOfActivePath(int path, int activePath) {
        // If active routing path is internal source, return false.
        if (activePath == 0) {
            return false;
        }
        for (int i = 12; i >= 0; i -= 4) {
            int curActivePath = (activePath >> i) & 0xF;
            if (curActivePath == 0) {
                return true;
            } else {
                int curPath = (path >> i) & 0xF;
                if (curPath != curActivePath) {
                    return false;
                }
            }
        }
        return false;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRoutingChange(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Seq #21
        byte[] params = message.getParams();
        if (params.length != 4) {
            Slog.w(TAG, "Wrong parameter: " + message);
            return true;
        }
        int currentPath = HdmiUtils.twoBytesToInt(params);
        if (HdmiUtils.isAffectingActiveRoutingPath(getActivePath(), currentPath)) {
            int newPath = HdmiUtils.twoBytesToInt(params, 2);
            setActivePath(newPath);
            removeAction(RoutingControlAction.class);
            addAndStartAction(new RoutingControlAction(this, newPath, null));
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
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

    @Override
    @ServiceThreadOnly
    protected boolean handleReportAudioStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();

        byte params[] = message.getParams();
        if (params.length < 1) {
            Slog.w(TAG, "Invalide <Report Audio Status> message:" + message);
            return true;
        }
        int mute = params[0] & 0x80;
        int volume = params[0] & 0x7F;
        setAudioStatus(mute == 0x80, volume);
        return true;
    }

    @ServiceThreadOnly
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
                            if (isConnectedToArcPort(avrInfo.getPhysicalAddress())) {
                                addAndStartAction(new RequestArcInitiationAction(
                                        HdmiCecLocalDeviceTv.this, avrInfo.getLogicalAddress()));
                            }
                        }
                    }
                });
        addAndStartAction(action);
    }

    // Clear all device info.
    @ServiceThreadOnly
    private void clearDeviceInfoList() {
        assertRunOnServiceThread();
        mDeviceInfos.clear();
        updateSafeDeviceInfoList();
    }

    @ServiceThreadOnly
    void changeSystemAudioMode(boolean enabled, IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        HdmiCecDeviceInfo avr = getAvrDeviceInfo();
        if (avr == null) {
            invokeCallback(callback, HdmiCec.RESULT_SOURCE_NOT_AVAILABLE);
            return;
        }

        addAndStartAction(
                new SystemAudioActionFromTv(this, avr.getLogicalAddress(), enabled, callback));
    }

    void setSystemAudioMode(boolean on) {
        synchronized (mLock) {
            if (on != mSystemAudioMode) {
                mSystemAudioMode = on;
                // TODO: Need to set the preference for SystemAudioMode.
                mService.announceSystemAudioModeChange(on);
            }
        }
    }

    boolean getSystemAudioMode() {
        synchronized (mLock) {
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
            // 2. Notify arc status to audio service.
            notifyArcStatusToAudioService(enabled);
            // 3. Update arc status;
            mArcStatusEnabled = enabled;
            return oldStatus;
        }
    }

    private void notifyArcStatusToAudioService(boolean enabled) {
        // Note that we don't set any name to ARC.
        mService.getAudioManager().setWiredDeviceConnectionState(
                AudioSystem.DEVICE_OUT_HDMI_ARC,
                enabled ? 1 : 0, "");
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
        synchronized (mLock) {
            mSystemAudioMute = mute;
            mSystemAudioVolume = volume;
            // TODO: pass volume to service (audio service) after scale it to local volume level.
            mService.setAudioStatus(mute, volume);
        }
    }

    @ServiceThreadOnly
    void changeVolume(int curVolume, int delta, int maxVolume) {
        assertRunOnServiceThread();
        if (delta == 0 || !isSystemAudioOn()) {
            return;
        }

        int targetVolume = curVolume + delta;
        int cecVolume = VolumeControlAction.scaleToCecVolume(targetVolume, maxVolume);
        synchronized (mLock) {
            // If new volume is the same as current system audio volume, just ignore it.
            // Note that UNKNOWN_VOLUME is not in range of cec volume scale.
            if (cecVolume == mSystemAudioVolume) {
                // Update tv volume with system volume value.
                mService.setAudioStatus(false,
                        VolumeControlAction.scaleToCustomVolume(mSystemAudioVolume, maxVolume));
                return;
            }
        }

        // Remove existing volume action.
        removeAction(VolumeControlAction.class);

        HdmiCecDeviceInfo avr = getAvrDeviceInfo();
        addAndStartAction(VolumeControlAction.ofVolumeChange(this, avr.getLogicalAddress(),
                cecVolume, delta > 0));
    }

    @ServiceThreadOnly
    void changeMute(boolean mute) {
        assertRunOnServiceThread();
        if (!isSystemAudioOn()) {
            return;
        }

        // Remove existing volume action.
        removeAction(VolumeControlAction.class);
        HdmiCecDeviceInfo avr = getAvrDeviceInfo();
        addAndStartAction(VolumeControlAction.ofMute(this, avr.getLogicalAddress(), mute));
    }

    private boolean isSystemAudioOn() {
        if (getAvrDeviceInfo() == null) {
            return false;
        }

        synchronized (mLock) {
            return mSystemAudioMode;
        }
    }

    @Override
    @ServiceThreadOnly
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
    @ServiceThreadOnly
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
    @ServiceThreadOnly
    protected boolean handleSetSystemAudioMode(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!isMessageForSystemAudio(message)) {
            return false;
        }
        SystemAudioActionFromAvr action = new SystemAudioActionFromAvr(this,
                message.getSource(), HdmiUtils.parseCommandParamSystemAudioStatus(message), null);
        addAndStartAction(action);
        return true;
    }

    @Override
    @ServiceThreadOnly
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
    @ServiceThreadOnly
    HdmiCecDeviceInfo addDeviceInfo(HdmiCecDeviceInfo deviceInfo) {
        assertRunOnServiceThread();
        HdmiCecDeviceInfo oldDeviceInfo = getDeviceInfo(deviceInfo.getLogicalAddress());
        if (oldDeviceInfo != null) {
            removeDeviceInfo(deviceInfo.getLogicalAddress());
        }
        mDeviceInfos.append(deviceInfo.getLogicalAddress(), deviceInfo);
        updateSafeDeviceInfoList();
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
    @ServiceThreadOnly
    HdmiCecDeviceInfo removeDeviceInfo(int logicalAddress) {
        assertRunOnServiceThread();
        HdmiCecDeviceInfo deviceInfo = mDeviceInfos.get(logicalAddress);
        if (deviceInfo != null) {
            mDeviceInfos.remove(logicalAddress);
        }
        updateSafeDeviceInfoList();
        return deviceInfo;
    }

    /**
     * Return a list of all {@link HdmiCecDeviceInfo}.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     * This is not thread-safe. For thread safety, call {@link #getSafeDeviceInfoList(boolean)}.
     */
    @ServiceThreadOnly
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

    /**
     * Return a list of  {@link HdmiCecDeviceInfo}.
     *
     * @param includeLocalDevice whether to include local device in result.
     */
    List<HdmiCecDeviceInfo> getSafeDeviceInfoList(boolean includeLocalDevice) {
        synchronized (mLock) {
            if (includeLocalDevice) {
                return mSafeAllDeviceInfos;
            } else {
                return mSafeExternalDeviceInfos;
            }
        }
    }

    @ServiceThreadOnly
    private void updateSafeDeviceInfoList() {
        assertRunOnServiceThread();
        List<HdmiCecDeviceInfo> copiedDevices = HdmiUtils.sparseArrayToList(mDeviceInfos);
        List<HdmiCecDeviceInfo> externalDeviceInfos = getDeviceInfoList(false);
        synchronized (mLock) {
            mSafeAllDeviceInfos = copiedDevices;
            mSafeExternalDeviceInfos = externalDeviceInfos;
        }
    }

    @ServiceThreadOnly
    private boolean isLocalDeviceAddress(int address) {
        assertRunOnServiceThread();
        for (HdmiCecLocalDevice device : mService.getAllLocalDevices()) {
            if (device.isAddressOf(address)) {
                return true;
            }
        }
        return false;
    }

    @ServiceThreadOnly
    HdmiCecDeviceInfo getAvrDeviceInfo() {
        assertRunOnServiceThread();
        return getDeviceInfo(HdmiCec.ADDR_AUDIO_SYSTEM);
    }

    /**
     * Return a {@link HdmiCecDeviceInfo} corresponding to the given {@code logicalAddress}.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     * This is not thread-safe. For thread safety, call {@link #getSafeDeviceInfo(int)}.
     *
     * @param logicalAddress logical address to be retrieved
     * @return {@link HdmiCecDeviceInfo} matched with the given {@code logicalAddress}.
     *         Returns null if no logical address matched
     */
    @ServiceThreadOnly
    HdmiCecDeviceInfo getDeviceInfo(int logicalAddress) {
        assertRunOnServiceThread();
        return mDeviceInfos.get(logicalAddress);
    }

    boolean hasSystemAudioDevice() {
        return getSafeAvrDeviceInfo() != null;
    }

    HdmiCecDeviceInfo getSafeAvrDeviceInfo() {
        return getSafeDeviceInfo(HdmiCec.ADDR_AUDIO_SYSTEM);
    }

    /**
     * Thread safe version of {@link #getDeviceInfo(int)}.
     *
     * @param logicalAddress logical address to be retrieved
     * @return {@link HdmiCecDeviceInfo} matched with the given {@code logicalAddress}.
     *         Returns null if no logical address matched
     */
    HdmiCecDeviceInfo getSafeDeviceInfo(int logicalAddress) {
        synchronized (mLock) {
            return mSafeAllDeviceInfos.get(logicalAddress);
        }
    }

    /**
     * Called when a device is newly added or a new device is detected.
     *
     * @param info device info of a new device.
     */
    @ServiceThreadOnly
    final void addCecDevice(HdmiCecDeviceInfo info) {
        assertRunOnServiceThread();
        addDeviceInfo(info);
        if (info.getLogicalAddress() == mAddress) {
            // The addition of TV device itself should not be notified.
            return;
        }
        mService.invokeDeviceEventListeners(info, true);
    }

    /**
     * Called when a device is removed or removal of device is detected.
     *
     * @param address a logical address of a device to be removed
     */
    @ServiceThreadOnly
    final void removeCecDevice(int address) {
        assertRunOnServiceThread();
        HdmiCecDeviceInfo info = removeDeviceInfo(address);
        handleRemoveActiveRoutingPath(info.getPhysicalAddress());
        mCecMessageCache.flushMessagesFrom(address);
        mService.invokeDeviceEventListeners(info, false);
    }

    private void handleRemoveActiveRoutingPath(int path) {
        // Seq #23
        if (isTailOfActivePath(path, getActivePath())) {
            removeAction(RoutingControlAction.class);
            int newPath = mService.portIdToPath(getActivePortId());
            mService.sendCecCommand(HdmiCecMessageBuilder.buildRoutingChange(
                    mAddress, getActivePath(), newPath));
            addAndStartAction(new RoutingControlAction(this, getActivePortId(), null));
        }
    }

    @ServiceThreadOnly
    void routingAtEnableTime() {
        assertRunOnServiceThread();
        // Seq #24
        if (getActivePortId() != HdmiConstants.INVALID_PORT_ID) {
            // TODO: Check if TV was not powered on due to <Text/Image View On>,
            //       TV is not in Preset Installation mode, not in initial setup mode, not
            //       in Software updating mode, not in service mode, for following actions.
            removeAction(RoutingControlAction.class);
            int newPath = mService.portIdToPath(getActivePortId());
            mService.sendCecCommand(
                    HdmiCecMessageBuilder.buildRoutingChange(mAddress, getActivePath(), newPath));
            addAndStartAction(new RoutingControlAction(this, getActivePortId(), null));
        } else {
            int activePath = mService.getPhysicalAddress();
            setActivePath(activePath);
            // TODO: Do following only when TV was not powered on due to <Text/Image View On>.
            mService.sendCecCommand(HdmiCecMessageBuilder.buildActiveSource(mAddress, activePath));
        }
    }

    /**
     * Returns the {@link HdmiCecDeviceInfo} instance whose physical address matches
     * the given routing path. CEC devices use routing path for its physical address to
     * describe the hierarchy of the devices in the network.
     *
     * @param path routing path or physical address
     * @return {@link HdmiCecDeviceInfo} if the matched info is found; otherwise null
     */
    @ServiceThreadOnly
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
     * @param logicalAddress logical address of a device to be searched
     * @param physicalAddress physical address of a device to be searched
     * @return true if exist; otherwise false
     */
    @ServiceThreadOnly
    boolean isInDeviceList(int logicalAddress, int physicalAddress) {
        assertRunOnServiceThread();
        HdmiCecDeviceInfo device = getDeviceInfo(logicalAddress);
        if (device == null) {
            return false;
        }
        return device.getPhysicalAddress() == physicalAddress;
    }

    @Override
    @ServiceThreadOnly
    void onHotplug(int portId, boolean connected) {
        assertRunOnServiceThread();

        // Tv device will have permanent HotplugDetectionAction.
        List<HotplugDetectionAction> hotplugActions = getActions(HotplugDetectionAction.class);
        if (!hotplugActions.isEmpty()) {
            // Note that hotplug action is single action running on a machine.
            // "pollAllDevicesNow" cleans up timer and start poll action immediately.
            hotplugActions.get(0).pollAllDevicesNow();
        }
    }
}
