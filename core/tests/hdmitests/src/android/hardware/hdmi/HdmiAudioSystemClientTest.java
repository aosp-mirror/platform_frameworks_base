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

package android.hardware.hdmi;

import android.os.Handler;
import android.os.test.TestLooper;
import android.util.Log;

import androidx.test.filters.SmallTest;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link HdmiAudioSystemClient}
 */
@RunWith(JUnit4.class)
@SmallTest
public class HdmiAudioSystemClientTest {
    private static final String TAG = "HdmiAudioSystemClientTe";

    private HdmiAudioSystemClient mHdmiAudioSystemClient;
    private TestLooper mTestLooper;
    private int mVolume;
    private int mMaxVolume;
    private boolean mIsMute;

    @Before
    public void before() {
        Log.d(TAG, "before()");
        IHdmiControlService mService = new TestHdmiControlService();
        mTestLooper = new TestLooper();
        mHdmiAudioSystemClient =
                new HdmiAudioSystemClient(mService, new Handler(mTestLooper.getLooper()));
        resetVariables();
    }

    @Test
    public void testSingleCommand() {
        mHdmiAudioSystemClient.sendReportAudioStatusCecCommand(false, 50, 100, false);
        assertAudioStatus(50, 100, false);
    }

    @Test
    public void testMultipleCommands_longTimeBetweenCalls() {
        mHdmiAudioSystemClient.sendReportAudioStatusCecCommand(false, 50, 100, false);
        assertAudioStatus(50, 100, false);
        mTestLooper.moveTimeForward(500);
        mTestLooper.dispatchAll();
        mHdmiAudioSystemClient.sendReportAudioStatusCecCommand(false, 60, 100, false);
        assertAudioStatus(60, 100, false);
    }

    @Test
    public void testMultipleCommands_shortTimeBetweenCalls() {
        mHdmiAudioSystemClient.sendReportAudioStatusCecCommand(false, 1, 100, false);
        assertAudioStatus(1, 100, false);

        mTestLooper.moveTimeForward(100); // current time: 100ms
        mTestLooper.dispatchAll();
        mHdmiAudioSystemClient.sendReportAudioStatusCecCommand(false, 10, 100, false);
        assertAudioStatus(1, 100, false); // command not sent, no change

        mTestLooper.moveTimeForward(100); // current time: 200ms
        mTestLooper.dispatchAll();
        mHdmiAudioSystemClient.sendReportAudioStatusCecCommand(false, 20, 100, false);
        assertAudioStatus(1, 100, false); // command not sent, no change

        mTestLooper.moveTimeForward(300); // current time: 500ms
        mTestLooper.dispatchAll();
        assertAudioStatus(20, 100, false); // pending command sent, changed to 20

        mTestLooper.moveTimeForward(100); // current time: 600ms
        mTestLooper.dispatchAll();
        mHdmiAudioSystemClient.sendReportAudioStatusCecCommand(false, 60, 100, false);
        assertAudioStatus(20, 100, false); // command not sent, no change

        mTestLooper.moveTimeForward(200); // current time: 800ms
        mTestLooper.dispatchAll();
        mHdmiAudioSystemClient.sendReportAudioStatusCecCommand(false, 80, 100, false);
        assertAudioStatus(20, 100, false); // command not sent, no change

        mTestLooper.moveTimeForward(200); // current time: 1000ms
        mTestLooper.dispatchAll();
        assertAudioStatus(80, 100, false); // command sent, changed to 80
    }

