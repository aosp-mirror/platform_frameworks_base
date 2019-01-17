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

import static android.hardware.hdmi.HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM;
import static android.hardware.hdmi.HdmiDeviceInfo.DEVICE_PLAYBACK;

import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.hardware.hdmi.HdmiPortInfo;
import android.os.Looper;
import android.os.test.TestLooper;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;

/**
 * Tests for {@link HdmiControlService} class.
 */
@SmallTest
@RunWith(JUnit4.class)
public class HdmiControlServiceTest {

    private class HdmiCecLocalDeviceMyDevice extends HdmiCecLocalDevice {

        private boolean mCanGoToStandby;
        private boolean mIsStandby;
        private boolean mIsDisabled;

        protected HdmiCecLocalDeviceMyDevice(HdmiControlService service, int deviceType) {
            super(service, deviceType);
        }

        @Override
        protected void onAddressAllocated(int logicalAddress, int reason) {}

        @Override
        protected int getPreferredAddress() {
            return 0;
        }

        @Override
        protected void setPreferredAddress(int addr) {}

        @Override
        protected boolean canGoToStandby() {
            return mCanGoToStandby;
        }

        @Override
        protected void disableDevice(
                boolean initiatedByCec, final PendingActionClearedCallback originalCallback) {
            mIsDisabled = true;
            originalCallback.onCleared(this);
        }

        @Override
        protected void onStandby(boolean initiatedByCec, int standbyAction) {
            mIsStandby = true;
        }

        protected boolean isStandby() {
            return mIsStandby;
        }

        protected boolean isDisabled() {
            return mIsDisabled;
        }

        protected void setCanGoToStandby(boolean canGoToStandby) {
            mCanGoToStandby = canGoToStandby;
        }
    }

    private static final String TAG = "HdmiControlServiceTest";
    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDeviceMyDevice mMyAudioSystemDevice;
    private HdmiCecLocalDeviceMyDevice mMyPlaybackDevice;
    private FakeNativeWrapper mNativeWrapper;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private boolean mStandbyMessageReceived;
    private HdmiPortInfo[] mHdmiPortInfo;

    @Before
    public void SetUp() {
        mHdmiControlService =
                new HdmiControlService(InstrumentationRegistry.getTargetContext()) {
                    @Override
                    boolean isStandbyMessageReceived() {
                        return mStandbyMessageReceived;
                    }
                };
        mMyLooper = mTestLooper.getLooper();

        mMyAudioSystemDevice =
                new HdmiCecLocalDeviceMyDevice(mHdmiControlService, DEVICE_AUDIO_SYSTEM);
        mMyPlaybackDevice = new HdmiCecLocalDeviceMyDevice(mHdmiControlService, DEVICE_PLAYBACK);
        mMyAudioSystemDevice.init();
        mMyPlaybackDevice.init();

        mHdmiControlService.setIoLooper(mMyLooper);

        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController =
                HdmiCecController.createWithNativeWrapper(mHdmiControlService, mNativeWrapper);
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.setMessageValidator(new HdmiCecMessageValidator(mHdmiControlService));

        mLocalDevices.add(mMyAudioSystemDevice);
        mLocalDevices.add(mMyPlaybackDevice);
        mHdmiPortInfo = new HdmiPortInfo[4];
        mHdmiPortInfo[0] =
            new HdmiPortInfo(1, HdmiPortInfo.PORT_INPUT, 0x2100, true, false, false);
        mHdmiPortInfo[1] =
            new HdmiPortInfo(2, HdmiPortInfo.PORT_INPUT, 0x2200, true, false, false);
        mHdmiPortInfo[2] =
            new HdmiPortInfo(3, HdmiPortInfo.PORT_INPUT, 0x2000, true, false, false);
        mHdmiPortInfo[3] =
            new HdmiPortInfo(4, HdmiPortInfo.PORT_INPUT, 0x3000, true, false, false);
        mNativeWrapper.setPortInfo(mHdmiPortInfo);
        mHdmiControlService.initPortInfo();
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);

        mTestLooper.dispatchAll();
    }

    @Test
    public void onStandby_notByCec_cannotGoToStandby() {
        mStandbyMessageReceived = false;
        mMyPlaybackDevice.setCanGoToStandby(false);

        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        assertTrue(mMyPlaybackDevice.isStandby());
        assertTrue(mMyAudioSystemDevice.isStandby());
        assertFalse(mMyPlaybackDevice.isDisabled());
        assertFalse(mMyAudioSystemDevice.isDisabled());
    }

    @Test
    public void onStandby_byCec() {
        mStandbyMessageReceived = true;

        mHdmiControlService.onStandby(HdmiControlService.STANDBY_SCREEN_OFF);
        assertTrue(mMyPlaybackDevice.isStandby());
        assertTrue(mMyAudioSystemDevice.isStandby());
        assertTrue(mMyPlaybackDevice.isDisabled());
        assertTrue(mMyAudioSystemDevice.isDisabled());
    }

    @Test
    public void pathToPort_pathExists_weAreNonTv() {
        mNativeWrapper.setPhysicalAddress(0x2000);
        mHdmiControlService.initPortInfo();
        assertThat(mHdmiControlService.pathToPortId(0x2120)).isEqualTo(1);
        assertThat(mHdmiControlService.pathToPortId(0x2234)).isEqualTo(2);
    }

    @Test
    public void pathToPort_pathExists_weAreTv() {
        mNativeWrapper.setPhysicalAddress(0x0000);
        mHdmiControlService.initPortInfo();
        assertThat(mHdmiControlService.pathToPortId(0x2120)).isEqualTo(3);
        assertThat(mHdmiControlService.pathToPortId(0x3234)).isEqualTo(4);
    }

    @Test
    public void pathToPort_pathInvalid() {
        mNativeWrapper.setPhysicalAddress(0x2000);
        mHdmiControlService.initPortInfo();
        assertThat(mHdmiControlService.pathToPortId(0x1000)).isEqualTo(Constants.INVALID_PORT_ID);
    }
}
