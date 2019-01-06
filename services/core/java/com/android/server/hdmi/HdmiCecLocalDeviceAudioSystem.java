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

import static com.android.server.hdmi.Constants.ALWAYS_SYSTEM_AUDIO_CONTROL_ON_POWER_ON;
import static com.android.server.hdmi.Constants.PROPERTY_SYSTEM_AUDIO_CONTROL_ON_POWER_ON;
import static com.android.server.hdmi.Constants.USE_LAST_STATE_SYSTEM_AUDIO_CONTROL_ON_POWER_ON;

import android.annotation.Nullable;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.os.SystemProperties;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.hdmi.Constants.AudioCodec;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;

/**
 * Represent a logical device of type {@link HdmiDeviceInfo#DEVICE_AUDIO_SYSTEM} residing in Android
 * system.
 */
public class HdmiCecLocalDeviceAudioSystem extends HdmiCecLocalDevice {

    private static final String TAG = "HdmiCecLocalDeviceAudioSystem";

    // Whether System audio mode is activated or not.
    // This becomes true only when all system audio sequences are finished.
    @GuardedBy("mLock")
    private boolean mSystemAudioActivated;

    // Whether the System Audio Control feature is enabled or not. True by default.
    @GuardedBy("mLock")
    private boolean mSystemAudioControlFeatureEnabled;

    private boolean mTvSystemAudioModeSupport;

    // Whether ARC is available or not. "true" means that ARC is established between TV and
    // AVR as audio receiver.
    @ServiceThreadOnly private boolean mArcEstablished = false;

