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

import static com.android.server.SystemService.PHASE_SYSTEM_SERVICES_READY;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;

import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.IHdmiControlCallback;
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
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;

/** Tests for {@link ArcTerminationActionFromAvr} */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class ArcTerminationActionFromAvrTest {

    private Context mContextSpy;
    private HdmiCecLocalDeviceAudioSystem mHdmiCecLocalDeviceAudioSystem;
    private FakePowerManagerWrapper mPowerManager;
    private TestCallback mCallback;
    private ArcTerminationActionFromAvr mAction;

    private FakeNativeWrapper mNativeWrapper;

    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

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
        mHdmiCecLocalDeviceAudioSystem = new HdmiCecLocalDeviceAudioSystem(hdmiControlService) {
            @Override
            protected void setPreferredAddress(int addr) {
            }
        };
        mHdmiCecLocalDeviceAudioSystem.init();
        mCallback = new TestCallback();
        mAction = new ArcTerminationActionFromAvr(mHdmiCecLocalDeviceAudioSystem,
                mCallback);

        mLocalDevices.add(mHdmiCecLocalDeviceAudioSystem);
        hdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);
        hdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mHdmiCecLocalDeviceAudioSystem.setArcStatus(true);
        mTestLooper.dispatchAll();
    }

    private static class TestCallback extends IHdmiControlCallback.Stub {
        private final ArrayList<Integer> mCallbackResult = new ArrayList<Integer>();

        @Override
        public void onComplete(int result) {
            mCallbackResult.add(result);
        }

        private int getResult() {
            assertThat(mCallbackResult.size()).isEqualTo(1);
            return mCallbackResult.get(0);
        }
    }

    @Test
    public void testSendMessage_sendFailed() {
        mNativeWrapper.setMessageSendResult(Constants.MESSAGE_TERMINATE_ARC,
                SendMessageResult.NACK);
        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(mAction);
        mTestLooper.dispatchAll();
        HdmiCecMessage terminateArc = HdmiCecMessageBuilder.buildTerminateArc(
                Constants.ADDR_AUDIO_SYSTEM, Constants.ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(terminateArc);

        assertThat(mHdmiCecLocalDeviceAudioSystem.isArcEnabled()).isFalse();
        assertThat(mCallback.getResult()).isEqualTo(HdmiControlManager.RESULT_TARGET_NOT_AVAILABLE);
    }

    @Test
    public void testReportArcTerminated_timeout() {
        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(mAction);
        mTestLooper.dispatchAll();
        HdmiCecMessage terminateArc = HdmiCecMessageBuilder.buildTerminateArc(
                Constants.ADDR_AUDIO_SYSTEM, Constants.ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(terminateArc);
        mTestLooper.dispatchAll();

        mTestLooper.moveTimeForward(ArcTerminationActionFromAvr.TIMEOUT_MS);
        mTestLooper.dispatchAll();

        assertThat(mHdmiCecLocalDeviceAudioSystem.isArcEnabled()).isFalse();
        assertThat(mCallback.getResult()).isEqualTo(HdmiControlManager.RESULT_TIMEOUT);
    }

    @Test
    public void testReportArcTerminated_received() {
        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(mAction);
        mTestLooper.dispatchAll();
        HdmiCecMessage terminateArc = HdmiCecMessageBuilder.buildTerminateArc(
                Constants.ADDR_AUDIO_SYSTEM, Constants.ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(terminateArc);

        HdmiCecMessage arcTerminatedResponse = HdmiCecMessageBuilder.buildReportArcTerminated(
                Constants.ADDR_TV, Constants.ADDR_AUDIO_SYSTEM);

        mNativeWrapper.onCecMessage(arcTerminatedResponse);
        mTestLooper.dispatchAll();

        assertThat(mHdmiCecLocalDeviceAudioSystem.isArcEnabled()).isFalse();
        assertThat(mCallback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void testReportArcTerminated_featureAbort() {
        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(mAction);
        mTestLooper.dispatchAll();
        HdmiCecMessage terminateArc = HdmiCecMessageBuilder.buildTerminateArc(
                Constants.ADDR_AUDIO_SYSTEM, Constants.ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(terminateArc);

        HdmiCecMessage arcTerminatedResponse = HdmiCecMessageBuilder.buildFeatureAbortCommand(
                Constants.ADDR_TV,
                Constants.ADDR_AUDIO_SYSTEM,
                Constants.MESSAGE_TERMINATE_ARC,
                Constants.ABORT_REFUSED);

        mNativeWrapper.onCecMessage(arcTerminatedResponse);
        mTestLooper.dispatchAll();

        assertThat(mHdmiCecLocalDeviceAudioSystem.isArcEnabled()).isFalse();
        assertThat(mCallback.getResult()).isEqualTo(HdmiControlManager.RESULT_TARGET_NOT_AVAILABLE);
    }
}
