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
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_3;
import static com.android.server.hdmi.DeviceSelectActionFromPlayback.STATE_WAIT_FOR_ACTIVE_SOURCE_MESSAGE_AFTER_ROUTING_CHANGE;
import static com.android.server.hdmi.DeviceSelectActionFromPlayback.STATE_WAIT_FOR_DEVICE_POWER_ON;
import static com.android.server.hdmi.DeviceSelectActionFromPlayback.STATE_WAIT_FOR_REPORT_POWER_STATUS;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
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
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class DeviceSelectActionFromPlaybackTest {

    private static final int PORT_1 = 1;
    private static final int PORT_2 = 1;
    private static final int PORT_3 = 1;
    private static final int PHYSICAL_ADDRESS_PLAYBACK_1 = 0x1000;
    private static final int PHYSICAL_ADDRESS_PLAYBACK_2 = 0x2000;
    private static final int PHYSICAL_ADDRESS_PLAYBACK_3 = 0x3000;

    private static final byte[] POWER_ON = new byte[] { POWER_STATUS_ON };
    private static final byte[] POWER_STANDBY = new byte[] { POWER_STATUS_STANDBY };
    private static final byte[] POWER_TRANSIENT_TO_ON = new byte[] { POWER_STATUS_TRANSIENT_TO_ON };

    private HdmiCecMessage mReportPowerStatusOn;
    private HdmiCecMessage mReportPowerStatusStandby;
    private HdmiCecMessage mReportPowerStatusTransientToOn;
    private HdmiCecMessage mSetStreamPath;
    private HdmiCecMessage mRoutingChange;
    private HdmiCecMessage mActiveSource;

    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDevicePlayback mHdmiCecLocalDevicePlayback;
    private HdmiCecNetwork mHdmiCecNetwork;
    private HdmiControlService mHdmiControlService;
    private HdmiMhlControllerStub mHdmiMhlControllerStub;

    private FakeNativeWrapper mNativeWrapper;
    private FakePowerManagerWrapper mPowerManager;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();

    private int mPlaybackLogicalAddress1;
    private int mPlaybackLogicalAddress2;
    private int mPlaybackLogicalAddress3;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        Context context = InstrumentationRegistry.getTargetContext();
        mMyLooper = mTestLooper.getLooper();

        mHdmiControlService =
                new HdmiControlService(InstrumentationRegistry.getTargetContext(),
                        Collections.singletonList(HdmiDeviceInfo.DEVICE_PLAYBACK),
                        new FakeAudioDeviceVolumeManagerWrapper()) {
                    @Override
                    boolean isCecControlEnabled() {
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


        mHdmiControlService.setIoLooper(mMyLooper);
        mHdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(context));
        mHdmiControlService.setDeviceConfig(new FakeDeviceConfigWrapper());
        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiMhlControllerStub = HdmiMhlControllerStub.create(mHdmiControlService);
        mHdmiControlService.setHdmiMhlController(mHdmiMhlControllerStub);
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(mHdmiMhlControllerStub);
        mHdmiCecNetwork = new HdmiCecNetwork(mHdmiControlService,
                mHdmiCecController, mHdmiMhlControllerStub);
        mHdmiControlService.setHdmiCecNetwork(mHdmiCecNetwork);

        mHdmiControlService.initService();
        mHdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);
        mNativeWrapper.setPhysicalAddress(0x0000);
        mPowerManager = new FakePowerManagerWrapper(context);
        mHdmiControlService.setPowerManager(mPowerManager);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();
        mHdmiCecLocalDevicePlayback = mHdmiControlService.playback();
        // The addresses depend on local device's LA.
        // This help the tests to pass with every local device LA.
        mPlaybackLogicalAddress1 =
                mHdmiCecLocalDevicePlayback.getDeviceInfo().getLogicalAddress();

        mPlaybackLogicalAddress2 = mPlaybackLogicalAddress1 == ADDR_PLAYBACK_2
                ? ADDR_PLAYBACK_1 : ADDR_PLAYBACK_2;
        mPlaybackLogicalAddress3 = mPlaybackLogicalAddress1 == ADDR_PLAYBACK_3
                ? ADDR_PLAYBACK_1 : ADDR_PLAYBACK_3;

        mReportPowerStatusOn = HdmiCecMessage.build(
                mPlaybackLogicalAddress2, mPlaybackLogicalAddress1,
                Constants.MESSAGE_REPORT_POWER_STATUS, POWER_ON);
        mReportPowerStatusStandby = HdmiCecMessage.build(
                mPlaybackLogicalAddress2, mPlaybackLogicalAddress1,
                Constants.MESSAGE_REPORT_POWER_STATUS, POWER_STANDBY);
        mReportPowerStatusTransientToOn = HdmiCecMessage.build(
                mPlaybackLogicalAddress2, mPlaybackLogicalAddress1,
                Constants.MESSAGE_REPORT_POWER_STATUS, POWER_TRANSIENT_TO_ON);
        mSetStreamPath = HdmiCecMessageBuilder.buildSetStreamPath(
                mPlaybackLogicalAddress1, PHYSICAL_ADDRESS_PLAYBACK_2);
        mRoutingChange = HdmiCecMessageBuilder.buildRoutingChange(
                mPlaybackLogicalAddress1, PHYSICAL_ADDRESS_PLAYBACK_3,
                PHYSICAL_ADDRESS_PLAYBACK_2);
        mActiveSource = HdmiCecMessageBuilder.buildActiveSource(
                mPlaybackLogicalAddress2, PHYSICAL_ADDRESS_PLAYBACK_2);

        HdmiDeviceInfo infoPlayback1 = HdmiDeviceInfo.cecDeviceBuilder()
                .setLogicalAddress(mPlaybackLogicalAddress1)
                .setPhysicalAddress(PHYSICAL_ADDRESS_PLAYBACK_1)
                .setPortId(PORT_1)
                .setDeviceType(HdmiDeviceInfo.DEVICE_PLAYBACK)
                .setVendorId(0x1234)
                .setDisplayName("Playback 1")
                .setDevicePowerStatus(HdmiControlManager.POWER_STATUS_ON)
                .setCecVersion(HdmiControlManager.HDMI_CEC_VERSION_1_4_B)
                .build();
        HdmiDeviceInfo infoPlayback2 = HdmiDeviceInfo.cecDeviceBuilder()
                .setLogicalAddress(mPlaybackLogicalAddress2)
                .setPhysicalAddress(PHYSICAL_ADDRESS_PLAYBACK_2)
                .setPortId(PORT_2)
                .setDeviceType(HdmiDeviceInfo.DEVICE_PLAYBACK)
                .setVendorId(0x1234)
                .setDisplayName("Playback 2")
                .setDevicePowerStatus(HdmiControlManager.POWER_STATUS_ON)
                .setCecVersion(HdmiControlManager.HDMI_CEC_VERSION_1_4_B)
                .build();
        HdmiDeviceInfo infoPlayback3 = HdmiDeviceInfo.cecDeviceBuilder()
                .setLogicalAddress(mPlaybackLogicalAddress3)
                .setPhysicalAddress(PHYSICAL_ADDRESS_PLAYBACK_3)
                .setPortId(PORT_3)
                .setDeviceType(HdmiDeviceInfo.DEVICE_PLAYBACK)
                .setVendorId(0x1234)
                .setDisplayName("Playback 3")
                .setDevicePowerStatus(HdmiControlManager.POWER_STATUS_ON)
                .setCecVersion(HdmiControlManager.HDMI_CEC_VERSION_1_4_B)
                .build();

        mHdmiControlService.getHdmiCecNetwork().addCecDevice(infoPlayback1);
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(infoPlayback2);
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(infoPlayback3);

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

    private DeviceSelectActionFromPlayback createDeviceSelectActionFromPlayback(
            TestActionTimer actionTimer,
            TestCallback callback,
            boolean isCec20) {
        HdmiDeviceInfo hdmiDeviceInfo =
                mHdmiControlService.getHdmiCecNetwork().getCecDeviceInfo(mPlaybackLogicalAddress2);
        DeviceSelectActionFromPlayback action = new DeviceSelectActionFromPlayback(
                mHdmiCecLocalDevicePlayback,
                hdmiDeviceInfo, callback, isCec20);
        action.setActionTimer(actionTimer);
        return action;
    }

    @Test
    public void testDeviceSelect_DeviceInPowerOnStatus_Cec14b() {
        // TV was watching playback3 device connected at port 3, and wants to select
        // playback2.
        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        DeviceSelectActionFromPlayback action = createDeviceSelectActionFromPlayback(actionTimer,
                callback, /*isCec20=*/false);
        mHdmiControlService.setActiveSource(mPlaybackLogicalAddress3, PHYSICAL_ADDRESS_PLAYBACK_3,
                "testDeviceSelectFromPlayback");
        action.start();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(mRoutingChange);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.processCommand(mReportPowerStatusOn);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(mRoutingChange);
        action.processCommand(mActiveSource);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void testDeviceSelect_DeviceInStandbyStatus_Cec14b() {
        mHdmiControlService.setActiveSource(mPlaybackLogicalAddress3, PHYSICAL_ADDRESS_PLAYBACK_3,
                "testDeviceSelectFromPlayback");
        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        DeviceSelectActionFromPlayback action = createDeviceSelectActionFromPlayback(actionTimer,
                callback, /*isCec20=*/false);
        action.start();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(mRoutingChange);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.processCommand(mReportPowerStatusStandby);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(mRoutingChange);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_DEVICE_POWER_ON);
        action.handleTimerEvent(STATE_WAIT_FOR_DEVICE_POWER_ON);
        action.processCommand(mReportPowerStatusOn);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(mRoutingChange);
        action.processCommand(mActiveSource);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void testDeviceSelect_DeviceInStandbyStatusWithSomeTimeouts_Cec14b() {
        mHdmiControlService.setActiveSource(mPlaybackLogicalAddress3, PHYSICAL_ADDRESS_PLAYBACK_3,
                "testDeviceSelectFromPlayback");
        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        DeviceSelectActionFromPlayback action = createDeviceSelectActionFromPlayback(actionTimer,
                callback, /*isCec20=*/false);
        action.start();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(mRoutingChange);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.processCommand(mReportPowerStatusStandby);
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_DEVICE_POWER_ON);
        action.handleTimerEvent(STATE_WAIT_FOR_DEVICE_POWER_ON);
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.processCommand(mReportPowerStatusTransientToOn);
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_DEVICE_POWER_ON);
        action.handleTimerEvent(STATE_WAIT_FOR_DEVICE_POWER_ON);
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.processCommand(mReportPowerStatusOn);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(mRoutingChange);
        action.processCommand(mActiveSource);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void testDeviceSelect_DeviceInStandbyAfterTimeoutForReportPowerStatus_Cec14b() {
        mHdmiControlService.setActiveSource(mPlaybackLogicalAddress3, PHYSICAL_ADDRESS_PLAYBACK_3,
                "testDeviceSelectFromPlayback");
        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        DeviceSelectActionFromPlayback action = createDeviceSelectActionFromPlayback(actionTimer,
                callback, /*isCec20=*/false);
        action.start();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(mRoutingChange);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.processCommand(mReportPowerStatusStandby);
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_DEVICE_POWER_ON);
        action.handleTimerEvent(STATE_WAIT_FOR_DEVICE_POWER_ON);
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.processCommand(mReportPowerStatusTransientToOn);
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_DEVICE_POWER_ON);
        action.handleTimerEvent(STATE_WAIT_FOR_DEVICE_POWER_ON);
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.handleTimerEvent(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        // Give up getting power status, and just send <Routing Change>
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(mRoutingChange);
        action.processCommand(mActiveSource);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void testDeviceSelect_ReachmSetStreamPath_Cec14b() {
        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        DeviceSelectActionFromPlayback action = createDeviceSelectActionFromPlayback(actionTimer,
                callback, /*isCec20=*/false);
        mHdmiControlService.setActiveSource(mPlaybackLogicalAddress3, PHYSICAL_ADDRESS_PLAYBACK_3,
                "testDeviceSelectFromPlayback");
        action.start();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(mRoutingChange);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.processCommand(mReportPowerStatusOn);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(mRoutingChange);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(
                STATE_WAIT_FOR_ACTIVE_SOURCE_MESSAGE_AFTER_ROUTING_CHANGE);
        action.handleTimerEvent(STATE_WAIT_FOR_ACTIVE_SOURCE_MESSAGE_AFTER_ROUTING_CHANGE);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(mSetStreamPath);
        action.processCommand(mActiveSource);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void testDeviceSelect_ReachmSetStreamPathDeviceInPowerOnStatus_Cec20() {
        mHdmiControlService.getHdmiCecNetwork().updateDevicePowerStatus(mPlaybackLogicalAddress2,
                HdmiControlManager.POWER_STATUS_ON);
        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        DeviceSelectActionFromPlayback action = createDeviceSelectActionFromPlayback(actionTimer,
                callback, /*isCec20=*/true);
        mHdmiControlService.setActiveSource(mPlaybackLogicalAddress3, PHYSICAL_ADDRESS_PLAYBACK_3,
                "testDeviceSelectFromPlayback");
        action.start();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(mRoutingChange);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(
                STATE_WAIT_FOR_ACTIVE_SOURCE_MESSAGE_AFTER_ROUTING_CHANGE);
        action.handleTimerEvent(STATE_WAIT_FOR_ACTIVE_SOURCE_MESSAGE_AFTER_ROUTING_CHANGE);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(mSetStreamPath);
        action.processCommand(mActiveSource);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void testDeviceSelect_DeviceInPowerOnStatus_Cec20() {
        mHdmiControlService.getHdmiCecNetwork().updateDevicePowerStatus(mPlaybackLogicalAddress2,
                HdmiControlManager.POWER_STATUS_ON);
        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        DeviceSelectActionFromPlayback action = createDeviceSelectActionFromPlayback(actionTimer,
                callback, /*isCec20=*/true);
        mHdmiControlService.setActiveSource(mPlaybackLogicalAddress3, PHYSICAL_ADDRESS_PLAYBACK_3,
                "testDeviceSelectFromPlayback");
        action.start();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(mRoutingChange);
        action.processCommand(mActiveSource);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void testDeviceSelect_DeviceInPowerUnknownStatus_Cec20() {
        mHdmiControlService.getHdmiCecNetwork().updateDevicePowerStatus(mPlaybackLogicalAddress2,
                HdmiControlManager.POWER_STATUS_UNKNOWN);
        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        DeviceSelectActionFromPlayback action = createDeviceSelectActionFromPlayback(actionTimer,
                callback, /*isCec20=*/true);
        mHdmiControlService.setActiveSource(mPlaybackLogicalAddress3, PHYSICAL_ADDRESS_PLAYBACK_3,
                "testDeviceSelectFromPlayback");
        action.start();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(mRoutingChange);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.processCommand(mReportPowerStatusOn);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(mRoutingChange);
        action.processCommand(mActiveSource);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }

    @Test
    public void testDeviceSelect_DeviceInStandbyStatus_Cec20() {
        mHdmiControlService.getHdmiCecNetwork().updateDevicePowerStatus(mPlaybackLogicalAddress2,
                HdmiControlManager.POWER_STATUS_STANDBY);
        mHdmiControlService.setActiveSource(mPlaybackLogicalAddress3, PHYSICAL_ADDRESS_PLAYBACK_3,
                "testDeviceSelectFromPlayback");
        TestActionTimer actionTimer = new TestActionTimer();
        TestCallback callback = new TestCallback();
        DeviceSelectActionFromPlayback action = createDeviceSelectActionFromPlayback(actionTimer,
                callback, /*isCec20=*/true);
        action.start();
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(mRoutingChange);
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_REPORT_POWER_STATUS);
        action.processCommand(mReportPowerStatusStandby);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_DEVICE_POWER_ON);
        action.handleTimerEvent(STATE_WAIT_FOR_DEVICE_POWER_ON);
        action.processCommand(mReportPowerStatusOn);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(mRoutingChange);
        action.processCommand(mActiveSource);
        assertThat(callback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
    }
}
