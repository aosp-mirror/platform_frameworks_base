/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.media.AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE;

import static com.android.server.SystemService.PHASE_SYSTEM_SERVICES_READY;
import static com.android.server.hdmi.Constants.HDMI_EARC_STATUS_ARC_PENDING;
import static com.android.server.hdmi.Constants.HDMI_EARC_STATUS_EARC_CONNECTED;
import static com.android.server.hdmi.Constants.HDMI_EARC_STATUS_EARC_PENDING;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.AudioDescriptor;
import android.media.AudioDeviceAttributes;
import android.media.AudioManager;
import android.os.Looper;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
/** Tests for {@link HdmiEarcLocalDeviceTx} class. */
public class HdmiEarcLocalDeviceTxTest {

    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiEarcLocalDevice mHdmiEarcLocalDeviceTx;
    private FakeNativeWrapper mNativeWrapper;
    private HdmiEarcController mHdmiEarcController;
    private FakeEArcNativeWrapper mEArcNativeWrapper;
    private FakePowerManagerWrapper mPowerManager;
    private byte[] mEarcCapabilities = new byte[]{
            0x01, 0x01, 0x1a, 0x35, 0x0f, 0x7f, 0x07, 0x15, 0x07, 0x50, 0x3d, 0x1f, (byte) 0xc0,
            0x57, 0x06, 0x03, 0x67, 0x7e, 0x03, 0x5f, 0x7e, 0x03, 0x5f, 0x7e, 0x01, (byte) 0x83,
            0x5f, 0x00, 0x00, 0x00, 0x00, 0x00};
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();

    @Mock
    private AudioManager mAudioManager;

    @Captor
    ArgumentCaptor<AudioDeviceAttributes> mAudioAttributesCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        Context context = InstrumentationRegistry.getTargetContext();
        mMyLooper = mTestLooper.getLooper();

        mHdmiControlService =
                new HdmiControlService(InstrumentationRegistry.getTargetContext(),
                        Collections.singletonList(HdmiDeviceInfo.DEVICE_TV),
                        new FakeAudioDeviceVolumeManagerWrapper()) {
                    @Override
                    boolean isCecControlEnabled() {
                        return true;
                    }

                    @Override
                    boolean isTvDevice() {
                        return true;
                    }

                    @Override
                    protected void writeStringSystemProperty(String key, String value) {
                        // do nothing
                    }

                    @Override
                    boolean isPowerStandby() {
                        return false;
                    }

                    @Override
                    AudioManager getAudioManager() {
                        return mAudioManager;
                    }
                };

