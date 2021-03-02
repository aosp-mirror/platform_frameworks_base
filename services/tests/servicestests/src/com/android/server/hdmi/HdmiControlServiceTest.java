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

import static com.android.server.SystemService.PHASE_BOOT_COMPLETED;
import static com.android.server.SystemService.PHASE_SYSTEM_SERVICES_READY;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.assertEquals;

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
import android.provider.Settings;
import android.util.Log;

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

    private class MockPlaybackDevice extends HdmiCecLocalDevicePlayback {

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
    private class MockAudioSystemDevice extends HdmiCecLocalDeviceAudioSystem {

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
    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private MockAudioSystemDevice mAudioSystemDevice;
    private MockPlaybackDevice mPlaybackDevice;
    private FakeNativeWrapper mNativeWrapper;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private boolean mStandbyMessageReceived;
    private HdmiPortInfo[] mHdmiPortInfo;

    @Mock private IPowerManager mIPowerManagerMock;
    @Mock private IThermalService mIThermalServiceMock;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));

        PowerManager powerManager = new PowerManager(mContextSpy, mIPowerManagerMock,
                mIThermalServiceMock, null);
        when(mContextSpy.getSystemService(Context.POWER_SERVICE)).thenReturn(powerManager);
        when(mContextSpy.getSystemService(PowerManager.class)).thenReturn(powerManager);
        when(mIPowerManagerMock.isInteractive()).thenReturn(true);

        HdmiCecConfig hdmiCecConfig = new FakeHdmiCecConfig(mContextSpy);

        mHdmiControlService = new HdmiControlService(mContextSpy) {
            @Override
            boolean isStandbyMessageReceived() {
                return mStandbyMessageReceived;
            }

            @Override
            protected void writeStringSystemProperty(String key, String value) {
            }
        };
        mMyLooper = mTestLooper.getLooper();

        mAudioSystemDevice = new MockAudioSystemDevice(mHdmiControlService);
        mPlaybackDevice = new MockPlaybackDevice(mHdmiControlService);
        mAudioSystemDevice.init();
        mPlaybackDevice.init();

        mHdmiControlService.setIoLooper(mMyLooper);
        mHdmiControlService.setHdmiCecConfig(hdmiCecConfig);
        mHdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);

        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.setMessageValidator(new HdmiCecMessageValidator(mHdmiControlService));

        mLocalDevices.add(mAudioSystemDevice);
        mLocalDevices.add(mPlaybackDevice);
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
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED,
                HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        mHdmiControlService.initService();
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);

        mTestLooper.dispatchAll();
    }

    @Test
    public void onStandby_notByCec_cannotGoToStandby() {
        mStandbyMessageReceived = false;
        mPlaybackDevice.setCanGoToStandby(false);

        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        assertTrue(mPlaybackDevice.isStandby());
        assertTrue(mAudioSystemDevice.isStandby());
        assertFalse(mPlaybackDevice.isDisabled());
        assertFalse(mAudioSystemDevice.isDisabled());
    }

    @Test
    public void onStandby_byCec() {
        mStandbyMessageReceived = true;

        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        assertTrue(mPlaybackDevice.isStandby());
        assertTrue(mAudioSystemDevice.isStandby());
        assertTrue(mPlaybackDevice.isDisabled());
        assertTrue(mAudioSystemDevice.isDisabled());
    }

    @Test
    public void initialPowerStatus_normalBoot_isTransientToStandby() {
        assertThat(mHdmiControlService.getInitialPowerStatus()).isEqualTo(
                HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY);
    }

    @Test
    public void initialPowerStatus_quiescentBoot_isTransientToStandby() throws RemoteException {
        when(mIPowerManagerMock.isInteractive()).thenReturn(false);
        assertThat(mHdmiControlService.getInitialPowerStatus()).isEqualTo(
                HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY);
    }

    @Test
    public void powerStatusAfterBootComplete_normalBoot_isOn() {
        mHdmiControlService.setPowerStatus(HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON);
        mHdmiControlService.onBootPhase(PHASE_BOOT_COMPLETED);
        assertThat(mHdmiControlService.getPowerStatus()).isEqualTo(
                HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON);
    }

    @Test
    public void powerStatusAfterBootComplete_quiescentBoot_isStandby() throws RemoteException {
        when(mIPowerManagerMock.isInteractive()).thenReturn(false);
        mHdmiControlService.onBootPhase(PHASE_BOOT_COMPLETED);
        assertThat(mHdmiControlService.getPowerStatus()).isEqualTo(
                HdmiControlManager.POWER_STATUS_STANDBY);
    }

    @Test
    public void initialPowerStatus_normalBoot_goToStandby_doesNotBroadcastsPowerStatus_1_4() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);

        mHdmiControlService.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        mNativeWrapper.clearResultMessages();

        assertThat(mHdmiControlService.getInitialPowerStatus()).isEqualTo(
                HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY);

        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);

        HdmiCecMessage reportPowerStatus = HdmiCecMessageBuilder.buildReportPowerStatus(
                Constants.ADDR_PLAYBACK_1, Constants.ADDR_BROADCAST,
                HdmiControlManager.POWER_STATUS_STANDBY);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(reportPowerStatus);
    }

    @Test
    public void initialPowerStatus_normalBoot_goToStandby_broadcastsPowerStatus_2_0() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mTestLooper.dispatchAll();

        mHdmiControlService.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        mNativeWrapper.clearResultMessages();

        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        assertThat(mHdmiControlService.getInitialPowerStatus()).isEqualTo(
                HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY);

        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportPowerStatus = HdmiCecMessageBuilder.buildReportPowerStatus(
                Constants.ADDR_PLAYBACK_1, Constants.ADDR_BROADCAST,
                HdmiControlManager.POWER_STATUS_STANDBY);
        assertThat(mNativeWrapper.getResultMessages()).contains(reportPowerStatus);
    }

    @Test
    public void setAndGetCecVolumeControlEnabled_isApi() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE,
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        assertThat(mHdmiControlService.getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE)).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);

        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE,
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        assertThat(mHdmiControlService.getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE)).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
    }

    @Test
    public void setAndGetCecVolumeControlEnabled_changesSetting() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE,
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        assertThat(mHdmiControlService.readIntSetting(
                Settings.Global.HDMI_CONTROL_VOLUME_CONTROL_ENABLED, -1)).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);

        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE,
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        assertThat(mHdmiControlService.readIntSetting(
                Settings.Global.HDMI_CONTROL_VOLUME_CONTROL_ENABLED, -1)).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
    }

    @Test
    public void setAndGetCecVolumeControlEnabledInternal_doesNotChangeSetting() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE,
                HdmiControlManager.VOLUME_CONTROL_ENABLED);

        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        assertThat(mHdmiControlService.getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE)).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);

        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        assertThat(mHdmiControlService.getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE)).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
    }

    @Test
    public void disableAndReenableCec_volumeControlReturnsToOriginalValue_enabled() {
        int volumeControlEnabled = HdmiControlManager.VOLUME_CONTROL_ENABLED;
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(volumeControlEnabled);

        mHdmiControlService.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED);
        assertThat(mHdmiControlService.getHdmiCecVolumeControl()).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);

        mHdmiControlService.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        assertThat(mHdmiControlService.getHdmiCecVolumeControl()).isEqualTo(volumeControlEnabled);
    }

    @Test
    public void disableAndReenableCec_volumeControlReturnsToOriginalValue_disabled() {
        int volumeControlEnabled = HdmiControlManager.VOLUME_CONTROL_DISABLED;
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE, volumeControlEnabled);

        mHdmiControlService.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED);
        assertThat(mHdmiControlService.getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE)).isEqualTo(
                volumeControlEnabled);

        mHdmiControlService.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        assertThat(mHdmiControlService.getHdmiCecConfig().getIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE)).isEqualTo(
                volumeControlEnabled);
    }

    @Test
    public void disableAndReenableCec_volumeControlFeatureListenersNotified() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_VOLUME_CONTROL_MODE,
                HdmiControlManager.VOLUME_CONTROL_ENABLED);

        VolumeControlFeatureCallback callback = new VolumeControlFeatureCallback();
        mHdmiControlService.addHdmiCecVolumeControlFeatureListener(callback);

        mHdmiControlService.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_DISABLED);
        assertThat(callback.mCallbackReceived).isTrue();
        assertThat(callback.mVolumeControlEnabled).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);


        mHdmiControlService.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        assertThat(callback.mVolumeControlEnabled).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
    }

    @Test
    public void addHdmiCecVolumeControlFeatureListener_emitsCurrentState_enabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        VolumeControlFeatureCallback callback = new VolumeControlFeatureCallback();

        mHdmiControlService.addHdmiCecVolumeControlFeatureListener(callback);
        mTestLooper.dispatchAll();

        assertThat(callback.mCallbackReceived).isTrue();
        assertThat(callback.mVolumeControlEnabled).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
    }

    @Test
    public void addHdmiCecVolumeControlFeatureListener_emitsCurrentState_disabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        VolumeControlFeatureCallback callback = new VolumeControlFeatureCallback();

        mHdmiControlService.addHdmiCecVolumeControlFeatureListener(callback);
        mTestLooper.dispatchAll();

        assertThat(callback.mCallbackReceived).isTrue();
        assertThat(callback.mVolumeControlEnabled).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
    }

    @Test
    public void addHdmiCecVolumeControlFeatureListener_notifiesStateUpdate() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        VolumeControlFeatureCallback callback = new VolumeControlFeatureCallback();

        mHdmiControlService.addHdmiCecVolumeControlFeatureListener(callback);

        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        mTestLooper.dispatchAll();

        assertThat(callback.mCallbackReceived).isTrue();
        assertThat(callback.mVolumeControlEnabled).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
    }

    @Test
    public void addHdmiCecVolumeControlFeatureListener_honorsUnregistration() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        VolumeControlFeatureCallback callback = new VolumeControlFeatureCallback();

        mHdmiControlService.addHdmiCecVolumeControlFeatureListener(callback);
        mTestLooper.dispatchAll();

        mHdmiControlService.removeHdmiControlVolumeControlStatusChangeListener(callback);
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        mTestLooper.dispatchAll();

        assertThat(callback.mCallbackReceived).isTrue();
        assertThat(callback.mVolumeControlEnabled).isEqualTo(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
    }

    @Test
    public void addHdmiCecVolumeControlFeatureListener_notifiesStateUpdate_multiple() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        VolumeControlFeatureCallback callback1 = new VolumeControlFeatureCallback();
        VolumeControlFeatureCallback callback2 = new VolumeControlFeatureCallback();

        mHdmiControlService.addHdmiCecVolumeControlFeatureListener(callback1);
        mHdmiControlService.addHdmiCecVolumeControlFeatureListener(callback2);


        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
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
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
        mHdmiControlService.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        assertThat(mHdmiControlService.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
    }

    @Test
    public void getCecVersion_2_0() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiControlService.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        assertThat(mHdmiControlService.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
    }

    @Test
    public void getCecVersion_change() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
        mHdmiControlService.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        assertThat(mHdmiControlService.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);

        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiControlService.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        assertThat(mHdmiControlService.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
    }

    @Test
    public void handleGiveFeatures_cec14_featureAbort() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
        mHdmiControlService.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
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
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiControlService.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        mNativeWrapper.onCecMessage(HdmiCecMessageBuilder.buildGiveFeatures(Constants.ADDR_TV,
                Constants.ADDR_PLAYBACK_1));
        mTestLooper.dispatchAll();

        HdmiCecMessage reportFeatures = HdmiCecMessageBuilder.buildReportFeatures(
                Constants.ADDR_PLAYBACK_1, HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Arrays.asList(DEVICE_PLAYBACK, DEVICE_AUDIO_SYSTEM),
                mPlaybackDevice.getRcProfile(), mPlaybackDevice.getRcFeatures(),
                mPlaybackDevice.getDeviceFeatures());
        assertThat(mNativeWrapper.getResultMessages()).contains(reportFeatures);
    }

    @Test
    public void initializeCec_14_doesNotBroadcastReportFeatures() {
        mNativeWrapper.clearResultMessages();
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
        mHdmiControlService.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportFeatures = HdmiCecMessageBuilder.buildReportFeatures(
                Constants.ADDR_PLAYBACK_1, HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Arrays.asList(DEVICE_PLAYBACK, DEVICE_AUDIO_SYSTEM),
                mPlaybackDevice.getRcProfile(), mPlaybackDevice.getRcFeatures(),
                mPlaybackDevice.getDeviceFeatures());
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(reportFeatures);
    }

    @Test
    public void initializeCec_20_reportsFeaturesBroadcast() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiControlService.setControlEnabled(HdmiControlManager.HDMI_CEC_CONTROL_ENABLED);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportFeatures = HdmiCecMessageBuilder.buildReportFeatures(
                Constants.ADDR_PLAYBACK_1, HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Arrays.asList(DEVICE_PLAYBACK, DEVICE_AUDIO_SYSTEM),
                mPlaybackDevice.getRcProfile(), mPlaybackDevice.getRcFeatures(),
                mPlaybackDevice.getDeviceFeatures());
        assertThat(mNativeWrapper.getResultMessages()).contains(reportFeatures);
    }

    @Test
    public void runOnServiceThread_preservesAndRestoresWorkSourceUid() {
        int callerUid = 1234;
        int runnerUid = 5678;

        Binder.setCallingWorkSourceUid(callerUid);
        WorkSourceUidReadingRunnable uidReadingRunnable = new WorkSourceUidReadingRunnable();
        mHdmiControlService.runOnServiceThread(uidReadingRunnable);

        Binder.setCallingWorkSourceUid(runnerUid);

        mTestLooper.dispatchAll();

        assertEquals(Optional.of(callerUid), uidReadingRunnable.getWorkSourceUid());
        assertEquals(runnerUid, Binder.getCallingWorkSourceUid());
    }

    @Test
    public void initCecVersion_limitToMinimumSupportedVersion() {
        mNativeWrapper.setCecVersion(HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
        Log.e("MARVIN", "set setting CEC");
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);

        mTestLooper.dispatchAll();
        assertThat(mHdmiControlService.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
    }

    @Test
    public void initCecVersion_limitToAtLeast1_4() {
        Log.e("MARVIN", "set HAL CEC to 0");
        mNativeWrapper.setCecVersion(0x0);
        Log.e("MARVIN", "set setting CEC to 2");
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);

        mTestLooper.dispatchAll();
        assertThat(mHdmiControlService.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
    }

    @Test
    public void initCecVersion_useHighestMatchingVersion() {
        mNativeWrapper.setCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0);
        Log.e("MARVIN", "set setting CEC");
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);

        mTestLooper.dispatchAll();
        assertThat(mHdmiControlService.getCecVersion()).isEqualTo(
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
}
