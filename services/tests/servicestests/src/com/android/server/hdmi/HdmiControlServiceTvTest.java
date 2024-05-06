/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.hardware.hdmi.HdmiDeviceInfo.DEVICE_TV;

import static com.android.server.SystemService.PHASE_SYSTEM_SERVICES_READY;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.os.Looper;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Collections;
import java.util.List;

/**
 * TV specific tests for {@link HdmiControlService} class.
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class HdmiControlServiceTvTest {

    private static final String TAG = "HdmiControlServiceTvTest";
    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDeviceTv mHdmiCecLocalDeviceTv;
    private FakeNativeWrapper mNativeWrapper;
    private HdmiEarcController mHdmiEarcController;
    private FakeEarcNativeWrapper mEarcNativeWrapper;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        mMyLooper = mTestLooper.getLooper();

        FakeAudioFramework audioFramework = new FakeAudioFramework();

        mHdmiControlService =
                new HdmiControlService(InstrumentationRegistry.getTargetContext(),
                        Collections.singletonList(HdmiDeviceInfo.DEVICE_TV),
                        audioFramework.getAudioManager(),
                        audioFramework.getAudioDeviceVolumeManager()) {
                    @Override
                    int pathToPortId(int path) {
                        return Constants.INVALID_PORT_ID + 1;
                    }
                };

        mMyLooper = mTestLooper.getLooper();
        mHdmiControlService.setIoLooper(mMyLooper);
        mHdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(context));
        mHdmiControlService.setDeviceConfig(new FakeDeviceConfigWrapper());
        mHdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);

        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(mHdmiCecController);
        mEarcNativeWrapper = new FakeEarcNativeWrapper();
        mHdmiEarcController = HdmiEarcController.createWithNativeWrapper(
                mHdmiControlService, mEarcNativeWrapper);
        mHdmiControlService.setEarcController(mHdmiEarcController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(
                mHdmiControlService));
        mHdmiControlService.initService();

        mTestLooper.dispatchAll();

        mHdmiCecLocalDeviceTv = mHdmiControlService.tv();
    }

    @Test
    public void onCecMessage_shortPhysicalAddress_featureAbortInvalidOperand() {
        // Invalid <Inactive Source> message.
        HdmiCecMessage message = HdmiUtils.buildMessage("40:9D:14");

        mNativeWrapper.onCecMessage(message);
        mTestLooper.dispatchAll();

        HdmiCecMessage featureAbort = HdmiCecMessageBuilder.buildFeatureAbortCommand(
                Constants.ADDR_TV, Constants.ADDR_PLAYBACK_1, Constants.MESSAGE_INACTIVE_SOURCE,
                Constants.ABORT_INVALID_OPERAND);
        assertThat(mNativeWrapper.getResultMessages()).contains(featureAbort);
    }

    @Test
    public void handleCecCommand_shortPhysicalAddress_returnsAbortInvalidOperand() {
        // Invalid <Active Source> message.
        HdmiCecMessage message = HdmiUtils.buildMessage("4F:82:10");

        // In case of a broadcasted message <Feature Abort> is not expected.
        // See CEC 1.4b specification, 12.2 Protocol General Rules for detail.
        assertThat(mHdmiControlService.handleCecCommand(message))
                .isEqualTo(Constants.ABORT_INVALID_OPERAND);
    }

    @Test
    public void test_verifyPhysicalAddresses() {
        // <Routing Change>
        assertThat(mHdmiControlService
                .verifyPhysicalAddresses(HdmiUtils.buildMessage("0F:80:10:00:40:00"))).isTrue();
        assertThat(mHdmiControlService
                .verifyPhysicalAddresses(HdmiUtils.buildMessage("0F:80:10:00:40"))).isFalse();
        assertThat(mHdmiControlService
                .verifyPhysicalAddresses(HdmiUtils.buildMessage("0F:80:10"))).isFalse();

        // <System Audio Mode Request>
        assertThat(mHdmiControlService
                .verifyPhysicalAddresses(HdmiUtils.buildMessage("40:70:00:00"))).isTrue();
        assertThat(mHdmiControlService
                .verifyPhysicalAddresses(HdmiUtils.buildMessage("40:70:00"))).isFalse();

        // <Active Source>
        assertThat(mHdmiControlService
                .verifyPhysicalAddresses(HdmiUtils.buildMessage("4F:82:10:00"))).isTrue();
        assertThat(mHdmiControlService
                .verifyPhysicalAddresses(HdmiUtils.buildMessage("4F:82:10"))).isFalse();
    }

    @Test
    public void setRcProfileTv_reportFeatureBroadcast() {
        mNativeWrapper.clearResultMessages();

        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_VERSION,
                HdmiControlManager.HDMI_CEC_VERSION_2_0);
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_RC_PROFILE_TV,
                HdmiControlManager.RC_PROFILE_TV_NONE);
        mTestLooper.dispatchAll();

        HdmiCecMessage reportFeatures = ReportFeaturesMessage.build(Constants.ADDR_TV,
                HdmiControlManager.HDMI_CEC_VERSION_2_0, List.of(DEVICE_TV),
                mHdmiCecLocalDeviceTv.getRcProfile(), mHdmiCecLocalDeviceTv.getRcFeatures(),
                mHdmiCecLocalDeviceTv.getDeviceFeatures());
        assertThat(mNativeWrapper.getResultMessages()).contains(reportFeatures);
    }
}
