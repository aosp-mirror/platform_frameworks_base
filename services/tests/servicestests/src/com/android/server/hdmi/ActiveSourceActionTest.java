/*
 * Copyright (C) 2020 The Android Open Source Project
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
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.spy;

import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
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

/** Tests for {@link ActiveSourceAction} */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class ActiveSourceActionTest {

    private Context mContextSpy;
    private HdmiControlService mHdmiControlService;
    private FakeNativeWrapper mNativeWrapper;
    private FakePowerManagerWrapper mPowerManager;

    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private int mPhysicalAddress;

    @Before
    public void setUp() throws Exception {
        assumeTrue("Test requires FEATURE_HDMI_CEC",
                InstrumentationRegistry.getTargetContext().getPackageManager()
                        .hasSystemFeature(FEATURE_HDMI_CEC));
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));

        FakeAudioFramework audioFramework = new FakeAudioFramework();
        mHdmiControlService = new HdmiControlService(mContextSpy, Collections.emptyList(),
                audioFramework.getAudioManager(), audioFramework.getAudioDeviceVolumeManager()) {
            @Override
            boolean isPowerStandby() {
                return false;
            }

            @Override
            protected void writeStringSystemProperty(String key, String value) {
                // do nothing
            }

            @Override
            protected void sendBroadcastAsUser(@RequiresPermission Intent intent) {
                // do nothing
            }
        };

        Looper looper = mTestLooper.getLooper();
        mHdmiControlService.setIoLooper(looper);
        mHdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(mContextSpy));
        mHdmiControlService.setDeviceConfig(new FakeDeviceConfigWrapper());
        mNativeWrapper = new FakeNativeWrapper();
        mPhysicalAddress = 0x2000;
        mNativeWrapper.setPhysicalAddress(mPhysicalAddress);
        HdmiCecController hdmiCecController = HdmiCecController.createWithNativeWrapper(
                this.mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(hdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.initService();
        mHdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);
        mPowerManager = new FakePowerManagerWrapper(mContextSpy);
        mHdmiControlService.setPowerManager(mPowerManager);
        mTestLooper.dispatchAll();
    }

    @Test
    public void playbackDevice_sendsActiveSource_sendsMenuStatus() {
        HdmiCecLocalDevicePlayback playbackDevice = new HdmiCecLocalDevicePlayback(
                mHdmiControlService);
        playbackDevice.init();
        mLocalDevices.add(playbackDevice);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        HdmiCecFeatureAction action = new com.android.server.hdmi.ActiveSourceAction(
                playbackDevice, ADDR_TV);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource =
                HdmiCecMessageBuilder.buildActiveSource(
                        playbackDevice.getDeviceInfo().getLogicalAddress(), mPhysicalAddress);
        HdmiCecMessage menuStatus =
                HdmiCecMessageBuilder.buildReportMenuStatus(
                        playbackDevice.getDeviceInfo().getLogicalAddress(),
                        ADDR_TV,
                        Constants.MENU_STATE_ACTIVATED);

        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(menuStatus);
    }

    @Test
    public void playbackDevice_updatesActiveSourceState() {
        HdmiCecLocalDevicePlayback playbackDevice = new HdmiCecLocalDevicePlayback(
                mHdmiControlService);
        playbackDevice.init();
        mLocalDevices.add(playbackDevice);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();

        HdmiCecFeatureAction action = new com.android.server.hdmi.ActiveSourceAction(
                playbackDevice, ADDR_TV);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        assertThat(playbackDevice.getActiveSource().logicalAddress)
                .isEqualTo(playbackDevice.getDeviceInfo().getLogicalAddress());
        assertThat(playbackDevice.getActiveSource().physicalAddress).isEqualTo(mPhysicalAddress);
        assertThat(playbackDevice.isActiveSource()).isTrue();
    }
}
