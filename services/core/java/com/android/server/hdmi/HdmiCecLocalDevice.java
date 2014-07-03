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
import android.os.Looper;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Class that models a logical CEC device hosted in this system. Handles initialization,
 * CEC commands that call for actions customized per device type.
 */
abstract class HdmiCecLocalDevice {
    private static final String TAG = "HdmiCecLocalDevice";

    protected final HdmiControlService mService;
    protected final int mDeviceType;
    protected int mAddress;
    protected int mPreferredAddress;
    protected HdmiCecDeviceInfo mDeviceInfo;

    // Logical address of the active source.
    @GuardedBy("mLock")
    private int mActiveSource;

    // Active routing path. Physical address of the active source but not all the time, such as
    // when the new active source does not claim itself to be one. Note that we don't keep
    // the active port id (or active input) since it can be gotten by {@link #pathToPortId(int)}.
    @GuardedBy("mLock")
    private int mActiveRoutingPath;

    protected final HdmiCecMessageCache mCecMessageCache = new HdmiCecMessageCache();
    protected final Object mLock;

    // A collection of FeatureAction.
    // Note that access to this collection should happen in service thread.
    private final LinkedList<FeatureAction> mActions = new LinkedList<>();

    protected HdmiCecLocalDevice(HdmiControlService service, int deviceType) {
        mService = service;
        mDeviceType = deviceType;
        mAddress = HdmiCec.ADDR_UNREGISTERED;
        mLock = service.getServiceLock();
    }

    // Factory method that returns HdmiCecLocalDevice of corresponding type.
    static HdmiCecLocalDevice create(HdmiControlService service, int deviceType) {
        switch (deviceType) {
        case HdmiCec.DEVICE_TV:
            return new HdmiCecLocalDeviceTv(service);
        case HdmiCec.DEVICE_PLAYBACK:
            return new HdmiCecLocalDevicePlayback(service);
        default:
            return null;
        }
    }

    @ServiceThreadOnly
    void init() {
        assertRunOnServiceThread();
        mPreferredAddress = HdmiCec.ADDR_UNREGISTERED;
        // TODO: load preferred address from permanent storage.
    }

    /**
     * Called once a logical address of the local device is allocated.
     */
    protected abstract void onAddressAllocated(int logicalAddress);

