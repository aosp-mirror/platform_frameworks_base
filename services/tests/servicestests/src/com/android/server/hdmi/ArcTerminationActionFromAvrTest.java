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

/** Tests for {@link ArcTerminationActionFromAvr} */
@SmallTest
@RunWith(JUnit4.class)
public class ArcTerminationActionFromAvrTest {

    private HdmiDeviceInfo mDeviceInfoForTests;
    private HdmiCecLocalDeviceAudioSystem mHdmiCecLocalDeviceAudioSystem;
    private ArcTerminationActionFromAvr mAction;

    private TestLooper mTestLooper = new TestLooper();
    private boolean mSendCecCommandSuccess;
    private boolean mShouldDispatchReportArcTerminated;
    private Instrumentation mInstrumentation;
    @Nullable private Boolean mArcEnabled = null;

    @Before
    public void setUp() {
        mDeviceInfoForTests = new HdmiDeviceInfo(1000, 1);

        mInstrumentation = InstrumentationRegistry.getInstrumentation();

        HdmiControlService hdmiControlService =
                new HdmiControlService(mInstrumentation.getTargetContext()) {
                    @Override
                    void sendCecCommand(
                            HdmiCecMessage command, @Nullable SendMessageCallback callback) {
                        switch (command.getOpcode()) {
                            case Constants.MESSAGE_TERMINATE_ARC:
                                if (callback != null) {
                                    callback.onSendCompleted(
                                            mSendCecCommandSuccess
                                                    ? SendMessageResult.SUCCESS
                                                    : SendMessageResult.NACK);
                                }
                                if (mShouldDispatchReportArcTerminated) {
                                    mHdmiCecLocalDeviceAudioSystem.dispatchMessage(
                                            HdmiCecMessageBuilder.buildReportArcTerminated(
                                                    Constants.ADDR_TV,
                                                    mHdmiCecLocalDeviceAudioSystem.mAddress));
                                }
                                break;
                            default:
                                throw new IllegalArgumentException("Unexpected message");
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
                new HdmiCecLocalDeviceAudioSystem(hdmiControlService) {
                    @Override
                    HdmiDeviceInfo getDeviceInfo() {
                        return mDeviceInfoForTests;
                    }

                    @Override
                    void setArcStatus(boolean enabled) {
                        mArcEnabled = enabled;
                    }
                };
        mHdmiCecLocalDeviceAudioSystem.init();
        Looper looper = mTestLooper.getLooper();
        hdmiControlService.setIoLooper(looper);

        mAction = new ArcTerminationActionFromAvr(mHdmiCecLocalDeviceAudioSystem);
    }

    @Test
    @Ignore("b/120845532")
    public void testSendMessage_notSuccess() {
        mSendCecCommandSuccess = false;
        mShouldDispatchReportArcTerminated = false;
        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(mAction);

        mTestLooper.dispatchAll();
        assertThat(mArcEnabled).isNull();
    }

    @Test
    public void testReportArcTerminated_notReceived() {
        mSendCecCommandSuccess = true;
        mShouldDispatchReportArcTerminated = false;
        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(mAction);

        mTestLooper.moveTimeForward(1000);
        mTestLooper.dispatchAll();
        assertThat(mArcEnabled).isNull();
    }

    @Test
    public void testReportArcTerminated_received() {
        mSendCecCommandSuccess = true;
        mShouldDispatchReportArcTerminated = true;
        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(mAction);

        mTestLooper.moveTimeForward(1000);
        mTestLooper.dispatchAll();
        assertThat(mArcEnabled).isFalse();
    }
}
