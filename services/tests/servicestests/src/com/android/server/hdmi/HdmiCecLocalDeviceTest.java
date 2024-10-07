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

import static android.hardware.hdmi.HdmiDeviceInfo.DEVICE_TV;

import static com.android.server.SystemService.PHASE_SYSTEM_SERVICES_READY;
import static com.android.server.hdmi.Constants.ADDR_AUDIO_SYSTEM;
import static com.android.server.hdmi.Constants.ADDR_BROADCAST;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_1;
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.Constants.MESSAGE_DEVICE_VENDOR_ID;
import static com.android.server.hdmi.Constants.MESSAGE_REPORT_PHYSICAL_ADDRESS;
import static com.android.server.hdmi.HdmiControlService.INITIATED_BY_ENABLE_CEC;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.Intent;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiPortInfo;
import android.hardware.tv.cec.V1_0.Result;
import android.media.AudioManager;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.google.common.testing.EqualsTester;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
/** Tests for {@link HdmiCecLocalDevice} class. */
public class HdmiCecLocalDeviceTest {

    private FakeNativeWrapper mNativeWrapper;

    private static int SendCecCommandFactory(int srcAddress, int dstAddress, byte[] body) {
        switch (body[0] & 0xFF) {
                /** {@link Constants#MESSAGE_GIVE_PHYSICAL_ADDRESS} */
            case MESSAGE_REPORT_PHYSICAL_ADDRESS:
            case MESSAGE_DEVICE_VENDOR_ID:
                return srcAddress == mSrcAddr
                                && dstAddress == mDesAddr
                                && Arrays.equals(Arrays.copyOfRange(body, 1, body.length), param)
                        ? 0
                        : 1;
            default:
                return 1;
        }
    }

    private class MyHdmiCecLocalDevice extends HdmiCecLocalDevice {

        protected MyHdmiCecLocalDevice(HdmiControlService service, int deviceType) {
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
        protected int getRcProfile() {
            return 0;
        }

        @Override
        protected List<Integer> getRcFeatures() {
            return Collections.emptyList();
        }
    }

    private MyHdmiCecLocalDevice mHdmiLocalDevice;
    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private ArrayList<HdmiCecLocalDevice> mLocalDevices = new ArrayList<>();
    private TestLooper mTestLooper = new TestLooper();
    private static int mDesAddr = -1;
    private static int mSrcAddr = -1;
    private static int mPhysicalAddr = 2;
    private int callbackResult;
    private HdmiCecMessageValidator mMessageValidator;
    private static byte[] param;
    private boolean mStandbyMessageReceived;
    private boolean mWakeupMessageReceived;
    private boolean isControlEnabled;
    private int mPowerStatus;

    private AudioManagerWrapper mAudioManager;

    @Before
    public void SetUp() {
        Context context = InstrumentationRegistry.getTargetContext();

        FakeAudioFramework audioFramework = new FakeAudioFramework();
        mAudioManager = spy(audioFramework.getAudioManager());
        mHdmiControlService =
                new HdmiControlService(context, Collections.emptyList(),
                        mAudioManager, audioFramework.getAudioDeviceVolumeManager()) {
                    @Override
                    boolean isCecControlEnabled() {
                        return isControlEnabled;
                    }

                    @Override
                    boolean isPowerOnOrTransient() {
                        return mPowerStatus == HdmiControlManager.POWER_STATUS_ON
                                || mPowerStatus == HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON;
                    }

                    @Override
                    boolean isPowerStandbyOrTransient() {
                        return mPowerStatus == HdmiControlManager.POWER_STATUS_STANDBY
                                || mPowerStatus
                                == HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY;
                    }

                    @Override
                    protected void writeStringSystemProperty(String key, String value) {
                    }

                    @Override
                    void standby() {
                        mStandbyMessageReceived = true;
                    }

                    @Override
                    void wakeUp() {
                        mWakeupMessageReceived = true;
                    }

                    @Override
                    protected void sendBroadcastAsUser(@RequiresPermission Intent intent) {
                        // do nothing
                    }
                };
        mHdmiControlService.setIoLooper(mTestLooper.getLooper());
        mHdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(context));
        mHdmiControlService.setDeviceConfig(new FakeDeviceConfigWrapper());
        mNativeWrapper = new FakeNativeWrapper();
        mNativeWrapper.setPhysicalAddress(0x2000);
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiLocalDevice = new MyHdmiCecLocalDevice(mHdmiControlService, DEVICE_TV);

