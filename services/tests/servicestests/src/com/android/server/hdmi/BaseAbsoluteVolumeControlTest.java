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

import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/**
 * Tests that Absolute Volume Control (AVC) is enabled and disabled correctly, and that
 * the device responds correctly to incoming <Report Audio Status> messages and API calls
 * from AudioService when AVC is active.
 *
 * This is an abstract base class. Concrete subclasses specify the type of the local device, and the
 * type of the System Audio device. This allows each test to be run for multiple setups.
 *
 * We test the following pairs of (local device, System Audio device):
 * (Playback, TV): {@link PlaybackDeviceToTvAvcTest}
 * (Playback, Audio System): {@link PlaybackDeviceToAudioSystemAvcTest}
 * (TV, Audio System): {@link TvToAudioSystemAvcTest}
 */
public abstract class BaseAbsoluteVolumeControlTest {
    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDevice mHdmiCecLocalDevice;
    private FakeHdmiCecConfig mHdmiCecConfig;
    private FakePowerManagerWrapper mPowerManager;
    private Looper mLooper;
    private Context mContextSpy;
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();

    @Mock protected AudioManager mAudioManager;
    protected FakeAudioDeviceVolumeManagerWrapper mAudioDeviceVolumeManager;

    protected TestLooper mTestLooper = new TestLooper();
    protected FakeNativeWrapper mNativeWrapper;

    // Audio Status given by the System Audio device in its initial <Report Audio Status> that
    // triggers AVC being enabled
    private static final AudioStatus INITIAL_SYSTEM_AUDIO_DEVICE_STATUS =
            new AudioStatus(50, false);

    // VolumeInfo passed to AudioDeviceVolumeManager#setDeviceAbsoluteVolumeBehavior to enable AVC
    private static final VolumeInfo ENABLE_AVC_VOLUME_INFO =
            new VolumeInfo.Builder(AudioManager.STREAM_MUSIC)
                    .setMuted(INITIAL_SYSTEM_AUDIO_DEVICE_STATUS.getMute())
                    .setVolumeIndex(INITIAL_SYSTEM_AUDIO_DEVICE_STATUS.getVolume())
                    .setMaxVolumeIndex(AudioStatus.MAX_VOLUME)
                    .setMinVolumeIndex(AudioStatus.MIN_VOLUME)
                    .build();

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

        mAudioDeviceVolumeManager = spy(new FakeAudioDeviceVolumeManagerWrapper());

