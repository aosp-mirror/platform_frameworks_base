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
import static android.hardware.hdmi.HdmiDeviceInfo.DEVICE_TV;

import static com.android.server.hdmi.Constants.ABORT_REFUSED;
import static com.android.server.hdmi.Constants.ABORT_UNRECOGNIZED_OPCODE;
import static com.android.server.hdmi.Constants.ADDR_AUDIO_SYSTEM;
import static com.android.server.hdmi.Constants.ADDR_BACKUP_1;
import static com.android.server.hdmi.Constants.ADDR_BACKUP_2;
import static com.android.server.hdmi.Constants.ADDR_BROADCAST;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_1;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_2;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_3;
import static com.android.server.hdmi.Constants.ADDR_SPECIFIC_USE;
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.Constants.ADDR_UNREGISTERED;
import static com.android.server.hdmi.Constants.HANDLED;
import static com.android.server.hdmi.Constants.MESSAGE_STANDBY;
import static com.android.server.hdmi.Constants.NOT_HANDLED;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.hardware.hdmi.HdmiControlManager;
import android.hardware.tv.cec.V1_0.SendMessageResult;
import android.os.Binder;
import android.os.Looper;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.SystemService;
import com.android.server.hdmi.HdmiCecController.AllocateAddressCallback;

import junit.framework.TestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;