    protected HdmiCecLocalDeviceAudioSystem(HdmiControlService service) {
        super(service, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mSystemAudioControlFeatureEnabled = true;
        // TODO(amyjojo) make System Audio Control controllable by users
        /*mSystemAudioControlFeatureEnabled =
        mService.readBooleanSetting(Global.HDMI_SYSTEM_AUDIO_CONTROL_ENABLED, true);*/
    }

    @Override
    @ServiceThreadOnly
    protected void onStandby(boolean initiatedByCec, int standbyAction) {
        assertRunOnServiceThread();
        mTvSystemAudioModeSupport = false;
        // Record the last state of System Audio Control before going to standby
        synchronized (mLock) {
            SystemProperties.set(
                    Constants.PROPERTY_LAST_SYSTEM_AUDIO_CONTROL,
                    mSystemAudioActivated ? "true" : "false");
        }
        terminateSystemAudioMode();
    }

    @Override
    @ServiceThreadOnly
    protected void onAddressAllocated(int logicalAddress, int reason) {
        assertRunOnServiceThread();
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                        mAddress, mService.getPhysicalAddress(), mDeviceType));
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildDeviceVendorIdCommand(mAddress, mService.getVendorId()));
        int systemAudioControlOnPowerOnProp =
                SystemProperties.getInt(
                        PROPERTY_SYSTEM_AUDIO_CONTROL_ON_POWER_ON,
                        ALWAYS_SYSTEM_AUDIO_CONTROL_ON_POWER_ON);
        boolean lastSystemAudioControlStatus =
                SystemProperties.getBoolean(Constants.PROPERTY_LAST_SYSTEM_AUDIO_CONTROL, true);
        systemAudioControlOnPowerOn(systemAudioControlOnPowerOnProp, lastSystemAudioControlStatus);
        startQueuedActions();
    }

    @Override
    protected int findKeyReceiverAddress() {
        return Constants.ADDR_TV;
    }

    @VisibleForTesting
    protected void systemAudioControlOnPowerOn(
            int systemAudioOnPowerOnProp, boolean lastSystemAudioControlStatus) {
        if ((systemAudioOnPowerOnProp == ALWAYS_SYSTEM_AUDIO_CONTROL_ON_POWER_ON)
                || ((systemAudioOnPowerOnProp == USE_LAST_STATE_SYSTEM_AUDIO_CONTROL_ON_POWER_ON)
                        && lastSystemAudioControlStatus)) {
            addAndStartAction(new SystemAudioInitiationActionFromAvr(this));
        }
    }

    @ServiceThreadOnly
    protected boolean handleActiveSource(HdmiCecMessage message) {
        assertRunOnServiceThread();
        int logicalAddress = message.getSource();
        int physicalAddress = HdmiUtils.twoBytesToInt(message.getParams());
        ActiveSource activeSource = ActiveSource.of(logicalAddress, physicalAddress);
        if (!mActiveSource.equals(activeSource)) {
            setActiveSource(activeSource);
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected int getPreferredAddress() {
        assertRunOnServiceThread();
        return SystemProperties.getInt(
                Constants.PROPERTY_PREFERRED_ADDRESS_AUDIO_SYSTEM, Constants.ADDR_UNREGISTERED);
    }

    @Override
    @ServiceThreadOnly
    protected void setPreferredAddress(int addr) {
        assertRunOnServiceThread();
        SystemProperties.set(
                Constants.PROPERTY_PREFERRED_ADDRESS_AUDIO_SYSTEM, String.valueOf(addr));
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleReportAudioStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // TODO(amyjojo): implement report audio status handler
        HdmiLogger.debug(TAG + "Stub handleReportAudioStatus");
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleInitiateArc(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // TODO(amyjojo): implement initiate arc handler
        HdmiLogger.debug(TAG + "Stub handleInitiateArc");
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleReportArcInitiate(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // TODO(amyjojo): implement report arc initiate handler
        HdmiLogger.debug(TAG + "Stub handleReportArcInitiate");
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleReportArcTermination(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // TODO(amyjojo): implement report arc terminate handler
        HdmiLogger.debug(TAG + "Stub handleReportArcTermination");
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleGiveAudioStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();

        reportAudioStatus(message);
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleGiveSystemAudioModeStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildReportSystemAudioMode(
                        mAddress, message.getSource(), mSystemAudioActivated));
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRequestArcInitiate(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!SystemProperties.getBoolean(Constants.PROPERTY_ARC_SUPPORT, true)) {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_UNRECOGNIZED_OPCODE);
        } else if (!isDirectConnectToTv()) {
            HdmiLogger.debug("AVR device is not directly connected with TV");
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_NOT_IN_CORRECT_MODE);
        } else {
            addAndStartAction(new ArcInitiationActionFromAvr(this));
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRequestArcTermination(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!SystemProperties.getBoolean(Constants.PROPERTY_ARC_SUPPORT, true)) {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_UNRECOGNIZED_OPCODE);
        } else if (!isArcEnabled()) {
            HdmiLogger.debug("ARC is not established between TV and AVR device");
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_NOT_IN_CORRECT_MODE);
        } else {
            addAndStartAction(new ArcTerminationActionFromAvr(this));
        }
        return true;
    }

    @ServiceThreadOnly
    protected boolean handleRequestShortAudioDescriptor(HdmiCecMessage message) {
        assertRunOnServiceThread();
        HdmiLogger.debug(TAG + "Stub handleRequestShortAudioDescriptor");
        if (!isSystemAudioControlFeatureEnabled()) {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_REFUSED);
            return true;
        }
        if (!isSystemAudioActivated()) {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_NOT_IN_CORRECT_MODE);
            return true;
        }
        AudioDeviceInfo deviceInfo = getSystemAudioDeviceInfo();
        if (deviceInfo == null) {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_UNABLE_TO_DETERMINE);
            return true;
        }
        @AudioCodec int[] audioFormatCodes = parseAudioFormatCodes(message.getParams());
        byte[] sadBytes = getSupportedShortAudioDescriptors(deviceInfo, audioFormatCodes);
        if (sadBytes.length == 0) {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_INVALID_OPERAND);
        } else {
            mService.sendCecCommand(
                    HdmiCecMessageBuilder.buildReportShortAudioDescriptor(
                            mAddress, message.getSource(), sadBytes));
        }
        return true;
    }

    private byte[] getSupportedShortAudioDescriptors(
            AudioDeviceInfo deviceInfo, @AudioCodec int[] audioFormatCodes) {
        // TODO(b/80297701) implement
        return new byte[] {};
    }

    @Nullable
    private AudioDeviceInfo getSystemAudioDeviceInfo() {
        // TODO(b/80297701) implement
        // Get the audio device used for system audio mode.
        return null;
    }

    @AudioCodec
    private int[] parseAudioFormatCodes(byte[] params) {
        @AudioCodec int[] audioFormatCodes = new int[params.length];
        for (int i = 0; i < params.length; i++) {
            byte val = params[i];
            audioFormatCodes[i] =
                    val >= 1 && val <= Constants.AUDIO_CODEC_MAX ? val : Constants.AUDIO_CODEC_NONE;
        }
        return audioFormatCodes;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleSystemAudioModeRequest(HdmiCecMessage message) {
        assertRunOnServiceThread();
        boolean systemAudioStatusOn = message.getParams().length != 0;
        if (!setSystemAudioMode(systemAudioStatusOn)) {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_REFUSED);
            return true;
        }

        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildSetSystemAudioMode(
                        mAddress, Constants.ADDR_BROADCAST, systemAudioStatusOn));
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleSetSystemAudioMode(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!setSystemAudioMode(HdmiUtils.parseCommandParamSystemAudioStatus(message))) {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_REFUSED);
        }
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleSystemAudioModeStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();
        if (!setSystemAudioMode(HdmiUtils.parseCommandParamSystemAudioStatus(message))) {
            mService.maySendFeatureAbortCommand(message, Constants.ABORT_REFUSED);
        }
        return true;
    }

    @ServiceThreadOnly
    void setArcStatus(boolean enabled) {
        // TODO(shubang): add tests
        assertRunOnServiceThread();

        HdmiLogger.debug("Set Arc Status[old:%b new:%b]", mArcEstablished, enabled);
        // 1. Enable/disable ARC circuit.
        enableAudioReturnChannel(enabled);
        // 2. Notify arc status to audio service.
        notifyArcStatusToAudioService(enabled);
        // 3. Update arc status;
        mArcEstablished = enabled;
    }

    /** Switch hardware ARC circuit in the system. */
    @ServiceThreadOnly
    private void enableAudioReturnChannel(boolean enabled) {
        assertRunOnServiceThread();
        mService.enableAudioReturnChannel(
                SystemProperties.getInt(Constants.PROPERTY_SYSTEM_AUDIO_DEVICE_ARC_PORT, 0),
                enabled);
    }

    private void notifyArcStatusToAudioService(boolean enabled) {
        // Note that we don't set any name to ARC.
        mService.getAudioManager()
                .setWiredDeviceConnectionState(AudioSystem.DEVICE_IN_HDMI, enabled ? 1 : 0, "", "");
    }

    private void reportAudioStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();

        int volume = mService.getAudioManager().getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean mute = mService.getAudioManager().isStreamMute(AudioManager.STREAM_MUSIC);
        int maxVolume = mService.getAudioManager().getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int scaledVolume = VolumeControlAction.scaleToCecVolume(volume, maxVolume);

        mService.sendCecCommand(
                HdmiCecMessageBuilder.buildReportAudioStatus(
                        mAddress, message.getSource(), scaledVolume, mute));
    }

    protected boolean setSystemAudioMode(boolean newSystemAudioMode) {
        if (!isSystemAudioControlFeatureEnabled()) {
            HdmiLogger.debug(
                    "Cannot turn "
                            + (newSystemAudioMode ? "on" : "off")
                            + "system audio mode "
                            + "because the System Audio Control feature is disabled.");
            return false;
        }
        HdmiLogger.debug(
                "System Audio Mode change[old:%b new:%b]",
                mSystemAudioActivated, newSystemAudioMode);
        // Wake up device if System Audio Control is turned on but device is still on standby
        if (newSystemAudioMode && mService.isPowerStandbyOrTransient()) {
            mService.wakeUp();
        }
        int targetPhysicalAddress = getActiveSource().physicalAddress;
        if (newSystemAudioMode && !isPhysicalAddressMeOrBelow(targetPhysicalAddress)) {
            switchToAudioInput();
        }
        // TODO(b/80297700): Mute device when TV terminates the system audio control
        updateAudioManagerForSystemAudio(newSystemAudioMode);
        synchronized (mLock) {
            if (mSystemAudioActivated != newSystemAudioMode) {
                mSystemAudioActivated = newSystemAudioMode;
                mService.announceSystemAudioModeChange(newSystemAudioMode);
            }
        }
        return true;
    }

    /**
     * Method to check if the target device belongs to the subtree of the current device or not.
     *
     * <p>Return true if it does or if the two devices share the same physical address.
     *
     * <p>This check assumes both device physical address and target address are valid.
     *
     * @param targetPhysicalAddress is the physical address of the target device
     */
    protected boolean isPhysicalAddressMeOrBelow(int targetPhysicalAddress) {
        int myPhysicalAddress = mService.getPhysicalAddress();
        int xor = targetPhysicalAddress ^ myPhysicalAddress;
        // Return true if two addresses are the same
        // or if they only differs for one byte, but not the first byte,
        // and myPhysicalAddress is 0 after that byte
        if (xor == 0
                || ((xor & 0x0f00) == xor && (myPhysicalAddress & 0x0fff) == 0)
                || ((xor & 0x00f0) == xor && (myPhysicalAddress & 0x00ff) == 0)
                || ((xor & 0x000f) == xor && (myPhysicalAddress & 0x000f) == 0)) {
            return true;
        }
        return false;
    }

    protected void switchToAudioInput() {
        // TODO(b/111396634): switch input according to PROPERTY_SYSTEM_AUDIO_MODE_AUDIO_PORT
    }

    protected boolean isDirectConnectToTv() {
        int myPhysicalAddress = mService.getPhysicalAddress();
        return (myPhysicalAddress & Constants.ROUTING_PATH_TOP_MASK) == myPhysicalAddress;
    }

    private void updateAudioManagerForSystemAudio(boolean on) {
        int device = mService.getAudioManager().setHdmiSystemAudioSupported(on);
        HdmiLogger.debug("[A]UpdateSystemAudio mode[on=%b] output=[%X]", on, device);
    }

    @ServiceThreadOnly
    void setSystemAudioControlFeatureEnabled(boolean enabled) {
        assertRunOnServiceThread();
        synchronized (mLock) {
            mSystemAudioControlFeatureEnabled = enabled;
        }
    }

    boolean isSystemAudioControlFeatureEnabled() {
        synchronized (mLock) {
            return mSystemAudioControlFeatureEnabled;
        }
    }

    protected boolean isSystemAudioActivated() {
        synchronized (mLock) {
            return mSystemAudioActivated;
        }
    }

    protected void terminateSystemAudioMode() {
        // remove pending initiation actions
        removeAction(SystemAudioInitiationActionFromAvr.class);
        if (!isSystemAudioActivated()) {
            return;
        }

        if (setSystemAudioMode(false)) {
            // send <Set System Audio Mode> [“Off”]
            mService.sendCecCommand(
                    HdmiCecMessageBuilder.buildSetSystemAudioMode(
                            mAddress, Constants.ADDR_BROADCAST, false));
        }
    }

    /** Reports if System Audio Mode is supported by the connected TV */
    interface TvSystemAudioModeSupportedCallback {

        /** {@code supported} is true if the TV is connected and supports System Audio Mode. */
        void onResult(boolean supported);
    }

    /**
     * Queries the connected TV to detect if System Audio Mode is supported by the TV.
     *
     * <p>This query may take up to 2 seconds to complete.
     *
     * <p>The result of the query may be cached until Audio device type is put in standby or loses
     * its physical address.
     */
    void queryTvSystemAudioModeSupport(TvSystemAudioModeSupportedCallback callback) {
        if (!mTvSystemAudioModeSupport) {
            addAndStartAction(new DetectTvSystemAudioModeSupportAction(this, callback));
        } else {
            callback.onResult(true);
        }
    }

    void setTvSystemAudioModeSupport(boolean supported) {
        mTvSystemAudioModeSupport = supported;
    }

    @VisibleForTesting
    protected boolean isArcEnabled() {
        synchronized (mLock) {
            return mArcEstablished;
        }
    }
}
