/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.CallSuper;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.sysprop.HdmiProperties;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.hdmi.Constants.LocalActivePort;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;

import java.util.ArrayList;
import java.util.List;

/**
 * Represent a logical source device residing in Android system.
 */
abstract class HdmiCecLocalDeviceSource extends HdmiCecLocalDevice {

    private static final String TAG = "HdmiCecLocalDeviceSource";

    // Device has cec switch functionality or not.
    // Default is false.
    protected boolean mIsSwitchDevice = HdmiProperties.is_switch().orElse(false);

    // Routing port number used for Routing Control.
    // This records the default routing port or the previous valid routing port.
    // Default is HOME input.
    // Note that we don't save active path here because for source device,
    // new Active Source physical address might not match the active path
    @GuardedBy("mLock")
    @LocalActivePort
    private int mRoutingPort = Constants.CEC_SWITCH_HOME;

    // This records the current input of the device.
    // When device is switched to ARC input, mRoutingPort does not record it
    // since it's not an HDMI port used for Routing Control.
    // mLocalActivePort will record whichever input we switch to to keep tracking on
    // the current input status of the device.
    // This can help prevent duplicate switching and provide status information.
    @GuardedBy("mLock")
    @LocalActivePort
    protected int mLocalActivePort = Constants.CEC_SWITCH_HOME;

    // Whether the Routing Coutrol feature is enabled or not. False by default.
    @GuardedBy("mLock")
    protected boolean mRoutingControlFeatureEnabled;

    protected HdmiCecLocalDeviceSource(HdmiControlService service, int deviceType) {
        super(service, deviceType);
    }

    @ServiceThreadOnly
    void queryDisplayStatus(IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        List<DevicePowerStatusAction> actions = getActions(DevicePowerStatusAction.class);
        if (!actions.isEmpty()) {
            Slog.i(TAG, "queryDisplayStatus already in progress");
            actions.get(0).addCallback(callback);
            return;
        }
        DevicePowerStatusAction action = DevicePowerStatusAction.create(this, Constants.ADDR_TV,
                callback);
        if (action == null) {
            Slog.w(TAG, "Cannot initiate queryDisplayStatus");
            invokeCallback(callback, HdmiControlManager.RESULT_EXCEPTION);
            return;
        }
        addAndStartAction(action);
    }

    @Override
    @ServiceThreadOnly
    void onHotplug(int portId, boolean connected) {
        assertRunOnServiceThread();
        if (mService.getPortInfo(portId).getType() == HdmiPortInfo.PORT_OUTPUT) {
            mCecMessageCache.flushAll();
        }
        // We'll not invalidate the active source on the hotplug event to pass CETC 11.2.2-2 ~ 3.
        if (connected) {
            mService.wakeUp();
        }
    }

