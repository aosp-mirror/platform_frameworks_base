/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.hardware.hdmi.HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM;

import static com.android.server.hdmi.HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_BOOT_UP;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.TruthJUnit.assume;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.hdmi.DeviceFeatures;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceVolumeManager;
import android.media.AudioManager;
import android.media.VolumeInfo;
import android.os.Looper;
import android.os.RemoteException;
import android.os.test.TestLooper;

import androidx.test.InstrumentationRegistry;

import com.android.server.SystemService;

import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/**
 * Tests that absolute volume behavior (AVB) is enabled and disabled correctly, and that
 * the device responds correctly to incoming <Report Audio Status> messages and API calls
 * from AudioService when AVB is active.
 *
 * This is an abstract base class. Concrete subclasses specify the type of the local device, and the
 * type of the System Audio device. This allows each test to be run for multiple setups.
 *
 * We test the following pairs of (local device, System Audio device):
 * (Playback, TV): {@link PlaybackDeviceToTvAvbTest}
 * (Playback, Audio System): {@link PlaybackDeviceToAudioSystemAvbTest}
 * (TV, Audio System): {@link TvToAudioSystemArcAvbTest}, {@link TvToAudioSystemEarcAvbTest}
 */
public abstract class BaseAbsoluteVolumeBehaviorTest {
    protected HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDevice mHdmiCecLocalDevice;
    private FakeHdmiCecConfig mHdmiCecConfig;
    private FakePowerManagerWrapper mPowerManager;
    private Looper mLooper;
    private Context mContextSpy;
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();

    protected FakeAudioFramework mAudioFramework;
    protected AudioManagerWrapper mAudioManager;
    protected AudioDeviceVolumeManagerWrapper mAudioDeviceVolumeManager;

    protected TestLooper mTestLooper = new TestLooper();
    protected FakeNativeWrapper mNativeWrapper;

    // Default Audio Status given by the System Audio device in its initial <Report Audio Status>
    // that triggers AVB being enabled
    protected static final AudioStatus INITIAL_SYSTEM_AUDIO_DEVICE_STATUS =
            new AudioStatus(50, false);

    // VolumeInfo passed to AudioDeviceVolumeManager#setDeviceAbsoluteVolumeBehavior to enable AVB
    protected static final VolumeInfo ENABLE_AVB_VOLUME_INFO =
            new VolumeInfo.Builder(AudioManager.STREAM_MUSIC)
                    .setMuted(INITIAL_SYSTEM_AUDIO_DEVICE_STATUS.getMute())
                    .setVolumeIndex(INITIAL_SYSTEM_AUDIO_DEVICE_STATUS.getVolume())
                    .setMaxVolumeIndex(AudioStatus.MAX_VOLUME)
                    .setMinVolumeIndex(AudioStatus.MIN_VOLUME)
                    .build();

    private static final int EMPTY_FLAGS = 0;

    protected static final int STREAM_MUSIC_MAX_VOLUME = 25;

    protected abstract HdmiCecLocalDevice createLocalDevice(HdmiControlService hdmiControlService);

    protected abstract int getPhysicalAddress();
    protected abstract int getDeviceType();
    protected abstract AudioDeviceAttributes getAudioOutputDevice();

