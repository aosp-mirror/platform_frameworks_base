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
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceVolumeManager;
import android.media.AudioManager;
import android.media.VolumeInfo;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for absolute volume behavior where the local device is a TV and the System Audio device
 * is an Audio System. Assumes that the TV uses ARC (rather than eARC).
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class TvToAudioSystemAvbTest extends BaseAbsoluteVolumeBehaviorTest {

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
    protected AudioDeviceAttributes getAudioOutputDevice() {
        return HdmiControlService.AUDIO_OUTPUT_DEVICE_HDMI_ARC;
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
        mAudioManager.setDeviceVolumeBehavior(getAudioOutputDevice(),
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_ENABLED);
        enableSystemAudioModeIfNeeded();
        verifyGiveAudioStatusNeverSent();

        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_NOT_SUPPORTED);
        verifyGiveAudioStatusSent();
    }

    @Test
    public void savlNotSupported_systemAudioDeviceSendsReportAudioStatus_adjustOnlyAvbEnabled() {
        mAudioManager.setDeviceVolumeBehavior(getAudioOutputDevice(),
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
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

        // New volume only: sets volume only
        receiveReportAudioStatus(32, true);
        verify(mAudioManager).setStreamVolume(eq(AudioManager.STREAM_MUSIC), eq(8),
                anyInt());
        verify(mAudioManager, never()).adjustStreamVolume(eq(AudioManager.STREAM_MUSIC),
                eq(AudioManager.ADJUST_MUTE), anyInt());
        clearInvocations(mAudioManager);

        // New mute status only: sets mute only
        receiveReportAudioStatus(32, false);
        verify(mAudioManager, never()).setStreamVolume(eq(AudioManager.STREAM_MUSIC), eq(8),
                anyInt());
        verify(mAudioManager).adjustStreamVolume(eq(AudioManager.STREAM_MUSIC),
                eq(AudioManager.ADJUST_UNMUTE), anyInt());
        clearInvocations(mAudioManager);

        // Repeat of earlier message: sets neither volume nor mute
        receiveReportAudioStatus(32, false);
        verify(mAudioManager, never()).setStreamVolume(eq(AudioManager.STREAM_MUSIC), eq(8),
                anyInt());
        verify(mAudioManager, never()).adjustStreamVolume(eq(AudioManager.STREAM_MUSIC),
                eq(AudioManager.ADJUST_UNMUTE), anyInt());

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
}
