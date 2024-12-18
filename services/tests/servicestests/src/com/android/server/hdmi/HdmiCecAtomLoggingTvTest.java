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

import static com.android.server.hdmi.Constants.HDMI_EARC_STATUS_EARC_PENDING;
import static com.android.server.hdmi.Constants.HDMI_EARC_STATUS_UNKNOWN;
import static com.android.server.hdmi.HdmiControlService.WAKE_UP_SCREEN_ON;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.os.Looper;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.stats.hdmi.HdmiStatsEnums;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

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
public class HdmiCecAtomLoggingTvTest {
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
    private int mPhysicalAddress = 0x0000;
    private static final int EARC_PORT_ID = 1;
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
                Collections.singletonList(HdmiDeviceInfo.DEVICE_TV),
                audioFramework.getAudioManager(), audioFramework.getAudioDeviceVolumeManager()));
        doNothing().when(mHdmiControlServiceSpy)
                .writeStringSystemProperty(anyString(), anyString());
        doNothing().when(mHdmiControlServiceSpy)
                .sendBroadcastAsUser(any(Intent.class));
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
                new HdmiPortInfo.Builder(EARC_PORT_ID, HdmiPortInfo.PORT_OUTPUT, 0x0000)
                        .setCecSupported(true)
                        .setMhlSupported(false)
                        .setArcSupported(false)
                        .setEarcSupported(true)
                        .build();
        mNativeWrapper.setPortInfo(hdmiPortInfos);
        mNativeWrapper.setPortConnectionStatus(EARC_PORT_ID, true);

        mHdmiControlServiceSpy.initService();
        mPowerManager = new FakePowerManagerWrapper(mContextSpy);
        mHdmiControlServiceSpy.setPowerManager(mPowerManager);
        mHdmiControlServiceSpy.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        mHdmiControlServiceSpy.setEarcEnabled(HdmiControlManager.EARC_FEATURE_DISABLED);
        mTestLooper.dispatchAll();

        Mockito.reset(mHdmiCecAtomWriterSpy);
    }

    @Test
    public void testEarcStatusChanged_handleEarcStateChange_writesAtom() {
        mHdmiControlServiceSpy.setEarcEnabled(HdmiControlManager.EARC_FEATURE_ENABLED);
        mTestLooper.dispatchAll();
        Mockito.clearInvocations(mHdmiCecAtomWriterSpy);

        mHdmiControlServiceSpy.handleEarcStateChange(HDMI_EARC_STATUS_EARC_PENDING,
                EARC_PORT_ID);
        verify(mHdmiCecAtomWriterSpy, times(1))
                .earcStatusChanged(true, true, HDMI_EARC_STATUS_EARC_PENDING,
                        HDMI_EARC_STATUS_EARC_PENDING,
                        HdmiStatsEnums.LOG_REASON_EARC_STATUS_CHANGED);
    }

    @Test
    public void testEarcStatusChanged_onWakeUp_earcSupported_earcEnabled_writesAtom() {
        mHdmiControlServiceSpy.setEarcEnabled(HdmiControlManager.EARC_FEATURE_ENABLED);
        Mockito.clearInvocations(mHdmiCecAtomWriterSpy);
        mTestLooper.dispatchAll();

        mHdmiControlServiceSpy.onWakeUp(WAKE_UP_SCREEN_ON);
        verify(mHdmiCecAtomWriterSpy, times(1))
                .earcStatusChanged(true, true, HDMI_EARC_STATUS_EARC_PENDING,
                        HDMI_EARC_STATUS_EARC_PENDING, HdmiStatsEnums.LOG_REASON_WAKE);
    }

    @Test
    public void testEarcStatusChanged_onWakeUp_earcSupported_earcDisabled_writesAtom() {
        mHdmiControlServiceSpy.setEarcEnabled(HdmiControlManager.EARC_FEATURE_DISABLED);
        Mockito.clearInvocations(mHdmiCecAtomWriterSpy);
        mTestLooper.dispatchAll();

        mHdmiControlServiceSpy.onWakeUp(WAKE_UP_SCREEN_ON);
        verify(mHdmiCecAtomWriterSpy, times(1))
                .earcStatusChanged(true, false, HDMI_EARC_STATUS_UNKNOWN,
                        HDMI_EARC_STATUS_UNKNOWN, HdmiStatsEnums.LOG_REASON_WAKE);
    }

    @Test
    public void testEarcStatusChanged_onWakeUp_earcNotSupported_earcEnabled_writesAtom() {
        doReturn(false).when(mHdmiControlServiceSpy).isEarcSupported();
        mTestLooper.dispatchAll();

        mHdmiControlServiceSpy.setEarcEnabled(HdmiControlManager.EARC_FEATURE_ENABLED);
        Mockito.clearInvocations(mHdmiCecAtomWriterSpy);
        mTestLooper.dispatchAll();

        mHdmiControlServiceSpy.onWakeUp(WAKE_UP_SCREEN_ON);
        verify(mHdmiCecAtomWriterSpy, times(1))
                .earcStatusChanged(false, true, HDMI_EARC_STATUS_UNKNOWN,
                        HDMI_EARC_STATUS_UNKNOWN, HdmiStatsEnums.LOG_REASON_WAKE);
    }

    @Test
    public void testEarcStatusChanged_onWakeUp_earcNotSupported_earcDisabled_writesAtom() {
        doReturn(false).when(mHdmiControlServiceSpy).isEarcSupported();
        mTestLooper.dispatchAll();

        mHdmiControlServiceSpy.setEarcEnabled(HdmiControlManager.EARC_FEATURE_DISABLED);
        Mockito.clearInvocations(mHdmiCecAtomWriterSpy);
        mTestLooper.dispatchAll();

        mHdmiControlServiceSpy.onWakeUp(WAKE_UP_SCREEN_ON);
        verify(mHdmiCecAtomWriterSpy, times(1))
                .earcStatusChanged(false, false, HDMI_EARC_STATUS_UNKNOWN,
                        HDMI_EARC_STATUS_UNKNOWN, HdmiStatsEnums.LOG_REASON_WAKE);
    }

    @Test
    public void testEarcStatusChanged_handleEarcStateChange_unSupportedPort_writesAtom() {
        // Initialize HDMI port with eARC not supported.
        HdmiPortInfo[] hdmiPortInfos = new HdmiPortInfo[1];
        hdmiPortInfos[0] =
                new HdmiPortInfo.Builder(EARC_PORT_ID, HdmiPortInfo.PORT_OUTPUT, 0x0000)
                        .setCecSupported(true)
                        .setMhlSupported(false)
                        .setArcSupported(false)
                        .setEarcSupported(false)
                        .build();
        mNativeWrapper.setPortInfo(hdmiPortInfos);
        mNativeWrapper.setPortConnectionStatus(EARC_PORT_ID, true);
        mHdmiControlServiceSpy.initService();
        mTestLooper.dispatchAll();

        mHdmiControlServiceSpy.setEarcEnabled(HdmiControlManager.EARC_FEATURE_ENABLED);
        mTestLooper.dispatchAll();
        Mockito.clearInvocations(mHdmiCecAtomWriterSpy);

        mHdmiControlServiceSpy.handleEarcStateChange(HDMI_EARC_STATUS_EARC_PENDING, EARC_PORT_ID);
        verify(mHdmiCecAtomWriterSpy, times(1))
                .earcStatusChanged(eq(false), eq(true), anyInt(),
                        eq(HDMI_EARC_STATUS_EARC_PENDING),
                        eq(HdmiStatsEnums.LOG_REASON_EARC_STATUS_CHANGED_UNSUPPORTED_PORT));
    }

    @Test
    public void testEarcStatusChanged_handleEarcStateChange_wrongState_writesAtom() {
        mHdmiControlServiceSpy.setEarcEnabled(HdmiControlManager.EARC_FEATURE_DISABLED);
        mTestLooper.dispatchAll();
        Mockito.clearInvocations(mHdmiCecAtomWriterSpy);

        // mEarcLocalDevice should be empty since eARC is disabled.
        mHdmiControlServiceSpy.handleEarcStateChange(HDMI_EARC_STATUS_EARC_PENDING, EARC_PORT_ID);
        verify(mHdmiCecAtomWriterSpy, times(1))
                .earcStatusChanged(eq(true), eq(false), anyInt(),
                        eq(HDMI_EARC_STATUS_EARC_PENDING),
                        eq(HdmiStatsEnums.LOG_REASON_EARC_STATUS_CHANGED_WRONG_STATE));
    }
}
