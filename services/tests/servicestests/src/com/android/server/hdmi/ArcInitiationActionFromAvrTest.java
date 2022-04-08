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

import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import android.annotation.Nullable;
import android.app.Instrumentation;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.os.Looper;
import android.os.test.TestLooper;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;

/** Tests for {@link ArcInitiationActionFromAvrTest} */
@SmallTest
@RunWith(JUnit4.class)
public class ArcInitiationActionFromAvrTest {

    private HdmiDeviceInfo mDeviceInfoForTests;
    private HdmiCecLocalDeviceAudioSystem mHdmiCecLocalDeviceAudioSystem;
    private HdmiCecController mHdmiCecController;
    private HdmiControlService mHdmiControlService;
    private FakeNativeWrapper mNativeWrapper;
    private ArcInitiationActionFromAvr mAction;

    private TestLooper mTestLooper = new TestLooper();
    private boolean mSendCecCommandSuccess;
    private boolean mShouldDispatchARCInitiated;
    private boolean mArcInitSent;
    private boolean mRequestActiveSourceSent;
    private Instrumentation mInstrumentation;
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();

    @Before
    public void setUp() {
        mDeviceInfoForTests = new HdmiDeviceInfo(1000, 1);

        mInstrumentation = InstrumentationRegistry.getInstrumentation();

        mHdmiControlService =
                new HdmiControlService(mInstrumentation.getTargetContext()) {
                    @Override
                    void sendCecCommand(
                            HdmiCecMessage command, @Nullable SendMessageCallback callback) {
                        switch (command.getOpcode()) {
                            case Constants.MESSAGE_REQUEST_ACTIVE_SOURCE:
                                if (callback != null) {
                                    callback.onSendCompleted(
                                            mSendCecCommandSuccess
                                                    ? SendMessageResult.SUCCESS
                                                    : SendMessageResult.NACK);
                                }
                                mRequestActiveSourceSent = true;
                                break;
                            case Constants.MESSAGE_INITIATE_ARC:
                                if (callback != null) {
                                    callback.onSendCompleted(
                                            mSendCecCommandSuccess
                                                    ? SendMessageResult.SUCCESS
                                                    : SendMessageResult.NACK);
                                }
                                mArcInitSent = true;
                                if (mShouldDispatchARCInitiated) {
                                    mHdmiCecLocalDeviceAudioSystem.dispatchMessage(
                                            HdmiCecMessageBuilder.buildReportArcInitiated(
                                                    Constants.ADDR_TV,
                                                    Constants.ADDR_AUDIO_SYSTEM));
                                }
                                break;
                            default:
                        }
                    }

                    @Override
                    boolean isPowerStandby() {
                        return false;
                    }

                    @Override
                    boolean isAddressAllocated() {
                        return true;
                    }

                    @Override
                    Looper getServiceLooper() {
                        return mTestLooper.getLooper();
                    }
                };

        mHdmiCecLocalDeviceAudioSystem =
                new HdmiCecLocalDeviceAudioSystem(mHdmiControlService) {
                    @Override
                    HdmiDeviceInfo getDeviceInfo() {
                        return mDeviceInfoForTests;
                    }

                    @Override
                    void setArcStatus(boolean enabled) {
                        // do nothing
                    }

                    @Override
                    protected boolean isSystemAudioActivated() {
                        return true;
                    }
                };

        mHdmiCecLocalDeviceAudioSystem.init();
        Looper looper = mTestLooper.getLooper();
        mHdmiControlService.setIoLooper(looper);
        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController =
                HdmiCecController.createWithNativeWrapper(this.mHdmiControlService, mNativeWrapper);
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.setMessageValidator(new HdmiCecMessageValidator(mHdmiControlService));
        mHdmiControlService.initPortInfo();
        mAction = new ArcInitiationActionFromAvr(mHdmiCecLocalDeviceAudioSystem);

        mLocalDevices.add(mHdmiCecLocalDeviceAudioSystem);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();
    }

    @Ignore("b/120845532")
    @Test
    public void arcInitiation_requestActiveSource() {
        mSendCecCommandSuccess = true;
        mShouldDispatchARCInitiated = true;
        mRequestActiveSourceSent = false;
        mArcInitSent = false;

        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(mAction);
        mTestLooper.dispatchAll();

        assertThat(mArcInitSent).isTrue();
        assertThat(mRequestActiveSourceSent).isTrue();
    }
}
