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

import static android.hardware.hdmi.HdmiControlManager.CLEAR_TIMER_STATUS_CEC_DISABLE;
import static android.hardware.hdmi.HdmiControlManager.CLEAR_TIMER_STATUS_CHECK_RECORDER_CONNECTION;
import static android.hardware.hdmi.HdmiControlManager.CLEAR_TIMER_STATUS_FAIL_TO_CLEAR_SELECTED_SOURCE;
import static android.hardware.hdmi.HdmiControlManager.ONE_TOUCH_RECORD_CEC_DISABLED;
import static android.hardware.hdmi.HdmiControlManager.ONE_TOUCH_RECORD_CHECK_RECORDER_CONNECTION;
import static android.hardware.hdmi.HdmiControlManager.ONE_TOUCH_RECORD_FAIL_TO_RECORD_DISPLAYED_SCREEN;
import static android.hardware.hdmi.HdmiControlManager.OSD_MESSAGE_ARC_CONNECTED_INVALID_PORT;
import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_RESULT_EXTRA_CEC_DISABLED;
import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_RESULT_EXTRA_CHECK_RECORDER_CONNECTION;
import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_RESULT_EXTRA_FAIL_TO_RECORD_SELECTED_SOURCE;
import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_TYPE_ANALOGUE;
import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_TYPE_DIGITAL;
import static android.hardware.hdmi.HdmiControlManager.TIMER_RECORDING_TYPE_EXTERNAL;

import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.hdmi.HdmiRecordSources;
import android.hardware.hdmi.HdmiTimerRecordSources;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager.TvInputCallback;
import android.os.RemoteException;
import android.provider.Settings.Global;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.hdmi.DeviceDiscoveryAction.DeviceDiscoveryCallback;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;
import com.android.server.hdmi.HdmiControlService.SendMessageCallback;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * Represent a logical device of type TV residing in Android system.
 */
final class HdmiCecLocalDeviceTv extends HdmiCecLocalDevice {
    private static final String TAG = "HdmiCecLocalDeviceTv";

    // Whether ARC is available or not. "true" means that ARC is established between TV and
    // AVR as audio receiver.
    @ServiceThreadOnly
    private boolean mArcEstablished = false;

    // Stores whether ARC feature is enabled per port.
    // True by default for all the ARC-enabled ports.
    private final SparseBooleanArray mArcFeatureEnabled = new SparseBooleanArray();

    // Whether System audio mode is activated or not.
    // This becomes true only when all system audio sequences are finished.
    @GuardedBy("mLock")
    private boolean mSystemAudioActivated = false;

    // Whether the System Audio Control feature is enabled or not. True by default.
    @GuardedBy("mLock")
    private boolean mSystemAudioControlFeatureEnabled;

    // The previous port id (input) before switching to the new one. This is remembered in order to
    // be able to switch to it upon receiving <Inactive Source> from currently active source.
    // This remains valid only when the active source was switched via one touch play operation
    // (either by TV or source device). Manual port switching invalidates this value to
    // Constants.PORT_INVALID, for which case <Inactive Source> does not do anything.
    @GuardedBy("mLock")
    private int mPrevPortId;

    @GuardedBy("mLock")
    private int mSystemAudioVolume = Constants.UNKNOWN_VOLUME;

    @GuardedBy("mLock")
    private boolean mSystemAudioMute = false;

    // Copy of mDeviceInfos to guarantee thread-safety.
    @GuardedBy("mLock")
    private List<HdmiDeviceInfo> mSafeAllDeviceInfos = Collections.emptyList();
    // All external cec input(source) devices. Does not include system audio device.
    @GuardedBy("mLock")
    private List<HdmiDeviceInfo> mSafeExternalInputs = Collections.emptyList();

    // Map-like container of all cec devices including local ones.
    // device id is used as key of container.
    // This is not thread-safe. For external purpose use mSafeDeviceInfos.
    private final SparseArray<HdmiDeviceInfo> mDeviceInfos = new SparseArray<>();

    // If true, TV going to standby mode puts other devices also to standby.
    private boolean mAutoDeviceOff;

    // If true, TV wakes itself up when receiving <Text/Image View On>.
    private boolean mAutoWakeup;

    // List of the logical address of local CEC devices. Unmodifiable, thread-safe.
    private List<Integer> mLocalDeviceAddresses;

    private final HdmiCecStandbyModeHandler mStandbyHandler;

    // If true, do not do routing control/send active source for internal source.
    // Set to true when the device was woken up by <Text/Image View On>.
    private boolean mSkipRoutingControl;

    // Set of physical addresses of CEC switches on the CEC bus. Managed independently from
    // other CEC devices since they might not have logical address.
    private final ArraySet<Integer> mCecSwitches = new ArraySet<Integer>();

    // Message buffer used to buffer selected messages to process later. <Active Source>
    // from a source device, for instance, needs to be buffered if the device is not
    // discovered yet. The buffered commands are taken out and when they are ready to
    // handle.
    private final DelayedMessageBuffer mDelayedMessageBuffer = new DelayedMessageBuffer(this);

