/*
 * Copyright (C) 2022 The Android Open Source Project
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
import static com.android.server.hdmi.Constants.HDMI_EARC_STATUS_ARC_PENDING;
import static com.android.server.hdmi.Constants.HDMI_EARC_STATUS_EARC_CONNECTED;
import static com.android.server.hdmi.Constants.HDMI_EARC_STATUS_EARC_PENDING;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.hardware.hdmi.HdmiDeviceInfo;
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
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
/** Tests for {@link HdmiEarcLocalDeviceTx} class. */
public class HdmiEarcLocalDeviceTxTest {

    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiEarcLocalDevice mHdmiEarcLocalDeviceTx;
    private FakeNativeWrapper mNativeWrapper;
    private FakePowerManagerWrapper mPowerManager;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();

    @Mock
    private AudioManager mAudioManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        Context context = InstrumentationRegistry.getTargetContext();
        mMyLooper = mTestLooper.getLooper();

        mHdmiControlService =
                new HdmiControlService(InstrumentationRegistry.getTargetContext(),
                        Collections.singletonList(HdmiDeviceInfo.DEVICE_TV),
                        new FakeAudioDeviceVolumeManagerWrapper()) {
                    @Override
                    boolean isCecControlEnabled() {
                        return true;
                    }

                    @Override
                    boolean isTvDevice() {
                        return true;
                    }

                    @Override
                    protected void writeStringSystemProperty(String key, String value) {
                        // do nothing
                    }

                    @Override
                    boolean isPowerStandby() {
                        return false;
                    }

                    @Override
                    AudioManager getAudioManager() {
                        return mAudioManager;
                    }
                };

        mHdmiControlService.setIoLooper(mMyLooper);
        mHdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(context));
        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.initService();
        mHdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);
        mPowerManager = new FakePowerManagerWrapper(context);
        mHdmiControlService.setPowerManager(mPowerManager);
        mTestLooper.dispatchAll();
        mHdmiControlService.initializeEarcLocalDevice(HdmiControlService.INITIATED_BY_BOOT_UP);
        mHdmiEarcLocalDeviceTx = mHdmiControlService.getEarcLocalDevice();
    }

    @Test
    public void earcGetsConnected_capsReportedInTime() {
        mHdmiEarcLocalDeviceTx.handleEarcStateChange(HDMI_EARC_STATUS_EARC_CONNECTED);
        mTestLooper.moveTimeForward(HdmiEarcLocalDeviceTx.REPORT_CAPS_MAX_DELAY_MS - 200);
        mTestLooper.dispatchAll();
        // TO DO: add meaningful capabilities and test that they get forwarded to AudioManager
        // correctly.
        mHdmiEarcLocalDeviceTx.handleEarcCapabilitiesReported(new ArrayList<>());
        mTestLooper.dispatchAll();
        verify(mAudioManager, times(1)).setWiredDeviceConnectionState(any(), eq(1));
    }

    @Test
    public void earcGetsConnected_capsReportedTooLate() {
        mHdmiEarcLocalDeviceTx.handleEarcStateChange(HDMI_EARC_STATUS_EARC_CONNECTED);
        mTestLooper.moveTimeForward(HdmiEarcLocalDeviceTx.REPORT_CAPS_MAX_DELAY_MS + 1);
        mTestLooper.dispatchAll();
        // TO DO: verify that empty capabilities are forwarded to AudioManager.
        verify(mAudioManager, times(1)).setWiredDeviceConnectionState(any(), eq(1));
        Mockito.clearInvocations(mAudioManager);

        mHdmiEarcLocalDeviceTx.handleEarcCapabilitiesReported(new ArrayList<>());
        mTestLooper.dispatchAll();
        verify(mAudioManager, times(0)).setWiredDeviceConnectionState(any(), anyInt());
    }

    @Test
    public void earcGetsConnected_earcGetsDisconnectedBeforeCapsReported() {
        mHdmiEarcLocalDeviceTx.handleEarcStateChange(HDMI_EARC_STATUS_EARC_CONNECTED);
        mTestLooper.dispatchAll();
        mHdmiEarcLocalDeviceTx.handleEarcStateChange(HDMI_EARC_STATUS_ARC_PENDING);
        mTestLooper.dispatchAll();
        verify(mAudioManager, times(0)).setWiredDeviceConnectionState(any(), eq(1));
        verify(mAudioManager, times(1)).setWiredDeviceConnectionState(any(), eq(0));
        Mockito.clearInvocations(mAudioManager);

        mHdmiEarcLocalDeviceTx.handleEarcCapabilitiesReported(new ArrayList<>());
        mTestLooper.dispatchAll();
        verify(mAudioManager, times(0)).setWiredDeviceConnectionState(any(), anyInt());
    }

    @Test
    public void earcGetsConnected_earcBecomesPendingBeforeCapsReported() {
        mHdmiEarcLocalDeviceTx.handleEarcStateChange(HDMI_EARC_STATUS_EARC_CONNECTED);
        mTestLooper.dispatchAll();
        mHdmiEarcLocalDeviceTx.handleEarcStateChange(HDMI_EARC_STATUS_EARC_PENDING);
        mTestLooper.dispatchAll();
        verify(mAudioManager, times(0)).setWiredDeviceConnectionState(any(), anyInt());
        Mockito.clearInvocations(mAudioManager);

        mHdmiEarcLocalDeviceTx.handleEarcCapabilitiesReported(new ArrayList<>());
        mTestLooper.dispatchAll();
        verify(mAudioManager, times(0)).setWiredDeviceConnectionState(any(), anyInt());
    }
}
