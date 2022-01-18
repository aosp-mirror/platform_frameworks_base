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

import static android.hardware.hdmi.DeviceFeatures.FEATURE_SUPPORTED;
import static android.hardware.hdmi.DeviceFeatures.FEATURE_SUPPORT_UNKNOWN;
import static android.hardware.hdmi.HdmiControlManager.HDMI_CEC_VERSION_2_0;
import static android.hardware.hdmi.HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM;

import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.hdmi.DeviceFeatures;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.IHdmiControlCallback;
import android.os.Looper;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.SystemService;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class GiveFeaturesActionTest {
    private HdmiControlService mHdmiControlServiceSpy;
    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDevicePlayback mPlaybackDevice;
    private FakeNativeWrapper mNativeWrapper;
    private FakePowerManagerWrapper mPowerManager;
    private Looper mLooper;
    private Context mContextSpy;
    private TestLooper mTestLooper = new TestLooper();
    private int mPhysicalAddress = 0x1100;
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private int mPlaybackLogicalAddress;

    private TestCallback mTestCallback;
    private GiveFeaturesAction mAction;

    /**
     * Setup: Local Playback device queries the features of a connected TV.
     */
    @Before
    public void setUp() throws RemoteException {
        mContextSpy = spy(new ContextWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext()));

        mHdmiControlServiceSpy = spy(new HdmiControlService(mContextSpy, Collections.emptyList()));
        doNothing().when(mHdmiControlServiceSpy)
                .writeStringSystemProperty(anyString(), anyString());

        mLooper = mTestLooper.getLooper();
        mHdmiControlServiceSpy.setIoLooper(mLooper);
        mHdmiControlServiceSpy.setHdmiCecConfig(new FakeHdmiCecConfig(mContextSpy));

        mNativeWrapper = new FakeNativeWrapper();
        mNativeWrapper.setPhysicalAddress(mPhysicalAddress);

        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlServiceSpy, mNativeWrapper, mHdmiControlServiceSpy.getAtomWriter());
        mHdmiControlServiceSpy.setCecController(mHdmiCecController);
        mHdmiControlServiceSpy.setHdmiMhlController(
                HdmiMhlControllerStub.create(mHdmiControlServiceSpy));
        mHdmiControlServiceSpy.initService();
        mPowerManager = new FakePowerManagerWrapper(mContextSpy);
        mHdmiControlServiceSpy.setPowerManager(mPowerManager);

        mPlaybackDevice = new HdmiCecLocalDevicePlayback(mHdmiControlServiceSpy);
        mPlaybackDevice.init();
        mLocalDevices.add(mPlaybackDevice);

        mHdmiControlServiceSpy.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mHdmiControlServiceSpy.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
        mTestLooper.dispatchAll();

        synchronized (mPlaybackDevice.mLock) {
            mPlaybackLogicalAddress = mPlaybackDevice.getDeviceInfo().getLogicalAddress();
        }

        // Setup specific to these tests
        mNativeWrapper.onCecMessage(HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                Constants.ADDR_TV, 0x0000, HdmiDeviceInfo.DEVICE_TV));
        mTestLooper.dispatchAll();

        mTestCallback = new TestCallback();
        mAction = new GiveFeaturesAction(mPlaybackDevice, Constants.ADDR_TV, mTestCallback);
    }

    @Test
    public void sendsGiveFeaturesMessage() {
        mPlaybackDevice.addAndStartAction(mAction);
        mTestLooper.dispatchAll();

        HdmiCecMessage giveFeatures = HdmiCecMessageBuilder.buildGiveFeatures(
                mPlaybackLogicalAddress, Constants.ADDR_TV);
        assertThat(mNativeWrapper.getResultMessages()).contains(giveFeatures);
    }

    @Test
    public void noMatchingReportFeaturesReceived_actionFailsAndNetworkIsNotUpdated() {
        mPlaybackDevice.addAndStartAction(mAction);
        mTestLooper.dispatchAll();

        // Wrong source
        mNativeWrapper.onCecMessage(ReportFeaturesMessage.build(
                Constants.ADDR_AUDIO_SYSTEM, HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Arrays.asList(DEVICE_AUDIO_SYSTEM), Constants.RC_PROFILE_SOURCE,
                Collections.emptyList(), DeviceFeatures.NO_FEATURES_SUPPORTED));
        mTestLooper.dispatchAll();

        mTestLooper.moveTimeForward(HdmiConfig.TIMEOUT_MS);
        mTestLooper.dispatchAll();

        @DeviceFeatures.FeatureSupportStatus int avcSupport =
                mHdmiControlServiceSpy.getHdmiCecNetwork().getCecDeviceInfo(Constants.ADDR_TV)
                        .getDeviceFeatures().getSetAudioVolumeLevelSupport();

        assertThat(avcSupport).isEqualTo(FEATURE_SUPPORT_UNKNOWN);
        assertThat(mTestCallback.getResult()).isEqualTo(
                HdmiControlManager.RESULT_COMMUNICATION_FAILED);
    }

    @Test
    public void matchingReportFeaturesReceived_actionSucceedsAndNetworkIsUpdated() {
        mPlaybackDevice.addAndStartAction(mAction);
        mTestLooper.dispatchAll();

        mNativeWrapper.onCecMessage(
                ReportFeaturesMessage.build(
                        Constants.ADDR_TV, HDMI_CEC_VERSION_2_0, Collections.emptyList(),
                        Constants.RC_PROFILE_TV, Lists.newArrayList(Constants.RC_PROFILE_TV_NONE),
                        DeviceFeatures.NO_FEATURES_SUPPORTED.toBuilder()
                                .setSetAudioVolumeLevelSupport(FEATURE_SUPPORTED)
                                .build()
                )
        );
        mTestLooper.dispatchAll();

        @DeviceFeatures.FeatureSupportStatus int avcSupport =
                mHdmiControlServiceSpy.getHdmiCecNetwork().getCecDeviceInfo(Constants.ADDR_TV)
                        .getDeviceFeatures().getSetAudioVolumeLevelSupport();

        assertThat(avcSupport).isEqualTo(FEATURE_SUPPORTED);
        assertThat(mTestCallback.getResult()).isEqualTo(HdmiControlManager.RESULT_SUCCESS);
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
}