    @Test
    public void testMultipleCommands_shortTimeAndReturn() {
        mHdmiAudioSystemClient.sendReportAudioStatusCecCommand(false, 1, 100, false);
        assertAudioStatus(1, 100, false);

        mTestLooper.moveTimeForward(100); // current time: 100ms
        mTestLooper.dispatchAll();
        mHdmiAudioSystemClient.sendReportAudioStatusCecCommand(false, 20, 100, false);
        assertAudioStatus(1, 100, false); // command not sent, no change

        mTestLooper.moveTimeForward(100); // current time: 200ms
        mTestLooper.dispatchAll();
        mHdmiAudioSystemClient.sendReportAudioStatusCecCommand(false, 1, 100, false);
        assertAudioStatus(1, 100, false); // command not sent, no change

        mTestLooper.moveTimeForward(300); // current time: 500ms
        mTestLooper.dispatchAll();
        assertAudioStatus(1, 100, false); // pending command sent
    }

    @Test
    public void testMultipleCommands_muteAdjust() {
        mHdmiAudioSystemClient.sendReportAudioStatusCecCommand(false, 1, 100, false);
        assertAudioStatus(1, 100, false);

        mTestLooper.moveTimeForward(100); // current time: 100ms
        mTestLooper.dispatchAll();
        mHdmiAudioSystemClient.sendReportAudioStatusCecCommand(true, 10, 100, true);
        assertAudioStatus(10, 100, true); // mute adjust, command sent, changed to 10

        mTestLooper.moveTimeForward(100); // current time: 200ms
        mTestLooper.dispatchAll();
        mHdmiAudioSystemClient.sendReportAudioStatusCecCommand(false, 20, 100, false);
        assertAudioStatus(10, 100, true); // command not sent, no change

        mTestLooper.moveTimeForward(300); // current time: 500ms
        mTestLooper.dispatchAll();
        assertAudioStatus(20, 100, false); // pending command sent, changed to 20, unmuted
    }

    private void assertAudioStatus(int volume, int maxVolume, boolean isMute) {
        Assert.assertEquals(volume, mVolume);
        Assert.assertEquals(maxVolume, mMaxVolume);
        Assert.assertEquals(isMute, mIsMute);
    }

    private void resetVariables() {
        mVolume = -1;
        mMaxVolume = -1;
        mIsMute = true;
    }

    private final class TestHdmiControlService extends IHdmiControlService.Stub {

        @Override
        public int[] getSupportedTypes() {
            return null;
        }

        @Override
        public HdmiDeviceInfo getActiveSource() {
            return null;
        }

        @Override
        public void deviceSelect(final int deviceId, final IHdmiControlCallback callback) {
        }

        @Override
        public void portSelect(final int portId, final IHdmiControlCallback callback) {
        }

        @Override
        public void sendKeyEvent(final int deviceType, final int keyCode, final boolean isPressed) {
        }

        @Override
        public void sendVolumeKeyEvent(
            final int deviceType, final int keyCode, final boolean isPressed) {
        }

        @Override
        public void oneTouchPlay(final IHdmiControlCallback callback) {
        }

        @Override
        public void toggleAndFollowTvPower() {
        }

        @Override
        public boolean shouldHandleTvPowerKey() {
            return false;
        }

        @Override
        public void queryDisplayStatus(final IHdmiControlCallback callback) {
        }

        @Override
        public void addHdmiControlStatusChangeListener(
                final IHdmiControlStatusChangeListener listener) {
        }

        @Override
        public void removeHdmiControlStatusChangeListener(
                final IHdmiControlStatusChangeListener listener) {
        }

        @Override
        public void addHotplugEventListener(final IHdmiHotplugEventListener listener) {
        }

        @Override
        public void removeHotplugEventListener(final IHdmiHotplugEventListener listener) {
        }

        @Override
        public void addDeviceEventListener(final IHdmiDeviceEventListener listener) {
        }

        @Override
        public List<HdmiPortInfo> getPortInfo() {
            return null;
        }

        @Override
        public boolean canChangeSystemAudioMode() {
            return false;
        }

        @Override
        public boolean getSystemAudioMode() {
            return false;
        }

        @Override
        public void setSystemAudioMode(final boolean enabled, final IHdmiControlCallback callback) {
        }

        @Override
        public void addSystemAudioModeChangeListener(
                final IHdmiSystemAudioModeChangeListener listener) {
        }