        mLocalDevices.add(mHdmiLocalDevice);
        HdmiPortInfo[] hdmiPortInfos = new HdmiPortInfo[1];
        hdmiPortInfos[0] =
                new HdmiPortInfo.Builder(1, HdmiPortInfo.PORT_OUTPUT, 0x0000)
                        .setCecSupported(true)
                        .setMhlSupported(false)
                        .setArcSupported(false)
                        .build();
        mNativeWrapper.setPortInfo(hdmiPortInfos);
        mNativeWrapper.setPortConnectionStatus(1, true);
        mHdmiControlService.initService();
        mHdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);
        mHdmiControlService.allocateLogicalAddress(mLocalDevices, INITIATED_BY_ENABLE_CEC);
        mTestLooper.dispatchAll();
        mNativeWrapper.clearResultMessages();
    }

    @Test
    public void testEqualsActiveSource() {
        int logicalAddress = 0;
        int physicalAddress = 0x0000;
        new EqualsTester()
                .addEqualityGroup(
                        new HdmiCecLocalDevice.ActiveSource(logicalAddress, physicalAddress),
                        new HdmiCecLocalDevice.ActiveSource(logicalAddress, physicalAddress))
                .addEqualityGroup(
                        new HdmiCecLocalDevice.ActiveSource(logicalAddress, physicalAddress + 1))
                .addEqualityGroup(
                        new HdmiCecLocalDevice.ActiveSource(logicalAddress + 1, physicalAddress))
                .addEqualityGroup(
                        new HdmiCecLocalDevice.ActiveSource(
                                logicalAddress + 1, physicalAddress + 1))
                .testEquals();
    }

    @Test
    public void dispatchMessage_logicalAddressDoesNotMatch() {
        HdmiCecMessage msg =
                HdmiCecMessage.build(
                        ADDR_TV,
                        ADDR_PLAYBACK_1,
                        Constants.MESSAGE_CEC_VERSION,
                        HdmiCecMessage.EMPTY_PARAM);
        @Constants.HandleMessageResult int handleResult = mHdmiLocalDevice.dispatchMessage(msg);
        assertEquals(Constants.NOT_HANDLED, handleResult);
    }

    @Test
    public void handleGivePhysicalAddress_success() {
        mNativeWrapper.setPhysicalAddress(0x0);
        mHdmiControlService.onHotplug(0x0, true);
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(ADDR_TV, 0, DEVICE_TV);
        @Constants.HandleMessageResult
        int handleResult =
                mHdmiLocalDevice.handleGivePhysicalAddress(
                        HdmiCecMessageBuilder.buildGivePhysicalAddress(
                                ADDR_PLAYBACK_1, ADDR_TV));
        mTestLooper.dispatchAll();
        assertEquals(Constants.HANDLED, handleResult);
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
    }

    @Test
    public void handleGivePhysicalAddress_failure() {
        mNativeWrapper.setPhysicalAddress(Constants.INVALID_PHYSICAL_ADDRESS);
        mHdmiControlService.onHotplug(Constants.INVALID_PHYSICAL_ADDRESS, true);
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildFeatureAbortCommand(
                        ADDR_TV,
                        ADDR_PLAYBACK_1,
                        Constants.MESSAGE_GIVE_PHYSICAL_ADDRESS,
                        Constants.ABORT_UNABLE_TO_DETERMINE);
        @Constants.HandleMessageResult
        int handleResult =
                mHdmiLocalDevice.handleGivePhysicalAddress(
                        HdmiCecMessageBuilder.buildGivePhysicalAddress(
                                ADDR_PLAYBACK_1, ADDR_TV));
        mTestLooper.dispatchAll();
        assertEquals(Constants.HANDLED, handleResult);
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
    }

    @Test
    public void handleGiveDeviceVendorId_success() {
        /** Set vendor id to 0 */
        mNativeWrapper.setVendorId(0);
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildDeviceVendorIdCommand(ADDR_TV, 0);
        @Constants.HandleMessageResult
        int handleResult =
                mHdmiLocalDevice.handleGiveDeviceVendorId(
                        HdmiCecMessageBuilder.buildGiveDeviceVendorIdCommand(
                                ADDR_PLAYBACK_1, ADDR_TV));
        mTestLooper.dispatchAll();
        assertEquals(Constants.HANDLED, handleResult);
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
    }

    @Test
    public void handleGiveDeviceVendorId_failure() {
        mNativeWrapper.setVendorId(Result.FAILURE_UNKNOWN);
        HdmiCecMessage expectedMessage =
                HdmiCecMessageBuilder.buildFeatureAbortCommand(
                        ADDR_TV,
                        ADDR_PLAYBACK_1,
                        Constants.MESSAGE_GIVE_DEVICE_VENDOR_ID,
                        Constants.ABORT_UNABLE_TO_DETERMINE);
        @Constants.HandleMessageResult
        int handleResult =
                mHdmiLocalDevice.handleGiveDeviceVendorId(
                        HdmiCecMessageBuilder.buildGiveDeviceVendorIdCommand(
                                ADDR_PLAYBACK_1, ADDR_TV));
        mTestLooper.dispatchAll();
        assertEquals(Constants.HANDLED, handleResult);
        assertThat(mNativeWrapper.getOnlyResultMessage()).isEqualTo(expectedMessage);
    }

    @Test
    public void handleStandby_isPowerOn() {
        mPowerStatus = HdmiControlManager.POWER_STATUS_ON;
        isControlEnabled = true;
        assertFalse(mStandbyMessageReceived);
        mHdmiLocalDevice.handleStandby(
                HdmiCecMessageBuilder.buildStandby(ADDR_TV, ADDR_AUDIO_SYSTEM));
        assertTrue(mStandbyMessageReceived);
    }

    @Test
    public void handleUserControlPressed_volumeUp() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        @Constants.HandleMessageResult int result = mHdmiLocalDevice.handleUserControlPressed(
                HdmiCecMessageBuilder.buildUserControlPressed(ADDR_PLAYBACK_1, ADDR_TV,
                        HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP));

        assertEquals(Constants.HANDLED, result);
    }

    @Test
    public void handleUserControlPressed_volumeDown() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        @Constants.HandleMessageResult int result = mHdmiLocalDevice.handleUserControlPressed(
                HdmiCecMessageBuilder.buildUserControlPressed(ADDR_PLAYBACK_1, ADDR_TV,
                        HdmiCecKeycode.CEC_KEYCODE_VOLUME_DOWN));

        assertEquals(Constants.HANDLED, result);
    }

    @Test
    public void handleUserControlPressed_volumeMute() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_ENABLED);
        @Constants.HandleMessageResult int result = mHdmiLocalDevice.handleUserControlPressed(
                HdmiCecMessageBuilder.buildUserControlPressed(ADDR_PLAYBACK_1, ADDR_TV,
                        HdmiCecKeycode.CEC_KEYCODE_MUTE));

        assertEquals(Constants.HANDLED, result);
    }

    @Test
    public void handleUserControlPressed_volumeUp_disabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        @Constants.HandleMessageResult int result = mHdmiLocalDevice.handleUserControlPressed(
                HdmiCecMessageBuilder.buildUserControlPressed(ADDR_PLAYBACK_1, ADDR_TV,
                        HdmiCecKeycode.CEC_KEYCODE_VOLUME_UP));

        assertThat(result).isEqualTo(Constants.ABORT_REFUSED);
    }

    @Test
    public void handleUserControlPressed_volumeDown_disabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        @Constants.HandleMessageResult int result = mHdmiLocalDevice.handleUserControlPressed(
                HdmiCecMessageBuilder.buildUserControlPressed(ADDR_PLAYBACK_1, ADDR_TV,
                        HdmiCecKeycode.CEC_KEYCODE_VOLUME_DOWN));

        assertThat(result).isEqualTo(Constants.ABORT_REFUSED);
    }

    @Test
    public void handleUserControlPressed_volumeMute_disabled() {
        mHdmiControlService.setHdmiCecVolumeControlEnabledInternal(
                HdmiControlManager.VOLUME_CONTROL_DISABLED);
        @Constants.HandleMessageResult int result = mHdmiLocalDevice.handleUserControlPressed(
                HdmiCecMessageBuilder.buildUserControlPressed(ADDR_PLAYBACK_1, ADDR_TV,
                        HdmiCecKeycode.CEC_KEYCODE_MUTE));

        assertThat(result).isEqualTo(Constants.ABORT_REFUSED);
    }

    @Test
    public void handleCecVersion_isHandled() {
        @Constants.HandleMessageResult
        int result =
                mHdmiLocalDevice.onMessage(
                        HdmiCecMessageBuilder.buildCecVersion(
                                ADDR_PLAYBACK_1,
                                mHdmiLocalDevice.getDeviceInfo().getLogicalAddress(),
                                HdmiControlManager.HDMI_CEC_VERSION_1_4_B));

        assertEquals(Constants.HANDLED, result);
    }

    @Test
    public void handleUserControlPressed_power_localDeviceInStandby_shouldTurnOn() {
        mPowerStatus = HdmiControlManager.POWER_STATUS_STANDBY;
        @Constants.HandleMessageResult int result = mHdmiLocalDevice.handleUserControlPressed(
                HdmiCecMessageBuilder.buildUserControlPressed(ADDR_TV, ADDR_PLAYBACK_1,
                        HdmiCecKeycode.CEC_KEYCODE_POWER));

        assertEquals(Constants.HANDLED, result);
        assertThat(mWakeupMessageReceived).isTrue();
        assertThat(mStandbyMessageReceived).isFalse();
    }

    @Test
    public void handleUserControlPressed_power_localDeviceOn_shouldNotChangePowerStatus() {
        mPowerStatus = HdmiControlManager.POWER_STATUS_ON;
        @Constants.HandleMessageResult int result = mHdmiLocalDevice.handleUserControlPressed(
                HdmiCecMessageBuilder.buildUserControlPressed(ADDR_TV, ADDR_PLAYBACK_1,
                        HdmiCecKeycode.CEC_KEYCODE_POWER));

        assertEquals(Constants.HANDLED, result);
        assertThat(mWakeupMessageReceived).isFalse();
        assertThat(mStandbyMessageReceived).isFalse();
    }

    @Test
    public void handleUserControlPressed_powerToggleFunction_localDeviceInStandby_shouldTurnOn() {
        mPowerStatus = HdmiControlManager.POWER_STATUS_STANDBY;
        @Constants.HandleMessageResult int result = mHdmiLocalDevice.handleUserControlPressed(
                HdmiCecMessageBuilder.buildUserControlPressed(ADDR_TV, ADDR_PLAYBACK_1,
                        HdmiCecKeycode.CEC_KEYCODE_POWER_TOGGLE_FUNCTION));

        assertEquals(Constants.HANDLED, result);
        assertThat(mWakeupMessageReceived).isTrue();
        assertThat(mStandbyMessageReceived).isFalse();
    }

    @Test
    public void handleUserControlPressed_powerToggleFunction_localDeviceOn_shouldTurnOff() {
        mPowerStatus = HdmiControlManager.POWER_STATUS_ON;
        @Constants.HandleMessageResult int result = mHdmiLocalDevice.handleUserControlPressed(
                HdmiCecMessageBuilder.buildUserControlPressed(ADDR_TV, ADDR_PLAYBACK_1,
                        HdmiCecKeycode.CEC_KEYCODE_POWER_TOGGLE_FUNCTION));

        assertEquals(Constants.HANDLED, result);
        assertThat(mWakeupMessageReceived).isFalse();
        assertThat(mStandbyMessageReceived).isTrue();
    }

    @Test
    public void handleUserControlPressed_powerOnFunction_localDeviceInStandby_shouldTurnOn() {
        mPowerStatus = HdmiControlManager.POWER_STATUS_STANDBY;
        @Constants.HandleMessageResult int result = mHdmiLocalDevice.handleUserControlPressed(
                HdmiCecMessageBuilder.buildUserControlPressed(ADDR_TV, ADDR_PLAYBACK_1,
                        HdmiCecKeycode.CEC_KEYCODE_POWER_ON_FUNCTION));

        assertEquals(Constants.HANDLED, result);
        assertThat(mWakeupMessageReceived).isTrue();
        assertThat(mStandbyMessageReceived).isFalse();
    }

    @Test
    public void handleUserControlPressed_powerOnFunction_localDeviceOn_noPowerStatusChange() {
        mPowerStatus = HdmiControlManager.POWER_STATUS_ON;
        @Constants.HandleMessageResult int result = mHdmiLocalDevice.handleUserControlPressed(
                HdmiCecMessageBuilder.buildUserControlPressed(ADDR_TV, ADDR_PLAYBACK_1,
                        HdmiCecKeycode.CEC_KEYCODE_POWER_ON_FUNCTION));

        assertEquals(Constants.HANDLED, result);
        assertThat(mWakeupMessageReceived).isFalse();
        assertThat(mStandbyMessageReceived).isFalse();
    }

    @Test
    public void handleUserControlPressed_powerOffFunction_localDeviceStandby_noPowerStatusChange() {
        mPowerStatus = HdmiControlManager.POWER_STATUS_STANDBY;
        @Constants.HandleMessageResult int result = mHdmiLocalDevice.handleUserControlPressed(
                HdmiCecMessageBuilder.buildUserControlPressed(ADDR_TV, ADDR_PLAYBACK_1,
                        HdmiCecKeycode.CEC_KEYCODE_POWER_OFF_FUNCTION));

        assertEquals(Constants.HANDLED, result);
        assertThat(mWakeupMessageReceived).isFalse();
        assertThat(mStandbyMessageReceived).isFalse();
    }

    @Test
    public void handleUserControlPressed_powerOffFunction_localDeviceOn_shouldTurnOff() {
        mPowerStatus = HdmiControlManager.POWER_STATUS_ON;
        @Constants.HandleMessageResult int result = mHdmiLocalDevice.handleUserControlPressed(
                HdmiCecMessageBuilder.buildUserControlPressed(ADDR_TV, ADDR_PLAYBACK_1,
                        HdmiCecKeycode.CEC_KEYCODE_POWER_OFF_FUNCTION));

        assertEquals(Constants.HANDLED, result);
        assertThat(mWakeupMessageReceived).isFalse();
        assertThat(mStandbyMessageReceived).isTrue();
    }

    @Test
    public void handleUserControlPressed_muteFunction() {
        @Constants.HandleMessageResult int result = mHdmiLocalDevice.handleUserControlPressed(
                HdmiCecMessageBuilder.buildUserControlPressed(ADDR_TV, ADDR_PLAYBACK_1,
                        HdmiCecKeycode.CEC_KEYCODE_MUTE_FUNCTION));

        assertEquals(result, Constants.HANDLED);
        verify(mAudioManager, times(1))
                .adjustStreamVolume(anyInt(), eq(AudioManager.ADJUST_MUTE), anyInt());
    }

    @Test
    public void handleUserControlPressed_restoreVolumeFunction() {
        @Constants.HandleMessageResult int result = mHdmiLocalDevice.handleUserControlPressed(
                HdmiCecMessageBuilder.buildUserControlPressed(ADDR_TV, ADDR_PLAYBACK_1,
                        HdmiCecKeycode.CEC_KEYCODE_RESTORE_VOLUME_FUNCTION));

        assertEquals(result, Constants.HANDLED);
        verify(mAudioManager, times(1))
                .adjustStreamVolume(anyInt(), eq(AudioManager.ADJUST_UNMUTE), anyInt());
    }

    @Test
    public void handleUserControlPressed_ignoreAdditionalParameters() {
        byte[] params = new byte[] {
                (byte) (HdmiCecKeycode.CEC_KEYCODE_POWER_TOGGLE_FUNCTION & 0xFF), (byte) 0xFF};
        mPowerStatus = HdmiControlManager.POWER_STATUS_STANDBY;
        @Constants.HandleMessageResult int result = mHdmiLocalDevice.handleUserControlPressed(
                HdmiCecMessageBuilder.buildUserControlPressed(ADDR_TV, ADDR_PLAYBACK_1, params));

        assertEquals(Constants.HANDLED, result);
        assertThat(mWakeupMessageReceived).isTrue();
        assertThat(mStandbyMessageReceived).isFalse();
    }

    @Test
    public void handleVendorCommand_notHandled() {
        HdmiCecMessage vendorCommand = HdmiCecMessageBuilder.buildVendorCommand(ADDR_TV,
                ADDR_PLAYBACK_1, new byte[]{0});
        @Constants.HandleMessageResult int result =
                mHdmiLocalDevice.handleVendorCommand(vendorCommand);
        mTestLooper.dispatchAll();

        assertEquals(Constants.ABORT_REFUSED, result);
    }

    @Test
    public void handleVendorCommandWithId_notHandled_Cec14() {
        HdmiCecMessage vendorCommand = HdmiCecMessageBuilder.buildVendorCommandWithId(ADDR_TV,
                ADDR_PLAYBACK_1, 0x1234, new byte[]{0});
        @Constants.HandleMessageResult int result =
                mHdmiLocalDevice.handleVendorCommandWithId(vendorCommand);
        mTestLooper.dispatchAll();

        assertEquals(Constants.ABORT_REFUSED, result);
    }

    @Test
    public void handleVendorCommandWithId_broadcasted_handled() {
        HdmiCecMessage vendorCommand = HdmiCecMessageBuilder.buildVendorCommandWithId(ADDR_TV,
                ADDR_BROADCAST, 0x1234, new byte[]{0});
        @Constants.HandleMessageResult int result =
                mHdmiLocalDevice.handleVendorCommandWithId(vendorCommand);
        mTestLooper.dispatchAll();

        assertEquals(Constants.HANDLED, result);
    }
}
