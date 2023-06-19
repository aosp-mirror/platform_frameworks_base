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

import static com.android.server.SystemService.PHASE_BOOT_COMPLETED;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_1;
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.Constants.PATH_RELATIONSHIP_ANCESTOR;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.os.Binder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.stats.hdmi.HdmiStatsEnums;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.SystemService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

import java.util.Collections;

/**
 * Tests for the {@link HdmiCecAtomWriter} class and its usage by the HDMI-CEC framework.
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class HdmiCecAtomLoggingTest {
    private HdmiCecAtomWriter mHdmiCecAtomWriterSpy;
    private HdmiControlService mHdmiControlServiceSpy;
    private HdmiCecController mHdmiCecController;
    private HdmiMhlControllerStub mHdmiMhlControllerStub;
    private FakeNativeWrapper mNativeWrapper;
    private FakePowerManagerWrapper mPowerManager;
    private HdmiCecNetwork mHdmiCecNetwork;
    private Looper mLooper;
    private Context mContextSpy;
    private TestLooper mTestLooper = new TestLooper();
    private int mPhysicalAddress = 0x1110;
    private HdmiPortInfo[] mHdmiPortInfo;
    private HdmiEarcController mHdmiEarcController;
    private FakeEarcNativeWrapper mEarcNativeWrapper;

    @Before
    public void setUp() throws RemoteException {
        mHdmiCecAtomWriterSpy = spy(new HdmiCecAtomWriter());

        mLooper = mTestLooper.getLooper();

        mContextSpy = spy(new ContextWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext()));

        FakeAudioFramework audioFramework = new FakeAudioFramework();

        mHdmiControlServiceSpy = spy(new HdmiControlService(mContextSpy,
                Collections.singletonList(HdmiDeviceInfo.DEVICE_PLAYBACK),
                audioFramework.getAudioManager(), audioFramework.getAudioDeviceVolumeManager()));
        doNothing().when(mHdmiControlServiceSpy)
                .writeStringSystemProperty(anyString(), anyString());
        doReturn(mHdmiCecAtomWriterSpy).when(mHdmiControlServiceSpy).getAtomWriter();
        HdmiCecConfig hdmiCecConfig = new FakeHdmiCecConfig(mContextSpy);
        doReturn(hdmiCecConfig).when(mHdmiControlServiceSpy).getHdmiCecConfig();

        mHdmiControlServiceSpy.setIoLooper(mLooper);
        mHdmiControlServiceSpy.setCecMessageBuffer(
                new CecMessageBuffer(mHdmiControlServiceSpy));

        mNativeWrapper = new FakeNativeWrapper();
        mNativeWrapper.setPhysicalAddress(mPhysicalAddress);

        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlServiceSpy, mNativeWrapper, mHdmiCecAtomWriterSpy);
        mHdmiControlServiceSpy.setCecController(mHdmiCecController);

        mHdmiMhlControllerStub = HdmiMhlControllerStub.create(mHdmiControlServiceSpy);
        mHdmiControlServiceSpy.setHdmiMhlController(
                mHdmiMhlControllerStub);

        mHdmiCecNetwork = new HdmiCecNetwork(mHdmiControlServiceSpy,
                mHdmiCecController, mHdmiMhlControllerStub);
        mHdmiControlServiceSpy.setHdmiCecNetwork(mHdmiCecNetwork);
        mHdmiControlServiceSpy.setDeviceConfig(new FakeDeviceConfigWrapper());

        mEarcNativeWrapper = new FakeEarcNativeWrapper();
        mHdmiEarcController = HdmiEarcController.createWithNativeWrapper(
                mHdmiControlServiceSpy, mEarcNativeWrapper);
        mHdmiControlServiceSpy.setEarcController(mHdmiEarcController);

        HdmiPortInfo[] hdmiPortInfos = new HdmiPortInfo[1];
        hdmiPortInfos[0] =
                new HdmiPortInfo.Builder(1, HdmiPortInfo.PORT_OUTPUT, 0x0000)
                        .setCecSupported(true)
                        .setMhlSupported(false)
                        .setArcSupported(false)
                        .build();
        mNativeWrapper.setPortInfo(hdmiPortInfos);
        mNativeWrapper.setPortConnectionStatus(1, true);

        mHdmiControlServiceSpy.initService();
        mPowerManager = new FakePowerManagerWrapper(mContextSpy);
        mHdmiControlServiceSpy.setPowerManager(mPowerManager);
        mHdmiControlServiceSpy.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);

        mTestLooper.dispatchAll();

        Mockito.reset(mHdmiCecAtomWriterSpy);
    }

    @Test
    public void testActiveSourceChanged_calledOnSetActiveSource() {
        mHdmiControlServiceSpy.setActiveSource(1, 0x1111, "caller");
        verify(mHdmiCecAtomWriterSpy, times(1))
                .activeSourceChanged(1, 0x1111, PATH_RELATIONSHIP_ANCESTOR);
    }

    @Test
    public void testMessageReported_calledOnOutgoingMessage() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(ADDR_PLAYBACK_1,
                mPhysicalAddress);

        mHdmiCecController.sendCommand(message);
        mTestLooper.dispatchAll();

        verify(mHdmiCecAtomWriterSpy, times(1)).messageReported(
                eq(message),
                eq(HdmiStatsEnums.OUTGOING),
                anyInt(),
                eq(SendMessageResult.SUCCESS));
    }

    @Test
    public void testMessageReported_calledOnIncomingMessage() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildActiveSource(ADDR_TV, 0x0000);

        mNativeWrapper.onCecMessage(message);
        mTestLooper.dispatchAll();

        verify(mHdmiCecAtomWriterSpy, times(1)).messageReported(
                eq(message),
                eq(HdmiStatsEnums.INCOMING),
                anyInt());
    }

    @Test
    public void testMessageReported_calledWithUid() {
        int callerUid = 1234;
        int runnerUid = 5678;

        mHdmiControlServiceSpy.setPowerStatus(HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON);
        mHdmiControlServiceSpy.onBootPhase(PHASE_BOOT_COMPLETED);

        Binder.setCallingWorkSourceUid(callerUid);

        mHdmiControlServiceSpy.runOnServiceThread(
                () -> mHdmiControlServiceSpy.setStandbyMode(true));

        Binder.setCallingWorkSourceUid(runnerUid);

        mTestLooper.dispatchAll();

        verify(mHdmiCecAtomWriterSpy, atLeastOnce()).messageReported(
                any(),
                anyInt(),
                eq(callerUid),
                anyInt());
    }

    @Test
    public void testMessageReported_writesAtom_userControlPressed() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildUserControlPressed(
                Constants.ADDR_TV,
                Constants.ADDR_PLAYBACK_1,
                HdmiCecKeycode.CEC_KEYCODE_MUTE
        );

        mHdmiCecAtomWriterSpy.messageReported(
                message,
                HdmiStatsEnums.INCOMING,
                1234);

        verify(mHdmiCecAtomWriterSpy, times(1))
                .writeHdmiCecMessageReportedAtom(
                        1234,
                        HdmiStatsEnums.INCOMING,
                        Constants.ADDR_TV,
                        Constants.ADDR_PLAYBACK_1,
                        Constants.MESSAGE_USER_CONTROL_PRESSED,
                        HdmiStatsEnums.SEND_MESSAGE_RESULT_UNKNOWN,
                        HdmiStatsEnums.VOLUME_MUTE,
                        HdmiCecAtomWriter.FEATURE_ABORT_OPCODE_UNKNOWN,
                        HdmiStatsEnums.FEATURE_ABORT_REASON_UNKNOWN);
    }

    @Test
    public void testMessageReported_writesAtom_userControlPressed_noParams() {
        HdmiCecMessage message = HdmiCecMessage.build(
                Constants.ADDR_TV,
                Constants.ADDR_PLAYBACK_1,
                Constants.MESSAGE_USER_CONTROL_PRESSED,
                new byte[0]);

        mHdmiCecAtomWriterSpy.messageReported(
                message,
                HdmiStatsEnums.INCOMING,
                1234);

        verify(mHdmiCecAtomWriterSpy, times(1))
                .writeHdmiCecMessageReportedAtom(
                        1234,
                        HdmiStatsEnums.INCOMING,
                        Constants.ADDR_TV,
                        Constants.ADDR_PLAYBACK_1,
                        Constants.MESSAGE_USER_CONTROL_PRESSED,
                        HdmiStatsEnums.SEND_MESSAGE_RESULT_UNKNOWN,
                        HdmiStatsEnums.USER_CONTROL_PRESSED_COMMAND_UNKNOWN,
                        HdmiCecAtomWriter.FEATURE_ABORT_OPCODE_UNKNOWN,
                        HdmiStatsEnums.FEATURE_ABORT_REASON_UNKNOWN);
    }

    @Test
    public void testMessageReported_writesAtom_featureAbort() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildFeatureAbortCommand(
                Constants.ADDR_TV,
                Constants.ADDR_PLAYBACK_1,
                Constants.MESSAGE_RECORD_ON,
                Constants.ABORT_UNRECOGNIZED_OPCODE
        );

        mHdmiCecAtomWriterSpy.messageReported(
                message,
                HdmiStatsEnums.INCOMING,
                1234);

        verify(mHdmiCecAtomWriterSpy, times(1))
                .writeHdmiCecMessageReportedAtom(
                        1234,
                        HdmiStatsEnums.INCOMING,
                        Constants.ADDR_TV,
                        Constants.ADDR_PLAYBACK_1,
                        Constants.MESSAGE_FEATURE_ABORT,
                        HdmiStatsEnums.SEND_MESSAGE_RESULT_UNKNOWN,
                        HdmiStatsEnums.USER_CONTROL_PRESSED_COMMAND_UNKNOWN,
                        Constants.MESSAGE_RECORD_ON,
                        HdmiStatsEnums.UNRECOGNIZED_OPCODE);
    }

    @Test
    public void testMessageReported_writesAtom_featureAbort_noParams() {
        HdmiCecMessage message = HdmiCecMessage.build(
                Constants.ADDR_TV,
                Constants.ADDR_PLAYBACK_1,
                Constants.MESSAGE_FEATURE_ABORT,
                new byte[0]);

        mHdmiCecAtomWriterSpy.messageReported(
                message,
                HdmiStatsEnums.INCOMING,
                1234);

        verify(mHdmiCecAtomWriterSpy, times(1))
                .writeHdmiCecMessageReportedAtom(
                        1234,
                        HdmiStatsEnums.INCOMING,
                        Constants.ADDR_TV,
                        Constants.ADDR_PLAYBACK_1,
                        Constants.MESSAGE_FEATURE_ABORT,
                        HdmiStatsEnums.SEND_MESSAGE_RESULT_UNKNOWN,
                        HdmiStatsEnums.USER_CONTROL_PRESSED_COMMAND_UNKNOWN,
                        HdmiCecAtomWriter.FEATURE_ABORT_OPCODE_UNKNOWN,
                        HdmiStatsEnums.FEATURE_ABORT_REASON_UNKNOWN);
    }
}