        mHdmiControlService.setIoLooper(mMyLooper);
        mHdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(context));
        mHdmiControlService.setDeviceConfig(new FakeDeviceConfigWrapper());
        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(mHdmiCecController);
        mEArcNativeWrapper = new FakeEArcNativeWrapper();
        mHdmiEarcController = HdmiEarcController.createWithNativeWrapper(
                mHdmiControlService, mEArcNativeWrapper);
        mHdmiControlService.setEarcController(mHdmiEarcController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.initService();
        mHdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);
        mPowerManager = new FakePowerManagerWrapper(context);
        mHdmiControlService.setPowerManager(mPowerManager);
        mTestLooper.dispatchAll();
        mHdmiControlService.initializeEarcLocalDevice(HdmiControlService.INITIATED_BY_BOOT_UP);
        mHdmiEarcLocalDeviceTx = mHdmiControlService.getEarcLocalDevice();
    }

    @Test
    public void earcGetsConnected_capsReportedInTime_sad() {
        mHdmiEarcLocalDeviceTx.handleEarcStateChange(HDMI_EARC_STATUS_EARC_CONNECTED);
        mTestLooper.moveTimeForward(HdmiEarcLocalDeviceTx.REPORT_CAPS_MAX_DELAY_MS - 200);
        mTestLooper.dispatchAll();
        mHdmiEarcLocalDeviceTx.handleEarcCapabilitiesReported(new byte[]{
                0x01, 0x01, 0x1a, 0x35, 0x0f, 0x7f, 0x07, 0x15, 0x07, 0x50, 0x3d, 0x1f, (byte) 0xc0,
                0x57, 0x06, 0x03, 0x67, 0x7e, 0x03, 0x5f, 0x7e, 0x03, 0x5f, 0x7e, 0x01, 0x00, 0x5f,
                0x00, 0x00, 0x00, 0x00, 0x00
        });
        mTestLooper.dispatchAll();
        verify(mAudioManager, times(1)).setWiredDeviceConnectionState(
                mAudioAttributesCaptor.capture(), eq(1));
        AudioDeviceAttributes attributes = mAudioAttributesCaptor.getValue();
        List<AudioDescriptor> descriptors = attributes.getAudioDescriptors();
        List<AudioDescriptor> expectedDescriptors = new ArrayList<AudioDescriptor>(Arrays.asList(
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {15, 127, 7}),
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {21, 7, 80}),
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {61, 31, -64}),
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {87, 6, 3}),
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {103, 126, 3}),
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {95, 126, 3}),
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {95, 126, 1})));
        assertThat(descriptors).isEqualTo(expectedDescriptors);
    }

    @Test
    public void earcGetsConnected_capsReportedInTime_sad_sadb() {
        mHdmiEarcLocalDeviceTx.handleEarcStateChange(HDMI_EARC_STATUS_EARC_CONNECTED);
        mTestLooper.moveTimeForward(HdmiEarcLocalDeviceTx.REPORT_CAPS_MAX_DELAY_MS - 200);
        mTestLooper.dispatchAll();
        mHdmiEarcLocalDeviceTx.handleEarcCapabilitiesReported(new byte[]{
                0x01, 0x01, 0x1a, 0x35, 0x0f, 0x7f, 0x07, 0x15, 0x07, 0x50, 0x3d, 0x1f, (byte) 0xc0,
                0x57, 0x06, 0x03, 0x67, 0x7e, 0x03, 0x5f, 0x7e, 0x03, 0x5f, 0x7e, 0x01, (byte) 0x83,
                0x5f, 0x00, 0x00, 0x00, 0x00, 0x00});
        mTestLooper.dispatchAll();
        verify(mAudioManager, times(1)).setWiredDeviceConnectionState(
                mAudioAttributesCaptor.capture(), eq(1));
        AudioDeviceAttributes attributes = mAudioAttributesCaptor.getValue();
        List<AudioDescriptor> descriptors = attributes.getAudioDescriptors();
        List<AudioDescriptor> expectedDescriptors = new ArrayList<AudioDescriptor>(Arrays.asList(
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {15, 127, 7}),
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {21, 7, 80}),
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {61, 31, -64}),
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {87, 6, 3}),
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {103, 126, 3}),
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {95, 126, 3}),
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {95, 126, 1}),
                new AudioDescriptor(AudioDescriptor.STANDARD_SADB, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {-125, 95, 0, 0})));
        assertThat(descriptors).isEqualTo(expectedDescriptors);
    }

    @Test
    public void earcGetsConnected_capsReportedInTime_sad_sadb_vsadb() {
        mHdmiEarcLocalDeviceTx.handleEarcStateChange(HDMI_EARC_STATUS_EARC_CONNECTED);
        mTestLooper.moveTimeForward(HdmiEarcLocalDeviceTx.REPORT_CAPS_MAX_DELAY_MS - 200);
        mTestLooper.dispatchAll();
        mHdmiEarcLocalDeviceTx.handleEarcCapabilitiesReported(new byte[]{
                0x01, 0x01, 0x21, 0x35, 0x5F, 0x7E, 0x03, 0x5F, 0x7E, 0x01, 0x67, 0x7E, 0x03, 0x57,
                0x06, 0x03, 0x3D, 0x1E, (byte) 0xC0, 0x15, 0x07, 0x50, 0x0F, 0x7F, 0x07,
                (byte) 0x83, 0x5F, 0x00, 0x00, (byte) 0xE6, 0x11, 0x46, (byte) 0xD0, 0x00, 0x70,
                0x00, 0x03, 0x01, (byte) 0x80, 0x00, (byte) 0x9D, (byte) 0xAD, (byte) 0x9E, 0x7B,
                0x08, (byte) 0xC1, (byte) 0xA8, 0x23, (byte) 0x9B, 0x49, 0x5C, (byte) 0xF5, 0x6B,
                (byte) 0xAC, 0x22, (byte) 0xC2, (byte) 0x80, 0x48, 0x67, 0x7F, 0x59, 0x1C, 0x20,
                0x71, 0x35, 0x25, (byte) 0x9F, 0x43, 0x70, 0x1E, 0x32, 0x15, 0x60, (byte) 0xED,
                (byte) 0xC8, 0x77, (byte) 0xA3, 0x24, 0x2E, (byte) 0xDA, (byte) 0x94, 0x6D, 0x35,
                0x34, 0x0F, 0x30, 0x62, 0x1A, 0x3B, (byte) 0xC9, 0x5A, (byte) 0xE6, (byte) 0xD8,
                0x22, 0x11, 0x56, (byte) 0xA6, (byte) 0x99, (byte) 0xCF, (byte) 0xE3, 0x1B,
                (byte) 0x88, (byte) 0xA0, 0x2A, 0x5B, 0x6C, 0x5E, 0x53, 0x01, 0x47, 0x69, 0x51,
                0x61, (byte) 0xC7, (byte) 0xCB, 0x1B, 0x28, 0x14, 0x23, 0x10, (byte) 0xB1, 0x34,
                0x5E, 0x57, (byte) 0x97, (byte) 0xB3, 0x78, 0x03, 0x79, (byte) 0x8A, (byte) 0xFE,
                0x1E, (byte) 0xC8, (byte) 0xAB, 0x14, 0x74, 0x73, (byte) 0xFA, (byte) 0xBB,
                (byte) 0xF7, 0x4E, 0x00, (byte) 0xFC, 0x5C, (byte) 0xDC, (byte) 0x8B, (byte) 0xC9,
                0x1E, 0x16, 0x35, (byte) 0xB1, (byte) 0x98, (byte) 0xEB, 0x2B, (byte) 0xE6,
                (byte) 0xFC, (byte) 0xCC, 0x3C, 0x30, 0x19, 0x40, (byte) 0xC0, 0x50, (byte) 0xF2,
                0x58, 0x30, 0x4B, 0x0C, 0x7A, (byte) 0xE0, (byte) 0xFF, 0x7A, 0x64, 0x78,
                (byte) 0xF8, 0x56, (byte) 0xF8, 0x6E, 0x72, 0x42, 0x49, 0x4E, (byte) 0xA6,
                (byte) 0x95, (byte) 0xF5, 0x4C, 0x4F, (byte) 0xFF, 0x7F, 0x21, (byte) 0xA2,
                (byte) 0x98, 0x33, (byte) 0x90, (byte) 0xFD, 0x17, 0x08, 0x13, (byte) 0xB2, 0x00,
                (byte) 0xA9, (byte) 0xB5, (byte) 0xBD, (byte) 0xB5, (byte) 0xC1, (byte) 0xC7, 0x45,
                (byte) 0xD9, (byte) 0xDC, (byte) 0x8B, 0x58, (byte) 0xB3, 0x5D, 0x5E, 0x72,
                (byte) 0xE6, (byte) 0x8D, (byte) 0xDD, 0x0B, 0x21, (byte) 0xF3, (byte) 0x9A,
                (byte) 0x8E, 0x1B, 0x79, 0x59, (byte) 0xE1, 0x3F, (byte) 0xAC, 0x24, (byte) 0xA0,
                (byte) 0xC8, 0x56, (byte) 0xFD, (byte) 0x85, (byte) 0x8F, 0x6A, (byte) 0x80, 0x41,
                (byte) 0xA8, 0x5D, 0x2C, (byte) 0xC2, 0x69, (byte) 0xA1, 0x0D, (byte) 0x82, 0x04,
                0x5D, (byte) 0xCA, (byte) 0xB4, (byte) 0x9F, 0x3A, 0x2D, (byte) 0xBF, 0x24});
        mTestLooper.dispatchAll();
        verify(mAudioManager, times(1)).setWiredDeviceConnectionState(
                mAudioAttributesCaptor.capture(), eq(1));
        AudioDeviceAttributes attributes = mAudioAttributesCaptor.getValue();
        List<AudioDescriptor> descriptors = attributes.getAudioDescriptors();
        List<AudioDescriptor> expectedDescriptors = new ArrayList<AudioDescriptor>(Arrays.asList(
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {95, 126, 3}),
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {95, 126, 1}),
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {103, 126, 3}),
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {87, 6, 3}),
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {61, 30, -64}),
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {21, 7, 80}),
                new AudioDescriptor(AudioDescriptor.STANDARD_EDID, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {15, 127, 7}),
                new AudioDescriptor(AudioDescriptor.STANDARD_SADB, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {-125, 95, 0, 0}),
                new AudioDescriptor(AudioDescriptor.STANDARD_VSADB, AUDIO_ENCAPSULATION_TYPE_NONE,
                        new byte[] {-26, 17, 70, -48, 0, 112, 0})));
        assertThat(descriptors).isEqualTo(expectedDescriptors);
    }

    @Test
    public void earcGetsConnected_capsReportedTooLate() {
        mHdmiEarcLocalDeviceTx.handleEarcStateChange(HDMI_EARC_STATUS_EARC_CONNECTED);
        mTestLooper.moveTimeForward(HdmiEarcLocalDeviceTx.REPORT_CAPS_MAX_DELAY_MS + 1);
        mTestLooper.dispatchAll();
        verify(mAudioManager, times(1)).setWiredDeviceConnectionState(
                mAudioAttributesCaptor.capture(), eq(1));
        AudioDeviceAttributes attributes = mAudioAttributesCaptor.getValue();
        List<AudioDescriptor> descriptors = attributes.getAudioDescriptors();
        assertThat(descriptors).hasSize(0);
        Mockito.clearInvocations(mAudioManager);

        mHdmiEarcLocalDeviceTx.handleEarcCapabilitiesReported(mEarcCapabilities);
        mTestLooper.dispatchAll();
        verify(mAudioManager, times(0)).setWiredDeviceConnectionState(any(), anyInt());
    }

    @Test
    public void earcGetsConnected_earcGetsDisconnectedBeforeCapsReported() {
        mHdmiEarcLocalDeviceTx.handleEarcStateChange(HDMI_EARC_STATUS_EARC_CONNECTED);
        mTestLooper.dispatchAll();
        mHdmiEarcLocalDeviceTx.handleEarcStateChange(HDMI_EARC_STATUS_ARC_PENDING);
        mTestLooper.dispatchAll();
        verify(mAudioManager, times(0)).setWiredDeviceConnectionState(any(), eq(1));
        verify(mAudioManager, times(1)).setWiredDeviceConnectionState(
                mAudioAttributesCaptor.capture(), eq(0));
        AudioDeviceAttributes attributes = mAudioAttributesCaptor.getValue();
        List<AudioDescriptor> descriptors = attributes.getAudioDescriptors();
        assertThat(descriptors).hasSize(0);
        Mockito.clearInvocations(mAudioManager);

        mHdmiEarcLocalDeviceTx.handleEarcCapabilitiesReported(mEarcCapabilities);
        mTestLooper.dispatchAll();
        verify(mAudioManager, times(0)).setWiredDeviceConnectionState(any(), anyInt());
    }

    @Test
    public void earcGetsConnected_earcBecomesPendingBeforeCapsReported() {
        mHdmiEarcLocalDeviceTx.handleEarcStateChange(HDMI_EARC_STATUS_EARC_CONNECTED);
        mTestLooper.dispatchAll();
        mHdmiEarcLocalDeviceTx.handleEarcStateChange(HDMI_EARC_STATUS_EARC_PENDING);
        mTestLooper.dispatchAll();
        verify(mAudioManager, times(0)).setWiredDeviceConnectionState(any(), anyInt());
        Mockito.clearInvocations(mAudioManager);

        mHdmiEarcLocalDeviceTx.handleEarcCapabilitiesReported(mEarcCapabilities);
        mTestLooper.dispatchAll();
        verify(mAudioManager, times(0)).setWiredDeviceConnectionState(any(), anyInt());
    }
}
