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

import static android.hardware.hdmi.HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM;
import static android.hardware.hdmi.HdmiDeviceInfo.DEVICE_PLAYBACK;
import static android.hardware.hdmi.HdmiDeviceInfo.DEVICE_TV;

import static com.android.server.SystemService.PHASE_BOOT_COMPLETED;
import static com.android.server.SystemService.PHASE_SYSTEM_SERVICES_READY;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.assertEquals;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.hdmi.IHdmiCecVolumeControlFeatureListener;
import android.os.Binder;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.sysprop.HdmiProperties;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;

/**
 * Tests for {@link HdmiControlService} class.
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class HdmiControlServiceTest {

    protected static class MockPlaybackDevice extends HdmiCecLocalDevicePlayback {

        private boolean mCanGoToStandby;
        private boolean mIsStandby;
        private boolean mIsDisabled;

        MockPlaybackDevice(HdmiControlService service) {
            super(service);
        }

        @Override
        protected void onAddressAllocated(int logicalAddress, int reason) {}

        @Override
        protected int getPreferredAddress() {
            return 0;
        }

        @Override
        protected void setPreferredAddress(int addr) {}

        @Override
        protected boolean canGoToStandby() {
            return mCanGoToStandby;
        }

        @Override
        protected void disableDevice(
                boolean initiatedByCec, final PendingActionClearedCallback originalCallback) {
            mIsDisabled = true;
            originalCallback.onCleared(this);
        }

        @Override
        protected void onStandby(boolean initiatedByCec, int standbyAction) {
            mIsStandby = true;
        }

        protected boolean isStandby() {
            return mIsStandby;
        }

        protected boolean isDisabled() {
            return mIsDisabled;
        }

        protected void setCanGoToStandby(boolean canGoToStandby) {
            mCanGoToStandby = canGoToStandby;
        }
    }
    protected static class MockAudioSystemDevice extends HdmiCecLocalDeviceAudioSystem {

        private boolean mCanGoToStandby;
        private boolean mIsStandby;
        private boolean mIsDisabled;

        MockAudioSystemDevice(HdmiControlService service) {
            super(service);
        }

        @Override
        protected void onAddressAllocated(int logicalAddress, int reason) {}

        @Override
        protected int getPreferredAddress() {
            return 0;
        }

        @Override
        protected void setPreferredAddress(int addr) {}

        @Override
        protected boolean canGoToStandby() {
            return mCanGoToStandby;
        }

        @Override
        protected void disableDevice(
                boolean initiatedByCec, final PendingActionClearedCallback originalCallback) {
            mIsDisabled = true;
            originalCallback.onCleared(this);
        }

        @Override
        protected void onStandby(boolean initiatedByCec, int standbyAction) {
            mIsStandby = true;
        }

        protected boolean isStandby() {
            return mIsStandby;
        }

        protected boolean isDisabled() {
            return mIsDisabled;
        }

        protected void setCanGoToStandby(boolean canGoToStandby) {
            mCanGoToStandby = canGoToStandby;
        }
    }

    private static final String TAG = "HdmiControlServiceTest";
    private Context mContextSpy;
    private HdmiControlService mHdmiControlServiceSpy;
    private HdmiCecController mHdmiCecController;
    private MockAudioSystemDevice mAudioSystemDeviceSpy;
    private MockPlaybackDevice mPlaybackDeviceSpy;
    private FakeNativeWrapper mNativeWrapper;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private HdmiPortInfo[] mHdmiPortInfo;

    @Mock private IPowerManager mIPowerManagerMock;
    @Mock private IThermalService mIThermalServiceMock;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));

        when(mContextSpy.getSystemService(Context.POWER_SERVICE)).thenAnswer(i ->
                new PowerManager(mContextSpy, mIPowerManagerMock,
                mIThermalServiceMock, null));
        when(mContextSpy.getSystemService(PowerManager.class)).thenAnswer(i ->
                new PowerManager(mContextSpy, mIPowerManagerMock,
                mIThermalServiceMock, null));
        when(mIPowerManagerMock.isInteractive()).thenReturn(true);

        HdmiCecConfig hdmiCecConfig = new FakeHdmiCecConfig(mContextSpy);

        mHdmiControlServiceSpy = spy(new HdmiControlService(mContextSpy));
        doNothing().when(mHdmiControlServiceSpy)
                .writeStringSystemProperty(anyString(), anyString());

        mMyLooper = mTestLooper.getLooper();

        mAudioSystemDeviceSpy = spy(new MockAudioSystemDevice(mHdmiControlServiceSpy));
        mPlaybackDeviceSpy = spy(new MockPlaybackDevice(mHdmiControlServiceSpy));
        mAudioSystemDeviceSpy.init();
        mPlaybackDeviceSpy.init();

        mHdmiControlServiceSpy.setIoLooper(mMyLooper);
        mHdmiControlServiceSpy.setHdmiCecConfig(hdmiCecConfig);
        mHdmiControlServiceSpy.onBootPhase(PHASE_SYSTEM_SERVICES_READY);

        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlServiceSpy, mNativeWrapper, mHdmiControlServiceSpy.getAtomWriter());
        mHdmiControlServiceSpy.setCecController(mHdmiCecController);
        mHdmiControlServiceSpy.setHdmiMhlController(HdmiMhlControllerStub.create(
                mHdmiControlServiceSpy));
        mHdmiControlServiceSpy.setMessageValidator(new HdmiCecMessageValidator(
                mHdmiControlServiceSpy));

        mLocalDevices.add(mAudioSystemDeviceSpy);
        mLocalDevices.add(mPlaybackDeviceSpy);
        mHdmiPortInfo = new HdmiPortInfo[4];
        mHdmiPortInfo[0] =
            new HdmiPortInfo(1, HdmiPortInfo.PORT_INPUT, 0x2100, true, false, false);
        mHdmiPortInfo[1] =
            new HdmiPortInfo(2, HdmiPortInfo.PORT_INPUT, 0x2200, true, false, false);
        mHdmiPortInfo[2] =
            new HdmiPortInfo(3, HdmiPortInfo.PORT_INPUT, 0x2000, true, false, false);
        mHdmiPortInfo[3] =
            new HdmiPortInfo(4, HdmiPortInfo.PORT_INPUT, 0x3000, true, false, false);
        mNativeWrapper.setPortInfo(mHdmiPortInfo);
        mHdmiControlServiceSpy.initService();
        mHdmiControlServiceSpy.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);

        mTestLooper.dispatchAll();
    }

    @Test
    public void onStandby_notByCec_cannotGoToStandby() {
        doReturn(false).when(mHdmiControlServiceSpy).isStandbyMessageReceived();

        mPlaybackDeviceSpy.setCanGoToStandby(false);

        mHdmiControlServiceSpy.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        assertTrue(mPlaybackDeviceSpy.isStandby());
        assertTrue(mAudioSystemDeviceSpy.isStandby());
        assertFalse(mPlaybackDeviceSpy.isDisabled());
        assertFalse(mAudioSystemDeviceSpy.isDisabled());
    }

    @Test
    public void onStandby_byCec() {
        doReturn(true).when(mHdmiControlServiceSpy).isStandbyMessageReceived();

        mHdmiControlServiceSpy.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        assertTrue(mPlaybackDeviceSpy.isStandby());
        assertTrue(mAudioSystemDeviceSpy.isStandby());
        assertTrue(mPlaybackDeviceSpy.isDisabled());
        assertTrue(mAudioSystemDeviceSpy.isDisabled());
    }

    @Test
    public void initialPowerStatus_normalBoot_isTransientToStandby() {
        assertThat(mHdmiControlServiceSpy.getInitialPowerStatus()).isEqualTo(
                HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY);
    }

    @Test
    public void initialPowerStatus_quiescentBoot_isTransientToStandby() throws RemoteException {
        when(mIPowerManagerMock.isInteractive()).thenReturn(false);
        assertThat(mHdmiControlServiceSpy.getInitialPowerStatus()).isEqualTo(
                HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY);
    }

    @Test
    public void powerStatusAfterBootComplete_normalBoot_isOn() {
        mHdmiControlServiceSpy.setPowerStatus(HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON);
        mHdmiControlServiceSpy.onBootPhase(PHASE_BOOT_COMPLETED);
        assertThat(mHdmiControlServiceSpy.getPowerStatus()).isEqualTo(
                HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON);
    }

    @Test
    public void powerStatusAfterBootComplete_quiescentBoot_isStandby() throws RemoteException {
        when(mIPowerManagerMock.isInteractive()).thenReturn(false);
        mHdmiControlServiceSpy.onBootPhase(PHASE_BOOT_COMPLETED);
        assertThat(mHdmiControlServiceSpy.getPowerStatus()).isEqualTo(
                HdmiControlManager.POWER_STATUS_STANDBY);
    }

    @Test
    public void initialPowerStatus_normalBoot_goToStandby_doesNotBroadcastsPowerStatus_1_4() {
        mHdmiControlServiceSpy.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);

        mHdmiControlServiceSpy.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        mNativeWrapper.clearResultMessages();

        assertThat(mHdmiControlServiceSpy.getInitialPowerStatus()).isEqualTo(
                HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY);

        mHdmiControlServiceSpy.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);

        HdmiCecMessage reportPowerStatus = HdmiCecMessageBuilder.buildReportPowerStatus(
                Constants.ADDR_PLAYBACK_1, Constants.ADDR_BROADCAST,
                HdmiControlManager.POWER_STATUS_STANDBY);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(reportPowerStatus);
    }

    @Test
    public void initialPowerStatus_normalBoot_goToStandby_broadcastsPowerStatus_2_0() {
        mHdmiControlServiceSpy.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mTestLooper.dispatchAll();

        mHdmiControlServiceSpy.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        mNativeWrapper.clearResultMessages();

        mHdmiControlServiceSpy.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        assertThat(mHdmiControlServiceSpy.getInitialPowerStatus()).isEqualTo(
                HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY);

        mHdmiControlServiceSpy.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportPowerStatus = HdmiCecMessageBuilder.buildReportPowerStatus(
                Constants.ADDR_PLAYBACK_1, Constants.ADDR_BROADCAST,
                HdmiControlManager.POWER_STATUS_STANDBY);
        assertThat(mNativeWrapper.getResultMessages()).contains(reportPowerStatus);
    }

    @Test
    public void setAndGetCecVolumeControlEnabled_isApi() {
        mHdmiControlServiceSpy.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE,
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        assertThat(mHdmiControlServiceSpy.getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE)).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);

        mHdmiControlServiceSpy.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE,
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        assertThat(mHdmiControlServiceSpy.getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE)).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
    }

    @Test
    public void setAndGetCecVolumeControlEnabledInternal_doesNotChangeSetting() {
        mHdmiControlServiceSpy.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE,
                HdmiControlManager.VOLUME_CONTROL_ENABLED);

        mHdmiControlServiceSpy.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        assertThat(mHdmiControlServiceSpy.getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE)).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);

        mHdmiControlServiceSpy.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        assertThat(mHdmiControlServiceSpy.getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE)).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
    }

    @Test
    public void disableAndReenableCec_volumeControlReturnsToOriginalValue_enabled() {
        int volumeControlEnabled = HdmiControlManager.VOLUME_CONTROL_ENABLED;
        mHdmiControlServiceSpy.setHdmiCecVolumeControlEnabledInternal(volumeControlEnabled);

        mHdmiControlServiceSpy.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED);
        assertThat(mHdmiControlServiceSpy.getHdmiCecVolumeControl()).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);

        mHdmiControlServiceSpy.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        assertThat(mHdmiControlServiceSpy.getHdmiCecVolumeControl())
                .isEqualTo(volumeControlEnabled);
    }

    @Test
    public void disableAndReenableCec_volumeControlReturnsToOriginalValue_disabled() {
        int volumeControlEnabled = HdmiControlManager.VOLUME_CONTROL_DISABLED;
        mHdmiControlServiceSpy.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE, volumeControlEnabled);

        mHdmiControlServiceSpy.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED);
        assertThat(mHdmiControlServiceSpy.getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE)).isEqualTo(
                volumeControlEnabled);

        mHdmiControlServiceSpy.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        assertThat(mHdmiControlServiceSpy.getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE)).isEqualTo(
                volumeControlEnabled);
    }

    @Test
    public void disableAndReenableCec_volumeControlFeatureListenersNotified() {
        mHdmiControlServiceSpy.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE,
                HdmiControlManager.VOLUME_CONTROL_ENABLED);

        VolumeControlFeatureCallback callback = new VolumeControlFeatureCallback();
        mHdmiControlServiceSpy.addHdmiCecVolumeControlFeatureListener(callback);

        mHdmiControlServiceSpy.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED);
        assertThat(callback.mCallbackReceived).isTrue();
        assertThat(callback.mVolumeControlEnabled).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);


        mHdmiControlServiceSpy.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        assertThat(callback.mVolumeControlEnabled).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
    }

    @Test
    public void addHdmiCecVolumeControlFeatureListener_emitsCurrentState_enabled() {
        mHdmiControlServiceSpy.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        VolumeControlFeatureCallback callback = new VolumeControlFeatureCallback();

        mHdmiControlServiceSpy.addHdmiCecVolumeControlFeatureListener(callback);
        mTestLooper.dispatchAll();

        assertThat(callback.mCallbackReceived).isTrue();
        assertThat(callback.mVolumeControlEnabled).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
    }

    @Test
    public void addHdmiCecVolumeControlFeatureListener_emitsCurrentState_disabled() {
        mHdmiControlServiceSpy.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        VolumeControlFeatureCallback callback = new VolumeControlFeatureCallback();

        mHdmiControlServiceSpy.addHdmiCecVolumeControlFeatureListener(callback);
        mTestLooper.dispatchAll();

        assertThat(callback.mCallbackReceived).isTrue();
        assertThat(callback.mVolumeControlEnabled).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
    }

    @Test
    public void addHdmiCecVolumeControlFeatureListener_notifiesStateUpdate() {
        mHdmiControlServiceSpy.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        VolumeControlFeatureCallback callback = new VolumeControlFeatureCallback();

        mHdmiControlServiceSpy.addHdmiCecVolumeControlFeatureListener(callback);

        mHdmiControlServiceSpy.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        mTestLooper.dispatchAll();

        assertThat(callback.mCallbackReceived).isTrue();
        assertThat(callback.mVolumeControlEnabled).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
    }

    @Test
    public void addHdmiCecVolumeControlFeatureListener_honorsUnregistration() {
        mHdmiControlServiceSpy.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        VolumeControlFeatureCallback callback = new VolumeControlFeatureCallback();

        mHdmiControlServiceSpy.addHdmiCecVolumeControlFeatureListener(callback);
        mTestLooper.dispatchAll();

        mHdmiControlServiceSpy.removeHdmiControlVolumeControlStatusChangeListener(callback);
        mHdmiControlServiceSpy.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        mTestLooper.dispatchAll();

        assertThat(callback.mCallbackReceived).isTrue();
        assertThat(callback.mVolumeControlEnabled).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
    }

    @Test
    public void addHdmiCecVolumeControlFeatureListener_notifiesStateUpdate_multiple() {
        mHdmiControlServiceSpy.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        VolumeControlFeatureCallback callback1 = new VolumeControlFeatureCallback();
        VolumeControlFeatureCallback callback2 = new VolumeControlFeatureCallback();

        mHdmiControlServiceSpy.addHdmiCecVolumeControlFeatureListener(callback1);
        mHdmiControlServiceSpy.addHdmiCecVolumeControlFeatureListener(callback2);


        mHdmiControlServiceSpy.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        mTestLooper.dispatchAll();

        assertThat(callback1.mCallbackReceived).isTrue();
        assertThat(callback2.mCallbackReceived).isTrue();
        assertThat(callback1.mVolumeControlEnabled).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        assertThat(callback2.mVolumeControlEnabled).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
    }

    @Test
    public void getCecVersion_1_4() {
        mHdmiControlServiceSpy.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
        mHdmiControlServiceSpy.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        assertThat(mHdmiControlServiceSpy.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
    }

    @Test
    public void getCecVersion_2_0() {
        mHdmiControlServiceSpy.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiControlServiceSpy.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        assertThat(mHdmiControlServiceSpy.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
    }

    @Test
    public void getCecVersion_change() {
        mHdmiControlServiceSpy.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
        mHdmiControlServiceSpy.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        assertThat(mHdmiControlServiceSpy.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);

        mHdmiControlServiceSpy.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiControlServiceSpy.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        assertThat(mHdmiControlServiceSpy.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
    }

    @Test
    public void handleGiveFeatures_cec14_featureAbort() {
        mHdmiControlServiceSpy.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
        mHdmiControlServiceSpy.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        mHdmiControlServiceSpy.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        mNativeWrapper.onCecMessage(HdmiCecMessageBuilder.buildGiveFeatures(Constants.ADDR_TV,
                Constants.ADDR_PLAYBACK_1));
        mTestLooper.dispatchAll();

        HdmiCecMessage featureAbort = HdmiCecMessageBuilder.buildFeatureAbortCommand(
                Constants.ADDR_PLAYBACK_1, Constants.ADDR_TV, Constants.MESSAGE_GIVE_FEATURES,
                Constants.ABORT_UNRECOGNIZED_OPCODE);
        assertThat(mNativeWrapper.getResultMessages()).contains(featureAbort);
    }

    @Test
    public void handleGiveFeatures_cec20_reportsFeatures() {
        mHdmiControlServiceSpy.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiControlServiceSpy.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        mHdmiControlServiceSpy.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        mNativeWrapper.onCecMessage(HdmiCecMessageBuilder.buildGiveFeatures(Constants.ADDR_TV,
                Constants.ADDR_PLAYBACK_1));
        mTestLooper.dispatchAll();

        HdmiCecMessage reportFeatures = HdmiCecMessageBuilder.buildReportFeatures(
                Constants.ADDR_PLAYBACK_1, HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Arrays.asList(DEVICE_PLAYBACK, DEVICE_AUDIO_SYSTEM),
                mPlaybackDeviceSpy.getRcProfile(), mPlaybackDeviceSpy.getRcFeatures(),
                mPlaybackDeviceSpy.getDeviceFeatures());
        assertThat(mNativeWrapper.getResultMessages()).contains(reportFeatures);
    }

    @Test
    public void initializeCec_14_doesNotBroadcastReportFeatures() {
        mNativeWrapper.clearResultMessages();
        mHdmiControlServiceSpy.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
        mHdmiControlServiceSpy.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        mHdmiControlServiceSpy.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportFeatures = HdmiCecMessageBuilder.buildReportFeatures(
                Constants.ADDR_PLAYBACK_1, HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Arrays.asList(DEVICE_PLAYBACK, DEVICE_AUDIO_SYSTEM),
                mPlaybackDeviceSpy.getRcProfile(), mPlaybackDeviceSpy.getRcFeatures(),
                mPlaybackDeviceSpy.getDeviceFeatures());
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(reportFeatures);
    }

    @Test
    public void initializeCec_20_reportsFeaturesBroadcast() {
        mHdmiControlServiceSpy.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiControlServiceSpy.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        mHdmiControlServiceSpy.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportFeatures = HdmiCecMessageBuilder.buildReportFeatures(
                Constants.ADDR_PLAYBACK_1, HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Arrays.asList(DEVICE_PLAYBACK, DEVICE_AUDIO_SYSTEM),
                mPlaybackDeviceSpy.getRcProfile(), mPlaybackDeviceSpy.getRcFeatures(),
                mPlaybackDeviceSpy.getDeviceFeatures());
        assertThat(mNativeWrapper.getResultMessages()).contains(reportFeatures);
    }

    @Test
    public void runOnServiceThread_preservesAndRestoresWorkSourceUid() {
        int callerUid = 1234;
        int runnerUid = 5678;

        Binder.setCallingWorkSourceUid(callerUid);
        WorkSourceUidReadingRunnable uidReadingRunnable = new WorkSourceUidReadingRunnable();
        mHdmiControlServiceSpy.runOnServiceThread(uidReadingRunnable);

        Binder.setCallingWorkSourceUid(runnerUid);

        mTestLooper.dispatchAll();

        assertEquals(Optional.of(callerUid), uidReadingRunnable.getWorkSourceUid());
        assertEquals(runnerUid, Binder.getCallingWorkSourceUid());
    }

    @Test
    public void initCecVersion_limitToMinimumSupportedVersion() {
        mNativeWrapper.setCecVersion(HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
        mHdmiControlServiceSpy.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);

        mTestLooper.dispatchAll();
        assertThat(mHdmiControlServiceSpy.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
    }

    @Test
    public void initCecVersion_limitToAtLeast1_4() {
        mNativeWrapper.setCecVersion(0x0);
        mHdmiControlServiceSpy.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);

        mTestLooper.dispatchAll();
        assertThat(mHdmiControlServiceSpy.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
    }

    @Test
    public void initCecVersion_useHighestMatchingVersion() {
        mNativeWrapper.setCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiControlServiceSpy.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);

        mTestLooper.dispatchAll();
        assertThat(mHdmiControlServiceSpy.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
    }

    private static class VolumeControlFeatureCallback extends
            IHdmiCecVolumeControlFeatureListener.Stub {
        boolean mCallbackReceived = false;
        int mVolumeControlEnabled = -1;

        @Override
        public void onHdmiCecVolumeControlFeature(int enabled) throws RemoteException {
            this.mCallbackReceived = true;
            this.mVolumeControlEnabled = enabled;
        }
    }

    @Test
    public void handleCecCommand_errorParameter_returnsAbortInvalidOperand() {
        // Validity ERROR_PARAMETER. Taken from HdmiCecMessageValidatorTest#isValid_menuStatus
        HdmiCecMessage message = HdmiUtils.buildMessage("40:8D:03");

        assertThat(mHdmiControlServiceSpy.handleCecCommand(message))
                .isEqualTo(Constants.ABORT_INVALID_OPERAND);
    }

    @Test
    public void handleCecCommand_errorSource_returnsHandled() {
        // Validity ERROR_SOURCE. Taken from HdmiCecMessageValidatorTest#isValid_menuStatus
        HdmiCecMessage message = HdmiUtils.buildMessage("F0:8E");

        assertThat(mHdmiControlServiceSpy.handleCecCommand(message))
                .isEqualTo(Constants.HANDLED);

    }

    @Test
    public void handleCecCommand_errorDestination_returnsHandled() {
        // Validity ERROR_DESTINATION. Taken from HdmiCecMessageValidatorTest#isValid_menuStatus
        HdmiCecMessage message = HdmiUtils.buildMessage("0F:8E:00");

        assertThat(mHdmiControlServiceSpy.handleCecCommand(message))
                .isEqualTo(Constants.HANDLED);
    }

    @Test
    public void handleCecCommand_errorParameterShort_returnsHandled() {
        // Validity ERROR_PARAMETER_SHORT
        // Taken from HdmiCecMessageValidatorTest#isValid_menuStatus
        HdmiCecMessage message = HdmiUtils.buildMessage("40:8E");

        assertThat(mHdmiControlServiceSpy.handleCecCommand(message))
                .isEqualTo(Constants.HANDLED);
    }

    @Test
    public void handleCecCommand_notHandledByLocalDevice_returnsNotHandled() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildReportPowerStatus(
                Constants.ADDR_TV,
                Constants.ADDR_PLAYBACK_1,
                HdmiControlManager.POWER_STATUS_ON);

        doReturn(Constants.NOT_HANDLED).when(mHdmiControlServiceSpy)
                .dispatchMessageToLocalDevice(message);

        assertThat(mHdmiControlServiceSpy.handleCecCommand(message))
                .isEqualTo(Constants.NOT_HANDLED);
    }

    @Test
    public void handleCecCommand_handledByLocalDevice_returnsHandled() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildReportPowerStatus(
                Constants.ADDR_TV,
                Constants.ADDR_PLAYBACK_1,
                HdmiControlManager.POWER_STATUS_ON);

        doReturn(Constants.HANDLED).when(mHdmiControlServiceSpy)
                .dispatchMessageToLocalDevice(message);

        assertThat(mHdmiControlServiceSpy.handleCecCommand(message))
                .isEqualTo(Constants.HANDLED);
    }

    @Test
    public void handleCecCommand_localDeviceReturnsFeatureAbort_returnsFeatureAbort() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildReportPowerStatus(
                Constants.ADDR_TV,
                Constants.ADDR_PLAYBACK_1,
                HdmiControlManager.POWER_STATUS_ON);

        doReturn(Constants.ABORT_REFUSED).when(mHdmiControlServiceSpy)
                .dispatchMessageToLocalDevice(message);

        assertThat(mHdmiControlServiceSpy.handleCecCommand(message))
                .isEqualTo(Constants.ABORT_REFUSED);
    }

    @Test
    public void dispatchMessageToLocalDevice_broadcastMessage_returnsHandled() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildStandby(
                Constants.ADDR_TV,
                Constants.ADDR_BROADCAST);

        doReturn(Constants.ABORT_REFUSED).when(mPlaybackDeviceSpy).dispatchMessage(message);
        doReturn(Constants.ABORT_NOT_IN_CORRECT_MODE)
                .when(mAudioSystemDeviceSpy).dispatchMessage(message);

        assertThat(mHdmiControlServiceSpy.dispatchMessageToLocalDevice(message))
                .isEqualTo(Constants.HANDLED);
    }

    @Test
    public void dispatchMessageToLocalDevice_localDevicesDoNotHandleMessage_returnsUnhandled() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildStandby(
                Constants.ADDR_TV,
                Constants.ADDR_PLAYBACK_1);

        doReturn(Constants.NOT_HANDLED).when(mPlaybackDeviceSpy).dispatchMessage(message);
        doReturn(Constants.NOT_HANDLED)
                .when(mAudioSystemDeviceSpy).dispatchMessage(message);

        assertThat(mHdmiControlServiceSpy.dispatchMessageToLocalDevice(message))
                .isEqualTo(Constants.NOT_HANDLED);
    }

    @Test
    public void dispatchMessageToLocalDevice_localDeviceHandlesMessage_returnsHandled() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildStandby(
                Constants.ADDR_TV,
                Constants.ADDR_PLAYBACK_1);

        doReturn(Constants.NOT_HANDLED).when(mPlaybackDeviceSpy).dispatchMessage(message);
        doReturn(Constants.HANDLED)
                .when(mAudioSystemDeviceSpy).dispatchMessage(message);

        assertThat(mHdmiControlServiceSpy.dispatchMessageToLocalDevice(message))
                .isEqualTo(Constants.HANDLED);
    }

    @Test
    public void dispatchMessageToLocalDevice_localDeviceReturnsFeatureAbort_returnsFeatureAbort() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildStandby(
                Constants.ADDR_TV,
                Constants.ADDR_PLAYBACK_1);

        doReturn(Constants.NOT_HANDLED).when(mPlaybackDeviceSpy).dispatchMessage(message);
        doReturn(Constants.ABORT_REFUSED)
                .when(mAudioSystemDeviceSpy).dispatchMessage(message);

        assertThat(mHdmiControlServiceSpy.dispatchMessageToLocalDevice(message))
                .isEqualTo(Constants.ABORT_REFUSED);
    }

    @Test
    public void readDeviceTypes_readsIntegerDeviceTypes() {
        doReturn(Arrays.asList(new Integer[]{DEVICE_PLAYBACK, DEVICE_AUDIO_SYSTEM}))
                .when(mHdmiControlServiceSpy).getDeviceTypes();
        doReturn(Arrays.asList(new HdmiProperties.cec_device_types_values[]{}))
                .when(mHdmiControlServiceSpy).getCecDeviceTypes();

        assertThat(mHdmiControlServiceSpy.readDeviceTypes())
                .containsExactly(DEVICE_PLAYBACK, DEVICE_AUDIO_SYSTEM);
    }

    @Test
    public void readDeviceTypes_readsEnumDeviceTypes() {
        doReturn(Arrays.asList(new Integer[]{})).when(mHdmiControlServiceSpy).getDeviceTypes();
        doReturn(Arrays.asList(
                new HdmiProperties.cec_device_types_values[]{
                        HdmiProperties.cec_device_types_values.PLAYBACK_DEVICE,
                        HdmiProperties.cec_device_types_values.AUDIO_SYSTEM
                }))
                .when(mHdmiControlServiceSpy).getCecDeviceTypes();

        assertThat(mHdmiControlServiceSpy.readDeviceTypes())
                .containsExactly(DEVICE_PLAYBACK, DEVICE_AUDIO_SYSTEM);
    }

    @Test
    public void readDeviceTypes_readsEnumOverIntegerDeviceTypes() {
        doReturn(Arrays.asList(new Integer[]{DEVICE_TV}))
                .when(mHdmiControlServiceSpy).getDeviceTypes();
        doReturn(Arrays.asList(
                new HdmiProperties.cec_device_types_values[]{
                        HdmiProperties.cec_device_types_values.PLAYBACK_DEVICE,
                        HdmiProperties.cec_device_types_values.AUDIO_SYSTEM
                }))
                .when(mHdmiControlServiceSpy).getCecDeviceTypes();

        assertThat(mHdmiControlServiceSpy.readDeviceTypes())
                .containsExactly(DEVICE_PLAYBACK, DEVICE_AUDIO_SYSTEM);
    }

    @Test
    public void readDeviceTypes_doesNotReadNullEnumDeviceType() {
        doReturn(Arrays.asList(new Integer[]{})).when(mHdmiControlServiceSpy).getDeviceTypes();
        doReturn(Arrays.asList(
                new HdmiProperties.cec_device_types_values[]{
                        HdmiProperties.cec_device_types_values.PLAYBACK_DEVICE,
                        HdmiProperties.cec_device_types_values.AUDIO_SYSTEM,
                        null
                }))
                .when(mHdmiControlServiceSpy).getCecDeviceTypes();

        assertThat(mHdmiControlServiceSpy.readDeviceTypes())
                .containsExactly(DEVICE_PLAYBACK, DEVICE_AUDIO_SYSTEM);
    }

    @Test
    public void readDeviceTypes_doesNotReadNullIntegerDeviceType() {
        doReturn(Arrays.asList(new Integer[]{DEVICE_PLAYBACK, DEVICE_AUDIO_SYSTEM, null}))
                .when(mHdmiControlServiceSpy).getDeviceTypes();
        doReturn(Arrays.asList(new HdmiProperties.cec_device_types_values[]{}))
                .when(mHdmiControlServiceSpy).getCecDeviceTypes();

        assertThat(mHdmiControlServiceSpy.readDeviceTypes())
                .containsExactly(DEVICE_PLAYBACK, DEVICE_AUDIO_SYSTEM);
    }

}
