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
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.hdmi.IHdmiCecVolumeControlFeatureListener;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;

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

/**
 * Tests for {@link HdmiControlService} class.
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class HdmiControlServiceTest {

    private class HdmiCecLocalDeviceMyDevice extends HdmiCecLocalDeviceSource {

        private boolean mCanGoToStandby;
        private boolean mIsStandby;
        private boolean mIsDisabled;

        protected HdmiCecLocalDeviceMyDevice(HdmiControlService service, int deviceType) {
            super(service, deviceType);
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
    private HdmiCecLocalDeviceMyDevice mMyAudioSystemDevice;
    private HdmiCecLocalDeviceMyDevice mMyPlaybackDevice;
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
        when(mIPowerManagerMock.isInteractive()).thenReturn(true);

        HdmiCecConfig hdmiCecConfig = new FakeHdmiCecConfig(mContextSpy);

        mHdmiControlService = new HdmiControlService(mContextSpy) {
            @Override
            boolean isStandbyMessageReceived() {
                return mStandbyMessageReceived;
            }

            @Override
            protected HdmiCecConfig getHdmiCecConfig() {
                return hdmiCecConfig;
            }
        };
        mMyLooper = mTestLooper.getLooper();

        mMyAudioSystemDevice =
                new HdmiCecLocalDeviceMyDevice(mHdmiControlService, DEVICE_AUDIO_SYSTEM);
        mMyPlaybackDevice = new HdmiCecLocalDeviceMyDevice(mHdmiControlService, DEVICE_PLAYBACK);
        mMyAudioSystemDevice.init();
        mMyPlaybackDevice.init();

        mHdmiControlService.setIoLooper(mMyLooper);

        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.setMessageValidator(new HdmiCecMessageValidator(mHdmiControlService));

        mLocalDevices.add(mMyAudioSystemDevice);
        mLocalDevices.add(mMyPlaybackDevice);
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
        mHdmiControlService.initService();
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);

        mTestLooper.dispatchAll();
    }

    @Test
    public void onStandby_notByCec_cannotGoToStandby() {
        mStandbyMessageReceived = false;
        mMyPlaybackDevice.setCanGoToStandby(false);

        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        assertTrue(mMyPlaybackDevice.isStandby());
        assertTrue(mMyAudioSystemDevice.isStandby());
        assertFalse(mMyPlaybackDevice.isDisabled());
        assertFalse(mMyAudioSystemDevice.isDisabled());
    }

    @Test
    public void onStandby_byCec() {
        mStandbyMessageReceived = true;

        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        assertTrue(mMyPlaybackDevice.isStandby());
        assertTrue(mMyAudioSystemDevice.isStandby());
        assertTrue(mMyPlaybackDevice.isDisabled());
        assertTrue(mMyAudioSystemDevice.isDisabled());
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

        mHdmiControlService.setControlEnabled(true);
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

        mHdmiControlService.setControlEnabled(true);
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
        mHdmiControlService.setHdmiCecVolumeControlEnabled(false);
        assertThat(mHdmiControlService.isHdmiCecVolumeControlEnabled()).isFalse();

        mHdmiControlService.setHdmiCecVolumeControlEnabled(true);
        assertThat(mHdmiControlService.isHdmiCecVolumeControlEnabled()).isTrue();
    }

    @Test
    public void setAndGetCecVolumeControlEnabled_changesSetting() {
        mHdmiControlService.setHdmiCecVolumeControlEnabled(false);
        assertThat(mHdmiControlService.readBooleanSetting(
                Settings.Global.HDMI_CONTROL_VOLUME_CONTROL_ENABLED, true)).isFalse();

        mHdmiControlService.setHdmiCecVolumeControlEnabled(true);
        assertThat(mHdmiControlService.readBooleanSetting(
                Settings.Global.HDMI_CONTROL_VOLUME_CONTROL_ENABLED, true)).isTrue();
    }

    @Test
    public void setAndGetCecVolumeControlEnabledInternal_doesNotChangeSetting() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(true);

        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(false);
        assertThat(mHdmiControlService.readBooleanSetting(
                Settings.Global.HDMI_CONTROL_VOLUME_CONTROL_ENABLED, true)).isTrue();

        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(true);
        assertThat(mHdmiControlService.readBooleanSetting(
                Settings.Global.HDMI_CONTROL_VOLUME_CONTROL_ENABLED, true)).isTrue();
    }

    @Test
    public void disableAndReenableCec_volumeControlReturnsToOriginalValue_enabled() {
        boolean volumeControlEnabled = true;
        mHdmiControlService.setHdmiCecVolumeControlEnabled(volumeControlEnabled);

        mHdmiControlService.setControlEnabled(false);
        assertThat(mHdmiControlService.isHdmiCecVolumeControlEnabled()).isFalse();

        mHdmiControlService.setControlEnabled(true);
        assertThat(mHdmiControlService.isHdmiCecVolumeControlEnabled()).isEqualTo(
                volumeControlEnabled);
    }

    @Test
    public void disableAndReenableCec_volumeControlReturnsToOriginalValue_disabled() {
        boolean volumeControlEnabled = false;
        mHdmiControlService.setHdmiCecVolumeControlEnabled(volumeControlEnabled);

        mHdmiControlService.setControlEnabled(false);
        assertThat(mHdmiControlService.isHdmiCecVolumeControlEnabled()).isFalse();

        mHdmiControlService.setControlEnabled(true);
        assertThat(mHdmiControlService.isHdmiCecVolumeControlEnabled()).isEqualTo(
                volumeControlEnabled);
    }

    @Test
    public void disableAndReenableCec_volumeControlFeatureListenersNotified() {
        mHdmiControlService.setHdmiCecVolumeControlEnabled(true);

        VolumeControlFeatureCallback callback = new VolumeControlFeatureCallback();
        mHdmiControlService.addHdmiCecVolumeControlFeatureListener(callback);

        mHdmiControlService.setControlEnabled(false);
        assertThat(callback.mCallbackReceived).isTrue();
        assertThat(callback.mVolumeControlEnabled).isFalse();


        mHdmiControlService.setControlEnabled(true);
        assertThat(callback.mVolumeControlEnabled).isTrue();
    }

    @Test
    public void addHdmiCecVolumeControlFeatureListener_emitsCurrentState_enabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabled(true);
        VolumeControlFeatureCallback callback = new VolumeControlFeatureCallback();

        mHdmiControlService.addHdmiCecVolumeControlFeatureListener(callback);
        mTestLooper.dispatchAll();

        assertThat(callback.mCallbackReceived).isTrue();
        assertThat(callback.mVolumeControlEnabled).isTrue();
    }

    @Test
    public void addHdmiCecVolumeControlFeatureListener_emitsCurrentState_disabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabled(false);
        VolumeControlFeatureCallback callback = new VolumeControlFeatureCallback();

        mHdmiControlService.addHdmiCecVolumeControlFeatureListener(callback);
        mTestLooper.dispatchAll();

        assertThat(callback.mCallbackReceived).isTrue();
        assertThat(callback.mVolumeControlEnabled).isFalse();
    }

    @Test
    public void addHdmiCecVolumeControlFeatureListener_notifiesStateUpdate() {
        mHdmiControlService.setHdmiCecVolumeControlEnabled(false);
        VolumeControlFeatureCallback callback = new VolumeControlFeatureCallback();

        mHdmiControlService.addHdmiCecVolumeControlFeatureListener(callback);

        mHdmiControlService.setHdmiCecVolumeControlEnabled(true);
        mTestLooper.dispatchAll();

        assertThat(callback.mCallbackReceived).isTrue();
        assertThat(callback.mVolumeControlEnabled).isTrue();
    }

    @Test
    public void addHdmiCecVolumeControlFeatureListener_honorsUnregistration() {
        mHdmiControlService.setHdmiCecVolumeControlEnabled(false);
        VolumeControlFeatureCallback callback = new VolumeControlFeatureCallback();

        mHdmiControlService.addHdmiCecVolumeControlFeatureListener(callback);
        mTestLooper.dispatchAll();

        mHdmiControlService.removeHdmiControlVolumeControlStatusChangeListener(callback);
        mHdmiControlService.setHdmiCecVolumeControlEnabled(true);
        mTestLooper.dispatchAll();

        assertThat(callback.mCallbackReceived).isTrue();
        assertThat(callback.mVolumeControlEnabled).isFalse();
    }

    @Test
    public void addHdmiCecVolumeControlFeatureListener_notifiesStateUpdate_multiple() {
        mHdmiControlService.setHdmiCecVolumeControlEnabled(false);
        VolumeControlFeatureCallback callback1 = new VolumeControlFeatureCallback();
        VolumeControlFeatureCallback callback2 = new VolumeControlFeatureCallback();

        mHdmiControlService.addHdmiCecVolumeControlFeatureListener(callback1);
        mHdmiControlService.addHdmiCecVolumeControlFeatureListener(callback2);


        mHdmiControlService.setHdmiCecVolumeControlEnabled(true);
        mTestLooper.dispatchAll();

        assertThat(callback1.mCallbackReceived).isTrue();
        assertThat(callback2.mCallbackReceived).isTrue();
        assertThat(callback1.mVolumeControlEnabled).isTrue();
        assertThat(callback2.mVolumeControlEnabled).isTrue();
    }

    @Test
    public void getCecVersion_default() {
        // Set the Settings value to "null" to emulate it being empty and force the default value.
        Settings.Global.putString(mContextSpy.getContentResolver(),
                Settings.Global.HDMI_CEC_VERSION,
                null);
        mHdmiControlService.setControlEnabled(true);
        assertThat(mHdmiControlService.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
    }

    @Test
    public void getCecVersion_1_4() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
        mHdmiControlService.setControlEnabled(true);
        assertThat(mHdmiControlService.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
    }

    @Test
    public void getCecVersion_2_0() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiControlService.setControlEnabled(true);
        assertThat(mHdmiControlService.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
    }

    @Test
    public void getCecVersion_change() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
        mHdmiControlService.setControlEnabled(true);
        assertThat(mHdmiControlService.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);

        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiControlService.setControlEnabled(true);
        assertThat(mHdmiControlService.getCecVersion()).isEqualTo(
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
    }

    @Test
    public void handleGiveFeatures_cec14_featureAbort() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
        mHdmiControlService.setControlEnabled(true);
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
        mHdmiControlService.setControlEnabled(true);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        mNativeWrapper.onCecMessage(HdmiCecMessageBuilder.buildGiveFeatures(Constants.ADDR_TV,
                Constants.ADDR_PLAYBACK_1));
        mTestLooper.dispatchAll();

        HdmiCecMessage reportFeatures = HdmiCecMessageBuilder.buildReportFeatures(
                Constants.ADDR_PLAYBACK_1, HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Arrays.asList(DEVICE_PLAYBACK, DEVICE_AUDIO_SYSTEM),
                mMyPlaybackDevice.getRcProfile(), mMyPlaybackDevice.getRcFeatures(),
                mMyPlaybackDevice.getDeviceFeatures());
        assertThat(mNativeWrapper.getResultMessages()).contains(reportFeatures);
    }

    @Test
    public void initializeCec_14_doesNotBroadcastReportFeatures() {
        mNativeWrapper.clearResultMessages();
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
        mHdmiControlService.setControlEnabled(true);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportFeatures = HdmiCecMessageBuilder.buildReportFeatures(
                Constants.ADDR_PLAYBACK_1, HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Arrays.asList(DEVICE_PLAYBACK, DEVICE_AUDIO_SYSTEM),
                mMyPlaybackDevice.getRcProfile(), mMyPlaybackDevice.getRcFeatures(),
                mMyPlaybackDevice.getDeviceFeatures());
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(reportFeatures);
    }

    @Test
    public void initializeCec_20_reportsFeaturesBroadcast() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiControlService.setControlEnabled(true);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportFeatures = HdmiCecMessageBuilder.buildReportFeatures(
                Constants.ADDR_PLAYBACK_1, HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Arrays.asList(DEVICE_PLAYBACK, DEVICE_AUDIO_SYSTEM),
                mMyPlaybackDevice.getRcProfile(), mMyPlaybackDevice.getRcFeatures(),
                mMyPlaybackDevice.getDeviceFeatures());
        assertThat(mNativeWrapper.getResultMessages()).contains(reportFeatures);
    }

    private static class VolumeControlFeatureCallback extends
            IHdmiCecVolumeControlFeatureListener.Stub {
        boolean mCallbackReceived = false;
        boolean mVolumeControlEnabled = false;

        @Override
        public void onHdmiCecVolumeControlFeature(boolean enabled) throws RemoteException {
            this.mCallbackReceived = true;
            this.mVolumeControlEnabled = enabled;
        }
    }
}
