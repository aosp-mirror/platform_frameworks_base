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

import static org.junit.Assert.assertEquals;

import android.annotation.Nullable;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.os.Looper;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.hdmi.HdmiCecLocalDeviceAudioSystem.TvSystemAudioModeSupportedCallback;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Collections;

/** Tests for {@link DetectTvSystemAudioModeSupportAction} class. */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class DetectTvSystemAudioModeSupportActionTest {

    private HdmiDeviceInfo mDeviceInfoForTests;
    private HdmiCecLocalDeviceAudioSystem mHdmiCecLocalDeviceAudioSystem;
    private DetectTvSystemAudioModeSupportAction mAction;

    private TestLooper mTestLooper = new TestLooper();
    private boolean mSendCecCommandSuccess;
    private boolean mShouldDispatchFeatureAbort;
    private Boolean mSupported;

    @Before
    public void SetUp() {
        mDeviceInfoForTests = HdmiDeviceInfo.hardwarePort(1001, 1234);
        HdmiControlService hdmiControlService =
                new HdmiControlService(InstrumentationRegistry.getTargetContext(),
                        Collections.emptyList(), new FakeAudioDeviceVolumeManagerWrapper()) {

                    @Override
                    void sendCecCommand(
                            HdmiCecMessage command, @Nullable SendMessageCallback callback) {
                        switch (command.getOpcode()) {
                            case Constants.MESSAGE_SET_SYSTEM_AUDIO_MODE:
                                if (callback != null) {
                                    callback.onSendCompleted(
                                            mSendCecCommandSuccess
                                                    ? SendMessageResult.SUCCESS
                                                    : SendMessageResult.NACK);
                                }
                                if (mShouldDispatchFeatureAbort) {
                                    mHdmiCecLocalDeviceAudioSystem.dispatchMessage(
                                            HdmiCecMessageBuilder.buildFeatureAbortCommand(
                                                    Constants.ADDR_TV,
                                                    mHdmiCecLocalDeviceAudioSystem
                                                            .getDeviceInfo()
                                                            .getLogicalAddress(),
                                                    Constants.MESSAGE_SET_SYSTEM_AUDIO_MODE,
                                                    Constants.ABORT_UNRECOGNIZED_OPCODE));
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
                    protected Looper getServiceLooper() {
                        return mTestLooper.getLooper();
                    }
                };
        mHdmiCecLocalDeviceAudioSystem =
                new HdmiCecLocalDeviceAudioSystem(hdmiControlService) {
                    @Override
                    HdmiDeviceInfo getDeviceInfo() {
                        return mDeviceInfoForTests;
                    }
                };
        mHdmiCecLocalDeviceAudioSystem.init();
        mHdmiCecLocalDeviceAudioSystem.setDeviceInfo(mDeviceInfoForTests);
        Looper looper = mTestLooper.getLooper();
        hdmiControlService.setIoLooper(looper);

        mAction =
                new DetectTvSystemAudioModeSupportAction(
                        mHdmiCecLocalDeviceAudioSystem,
                        new TvSystemAudioModeSupportedCallback() {
                            public void onResult(boolean supported) {
                                mSupported = Boolean.valueOf(supported);
                            }
                        });
        mSupported = null;
    }

    @Test
    public void testSendCecCommandNotSucceed() {
        mSendCecCommandSuccess = false;
        mShouldDispatchFeatureAbort = false;
        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(mAction);
        mTestLooper.dispatchAll();
        assertEquals(Boolean.FALSE, mSupported);
    }

    @Test
    public void testFeatureAbort() {
        mSendCecCommandSuccess = true;
        mShouldDispatchFeatureAbort = true;
        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(mAction);
        mTestLooper.dispatchAll();
        assertEquals(Boolean.FALSE, mSupported);
    }

    @Test
    public void testSupported() {
        mSendCecCommandSuccess = true;
        mShouldDispatchFeatureAbort = false;
        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(mAction);
        mTestLooper.moveTimeForward(2000);
        mTestLooper.dispatchAll();
        assertEquals(Boolean.TRUE, mSupported);
    }
}
