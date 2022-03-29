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

import static com.android.server.hdmi.Constants.ADDR_AUDIO_SYSTEM;
import static com.android.server.hdmi.Constants.ADDR_BROADCAST;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_1;
import static com.android.server.hdmi.Constants.ADDR_TUNER_1;
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.Constants.ADDR_UNREGISTERED;
import static com.android.server.hdmi.Constants.MESSAGE_ACTIVE_SOURCE;
import static com.android.server.hdmi.Constants.MESSAGE_ROUTING_INFORMATION;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;
import static com.android.server.hdmi.RoutingControlAction.STATE_WAIT_FOR_ROUTING_INFORMATION;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
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
import java.util.List;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class RoutingControlActionTest {
    /*
     * Example connection diagram used in tests. Double-lined paths indicate the currently active
     * routes.
     *
     *
     *                              +-----------+
     *                              |    TV     |
     *                              |  0.0.0.0  |
     *                              +---+-----+-+
     *                                  |     |
     *                               <----------+ 1) AVR -> Switch
     *             +----------+         |     |  +-----------+
     *             | AVR      +---------+     +--+ Switch    |
     *             | 1.0.0.0  |                  | 2.0.0.0   |
     *             +--+---++--+                  +--++-----+-+  <-------+ 2) Recorder -> Blu-ray
     *                |   ||                        ||     |
     *                |   ||                        ||     +--------+
     * +-----------+  |   ||  +----------+     +----++----+         |
     * | XBox      +--+   ++--+ Tuner    |     | Blueray  |   +-----+----+
     * | 1.1.0.0   |          | 1.2.0.0  |     | 2.1.0.0  |   | Recorder |
     * +-----------+          +----++----+     +----------+   | 2.2.0.0  |
     *                             ||                         +----------+
     *                             ||
     *                        +----++----+
     *                        | Player   |
     *                        | 1.2.1.0  |
     *                        +----------+
     *
     */

    private static final int PHYSICAL_ADDRESS_TV = 0x0000;
    private static final int PHYSICAL_ADDRESS_AVR = 0x1000;
    private static final int PHYSICAL_ADDRESS_SWITCH = 0x2000;
    private static final int PHYSICAL_ADDRESS_TUNER = 0x1200;
    private static final int PHYSICAL_ADDRESS_PLAYER = 0x1210;
    private static final int PHYSICAL_ADDRESS_BLUERAY = 0x2100;
    private static final int PHYSICAL_ADDRESS_RECORDER = 0x2200;
    private static final int PORT_1 = 1;
    private static final int PORT_2 = 2;
    private static final int VENDOR_ID_AVR = 0x11233;

    private static final byte[] TUNER_PARAM =
            new byte[]{(PHYSICAL_ADDRESS_TUNER >> 8) & 0xFF, PHYSICAL_ADDRESS_TUNER & 0xFF};
    private static final byte[] PLAYER_PARAM =
            new byte[]{(PHYSICAL_ADDRESS_PLAYER >> 8) & 0xFF, PHYSICAL_ADDRESS_PLAYER & 0xFF};

    private static final HdmiDeviceInfo DEVICE_INFO_AVR = HdmiDeviceInfo.cecDeviceBuilder()
            .setLogicalAddress(ADDR_AUDIO_SYSTEM)
            .setPhysicalAddress(PHYSICAL_ADDRESS_AVR)
            .setPortId(PORT_1)
            .setDeviceType(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM)
            .setVendorId(VENDOR_ID_AVR)
            .setDisplayName("Audio")
            .build();
    private static final HdmiDeviceInfo DEVICE_INFO_PLAYER = HdmiDeviceInfo.cecDeviceBuilder()
            .setLogicalAddress(ADDR_PLAYBACK_1)
            .setPhysicalAddress(PHYSICAL_ADDRESS_PLAYER)
            .setPortId(PORT_1)
            .setDeviceType(HdmiDeviceInfo.DEVICE_PLAYBACK)
            .setVendorId(VENDOR_ID_AVR)
            .setDisplayName("Player")
            .build();
    private static final HdmiCecMessage ROUTING_INFORMATION_TUNER = HdmiCecMessage.build(
            ADDR_UNREGISTERED, ADDR_BROADCAST, MESSAGE_ROUTING_INFORMATION, TUNER_PARAM);
    private static final HdmiCecMessage ROUTING_INFORMATION_PLAYER = HdmiCecMessage.build(
            ADDR_UNREGISTERED, ADDR_BROADCAST, MESSAGE_ROUTING_INFORMATION, PLAYER_PARAM);
    private static final HdmiCecMessage ACTIVE_SOURCE_TUNER = HdmiCecMessage.build(
            ADDR_TUNER_1, ADDR_BROADCAST, MESSAGE_ACTIVE_SOURCE, TUNER_PARAM);
    private static final HdmiCecMessage ACTIVE_SOURCE_PLAYER = HdmiCecMessage.build(
            ADDR_PLAYBACK_1, ADDR_BROADCAST, MESSAGE_ACTIVE_SOURCE, PLAYER_PARAM);

    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDeviceTv mHdmiCecLocalDeviceTv;
    private FakeNativeWrapper mNativeWrapper;
    private FakePowerManagerWrapper mPowerManager;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();

    private static RoutingControlAction createRoutingControlAction(HdmiCecLocalDeviceTv localDevice,
            TestInputSelectCallback callback) {
        return new RoutingControlAction(localDevice, PHYSICAL_ADDRESS_AVR, callback);
    }

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getTargetContext();
        mMyLooper = mTestLooper.getLooper();

        HdmiCecConfig hdmiCecConfig = new FakeHdmiCecConfig(context);

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

                    @Override
                    protected HdmiCecConfig getHdmiCecConfig() {
                        return hdmiCecConfig;
                    }
                };

        mHdmiCecLocalDeviceTv = new HdmiCecLocalDeviceTv(mHdmiControlService);
        mHdmiCecLocalDeviceTv.init();
        mHdmiControlService.setIoLooper(mMyLooper);
        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mLocalDevices.add(mHdmiCecLocalDeviceTv);
        HdmiPortInfo[] hdmiPortInfos = new HdmiPortInfo[1];
        hdmiPortInfos[0] =
                new HdmiPortInfo(1, HdmiPortInfo.PORT_INPUT, PHYSICAL_ADDRESS_AVR,
                        true, false, false);
        mNativeWrapper.setPortInfo(hdmiPortInfos);
        mHdmiControlService.initService();
        mPowerManager = new FakePowerManagerWrapper(context);
        mHdmiControlService.setPowerManager(mPowerManager);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mNativeWrapper.setPhysicalAddress(0x0000);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(DEVICE_INFO_AVR);
    }

    // Routing control succeeds against the device connected directly to the port. Action
    // won't get any <Routing Information> in this case. It times out on <Routing Information>,
    // regards the directly connected one as the new routing path to switch to.
    @Test
    public void testRoutingControl_succeedForDirectlyConnectedDevice() {
        TestInputSelectCallback callback = new TestInputSelectCallback();
        TestActionTimer actionTimer = new TestActionTimer();
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(DEVICE_INFO_AVR);

        RoutingControlAction action = createRoutingControlAction(mHdmiCecLocalDeviceTv, callback);
        action.setActionTimer(actionTimer);
        action.start();
        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_ROUTING_INFORMATION);

        action.handleTimerEvent(actionTimer.getState());
        mTestLooper.dispatchAll();
        HdmiCecMessage setStreamPath = HdmiCecMessageBuilder.buildSetStreamPath(
                ADDR_TV, PHYSICAL_ADDRESS_AVR);
        assertThat(mNativeWrapper.getResultMessages()).contains(setStreamPath);
    }

    // Succeeds by receiving a couple of <Routing Information> commands, followed by
    // <Set Stream Path> going out in the end.
    @Test
    public void testRoutingControl_succeedForDeviceBehindSwitch() {
        TestInputSelectCallback callback = new TestInputSelectCallback();
        TestActionTimer actionTimer = new TestActionTimer();
        mHdmiControlService.getHdmiCecNetwork().addCecDevice(DEVICE_INFO_PLAYER);
        RoutingControlAction action = createRoutingControlAction(mHdmiCecLocalDeviceTv, callback);
        action.setActionTimer(actionTimer);
        action.start();

        assertThat(actionTimer.getState()).isEqualTo(STATE_WAIT_FOR_ROUTING_INFORMATION);

        action.processCommand(ROUTING_INFORMATION_TUNER);
        action.processCommand(ROUTING_INFORMATION_PLAYER);

        action.handleTimerEvent(actionTimer.getState());
        mTestLooper.dispatchAll();
        HdmiCecMessage setStreamPath = HdmiCecMessageBuilder.buildSetStreamPath(
                ADDR_TV, PHYSICAL_ADDRESS_PLAYER);
        assertThat(mNativeWrapper.getResultMessages()).contains(setStreamPath);
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

    private static class TestInputSelectCallback extends IHdmiControlCallback.Stub {
        private final List<Integer> mCallbackResult = new ArrayList<Integer>();

        @Override
        public void onComplete(int result) {
            mCallbackResult.add(result);
        }

        private int getResult() {
            assert (mCallbackResult.size() == 1);
            return mCallbackResult.get(0);
        }
    }
}