    @Override
    @ServiceThreadOnly
    protected void sendStandby(int deviceId) {
        assertRunOnServiceThread();
        String powerControlMode = mService.getHdmiCecConfig().getStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE);
        if (powerControlMode.equals(HdmiControlManager.POWER_CONTROL_MODE_BROADCAST)) {
            mService.sendCecCommand(
                    HdmiCecMessageBuilder.buildStandby(
                            getDeviceInfo().getLogicalAddress(), Constants.ADDR_BROADCAST));
            return;
        }
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildStandby(
                        getDeviceInfo().getLogicalAddress(), Constants.ADDR_TV));
        if (powerControlMode.equals(HdmiControlManager.POWER_CONTROL_MODE_TV_AND_AUDIO_SYSTEM)) {
            mService.sendCecCommand(
                    HdmiCecMessageBuilder.buildStandby(
                            getDeviceInfo().getLogicalAddress(), Constants.ADDR_AUDIO_SYSTEM));
        }
    }

    @ServiceThreadOnly
    void oneTouchPlay(IHdmiControlCallback callback) {
        assertRunOnServiceThread();
        List<OneTouchPlayAction> actions = getActions(OneTouchPlayAction.class);
        if (!actions.isEmpty()) {
            Slog.i(TAG, "oneTouchPlay already in progress");
            actions.get(0).addCallback(callback);
            return;
        }
        OneTouchPlayAction action = OneTouchPlayAction.create(this, Constants.ADDR_TV,
                callback);
        if (action == null) {
            Slog.w(TAG, "Cannot initiate oneTouchPlay");
            invokeCallback(callback, HdmiControlManager.RESULT_EXCEPTION);
            return;
        }
        addAndStartAction(action);
    }

    @ServiceThreadOnly
    void toggleAndFollowTvPower() {
        assertRunOnServiceThread();
        if (mService.getPowerManager().isInteractive()) {
            mService.pauseActiveMediaSessions();
        } else {
            // Wake up Android framework to take over CEC control from the microprocessor.
            mService.wakeUp();
        }
        mService.queryDisplayStatus(new IHdmiControlCallback.Stub() {
            @Override
            public void onComplete(int status) {
                if (status == HdmiControlManager.POWER_STATUS_UNKNOWN) {
                    Slog.i(TAG, "TV power toggle: TV power status unknown");
                    sendUserControlPressedAndReleased(Constants.ADDR_TV,
                            HdmiCecKeycode.CEC_KEYCODE_POWER_TOGGLE_FUNCTION);
                    // Source device remains awake.
                } else if (status == HdmiControlManager.POWER_STATUS_ON
                        || status == HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON) {
                    Slog.i(TAG, "TV power toggle: turning off TV");
                    sendStandby(0 /*unused */);
                    // Source device goes to standby, to follow the toggled TV power state.
                    mService.standby();
                } else if (status == HdmiControlManager.POWER_STATUS_STANDBY
                        || status == HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY) {
                    Slog.i(TAG, "TV power toggle: turning on TV");
                    oneTouchPlay(new IHdmiControlCallback.Stub() {
                        @Override
                        public void onComplete(int result) {
                            if (result != HdmiControlManager.RESULT_SUCCESS) {
                                Slog.w(TAG, "Failed to complete One Touch Play. result=" + result);
                                sendUserControlPressedAndReleased(Constants.ADDR_TV,
                                        HdmiCecKeycode.CEC_KEYCODE_POWER_TOGGLE_FUNCTION);
                            }
                        }
                    });
                    // Source device remains awake, to follow the toggled TV power state.
                }
            }
        });
    }

    @ServiceThreadOnly
    protected void onActiveSourceLost() {
        // Nothing to do.
    }

    @Override
    @CallSuper
    @ServiceThreadOnly
    void setActiveSource(int logicalAddress, int physicalAddress, String caller) {
        boolean wasActiveSource = isActiveSource();
        super.setActiveSource(logicalAddress, physicalAddress, caller);
        if (wasActiveSource && !isActiveSource()) {
            onActiveSourceLost();
        }
    }

    @ServiceThreadOnly
    protected void setActiveSource(int physicalAddress, String caller) {
        assertRunOnServiceThread();
        // Invalidate the internal active source record.
        ActiveSource activeSource = ActiveSource.of(Constants.ADDR_INVALID, physicalAddress);
        setActiveSource(activeSource, caller);
    }

    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleActiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int logicalAddress = message.getSource();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        ActiveSource activeSource = ActiveSource.of(logicalAddress, physicalAddress);
        if (!getActiveSource().equals(activeSource)) {
            setActiveSource(activeSource, "HdmiCecLocalDeviceSource#handleActiveSource()");
        }
        updateDevicePowerStatus(logicalAddress, HdmiControlManager.POWER_STATUS_ON);
        if (isRoutingControlFeatureEnabled()) {
            switchInputOnReceivingNewActivePath(physicalAddress);
        }
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleRequestActiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        maySendActiveSource(message.getSource());
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleSetStreamPath(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        // If current device is the target path, set to Active Source.
        // If the path is under the current device, should switch
        if (physicalAddress == mService.getPhysicalAddress() && mService.isPlaybackDevice()) {
            setAndBroadcastActiveSource(message, physicalAddress,
                    "HdmiCecLocalDeviceSource#handleSetStreamPath()");
        } else if (physicalAddress != mService.getPhysicalAddress() || !isActiveSource()) {
            // Invalidate the active source if stream path is set to other physical address or
            // our physical address while not active source
            setActiveSource(physicalAddress, "HdmiCecLocalDeviceSource#handleSetStreamPath()");
        }
        switchInputOnReceivingNewActivePath(physicalAddress);
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleRoutingChange(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams(), 2);
        if (physicalAddress != mService.getPhysicalAddress() || !isActiveSource()) {
            // Invalidate the active source if routing is changed to other physical address or
            // our physical address while not active source
            setActiveSource(physicalAddress, "HdmiCecLocalDeviceSource#handleRoutingChange()");
        }
        if (!isRoutingControlFeatureEnabled()) {
            return Constants.ABORT_REFUSED;
        }
        handleRoutingChangeAndInformation(physicalAddress, message);
        return Constants.HANDLED;
    }

    @Override
    @ServiceThreadOnly
    @Constants.HandleMessageResult
    protected int handleRoutingInformation(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        if (physicalAddress != mService.getPhysicalAddress() || !isActiveSource()) {
            // Invalidate the active source if routing is changed to other physical address or
            // our physical address while not active source
            setActiveSource(physicalAddress, "HdmiCecLocalDeviceSource#handleRoutingInformation()");
        }
        if (!isRoutingControlFeatureEnabled()) {
            return Constants.ABORT_REFUSED;
        }
        handleRoutingChangeAndInformation(physicalAddress, message);
        return Constants.HANDLED;
    }

    // Method to switch Input with the new Active Path.
    // All the devices with Switch functionality should implement this.
    protected void switchInputOnReceivingNewActivePath(int physicalAddress) {
        // do nothing
    }

    // Only source devices that react to routing control messages should implement
    // this method (e.g. a TV with built in switch).
    protected void handleRoutingChangeAndInformation(int physicalAddress, HdmiCecMessage message) {
        // do nothing
    }

    @Override
    @ServiceThreadOnly
    protected void disableDevice(boolean initiatedByCec, PendingActionClearedCallback callback) {
        removeAction(OneTouchPlayAction.class);
        removeAction(DevicePowerStatusAction.class);
        removeAction(AbsoluteVolumeAudioStatusAction.class);

        super.disableDevice(initiatedByCec, callback);
    }

    // Update the power status of the devices connected to the current device.
    // This only works if the current device is a switch and keeps tracking the device info
    // of the device connected to it.
    protected void updateDevicePowerStatus(int logicalAddress, int newPowerStatus) {
        // do nothing
    }

    @Constants.RcProfile
    @Override
    protected int getRcProfile() {
        return Constants.RC_PROFILE_SOURCE;
    }

    @Override
    protected List<Integer> getRcFeatures() {
        List<Integer> features = new ArrayList<>();
        HdmiCecConfig hdmiCecConfig = mService.getHdmiCecConfig();
        if (hdmiCecConfig.getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_ROOT_MENU)
                == HdmiControlManager.RC_PROFILE_SOURCE_MENU_HANDLED) {
            features.add(Constants.RC_PROFILE_SOURCE_HANDLES_ROOT_MENU);
        }
        if (hdmiCecConfig.getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_SETUP_MENU)
                == HdmiControlManager.RC_PROFILE_SOURCE_MENU_HANDLED) {
            features.add(Constants.RC_PROFILE_SOURCE_HANDLES_SETUP_MENU);
        }
        if (hdmiCecConfig.getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_CONTENTS_MENU)
                == HdmiControlManager.RC_PROFILE_SOURCE_MENU_HANDLED) {
            features.add(Constants.RC_PROFILE_SOURCE_HANDLES_CONTENTS_MENU);
        }
        if (hdmiCecConfig.getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_TOP_MENU)
                == HdmiControlManager.RC_PROFILE_SOURCE_MENU_HANDLED) {
            features.add(Constants.RC_PROFILE_SOURCE_HANDLES_TOP_MENU);
        }
        if (hdmiCecConfig.getIntValue(HdmiControlManager
                .CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_MEDIA_CONTEXT_SENSITIVE_MENU)
                == HdmiControlManager.RC_PROFILE_SOURCE_MENU_HANDLED) {
            features.add(Constants.RC_PROFILE_SOURCE_HANDLES_MEDIA_CONTEXT_SENSITIVE_MENU);
        }
        return features;
    }

    // Active source claiming needs to be handled in Service
    // since service can decide who will be the active source when the device supports
    // multiple device types in this method.
    // This method should only be called when the device can be the active source.
    protected void setAndBroadcastActiveSource(HdmiCecMessage message, int physicalAddress,
            String caller) {
        mService.setAndBroadcastActiveSource(
                physicalAddress, getDeviceInfo().getDeviceType(), message.getSource(), caller);
    }

    // Indicates if current device is the active source or not
    @ServiceThreadOnly
    protected boolean isActiveSource() {
        if (getDeviceInfo() == null) {
            return false;
        }

        return getActiveSource().equals(getDeviceInfo().getLogicalAddress(),
                getDeviceInfo().getPhysicalAddress());
    }

    protected void wakeUpIfActiveSource() {
        if (!isActiveSource()) {
            return;
        }
        // Wake up the device. This will also exit dream mode.
        mService.wakeUp();
        return;
    }

    protected void maySendActiveSource(int dest) {
        if (!isActiveSource()) {
            return;
        }
        addAndStartAction(new ActiveSourceAction(this, dest));
    }

    /**
     * Set {@link #mRoutingPort} to a specific {@link LocalActivePort} to record the current active
     * CEC Routing Control related port.
     *
     * @param portId The portId of the new routing port.
     */
    @VisibleForTesting
    protected void setRoutingPort(@LocalActivePort int portId) {
        synchronized (mLock) {
            mRoutingPort = portId;
        }
    }

    /**
     * Get {@link #mRoutingPort}. This is useful when the device needs to route to the last valid
     * routing port.
     */
    @LocalActivePort
    protected int getRoutingPort() {
        synchronized (mLock) {
            return mRoutingPort;
        }
    }

    /**
     * Get {@link #mLocalActivePort}. This is useful when device needs to know the current active
     * port.
     */
    @LocalActivePort
    protected int getLocalActivePort() {
        synchronized (mLock) {
            return mLocalActivePort;
        }
    }

    /**
     * Set {@link #mLocalActivePort} to a specific {@link LocalActivePort} to record the current
     * active port.
     *
     * <p>It does not have to be a Routing Control related port. For example it can be
     * set to {@link Constants#CEC_SWITCH_ARC} but this port is System Audio related.
     *
     * @param activePort The portId of the new active port.
     */
    protected void setLocalActivePort(@LocalActivePort int activePort) {
        synchronized (mLock) {
            mLocalActivePort = activePort;
        }
    }

    boolean isRoutingControlFeatureEnabled() {
        synchronized (mLock) {
            return mRoutingControlFeatureEnabled;
        }
    }

    // Check if the device is trying to switch to the same input that is active right now.
    // This can help avoid redundant port switching.
    protected boolean isSwitchingToTheSameInput(@LocalActivePort int activePort) {
        return activePort == getLocalActivePort();
    }
}
