/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static junit.framework.Assert.assertEquals;

import android.content.Context;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.os.Looper;
import android.os.test.TestLooper;
import android.provider.Settings;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tests for {@link HdmiControlServiceBinderAPITest} class.
 */
@SmallTest
@RunWith(JUnit4.class)
public class HdmiControlServiceBinderAPITest {

    private Context mContext;

    private class HdmiCecLocalDeviceMyDevice extends HdmiCecLocalDevice {

        private boolean mCanGoToStandby;
        private boolean mIsStandby;
        private boolean mIsDisabled;

        protected HdmiCecLocalDeviceMyDevice(HdmiControlService service, int deviceType) {
            super(service, deviceType);
        }

        @Override
        protected void onAddressAllocated(int logicalAddress, int reason) {
        }

        @Override
        protected int getPreferredAddress() {
            return 0;
        }

        @Override
        protected void setPreferredAddress(int addr) {
        }

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

        @Override
        protected int getRcProfile() {
            return 0;
        }

        @Override
        protected List<Integer> getRcFeatures() {
            return Collections.emptyList();
        }

        @Override
        protected List<Integer> getDeviceFeatures() {
            return Collections.emptyList();
        }
    }

    private static final String TAG = "HdmiControlServiceBinderAPITest";
    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDevicePlayback mPlaybackDevice;
    private FakeNativeWrapper mNativeWrapper;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private HdmiPortInfo[] mHdmiPortInfo;
    private int mResult;
    private int mPowerStatus;

    @Before
    public void SetUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        // Some tests expect no logical addresses being allocated at the beginning of the test.
        setHdmiControlEnabled(false);

        mHdmiControlService =
            new HdmiControlService(mContext) {
                @Override
                void sendCecCommand(HdmiCecMessage command) {
                    switch (command.getOpcode()) {
                        case Constants.MESSAGE_GIVE_DEVICE_POWER_STATUS:
                            HdmiCecMessage message =
                                HdmiCecMessageBuilder.buildReportPowerStatus(
                                    Constants.ADDR_TV,
                                    Constants.ADDR_PLAYBACK_1,
                                    HdmiControlManager.POWER_STATUS_ON);
                            handleCecCommand(message);
                            break;
                        default:
                            return;
                    }
                }

                @Override
                boolean isPowerStandby() {
                    return mPowerStatus == HdmiControlManager.POWER_STATUS_STANDBY;
                }
            };
        mMyLooper = mTestLooper.getLooper();

        mPlaybackDevice = new HdmiCecLocalDevicePlayback(mHdmiControlService) {
            @Override
            protected void wakeUpIfActiveSource() {}

            @Override
            protected void setPreferredAddress(int addr) {}

            @Override
            protected int getPreferredAddress() {
                return Constants.ADDR_PLAYBACK_1;
            }
        };
        mPlaybackDevice.init();

        mHdmiControlService.setIoLooper(mMyLooper);

        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.setMessageValidator(new HdmiCecMessageValidator(mHdmiControlService));

        mLocalDevices.add(mPlaybackDevice);
        mHdmiPortInfo = new HdmiPortInfo[1];
        mHdmiPortInfo[0] =
            new HdmiPortInfo(1, HdmiPortInfo.PORT_INPUT, 0x2100, true, false, false);
        mNativeWrapper.setPortInfo(mHdmiPortInfo);
        mHdmiControlService.initService();
        mResult = -1;
        mPowerStatus = HdmiControlManager.POWER_STATUS_ON;

        mTestLooper.dispatchAll();
    }

    @Test
    public void oneTouchPlay_addressNotAllocated() {
        assertThat(mHdmiControlService.isAddressAllocated()).isFalse();
        mHdmiControlService.oneTouchPlay(new IHdmiControlCallback.Stub() {
            @Override
            public void onComplete(int result) {
                mResult = result;
            }
        });
        assertEquals(mResult, -1);
        assertThat(mPlaybackDevice.isActiveSource()).isFalse();

        setHdmiControlEnabled(true);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();
        assertThat(mHdmiControlService.isAddressAllocated()).isTrue();
        assertEquals(mResult, HdmiControlManager.RESULT_SUCCESS);
        assertThat(mPlaybackDevice.isActiveSource()).isTrue();
    }

    @Test
    public void oneTouchPlay_addressAllocated() {
        setHdmiControlEnabled(true);

        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();
        assertThat(mHdmiControlService.isAddressAllocated()).isTrue();
        mHdmiControlService.oneTouchPlay(new IHdmiControlCallback.Stub() {
            @Override
            public void onComplete(int result) {
                mResult = result;
            }
        });
        assertEquals(mResult, HdmiControlManager.RESULT_SUCCESS);
        assertThat(mPlaybackDevice.isActiveSource()).isTrue();
    }

    private void setHdmiControlEnabled(boolean enabled) {
        int value = enabled ? 1 : 0;
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.HDMI_CONTROL_ENABLED,
                value);
    }
}