        mHdmiControlService =
                new HdmiControlService(InstrumentationRegistry.getTargetContext(),
                        Collections.singletonList(getDeviceType()),
                        mAudioDeviceVolumeManager) {
                    @Override
                    AudioManager getAudioManager() {
                        return mAudioManager;
                    }

                    @Override
                    protected void writeStringSystemProperty(String key, String value) {
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
                new HdmiPortInfo(1, HdmiPortInfo.PORT_OUTPUT, 0x0000, true, false, false);
        mNativeWrapper.setPortInfo(hdmiPortInfos);
        mNativeWrapper.setPortConnectionStatus(1, true);

        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_BOOT_UP);
        mTestLooper.dispatchAll();

        // Simulate AudioManager's behavior and response when setDeviceVolumeBehavior is called
        doAnswer(invocation -> {
            setDeviceVolumeBehavior(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(mAudioManager).setDeviceVolumeBehavior(any(), anyInt());

        // Set starting volume behavior
        doReturn(AudioManager.DEVICE_VOLUME_BEHAVIOR_VARIABLE)
                .when(mAudioManager).getDeviceVolumeBehavior(eq(getAudioOutputDevice()));

        // Audio service always plays STREAM_MUSIC on the device we need
        doReturn(Collections.singletonList(getAudioOutputDevice())).when(mAudioManager)
                .getDevicesForAttributes(HdmiControlService.STREAM_MUSIC_ATTRIBUTES);

        // Max volume of STREAM_MUSIC
        doReturn(25).when(mAudioManager).getStreamMaxVolume(AudioManager.STREAM_MUSIC);

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
     * Simulates the volume behavior of {@code device} being set to {@code behavior}.
     */
    protected void setDeviceVolumeBehavior(AudioDeviceAttributes device,
            @AudioManager.DeviceVolumeBehavior int behavior) {
        doReturn(behavior).when(mAudioManager).getDeviceVolumeBehavior(eq(device));
        mHdmiControlService.onDeviceVolumeBehaviorChanged(device, behavior);
        mTestLooper.dispatchAll();
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
     * Has the device receive a <Report Audio Status> reporting the status in
     * {@link #INITIAL_SYSTEM_AUDIO_DEVICE_STATUS}
     */
    protected void receiveInitialReportAudioStatus() {
        receiveReportAudioStatus(
                INITIAL_SYSTEM_AUDIO_DEVICE_STATUS.getVolume(),
                INITIAL_SYSTEM_AUDIO_DEVICE_STATUS.getMute());
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
     * Triggers all the conditions required to enable Absolute Volume Control.
     */
    protected void enableAbsoluteVolumeControl() {
        setDeviceVolumeBehavior(getAudioOutputDevice(), AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_ENABLED);
        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_SUPPORTED);
        enableSystemAudioModeIfNeeded();
        receiveInitialReportAudioStatus();

        verifyAbsoluteVolumeEnabled();
    }

    /**
     * Verifies that AVC was enabled - that is the audio output device's volume behavior was last
     * set to absolute volume behavior.
     */
    protected void verifyAbsoluteVolumeEnabled() {
        InOrder inOrder = inOrder(mAudioManager, mAudioDeviceVolumeManager);
        inOrder.verify(mAudioDeviceVolumeManager, atLeastOnce()).setDeviceAbsoluteVolumeBehavior(
                eq(getAudioOutputDevice()), any(), any(), any(), anyBoolean());
        inOrder.verify(mAudioManager, never()).setDeviceVolumeBehavior(
                eq(getAudioOutputDevice()), not(eq(AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE)));
    }

    /**
     * Verifies that AVC was disabled - that is, the audio output device's volume behavior was
     * last set to something other than absolute volume behavior.
     */
    protected void verifyAbsoluteVolumeDisabled() {
        InOrder inOrder = inOrder(mAudioManager, mAudioDeviceVolumeManager);
        inOrder.verify(mAudioManager, atLeastOnce()).setDeviceVolumeBehavior(
                eq(getAudioOutputDevice()), not(eq(AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE)));
        inOrder.verify(mAudioDeviceVolumeManager, never()).setDeviceAbsoluteVolumeBehavior(
                eq(getAudioOutputDevice()), any(), any(), any(), anyBoolean());
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
        setDeviceVolumeBehavior(getAudioOutputDevice(), AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
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
        setDeviceVolumeBehavior(getAudioOutputDevice(), AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_ENABLED);
        enableSystemAudioModeIfNeeded();
        verifyGiveAudioStatusNeverSent();

        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_SUPPORTED);
        verifyGiveAudioStatusSent();
    }

    @Test
    public void allConditionsMet_savlSupportLast_noFeatureAbort_giveAudioStatusSent() {
        setDeviceVolumeBehavior(getAudioOutputDevice(), AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_ENABLED);
        enableSystemAudioModeIfNeeded();
        verifyGiveAudioStatusNeverSent();

        mTestLooper.moveTimeForward(HdmiConfig.TIMEOUT_MS);
        mTestLooper.dispatchAll();
        verifyGiveAudioStatusSent();
    }

    @Test
    public void allConditionsMet_cecVolumeEnabledLast_giveAudioStatusSent() {
        setDeviceVolumeBehavior(getAudioOutputDevice(), AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
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

        setDeviceVolumeBehavior(getAudioOutputDevice(), AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
        verifyGiveAudioStatusSent();
    }

    @Test
    public void allConditionsMet_systemAudioModeEnabledLast_giveAudioStatusSent() {
        // Only run when the System Audio device is an Audio System.
        assume().that(getSystemAudioDeviceType()).isEqualTo(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);

        setDeviceVolumeBehavior(getAudioOutputDevice(), AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_ENABLED);
        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_SUPPORTED);
        verifyGiveAudioStatusNeverSent();

        receiveSetSystemAudioMode(true);
        verifyGiveAudioStatusSent();
    }

    @Test
    public void giveAudioStatusSent_systemAudioDeviceSendsReportAudioStatus_avcEnabled() {
        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_ENABLED);
        enableSystemAudioModeIfNeeded();
        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_SUPPORTED);
        setDeviceVolumeBehavior(getAudioOutputDevice(), AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);

        // Verify that AVC was never enabled
        verify(mAudioDeviceVolumeManager, never()).setDeviceAbsoluteVolumeBehavior(
                eq(getAudioOutputDevice()), any(), any(), any(), anyBoolean());
        receiveInitialReportAudioStatus();

        verifyAbsoluteVolumeEnabled();
    }

    @Test
    public void avcEnabled_cecVolumeDisabled_absoluteVolumeDisabled() {
        enableAbsoluteVolumeControl();

        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_DISABLED);
        verifyAbsoluteVolumeDisabled();
    }

    @Test
    public void avcEnabled_setAudioVolumeLevelNotSupported_absoluteVolumeDisabled() {
        enableAbsoluteVolumeControl();

        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_NOT_SUPPORTED);
        verifyAbsoluteVolumeDisabled();
    }

    @Test
    public void avcEnabled_setAudioVolumeLevelFeatureAborted_absoluteVolumeDisabled() {
        enableAbsoluteVolumeControl();

        mNativeWrapper.onCecMessage(HdmiCecMessageBuilder.buildFeatureAbortCommand(
                getSystemAudioDeviceLogicalAddress(), getLogicalAddress(),
                Constants.MESSAGE_SET_AUDIO_VOLUME_LEVEL, Constants.ABORT_UNRECOGNIZED_OPCODE));
        mTestLooper.dispatchAll();
        verifyAbsoluteVolumeDisabled();
    }

    @Test
    public void avcEnabled_systemAudioModeDisabled_absoluteVolumeDisabled() {
        // Only run when the System Audio device is an Audio System.
        assume().that(getSystemAudioDeviceType()).isEqualTo(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);

        enableAbsoluteVolumeControl();

        receiveSetSystemAudioMode(false);
        verifyAbsoluteVolumeDisabled();
    }

    @Test
    public void avcEnabled_receiveReportAudioStatus_notifiesVolumeOrMuteChanges() {
        // Initial <Report Audio Status> has volume=50 and mute=false
        enableAbsoluteVolumeControl();

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
        clearInvocations(mAudioManager);

        // If AudioService causes us to send <Set Audio Volume Level>, the System Audio device's
        // volume changes. Afterward, a duplicate of an earlier <Report Audio Status> should
        // still cause us to call setStreamVolume()
        mHdmiControlService.getAbsoluteVolumeChangedListener().onAudioDeviceVolumeChanged(
                getAudioOutputDevice(),
                new VolumeInfo.Builder(ENABLE_AVC_VOLUME_INFO)
                        .setVolumeIndex(20)
                        .build()
        );
        mTestLooper.dispatchAll();
        receiveReportAudioStatus(32, false);
        verify(mAudioManager).setStreamVolume(eq(AudioManager.STREAM_MUSIC), eq(8),
                anyInt());
        verify(mAudioManager, never()).adjustStreamVolume(eq(AudioManager.STREAM_MUSIC),
                eq(AudioManager.ADJUST_UNMUTE), anyInt());
    }

    @Test
    public void avcEnabled_audioDeviceVolumeAdjusted_sendsUserControlPressedAndGiveAudioStatus() {
        enableAbsoluteVolumeControl();
        mNativeWrapper.clearResultMessages();

        mHdmiControlService.getAbsoluteVolumeChangedListener().onAudioDeviceVolumeAdjusted(
                getAudioOutputDevice(),
                ENABLE_AVC_VOLUME_INFO,
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
    public void avcEnabled_audioDeviceVolumeChanged_sendsSetAudioVolumeLevel() {
        enableAbsoluteVolumeControl();
        mNativeWrapper.clearResultMessages();

        mHdmiControlService.getAbsoluteVolumeChangedListener().onAudioDeviceVolumeChanged(
                getAudioOutputDevice(),
                new VolumeInfo.Builder(ENABLE_AVC_VOLUME_INFO)
                        .setVolumeIndex(20)
                        .build()
        );
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).contains(
                SetAudioVolumeLevelMessage.build(getLogicalAddress(),
                        getSystemAudioDeviceLogicalAddress(), 20));
    }
}
