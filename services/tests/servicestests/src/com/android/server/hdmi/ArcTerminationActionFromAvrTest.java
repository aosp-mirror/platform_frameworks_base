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

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.Looper;
import android.os.PowerManager;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

/** Tests for {@link ArcTerminationActionFromAvr} */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class ArcTerminationActionFromAvrTest {

    private Context mContextSpy;
    private HdmiCecLocalDeviceAudioSystem mHdmiCecLocalDeviceAudioSystem;
    private ArcTerminationActionFromAvr mAction;

    private FakeNativeWrapper mNativeWrapper;

    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();

    @Mock private IPowerManager mIPowerManagerMock;
    @Mock private IThermalService mIThermalServiceMock;
    @Mock private AudioManager mAudioManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));

        PowerManager powerManager = new PowerManager(mContextSpy, mIPowerManagerMock,
                mIThermalServiceMock, new Handler(mTestLooper.getLooper()));
        when(mContextSpy.getSystemService(Context.POWER_SERVICE)).thenReturn(powerManager);
        when(mContextSpy.getSystemService(PowerManager.class)).thenReturn(powerManager);
        when(mIPowerManagerMock.isInteractive()).thenReturn(true);

        HdmiControlService hdmiControlService =
                new HdmiControlService(mContextSpy) {
                    @Override
                    void wakeUp() {
                    }

                    @Override
                    protected PowerManager getPowerManager() {
                        return powerManager;
                    }

                    @Override
                    AudioManager getAudioManager() {
                        return mAudioManager;
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
                    protected void writeStringSystemProperty(String key, String value) {
                    }

                    @Override
                    Looper getServiceLooper() {
                        return mTestLooper.getLooper();
                    }
                };

        Looper looper = mTestLooper.getLooper();
        hdmiControlService.setIoLooper(looper);
        hdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(mContextSpy));
        mNativeWrapper = new FakeNativeWrapper();
        HdmiCecController hdmiCecController = HdmiCecController.createWithNativeWrapper(
                hdmiControlService, mNativeWrapper, hdmiControlService.getAtomWriter());
        hdmiControlService.setCecController(hdmiCecController);
        hdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(hdmiControlService));
        hdmiControlService.setMessageValidator(new HdmiCecMessageValidator(hdmiControlService));
        hdmiControlService.initService();

        mHdmiCecLocalDeviceAudioSystem = new HdmiCecLocalDeviceAudioSystem(hdmiControlService) {
            @Override
            protected void setPreferredAddress(int addr) {
            }
        };
        mHdmiCecLocalDeviceAudioSystem.init();
        mAction = new ArcTerminationActionFromAvr(mHdmiCecLocalDeviceAudioSystem);

        mLocalDevices.add(mHdmiCecLocalDeviceAudioSystem);
        hdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mHdmiCecLocalDeviceAudioSystem.setArcStatus(true);
        mTestLooper.dispatchAll();
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
    }

    @Test
    public void testReportArcTerminated_timeout() {
        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(mAction);
        mTestLooper.dispatchAll();
        HdmiCecMessage terminateArc = HdmiCecMessageBuilder.buildTerminateArc(
                Constants.ADDR_AUDIO_SYSTEM, Constants.ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(terminateArc);

        assertThat(mHdmiCecLocalDeviceAudioSystem.isArcEnabled()).isTrue();
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
    }
}
