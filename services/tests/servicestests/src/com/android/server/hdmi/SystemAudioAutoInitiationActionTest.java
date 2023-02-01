/*
 * Copyright (C) 2021 The Android Open Source Project
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
import static com.android.server.hdmi.Constants.ADDR_AUDIO_SYSTEM;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;
import static com.android.server.hdmi.SystemAudioAutoInitiationAction.RETRIES_ON_TIMEOUT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.hdmi.HdmiPortInfo;
import android.media.AudioManager;
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

/**
 * Test for {@link SystemAudioAutoInitiationAction}.
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class SystemAudioAutoInitiationActionTest {

    private Context mContextSpy;
    private HdmiControlService mHdmiControlService;
    private FakeNativeWrapper mNativeWrapper;
    private FakePowerManagerWrapper mPowerManager;

    private HdmiCecLocalDeviceTv mHdmiCecLocalDeviceTv;

    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private int mPhysicalAddress;

    @Before
    public void setUp() throws Exception {
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));

        Looper myLooper = mTestLooper.getLooper();

        mHdmiControlService = new HdmiControlService(mContextSpy, Collections.emptyList(),
                new FakeAudioDeviceVolumeManagerWrapper()) {
            @Override
            AudioManager getAudioManager() {
                return new AudioManager() {
                    @Override
                    public void setWiredDeviceConnectionState(
                            int type, int state, String address, String name) {
                        // Do nothing.
                    }
                };
            }

            @Override
            boolean isPowerStandby() {
                return false;
            }

            @Override
            protected void writeStringSystemProperty(String key, String value) {
                // do nothing
            }
        };

        mHdmiCecLocalDeviceTv = new HdmiCecLocalDeviceTv(mHdmiControlService);
        mHdmiCecLocalDeviceTv.init();
        mHdmiControlService.setIoLooper(myLooper);
        mNativeWrapper = new FakeNativeWrapper();
        HdmiCecController hdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(hdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mLocalDevices.add(mHdmiCecLocalDeviceTv);
        HdmiPortInfo[] hdmiPortInfos = new HdmiPortInfo[2];
        hdmiPortInfos[0] =
                new HdmiPortInfo(1, HdmiPortInfo.PORT_INPUT, 0x1000, true, false, false);
        hdmiPortInfos[1] =
                new HdmiPortInfo(2, HdmiPortInfo.PORT_INPUT, 0x2000, true, false, true);
        mNativeWrapper.setPortInfo(hdmiPortInfos);
        mHdmiControlService.initService();
        mHdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);
        mPowerManager = new FakePowerManagerWrapper(mContextSpy);
        mHdmiControlService.setPowerManager(mPowerManager);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mPhysicalAddress = 0x0000;
        mNativeWrapper.setPhysicalAddress(mPhysicalAddress);
        mTestLooper.dispatchAll();
        mPhysicalAddress = mHdmiCecLocalDeviceTv.getDeviceInfo().getLogicalAddress();
        mNativeWrapper.clearResultMessages();
    }

    private void setSystemAudioSetting(boolean on) {
        mHdmiCecLocalDeviceTv.setSystemAudioControlFeatureEnabled(on);
    }

    private void setTvHasSystemAudioChangeAction() {
        mHdmiCecLocalDeviceTv.addAndStartAction(
                new SystemAudioActionFromTv(mHdmiCecLocalDeviceTv, Constants.ADDR_AUDIO_SYSTEM,
                        true, null));
    }

    @Test
    public void testReceiveSystemAudioMode_systemAudioOn() {
        // Record that previous system audio mode is on.
        setSystemAudioSetting(true);

        HdmiCecFeatureAction action = new SystemAudioAutoInitiationAction(mHdmiCecLocalDeviceTv,
                ADDR_AUDIO_SYSTEM);
        mHdmiCecLocalDeviceTv.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage giveSystemAudioModeStatus =
                HdmiCecMessageBuilder.buildGiveSystemAudioModeStatus(
                        mHdmiCecLocalDeviceTv.getDeviceInfo().getLogicalAddress(),
                        ADDR_AUDIO_SYSTEM);

        assertThat(mNativeWrapper.getResultMessages()).contains(giveSystemAudioModeStatus);

        HdmiCecMessage reportSystemAudioMode =
                HdmiCecMessageBuilder.buildReportSystemAudioMode(
                        ADDR_AUDIO_SYSTEM,
                        mHdmiCecLocalDeviceTv.getDeviceInfo().getLogicalAddress(),
                        true);
        mHdmiControlService.handleCecCommand(reportSystemAudioMode);
        mTestLooper.dispatchAll();

        assertThat(mHdmiControlService.isSystemAudioActivated()).isTrue();
    }

    @Test
    public void testReceiveSystemAudioMode_systemAudioOnAndImpossibleToChangeSystemAudio() {
        // Turn on system audio.
        setSystemAudioSetting(true);
        // Impossible to change system audio mode while SystemAudioActionFromTv is in progress.
        setTvHasSystemAudioChangeAction();

        HdmiCecFeatureAction action = new SystemAudioAutoInitiationAction(mHdmiCecLocalDeviceTv,
                ADDR_AUDIO_SYSTEM);
        mHdmiCecLocalDeviceTv.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage giveSystemAudioModeStatus =
                HdmiCecMessageBuilder.buildGiveSystemAudioModeStatus(
                        mHdmiCecLocalDeviceTv.getDeviceInfo().getLogicalAddress(),
                        ADDR_AUDIO_SYSTEM);

        assertThat(mNativeWrapper.getResultMessages()).contains(giveSystemAudioModeStatus);

        HdmiCecMessage reportSystemAudioMode =
                HdmiCecMessageBuilder.buildReportSystemAudioMode(
                        ADDR_AUDIO_SYSTEM,
                        mHdmiCecLocalDeviceTv.getDeviceInfo().getLogicalAddress(),
                        true);
        mHdmiControlService.handleCecCommand(reportSystemAudioMode);
        mTestLooper.dispatchAll();

        assertThat(mHdmiControlService.isSystemAudioActivated()).isFalse();
    }

    @Test
    public void testReceiveSystemAudioMode_systemAudioOnAndResponseOff() {
        // Record that previous system audio mode is on.
        setSystemAudioSetting(true);

        HdmiCecFeatureAction action = new SystemAudioAutoInitiationAction(mHdmiCecLocalDeviceTv,
                ADDR_AUDIO_SYSTEM);
        mHdmiCecLocalDeviceTv.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage giveSystemAudioModeStatus =
                HdmiCecMessageBuilder.buildGiveSystemAudioModeStatus(
                        mHdmiCecLocalDeviceTv.getDeviceInfo().getLogicalAddress(),
                        ADDR_AUDIO_SYSTEM);

        assertThat(mNativeWrapper.getResultMessages()).contains(giveSystemAudioModeStatus);

        HdmiCecMessage reportSystemAudioMode =
                HdmiCecMessageBuilder.buildReportSystemAudioMode(
                        ADDR_AUDIO_SYSTEM,
                        mHdmiCecLocalDeviceTv.getDeviceInfo().getLogicalAddress(),
                        false);
        mHdmiControlService.handleCecCommand(reportSystemAudioMode);
        mTestLooper.dispatchAll();

        assertThat(mHdmiCecLocalDeviceTv.getActions(SystemAudioActionFromTv.class)).isNotEmpty();
        SystemAudioActionFromTv resultingAction = mHdmiCecLocalDeviceTv.getActions(
                SystemAudioActionFromTv.class).get(0);
        assertThat(resultingAction.mTargetAudioStatus).isTrue();
    }

    @Test
    public void testReceiveSystemAudioMode_settingOffAndResponseOn() {
        // Turn off system audio.
        setSystemAudioSetting(false);

        HdmiCecFeatureAction action = new SystemAudioAutoInitiationAction(mHdmiCecLocalDeviceTv,
                ADDR_AUDIO_SYSTEM);
        mHdmiCecLocalDeviceTv.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage giveSystemAudioModeStatus =
                HdmiCecMessageBuilder.buildGiveSystemAudioModeStatus(
                        mHdmiCecLocalDeviceTv.getDeviceInfo().getLogicalAddress(),
                        ADDR_AUDIO_SYSTEM);

        assertThat(mNativeWrapper.getResultMessages()).contains(giveSystemAudioModeStatus);

        HdmiCecMessage reportSystemAudioMode =
                HdmiCecMessageBuilder.buildReportSystemAudioMode(
                        ADDR_AUDIO_SYSTEM,
                        mHdmiCecLocalDeviceTv.getDeviceInfo().getLogicalAddress(),
                        true);
        mHdmiControlService.handleCecCommand(reportSystemAudioMode);
        mTestLooper.dispatchAll();

        assertThat(mHdmiCecLocalDeviceTv.getActions(SystemAudioActionFromTv.class)).isNotEmpty();
        SystemAudioActionFromTv resultingAction = mHdmiCecLocalDeviceTv.getActions(
                SystemAudioActionFromTv.class).get(0);
        assertThat(resultingAction.mTargetAudioStatus).isFalse();
    }

    @Test
    public void testReceiveSystemAudioMode_settingOffAndResponseOff() {
        // Turn off system audio.
        setSystemAudioSetting(false);

        HdmiCecFeatureAction action = new SystemAudioAutoInitiationAction(mHdmiCecLocalDeviceTv,
                ADDR_AUDIO_SYSTEM);
        mHdmiCecLocalDeviceTv.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage giveSystemAudioModeStatus =
                HdmiCecMessageBuilder.buildGiveSystemAudioModeStatus(
                        mHdmiCecLocalDeviceTv.getDeviceInfo().getLogicalAddress(),
                        ADDR_AUDIO_SYSTEM);

        assertThat(mNativeWrapper.getResultMessages()).contains(giveSystemAudioModeStatus);

        HdmiCecMessage reportSystemAudioMode =
                HdmiCecMessageBuilder.buildReportSystemAudioMode(
                        ADDR_AUDIO_SYSTEM,
                        mHdmiCecLocalDeviceTv.getDeviceInfo().getLogicalAddress(),
                        false);
        mHdmiControlService.handleCecCommand(reportSystemAudioMode);
        mTestLooper.dispatchAll();

        assertThat(mHdmiCecLocalDeviceTv.getActions(SystemAudioActionFromTv.class)).isEmpty();
        assertThat(mHdmiControlService.isSystemAudioActivated()).isFalse();
    }

    @Test
    public void testTimeout_systemAudioOn_retries() {
        // Turn on system audio.
        setSystemAudioSetting(true);

        HdmiCecFeatureAction action = new SystemAudioAutoInitiationAction(mHdmiCecLocalDeviceTv,
                ADDR_AUDIO_SYSTEM);
        mHdmiCecLocalDeviceTv.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage giveSystemAudioModeStatus =
                HdmiCecMessageBuilder.buildGiveSystemAudioModeStatus(
                        mHdmiCecLocalDeviceTv.getDeviceInfo().getLogicalAddress(),
                        ADDR_AUDIO_SYSTEM);

        assertThat(mNativeWrapper.getResultMessages()).contains(giveSystemAudioModeStatus);
        mNativeWrapper.clearResultMessages();

        mTestLooper.moveTimeForward(HdmiConfig.TIMEOUT_MS);
        mTestLooper.dispatchAll();

        // Retry sends <Give System Audio Mode Status> again
        assertThat(mNativeWrapper.getResultMessages()).contains(giveSystemAudioModeStatus);
    }

    @Test
    public void testTimeout_systemAudioOn_allRetriesFail() {
        boolean targetStatus = true;
        // Turn on system audio.
        setSystemAudioSetting(targetStatus);

        HdmiCecFeatureAction action = new SystemAudioAutoInitiationAction(mHdmiCecLocalDeviceTv,
                ADDR_AUDIO_SYSTEM);
        mHdmiCecLocalDeviceTv.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage giveSystemAudioModeStatus =
                HdmiCecMessageBuilder.buildGiveSystemAudioModeStatus(
                        mHdmiCecLocalDeviceTv.getDeviceInfo().getLogicalAddress(),
                        ADDR_AUDIO_SYSTEM);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveSystemAudioModeStatus);

        for (int i = 0; i < RETRIES_ON_TIMEOUT; i++) {
            mNativeWrapper.clearResultMessages();

            // Target device doesn't respond within timeout
            mTestLooper.moveTimeForward(HdmiConfig.TIMEOUT_MS);
            mTestLooper.dispatchAll();

            // Retry sends <Give System Audio Mode Status> again
            assertThat(mNativeWrapper.getResultMessages()).contains(giveSystemAudioModeStatus);
        }

        // Target device doesn't respond within timeouts
        mTestLooper.moveTimeForward(HdmiConfig.TIMEOUT_MS);
        mTestLooper.dispatchAll();

        assertThat(mHdmiCecLocalDeviceTv.getActions(SystemAudioActionFromTv.class)).isNotEmpty();
        SystemAudioActionFromTv resultingAction = mHdmiCecLocalDeviceTv.getActions(
                SystemAudioActionFromTv.class).get(0);
        assertThat(resultingAction.mTargetAudioStatus).isEqualTo(targetStatus);
    }
}
