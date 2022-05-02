/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.hardware.hdmi.HdmiControlManager.POWER_STATUS_ON;
import static android.hardware.hdmi.HdmiControlManager.POWER_STATUS_STANDBY;
import static android.hardware.hdmi.HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON;

import static com.android.server.SystemService.PHASE_SYSTEM_SERVICES_READY;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_1;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_2;
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.DeviceSelectActionFromTv.STATE_WAIT_FOR_DEVICE_POWER_ON;
import static com.android.server.hdmi.DeviceSelectActionFromTv.STATE_WAIT_FOR_REPORT_POWER_STATUS;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.os.Looper;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.hdmi.HdmiCecFeatureAction.ActionTimer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Collections;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class DeviceSelectActionFromTvTest {

    private static final int PORT_1 = 1;
    private static final int PORT_2 = 1;
    private static final int PHYSICAL_ADDRESS_PLAYBACK_1 = 0x1000;
    private static final int PHYSICAL_ADDRESS_PLAYBACK_2 = 0x2000;

    private static final byte[] POWER_ON = new byte[] { POWER_STATUS_ON };
    private static final byte[] POWER_STANDBY = new byte[] { POWER_STATUS_STANDBY };
    private static final byte[] POWER_TRANSIENT_TO_ON = new byte[] { POWER_STATUS_TRANSIENT_TO_ON };
    private static final HdmiCecMessage REPORT_POWER_STATUS_ON = HdmiCecMessage.build(
            ADDR_PLAYBACK_1, ADDR_TV, Constants.MESSAGE_REPORT_POWER_STATUS, POWER_ON);
    private static final HdmiCecMessage REPORT_POWER_STATUS_STANDBY = HdmiCecMessage.build(
            ADDR_PLAYBACK_1, ADDR_TV, Constants.MESSAGE_REPORT_POWER_STATUS, POWER_STANDBY);
    private static final HdmiCecMessage REPORT_POWER_STATUS_TRANSIENT_TO_ON = HdmiCecMessage.build(
            ADDR_PLAYBACK_1, ADDR_TV, Constants.MESSAGE_REPORT_POWER_STATUS, POWER_TRANSIENT_TO_ON);
    private static final HdmiCecMessage SET_STREAM_PATH = HdmiCecMessageBuilder.buildSetStreamPath(
                        ADDR_TV, PHYSICAL_ADDRESS_PLAYBACK_1);
    private static final HdmiDeviceInfo INFO_PLAYBACK_1 = HdmiDeviceInfo.cecDeviceBuilder()
            .setLogicalAddress(ADDR_PLAYBACK_1)
            .setPhysicalAddress(PHYSICAL_ADDRESS_PLAYBACK_1)
            .setPortId(PORT_1)
            .setDeviceType(HdmiDeviceInfo.DEVICE_PLAYBACK)
            .setVendorId(0x1234)
            .setDisplayName("Plyback 1")
            .setDevicePowerStatus(HdmiControlManager.POWER_STATUS_ON)
            .setCecVersion(HdmiControlManager.HDMI_CEC_VERSION_1_4_B)
            .build();
    private static final HdmiDeviceInfo INFO_PLAYBACK_2 = HdmiDeviceInfo.cecDeviceBuilder()
            .setLogicalAddress(ADDR_PLAYBACK_2)
            .setPhysicalAddress(PHYSICAL_ADDRESS_PLAYBACK_2)
            .setPortId(PORT_2)
            .setDeviceType(HdmiDeviceInfo.DEVICE_PLAYBACK)
            .setVendorId(0x1234)
            .setDisplayName("Playback 2")
            .setDevicePowerStatus(HdmiControlManager.POWER_STATUS_ON)
            .setCecVersion(HdmiControlManager.HDMI_CEC_VERSION_1_4_B)
            .build();

    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDeviceTv mHdmiCecLocalDeviceTv;
    private FakeNativeWrapper mNativeWrapper;
    private FakePowerManagerWrapper mPowerManager;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getTargetContext();
        mMyLooper = mTestLooper.getLooper();

        mHdmiControlService =
                new HdmiControlService(InstrumentationRegistry.getTargetContext(),
                        Collections.emptyList(), new FakeAudioDeviceVolumeManagerWrapper()) {
                    @Override
                    boolean isControlEnabled() {
                        return true;
                    }

                    @Override
                    protected void writeStringSystemProperty(String key, String value) {
                        // do nothing
                    }

                    @Override
                    boolean isPowerStandbyOrTransient() {
                        return false;
                    }
                };

        mHdmiCecLocalDeviceTv = new HdmiCecLocalDeviceTv(mHdmiControlService);
        mHdmiCecLocalDeviceTv.init();
        mHdmiControlService.setIoLooper(mMyLooper);
        mHdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(context));
        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mLocalDevices.add(mHdmiCecLocalDeviceTv);
        HdmiPortInfo[] hdmiPortInfos = new HdmiPortInfo[2];
        hdmiPortInfos[0] =
                new HdmiPortInfo(1, HdmiPortInfo.PORT_INPUT, PHYSICAL_ADDRESS_PLAYBACK_1,
                                 true, false, false);
        hdmiPortInfos[1] =
                new HdmiPortInfo(2, HdmiPortInfo.PORT_INPUT, PHYSICAL_ADDRESS_PLAYBACK_2,
                                 true, false, false);
        mNativeWrapper.setPortInfo(hdmiPortInfos);
        mHdmiControlService.initService();
        mHdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);
        mPowerManager = new FakePowerManagerWrapper(context);
        mHdmiControlService.setPowerManager(mPowerManager);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mNativeWrapper.setPhysicalAddress(0x0000);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(INFO_PLAYBACK_1);
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(INFO_PLAYBACK_2);
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

        private int getResult() {
            assertThat(mCallbackResult.size()).isEqualTo(1);
            return mCallbackResult.get(0);
        }
    }

    private DeviceSelectActionFromTv createDeviceSelectAction(TestActionTimer actionTimer,
                                                        TestCallback callback,
                                                        boolean isCec20) {
        HdmiDeviceInfo hdmiDeviceInfo =
                mHdmiControlService.getHdmiCecNetwork().getCecDeviceInfo(ADDR_PLAYBACK_1);
        DeviceSelectActionFromTv action = new DeviceSelectActionFromTv(mHdmiCecLocalDeviceTv,
                                                           hdmiDeviceInfo, callback, isCec20);
        action.setActionTimer(actionTimer);
        return action;
    }

    @Test
    public void testDeviceSelect_DeviceInPowerOnStatus_Cec14b() {
        // TV was watching playback2 device connected at port 2, and wants to select
        // playback1.
        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        DeviceSelectActionFromTv action = createDeviceSelectAction(actionTimer, callback,
                                        /*isCec20=*/false);
        mHdmiCecLocalDeviceTv.updateActiveSource(ADDR_PLAYBACK_2, PHYSICAL_ADDRESS_PLAYBACK_2,
                                                 "testDeviceSelect");
        action.start();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(SET_STREAM_PATH);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.processCommand(REPORT_POWER_STATUS_ON);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(SET_STREAM_PATH);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void testDeviceSelect_DeviceInStandbyStatus_Cec14b() {
        mHdmiCecLocalDeviceTv.updateActiveSource(ADDR_PLAYBACK_2, PHYSICAL_ADDRESS_PLAYBACK_2,
                                                 "testDeviceSelect");
        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        DeviceSelectActionFromTv action = createDeviceSelectAction(actionTimer, callback,
                                        /*isCec20=*/false);
        action.start();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(SET_STREAM_PATH);
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.processCommand(REPORT_POWER_STATUS_STANDBY);
        mTestLooper.dispatchAll();
        HdmiCecMessage userControlPressed = HdmiCecMessageBuilder.buildUserControlPressed(
                        ADDR_TV, ADDR_PLAYBACK_1, HdmiCecKeycode.CEC_KEYCODE_POWER);
        assertThat(mNativeWrapper.getResultMessages()).contains(userControlPressed);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_DEVICE_POWER_ON);
        action.handleTimerEvent(STATE_WAIT_FOR_DEVICE_POWER_ON);
        action.processCommand(REPORT_POWER_STATUS_ON);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(SET_STREAM_PATH);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void testDeviceSelect_DeviceInStandbyStatusWithSomeTimeouts_Cec14b() {
        mHdmiCecLocalDeviceTv.updateActiveSource(ADDR_PLAYBACK_2, PHYSICAL_ADDRESS_PLAYBACK_2,
                                                 "testDeviceSelect");
        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        DeviceSelectActionFromTv action = createDeviceSelectAction(actionTimer, callback,
                                        /*isCec20=*/false);
        action.start();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(SET_STREAM_PATH);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.processCommand(REPORT_POWER_STATUS_STANDBY);
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_DEVICE_POWER_ON);
        action.handleTimerEvent(STATE_WAIT_FOR_DEVICE_POWER_ON);
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.processCommand(REPORT_POWER_STATUS_TRANSIENT_TO_ON);
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_DEVICE_POWER_ON);
        action.handleTimerEvent(STATE_WAIT_FOR_DEVICE_POWER_ON);
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.processCommand(REPORT_POWER_STATUS_ON);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(SET_STREAM_PATH);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void testDeviceSelect_DeviceInStandbyAfterTimeoutForReportPowerStatus_Cec14b() {
        mHdmiCecLocalDeviceTv.updateActiveSource(ADDR_PLAYBACK_2, PHYSICAL_ADDRESS_PLAYBACK_2,
                                                 "testDeviceSelect");
        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        DeviceSelectActionFromTv action = createDeviceSelectAction(actionTimer, callback,
                                        /*isCec20=*/false);
        action.start();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(SET_STREAM_PATH);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.processCommand(REPORT_POWER_STATUS_STANDBY);
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_DEVICE_POWER_ON);
        action.handleTimerEvent(STATE_WAIT_FOR_DEVICE_POWER_ON);
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.processCommand(REPORT_POWER_STATUS_TRANSIENT_TO_ON);
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_DEVICE_POWER_ON);
        action.handleTimerEvent(STATE_WAIT_FOR_DEVICE_POWER_ON);
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.handleTimerEvent(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        // Give up getting power status, and just send <Set Stream Path>
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(SET_STREAM_PATH);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void testDeviceSelect_DeviceInPowerOnStatus_Cec20() {
        mHdmiControlService.getHdmiCecNetwork().updateDevicePowerStatus(ADDR_PLAYBACK_1,
                HdmiControlManager.POWER_STATUS_ON);
        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        DeviceSelectActionFromTv action = createDeviceSelectAction(actionTimer, callback,
                                        /*isCec20=*/true);
        mHdmiCecLocalDeviceTv.updateActiveSource(ADDR_PLAYBACK_2, PHYSICAL_ADDRESS_PLAYBACK_2,
                                                 "testDeviceSelect");
        action.start();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(SET_STREAM_PATH);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void testDeviceSelect_DeviceInPowerUnknownStatus_Cec20() {
        mHdmiControlService.getHdmiCecNetwork().updateDevicePowerStatus(ADDR_PLAYBACK_1,
                HdmiControlManager.POWER_STATUS_UNKNOWN);
        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        DeviceSelectActionFromTv action = createDeviceSelectAction(actionTimer, callback,
                                        /*isCec20=*/true);
        mHdmiCecLocalDeviceTv.updateActiveSource(ADDR_PLAYBACK_2, PHYSICAL_ADDRESS_PLAYBACK_2,
                                                 "testDeviceSelect");
        action.start();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(SET_STREAM_PATH);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.processCommand(REPORT_POWER_STATUS_ON);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(SET_STREAM_PATH);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void testDeviceSelect_DeviceInStandbyStatus_Cec20() {
        mHdmiControlService.getHdmiCecNetwork().updateDevicePowerStatus(ADDR_PLAYBACK_1,
                HdmiControlManager.POWER_STATUS_STANDBY);
        mHdmiCecLocalDeviceTv.updateActiveSource(ADDR_PLAYBACK_2, PHYSICAL_ADDRESS_PLAYBACK_2,
                                                 "testDeviceSelect");
        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        DeviceSelectActionFromTv action = createDeviceSelectAction(actionTimer, callback,
                                        /*isCec20=*/true);
        action.start();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(SET_STREAM_PATH);
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.processCommand(REPORT_POWER_STATUS_STANDBY);
        mTestLooper.dispatchAll();
        HdmiCecMessage userControlPressed = HdmiCecMessageBuilder.buildUserControlPressed(
                        ADDR_TV, ADDR_PLAYBACK_1, HdmiCecKeycode.CEC_KEYCODE_POWER);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(userControlPressed);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_DEVICE_POWER_ON);
        action.handleTimerEvent(STATE_WAIT_FOR_DEVICE_POWER_ON);
        action.processCommand(REPORT_POWER_STATUS_ON);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(SET_STREAM_PATH);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }
}
