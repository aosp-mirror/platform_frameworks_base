/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.server.hdmi.HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.hardware.hdmi.DeviceFeatures;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.AudioDeviceVolumeManager;
import android.media.AudioManager;
import android.media.VolumeInfo;

import org.junit.Test;

/**
 * Base class for tests for absolute volume behavior on TV panels. Contains tests that are
 * relevant to TV panels but not to Playback devices.
 *
 * Subclasses test the scenarios where ARC and eARC are the audio output devices:
 * ARC: {@link TvToAudioSystemArcAvbTest}
 * eARC: {@link TvToAudioSystemEarcAvbTest}
 */
public abstract class BaseTvToAudioSystemAvbTest extends BaseAbsoluteVolumeBehaviorTest {

    @Override
    protected HdmiCecLocalDevice createLocalDevice(HdmiControlService hdmiControlService) {
        return new HdmiCecLocalDeviceTv(hdmiControlService);
    }

    @Override
    protected int getPhysicalAddress() {
        return 0x0000;
    }

    @Override
    protected int getDeviceType() {
        return HdmiDeviceInfo.DEVICE_TV;
    }

    @Override
    protected int getSystemAudioDeviceLogicalAddress() {
        return Constants.ADDR_AUDIO_SYSTEM;
    }

    @Override
    protected int getSystemAudioDeviceType() {
        return HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM;
    }

