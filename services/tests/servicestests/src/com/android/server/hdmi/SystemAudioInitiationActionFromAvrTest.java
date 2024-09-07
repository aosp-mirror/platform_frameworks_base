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

import static com.android.server.hdmi.HdmiConfig.TIMEOUT_MS;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.Intent;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.os.Looper;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Collections;

/** Tests for {@link SystemAudioInitiationActionFromAvr} */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class SystemAudioInitiationActionFromAvrTest {

    private HdmiCecLocalDeviceAudioSystem mHdmiCecLocalDeviceAudioSystem;
    private FakePowerManagerWrapper mPowerManager;
    private TestLooper mTestLooper = new TestLooper();

    private boolean mShouldDispatchActiveSource;
    private boolean mTvSystemAudioModeSupport;
    private int mTryCountBeforeSucceed;
    private HdmiDeviceInfo mDeviceInfoForTests;

    private int mMsgRequestActiveSourceCount;
    private int mMsgSetSystemAudioModeCount;
    private int mQueryTvSystemAudioModeSupportCount;
    private boolean mArcEnabled;
    private boolean mIsPlaybackDevice;
    private boolean mBroadcastActiveSource;
    private boolean mStandbyMessageReceived;

    @Before
    public void SetUp() {
        mDeviceInfoForTests = HdmiDeviceInfo.hardwarePort(1001, 1234);

        Context context = InstrumentationRegistry.getTargetContext();

        FakeAudioFramework audioFramework = new FakeAudioFramework();

        HdmiControlService hdmiControlService = new HdmiControlService(context,
                Collections.emptyList(), audioFramework.getAudioManager(),
                audioFramework.getAudioDeviceVolumeManager()) {
                    @Override
                    void sendCecCommand(
                            HdmiCecMessage command, @Nullable SendMessageCallback callback) {
                        switch (command.getOpcode()) {
                            case Constants.MESSAGE_REQUEST_ACTIVE_SOURCE:
                                mMsgRequestActiveSourceCount++;
                                if (mTryCountBeforeSucceed >= mMsgRequestActiveSourceCount
                                        && callback != null) {
                                    callback.onSendCompleted(SendMessageResult.NACK);
                                    break;
                                }
                                if (mShouldDispatchActiveSource) {
                                    mHdmiCecLocalDeviceAudioSystem.dispatchMessage(
                                            HdmiCecMessageBuilder.buildActiveSource(
                                                    Constants.ADDR_TV, 1002));
                                }
                                break;
                            case Constants.MESSAGE_SET_SYSTEM_AUDIO_MODE:
                                mMsgSetSystemAudioModeCount++;
                                if (mTryCountBeforeSucceed >= mMsgSetSystemAudioModeCount
                                        && callback != null) {
                                    callback.onSendCompleted(SendMessageResult.NACK);
                                }
                                break;
                            case Constants.MESSAGE_INITIATE_ARC:
                                break;
                        }
                    }

                    @Override
                    boolean isPowerStandby() {
                        return false;
                    }

                    @Override
                    boolean isAddressAllocated() {
                        return true;
                    }

                    @Override
                    protected void writeStringSystemProperty(String key, String value) {
                    }

                    @Override
                    int getPhysicalAddress() {
                        return 0;
                    }

                    @Override
                    boolean isPlaybackDevice() {
                        return mIsPlaybackDevice;
                    }

                    @Override
                    public void setAndBroadcastActiveSourceFromOneDeviceType(
                            int sourceAddress, int physicalAddress, String caller) {
                        mBroadcastActiveSource = true;
                    }

                    @Override
                    int pathToPortId(int path) {
                        return -1;
                    }

                    @Override
                    protected boolean isStandbyMessageReceived() {
                        return mStandbyMessageReceived;
                    }

                    @Override
                    protected void sendBroadcastAsUser(@RequiresPermission Intent intent) {
                        // do nothing
                    }
                };

        Looper looper = mTestLooper.getLooper();
        hdmiControlService.setIoLooper(looper);
        hdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(context));
        hdmiControlService.setDeviceConfig(new FakeDeviceConfigWrapper());
        HdmiCecController.NativeWrapper nativeWrapper = new FakeNativeWrapper();
        HdmiCecController hdmiCecController = HdmiCecController.createWithNativeWrapper(
                hdmiControlService, nativeWrapper, hdmiControlService.getAtomWriter());
        hdmiControlService.setCecController(hdmiCecController);
        hdmiControlService.initService();
        mPowerManager = new FakePowerManagerWrapper(context);
        hdmiControlService.setPowerManager(mPowerManager);
        mHdmiCecLocalDeviceAudioSystem =
                new HdmiCecLocalDeviceAudioSystem(hdmiControlService) {
                    @Override
                    void queryTvSystemAudioModeSupport(
                            TvSystemAudioModeSupportedCallback callback) {
                        mQueryTvSystemAudioModeSupportCount++;
                        if (callback != null) {
                            callback.onResult(mTvSystemAudioModeSupport);
                        }
                    }

                    @Override
                    HdmiDeviceInfo getDeviceInfo() {
                        return mDeviceInfoForTests;
                    }

                    @Override
                    void setArcStatus(boolean enabled) {
                        mArcEnabled = enabled;
                    }
                };
        mHdmiCecLocalDeviceAudioSystem.init();
        mHdmiCecLocalDeviceAudioSystem.setDeviceInfo(mDeviceInfoForTests);
    }

    @Test
    public void testNoActiveSourceMessageReceived() {
        resetTestVariables();
        mShouldDispatchActiveSource = false;

        assertThat(mHdmiCecLocalDeviceAudioSystem.getActiveSource().physicalAddress)
                .isEqualTo(Constants.INVALID_PHYSICAL_ADDRESS);

        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(
                new SystemAudioInitiationActionFromAvr(mHdmiCecLocalDeviceAudioSystem));
        mTestLooper.dispatchAll();

        assertThat(mMsgRequestActiveSourceCount).isEqualTo(1);
        assertThat(mMsgSetSystemAudioModeCount).isEqualTo(0);
        assertThat(mQueryTvSystemAudioModeSupportCount).isEqualTo(0);
        assertFalse(mHdmiCecLocalDeviceAudioSystem.isSystemAudioActivated());

        assertThat(mHdmiCecLocalDeviceAudioSystem.getActiveSource().physicalAddress)
                .isEqualTo(Constants.INVALID_PHYSICAL_ADDRESS);
    }

    @Test
    public void testTvNotSupport() {
        resetTestVariables();
        mShouldDispatchActiveSource = true;
        mTvSystemAudioModeSupport = false;

        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(
                new SystemAudioInitiationActionFromAvr(mHdmiCecLocalDeviceAudioSystem));
        mTestLooper.dispatchAll();

        assertThat(mMsgRequestActiveSourceCount).isEqualTo(1);
        assertThat(mMsgSetSystemAudioModeCount).isEqualTo(0);
        assertThat(mQueryTvSystemAudioModeSupportCount).isEqualTo(1);
        assertFalse(mHdmiCecLocalDeviceAudioSystem.isSystemAudioActivated());
    }

    @Test
    @Ignore("b/120845532")
    public void testTvSupport() {
        resetTestVariables();
        mShouldDispatchActiveSource = true;
        mTvSystemAudioModeSupport = true;

        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(
                new SystemAudioInitiationActionFromAvr(mHdmiCecLocalDeviceAudioSystem));
        mTestLooper.dispatchAll();

        assertThat(mMsgRequestActiveSourceCount).isEqualTo(1);
        assertThat(mMsgSetSystemAudioModeCount).isEqualTo(1);
        assertThat(mQueryTvSystemAudioModeSupportCount).isEqualTo(1);
        assertTrue(mHdmiCecLocalDeviceAudioSystem.isSystemAudioActivated());

        assertThat(mHdmiCecLocalDeviceAudioSystem.getActiveSource().physicalAddress)
            .isEqualTo(1002);
    }

    @Test
    public void testKnownActiveSource() {
        resetTestVariables();
        mTvSystemAudioModeSupport = true;
        mHdmiCecLocalDeviceAudioSystem.getActiveSource().physicalAddress = 1001;

        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(
                new SystemAudioInitiationActionFromAvr(mHdmiCecLocalDeviceAudioSystem));
        mTestLooper.dispatchAll();

        assertThat(mMsgRequestActiveSourceCount).isEqualTo(0);
        assertThat(mMsgSetSystemAudioModeCount).isEqualTo(1);
        assertThat(mQueryTvSystemAudioModeSupportCount).isEqualTo(1);
        assertTrue(mHdmiCecLocalDeviceAudioSystem.isSystemAudioActivated());
    }

    @Test
    public void testRetry() {
        resetTestVariables();
        mTvSystemAudioModeSupport = true;
        mShouldDispatchActiveSource = true;
        mTryCountBeforeSucceed = 3;
        assertThat(mTryCountBeforeSucceed)
                .isAtMost(SystemAudioInitiationActionFromAvr.MAX_RETRY_COUNT);
        assertThat(mHdmiCecLocalDeviceAudioSystem.getActiveSource().physicalAddress)
                .isEqualTo(Constants.INVALID_PHYSICAL_ADDRESS);

        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(
                new SystemAudioInitiationActionFromAvr(mHdmiCecLocalDeviceAudioSystem));
        mTestLooper.dispatchAll();

        assertThat(mMsgRequestActiveSourceCount).isEqualTo(4);
        assertThat(mMsgSetSystemAudioModeCount).isEqualTo(4);
        assertThat(mQueryTvSystemAudioModeSupportCount).isEqualTo(1);
        assertTrue(mHdmiCecLocalDeviceAudioSystem.isSystemAudioActivated());
    }

    @Ignore("b/120845532")
    @Test
    public void testIsPlaybackDevice_cannotReceiveActiveSource() {
        resetTestVariables();
        mIsPlaybackDevice = true;
        assertThat(mHdmiCecLocalDeviceAudioSystem.getActiveSource().physicalAddress)
            .isEqualTo(Constants.INVALID_PHYSICAL_ADDRESS);

        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(
                new SystemAudioInitiationActionFromAvr(mHdmiCecLocalDeviceAudioSystem));
        mTestLooper.dispatchAll();

        assertThat(mMsgRequestActiveSourceCount).isEqualTo(1);
        assertThat(mBroadcastActiveSource).isTrue();
        assertThat(mQueryTvSystemAudioModeSupportCount).isEqualTo(1);
        assertThat(mMsgSetSystemAudioModeCount).isEqualTo(1);
        assertThat(mHdmiCecLocalDeviceAudioSystem.isSystemAudioActivated()).isTrue();
    }

    @Test
    public void onActionStarted_deviceGoesToSleep_noActiveSourceAfterTimeout() {
        resetTestVariables();

        mStandbyMessageReceived = true;
        mHdmiCecLocalDeviceAudioSystem.addAndStartAction(
                new SystemAudioInitiationActionFromAvr(
                mHdmiCecLocalDeviceAudioSystem));
        mTestLooper.dispatchAll();

        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();

        assertThat(mBroadcastActiveSource).isFalse();
    }

    private void resetTestVariables() {
        mMsgRequestActiveSourceCount = 0;
        mMsgSetSystemAudioModeCount = 0;
        mQueryTvSystemAudioModeSupportCount = 0;
        mTryCountBeforeSucceed = 0;
        mIsPlaybackDevice = false;
        mBroadcastActiveSource = false;
        mHdmiCecLocalDeviceAudioSystem.getActiveSource().physicalAddress =
                Constants.INVALID_PHYSICAL_ADDRESS;
        mStandbyMessageReceived = false;
    }
}