        @Override
        public void removeSystemAudioModeChangeListener(
                final IHdmiSystemAudioModeChangeListener listener) {
        }

        @Override
        public void setInputChangeListener(final IHdmiInputChangeListener listener) {
        }

        @Override
        public List<HdmiDeviceInfo> getInputDevices() {
            return null;
        }

        // Returns all the CEC devices on the bus including system audio, switch,
        // even those of reserved type.
        @Override
        public List<HdmiDeviceInfo> getDeviceList() {
            return null;
        }

        @Override
        public void setSystemAudioVolume(final int oldIndex, final int newIndex,
                final int maxIndex) {
        }

        @Override
        public void setSystemAudioMute(final boolean mute) {
        }

        @Override
        public void setArcMode(final boolean enabled) {
        }

        @Override
        public void setProhibitMode(final boolean enabled) {
        }

        @Override
        public void addVendorCommandListener(final IHdmiVendorCommandListener listener,
                final int deviceType) {
        }

        @Override
        public void sendVendorCommand(final int deviceType, final int targetAddress,
                final byte[] params, final boolean hasVendorId) {
        }

        @Override
        public void sendStandby(final int deviceType, final int deviceId) {
        }

        @Override
        public void setHdmiRecordListener(IHdmiRecordListener listener) {
        }

        @Override
        public void startOneTouchRecord(final int recorderAddress, final byte[] recordSource) {
        }

        @Override
        public void stopOneTouchRecord(final int recorderAddress) {
        }

        @Override
        public void startTimerRecording(final int recorderAddress, final int sourceType,
                final byte[] recordSource) {
        }

        @Override
        public void clearTimerRecording(final int recorderAddress, final int sourceType,
                final byte[] recordSource) {
        }

        @Override
        public void sendMhlVendorCommand(final int portId, final int offset, final int length,
                final byte[] data) {
        }

        @Override
        public void addHdmiMhlVendorCommandListener(IHdmiMhlVendorCommandListener listener) {
        }

        @Override
        public void setStandbyMode(final boolean isStandbyModeOn) {
        }

        @Override
        public void reportAudioStatus(final int deviceType, final int volume, final int maxVolume,
                final boolean isMute) {
            mVolume = volume;
            mMaxVolume = maxVolume;
            mIsMute = isMute;
        }

        @Override
        public void setSystemAudioModeOnForAudioOnlySource() {
        }

        @Override
        public int getPhysicalAddress() {
            return 0x0000;
        }

        @Override
        public void powerOffRemoteDevice(int logicalAddress, int powerStatus) {
        }

        @Override
        public void powerOnRemoteDevice(int logicalAddress, int powerStatus) {
        }

        @Override
        public void askRemoteDeviceToBecomeActiveSource(int physicalAddress) {
        }

        @Override
        public void addHdmiCecVolumeControlFeatureListener(
                IHdmiCecVolumeControlFeatureListener listener) {
        }

        @Override
        public void removeHdmiCecVolumeControlFeatureListener(
                IHdmiCecVolumeControlFeatureListener listener) {
        }

        @Override
        public List<String> getUserCecSettings() {
            return new ArrayList<>();
        }

        @Override
        public List<String> getAllowedCecSettingStringValues(String name) {
            return new ArrayList<>();
        }

        @Override
        public void addCecSettingChangeListener(String name,
                IHdmiCecSettingChangeListener listener) {
        }

        @Override
        public void removeCecSettingChangeListener(String name,
                IHdmiCecSettingChangeListener listener) {
        }

        @Override
        public int[] getAllowedCecSettingIntValues(String name) {
            return new int[0];
        }

        @Override
        public String getCecSettingStringValue(String name) {
            return "";
        }

        @Override
        public void setCecSettingStringValue(String name, String value) {
        }

        @Override
        public int getCecSettingIntValue(String name) {
            return 0;
        }

        @Override
        public void setCecSettingIntValue(String name, int value) {
        }
    }

}