    protected abstract int getSystemAudioDeviceLogicalAddress();
    protected abstract int getSystemAudioDeviceType();

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);

        mContextSpy = spy(new ContextWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext()));

        mAudioFramework = new FakeAudioFramework();
        mAudioManager = spy(mAudioFramework.getAudioManager());
        mAudioDeviceVolumeManager = spy(mAudioFramework.getAudioDeviceVolumeManager());

        mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 1, EMPTY_FLAGS);
        mAudioManager.setStreamMute(AudioManager.STREAM_MUSIC, true);

        mHdmiControlService =
                new HdmiControlService(InstrumentationRegistry.getTargetContext(),
                        Collections.singletonList(getDeviceType()),
                        mAudioManager, mAudioDeviceVolumeManager) {
                    @Override
                    protected void writeStringSystemProperty(String key, String value) {
                        // do nothing
                    }

                    /**
                     * Override displayOsd to prevent it from broadcasting an intent, which
                     * can trigger a SecurityException.
                     */
                    @Override
                    void displayOsd(int messageId) {
                        // do nothing
                    }

                    @Override
                    void displayOsd(int messageId, int extra) {
                        // do nothing
                    }
                };

        mLooper = mTestLooper.getLooper();
        mHdmiControlService.setIoLooper(mLooper);

        mHdmiCecConfig = new FakeHdmiCecConfig(mContextSpy);
        mHdmiCecConfig.setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiCecConfig.setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE,
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        mHdmiControlService.setHdmiCecConfig(mHdmiCecConfig);
        mHdmiControlService.setDeviceConfig(new FakeDeviceConfigWrapper());

        mNativeWrapper = new FakeNativeWrapper();
        mNativeWrapper.setPhysicalAddress(getPhysicalAddress());
        mNativeWrapper.setPollAddressResponse(Constants.ADDR_TV, SendMessageResult.SUCCESS);

        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(
                HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.initService();
        mPowerManager = new FakePowerManagerWrapper(mContextSpy);
        mHdmiControlService.setPowerManager(mPowerManager);

        mHdmiCecLocalDevice = createLocalDevice(mHdmiControlService);
        mHdmiCecLocalDevice.init();
        mLocalDevices.add(mHdmiCecLocalDevice);

        HdmiPortInfo[] hdmiPortInfos = new HdmiPortInfo[1];
        hdmiPortInfos[0] =
                new HdmiPortInfo.Builder(1, HdmiPortInfo.PORT_OUTPUT, 0x0000)
                        .setCecSupported(true)
                        .setMhlSupported(false)
                        .setArcSupported(false)
                        .build();
        mNativeWrapper.setPortInfo(hdmiPortInfos);
        mNativeWrapper.setPortConnectionStatus(1, true);

        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_BOOT_UP);
        mTestLooper.dispatchAll();

        // Audio service always plays STREAM_MUSIC on the device we need
        mAudioFramework.setDevicesForAttributes(HdmiControlService.STREAM_MUSIC_ATTRIBUTES,
                Collections.singletonList(getAudioOutputDevice()));

        // Max volume of STREAM_MUSIC
        mAudioFramework.setStreamMaxVolume(AudioManager.STREAM_MUSIC, STREAM_MUSIC_MAX_VOLUME);

        // Receive messages from devices to make sure they're registered in HdmiCecNetwork
        mNativeWrapper.onCecMessage(HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                Constants.ADDR_TV, getLogicalAddress()));
        if (getSystemAudioDeviceType() == DEVICE_AUDIO_SYSTEM) {
            mNativeWrapper.onCecMessage(HdmiCecMessageBuilder.buildGiveDevicePowerStatus(
                    Constants.ADDR_AUDIO_SYSTEM, getLogicalAddress()));
        }

        mHdmiControlService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        mHdmiControlService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
        mTestLooper.dispatchAll();
    }

    protected int getLogicalAddress() {
        return mHdmiCecLocalDevice.getDeviceInfo().getLogicalAddress();
    }

    /**
     * Adopts full volume behavior on all of the HDMI audio output devices capable of adopting AVB.
     */
    protected void adoptFullVolumeBehaviorOnAvbCapableAudioOutputDevices() {
        if (getDeviceType() == HdmiDeviceInfo.DEVICE_PLAYBACK) {
            mAudioManager.setDeviceVolumeBehavior(HdmiControlService.AUDIO_OUTPUT_DEVICE_HDMI,
                    AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
        } else if (getDeviceType() == HdmiDeviceInfo.DEVICE_TV) {
            mAudioManager.setDeviceVolumeBehavior(HdmiControlService.AUDIO_OUTPUT_DEVICE_HDMI_ARC,
                    AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
            mAudioManager.setDeviceVolumeBehavior(HdmiControlService.AUDIO_OUTPUT_DEVICE_HDMI_EARC,
                    AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
        }
    }

    /**
     * Changes the setting for CEC volume.
     */
    protected void setCecVolumeControlSetting(@HdmiControlManager.VolumeControl int setting) {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE, setting);
        mTestLooper.dispatchAll();
    }

    /**
     * Has the device receive a <Report Features> message from the System Audio device specifying
     * whether <Set Audio Volume Level> is supported or not.
     */
    protected void receiveSetAudioVolumeLevelSupport(
            @DeviceFeatures.FeatureSupportStatus int featureSupportStatus) {
        // <Report Features> can't specify an unknown feature support status
        if (featureSupportStatus != DeviceFeatures.FEATURE_SUPPORT_UNKNOWN) {
            mNativeWrapper.onCecMessage(ReportFeaturesMessage.build(
                    getSystemAudioDeviceLogicalAddress(), HdmiControlManager.HDMI_CEC_VERSION_2_0,
                    Arrays.asList(getSystemAudioDeviceType()), Constants.RC_PROFILE_SOURCE,
                    Collections.emptyList(),
                    DeviceFeatures.NO_FEATURES_SUPPORTED.toBuilder()
                            .setSetAudioVolumeLevelSupport(featureSupportStatus)
                            .build()));
            mTestLooper.dispatchAll();
        }
    }

    /**
     * Enables System Audio mode if the System Audio device is an Audio System.
     */
    protected void enableSystemAudioModeIfNeeded() {
        if (getSystemAudioDeviceType() == DEVICE_AUDIO_SYSTEM) {
            receiveSetSystemAudioMode(true);
        }
    }

    /**
     * Sets System Audio Mode by having the device receive <Set System Audio Mode>
     * from the Audio System.
     */
    protected void receiveSetSystemAudioMode(boolean status) {
        mNativeWrapper.onCecMessage(HdmiCecMessageBuilder.buildSetSystemAudioMode(
                Constants.ADDR_AUDIO_SYSTEM, Constants.ADDR_BROADCAST, status));
        mTestLooper.dispatchAll();
    }

    /**
     * Has the device receive a <Report Audio Status> message from the System Audio Device.
     */
    protected void receiveReportAudioStatus(int volume, boolean mute) {
        mNativeWrapper.onCecMessage(HdmiCecMessageBuilder.buildReportAudioStatus(
                getSystemAudioDeviceLogicalAddress(),
                getLogicalAddress(),
                volume,
                mute));
        mTestLooper.dispatchAll();
    }

    /**
     * Triggers all the conditions required to enable absolute volume behavior.
     */
    protected void enableAbsoluteVolumeBehavior() {
        adoptFullVolumeBehaviorOnAvbCapableAudioOutputDevices();
        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_ENABLED);
        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_SUPPORTED);
        enableSystemAudioModeIfNeeded();
        receiveReportAudioStatus(
                INITIAL_SYSTEM_AUDIO_DEVICE_STATUS.getVolume(),
                INITIAL_SYSTEM_AUDIO_DEVICE_STATUS.getMute());

        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE);
    }

    protected void enableAdjustOnlyAbsoluteVolumeBehavior() {
        adoptFullVolumeBehaviorOnAvbCapableAudioOutputDevices();
        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_ENABLED);
        enableSystemAudioModeIfNeeded();
        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_NOT_SUPPORTED);
        receiveReportAudioStatus(
                INITIAL_SYSTEM_AUDIO_DEVICE_STATUS.getVolume(),
                INITIAL_SYSTEM_AUDIO_DEVICE_STATUS.getMute());

        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE_ADJUST_ONLY);
    }

    protected void verifyGiveAudioStatusNeverSent() {
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(
                HdmiCecMessageBuilder.buildGiveAudioStatus(
                        getLogicalAddress(), getSystemAudioDeviceLogicalAddress()));
    }

    protected void verifyGiveAudioStatusSent() {
        assertThat(mNativeWrapper.getResultMessages()).contains(
                HdmiCecMessageBuilder.buildGiveAudioStatus(
                        getLogicalAddress(), getSystemAudioDeviceLogicalAddress()));
    }

    @Test
    public void allConditionsExceptSavlSupportMet_sendsSetAudioVolumeLevelAndGiveFeatures() {
        adoptFullVolumeBehaviorOnAvbCapableAudioOutputDevices();
        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_ENABLED);
        enableSystemAudioModeIfNeeded();

        assertThat(mNativeWrapper.getResultMessages()).contains(
                SetAudioVolumeLevelMessage.build(
                        getLogicalAddress(), getSystemAudioDeviceLogicalAddress(),
                        Constants.AUDIO_VOLUME_STATUS_UNKNOWN));
        assertThat(mNativeWrapper.getResultMessages()).contains(
                HdmiCecMessageBuilder.buildGiveFeatures(
                        getLogicalAddress(), getSystemAudioDeviceLogicalAddress()));
    }

    @Test
    public void allConditionsMet_savlSupportLast_reportFeatures_giveAudioStatusSent() {
        adoptFullVolumeBehaviorOnAvbCapableAudioOutputDevices();
        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_ENABLED);
        enableSystemAudioModeIfNeeded();
        verifyGiveAudioStatusNeverSent();

        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_SUPPORTED);
        verifyGiveAudioStatusSent();
    }

    @Test
    public void allConditionsMet_savlSupportLast_noFeatureAbort_giveAudioStatusSent() {
        adoptFullVolumeBehaviorOnAvbCapableAudioOutputDevices();
        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_ENABLED);
        enableSystemAudioModeIfNeeded();
        verifyGiveAudioStatusNeverSent();

        mTestLooper.moveTimeForward(HdmiConfig.TIMEOUT_MS);
        mTestLooper.dispatchAll();
        verifyGiveAudioStatusSent();
    }

    @Test
    public void allConditionsMet_cecVolumeEnabledLast_giveAudioStatusSent() {
        adoptFullVolumeBehaviorOnAvbCapableAudioOutputDevices();
        enableSystemAudioModeIfNeeded();
        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_SUPPORTED);
        verifyGiveAudioStatusNeverSent();

        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_ENABLED);
        verifyGiveAudioStatusSent();
    }

    @Test
    public void allConditionsMet_fullVolumeBehaviorLast_giveAudioStatusSent() {
        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_ENABLED);
        enableSystemAudioModeIfNeeded();
        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_SUPPORTED);
        verifyGiveAudioStatusNeverSent();

        adoptFullVolumeBehaviorOnAvbCapableAudioOutputDevices();
        mTestLooper.dispatchAll();
        verifyGiveAudioStatusSent();
    }

    @Test
    public void allConditionsMet_systemAudioModeEnabledLast_giveAudioStatusSent() {
        // Only run when the System Audio device is an Audio System.
        assume().that(getSystemAudioDeviceType()).isEqualTo(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);

        adoptFullVolumeBehaviorOnAvbCapableAudioOutputDevices();
        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_ENABLED);
        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_SUPPORTED);
        verifyGiveAudioStatusNeverSent();

        receiveSetSystemAudioMode(true);
        verifyGiveAudioStatusSent();
    }

    @Test
    public void giveAudioStatusSent_systemAudioDeviceSendsReportAudioStatus_avbEnabled() {
        adoptFullVolumeBehaviorOnAvbCapableAudioOutputDevices();
        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_ENABLED);
        enableSystemAudioModeIfNeeded();
        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_SUPPORTED);

        // AVB should not be enabled before receiving <Report Audio Status>
        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);

        receiveReportAudioStatus(60, false);

        // Check that absolute volume behavior was the last one adopted
        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE);

        // Check that the volume and mute status received were included when setting AVB
        verify(mAudioDeviceVolumeManager).setDeviceAbsoluteVolumeBehavior(
                eq(getAudioOutputDevice()),
                eq(new VolumeInfo.Builder(AudioManager.STREAM_MUSIC)
                        .setVolumeIndex(60)
                        .setMuted(false)
                        .setMaxVolumeIndex(AudioStatus.MAX_VOLUME)
                        .setMinVolumeIndex(AudioStatus.MIN_VOLUME)
                        .build()),
                any(), any(), anyBoolean());
    }

    @Test
    public void giveAudioStatusSent_reportAudioStatusVolumeOutOfBounds_avbNotEnabled() {
        adoptFullVolumeBehaviorOnAvbCapableAudioOutputDevices();
        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_ENABLED);
        enableSystemAudioModeIfNeeded();
        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_SUPPORTED);

        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
        receiveReportAudioStatus(127, false);
        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
    }

    @Test
    public void avbEnabled_standby_avbDisabled() {
        enableAbsoluteVolumeBehavior();
        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
    }

    @Test
    public void avbEnabled_cecVolumeDisabled_avbDisabled() {
        enableAbsoluteVolumeBehavior();

        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_DISABLED);

        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
    }

    @Test
    public void avbEnabled_setAudioVolumeLevelNotSupported_avbDisabled() {
        enableAbsoluteVolumeBehavior();

        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_NOT_SUPPORTED);
        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
    }

    @Test
    public void avbEnabled_setAudioVolumeLevelFeatureAborted_avbDisabled() {
        enableAbsoluteVolumeBehavior();

        mNativeWrapper.onCecMessage(HdmiCecMessageBuilder.buildFeatureAbortCommand(
                getSystemAudioDeviceLogicalAddress(), getLogicalAddress(),
                Constants.MESSAGE_SET_AUDIO_VOLUME_LEVEL, Constants.ABORT_UNRECOGNIZED_OPCODE));
        mTestLooper.dispatchAll();
        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
    }

    @Test
    public void avbEnabled_systemAudioModeDisabled_avbDisabled() {
        // Only run when the System Audio device is an Audio System.
        assume().that(getSystemAudioDeviceType()).isEqualTo(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);

        enableAbsoluteVolumeBehavior();

        receiveSetSystemAudioMode(false);
        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
    }

    @Test
    public void avbEnabled_receiveReportAudioStatus_notifiesVolumeOrMuteChanges() {
        // Initial <Report Audio Status> has volume=50 and mute=false
        enableAbsoluteVolumeBehavior();

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

        // Repeat of earlier message: sets neither volume nor mute
        // Exception: On TV, mute is set to ensure that UI is shown
        receiveReportAudioStatus(32, false);
        verify(mAudioManager, never()).setStreamVolume(eq(AudioManager.STREAM_MUSIC),
                eq(32), anyInt());
        if (getDeviceType() == HdmiDeviceInfo.DEVICE_TV) {
            verify(mAudioManager).adjustStreamVolume(eq(AudioManager.STREAM_MUSIC),
                    eq(AudioManager.ADJUST_UNMUTE), anyInt());
        } else {
            verify(mAudioManager, never()).adjustStreamVolume(eq(AudioManager.STREAM_MUSIC),
                    eq(AudioManager.ADJUST_UNMUTE), anyInt());
        }
        clearInvocations(mAudioManager);

        // Volume not within range [0, 100]: sets neither volume nor mute
        receiveReportAudioStatus(127, true);
        verify(mAudioManager, never()).setStreamVolume(eq(AudioManager.STREAM_MUSIC), anyInt(),
                anyInt());
        verify(mAudioManager, never()).adjustStreamVolume(eq(AudioManager.STREAM_MUSIC), anyInt(),
                anyInt());
        clearInvocations(mAudioManager);

        // If AudioService causes us to send <Set Audio Volume Level>, the System Audio device's
        // volume changes. Afterward, a duplicate of an earlier <Report Audio Status> should
        // still cause us to call setStreamVolume()
        mHdmiControlService.getAbsoluteVolumeChangedListener().onAudioDeviceVolumeChanged(
                getAudioOutputDevice(),
                new VolumeInfo.Builder(ENABLE_AVB_VOLUME_INFO)
                        .setVolumeIndex(20)
                        .build()
        );
        mTestLooper.dispatchAll();
        receiveReportAudioStatus(32, false);
        verify(mAudioManager).setStreamVolume(eq(AudioManager.STREAM_MUSIC), eq(8),
                anyInt());
        // Update mute status because we updated volume
        verify(mAudioManager).adjustStreamVolume(eq(AudioManager.STREAM_MUSIC),
                eq(AudioManager.ADJUST_UNMUTE), anyInt());
    }

    @Test
    public void avbEnabled_audioDeviceVolumeAdjusted_sendsUserControlPressedAndGiveAudioStatus() {
        enableAbsoluteVolumeBehavior();
        mNativeWrapper.clearResultMessages();

        mHdmiControlService.getAbsoluteVolumeChangedListener().onAudioDeviceVolumeAdjusted(
                getAudioOutputDevice(),
                ENABLE_AVB_VOLUME_INFO,
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
    public void avbEnabled_audioDeviceVolumeChanged_sendsSetAudioVolumeLevel() {
        enableAbsoluteVolumeBehavior();
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

        assertThat(mNativeWrapper.getResultMessages()).contains(
                SetAudioVolumeLevelMessage.build(getLogicalAddress(),
                        getSystemAudioDeviceLogicalAddress(), 20));
    }
}