    /**
     * Dispatch incoming message.
     *
     * @param message incoming message
     * @return true if consumed a message; otherwise, return false.
     */
    @ServiceThreadOnly
    final boolean dispatchMessage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int dest = message.getDestination();
        if (dest != mAddress && dest != HdmiCec.ADDR_BROADCAST) {
            return false;
        }
        // Cache incoming message. Note that it caches only white-listed one.
        mCecMessageCache.cacheMessage(message);
        return onMessage(message);
    }

    @ServiceThreadOnly
    protected final boolean onMessage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (dispatchMessageToAction(message)) {
            return true;
        }
        switch (message.getOpcode()) {
            case HdmiCec.MESSAGE_ACTIVE_SOURCE:
                return handleActiveSource(message);
            case HdmiCec.MESSAGE_INACTIVE_SOURCE:
                return handleInactiveSource(message);
            case HdmiCec.MESSAGE_REQUEST_ACTIVE_SOURCE:
                return handleRequestActiveSource(message);
            case HdmiCec.MESSAGE_GET_MENU_LANGUAGE:
                return handleGetMenuLanguage(message);
            case HdmiCec.MESSAGE_GIVE_PHYSICAL_ADDRESS:
                return handleGivePhysicalAddress();
            case HdmiCec.MESSAGE_GIVE_OSD_NAME:
                return handleGiveOsdName(message);
            case HdmiCec.MESSAGE_GIVE_DEVICE_VENDOR_ID:
                return handleGiveDeviceVendorId();
            case HdmiCec.MESSAGE_GET_CEC_VERSION:
                return handleGetCecVersion(message);
            case HdmiCec.MESSAGE_REPORT_PHYSICAL_ADDRESS:
                return handleReportPhysicalAddress(message);
            case HdmiCec.MESSAGE_ROUTING_CHANGE:
                return handleRoutingChange(message);
            case HdmiCec.MESSAGE_INITIATE_ARC:
                return handleInitiateArc(message);
            case HdmiCec.MESSAGE_TERMINATE_ARC:
                return handleTerminateArc(message);
            case HdmiCec.MESSAGE_SET_SYSTEM_AUDIO_MODE:
                return handleSetSystemAudioMode(message);
            case HdmiCec.MESSAGE_SYSTEM_AUDIO_MODE_STATUS:
                return handleSystemAudioModeStatus(message);
            case HdmiCec.MESSAGE_REPORT_AUDIO_STATUS:
                return handleReportAudioStatus(message);
            case HdmiCec.MESSAGE_STANDBY:
                return handleStandby(message);
            case HdmiCec.MESSAGE_TEXT_VIEW_ON:
                return handleTextViewOn(message);
            case HdmiCec.MESSAGE_IMAGE_VIEW_ON:
                return handleImageViewOn(message);
            case HdmiCec.MESSAGE_USER_CONTROL_PRESSED:
                return handleUserControlPressed(message);
            case HdmiCec.MESSAGE_SET_STREAM_PATH:
                return handleSetStreamPath(message);
            case HdmiCec.MESSAGE_GIVE_DEVICE_POWER_STATUS:
                return handleGiveDevicePowerStatus(message);
            default:
                return false;
        }
    }

    @ServiceThreadOnly
    private boolean dispatchMessageToAction(HdmiCecMessage message) {
        assertRunOnServiceThread();
        for (FeatureAction action : mActions) {
            if (action.processCommand(message)) {
                return true;
            }
        }
        return false;
    }

    @ServiceThreadOnly
    protected boolean handleGivePhysicalAddress() {
        assertRunOnServiceThread();

        int physicalAddress = mService.getPhysicalAddress();
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                mAddress, physicalAddress, mDeviceType);
        mService.sendCecCommand(cecMessage);
        return true;
    }

    @ServiceThreadOnly
    protected boolean handleGiveDeviceVendorId() {
        assertRunOnServiceThread();
        int vendorId = mService.getVendorId();
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildDeviceVendorIdCommand(
                mAddress, vendorId);
        mService.sendCecCommand(cecMessage);
        return true;
    }

    @ServiceThreadOnly
    protected boolean handleGetCecVersion(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int version = mService.getCecVersion();
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildCecVersion(message.getDestination(),
                message.getSource(), version);
        mService.sendCecCommand(cecMessage);
        return true;
    }

    @ServiceThreadOnly
    protected boolean handleActiveSource(HdmiCecMessage message) {
        return false;
    }

    @ServiceThreadOnly
    protected boolean handleInactiveSource(HdmiCecMessage message) {
        return false;
    }

    @ServiceThreadOnly
    protected boolean handleRequestActiveSource(HdmiCecMessage message) {
        return false;
    }

    @ServiceThreadOnly
    protected boolean handleGetMenuLanguage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        Slog.w(TAG, "Only TV can handle <Get Menu Language>:" + message.toString());
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildFeatureAbortCommand(mAddress,
                        message.getSource(), HdmiCec.MESSAGE_GET_MENU_LANGUAGE,
                        HdmiConstants.ABORT_UNRECOGNIZED_MODE));
        return true;
    }

    @ServiceThreadOnly
    protected boolean handleGiveOsdName(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Note that since this method is called after logical address allocation is done,
        // mDeviceInfo should not be null.
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildSetOsdNameCommand(
                mAddress, message.getSource(), mDeviceInfo.getDisplayName());
        if (cecMessage != null) {
            mService.sendCecCommand(cecMessage);
        } else {
            Slog.w(TAG, "Failed to build <Get Osd Name>:" + mDeviceInfo.getDisplayName());
        }
        return true;
    }

    protected boolean handleVendorSpecificCommand(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleRoutingChange(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleReportPhysicalAddress(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleSystemAudioModeStatus(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleSetSystemAudioMode(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleTerminateArc(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleInitiateArc(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleReportAudioStatus(HdmiCecMessage message) {
        return false;
    }

    @ServiceThreadOnly
    protected boolean handleStandby(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Seq #12
        if (mService.isControlEnabled() && !mService.isProhibitMode()
                && mService.isPowerOnOrTransient()) {
            mService.standby();
            return true;
        }
        return false;
    }

    @ServiceThreadOnly
    protected boolean handleUserControlPressed(HdmiCecMessage message) {
        assertRunOnServiceThread();
        final int opCode = message.getOpcode();
        final byte[] params = message.getParams();
        if (mService.isPowerOnOrTransient() && isPowerOffOrToggleCommand(message)) {
            mService.standby();
            return true;
        } else if (mService.isPowerStandbyOrTransient() && isPowerOnOrToggleCommand(message)) {
            mService.wakeUp();
            return true;
        }
        return false;
    }

    private static boolean isPowerOnOrToggleCommand(HdmiCecMessage message) {
        byte[] params = message.getParams();
        return message.getOpcode() == HdmiCec.MESSAGE_USER_CONTROL_PRESSED && params.length == 1
                && (params[0] == HdmiConstants.UI_COMMAND_POWER
                        || params[0] == HdmiConstants.UI_COMMAND_POWER_ON_FUNCTION
                        || params[0] == HdmiConstants.UI_COMMAND_POWER_TOGGLE_FUNCTION);
    }

    private static boolean isPowerOffOrToggleCommand(HdmiCecMessage message) {
        byte[] params = message.getParams();
        return message.getOpcode() == HdmiCec.MESSAGE_USER_CONTROL_PRESSED && params.length == 1
                && (params[0] == HdmiConstants.UI_COMMAND_POWER
                        || params[0] == HdmiConstants.UI_COMMAND_POWER_OFF_FUNCTION
                        || params[0] == HdmiConstants.UI_COMMAND_POWER_TOGGLE_FUNCTION);
    }

    protected boolean handleTextViewOn(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleImageViewOn(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleSetStreamPath(HdmiCecMessage message) {
        return false;
    }

    protected boolean handleGiveDevicePowerStatus(HdmiCecMessage message) {
        mService.sendCecCommand(HdmiCecMessageBuilder.buildReportPowerStatus(
                mAddress, message.getSource(), mService.getPowerStatus()));
        return true;
    }

    @ServiceThreadOnly
    final void handleAddressAllocated(int logicalAddress) {
        assertRunOnServiceThread();
        mAddress = mPreferredAddress = logicalAddress;
        onAddressAllocated(logicalAddress);
    }

    @ServiceThreadOnly
    HdmiCecDeviceInfo getDeviceInfo() {
        assertRunOnServiceThread();
        return mDeviceInfo;
    }

    @ServiceThreadOnly
    void setDeviceInfo(HdmiCecDeviceInfo info) {
        assertRunOnServiceThread();
        mDeviceInfo = info;
    }

    // Returns true if the logical address is same as the argument.
    @ServiceThreadOnly
    boolean isAddressOf(int addr) {
        assertRunOnServiceThread();
        return addr == mAddress;
    }

    // Resets the logical address to unregistered(15), meaning the logical device is invalid.
    @ServiceThreadOnly
    void clearAddress() {
        assertRunOnServiceThread();
        mAddress = HdmiCec.ADDR_UNREGISTERED;
    }

    @ServiceThreadOnly
    void setPreferredAddress(int addr) {
        assertRunOnServiceThread();
        mPreferredAddress = addr;
    }

    @ServiceThreadOnly
    int getPreferredAddress() {
        assertRunOnServiceThread();
        return mPreferredAddress;
    }

    @ServiceThreadOnly
    void addAndStartAction(final FeatureAction action) {
        assertRunOnServiceThread();
        if (mService.isPowerStandbyOrTransient()) {
            Slog.w(TAG, "Skip the action during Standby: " + action);
            return;
        }
        mActions.add(action);
        action.start();
    }

    // See if we have an action of a given type in progress.
    @ServiceThreadOnly
    <T extends FeatureAction> boolean hasAction(final Class<T> clazz) {
        assertRunOnServiceThread();
        for (FeatureAction action : mActions) {
            if (action.getClass().equals(clazz)) {
                return true;
            }
        }
        return false;
    }

    // Returns all actions matched with given class type.
    @ServiceThreadOnly
    <T extends FeatureAction> List<T> getActions(final Class<T> clazz) {
        assertRunOnServiceThread();
        ArrayList<T> actions = new ArrayList<>();
        for (FeatureAction action : mActions) {
            if (action.getClass().equals(clazz)) {
                actions.add((T) action);
            }
        }
        return actions;
    }

    /**
     * Remove the given {@link FeatureAction} object from the action queue.
     *
     * @param action {@link FeatureAction} to remove
     */
    @ServiceThreadOnly
    void removeAction(final FeatureAction action) {
        assertRunOnServiceThread();
        mActions.remove(action);
        checkIfPendingActionsCleared();
    }

    // Remove all actions matched with the given Class type.
    @ServiceThreadOnly
    <T extends FeatureAction> void removeAction(final Class<T> clazz) {
        assertRunOnServiceThread();
        removeActionExcept(clazz, null);
    }

    // Remove all actions matched with the given Class type besides |exception|.
    @ServiceThreadOnly
    <T extends FeatureAction> void removeActionExcept(final Class<T> clazz,
            final FeatureAction exception) {
        assertRunOnServiceThread();
        Iterator<FeatureAction> iter = mActions.iterator();
        while (iter.hasNext()) {
            FeatureAction action = iter.next();
            if (action != exception && action.getClass().equals(clazz)) {
                action.clear();
                mActions.remove(action);
            }
        }
        checkIfPendingActionsCleared();
    }

    protected void checkIfPendingActionsCleared() {
        if (mActions.isEmpty()) {
            mService.onPendingActionsCleared();
        }
    }
    protected void assertRunOnServiceThread() {
        if (Looper.myLooper() != mService.getServiceLooper()) {
            throw new IllegalStateException("Should run on service thread.");
        }
    }

    /**
     * Called when a hot-plug event issued.
     *
     * @param portId id of port where a hot-plug event happened
     * @param connected whether to connected or not on the event
     */
    void onHotplug(int portId, boolean connected) {
    }

    final HdmiControlService getService() {
        return mService;
    }

    @ServiceThreadOnly
    final boolean isConnectedToArcPort(int path) {
        assertRunOnServiceThread();
        return mService.isConnectedToArcPort(path);
    }

    int getActiveSource() {
        synchronized (mLock) {
            return mActiveSource;
        }
    }

    void setActiveSource(int source) {
        synchronized (mLock) {
            mActiveSource = source;
        }
    }

    int getActivePath() {
        synchronized (mLock) {
            return mActiveRoutingPath;
        }
    }

    void setActivePath(int path) {
        synchronized (mLock) {
            mActiveRoutingPath = path;
        }
    }

    /**
     * Returns the ID of the active HDMI port. The active port is the one that has the active
     * routing path connected to it directly or indirectly under the device hierarchy.
     */
    int getActivePortId() {
        synchronized (mLock) {
            return mService.pathToPortId(mActiveRoutingPath);
        }
    }

    /**
     * Update the active port.
     *
     * @param portId the new active port id
     */
    void setActivePortId(int portId) {
        synchronized (mLock) {
            // We update active routing path instead, since we get the active port id from
            // the active routing path.
            mActiveRoutingPath = mService.portIdToPath(portId);
        }
    }

    void updateActiveDevice(int logicalAddress, int physicalAddress) {
        synchronized (mLock) {
            mActiveSource = logicalAddress;
            mActiveRoutingPath = physicalAddress;
        }
    }

    @ServiceThreadOnly
    HdmiCecMessageCache getCecMessageCache() {
        assertRunOnServiceThread();
        return mCecMessageCache;
    }

    @ServiceThreadOnly
    int pathToPortId(int newPath) {
        assertRunOnServiceThread();
        return mService.pathToPortId(newPath);
    }

    /**
     * Called when the system started transition to standby mode.
     *
     * @param initiatedByCec true if this power sequence is initiated
     *         by the reception the CEC messages like <StandBy>
     */
    protected void onTransitionToStandby(boolean initiatedByCec) {
        // If there are no outstanding actions, we'll go to STANDBY state.
        checkIfPendingActionsCleared();
    }

    /**
     * Called when the system goes to standby mode.
     *
     * @param initiatedByCec true if this power sequence is initiated
     *         by the reception the CEC messages like <StandBy>
     */
    protected void onStandBy(boolean initiatedByCec) {}
}
