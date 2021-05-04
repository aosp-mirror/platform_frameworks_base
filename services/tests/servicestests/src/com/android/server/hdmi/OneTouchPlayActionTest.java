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

import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;
import static com.android.server.hdmi.OneTouchPlayAction.STATE_WAITING_FOR_REPORT_POWER_STATUS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.Looper;
import android.os.PowerManager;
import android.os.test.TestLooper;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.hdmi.HdmiCecFeatureAction.ActionTimer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;

/** Tests for {@link OneTouchPlayAction} */
@SmallTest
@RunWith(JUnit4.class)
public class OneTouchPlayActionTest {
    private static final byte[] POWER_ON = new byte[]{HdmiControlManager.POWER_STATUS_ON};
    private static final byte[] POWER_TRANSIENT_TO_ON =
            new byte[]{HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON};

    private static final int PORT_1 = 1;
    private static final HdmiDeviceInfo INFO_TV = new HdmiDeviceInfo(
            ADDR_TV, 0x0000, PORT_1, HdmiDeviceInfo.DEVICE_TV,
            0x1234, "TV",
            HdmiControlManager.POWER_STATUS_ON, HdmiControlManager.HDMI_CEC_VERSION_1_4_B);

    private Context mContextSpy;
    private HdmiControlService mHdmiControlService;
    private FakeNativeWrapper mNativeWrapper;
    private FakeHdmiCecConfig mHdmiCecConfig;

    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private int mPhysicalAddress;

    @Mock
    private IPowerManager mIPowerManagerMock;
    @Mock
    private IThermalService mIThermalServiceMock;

