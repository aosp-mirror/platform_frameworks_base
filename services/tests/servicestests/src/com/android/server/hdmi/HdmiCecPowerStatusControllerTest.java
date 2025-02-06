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

import static android.content.pm.PackageManager.FEATURE_HDMI_CEC;

import static com.android.server.SystemService.PHASE_SYSTEM_SERVICES_READY;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.spy;

import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.os.Looper;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.SystemService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Collections;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
/** Tests for {@link HdmiCecPowerStatusController} class. */
public class HdmiCecPowerStatusControllerTest {

    public static final int[] ARRAY_POWER_STATUS = new int[]{HdmiControlManager.POWER_STATUS_ON,
            HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON,
            HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY,
            HdmiControlManager.POWER_STATUS_STANDBY};
    private HdmiCecPowerStatusController mHdmiCecPowerStatusController;
    private FakeNativeWrapper mNativeWrapper;
    private FakePowerManagerWrapper mPowerManager;
    private TestLooper mTestLooper = new TestLooper();
    private HdmiControlService mHdmiControlService;
    private HdmiCecLocalDevicePlayback mHdmiCecLocalDevicePlayback;

    @Before
    public void setUp() throws Exception {
        assumeTrue("Test requires FEATURE_HDMI_CEC",
                InstrumentationRegistry.getTargetContext().getPackageManager()
                        .hasSystemFeature(FEATURE_HDMI_CEC));
        Context contextSpy = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));
        Looper myLooper = mTestLooper.getLooper();

        FakeAudioFramework audioFramework = new FakeAudioFramework();

        mHdmiControlService = new HdmiControlService(contextSpy,
                Collections.singletonList(HdmiDeviceInfo.DEVICE_PLAYBACK),
                audioFramework.getAudioManager(), audioFramework.getAudioDeviceVolumeManager()) {
            @Override
            boolean isCecControlEnabled() {
                return true;
            }

            @Override
            boolean isPlaybackDevice() {
                return true;
            }

            @Override
            protected void writeStringSystemProperty(String key, String value) {
                // do nothing
            }

            @Override
            boolean isPowerStandby() {
                return false;
            }

            @Override
            protected void sendBroadcastAsUser(@RequiresPermission Intent intent) {
                // do nothing
            }
        };
        mHdmiControlService.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);

        mHdmiControlService.setIoLooper(myLooper);
        mHdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(contextSpy));
        mHdmiControlService.setDeviceConfig(new FakeDeviceConfigWrapper());
        mNativeWrapper = new FakeNativeWrapper();
        HdmiCecController hdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(hdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        HdmiPortInfo[] hdmiPortInfos = new HdmiPortInfo[1];
        hdmiPortInfos[0] =
                new HdmiPortInfo.Builder(1, HdmiPortInfo.PORT_OUTPUT, 0x0000)
                        .setCecSupported(true)
                        .setMhlSupported(false)
                        .setArcSupported(false)
                        .build();
        mNativeWrapper.setPortInfo(hdmiPortInfos);
        mNativeWrapper.setPortConnectionStatus(1, true);
        mNativeWrapper.setPhysicalAddress(0x2000);
        mHdmiControlService.initService();
        mHdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);
        mPowerManager = new FakePowerManagerWrapper(contextSpy);
        mHdmiControlService.setPowerManager(mPowerManager);
        mHdmiControlService.getHdmiCecNetwork().initPortInfo();
        mTestLooper.dispatchAll();
        mHdmiCecLocalDevicePlayback = mHdmiControlService.playback();
        mHdmiCecPowerStatusController = new HdmiCecPowerStatusController(mHdmiControlService);
        mNativeWrapper.clearResultMessages();
    }

    @Test
    public void setPowerStatus() {
        for (int status : ARRAY_POWER_STATUS) {
            mHdmiCecPowerStatusController.setPowerStatus(status);
            assertThat(mHdmiCecPowerStatusController.getPowerStatus()).isEqualTo(status);
        }
    }

    @Test
    public void isPowerStatusOn() {
        for (int status : ARRAY_POWER_STATUS) {
            mHdmiCecPowerStatusController.setPowerStatus(status);
            assertThat(mHdmiCecPowerStatusController.isPowerStatusOn()).isEqualTo(
                    HdmiControlManager.POWER_STATUS_ON == status);
        }
    }

    @Test
    public void isPowerStatusStandby() {
        for (int status : ARRAY_POWER_STATUS) {
            mHdmiCecPowerStatusController.setPowerStatus(status);
            assertThat(mHdmiCecPowerStatusController.isPowerStatusStandby()).isEqualTo(
                    HdmiControlManager.POWER_STATUS_STANDBY == status);
        }
    }

    @Test
    public void isPowerStatusTransientToOn() {
        for (int status : ARRAY_POWER_STATUS) {
            mHdmiCecPowerStatusController.setPowerStatus(status);
            assertThat(mHdmiCecPowerStatusController.isPowerStatusTransientToOn()).isEqualTo(
                    HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON == status);
        }
    }

    @Test
    public void isPowerStatusTransientToStandby() {
        for (int status : ARRAY_POWER_STATUS) {
            mHdmiCecPowerStatusController.setPowerStatus(status);
            assertThat(mHdmiCecPowerStatusController.isPowerStatusTransientToStandby()).isEqualTo(
                    HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY == status);
        }
    }

    @Test
    public void setPowerStatus_doesntSendBroadcast_1_4() {
        setCecVersion(HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
        mHdmiCecPowerStatusController.setPowerStatus(HdmiControlManager.POWER_STATUS_ON);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportPowerStatus =
                HdmiCecMessageBuilder.buildReportPowerStatus(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        Constants.ADDR_BROADCAST,
                        HdmiControlManager.POWER_STATUS_ON);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(reportPowerStatus);
    }

    @Test
    public void setPowerStatus_transient_doesntSendBroadcast_1_4() {
        setCecVersion(HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
        mHdmiCecPowerStatusController.setPowerStatus(
                HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportPowerStatus =
                HdmiCecMessageBuilder.buildReportPowerStatus(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        Constants.ADDR_BROADCAST,
                        HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(reportPowerStatus);
    }

    @Test
    public void setPowerStatus_fast_transient_doesntSendBroadcast_1_4() {
        setCecVersion(HdmiControlManager.HDMI_CEC_VERSION_1_4_B);
        mHdmiCecPowerStatusController.setPowerStatus(
                HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON, false);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportPowerStatus =
                HdmiCecMessageBuilder.buildReportPowerStatus(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        Constants.ADDR_BROADCAST,
                        HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(reportPowerStatus);
    }

    @Test
    public void setPowerStatus_sendsBroadcast_2_0() {
        setCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiCecPowerStatusController.setPowerStatus(HdmiControlManager.POWER_STATUS_ON);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportPowerStatus =
                HdmiCecMessageBuilder.buildReportPowerStatus(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        Constants.ADDR_BROADCAST,
                        HdmiControlManager.POWER_STATUS_ON);
        assertThat(mNativeWrapper.getResultMessages()).contains(reportPowerStatus);
    }

    @Test
    public void setPowerStatus_transient_sendsBroadcast_2_0() {
        setCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiCecPowerStatusController.setPowerStatus(
                HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportPowerStatus =
                HdmiCecMessageBuilder.buildReportPowerStatus(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        Constants.ADDR_BROADCAST,
                        HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON);
        assertThat(mNativeWrapper.getResultMessages()).contains(reportPowerStatus);
    }

    @Test
    public void setPowerStatus_fast_transient_doesntSendBroadcast_2_0() {
        setCecVersion(HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiCecPowerStatusController.setPowerStatus(
                HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON, false);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportPowerStatus =
                HdmiCecMessageBuilder.buildReportPowerStatus(
                        mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress(),
                        Constants.ADDR_BROADCAST,
                        HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(reportPowerStatus);
    }

    private void setCecVersion(int version) {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION, version);
        mTestLooper.dispatchAll();
    }
}
