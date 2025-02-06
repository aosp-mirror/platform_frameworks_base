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
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.spy;

import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.os.Looper;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Collections;

/** Tests for {@link ArcInitiationActionFromAvrTest} */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class ArcInitiationActionFromAvrTest {

    private Context mContextSpy;
    private HdmiCecLocalDeviceAudioSystem mHdmiCecLocalDeviceAudioSystem;
    private FakeNativeWrapper mNativeWrapper;
    private FakePowerManagerWrapper mPowerManager;
    private ArcInitiationActionFromAvr mAction;

    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        assumeTrue("Test requires FEATURE_HDMI_CEC",
                InstrumentationRegistry.getTargetContext().getPackageManager()
                        .hasSystemFeature(FEATURE_HDMI_CEC));
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));

        FakeAudioFramework audioFramework = new FakeAudioFramework();

        HdmiControlService hdmiControlService =
                new HdmiControlService(mContextSpy, Collections.emptyList(),
                        audioFramework.getAudioManager(),
                        audioFramework.getAudioDeviceVolumeManager()) {
                    @Override
                    boolean isPowerStandby() {
                        return false;
                    }


                    @Override
                    boolean isAddressAllocated() {
                        return true;
                    }

                    @Override
                    protected void writeStringSystemProperty(String key, String value) {
                    }

                    @Override
                    protected Looper getServiceLooper() {
                        return mTestLooper.getLooper();
                    }

                    @Override
                    protected void sendBroadcastAsUser(@RequiresPermission Intent intent) {
                        // do nothing
                    }
                };

        mHdmiCecLocalDeviceAudioSystem = new HdmiCecLocalDeviceAudioSystem(hdmiControlService) {
            @Override
            protected void setPreferredAddress(int addr) {
            }
        };

        mHdmiCecLocalDeviceAudioSystem.init();
        Looper looper = mTestLooper.getLooper();
        hdmiControlService.setIoLooper(looper);
        hdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(mContextSpy));
        hdmiControlService.setDeviceConfig(new FakeDeviceConfigWrapper());
        mNativeWrapper = new FakeNativeWrapper();
        HdmiCecController hdmiCecController = HdmiCecController.createWithNativeWrapper(
                hdmiControlService, mNativeWrapper, hdmiControlService.getAtomWriter());
        hdmiControlService.setCecController(hdmiCecController);
        hdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(hdmiControlService));
        hdmiControlService.initService();
        mPowerManager = new FakePowerManagerWrapper(mContextSpy);
        hdmiControlService.setPowerManager(mPowerManager);
        mAction = new ArcInitiationActionFromAvr(mHdmiCecLocalDeviceAudioSystem);

        mLocalDevices.add(mHdmiCecLocalDeviceAudioSystem);
        hdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);
        hdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();
    }

    @Test
    public void arcInitiation_initiated() {
        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(mAction);
        mTestLooper.dispatchAll();
        HdmiCecMessage initiateArc = HdmiCecMessageBuilder.buildInitiateArc(
                Constants.ADDR_AUDIO_SYSTEM, Constants.ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(initiateArc);

        mNativeWrapper.onCecMessage(
                HdmiCecMessageBuilder.buildReportArcInitiated(
                        Constants.ADDR_TV,
                        Constants.ADDR_AUDIO_SYSTEM));
        mTestLooper.dispatchAll();

        assertThat(mHdmiCecLocalDeviceAudioSystem.isArcEnabled()).isTrue();
    }

    @Test
    public void arcInitiation_sendFailed() {
        mNativeWrapper.setMessageSendResult(Constants.MESSAGE_INITIATE_ARC, SendMessageResult.NACK);
        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(mAction);
        mTestLooper.dispatchAll();
        HdmiCecMessage initiateArc = HdmiCecMessageBuilder.buildInitiateArc(
                Constants.ADDR_AUDIO_SYSTEM, Constants.ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(initiateArc);

        assertThat(mHdmiCecLocalDeviceAudioSystem.isArcEnabled()).isFalse();
    }

    @Test
    public void arcInitiation_terminated() {
        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(mAction);
        mTestLooper.dispatchAll();

        HdmiCecMessage initiateArc = HdmiCecMessageBuilder.buildInitiateArc(
                Constants.ADDR_AUDIO_SYSTEM, Constants.ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(initiateArc);

        mNativeWrapper.onCecMessage(HdmiCecMessageBuilder.buildReportArcTerminated(
                Constants.ADDR_TV,
                Constants.ADDR_AUDIO_SYSTEM));
        mTestLooper.dispatchAll();

        assertThat(mHdmiCecLocalDeviceAudioSystem.isArcEnabled()).isFalse();
    }

    @Test
    public void arcInitiation_abort() {
        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(mAction);
        mTestLooper.dispatchAll();

        HdmiCecMessage initiateArc = HdmiCecMessageBuilder.buildInitiateArc(
                Constants.ADDR_AUDIO_SYSTEM, Constants.ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(initiateArc);

        mNativeWrapper.onCecMessage(
                HdmiCecMessageBuilder.buildFeatureAbortCommand(
                        Constants.ADDR_TV,
                        Constants.ADDR_AUDIO_SYSTEM, Constants.MESSAGE_INITIATE_ARC,
                        Constants.ABORT_REFUSED));
        mTestLooper.dispatchAll();

        assertThat(mHdmiCecLocalDeviceAudioSystem.isArcEnabled()).isFalse();
    }

    //Fail
    @Test
    public void arcInitiation_timeout() {
        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(mAction);
        mTestLooper.dispatchAll();

        HdmiCecMessage initiateArc = HdmiCecMessageBuilder.buildInitiateArc(
                Constants.ADDR_AUDIO_SYSTEM, Constants.ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(initiateArc);

        mTestLooper.moveTimeForward(1001);
        mTestLooper.dispatchAll();
        assertThat(mHdmiCecLocalDeviceAudioSystem.isArcEnabled()).isTrue();
    }
}