    /**
     * Manually called before tests, because some tests require HDMI control to be disabled.
     * @param hdmiControlEnabled whether to enable the global setting hdmi_control.
     * @throws Exception
     */
    public void setUp(boolean hdmiControlEnabled) throws Exception {
        MockitoAnnotations.initMocks(this);

        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));
        mHdmiCecConfig = new FakeHdmiCecConfig(mContextSpy);

        when(mContextSpy.getSystemService(Context.POWER_SERVICE)).thenAnswer(i ->
                new PowerManager(mContextSpy, mIPowerManagerMock,
                mIThermalServiceMock, new Handler(mTestLooper.getLooper())));
        when(mContextSpy.getSystemService(PowerManager.class)).thenAnswer(i ->
                new PowerManager(mContextSpy, mIPowerManagerMock,
                mIThermalServiceMock, new Handler(mTestLooper.getLooper())));
        when(mIPowerManagerMock.isInteractive()).thenReturn(true);

        mHdmiControlService = new HdmiControlService(mContextSpy, Collections.emptyList()) {
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
            void wakeUp() {
            }

            @Override
            boolean isPowerStandby() {
                return false;
            }

            @Override
            protected PowerManager getPowerManager() {
                return new PowerManager(mContextSpy, mIPowerManagerMock,
                        mIThermalServiceMock, new Handler(mTestLooper.getLooper()));
            }

            @Override
            protected void writeStringSystemProperty(String key, String value) {
                // do nothing
            }
        };

        Looper looper = mTestLooper.getLooper();
        mHdmiControlService.setIoLooper(looper);
        mHdmiControlService.setHdmiCecConfig(mHdmiCecConfig);
        setHdmiControlEnabled(hdmiControlEnabled);
        mNativeWrapper = new FakeNativeWrapper();
        HdmiCecController hdmiCecController = HdmiCecController.createWithNativeWrapper(
                this.mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(hdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.setMessageValidator(new HdmiCecMessageValidator(mHdmiControlService));
        mHdmiControlService.initService();
        mPhysicalAddress = 0x2000;
        mNativeWrapper.setPhysicalAddress(mPhysicalAddress);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();
    }

    private OneTouchPlayAction createOneTouchPlayAction(HdmiCecLocalDevicePlayback device,
            TestActionTimer actionTimer, TestCallback callback, boolean isCec20) {
        OneTouchPlayAction action = new OneTouchPlayAction(device, ADDR_TV, callback, isCec20);
        action.setActionTimer(actionTimer);
        return action;
    }

    @Test
    public void succeedWithUnknownTvDevice() throws Exception {
        setUp(true);

        HdmiCecLocalDevicePlayback playbackDevice = new HdmiCecLocalDevicePlayback(
                mHdmiControlService);
        playbackDevice.init();
        mLocalDevices.add(playbackDevice);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();

        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        OneTouchPlayAction action = createOneTouchPlayAction(playbackDevice, actionTimer, callback,
                false);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                playbackDevice.mAddress, mPhysicalAddress);
        HdmiCecMessage textViewOn = HdmiCecMessageBuilder.buildTextViewOn(playbackDevice.mAddress,
                ADDR_TV);
        HdmiCecMessage giveDevicePowerStatus = HdmiCecMessageBuilder
                .buildGiveDevicePowerStatus(playbackDevice.mAddress, ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAITING_FOR_REPORT_POWER_STATUS);
        HdmiCecMessage reportPowerStatusOn = new HdmiCecMessage(
                ADDR_TV, playbackDevice.mAddress, Constants.MESSAGE_REPORT_POWER_STATUS, POWER_ON);
        action.processCommand(reportPowerStatusOn);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(giveDevicePowerStatus);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void succeedAfterGettingPowerStatusOn_Cec14b() throws Exception {
        setUp(true);

        mHdmiControlService.getHdmiCecNetwork().addCecDevice(INFO_TV);
        HdmiCecLocalDevicePlayback playbackDevice = new HdmiCecLocalDevicePlayback(
                mHdmiControlService);
        playbackDevice.init();
        mLocalDevices.add(playbackDevice);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();

        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        OneTouchPlayAction action = createOneTouchPlayAction(playbackDevice, actionTimer, callback,
                false);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                playbackDevice.mAddress, mPhysicalAddress);
        HdmiCecMessage textViewOn = HdmiCecMessageBuilder.buildTextViewOn(playbackDevice.mAddress,
                ADDR_TV);
        HdmiCecMessage giveDevicePowerStatus = HdmiCecMessageBuilder
                .buildGiveDevicePowerStatus(playbackDevice.mAddress, ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAITING_FOR_REPORT_POWER_STATUS);
        HdmiCecMessage reportPowerStatusOn = new HdmiCecMessage(
                ADDR_TV, playbackDevice.mAddress, Constants.MESSAGE_REPORT_POWER_STATUS, POWER_ON);
        action.processCommand(reportPowerStatusOn);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(giveDevicePowerStatus);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void succeedAfterGettingTransientPowerStatus_Cec14b() throws Exception {
        setUp(true);

        mHdmiControlService.getHdmiCecNetwork().addCecDevice(INFO_TV);
        HdmiCecLocalDevicePlayback playbackDevice = new HdmiCecLocalDevicePlayback(
                mHdmiControlService);
        playbackDevice.init();
        mLocalDevices.add(playbackDevice);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();

        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        OneTouchPlayAction action = createOneTouchPlayAction(playbackDevice, actionTimer, callback,
                false);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                playbackDevice.mAddress, mPhysicalAddress);
        HdmiCecMessage textViewOn = HdmiCecMessageBuilder.buildTextViewOn(playbackDevice.mAddress,
                ADDR_TV);
        HdmiCecMessage giveDevicePowerStatus = HdmiCecMessageBuilder
                .buildGiveDevicePowerStatus(playbackDevice.mAddress, ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAITING_FOR_REPORT_POWER_STATUS);
        HdmiCecMessage reportPowerStatusTransientToOn = new HdmiCecMessage(
                ADDR_TV, playbackDevice.mAddress, Constants.MESSAGE_REPORT_POWER_STATUS,
                POWER_TRANSIENT_TO_ON);
        action.processCommand(reportPowerStatusTransientToOn);
        action.handleTimerEvent(STATE_WAITING_FOR_REPORT_POWER_STATUS);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);
        mNativeWrapper.clearResultMessages();

        HdmiCecMessage reportPowerStatusOn = new HdmiCecMessage(
                ADDR_TV, playbackDevice.mAddress, Constants.MESSAGE_REPORT_POWER_STATUS, POWER_ON);
        action.processCommand(reportPowerStatusOn);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(giveDevicePowerStatus);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void timeOut_Cec14b() throws Exception {
        setUp(true);

        mHdmiControlService.getHdmiCecNetwork().addCecDevice(INFO_TV);
        HdmiCecLocalDevicePlayback playbackDevice = new HdmiCecLocalDevicePlayback(
                mHdmiControlService);
        playbackDevice.init();
        mLocalDevices.add(playbackDevice);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();

        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        OneTouchPlayAction action = createOneTouchPlayAction(playbackDevice, actionTimer, callback,
                false);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                playbackDevice.mAddress, mPhysicalAddress);
        HdmiCecMessage textViewOn = HdmiCecMessageBuilder.buildTextViewOn(playbackDevice.mAddress,
                ADDR_TV);
        HdmiCecMessage giveDevicePowerStatus = HdmiCecMessageBuilder
                .buildGiveDevicePowerStatus(playbackDevice.mAddress, ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAITING_FOR_REPORT_POWER_STATUS);
        for (int i = 0; i < 10; ++i) {
            action.handleTimerEvent(STATE_WAITING_FOR_REPORT_POWER_STATUS);
            mTestLooper.dispatchAll();
        }

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);
        action.handleTimerEvent(STATE_WAITING_FOR_REPORT_POWER_STATUS);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_TIMEOUT);
    }

    @Test
    public void succeedIfPowerStatusOn_Cec20() throws Exception {
        setUp(true);

        mHdmiControlService.getHdmiCecNetwork().addCecDevice(INFO_TV);
        HdmiCecLocalDevicePlayback playbackDevice = new HdmiCecLocalDevicePlayback(
                mHdmiControlService);
        playbackDevice.init();
        mLocalDevices.add(playbackDevice);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mHdmiControlService.getHdmiCecNetwork().updateDevicePowerStatus(ADDR_TV,
                HdmiControlManager.POWER_STATUS_ON);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();

        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        OneTouchPlayAction action = createOneTouchPlayAction(playbackDevice, actionTimer, callback,
                true);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                playbackDevice.mAddress, mPhysicalAddress);
        HdmiCecMessage textViewOn = HdmiCecMessageBuilder.buildTextViewOn(playbackDevice.mAddress,
                ADDR_TV);
        HdmiCecMessage giveDevicePowerStatus = HdmiCecMessageBuilder
                .buildGiveDevicePowerStatus(playbackDevice.mAddress, ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(giveDevicePowerStatus);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void succeedIfPowerStatusUnknown_Cec20() throws Exception {
        setUp(true);

        mHdmiControlService.getHdmiCecNetwork().addCecDevice(INFO_TV);
        HdmiCecLocalDevicePlayback playbackDevice = new HdmiCecLocalDevicePlayback(
                mHdmiControlService);
        playbackDevice.init();
        mLocalDevices.add(playbackDevice);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mHdmiControlService.getHdmiCecNetwork().updateDevicePowerStatus(ADDR_TV,
                HdmiControlManager.POWER_STATUS_UNKNOWN);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();

        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        OneTouchPlayAction action = createOneTouchPlayAction(playbackDevice, actionTimer, callback,
                true);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                playbackDevice.mAddress, mPhysicalAddress);
        HdmiCecMessage textViewOn = HdmiCecMessageBuilder.buildTextViewOn(playbackDevice.mAddress,
                ADDR_TV);
        HdmiCecMessage giveDevicePowerStatus = HdmiCecMessageBuilder
                .buildGiveDevicePowerStatus(playbackDevice.mAddress, ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveDevicePowerStatus);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAITING_FOR_REPORT_POWER_STATUS);
        HdmiCecMessage reportPowerStatusOn = new HdmiCecMessage(
                ADDR_TV, playbackDevice.mAddress, Constants.MESSAGE_REPORT_POWER_STATUS, POWER_ON);
        action.processCommand(reportPowerStatusOn);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(giveDevicePowerStatus);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void succeedIfPowerStatusStandby_Cec20() throws Exception {
        setUp(true);

        mHdmiControlService.getHdmiCecNetwork().addCecDevice(INFO_TV);
        HdmiCecLocalDevicePlayback playbackDevice = new HdmiCecLocalDevicePlayback(
                mHdmiControlService);
        playbackDevice.init();
        mLocalDevices.add(playbackDevice);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mHdmiControlService.getHdmiCecNetwork().updateDevicePowerStatus(ADDR_TV,
                HdmiControlManager.POWER_STATUS_STANDBY);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();

        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        OneTouchPlayAction action = createOneTouchPlayAction(playbackDevice, actionTimer, callback,
                true);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                playbackDevice.mAddress, mPhysicalAddress);
        HdmiCecMessage textViewOn = HdmiCecMessageBuilder.buildTextViewOn(playbackDevice.mAddress,
                ADDR_TV);
        HdmiCecMessage giveDevicePowerStatus = HdmiCecMessageBuilder
                .buildGiveDevicePowerStatus(playbackDevice.mAddress, ADDR_TV);

        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(giveDevicePowerStatus);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAITING_FOR_REPORT_POWER_STATUS);
        HdmiCecMessage reportPowerStatusOn = new HdmiCecMessage(
                ADDR_TV, playbackDevice.mAddress, Constants.MESSAGE_REPORT_POWER_STATUS, POWER_ON);
        action.processCommand(reportPowerStatusOn);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(textViewOn);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(giveDevicePowerStatus);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void succeedWithAddressNotAllocated_Cec14b() throws Exception {
        setUp(false);

        assertThat(mHdmiControlService.isAddressAllocated()).isFalse();

        HdmiCecLocalDevicePlayback playbackDevice = new HdmiCecLocalDevicePlayback(
                mHdmiControlService);
        playbackDevice.init();
        mLocalDevices.add(playbackDevice);

        TestCallback callback = new TestCallback();

        mHdmiControlService.oneTouchPlay(callback);
        mTestLooper.dispatchAll();

        assertThat(callback.hasResult()).isFalse();
        mNativeWrapper.clearResultMessages();

        setHdmiControlEnabled(true);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);

        mTestLooper.dispatchAll();

        HdmiCecMessage reportPowerStatusMessage = HdmiCecMessageBuilder.buildReportPowerStatus(
                Constants.ADDR_TV,
                playbackDevice.mAddress,
                HdmiControlManager.POWER_STATUS_ON
        );
        mNativeWrapper.onCecMessage(reportPowerStatusMessage);

        mTestLooper.dispatchAll();

        assertThat(mHdmiControlService.isAddressAllocated()).isTrue();
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
        assertThat(playbackDevice.isActiveSource()).isTrue();
        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                playbackDevice.mAddress, mPhysicalAddress);
        HdmiCecMessage textViewOn = HdmiCecMessageBuilder.buildTextViewOn(playbackDevice.mAddress,
                ADDR_TV);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
    }

    @Test
    public void succeedWithAddressAllocated_Cec14b() throws Exception {
        setUp(true);

        HdmiCecLocalDevicePlayback playbackDevice = new HdmiCecLocalDevicePlayback(
                mHdmiControlService);
        playbackDevice.init();
        mLocalDevices.add(playbackDevice);

        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();
        assertThat(mHdmiControlService.isAddressAllocated()).isTrue();

        TestCallback callback = new TestCallback();
        mHdmiControlService.oneTouchPlay(callback);

        HdmiCecMessage reportPowerStatusMessage = HdmiCecMessageBuilder.buildReportPowerStatus(
                Constants.ADDR_TV,
                playbackDevice.mAddress,
                HdmiControlManager.POWER_STATUS_ON
        );
        mNativeWrapper.onCecMessage(reportPowerStatusMessage);

        mTestLooper.dispatchAll();

        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
        assertThat(playbackDevice.isActiveSource()).isTrue();
        HdmiCecMessage activeSource = HdmiCecMessageBuilder.buildActiveSource(
                playbackDevice.mAddress, mPhysicalAddress);
        HdmiCecMessage textViewOn = HdmiCecMessageBuilder.buildTextViewOn(playbackDevice.mAddress,
                ADDR_TV);
        assertThat(mNativeWrapper.getResultMessages()).contains(activeSource);
        assertThat(mNativeWrapper.getResultMessages()).contains(textViewOn);
    }

    @Test
    public void pendingActionDoesNotBlockSendingStandby_Cec14b() throws Exception {
        setUp(true);

        mHdmiControlService.getHdmiCecNetwork().addCecDevice(INFO_TV);
        HdmiCecLocalDevicePlayback playbackDevice = new HdmiCecLocalDevicePlayback(
                mHdmiControlService);
        playbackDevice.init();
        mLocalDevices.add(playbackDevice);
        playbackDevice.mService.getHdmiCecConfig().setStringValue(
                HdmiControlManager.CEC_SETTING_NAME_POWER_CONTROL_MODE,
                HdmiControlManager.POWER_CONTROL_MODE_TV);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();

        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        OneTouchPlayAction action = createOneTouchPlayAction(playbackDevice, actionTimer, callback,
                false);
        playbackDevice.addAndStartAction(action);
        mTestLooper.dispatchAll();

        assertThat(actionTimer.getState()).isEqualTo(STATE_WAITING_FOR_REPORT_POWER_STATUS);
        for (int i = 0; i < 5; ++i) {
            action.handleTimerEvent(STATE_WAITING_FOR_REPORT_POWER_STATUS);
            mTestLooper.dispatchAll();
        }
        mNativeWrapper.clearResultMessages();

        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        mTestLooper.dispatchAll();
        HdmiCecMessage standbyMessage = HdmiCecMessageBuilder.buildStandby(
                playbackDevice.mAddress, ADDR_TV);
        assertThat(mNativeWrapper.getResultMessages()).contains(standbyMessage);
    }

    private static class TestActionTimer implements ActionTimer {
        private int mState;

        @Override
        public void sendTimerMessage(int state, long delayMillis) {
            mState = state;
        }

        @Override
        public void clearTimerMessage() {
        }

        private int getState() {
            return mState;
        }
    }

    private static class TestCallback extends IHdmiControlCallback.Stub {
        private final ArrayList<Integer> mCallbackResult = new ArrayList<Integer>();

        @Override
        public void onComplete(int result) {
            mCallbackResult.add(result);
        }

        private boolean hasResult() {
            return mCallbackResult.size() != 0;
        }

        private int getResult() {
            assertThat(mCallbackResult.size()).isEqualTo(1);
            return mCallbackResult.get(0);
        }
    }

    private void setHdmiControlEnabled(boolean enabled) {
        int value = enabled ? HdmiControlManager.HDMI_CEC_CONTROL_ENABLED :
                HdmiControlManager.HDMI_CEC_CONTROL_DISABLED;
        mHdmiCecConfig.setIntValue(HdmiControlManager.CEC_SETTING_NAME_HDMI_CEC_ENABLED, value);
    }
}
