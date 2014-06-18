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

    // Set to true while the service is in normal mode. While set to false, no input change is
    // allowed. Used for situations where input change can confuse users such as channel auto-scan,
    // system upgrade, etc., a.k.a. "prohibit mode".
    @GuardedBy("mLock")
    private boolean mInputChangeEnabled;

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

        // TODO: Get control flag from persistent storage
        mInputChangeEnabled = true;
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

    void init() {
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

    protected final boolean onMessage(HdmiCecMessage message) {
        assertRunOnServiceThread();

        if (dispatchMessageToAction(message)) {
            return true;
        }
        switch (message.getOpcode()) {
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
            case HdmiCec.MESSAGE_INITIATE_ARC:
                return handleInitiateArc(message);
            case HdmiCec.MESSAGE_TERMINATE_ARC:
                return handleTerminateArc(message);
            case HdmiCec.MESSAGE_SET_SYSTEM_AUDIO_MODE:
                return handleSetSystemAudioMode(message);
            case HdmiCec.MESSAGE_SYSTEM_AUDIO_MODE_STATUS:
                return handleSystemAudioModeStatus(message);
            default:
                return false;
        }
    }

    private boolean dispatchMessageToAction(HdmiCecMessage message) {
        for (FeatureAction action : mActions) {
            if (action.processCommand(message)) {
                return true;
            }
        }
        return false;
    }

    protected boolean handleGivePhysicalAddress() {
        assertRunOnServiceThread();

        int physicalAddress = mService.getPhysicalAddress();
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                mAddress, physicalAddress, mDeviceType);
        mService.sendCecCommand(cecMessage);
        return true;
    }

    protected boolean handleGiveDeviceVendorId() {
        assertRunOnServiceThread();

        int vendorId = mService.getVendorId();
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildDeviceVendorIdCommand(
                mAddress, vendorId);
        mService.sendCecCommand(cecMessage);
        return true;
    }

    protected boolean handleGetCecVersion(HdmiCecMessage message) {
        assertRunOnServiceThread();

        int version = mService.getCecVersion();
        HdmiCecMessage cecMessage = HdmiCecMessageBuilder.buildCecVersion(message.getDestination(),
                message.getSource(), version);
        mService.sendCecCommand(cecMessage);
        return true;
    }

    protected boolean handleGetMenuLanguage(HdmiCecMessage message) {
        assertRunOnServiceThread();

        Slog.w(TAG, "Only TV can handle <Get Menu Language>:" + message.toString());
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildFeatureAbortCommand(mAddress,
                        message.getSource(), HdmiCec.MESSAGE_GET_MENU_LANGUAGE,
                        HdmiConstants.ABORT_UNRECOGNIZED_MODE));
        return true;
    }

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

    final void handleAddressAllocated(int logicalAddress) {
        assertRunOnServiceThread();

        mAddress = mPreferredAddress = logicalAddress;
        onAddressAllocated(logicalAddress);
    }

    HdmiCecDeviceInfo getDeviceInfo() {
        assertRunOnServiceThread();
        return mDeviceInfo;
    }

    void setDeviceInfo(HdmiCecDeviceInfo info) {
        assertRunOnServiceThread();
        mDeviceInfo = info;
    }

    // Returns true if the logical address is same as the argument.
    boolean isAddressOf(int addr) {
        assertRunOnServiceThread();
        return addr == mAddress;
    }

    // Resets the logical address to unregistered(15), meaning the logical device is invalid.
    void clearAddress() {
        assertRunOnServiceThread();
        mAddress = HdmiCec.ADDR_UNREGISTERED;
    }

    void setPreferredAddress(int addr) {
        assertRunOnServiceThread();
        mPreferredAddress = addr;
    }

    int getPreferredAddress() {
        assertRunOnServiceThread();
        return mPreferredAddress;
    }

    void addAndStartAction(final FeatureAction action) {
        assertRunOnServiceThread();
        mActions.add(action);
        action.start();
    }

    // See if we have an action of a given type in progress.
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
    void removeAction(final FeatureAction action) {
        assertRunOnServiceThread();
        mActions.remove(action);
    }

    // Remove all actions matched with the given Class type.
    <T extends FeatureAction> void removeAction(final Class<T> clazz) {
        removeActionExcept(clazz, null);
    }

    // Remove all actions matched with the given Class type besides |exception|.
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

    final boolean isConnectedToArcPort(int path) {
        return mService.isConnectedToArcPort(path);
    }

    int getActiveSource() {
        synchronized (mLock) {
            return mActiveSource;
        }
    }

    /**
     * Returns the active routing path.
     */
    int getActivePath() {
        synchronized (mLock) {
            return mActiveRoutingPath;
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

    void setInputChangeEnabled(boolean enabled) {
        synchronized (mLock) {
            mInputChangeEnabled = enabled;
        }
    }

    boolean isInPresetInstallationMode() {
        synchronized (mLock) {
            return !mInputChangeEnabled;
        }
    }

    /**
     * Whether the given path is located in the tail of current active path.
     *
     * @param path to be tested
     * @return true if the given path is located in the tail of current active path; otherwise,
     *         false
     */
    // TODO: move this to local device tv.
    boolean isTailOfActivePath(int path) {
        synchronized (mLock) {
            // If active routing path is internal source, return false.
            if (mActiveRoutingPath == 0) {
                return false;
            }
            for (int i = 12; i >= 0; i -= 4) {
                int curActivePath = (mActiveRoutingPath >> i) & 0xF;
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
    }

    HdmiCecMessageCache getCecMessageCache() {
        assertRunOnServiceThread();
        return mCecMessageCache;
    }

    int pathToPortId(int newPath) {
        assertRunOnServiceThread();
        return mService.pathToPortId(newPath);
    }
}
