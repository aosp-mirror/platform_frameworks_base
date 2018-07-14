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

import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.AudioManager;
import android.os.SystemProperties;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.hdmi.HdmiAnnotations.ServiceThreadOnly;

/**
 * Represent a logical device of type {@link HdmiDeviceInfo#DEVICE_AUDIO_SYSTEM} residing in
 * Android system.
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
    protected Integer mSystemAudioSource;

    private boolean mTvSystemAudioModeSupport;

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
            SystemProperties.set(Constants.PROPERTY_LAST_SYSTEM_AUDIO_CONTROL,
                mSystemAudioActivated ? "true" : "false");
        }
        if (setSystemAudioMode(false)) {
            mService.sendCecCommand(
                HdmiCecMessageBuilder.buildSetSystemAudioMode(
                    mAddress, Constants.ADDR_BROADCAST, false));
        }
    }

    @Override
    @ServiceThreadOnly
    protected void onAddressAllocated(int logicalAddress, int reason) {
        assertRunOnServiceThread();
        mService.sendCecCommand(HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                mAddress, mService.getPhysicalAddress(), mDeviceType));
        mService.sendCecCommand(HdmiCecMessageBuilder.buildDeviceVendorIdCommand(
                mAddress, mService.getVendorId()));
        int systemAudioControlOnPowerOnProp = SystemProperties.getInt(
            PROPERTY_SYSTEM_AUDIO_CONTROL_ON_POWER_ON, ALWAYS_SYSTEM_AUDIO_CONTROL_ON_POWER_ON);
        boolean lastSystemAudioControlStatus = SystemProperties.getBoolean(
            Constants.PROPERTY_LAST_SYSTEM_AUDIO_CONTROL, true);
        systemAudioControlOnPowerOn(systemAudioControlOnPowerOnProp, lastSystemAudioControlStatus);
        startQueuedActions();
    }

    @VisibleForTesting
    protected void systemAudioControlOnPowerOn(
        int systemAudioOnPowerOnProp, boolean lastSystemAudioControlStatus) {
        if ((systemAudioOnPowerOnProp ==
                ALWAYS_SYSTEM_AUDIO_CONTROL_ON_POWER_ON) ||
            ((systemAudioOnPowerOnProp ==
                USE_LAST_STATE_SYSTEM_AUDIO_CONTROL_ON_POWER_ON) &&
                lastSystemAudioControlStatus)) {
            addAndStartAction(new SystemAudioInitiationActionFromAvr(this));
        }
    }

    @Override
    @ServiceThreadOnly
    protected int getPreferredAddress() {
        assertRunOnServiceThread();
        return SystemProperties.getInt(Constants.PROPERTY_PREFERRED_ADDRESS_AUDIO_SYSTEM,
                Constants.ADDR_UNREGISTERED);
    }

    @Override
    @ServiceThreadOnly
    protected void setPreferredAddress(int addr) {
        assertRunOnServiceThread();
        SystemProperties.set(Constants.PROPERTY_PREFERRED_ADDRESS_AUDIO_SYSTEM,
                String.valueOf(addr));
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
        mService.sendCecCommand(HdmiCecMessageBuilder
            .buildReportSystemAudioMode(mAddress, message.getSource(), mSystemAudioActivated));
        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRequestArcInitiate(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // TODO(b/80296911): Check if ARC supported.

        // TODO(b/80296911): Check if port is ready to accept.

        // TODO(b/80296911): if both true, activate ARC functinality and
        mService.sendCecCommand(HdmiCecMessageBuilder
            .buildInitiateArc(mAddress, message.getSource()));
        // TODO(b/80296911): else, send <Feature Abort>["Unrecongnized opcode"]

        return true;
    }

    @Override
    @ServiceThreadOnly
    protected boolean handleRequestArcTermination(HdmiCecMessage message) {
        assertRunOnServiceThread();
        // TODO(b/80297105): Check if ARC supported.

        // TODO(b/80297105): Check is currently in arc.

        // TODO(b/80297105): If both true, deactivate ARC functionality and
        mService.sendCecCommand(HdmiCecMessageBuilder
            .buildTerminateArc(mAddress, message.getSource()));
        // TODO(b/80297105): else, send <Feature Abort>["Unrecongnized opcode"]

        return true;
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

        mService.sendCecCommand(HdmiCecMessageBuilder
            .buildSetSystemAudioMode(mAddress, Constants.ADDR_BROADCAST, systemAudioStatusOn));
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

    private void reportAudioStatus(HdmiCecMessage message) {
        assertRunOnServiceThread();

        int volume = mService.getAudioManager().getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean mute = mService.getAudioManager().isStreamMute(AudioManager.STREAM_MUSIC);
        int maxVolume = mService.getAudioManager().getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int scaledVolume = VolumeControlAction.scaleToCecVolume(volume, maxVolume);

        mService.sendCecCommand(HdmiCecMessageBuilder
            .buildReportAudioStatus(mAddress, message.getSource(), scaledVolume, mute));
    }

    protected boolean setSystemAudioMode(boolean newSystemAudioMode) {
        if (!isSystemAudioControlFeatureEnabled()) {
            HdmiLogger.debug("Cannot turn " +
                (newSystemAudioMode ? "on" : "off") + "system audio mode " +
                "because the System Audio Control feature is disabled.");
            return false;
        }
        HdmiLogger.debug("System Audio Mode change[old:%b new:%b]",
            mSystemAudioActivated, newSystemAudioMode);
        // Wake up device if System Audio Control is turned on but device is still on standby
        if (newSystemAudioMode && mService.isPowerStandbyOrTransient()) {
            mService.wakeUp();
            // TODO(amyjojo): Switch to the corresponding input
        }
        // Mute device when feature is turned off and unmute device when feature is turned on
        boolean currentMuteStatus =
            mService.getAudioManager().isStreamMute(AudioManager.STREAM_MUSIC);
        if (currentMuteStatus == newSystemAudioMode) {
            mService.getAudioManager().adjustStreamVolume(AudioManager.STREAM_MUSIC,
                newSystemAudioMode ? AudioManager.ADJUST_UNMUTE : AudioManager.ADJUST_MUTE, 0);
        }
        updateAudioManagerForSystemAudio(newSystemAudioMode);
        synchronized (mLock) {
            if (mSystemAudioActivated != newSystemAudioMode) {
                mSystemAudioActivated = newSystemAudioMode;
                mService.announceSystemAudioModeChange(newSystemAudioMode);
            }
        }
        return true;
    }

    private void updateAudioManagerForSystemAudio(boolean on) {
        int device = mService.getAudioManager().setHdmiSystemAudioSupported(on);
        HdmiLogger.debug("[A]UpdateSystemAudio mode[on=%b] output=[%X]", on, device);
    }

    protected boolean isSystemAudioControlFeatureEnabled() {
        synchronized (mLock) {
            return mSystemAudioControlFeatureEnabled;
        }
    }

    protected boolean isSystemAudioActivated() {
        synchronized (mLock) {
            return mSystemAudioActivated;
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
}