    /**
     * TVs start the process for adopting adjust-only AVB if the System Audio device doesn't
     * support <Set Audio Volume Level>
     */
    @Test
    public void savlNotSupported_allOtherConditionsMet_giveAudioStatusSent() {
        adoptFullVolumeBehaviorOnAvbCapableAudioOutputDevices();
        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_ENABLED);
        enableSystemAudioModeIfNeeded();
        verifyGiveAudioStatusNeverSent();

        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_NOT_SUPPORTED);
        verifyGiveAudioStatusSent();
    }

    @Test
    public void savlNotSupported_systemAudioDeviceSendsReportAudioStatus_adjustOnlyAvbEnabled() {
        adoptFullVolumeBehaviorOnAvbCapableAudioOutputDevices();
        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_ENABLED);
        enableSystemAudioModeIfNeeded();
        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_NOT_SUPPORTED);

        // Adjust-only AVB should not be enabled before receiving <Report Audio Status>
        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);

        receiveReportAudioStatus(20, false);

        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY);

        verify(mAudioDeviceVolumeManager).setDeviceAbsoluteVolumeAdjustOnlyBehavior(
                eq(getAudioOutputDevice()),
                eq(new VolumeInfo.Builder(AudioManager.STREAM_MUSIC)
                        .setVolumeIndex(20)
                        .setMuted(false)
                        .setMaxVolumeIndex(AudioStatus.MAX_VOLUME)
                        .setMinVolumeIndex(AudioStatus.MIN_VOLUME)
                        .build()),
                any(), any(), anyBoolean());
    }


    @Test
    public void avbEnabled_savlNotSupported_receiveReportAudioStatus_switchToAdjustOnlyAvb() {
        enableAbsoluteVolumeBehavior();

        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_NOT_SUPPORTED);

        receiveReportAudioStatus(40, true);

        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY);

        verify(mAudioDeviceVolumeManager).setDeviceAbsoluteVolumeAdjustOnlyBehavior(
                eq(getAudioOutputDevice()),
                eq(new VolumeInfo.Builder(AudioManager.STREAM_MUSIC)
                        .setVolumeIndex(40)
                        .setMuted(true)
                        .setMaxVolumeIndex(AudioStatus.MAX_VOLUME)
                        .setMinVolumeIndex(AudioStatus.MIN_VOLUME)
                        .build()),
                any(), any(), anyBoolean());
    }

    @Test
    public void avbEnabled_savlFeatureAborted_receiveReportAudioStatus_switchToAdjustOnlyAvb() {
        enableAbsoluteVolumeBehavior();

        mNativeWrapper.onCecMessage(HdmiCecMessageBuilder.buildFeatureAbortCommand(
                getSystemAudioDeviceLogicalAddress(), getLogicalAddress(),
                Constants.MESSAGE_SET_AUDIO_VOLUME_LEVEL, Constants.ABORT_UNRECOGNIZED_OPCODE));
        mTestLooper.dispatchAll();

        receiveReportAudioStatus(40, true);

        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY);

        verify(mAudioDeviceVolumeManager).setDeviceAbsoluteVolumeAdjustOnlyBehavior(
                eq(getAudioOutputDevice()),
                eq(new VolumeInfo.Builder(AudioManager.STREAM_MUSIC)
                        .setVolumeIndex(40)
                        .setMuted(true)
                        .setMaxVolumeIndex(AudioStatus.MAX_VOLUME)
                        .setMinVolumeIndex(AudioStatus.MIN_VOLUME)
                        .build()),
                any(), any(), anyBoolean());
    }

    @Test
    public void adjustOnlyAvbEnabled_receiveReportAudioStatus_notifiesVolumeOrMuteChanges() {
        enableAdjustOnlyAbsoluteVolumeBehavior();

        // New volume and mute status: sets both
        receiveReportAudioStatus(20, true);
        verify(mAudioManager).setStreamVolume(eq(AudioManager.STREAM_MUSIC), eq(5),
                anyInt());
        verify(mAudioManager).adjustStreamVolume(eq(AudioManager.STREAM_MUSIC),
                eq(AudioManager.ADJUST_MUTE), anyInt());
        clearInvocations(mAudioManager);

        // New volume only: sets both volume and mute.
        // Volume changes can affect mute status; we need to set mute afterwards to undo this.
        receiveReportAudioStatus(32, true);
        verify(mAudioManager).setStreamVolume(eq(AudioManager.STREAM_MUSIC), eq(8),
                anyInt());
        verify(mAudioManager).adjustStreamVolume(eq(AudioManager.STREAM_MUSIC),
                eq(AudioManager.ADJUST_MUTE), anyInt());
        clearInvocations(mAudioManager);

        // New mute status only: sets mute only
        receiveReportAudioStatus(32, false);
        verify(mAudioManager, never()).setStreamVolume(eq(AudioManager.STREAM_MUSIC), eq(8),
                anyInt());
        verify(mAudioManager).adjustStreamVolume(eq(AudioManager.STREAM_MUSIC),
                eq(AudioManager.ADJUST_UNMUTE), anyInt());
        clearInvocations(mAudioManager);

        // Repeat of earlier message: sets mute only (to ensure volume UI is shown)
        receiveReportAudioStatus(32, false);
        verify(mAudioManager, never()).setStreamVolume(eq(AudioManager.STREAM_MUSIC), eq(8),
                anyInt());
        verify(mAudioManager).adjustStreamVolume(eq(AudioManager.STREAM_MUSIC),
                eq(AudioManager.ADJUST_UNMUTE), anyInt());
        clearInvocations(mAudioManager);

        // Volume not within range [0, 100]: sets neither volume nor mute
        receiveReportAudioStatus(127, true);
        verify(mAudioManager, never()).setStreamVolume(eq(AudioManager.STREAM_MUSIC), anyInt(),
                anyInt());
        verify(mAudioManager, never()).adjustStreamVolume(eq(AudioManager.STREAM_MUSIC), anyInt(),
                anyInt());
    }

    @Test
    public void adjustOnlyAvbEnabled_audioDeviceVolumeAdjusted_sendsUcpAndGiveAudioStatus() {
        enableAdjustOnlyAbsoluteVolumeBehavior();
        mNativeWrapper.clearResultMessages();

        mHdmiControlService.getAbsoluteVolumeChangedListener().onAudioDeviceVolumeAdjusted(
                getAudioOutputDevice(),
                new VolumeInfo.Builder(AudioManager.STREAM_MUSIC)
                        .setMaxVolumeIndex(AudioStatus.MAX_VOLUME)
                        .setMinVolumeIndex(AudioStatus.MIN_VOLUME)
                        .build(),
                AudioManager.ADJUST_RAISE,
                AudioDeviceVolumeManager.ADJUST_MODE_NORMAL
        );
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).contains(
                HdmiCecMessageBuilder.buildUserControlPressed(getLogicalAddress(),
                        getSystemAudioDeviceLogicalAddress(), CEC_KEYCODE_VOLUME_UP));
        assertThat(mNativeWrapper.getResultMessages()).contains(
                HdmiCecMessageBuilder.buildUserControlReleased(getLogicalAddress(),
                        getSystemAudioDeviceLogicalAddress()));
        assertThat(mNativeWrapper.getResultMessages()).contains(
                HdmiCecMessageBuilder.buildGiveAudioStatus(getLogicalAddress(),
                        getSystemAudioDeviceLogicalAddress()));
    }

    @Test
    public void adjustOnlyAvbEnabled_audioDeviceVolumeChanged_doesNotSendSetAudioVolumeLevel() {
        enableAdjustOnlyAbsoluteVolumeBehavior();

        mNativeWrapper.clearResultMessages();

        mHdmiControlService.getAbsoluteVolumeChangedListener().onAudioDeviceVolumeChanged(
                getAudioOutputDevice(),
                new VolumeInfo.Builder(AudioManager.STREAM_MUSIC)
                        .setVolumeIndex(20)
                        .setMaxVolumeIndex(AudioStatus.MAX_VOLUME)
                        .setMinVolumeIndex(AudioStatus.MIN_VOLUME)
                        .build()
        );
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).isEmpty();
    }

    @Test
    public void adjustOnlyAvbEnabled_savlBecomesSupported_switchToAvb() {
        enableAdjustOnlyAbsoluteVolumeBehavior();
        mNativeWrapper.clearResultMessages();

        // When the Audio System reports support for <Set Audio Volume Level>,
        // the device should start the process for adopting AVB by sending <Give Audio Status>
        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_SUPPORTED);
        verifyGiveAudioStatusSent();

        // The device should use adjust-only AVB while waiting for <Report Audio Status>
        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY);

        // The device should switch to AVB upon receiving <Report Audio Status>
        receiveReportAudioStatus(60, false);
        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE);

    }

    /**
     * Tests that adjust-only AVB is not interrupted when a device's support for
     * <Set Audio Volume Level> becomes unknown.
     *
     * This may currently occur when NewDeviceAction overwrites a device's info in HdmiCecNetwork.
     * However, because replicating this scenario would be brittle and the behavior may change,
     * this test does not simulate it and instead changes HdmiCecNetwork directly.
     */
    @Test
    public void adjustOnlyAvbEnabled_savlSupportBecomesUnknown_keepUsingAdjustOnlyAvb() {
        enableAdjustOnlyAbsoluteVolumeBehavior();
        mNativeWrapper.clearResultMessages();

        // Make sure the existing SetAudioVolumeLevelDiscoveryAction expires,
        // so that we can check whether a new one is started.
        mTestLooper.moveTimeForward(HdmiConfig.TIMEOUT_MS + 1);
        mTestLooper.dispatchAll();

        // Replace Audio System device info with one that has unknown support for all features
        HdmiDeviceInfo updatedAudioSystemDeviceInfo =
                mHdmiControlService.getHdmiCecNetwork().getDeviceInfo(Constants.ADDR_AUDIO_SYSTEM)
                .toBuilder()
                .setDeviceFeatures(DeviceFeatures.ALL_FEATURES_SUPPORT_UNKNOWN)
                .build();
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(updatedAudioSystemDeviceInfo);
        mTestLooper.dispatchAll();

        // The device should not switch away from adjust-only AVB
        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY);

        // The device should query support for <Set Audio Volume Level> again
        assertThat(mNativeWrapper.getResultMessages()).contains(
                SetAudioVolumeLevelMessage.build(
                        getLogicalAddress(), getSystemAudioDeviceLogicalAddress(),
                        Constants.AUDIO_VOLUME_STATUS_UNKNOWN));
    }

    /**
     * Tests that a volume adjustment command with direction ADJUST_SAME causes HdmiControlService
     * to request the System Audio device's audio status, and notify AudioService of the
     * audio status.
     */
    @Test
    public void avbEnabled_audioDeviceVolumeAdjusted_adjustSame_updatesAudioService() {
        enableAbsoluteVolumeBehavior();
        mNativeWrapper.clearResultMessages();

        // HdmiControlService receives a volume adjustment with direction ADJUST_SAME
        mHdmiControlService.getAbsoluteVolumeChangedListener().onAudioDeviceVolumeAdjusted(
                getAudioOutputDevice(),
                ENABLE_AVB_VOLUME_INFO,
                AudioManager.ADJUST_SAME,
                AudioDeviceVolumeManager.ADJUST_MODE_NORMAL
        );
        mTestLooper.dispatchAll();

        // Device sends <Give Audio Status>
        assertThat(mNativeWrapper.getResultMessages()).contains(
                HdmiCecMessageBuilder.buildGiveAudioStatus(getLogicalAddress(),
                        getSystemAudioDeviceLogicalAddress()));

        clearInvocations(mAudioManager);

        // Device receives <Report Audio Status> with a new volume and mute state
        mNativeWrapper.onCecMessage(HdmiCecMessageBuilder.buildReportAudioStatus(
                getSystemAudioDeviceLogicalAddress(),
                getLogicalAddress(),
                80,
                true));
        mTestLooper.dispatchAll();

        // HdmiControlService calls setStreamVolume and adjustStreamVolume to trigger volume UI
        verify(mAudioManager).setStreamVolume(
                eq(AudioManager.STREAM_MUSIC),
                // Volume level is rescaled to the max volume of STREAM_MUSIC
                eq(80 * STREAM_MUSIC_MAX_VOLUME / AudioStatus.MAX_VOLUME),
                eq(AudioManager.FLAG_ABSOLUTE_VOLUME | AudioManager.FLAG_SHOW_UI));
        verify(mAudioManager).adjustStreamVolume(
                eq(AudioManager.STREAM_MUSIC),
                eq(AudioManager.ADJUST_MUTE),
                eq(AudioManager.FLAG_ABSOLUTE_VOLUME | AudioManager.FLAG_SHOW_UI));
    }

    /**
     * Tests that a volume adjustment command with direction ADJUST_SAME causes HdmiControlService
     * to request the System Audio device's audio status, and notify AudioService of the
     * audio status, even if it's unchanged from the previous one.
     */
    @Test
    public void avbEnabled_audioDeviceVolumeAdjusted_adjustSame_noChange_updatesAudioService() {
        enableAbsoluteVolumeBehavior();
        mNativeWrapper.clearResultMessages();

        // HdmiControlService receives a volume adjustment with direction ADJUST_SAME
        mHdmiControlService.getAbsoluteVolumeChangedListener().onAudioDeviceVolumeAdjusted(
                getAudioOutputDevice(),
                ENABLE_AVB_VOLUME_INFO,
                AudioManager.ADJUST_SAME,
                AudioDeviceVolumeManager.ADJUST_MODE_NORMAL
        );
        mTestLooper.dispatchAll();

        // Device sends <Give Audio Status>
        assertThat(mNativeWrapper.getResultMessages()).contains(
                HdmiCecMessageBuilder.buildGiveAudioStatus(getLogicalAddress(),
                        getSystemAudioDeviceLogicalAddress()));

        clearInvocations(mAudioManager);

        // Device receives <Report Audio Status> with the same volume level and mute state that
        // as when AVB was enabled
        mNativeWrapper.onCecMessage(HdmiCecMessageBuilder.buildReportAudioStatus(
                getSystemAudioDeviceLogicalAddress(),
                getLogicalAddress(),
                INITIAL_SYSTEM_AUDIO_DEVICE_STATUS.getVolume(),
                INITIAL_SYSTEM_AUDIO_DEVICE_STATUS.getMute()));
        mTestLooper.dispatchAll();

        // HdmiControlService calls adjustStreamVolume to trigger volume UI
        verify(mAudioManager).adjustStreamVolume(
                eq(AudioManager.STREAM_MUSIC),
                eq(AudioManager.ADJUST_UNMUTE),
                eq(AudioManager.FLAG_ABSOLUTE_VOLUME | AudioManager.FLAG_SHOW_UI));
        // setStreamVolume is not called because volume didn't change,
        // and adjustStreamVolume is sufficient to show volume UI
        verify(mAudioManager, never()).setStreamVolume(
                eq(AudioManager.STREAM_MUSIC),
                // Volume level is rescaled to the max volume of STREAM_MUSIC
                eq(INITIAL_SYSTEM_AUDIO_DEVICE_STATUS.getVolume()
                        * STREAM_MUSIC_MAX_VOLUME / AudioStatus.MAX_VOLUME),
                eq(AudioManager.FLAG_ABSOLUTE_VOLUME | AudioManager.FLAG_SHOW_UI));
    }
}