    // Defines the callback invoked when TV input framework is updated with input status.
    // We are interested in the notification for HDMI input addition event, in order to
    // process any CEC commands that arrived before the input is added.
    private final TvInputCallback mTvInputCallback = new TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            TvInputInfo tvInfo = mService.getTvInputManager().getTvInputInfo(inputId);
            if (tvInfo == null) return;
            HdmiDeviceInfo info = tvInfo.getHdmiDeviceInfo();
            if (info == null) return;
            addTvInput(inputId, info.getId());
            if (info.isCecDevice()) {
                processDelayedActiveSource(info.getLogicalAddress());
            }
        }

        @Override
        public void onInputRemoved(String inputId) {
            removeTvInput(inputId);
        }
    };

    // Keeps the mapping (TV input ID, HDMI device ID) to keep track of the TV inputs ready to
    // accept input switching request from HDMI devices. Requests for which the corresponding
    // input ID is not yet registered by TV input framework need to be buffered for delayed
    // processing.
    private final HashMap<String, Integer> mTvInputs = new HashMap<>();

    @ServiceThreadOnly
    private void addTvInput(String inputId, int deviceId) {
        assertRunOnServiceThread();
        mTvInputs.put(inputId, deviceId);
    }

    @ServiceThreadOnly
    private void removeTvInput(String inputId) {
        assertRunOnServiceThread();
        mTvInputs.remove(inputId);
    }

    @Override
    @ServiceThreadOnly
    protected boolean isInputReady(int deviceId) {
        assertRunOnServiceThread();
        return mTvInputs.containsValue(deviceId);
    }

    private SelectRequestBuffer mSelectRequestBuffer;

    HdmiCecLocalDeviceTv(HdmiControlService service) {
        super(service, HdmiDeviceInfo.DEVICE_TV);
        mPrevPortId = Constants.INVALID_PORT_ID;
        mAutoDeviceOff = mService.readBooleanSetting(Global.HDMI_CONTROL_AUTO_DEVICE_OFF_ENABLED,
                true);
        mAutoWakeup = mService.readBooleanSetting(Global.HDMI_CONTROL_AUTO_WAKEUP_ENABLED, true);
        mSystemAudioControlFeatureEnabled =
                mService.readBooleanSetting(Global.HDMI_SYSTEM_AUDIO_CONTROL_ENABLED, true);
        mStandbyHandler = new HdmiCecStandbyModeHandler(service, this);
    }

    @Override
    @ServiceThreadOnly
    protected void onAddressAllocated(int logicalAddress, int reason) {
        assertRunOnServiceThread();
        List<HdmiPortInfo> ports = mService.getPortInfo();
        for (HdmiPortInfo port : ports) {
            mArcFeatureEnabled.put(port.getId(), port.isArcSupported());
        }
        mService.registerTvInputCallback(mTvInputCallback);
        mService.sendCecCommand(HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                mAddress, mService.getPhysicalAddress(), mDeviceType));
        mService.sendCecCommand(HdmiCecMessageBuilder.buildDeviceVendorIdCommand(
                mAddress, mService.getVendorId()));
        mCecSwitches.add(mService.getPhysicalAddress());  // TV is a CEC switch too.
        mTvInputs.clear();
        mSkipRoutingControl = (reason == HdmiControlService.INITIATED_BY_WAKE_UP_MESSAGE);
        launchRoutingControl(reason != HdmiControlService.INITIATED_BY_ENABLE_CEC &&
                reason != HdmiControlService.INITIATED_BY_BOOT_UP);
        mLocalDeviceAddresses = initLocalDeviceAddresses();
        resetSelectRequestBuffer();
        launchDeviceDiscovery();
    }


    @ServiceThreadOnly
    private List<Integer> initLocalDeviceAddresses() {
        assertRunOnServiceThread();
        List<Integer> addresses = new ArrayList<>();
        for (HdmiCecLocalDevice device : mService.getAllLocalDevices()) {
            addresses.add(device.getDeviceInfo().getLogicalAddress());
        }
        return Collections.unmodifiableList(addresses);
    }


    @ServiceThreadOnly
    public void setSelectRequestBuffer(SelectRequestBuffer requestBuffer) {
        assertRunOnServiceThread();
        mSelectRequestBuffer = requestBuffer;
    }

    @ServiceThreadOnly
    private void resetSelectRequestBuffer() {
        assertRunOnServiceThread();
        setSelectRequestBuffer(SelectRequestBuffer.EMPTY_BUFFER);
    }

    @Override
    protected int getPreferredAddress() {
        return Constants.ADDR_TV;
    }

    @Override
    protected void setPreferredAddress(int addr) {
        Slog.w(TAG, "Preferred addres will not be stored for TV");
    }

    @Override
    @ServiceThreadOnly
    boolean dispatchMessage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (mService.isPowerStandby() && !mService.isWakeUpMessageReceived()
                && mStandbyHandler.handleCommand(message)) {
            return true;
        }
        return super.onMessage(message);
    }

    /**
     * Performs the action 'device select', or 'one touch play' initiated by TV.
     *
     * @param id id of HDMI device to select
     * @param callback callback object to report the result with
     */
    @ServiceThreadOnly
    void deviceSelect(int id, IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        HdmiDeviceInfo targetDevice = mDeviceInfos.get(id);
        if (targetDevice == null) {
            invokeCallback(callback, HdmiControlManager.RESULT_TARGET_NOT_AVAILABLE);
            return;
        }
        int targetAddress = targetDevice.getLogicalAddress();
        ActiveSource active = getActiveSource();
        if (targetDevice.getDevicePowerStatus() == HdmiControlManager.POWER_STATUS_ON
                && active.isValid()
                && targetAddress == active.logicalAddress) {
            invokeCallback(callback, HdmiControlManager.RESULT_SUCCESS);
            return;
        }
        if (targetAddress == Constants.ADDR_INTERNAL) {
            handleSelectInternalSource();
            // Switching to internal source is always successful even when CEC control is disabled.
            setActiveSource(targetAddress, mService.getPhysicalAddress());
            setActivePath(mService.getPhysicalAddress());
            invokeCallback(callback, HdmiControlManager.RESULT_SUCCESS);
            return;
        }
        if (!mService.isControlEnabled()) {
            setActiveSource(targetDevice);
            invokeCallback(callback, HdmiControlManager.RESULT_INCORRECT_MODE);
            return;
        }
        removeAction(DeviceSelectAction.class);
        addAndStartAction(new DeviceSelectAction(this, targetDevice, callback));
    }

    @ServiceThreadOnly
    private void handleSelectInternalSource() {
        assertRunOnServiceThread();
        // Seq #18
        if (mService.isControlEnabled() && mActiveSource.logicalAddress != mAddress) {
            updateActiveSource(mAddress, mService.getPhysicalAddress());
            if (mSkipRoutingControl) {
                mSkipRoutingControl = false;
                return;
            }
            HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                    mAddress, mService.getPhysicalAddress());
            mService.sendCecCommand(activeSource);
        }
    }

    @ServiceThreadOnly
    void updateActiveSource(int logicalAddress, int physicalAddress) {
        assertRunOnServiceThread();
        updateActiveSource(ActiveSource.of(logicalAddress, physicalAddress));
    }

    @ServiceThreadOnly
    void updateActiveSource(ActiveSource newActive) {
        assertRunOnServiceThread();
        // Seq #14
        if (mActiveSource.equals(newActive)) {
            return;
        }
        setActiveSource(newActive);
        int logicalAddress = newActive.logicalAddress;
        if (getCecDeviceInfo(logicalAddress) != null && logicalAddress != mAddress) {
            if (mService.pathToPortId(newActive.physicalAddress) == getActivePortId()) {
                setPrevPortId(getActivePortId());
            }
            // TODO: Show the OSD banner related to the new active source device.
        } else {
            // TODO: If displayed, remove the OSD banner related to the previous
            //       active source device.
        }
    }

    int getPortId(int physicalAddress) {
        return mService.pathToPortId(physicalAddress);
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
    void updateActiveInput(int path, boolean notifyInputChange) {
        assertRunOnServiceThread();
        // Seq #15
        setActivePath(path);
        // TODO: Handle PAP/PIP case.
        // Show OSD port change banner
        if (notifyInputChange) {
            ActiveSource activeSource = getActiveSource();
            HdmiDeviceInfo info = getCecDeviceInfo(activeSource.logicalAddress);
            if (info == null) {
                info = mService.getDeviceInfoByPort(getActivePortId());
                if (info == null) {
                    // No CEC/MHL device is present at the port. Attempt to switch to
                    // the hardware port itself for non-CEC devices that may be connected.
                    info = new HdmiDeviceInfo(path, getActivePortId());
                }
            }
            mService.invokeInputChangeListener(info);
        }
    }

    @ServiceThreadOnly
    void doManualPortSwitching(int portId, IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        // Seq #20
        if (!mService.isValidPortId(portId)) {
            invokeCallback(callback, HdmiControlManager.RESULT_INCORRECT_MODE);
            return;
        }
        if (portId == getActivePortId()) {
            invokeCallback(callback, HdmiControlManager.RESULT_SUCCESS);
            return;
        }
        mActiveSource.invalidate();
        if (!mService.isControlEnabled()) {
            setActivePortId(portId);
            invokeCallback(callback, HdmiControlManager.RESULT_INCORRECT_MODE);
            return;
        }
        int oldPath = getActivePortId() != Constants.INVALID_PORT_ID
                ? mService.portIdToPath(getActivePortId()) : getDeviceInfo().getPhysicalAddress();
        setActivePath(oldPath);
        if (mSkipRoutingControl) {
            mSkipRoutingControl = false;
            return;
        }
        int newPath = mService.portIdToPath(portId);
        startRoutingControl(oldPath, newPath, true, callback);
    }

    @ServiceThreadOnly
    void startRoutingControl(int oldPath, int newPath, boolean queryDevicePowerStatus,
            IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        if (oldPath == newPath) {
            return;
        }
        HdmiCecMessage routingChange =
                HdmiCecMessageBuilder.buildRoutingChange(mAddress, oldPath, newPath);
        mService.sendCecCommand(routingChange);
        removeAction(RoutingControlAction.class);
        addAndStartAction(
                new RoutingControlAction(this, newPath, queryDevicePowerStatus, callback));
    }

    @ServiceThreadOnly
    int getPowerStatus() {
        assertRunOnServiceThread();
        return mService.getPowerStatus();
    }

    @Override
    protected int findKeyReceiverAddress() {
        if (getActiveSource().isValid()) {
            return getActiveSource().logicalAddress;
        }
        HdmiDeviceInfo info = getDeviceInfoByPath(getActivePath());
        if (info != null) {
            return info.getLogicalAddress();
        }
        return Constants.ADDR_INVALID;
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
        int logicalAddress = message.getSource();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        HdmiDeviceInfo info = getCecDeviceInfo(logicalAddress);
        if (info == null) {
            if (!handleNewDeviceAtTheTailOfActivePath(physicalAddress)) {
                HdmiLogger.debug("Device info %X not found; buffering the command", logicalAddress);
                mDelayedMessageBuffer.add(message);
            }
        } else if (isInputReady(info.getId())
                || info.getDeviceType() == HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM) {
            updateDevicePowerStatus(logicalAddress, HdmiControlManager.POWER_STATUS_ON);
            ActiveSource activeSource = ActiveSource.of(logicalAddress, physicalAddress);
            ActiveSourceHandler.create(this, null).process(activeSource, info.getDeviceType());
        } else {
            HdmiLogger.debug("Input not ready for device: %X; buffering the command", info.getId());
            mDelayedMessageBuffer.add(message);
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleInactiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Seq #10

        // Ignore <Inactive Source> from non-active source device.
        if (getActiveSource().logicalAddress != message.getSource()) {
            return true;
        }
        if (isProhibitMode()) {
            return true;
        }
        int portId = getPrevPortId();
        if (portId != Constants.INVALID_PORT_ID) {
            // TODO: Do this only if TV is not showing multiview like PIP/PAP.

            HdmiDeviceInfo inactiveSource = getCecDeviceInfo(message.getSource());
            if (inactiveSource == null) {
                return true;
            }
            if (mService.pathToPortId(inactiveSource.getPhysicalAddress()) == portId) {
                return true;
            }
            // TODO: Switch the TV freeze mode off

            doManualPortSwitching(portId, null);
            setPrevPortId(Constants.INVALID_PORT_ID);
        } else {
            // No HDMI port to switch to was found. Notify the input change listers to
            // switch to the lastly shown internal input.
            mActiveSource.invalidate();
            setActivePath(Constants.INVALID_PHYSICAL_ADDRESS);
            mService.invokeInputChangeListener(HdmiDeviceInfo.INACTIVE_DEVICE);
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRequestActiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Seq #19
        if (mAddress == getActiveSource().logicalAddress) {
            mService.sendCecCommand(
                    HdmiCecMessageBuilder.buildActiveSource(mAddress, getActivePath()));
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleGetMenuLanguage(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!broadcastMenuLanguage(mService.getLanguage())) {
            Slog.w(TAG, "Failed to respond to <Get Menu Language>: " + message.toString());
        }
        return true;
    }

    @ServiceThreadOnly
    boolean broadcastMenuLanguage(String language) {
        assertRunOnServiceThread();
        HdmiCecMessage command = HdmiCecMessageBuilder.buildSetMenuLanguageCommand(
                mAddress, language);
        if (command != null) {
            mService.sendCecCommand(command);
            return true;
        }
        return false;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleReportPhysicalAddress(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int path = HdmiUtils.twoBytesToInt(message.getParams());
        int address = message.getSource();
        int type = message.getParams()[2];

        if (updateCecSwitchInfo(address, type, path)) return true;

        // Ignore if [Device Discovery Action] is going on.
        if (hasAction(DeviceDiscoveryAction.class)) {
            Slog.i(TAG, "Ignored while Device Discovery Action is in progress: " + message);
            return true;
        }

        if (!isInDeviceList(address, path)) {
            handleNewDeviceAtTheTailOfActivePath(path);
        }

        // Add the device ahead with default information to handle <Active Source>
        // promptly, rather than waiting till the new device action is finished.
        HdmiDeviceInfo deviceInfo = new HdmiDeviceInfo(address, path, getPortId(path), type,
                Constants.UNKNOWN_VENDOR_ID, HdmiUtils.getDefaultDeviceName(address));
        addCecDevice(deviceInfo);
        startNewDeviceAction(ActiveSource.of(address, path), type);
        return true;
    }

    @Override
    protected boolean handleReportPowerStatus(HdmiCecMessage command) {
        int newStatus = command.getParams()[0] & 0xFF;
        updateDevicePowerStatus(command.getSource(), newStatus);
        return true;
    }

    @Override
    protected boolean handleTimerStatus(HdmiCecMessage message) {
        // Do nothing.
        return true;
    }

    @Override
    protected boolean handleRecordStatus(HdmiCecMessage message) {
        // Do nothing.
        return true;
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

    void startNewDeviceAction(ActiveSource activeSource, int deviceType) {
        for (NewDeviceAction action : getActions(NewDeviceAction.class)) {
            // If there is new device action which has the same logical address and path
            // ignore new request.
            // NewDeviceAction is created whenever it receives <Report Physical Address>.
            // And there is a chance starting NewDeviceAction for the same source.
            // Usually, new device sends <Report Physical Address> when it's plugged
            // in. However, TV can detect a new device from HotPlugDetectionAction,
            // which sends <Give Physical Address> to the source for newly detected
            // device.
            if (action.isActionOf(activeSource)) {
                return;
            }
        }

        addAndStartAction(new NewDeviceAction(this, activeSource.logicalAddress,
                activeSource.physicalAddress, deviceType));
    }

    private boolean handleNewDeviceAtTheTailOfActivePath(int path) {
        // Seq #22
        if (isTailOfActivePath(path, getActivePath())) {
            int newPath = mService.portIdToPath(getActivePortId());
            setActivePath(newPath);
            startRoutingControl(getActivePath(), newPath, false, null);
            return true;
        }
        return false;
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
        int currentPath = HdmiUtils.twoBytesToInt(params);
        if (HdmiUtils.isAffectingActiveRoutingPath(getActivePath(), currentPath)) {
            mActiveSource.invalidate();
            removeAction(RoutingControlAction.class);
            int newPath = HdmiUtils.twoBytesToInt(params, 2);
            addAndStartAction(new RoutingControlAction(this, newPath, true, null));
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleReportAudioStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();

        byte params[] = message.getParams();
        int mute = params[0] & 0x80;
        int volume = params[0] & 0x7F;
        setAudioStatus(mute == 0x80, volume);
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleTextViewOn(HdmiCecMessage message) {
        assertRunOnServiceThread();

        // Note that <Text View On> (and <Image View On>) command won't be handled here in
        // most cases. A dedicated microcontroller should be in charge while Android system
        // is in sleep mode, and the command need not be passed up to this service.
        // The only situation where the command reaches this handler is that sleep mode is
        // implemented in such a way that Android system is not really put to standby mode
        // but only the display is set to blank. Then the command leads to the effect of
        // turning on the display by the invocation of PowerManager.wakeUp().
        if (mService.isPowerStandbyOrTransient() && mAutoWakeup) {
            mService.wakeUp();
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleImageViewOn(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // Currently, it's the same as <Text View On>.
        return handleTextViewOn(message);
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleSetOsdName(HdmiCecMessage message) {
        int source = message.getSource();
        HdmiDeviceInfo deviceInfo = getCecDeviceInfo(source);
        // If the device is not in device list, ignore it.
        if (deviceInfo == null) {
            Slog.e(TAG, "No source device info for <Set Osd Name>." + message);
            return true;
        }
        String osdName = null;
        try {
            osdName = new String(message.getParams(), "US-ASCII");
        } catch (UnsupportedEncodingException e) {
            Slog.e(TAG, "Invalid <Set Osd Name> request:" + message, e);
            return true;
        }

        if (deviceInfo.getDisplayName().equals(osdName)) {
            Slog.i(TAG, "Ignore incoming <Set Osd Name> having same osd name:" + message);
            return true;
        }

        addCecDevice(new HdmiDeviceInfo(deviceInfo.getLogicalAddress(),
                deviceInfo.getPhysicalAddress(), deviceInfo.getPortId(),
                deviceInfo.getDeviceType(), deviceInfo.getVendorId(), osdName));
        return true;
    }

    @ServiceThreadOnly
    private void launchDeviceDiscovery() {
        assertRunOnServiceThread();
        clearDeviceInfoList();
        DeviceDiscoveryAction action = new DeviceDiscoveryAction(this,
                new DeviceDiscoveryCallback() {
                    @Override
                    public void onDeviceDiscoveryDone(List<HdmiDeviceInfo> deviceInfos) {
                        for (HdmiDeviceInfo info : deviceInfos) {
                            addCecDevice(info);
                        }

                        // Since we removed all devices when it's start and
                        // device discovery action does not poll local devices,
                        // we should put device info of local device manually here
                        for (HdmiCecLocalDevice device : mService.getAllLocalDevices()) {
                            addCecDevice(device.getDeviceInfo());
                        }

                        mSelectRequestBuffer.process();
                        resetSelectRequestBuffer();

                        addAndStartAction(new HotplugDetectionAction(HdmiCecLocalDeviceTv.this));
                        addAndStartAction(new PowerStatusMonitorAction(HdmiCecLocalDeviceTv.this));

                        HdmiDeviceInfo avr = getAvrDeviceInfo();
                        if (avr != null) {
                            onNewAvrAdded(avr);
                        } else {
                            setSystemAudioMode(false);
                        }
                    }
                });
        addAndStartAction(action);
    }

    @ServiceThreadOnly
    void onNewAvrAdded(HdmiDeviceInfo avr) {
        assertRunOnServiceThread();
        addAndStartAction(new SystemAudioAutoInitiationAction(this, avr.getLogicalAddress()));
        if (isConnected(avr.getPortId()) && isArcFeatureEnabled(avr.getPortId())
                && !hasAction(SetArcTransmissionStateAction.class)) {
            startArcAction(true);
        }
    }

    // Clear all device info.
    @ServiceThreadOnly
    private void clearDeviceInfoList() {
        assertRunOnServiceThread();
        for (HdmiDeviceInfo info : mSafeExternalInputs) {
            invokeDeviceEventListener(info, HdmiControlManager.DEVICE_EVENT_REMOVE_DEVICE);
        }
        mDeviceInfos.clear();
        updateSafeDeviceInfoList();
    }

    @ServiceThreadOnly
    // Seq #32
    void changeSystemAudioMode(boolean enabled, IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        if (!mService.isControlEnabled() || hasAction(DeviceDiscoveryAction.class)) {
            setSystemAudioMode(false);
            invokeCallback(callback, HdmiControlManager.RESULT_INCORRECT_MODE);
            return;
        }
        HdmiDeviceInfo avr = getAvrDeviceInfo();
        if (avr == null) {
            setSystemAudioMode(false);
            invokeCallback(callback, HdmiControlManager.RESULT_TARGET_NOT_AVAILABLE);
            return;
        }

        addAndStartAction(
                new SystemAudioActionFromTv(this, avr.getLogicalAddress(), enabled, callback));
    }

    // # Seq 25
    void setSystemAudioMode(boolean on) {
        if (!isSystemAudioControlFeatureEnabled() && on) {
            HdmiLogger.debug("Cannot turn on system audio mode "
                    + "because the System Audio Control feature is disabled.");
            return;
        }
        HdmiLogger.debug("System Audio Mode change[old:%b new:%b]", mSystemAudioActivated, on);
        updateAudioManagerForSystemAudio(on);
        synchronized (mLock) {
            if (mSystemAudioActivated != on) {
                mSystemAudioActivated = on;
                mService.announceSystemAudioModeChange(on);
            }
            startArcAction(on);
        }
    }

    private void updateAudioManagerForSystemAudio(boolean on) {
        int device = mService.getAudioManager().setHdmiSystemAudioSupported(on);
        HdmiLogger.debug("[A]UpdateSystemAudio mode[on=%b] output=[%X]", on, device);
    }

    boolean isSystemAudioActivated() {
        if (!hasSystemAudioDevice()) {
            return false;
        }
        synchronized (mLock) {
            return mSystemAudioActivated;
        }
    }

    @ServiceThreadOnly
    void setSystemAudioControlFeatureEnabled(boolean enabled) {
        assertRunOnServiceThread();
        synchronized (mLock) {
            mSystemAudioControlFeatureEnabled = enabled;
        }
        if (hasSystemAudioDevice()) {
            changeSystemAudioMode(enabled, null);
        }
    }

    boolean isSystemAudioControlFeatureEnabled() {
        synchronized (mLock) {
            return mSystemAudioControlFeatureEnabled;
        }
    }

    /**
     * Change ARC status into the given {@code enabled} status.
     *
     * @return {@code true} if ARC was in "Enabled" status
     */
    @ServiceThreadOnly
    boolean setArcStatus(boolean enabled) {
        assertRunOnServiceThread();

        HdmiLogger.debug("Set Arc Status[old:%b new:%b]", mArcEstablished, enabled);
        boolean oldStatus = mArcEstablished;
        // 1. Enable/disable ARC circuit.
        enableAudioReturnChannel(enabled);
        // 2. Notify arc status to audio service.
        notifyArcStatusToAudioService(enabled);
        // 3. Update arc status;
        mArcEstablished = enabled;
        return oldStatus;
    }

    /**
     * Switch hardware ARC circuit in the system.
     */
    @ServiceThreadOnly
    void enableAudioReturnChannel(boolean enabled) {
        assertRunOnServiceThread();
        HdmiDeviceInfo avr = getAvrDeviceInfo();
        if (avr != null) {
            mService.enableAudioReturnChannel(avr.getPortId(), enabled);
        }
    }

    @ServiceThreadOnly
    boolean isConnected(int portId) {
        assertRunOnServiceThread();
        return mService.isConnected(portId);
    }

    private void notifyArcStatusToAudioService(boolean enabled) {
        // Note that we don't set any name to ARC.
        mService.getAudioManager().setWiredDeviceConnectionState(
                AudioSystem.DEVICE_OUT_HDMI_ARC,
                enabled ? 1 : 0, "", "");
    }

    /**
     * Returns true if ARC is currently established on a certain port.
     */
    @ServiceThreadOnly
    boolean isArcEstablished() {
        assertRunOnServiceThread();
        if (mArcEstablished) {
            for (int i = 0; i < mArcFeatureEnabled.size(); i++) {
                if (mArcFeatureEnabled.valueAt(i)) return true;
            }
        }
        return false;
    }

    @ServiceThreadOnly
    void changeArcFeatureEnabled(int portId, boolean enabled) {
        assertRunOnServiceThread();
        if (mArcFeatureEnabled.get(portId) == enabled) {
            return;
        }
        mArcFeatureEnabled.put(portId, enabled);
        HdmiDeviceInfo avr = getAvrDeviceInfo();
        if (avr == null || avr.getPortId() != portId) {
            return;
        }
        if (enabled && !mArcEstablished) {
            startArcAction(true);
        } else if (!enabled && mArcEstablished) {
            startArcAction(false);
        }
    }

    @ServiceThreadOnly
    boolean isArcFeatureEnabled(int portId) {
        assertRunOnServiceThread();
        return mArcFeatureEnabled.get(portId);
    }

    @ServiceThreadOnly
    void startArcAction(boolean enabled) {
        assertRunOnServiceThread();
        HdmiDeviceInfo info = getAvrDeviceInfo();
        if (info == null) {
            Slog.w(TAG, "Failed to start arc action; No AVR device.");
            return;
        }
        if (!canStartArcUpdateAction(info.getLogicalAddress(), enabled)) {
            Slog.w(TAG, "Failed to start arc action; ARC configuration check failed.");
            if (enabled && !isConnectedToArcPort(info.getPhysicalAddress())) {
                displayOsd(OSD_MESSAGE_ARC_CONNECTED_INVALID_PORT);
            }
            return;
        }

        // Terminate opposite action and start action if not exist.
        if (enabled) {
            removeAction(RequestArcTerminationAction.class);
            if (!hasAction(RequestArcInitiationAction.class)) {
                addAndStartAction(new RequestArcInitiationAction(this, info.getLogicalAddress()));
            }
        } else {
            removeAction(RequestArcInitiationAction.class);
            if (!hasAction(RequestArcTerminationAction.class)) {
                addAndStartAction(new RequestArcTerminationAction(this, info.getLogicalAddress()));
            }
        }
    }

    private boolean isDirectConnectAddress(int physicalAddress) {
        return (physicalAddress & Constants.ROUTING_PATH_TOP_MASK) == physicalAddress;
    }

    void setAudioStatus(boolean mute, int volume) {
        synchronized (mLock) {
            mSystemAudioMute = mute;
            mSystemAudioVolume = volume;
            int maxVolume = mService.getAudioManager().getStreamMaxVolume(
                    AudioManager.STREAM_MUSIC);
            mService.setAudioStatus(mute,
                    VolumeControlAction.scaleToCustomVolume(volume, maxVolume));
            displayOsd(HdmiControlManager.OSD_MESSAGE_AVR_VOLUME_CHANGED,
                    mute ? HdmiControlManager.AVR_VOLUME_MUTED : volume);
        }
    }

    @ServiceThreadOnly
    void changeVolume(int curVolume, int delta, int maxVolume) {
        assertRunOnServiceThread();
        if (delta == 0 || !isSystemAudioActivated()) {
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

        List<VolumeControlAction> actions = getActions(VolumeControlAction.class);
        if (actions.isEmpty()) {
            addAndStartAction(new VolumeControlAction(this,
                    getAvrDeviceInfo().getLogicalAddress(), delta > 0));
        } else {
            actions.get(0).handleVolumeChange(delta > 0);
        }
    }

    @ServiceThreadOnly
    void changeMute(boolean mute) {
        assertRunOnServiceThread();
        HdmiLogger.debug("[A]:Change mute:%b", mute);
        synchronized (mLock) {
            if (mSystemAudioMute == mute) {
                HdmiLogger.debug("No need to change mute.");
                return;
            }
        }
        if (!isSystemAudioActivated()) {
            HdmiLogger.debug("[A]:System audio is not activated.");
            return;
        }

        // Remove existing volume action.
        removeAction(VolumeControlAction.class);
        sendUserControlPressedAndReleased(getAvrDeviceInfo().getLogicalAddress(),
                HdmiCecKeycode.getMuteKey(mute));
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleInitiateArc(HdmiCecMessage message) {
        assertRunOnServiceThread();

        if (!canStartArcUpdateAction(message.getSource(), true)) {
            if (getAvrDeviceInfo() == null) {
                // AVR may not have been discovered yet. Delay the message processing.
                mDelayedMessageBuffer.add(message);
                return true;
            }
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_REFUSED);
            if (!isConnectedToArcPort(message.getSource())) {
                displayOsd(OSD_MESSAGE_ARC_CONNECTED_INVALID_PORT);
            }
            return true;
        }

        // In case where <Initiate Arc> is started by <Request ARC Initiation>
        // need to clean up RequestArcInitiationAction.
        removeAction(RequestArcInitiationAction.class);
        SetArcTransmissionStateAction action = new SetArcTransmissionStateAction(this,
                message.getSource(), true);
        addAndStartAction(action);
        return true;
    }

    private boolean canStartArcUpdateAction(int avrAddress, boolean enabled) {
        HdmiDeviceInfo avr = getAvrDeviceInfo();
        if (avr != null
                && (avrAddress == avr.getLogicalAddress())
                && isConnectedToArcPort(avr.getPhysicalAddress())
                && isDirectConnectAddress(avr.getPhysicalAddress())) {
            if (enabled) {
                return isConnected(avr.getPortId()) && isArcFeatureEnabled(avr.getPortId());
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleTerminateArc(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (mService .isPowerStandbyOrTransient()) {
            setArcStatus(false);
            return true;
        }
        // Do not check ARC configuration since the AVR might have been already removed.
        // Clean up RequestArcTerminationAction in case <Terminate Arc> was started by
        // <Request ARC Termination>.
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
        boolean systemAudioStatus = HdmiUtils.parseCommandParamSystemAudioStatus(message);
        if (!isMessageForSystemAudio(message)) {
            if (getAvrDeviceInfo() == null) {
                // AVR may not have been discovered yet. Delay the message processing.
                mDelayedMessageBuffer.add(message);
            } else {
                HdmiLogger.warning("Invalid <Set System Audio Mode> message:" + message);
                mService.maySendFeatureAbortCommand(message, Constants.ABORT_REFUSED);
            }
            return true;
        } else if (systemAudioStatus && !isSystemAudioControlFeatureEnabled()) {
            HdmiLogger.debug("Ignoring <Set System Audio Mode> message "
                    + "because the System Audio Control feature is disabled: %s", message);
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_REFUSED);
            return true;
        }
        removeAction(SystemAudioAutoInitiationAction.class);
        SystemAudioActionFromAvr action = new SystemAudioActionFromAvr(this,
                message.getSource(), systemAudioStatus, null);
        addAndStartAction(action);
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleSystemAudioModeStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!isMessageForSystemAudio(message)) {
            HdmiLogger.warning("Invalid <System Audio Mode Status> message:" + message);
            // Ignore this message.
            return true;
        }
        setSystemAudioMode(HdmiUtils.parseCommandParamSystemAudioStatus(message));
        return true;
    }

    // Seq #53
    @Override
    @ServiceThreadOnly
    protected boolean handleRecordTvScreen(HdmiCecMessage message) {
        List<OneTouchRecordAction> actions = getActions(OneTouchRecordAction.class);
        if (!actions.isEmpty()) {
            // Assumes only one OneTouchRecordAction.
            OneTouchRecordAction action = actions.get(0);
            if (action.getRecorderAddress() != message.getSource()) {
                announceOneTouchRecordResult(
                        message.getSource(),
                        HdmiControlManager.ONE_TOUCH_RECORD_PREVIOUS_RECORDING_IN_PROGRESS);
            }
            return super.handleRecordTvScreen(message);
        }

        int recorderAddress = message.getSource();
        byte[] recordSource = mService.invokeRecordRequestListener(recorderAddress);
        int reason = startOneTouchRecord(recorderAddress, recordSource);
        if (reason != Constants.ABORT_NO_ERROR) {
            mService.maySendFeatureAbortCommand(message, reason);
        }
        return true;
    }

    @Override
    protected boolean handleTimerClearedStatus(HdmiCecMessage message) {
        byte[] params = message.getParams();
        int timerClearedStatusData = params[0] & 0xFF;
        announceTimerRecordingResult(message.getSource(), timerClearedStatusData);
        return true;
    }

    void announceOneTouchRecordResult(int recorderAddress, int result) {
        mService.invokeOneTouchRecordResult(recorderAddress, result);
    }

    void announceTimerRecordingResult(int recorderAddress, int result) {
        mService.invokeTimerRecordingResult(recorderAddress, result);
    }

    void announceClearTimerRecordingResult(int recorderAddress, int result) {
        mService.invokeClearTimerRecordingResult(recorderAddress, result);
    }

    private boolean isMessageForSystemAudio(HdmiCecMessage message) {
        return mService.isControlEnabled()
                && message.getSource() == Constants.ADDR_AUDIO_SYSTEM
                && (message.getDestination() == Constants.ADDR_TV
                        || message.getDestination() == Constants.ADDR_BROADCAST)
                && getAvrDeviceInfo() != null;
    }

    /**
     * Add a new {@link HdmiDeviceInfo}. It returns old device info which has the same
     * logical address as new device info's.
     *
     * <p>Declared as package-private. accessed by {@link HdmiControlService} only.
     *
     * @param deviceInfo a new {@link HdmiDeviceInfo} to be added.
     * @return {@code null} if it is new device. Otherwise, returns old {@HdmiDeviceInfo}
     *         that has the same logical address as new one has.
     */
    @ServiceThreadOnly
    private HdmiDeviceInfo addDeviceInfo(HdmiDeviceInfo deviceInfo) {
        assertRunOnServiceThread();
        HdmiDeviceInfo oldDeviceInfo = getCecDeviceInfo(deviceInfo.getLogicalAddress());
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
    List<HdmiDeviceInfo> getSafeExternalInputsLocked() {
        return mSafeExternalInputs;
    }

    @ServiceThreadOnly
    private void updateSafeDeviceInfoList() {
        assertRunOnServiceThread();
        List<HdmiDeviceInfo> copiedDevices = HdmiUtils.sparseArrayToList(mDeviceInfos);
        List<HdmiDeviceInfo> externalInputs = getInputDevices();
        synchronized (mLock) {
            mSafeAllDeviceInfos = copiedDevices;
            mSafeExternalInputs = externalInputs;
        }
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
    // Returns true if the policy is set to true, and the device to check does not have
    // a parent CEC device (which should be the CEC-enabled switch) in the list.
    private boolean hideDevicesBehindLegacySwitch(HdmiDeviceInfo info) {
        return HdmiConfig.HIDE_DEVICES_BEHIND_LEGACY_SWITCH
                && !isConnectedToCecSwitch(info.getPhysicalAddress(), mCecSwitches);
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
                return parentNibble == 0 && (childPath >> i+4) == (parentPath >> i+4);
            }
        }
        return false;
    }

    private void invokeDeviceEventListener(HdmiDeviceInfo info, int status) {
        if (!hideDevicesBehindLegacySwitch(info)) {
            mService.invokeDeviceEventListeners(info, status);
        }
    }

    private boolean isLocalDeviceAddress(int address) {
        return mLocalDeviceAddresses.contains(address);
    }

    @ServiceThreadOnly
    HdmiDeviceInfo getAvrDeviceInfo() {
        assertRunOnServiceThread();
        return getCecDeviceInfo(Constants.ADDR_AUDIO_SYSTEM);
    }

    /**
     * Return a {@link HdmiDeviceInfo} corresponding to the given {@code logicalAddress}.
     *
     * This is not thread-safe. For thread safety, call {@link #getSafeCecDeviceInfo(int)}.
     *
     * @param logicalAddress logical address of the device to be retrieved
     * @return {@link HdmiDeviceInfo} matched with the given {@code logicalAddress}.
     *         Returns null if no logical address matched
     */
    @ServiceThreadOnly
    HdmiDeviceInfo getCecDeviceInfo(int logicalAddress) {
        assertRunOnServiceThread();
        return mDeviceInfos.get(HdmiDeviceInfo.idForCecDevice(logicalAddress));
    }

    boolean hasSystemAudioDevice() {
        return getSafeAvrDeviceInfo() != null;
    }

    HdmiDeviceInfo getSafeAvrDeviceInfo() {
        return getSafeCecDeviceInfo(Constants.ADDR_AUDIO_SYSTEM);
    }

    /**
     * Thread safe version of {@link #getCecDeviceInfo(int)}.
     *
     * @param logicalAddress logical address to be retrieved
     * @return {@link HdmiDeviceInfo} matched with the given {@code logicalAddress}.
     *         Returns null if no logical address matched
     */
    HdmiDeviceInfo getSafeCecDeviceInfo(int logicalAddress) {
        synchronized (mLock) {
            for (HdmiDeviceInfo info : mSafeAllDeviceInfos) {
                if (info.isCecDevice() && info.getLogicalAddress() == logicalAddress) {
                    return info;
                }
            }
            return null;
        }
    }

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
     * Called when a device is newly added or a new device is detected or
     * existing device is updated.
     *
     * @param info device info of a new device.
     */
    @ServiceThreadOnly
    final void addCecDevice(HdmiDeviceInfo info) {
        assertRunOnServiceThread();
        HdmiDeviceInfo old = addDeviceInfo(info);
        if (info.getLogicalAddress() == mAddress) {
            // The addition of TV device itself should not be notified.
            return;
        }
        if (old == null) {
            invokeDeviceEventListener(info, HdmiControlManager.DEVICE_EVENT_ADD_DEVICE);
        } else if (!old.equals(info)) {
            invokeDeviceEventListener(old, HdmiControlManager.DEVICE_EVENT_REMOVE_DEVICE);
            invokeDeviceEventListener(info, HdmiControlManager.DEVICE_EVENT_ADD_DEVICE);
        }
    }

    /**
     * Called when a device is removed or removal of device is detected.
     *
     * @param address a logical address of a device to be removed
     */
    @ServiceThreadOnly
    final void removeCecDevice(int address) {
        assertRunOnServiceThread();
        HdmiDeviceInfo info = removeDeviceInfo(HdmiDeviceInfo.idForCecDevice(address));

        mCecMessageCache.flushMessagesFrom(address);
        invokeDeviceEventListener(info, HdmiControlManager.DEVICE_EVENT_REMOVE_DEVICE);
    }

    @ServiceThreadOnly
    void handleRemoveActiveRoutingPath(int path) {
        assertRunOnServiceThread();
        // Seq #23
        if (isTailOfActivePath(path, getActivePath())) {
            int newPath = mService.portIdToPath(getActivePortId());
            startRoutingControl(getActivePath(), newPath, true, null);
        }
    }

    /**
     * Launch routing control process.
     *
     * @param routingForBootup true if routing control is initiated due to One Touch Play
     *        or TV power on
     */
    @ServiceThreadOnly
    void launchRoutingControl(boolean routingForBootup) {
        assertRunOnServiceThread();
        // Seq #24
        if (getActivePortId() != Constants.INVALID_PORT_ID) {
            if (!routingForBootup && !isProhibitMode()) {
                int newPath = mService.portIdToPath(getActivePortId());
                setActivePath(newPath);
                startRoutingControl(getActivePath(), newPath, routingForBootup, null);
            }
        } else {
            int activePath = mService.getPhysicalAddress();
            setActivePath(activePath);
            if (!routingForBootup
                    && !mDelayedMessageBuffer.isBuffered(Constants.MESSAGE_ACTIVE_SOURCE)) {
                mService.sendCecCommand(HdmiCecMessageBuilder.buildActiveSource(mAddress,
                        activePath));
            }
        }
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
        synchronized (mLock) {
            for (HdmiDeviceInfo info : mSafeAllDeviceInfos) {
                if (info.getPhysicalAddress() == path) {
                    return info;
                }
            }
            return null;
        }
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
        HdmiDeviceInfo device = getCecDeviceInfo(logicalAddress);
        if (device == null) {
            return false;
        }
        return device.getPhysicalAddress() == physicalAddress;
    }

    @Override
    @ServiceThreadOnly
    void onHotplug(int portId, boolean connected) {
        assertRunOnServiceThread();

        if (!connected) {
            removeCecSwitches(portId);
        }
        // Tv device will have permanent HotplugDetectionAction.
        List<HotplugDetectionAction> hotplugActions = getActions(HotplugDetectionAction.class);
        if (!hotplugActions.isEmpty()) {
            // Note that hotplug action is single action running on a machine.
            // "pollAllDevicesNow" cleans up timer and start poll action immediately.
            // It covers seq #40, #43.
            hotplugActions.get(0).pollAllDevicesNow();
        }
    }

    private void removeCecSwitches(int portId) {
        Iterator<Integer> it = mCecSwitches.iterator();
        while (!it.hasNext()) {
            int path = it.next();
            if (pathToPortId(path) == portId) {
                it.remove();
            }
        }
    }

    @Override
    @ServiceThreadOnly
    void setAutoDeviceOff(boolean enabled) {
        assertRunOnServiceThread();
        mAutoDeviceOff = enabled;
    }

    @ServiceThreadOnly
    void setAutoWakeup(boolean enabled) {
        assertRunOnServiceThread();
        mAutoWakeup = enabled;
    }

    @ServiceThreadOnly
    boolean getAutoWakeup() {
        assertRunOnServiceThread();
        return mAutoWakeup;
    }

    @Override
    @ServiceThreadOnly
    protected void disableDevice(boolean initiatedByCec, PendingActionClearedCallback callback) {
        assertRunOnServiceThread();
        mService.unregisterTvInputCallback(mTvInputCallback);
        // Remove any repeated working actions.
        // HotplugDetectionAction will be reinstated during the wake up process.
        // HdmiControlService.onWakeUp() -> initializeLocalDevices() ->
        //     LocalDeviceTv.onAddressAllocated() -> launchDeviceDiscovery().
        removeAction(DeviceDiscoveryAction.class);
        removeAction(HotplugDetectionAction.class);
        removeAction(PowerStatusMonitorAction.class);
        // Remove recording actions.
        removeAction(OneTouchRecordAction.class);
        removeAction(TimerRecordingAction.class);

        disableSystemAudioIfExist();
        disableArcIfExist();

        super.disableDevice(initiatedByCec, callback);
        clearDeviceInfoList();
        getActiveSource().invalidate();
        setActivePath(Constants.INVALID_PHYSICAL_ADDRESS);
        checkIfPendingActionsCleared();
    }

    @ServiceThreadOnly
    private void disableSystemAudioIfExist() {
        assertRunOnServiceThread();
        if (getAvrDeviceInfo() == null) {
            return;
        }

        // Seq #31.
        removeAction(SystemAudioActionFromAvr.class);
        removeAction(SystemAudioActionFromTv.class);
        removeAction(SystemAudioAutoInitiationAction.class);
        removeAction(SystemAudioStatusAction.class);
        removeAction(VolumeControlAction.class);
    }

    @ServiceThreadOnly
    private void disableArcIfExist() {
        assertRunOnServiceThread();
        HdmiDeviceInfo avr = getAvrDeviceInfo();
        if (avr == null) {
            return;
        }

        // Seq #44.
        removeAction(RequestArcInitiationAction.class);
        if (!hasAction(RequestArcTerminationAction.class) && isArcEstablished()) {
            addAndStartAction(new RequestArcTerminationAction(this, avr.getLogicalAddress()));
        }
    }

    @Override
    @ServiceThreadOnly
    protected void onStandby(boolean initiatedByCec, int standbyAction) {
        assertRunOnServiceThread();
        // Seq #11
        if (!mService.isControlEnabled()) {
            return;
        }
        if (!initiatedByCec && mAutoDeviceOff) {
            mService.sendCecCommand(HdmiCecMessageBuilder.buildStandby(
                    mAddress, Constants.ADDR_BROADCAST));
        }
    }

    boolean isProhibitMode() {
        return mService.isProhibitMode();
    }

    boolean isPowerStandbyOrTransient() {
        return mService.isPowerStandbyOrTransient();
    }

    @ServiceThreadOnly
    void displayOsd(int messageId) {
        assertRunOnServiceThread();
        mService.displayOsd(messageId);
    }

    @ServiceThreadOnly
    void displayOsd(int messageId, int extra) {
        assertRunOnServiceThread();
        mService.displayOsd(messageId, extra);
    }

    // Seq #54 and #55
    @ServiceThreadOnly
    int startOneTouchRecord(int recorderAddress, byte[] recordSource) {
        assertRunOnServiceThread();
        if (!mService.isControlEnabled()) {
            Slog.w(TAG, "Can not start one touch record. CEC control is disabled.");
            announceOneTouchRecordResult(recorderAddress, ONE_TOUCH_RECORD_CEC_DISABLED);
            return Constants.ABORT_NOT_IN_CORRECT_MODE;
        }

        if (!checkRecorder(recorderAddress)) {
            Slog.w(TAG, "Invalid recorder address:" + recorderAddress);
            announceOneTouchRecordResult(recorderAddress,
                    ONE_TOUCH_RECORD_CHECK_RECORDER_CONNECTION);
            return Constants.ABORT_NOT_IN_CORRECT_MODE;
        }

        if (!checkRecordSource(recordSource)) {
            Slog.w(TAG, "Invalid record source." + Arrays.toString(recordSource));
            announceOneTouchRecordResult(recorderAddress,
                    ONE_TOUCH_RECORD_FAIL_TO_RECORD_DISPLAYED_SCREEN);
            return Constants.ABORT_CANNOT_PROVIDE_SOURCE;
        }

        addAndStartAction(new OneTouchRecordAction(this, recorderAddress, recordSource));
        Slog.i(TAG, "Start new [One Touch Record]-Target:" + recorderAddress + ", recordSource:"
                + Arrays.toString(recordSource));
        return Constants.ABORT_NO_ERROR;
    }

    @ServiceThreadOnly
    void stopOneTouchRecord(int recorderAddress) {
        assertRunOnServiceThread();
        if (!mService.isControlEnabled()) {
            Slog.w(TAG, "Can not stop one touch record. CEC control is disabled.");
            announceOneTouchRecordResult(recorderAddress, ONE_TOUCH_RECORD_CEC_DISABLED);
            return;
        }

        if (!checkRecorder(recorderAddress)) {
            Slog.w(TAG, "Invalid recorder address:" + recorderAddress);
            announceOneTouchRecordResult(recorderAddress,
                    ONE_TOUCH_RECORD_CHECK_RECORDER_CONNECTION);
            return;
        }

        // Remove one touch record action so that other one touch record can be started.
        removeAction(OneTouchRecordAction.class);
        mService.sendCecCommand(HdmiCecMessageBuilder.buildRecordOff(mAddress, recorderAddress));
        Slog.i(TAG, "Stop [One Touch Record]-Target:" + recorderAddress);
    }

    private boolean checkRecorder(int recorderAddress) {
        HdmiDeviceInfo device = getCecDeviceInfo(recorderAddress);
        return (device != null)
                && (HdmiUtils.getTypeFromAddress(recorderAddress)
                        == HdmiDeviceInfo.DEVICE_RECORDER);
    }

    private boolean checkRecordSource(byte[] recordSource) {
        return (recordSource != null) && HdmiRecordSources.checkRecordSource(recordSource);
    }

    @ServiceThreadOnly
    void startTimerRecording(int recorderAddress, int sourceType, byte[] recordSource) {
        assertRunOnServiceThread();
        if (!mService.isControlEnabled()) {
            Slog.w(TAG, "Can not start one touch record. CEC control is disabled.");
            announceTimerRecordingResult(recorderAddress,
                    TIMER_RECORDING_RESULT_EXTRA_CEC_DISABLED);
            return;
        }

        if (!checkRecorder(recorderAddress)) {
            Slog.w(TAG, "Invalid recorder address:" + recorderAddress);
            announceTimerRecordingResult(recorderAddress,
                    TIMER_RECORDING_RESULT_EXTRA_CHECK_RECORDER_CONNECTION);
            return;
        }

        if (!checkTimerRecordingSource(sourceType, recordSource)) {
            Slog.w(TAG, "Invalid record source." + Arrays.toString(recordSource));
            announceTimerRecordingResult(
                    recorderAddress,
                    TIMER_RECORDING_RESULT_EXTRA_FAIL_TO_RECORD_SELECTED_SOURCE);
            return;
        }

        addAndStartAction(
                new TimerRecordingAction(this, recorderAddress, sourceType, recordSource));
        Slog.i(TAG, "Start [Timer Recording]-Target:" + recorderAddress + ", SourceType:"
                + sourceType + ", RecordSource:" + Arrays.toString(recordSource));
    }

    private boolean checkTimerRecordingSource(int sourceType, byte[] recordSource) {
        return (recordSource != null)
                && HdmiTimerRecordSources.checkTimerRecordSource(sourceType, recordSource);
    }

    @ServiceThreadOnly
    void clearTimerRecording(int recorderAddress, int sourceType, byte[] recordSource) {
        assertRunOnServiceThread();
        if (!mService.isControlEnabled()) {
            Slog.w(TAG, "Can not start one touch record. CEC control is disabled.");
            announceClearTimerRecordingResult(recorderAddress, CLEAR_TIMER_STATUS_CEC_DISABLE);
            return;
        }

        if (!checkRecorder(recorderAddress)) {
            Slog.w(TAG, "Invalid recorder address:" + recorderAddress);
            announceClearTimerRecordingResult(recorderAddress,
                    CLEAR_TIMER_STATUS_CHECK_RECORDER_CONNECTION);
            return;
        }

        if (!checkTimerRecordingSource(sourceType, recordSource)) {
            Slog.w(TAG, "Invalid record source." + Arrays.toString(recordSource));
            announceClearTimerRecordingResult(recorderAddress,
                    CLEAR_TIMER_STATUS_FAIL_TO_CLEAR_SELECTED_SOURCE);
            return;
        }

        sendClearTimerMessage(recorderAddress, sourceType, recordSource);
    }

    private void sendClearTimerMessage(final int recorderAddress, int sourceType,
            byte[] recordSource) {
        HdmiCecMessage message = null;
        switch (sourceType) {
            case TIMER_RECORDING_TYPE_DIGITAL:
                message = HdmiCecMessageBuilder.buildClearDigitalTimer(mAddress, recorderAddress,
                        recordSource);
                break;
            case TIMER_RECORDING_TYPE_ANALOGUE:
                message = HdmiCecMessageBuilder.buildClearAnalogueTimer(mAddress, recorderAddress,
                        recordSource);
                break;
            case TIMER_RECORDING_TYPE_EXTERNAL:
                message = HdmiCecMessageBuilder.buildClearExternalTimer(mAddress, recorderAddress,
                        recordSource);
                break;
            default:
                Slog.w(TAG, "Invalid source type:" + recorderAddress);
                announceClearTimerRecordingResult(recorderAddress,
                        CLEAR_TIMER_STATUS_FAIL_TO_CLEAR_SELECTED_SOURCE);
                return;

        }
        mService.sendCecCommand(message, new SendMessageCallback() {
            @Override
            public void onSendCompleted(int error) {
                if (error != SendMessageResult.SUCCESS) {
                    announceClearTimerRecordingResult(recorderAddress,
                            CLEAR_TIMER_STATUS_FAIL_TO_CLEAR_SELECTED_SOURCE);
                }
            }
        });
    }

    void updateDevicePowerStatus(int logicalAddress, int newPowerStatus) {
        HdmiDeviceInfo info = getCecDeviceInfo(logicalAddress);
        if (info == null) {
            Slog.w(TAG, "Can not update power status of non-existing device:" + logicalAddress);
            return;
        }

        if (info.getDevicePowerStatus() == newPowerStatus) {
            return;
        }

        HdmiDeviceInfo newInfo = HdmiUtils.cloneHdmiDeviceInfo(info, newPowerStatus);
        // addDeviceInfo replaces old device info with new one if exists.
        addDeviceInfo(newInfo);

        invokeDeviceEventListener(newInfo, HdmiControlManager.DEVICE_EVENT_UPDATE_DEVICE);
    }

    @Override
    protected boolean handleMenuStatus(HdmiCecMessage message) {
        // Do nothing and just return true not to prevent from responding <Feature Abort>.
        return true;
    }

    @Override
    protected void sendStandby(int deviceId) {
        HdmiDeviceInfo targetDevice = mDeviceInfos.get(deviceId);
        if (targetDevice == null) {
            return;
        }
        int targetAddress = targetDevice.getLogicalAddress();
        mService.sendCecCommand(HdmiCecMessageBuilder.buildStandby(mAddress, targetAddress));
    }

    @ServiceThreadOnly
    void processAllDelayedMessages() {
        assertRunOnServiceThread();
        mDelayedMessageBuffer.processAllMessages();
    }

    @ServiceThreadOnly
    void processDelayedMessages(int address) {
        assertRunOnServiceThread();
        mDelayedMessageBuffer.processMessagesForDevice(address);
    }

    @ServiceThreadOnly
    void processDelayedActiveSource(int address) {
        assertRunOnServiceThread();
        mDelayedMessageBuffer.processActiveSource(address);
    }

    @Override
    protected void dump(final IndentingPrintWriter pw) {
        super.dump(pw);
        pw.println("mArcEstablished: " + mArcEstablished);
        pw.println("mArcFeatureEnabled: " + mArcFeatureEnabled);
        pw.println("mSystemAudioActivated: " + mSystemAudioActivated);
        pw.println("mSystemAudioMute: " + mSystemAudioMute);
        pw.println("mSystemAudioControlFeatureEnabled: " + mSystemAudioControlFeatureEnabled);
        pw.println("mAutoDeviceOff: " + mAutoDeviceOff);
        pw.println("mAutoWakeup: " + mAutoWakeup);
        pw.println("mSkipRoutingControl: " + mSkipRoutingControl);
        pw.println("mPrevPortId: " + mPrevPortId);
        pw.println("CEC devices:");
        pw.increaseIndent();
        for (HdmiDeviceInfo info : mSafeAllDeviceInfos) {
            pw.println(info);
        }
        pw.decreaseIndent();
    }
}