/** Tests for {@link com.android.server.hdmi.HdmiCecController} class. */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class HdmiCecControllerTest {

    private FakeNativeWrapper mNativeWrapper;

    private HdmiControlService mHdmiControlServiceSpy;

    private HdmiCecController mHdmiCecController;
    private int mCecVersion = HdmiControlManager.HDMI_CEC_VERSION_1_4_B;
    private int mLogicalAddress = 16;
    private int mPlaybackLogicalAddress;
    private AllocateAddressCallback mCallback =
            new AllocateAddressCallback() {
                @Override
                public void onAllocated(int deviceType, int logicalAddress) {
                    mLogicalAddress = logicalAddress;
                }
            };
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();

    @Before
    public void SetUp() {
        mMyLooper = mTestLooper.getLooper();

        mHdmiControlServiceSpy = spy(new HdmiControlService(
                InstrumentationRegistry.getTargetContext(), Collections.emptyList(),
                new FakeAudioDeviceVolumeManagerWrapper()));
        doReturn(mMyLooper).when(mHdmiControlServiceSpy).getIoLooper();
        doReturn(mMyLooper).when(mHdmiControlServiceSpy).getServiceLooper();
        doAnswer(__ -> mCecVersion).when(mHdmiControlServiceSpy).getCecVersion();
        doNothing().when(mHdmiControlServiceSpy)
                .writeStringSystemProperty(anyString(), anyString());
        mHdmiControlServiceSpy.setDeviceConfig(new FakeDeviceConfigWrapper());

        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlServiceSpy, mNativeWrapper, mHdmiControlServiceSpy.getAtomWriter());
    }

    /** Additional setup for tests for onMessage
     *  Adds a local playback device and allocates addresses
     */
    public void setUpForOnMessageTest() {
        mHdmiControlServiceSpy.setCecController(mHdmiCecController);

        HdmiCecLocalDevicePlayback playbackDevice =
                new HdmiCecLocalDevicePlayback(mHdmiControlServiceSpy);
        playbackDevice.init();
        ArrayList<HdmiCecLocalDevice> localDevices = new ArrayList<>();
        localDevices.add(playbackDevice);

        mHdmiControlServiceSpy.initService();
        mHdmiControlServiceSpy.allocateLogicalAddress(localDevices,
                HdmiControlService.INITIATED_BY_ENABLE_CEC);
        mHdmiControlServiceSpy.onBootPhase(SystemService.PHASE_SYSTEM_SERVICES_READY);
        mTestLooper.dispatchAll();

        mPlaybackLogicalAddress = playbackDevice.getDeviceInfo().getLogicalAddress();
        mTestLooper.dispatchAll();
    }

    /** Tests for {@link HdmiCecController#allocateLogicalAddress} */
    @Test
    public void testAllocateLogicalAddress_TvDevicePreferredNotOccupied() {
        mHdmiCecController.allocateLogicalAddress(DEVICE_TV, ADDR_TV, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_TV, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_TvDeviceNonPreferredNotOccupied() {
        mHdmiCecController.allocateLogicalAddress(DEVICE_TV, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_TV, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_TvDeviceNonPreferredFirstOccupied() {
        mNativeWrapper.setPollAddressResponse(ADDR_TV, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_TV, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_SPECIFIC_USE, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_TvDeviceNonPreferredAllOccupied() {
        mNativeWrapper.setPollAddressResponse(ADDR_TV, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_SPECIFIC_USE, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_TV, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_UNREGISTERED, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_AudioSystemNonPreferredNotOccupied() {
        mHdmiCecController.allocateLogicalAddress(
                DEVICE_AUDIO_SYSTEM, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_AUDIO_SYSTEM, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_AudioSystemNonPreferredAllOccupied() {
        mNativeWrapper.setPollAddressResponse(ADDR_AUDIO_SYSTEM, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(
                DEVICE_AUDIO_SYSTEM, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_UNREGISTERED, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackPreferredNotOccupied() {
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_PLAYBACK_1, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_1, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackPreferredOccupied() {
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_PLAYBACK_1, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_2, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackNoPreferredNotOccupied() {
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_1, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackNoPreferredFirstOccupied() {
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_2, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackNonPreferredFirstTwoOccupied() {
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_2, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_3, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackNonPreferredAllOccupied() {
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_2, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_3, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_UNREGISTERED, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackNonPreferred_2_0_BackupOne() {
        mCecVersion = HdmiControlManager.HDMI_CEC_VERSION_2_0;

        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_2, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_3, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_BACKUP_1, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackNonPreferred_2_0_BackupTwo() {
        mCecVersion = HdmiControlManager.HDMI_CEC_VERSION_2_0;

        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_2, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_3, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_BACKUP_1, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_BACKUP_2, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackPreferredOccupiedDedicatedBelowAvailable() {
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_2, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_3, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_PLAYBACK_2, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_1, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackPreferredOccupiedDedicatedAboveAvailable() {
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_2, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_PLAYBACK_2, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_PLAYBACK_3, mLogicalAddress);
    }

    @Test
    public void testAllocateLogicalAddress_PlaybackNonPreferred_2_0_AllOccupied() {
        mCecVersion = HdmiControlManager.HDMI_CEC_VERSION_2_0;

        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_1, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_2, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_PLAYBACK_3, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_BACKUP_1, SendMessageResult.SUCCESS);
        mNativeWrapper.setPollAddressResponse(ADDR_BACKUP_2, SendMessageResult.SUCCESS);
        mHdmiCecController.allocateLogicalAddress(DEVICE_PLAYBACK, ADDR_UNREGISTERED, mCallback);
        mTestLooper.dispatchAll();
        assertEquals(ADDR_UNREGISTERED, mLogicalAddress);
    }

    @Test
    public void testIsLanguage() {
        assertTrue(HdmiCecController.isLanguage("en"));
        assertTrue(HdmiCecController.isLanguage("eng"));
        assertTrue(HdmiCecController.isLanguage("ger"));
        assertTrue(HdmiCecController.isLanguage("zh"));
        assertTrue(HdmiCecController.isLanguage("zhi"));
        assertTrue(HdmiCecController.isLanguage("zho"));

        assertFalse(HdmiCecController.isLanguage(null));
        assertFalse(HdmiCecController.isLanguage(""));
        assertFalse(HdmiCecController.isLanguage("e"));
        assertFalse(HdmiCecController.isLanguage("一")); // language code must be ASCII
    }

    @Test
    public void runOnServiceThread_preservesAndRestoresWorkSourceUid() {
        Binder.setCallingWorkSourceUid(1234);
        WorkSourceUidReadingRunnable uidReadingRunnable = new WorkSourceUidReadingRunnable();
        mHdmiCecController.runOnServiceThread(uidReadingRunnable);

        Binder.setCallingWorkSourceUid(5678);
        mTestLooper.dispatchAll();

        TestCase.assertEquals(Optional.of(1234), uidReadingRunnable.getWorkSourceUid());
        TestCase.assertEquals(5678, Binder.getCallingWorkSourceUid());
    }

    @Test
    public void runOnIoThread_preservesAndRestoresWorkSourceUid() {
        int callerUid = 1234;
        int runnerUid = 5678;

        Binder.setCallingWorkSourceUid(callerUid);
        WorkSourceUidReadingRunnable uidReadingRunnable = new WorkSourceUidReadingRunnable();
        mHdmiCecController.runOnIoThread(uidReadingRunnable);

        Binder.setCallingWorkSourceUid(runnerUid);
        mTestLooper.dispatchAll();

        TestCase.assertEquals(Optional.of(callerUid), uidReadingRunnable.getWorkSourceUid());
        TestCase.assertEquals(runnerUid, Binder.getCallingWorkSourceUid());
    }

    @Test
    public void onMessage_broadcastMessage_doesNotSendFeatureAbort() {
        setUpForOnMessageTest();

        doReturn(ABORT_UNRECOGNIZED_OPCODE).when(mHdmiControlServiceSpy).handleCecCommand(any());

        HdmiCecMessage receivedMessage = HdmiCecMessageBuilder.buildStandby(
                ADDR_TV, ADDR_BROADCAST);

        mNativeWrapper.onCecMessage(receivedMessage);

        mTestLooper.dispatchAll();

        assertFalse("No <Feature Abort> messages should be sent",
                mNativeWrapper.getResultMessages().stream().anyMatch(
                        message -> message.getOpcode() == Constants.MESSAGE_FEATURE_ABORT));
    }

    @Test
    public void onMessage_notTheDestination_doesNotSendFeatureAbort() {
        setUpForOnMessageTest();

        doReturn(ABORT_UNRECOGNIZED_OPCODE).when(mHdmiControlServiceSpy).handleCecCommand(any());

        HdmiCecMessage receivedMessage = HdmiCecMessageBuilder.buildStandby(
                ADDR_TV, ADDR_AUDIO_SYSTEM);
        mNativeWrapper.onCecMessage(receivedMessage);

        mTestLooper.dispatchAll();

        assertFalse("No <Feature Abort> messages should be sent",
                mNativeWrapper.getResultMessages().stream().anyMatch(
                        message -> message.getOpcode() == Constants.MESSAGE_FEATURE_ABORT));
    }

    @Test
    public void onMessage_handledMessage_doesNotSendFeatureAbort() {
        setUpForOnMessageTest();

        doReturn(HANDLED).when(mHdmiControlServiceSpy).handleCecCommand(any());

        HdmiCecMessage receivedMessage = HdmiCecMessageBuilder.buildStandby(
                ADDR_TV, mPlaybackLogicalAddress);
        mNativeWrapper.onCecMessage(receivedMessage);

        mTestLooper.dispatchAll();

        assertFalse("No <Feature Abort> messages should be sent",
                mNativeWrapper.getResultMessages().stream().anyMatch(
                        message -> message.getOpcode() == Constants.MESSAGE_FEATURE_ABORT));
    }

    @Test
    public void onMessage_unhandledMessage_sendsFeatureAbortUnrecognizedOpcode() {
        setUpForOnMessageTest();

        doReturn(NOT_HANDLED).when(mHdmiControlServiceSpy).handleCecCommand(any());

        HdmiCecMessage receivedMessage = HdmiCecMessageBuilder.buildStandby(
                ADDR_TV, mPlaybackLogicalAddress);
        mNativeWrapper.onCecMessage(receivedMessage);

        mTestLooper.dispatchAll();

        HdmiCecMessage featureAbort = HdmiCecMessageBuilder.buildFeatureAbortCommand(
                mPlaybackLogicalAddress, DEVICE_TV, MESSAGE_STANDBY, ABORT_UNRECOGNIZED_OPCODE);
        assertThat(mNativeWrapper.getResultMessages()).contains(featureAbort);
    }

    @Test
    public void onMessage_sendsFeatureAbortWithRequestedOperand() {
        setUpForOnMessageTest();

        doReturn(ABORT_REFUSED).when(mHdmiControlServiceSpy).handleCecCommand(any());

        HdmiCecMessage receivedMessage = HdmiCecMessageBuilder.buildStandby(
                ADDR_TV, mPlaybackLogicalAddress);
        mNativeWrapper.onCecMessage(receivedMessage);

        mTestLooper.dispatchAll();

        HdmiCecMessage featureAbort = HdmiCecMessageBuilder.buildFeatureAbortCommand(
                mPlaybackLogicalAddress, DEVICE_TV, MESSAGE_STANDBY, ABORT_REFUSED);
        assertThat(mNativeWrapper.getResultMessages()).contains(featureAbort);
    }
}
