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

import static com.android.server.SystemService.PHASE_SYSTEM_SERVICES_READY;
import static com.android.server.hdmi.Constants.ADDR_AUDIO_SYSTEM;

import static com.google.common.truth.Truth.assertThat;

import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.Intent;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.os.Looper;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.hdmi.RequestSadAction.RequestSadCallback;

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
public class RequestSadActionTest {

    private static final int TIMEOUT_MS = HdmiConfig.TIMEOUT_MS + 1;
    private static final ArrayList<Integer> CODECS_TO_QUERY_1 = new ArrayList<Integer>(
            Arrays.asList(Constants.AUDIO_CODEC_LPCM, Constants.AUDIO_CODEC_DD,
                    Constants.AUDIO_CODEC_MPEG1, Constants.AUDIO_CODEC_MP3));
    private static final ArrayList<Integer> CODECS_TO_QUERY_2 = new ArrayList<Integer>(
            Arrays.asList(Constants.AUDIO_CODEC_MPEG2, Constants.AUDIO_CODEC_AAC,
                    Constants.AUDIO_CODEC_DTS, Constants.AUDIO_CODEC_ATRAC));
    private static final ArrayList<Integer> CODECS_TO_QUERY_3 = new ArrayList<Integer>(
            Arrays.asList(Constants.AUDIO_CODEC_ONEBITAUDIO, Constants.AUDIO_CODEC_DDP,
                    Constants.AUDIO_CODEC_DTSHD, Constants.AUDIO_CODEC_TRUEHD));
    private static final ArrayList<Integer> CODECS_TO_QUERY_4 = new ArrayList<Integer>(
            Arrays.asList(Constants.AUDIO_CODEC_DST, Constants.AUDIO_CODEC_WMAPRO,
                    Constants.AUDIO_CODEC_MAX));

    private HdmiControlService mHdmiControlService;
    private HdmiCecController mHdmiCecController;
    private HdmiCecLocalDeviceTv mHdmiCecLocalDeviceTv;
    private FakeNativeWrapper mNativeWrapper;
    private FakePowerManagerWrapper mPowerManager;
    private Looper mMyLooper;
    private TestLooper mTestLooper = new TestLooper();
    private int mTvLogicalAddress;
    private List<byte[]> mSupportedSads;
    private RequestSadCallback mCallback =
            new RequestSadCallback() {
                @Override
                public void onRequestSadDone(
                        List<byte[]> supportedSads) {
                    mSupportedSads = supportedSads;
                }
            };

    private static byte[] concatenateSads(List<byte[]> sads) {
        byte[] concatenatedSads = new byte[sads.size() * 3];
        for (int i = 0; i < sads.size(); i++) {
            for (int j = 0; j < 3; j++) {
                concatenatedSads[3 * i + j] = sads.get(i)[j];
            }
        }
        return concatenatedSads;
    }

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getTargetContext();
        mMyLooper = mTestLooper.getLooper();

        FakeAudioFramework audioFramework = new FakeAudioFramework();

        mHdmiControlService =
                new HdmiControlService(context, Collections.singletonList(HdmiDeviceInfo.DEVICE_TV),
                        audioFramework.getAudioManager(),
                        audioFramework.getAudioDeviceVolumeManager()) {
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

                    @Override
                    protected void sendBroadcastAsUser(@RequiresPermission Intent intent) {
                        // do nothing
                    }
                };

        mHdmiControlService.setIoLooper(mMyLooper);
        mHdmiControlService.setHdmiCecConfig(new FakeHdmiCecConfig(context));
        mHdmiControlService.setDeviceConfig(new FakeDeviceConfigWrapper());
        mNativeWrapper = new FakeNativeWrapper();
        mHdmiCecController = HdmiCecController.createWithNativeWrapper(
                mHdmiControlService, mNativeWrapper, mHdmiControlService.getAtomWriter());
        mHdmiControlService.setCecController(mHdmiCecController);
        mHdmiControlService.setHdmiMhlController(HdmiMhlControllerStub.create(mHdmiControlService));
        mHdmiControlService.initService();
        mHdmiControlService.onBootPhase(PHASE_SYSTEM_SERVICES_READY);
        mPowerManager = new FakePowerManagerWrapper(context);
        mHdmiControlService.setPowerManager(mPowerManager);
        mNativeWrapper.setPhysicalAddress(0x0000);
        mTestLooper.dispatchAll();
        mHdmiCecLocalDeviceTv = mHdmiControlService.tv();
        mTvLogicalAddress = mHdmiCecLocalDeviceTv.getDeviceInfo().getLogicalAddress();
        mNativeWrapper.clearResultMessages();
    }

    @Test
    public void noResponse_queryAgainOnce_emptyResult() {
        RequestSadAction action = new RequestSadAction(mHdmiCecLocalDeviceTv, ADDR_AUDIO_SYSTEM,
                mCallback);
        action.start();
        mTestLooper.dispatchAll();
        assertThat(mSupportedSads).isNull();

        HdmiCecMessage expected1 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_1.stream().mapToInt(i -> i).toArray());
        assertThat(mNativeWrapper.getResultMessages()).contains(expected1);
        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(expected1);
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();

        assertThat(mSupportedSads).isNotNull();
        assertThat(mSupportedSads.size()).isEqualTo(0);

        HdmiCecMessage expected2 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_2.stream().mapToInt(i -> i).toArray());
        HdmiCecMessage expected3 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_3.stream().mapToInt(i -> i).toArray());
        HdmiCecMessage expected4 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_4.stream().mapToInt(i -> i).toArray());

        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(expected2);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(expected3);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(expected4);
        assertThat(mSupportedSads.size()).isEqualTo(0);
    }

    @Test
    public void unrecognizedOpcode_dontQueryAgain_emptyResult() {
        RequestSadAction action = new RequestSadAction(mHdmiCecLocalDeviceTv, ADDR_AUDIO_SYSTEM,
                mCallback);
        action.start();
        mTestLooper.dispatchAll();
        assertThat(mSupportedSads).isNull();

        HdmiCecMessage unrecognizedOpcode = HdmiCecMessageBuilder.buildFeatureAbortCommand(
                Constants.ADDR_AUDIO_SYSTEM, mTvLogicalAddress,
                Constants.MESSAGE_REQUEST_SHORT_AUDIO_DESCRIPTOR,
                Constants.ABORT_UNRECOGNIZED_OPCODE);

        HdmiCecMessage expected1 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_1.stream().mapToInt(i -> i).toArray());
        assertThat(mNativeWrapper.getResultMessages()).contains(expected1);
        action.processCommand(unrecognizedOpcode);
        mTestLooper.dispatchAll();

        assertThat(mSupportedSads).isNotNull();
        assertThat(mSupportedSads.size()).isEqualTo(0);

        HdmiCecMessage expected2 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_2.stream().mapToInt(i -> i).toArray());
        HdmiCecMessage expected3 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_3.stream().mapToInt(i -> i).toArray());
        HdmiCecMessage expected4 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_4.stream().mapToInt(i -> i).toArray());

        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(expected2);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(expected3);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(expected4);
        assertThat(mSupportedSads.size()).isEqualTo(0);
    }

    @Test
    public void featureAbort_dontQueryAgain_emptyResult() {
        RequestSadAction action = new RequestSadAction(mHdmiCecLocalDeviceTv, ADDR_AUDIO_SYSTEM,
                mCallback);
        action.start();
        mTestLooper.dispatchAll();
        HdmiCecMessage featureAbort = HdmiCecMessageBuilder.buildFeatureAbortCommand(
                Constants.ADDR_AUDIO_SYSTEM, mTvLogicalAddress,
                Constants.MESSAGE_REQUEST_SHORT_AUDIO_DESCRIPTOR,
                Constants.ABORT_INVALID_OPERAND);

        HdmiCecMessage expected1 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_1.stream().mapToInt(i -> i).toArray());
        assertThat(mNativeWrapper.getResultMessages()).contains(expected1);
        mNativeWrapper.clearResultMessages();
        action.processCommand(featureAbort);
        mTestLooper.dispatchAll();

        HdmiCecMessage expected2 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_2.stream().mapToInt(i -> i).toArray());
        assertThat(mNativeWrapper.getResultMessages()).contains(expected2);
        mNativeWrapper.clearResultMessages();
        action.processCommand(featureAbort);
        mTestLooper.dispatchAll();

        HdmiCecMessage expected3 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_3.stream().mapToInt(i -> i).toArray());
        assertThat(mNativeWrapper.getResultMessages()).contains(expected3);
        mNativeWrapper.clearResultMessages();
        action.processCommand(featureAbort);
        mTestLooper.dispatchAll();

        HdmiCecMessage expected4 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_4.stream().mapToInt(i -> i).toArray());
        assertThat(mNativeWrapper.getResultMessages()).contains(expected4);
        action.processCommand(featureAbort);
        mTestLooper.dispatchAll();

        assertThat(mSupportedSads.size()).isEqualTo(0);
    }

    @Test
    public void allSupported_completeResult() {
        RequestSadAction action = new RequestSadAction(mHdmiCecLocalDeviceTv, ADDR_AUDIO_SYSTEM,
                mCallback);
        action.start();
        mTestLooper.dispatchAll();

        HdmiCecMessage expected1 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_1.stream().mapToInt(i -> i).toArray());
        byte[] sadsToRespond_1 = new byte[]{
                0x0F, 0x18, 0x4A,
                0x17, 0x64, 0x5A,
                0x1F, 0x4B, 0x00,
                0x27, 0x20, 0x0A};
        HdmiCecMessage response1 = HdmiCecMessageBuilder.buildReportShortAudioDescriptor(
                Constants.ADDR_AUDIO_SYSTEM, mTvLogicalAddress, sadsToRespond_1);
        assertThat(mNativeWrapper.getResultMessages()).contains(expected1);
        mNativeWrapper.clearResultMessages();
        action.processCommand(response1);
        mTestLooper.dispatchAll();

        HdmiCecMessage expected2 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_2.stream().mapToInt(i -> i).toArray());
        byte[] sadsToRespond_2 = new byte[]{
                0x2F, 0x18, 0x4A,
                0x37, 0x64, 0x5A,
                0x3F, 0x4B, 0x00,
                0x47, 0x20, 0x0A};
        HdmiCecMessage response2 = HdmiCecMessageBuilder.buildReportShortAudioDescriptor(
                Constants.ADDR_AUDIO_SYSTEM, mTvLogicalAddress, sadsToRespond_2);
        assertThat(mNativeWrapper.getResultMessages()).contains(expected2);
        mNativeWrapper.clearResultMessages();
        action.processCommand(response2);
        mTestLooper.dispatchAll();

        HdmiCecMessage expected3 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_3.stream().mapToInt(i -> i).toArray());
        byte[] sadsToRespond_3 = new byte[]{
                0x4F, 0x18, 0x4A,
                0x57, 0x64, 0x5A,
                0x5F, 0x4B, 0x00,
                0x67, 0x20, 0x0A};
        HdmiCecMessage response3 = HdmiCecMessageBuilder.buildReportShortAudioDescriptor(
                Constants.ADDR_AUDIO_SYSTEM, mTvLogicalAddress, sadsToRespond_3);
        assertThat(mNativeWrapper.getResultMessages()).contains(expected3);
        mNativeWrapper.clearResultMessages();
        action.processCommand(response3);
        mTestLooper.dispatchAll();

        HdmiCecMessage expected4 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_4.stream().mapToInt(i -> i).toArray());
        byte[] sadsToRespond_4 = new byte[]{
                0x6F, 0x18, 0x4A,
                0x77, 0x64, 0x5A,
                0x7F, 0x4B, 0x00};
        HdmiCecMessage response4 = HdmiCecMessageBuilder.buildReportShortAudioDescriptor(
                Constants.ADDR_AUDIO_SYSTEM, mTvLogicalAddress, sadsToRespond_4);
        assertThat(mNativeWrapper.getResultMessages()).contains(expected4);
        action.processCommand(response4);
        mTestLooper.dispatchAll();

        assertThat(mSupportedSads.size()).isEqualTo(15);
        assertThat(Arrays.equals(sadsToRespond_1,
                concatenateSads(mSupportedSads.subList(0, 4)))).isTrue();
        assertThat(Arrays.equals(sadsToRespond_2,
                concatenateSads(mSupportedSads.subList(4, 8)))).isTrue();
        assertThat(Arrays.equals(sadsToRespond_3,
                concatenateSads(mSupportedSads.subList(8, 12)))).isTrue();
        assertThat(Arrays.equals(sadsToRespond_4,
                concatenateSads(mSupportedSads.subList(12, 15)))).isTrue();
    }

    @Test
    public void subsetSupported_subsetResult() {
        RequestSadAction action = new RequestSadAction(mHdmiCecLocalDeviceTv, ADDR_AUDIO_SYSTEM,
                mCallback);
        action.start();
        mTestLooper.dispatchAll();

        HdmiCecMessage expected1 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_1.stream().mapToInt(i -> i).toArray());
        byte[] sadsToRespond_1 = new byte[]{
                0x0F, 0x18, 0x4A,
                0x1F, 0x4B, 0x00,
                0x27, 0x20, 0x0A};
        HdmiCecMessage response1 = HdmiCecMessageBuilder.buildReportShortAudioDescriptor(
                Constants.ADDR_AUDIO_SYSTEM, mTvLogicalAddress, sadsToRespond_1);
        assertThat(mNativeWrapper.getResultMessages()).contains(expected1);
        mNativeWrapper.clearResultMessages();
        action.processCommand(response1);
        mTestLooper.dispatchAll();

        HdmiCecMessage expected2 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_2.stream().mapToInt(i -> i).toArray());
        byte[] sadsToRespond_2 = new byte[]{
                0x2F, 0x20, 0x0A};
        HdmiCecMessage response2 = HdmiCecMessageBuilder.buildReportShortAudioDescriptor(
                Constants.ADDR_AUDIO_SYSTEM, mTvLogicalAddress, sadsToRespond_2);
        assertThat(mNativeWrapper.getResultMessages()).contains(expected2);
        mNativeWrapper.clearResultMessages();
        action.processCommand(response2);
        mTestLooper.dispatchAll();

        HdmiCecMessage expected3 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_3.stream().mapToInt(i -> i).toArray());
        byte[] sadsToRespond_3 = new byte[]{
                0x4F, 0x18, 0x4A,
                0x57, 0x64, 0x5A,
                0x5F, 0x4B, 0x00,
                0x67, 0x20, 0x0A};
        HdmiCecMessage response3 = HdmiCecMessageBuilder.buildReportShortAudioDescriptor(
                Constants.ADDR_AUDIO_SYSTEM, mTvLogicalAddress, sadsToRespond_3);
        assertThat(mNativeWrapper.getResultMessages()).contains(expected3);
        mNativeWrapper.clearResultMessages();
        action.processCommand(response3);
        mTestLooper.dispatchAll();

        HdmiCecMessage expected4 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_4.stream().mapToInt(i -> i).toArray());
        byte[] sadsToRespond_4 = new byte[]{
                0x7F, 0x4B, 0x00};
        HdmiCecMessage response4 = HdmiCecMessageBuilder.buildReportShortAudioDescriptor(
                Constants.ADDR_AUDIO_SYSTEM, mTvLogicalAddress, sadsToRespond_4);
        assertThat(mNativeWrapper.getResultMessages()).contains(expected4);
        action.processCommand(response4);
        mTestLooper.dispatchAll();

        assertThat(mSupportedSads.size()).isEqualTo(9);
        assertThat(Arrays.equals(sadsToRespond_1,
                concatenateSads(mSupportedSads.subList(0, 3)))).isTrue();
        assertThat(Arrays.equals(sadsToRespond_2,
                concatenateSads(mSupportedSads.subList(3, 4)))).isTrue();
        assertThat(Arrays.equals(sadsToRespond_3,
                concatenateSads(mSupportedSads.subList(4, 8)))).isTrue();
        assertThat(Arrays.equals(sadsToRespond_4,
                concatenateSads(mSupportedSads.subList(8, 9)))).isTrue();
    }

    @Test
    public void invalidCodecs_emptyResults() {
        RequestSadAction action = new RequestSadAction(mHdmiCecLocalDeviceTv, ADDR_AUDIO_SYSTEM,
                mCallback);
        action.start();
        mTestLooper.dispatchAll();

        HdmiCecMessage expected1 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_1.stream().mapToInt(i -> i).toArray());
        // Negative values require explicit casting
        byte[] sadsToRespond_1 = new byte[]{
                (byte) 0x80, 0x18, 0x4A,
                (byte) 0x82, 0x64, 0x5A,
                (byte) 0x87, 0x4B, 0x00,
                (byte) 0x8F, 0x20, 0x0A};
        HdmiCecMessage response1 = HdmiCecMessageBuilder.buildReportShortAudioDescriptor(
                Constants.ADDR_AUDIO_SYSTEM, mTvLogicalAddress, sadsToRespond_1);
        assertThat(mNativeWrapper.getResultMessages()).contains(expected1);
        mNativeWrapper.clearResultMessages();
        action.processCommand(response1);
        mTestLooper.dispatchAll();

        HdmiCecMessage expected2 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_2.stream().mapToInt(i -> i).toArray());
        byte[] sadsToRespond_2 = new byte[]{
                (byte) 0x92, 0x18, 0x4A,
                (byte) 0x98, 0x64, 0x5A,
                (byte) 0xA1, 0x4B, 0x00,
                (byte) 0xA4, 0x20, 0x0A};
        HdmiCecMessage response2 = HdmiCecMessageBuilder.buildReportShortAudioDescriptor(
                Constants.ADDR_AUDIO_SYSTEM, mTvLogicalAddress, sadsToRespond_2);
        assertThat(mNativeWrapper.getResultMessages()).contains(expected2);
        mNativeWrapper.clearResultMessages();
        action.processCommand(response2);
        mTestLooper.dispatchAll();

        HdmiCecMessage expected3 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_3.stream().mapToInt(i -> i).toArray());
        byte[] sadsToRespond_3 = new byte[]{
                (byte) 0xB3, 0x18, 0x4A,
                (byte) 0xBA, 0x64, 0x5A,
                (byte) 0xCB, 0x4B, 0x00,
                (byte) 0xCF, 0x20, 0x0A};
        HdmiCecMessage response3 = HdmiCecMessageBuilder.buildReportShortAudioDescriptor(
                Constants.ADDR_AUDIO_SYSTEM, mTvLogicalAddress, sadsToRespond_3);
        assertThat(mNativeWrapper.getResultMessages()).contains(expected3);
        mNativeWrapper.clearResultMessages();
        action.processCommand(response3);
        mTestLooper.dispatchAll();

        HdmiCecMessage expected4 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_4.stream().mapToInt(i -> i).toArray());
        byte[] sadsToRespond_4 = new byte[]{
                (byte) 0xF0, 0x18, 0x4A,
                (byte) 0xF1, 0x64, 0x5A,
                (byte) 0xF3, 0x4B, 0x00};
        HdmiCecMessage response4 = HdmiCecMessageBuilder.buildReportShortAudioDescriptor(
                Constants.ADDR_AUDIO_SYSTEM, mTvLogicalAddress, sadsToRespond_4);
        assertThat(mNativeWrapper.getResultMessages()).contains(expected4);
        action.processCommand(response4);
        mTestLooper.dispatchAll();

        assertThat(mSupportedSads.size()).isEqualTo(0);
    }

    @Test
    public void invalidMessageLength_queryAgainOnce() {
        RequestSadAction action = new RequestSadAction(mHdmiCecLocalDeviceTv, ADDR_AUDIO_SYSTEM,
                mCallback);
        action.start();
        mTestLooper.dispatchAll();
        assertThat(mSupportedSads).isNull();

        HdmiCecMessage expected1 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_1.stream().mapToInt(i -> i).toArray());
        byte[] sadsToRespond_1 = new byte[]{
                0x0F, 0x18,
                0x17, 0x64, 0x5A,
                0x1F, 0x4B, 0x00,
                0x27, 0x20, 0x0A};
        HdmiCecMessage response1 = HdmiCecMessageBuilder.buildReportShortAudioDescriptor(
                Constants.ADDR_AUDIO_SYSTEM, mTvLogicalAddress, sadsToRespond_1);
        assertThat(mNativeWrapper.getResultMessages()).contains(expected1);
        mNativeWrapper.clearResultMessages();
        action.processCommand(response1);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();
        assertThat(mNativeWrapper.getResultMessages()).contains(expected1);
        mNativeWrapper.clearResultMessages();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();

        assertThat(mSupportedSads).isNotNull();
        assertThat(mSupportedSads.size()).isEqualTo(0);

        HdmiCecMessage expected2 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_2.stream().mapToInt(i -> i).toArray());
        HdmiCecMessage expected3 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_3.stream().mapToInt(i -> i).toArray());
        HdmiCecMessage expected4 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                CODECS_TO_QUERY_4.stream().mapToInt(i -> i).toArray());

        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();
        mTestLooper.moveTimeForward(TIMEOUT_MS);
        mTestLooper.dispatchAll();

        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(expected2);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(expected3);
        assertThat(mNativeWrapper.getResultMessages()).doesNotContain(expected4);
        assertThat(mSupportedSads.size()).isEqualTo(0);
    }

    @Test
    public void selectedSads_allSupported_completeResult() {
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_QUERY_SAD_MPEG1,
                HdmiControlManager.QUERY_SAD_DISABLED);
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_QUERY_SAD_ONEBITAUDIO,
                HdmiControlManager.QUERY_SAD_DISABLED);
        mHdmiControlService.getHdmiCecConfig().setIntValue(
                HdmiControlManager.CEC_SETTING_NAME_QUERY_SAD_WMAPRO,
                HdmiControlManager.QUERY_SAD_DISABLED);
        RequestSadAction action = new RequestSadAction(mHdmiCecLocalDeviceTv, ADDR_AUDIO_SYSTEM,
                mCallback);
        action.start();
        mTestLooper.dispatchAll();

        HdmiCecMessage expected1 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                new int[]{Constants.AUDIO_CODEC_LPCM, Constants.AUDIO_CODEC_DD,
                        Constants.AUDIO_CODEC_MP3, Constants.AUDIO_CODEC_MPEG2});
        byte[] sadsToRespond_1 = new byte[]{
                0x0F, 0x18, 0x4A,
                0x17, 0x64, 0x5A,
                0x27, 0x20, 0x0A,
                0x2F, 0x18, 0x4A};
        HdmiCecMessage response1 = HdmiCecMessageBuilder.buildReportShortAudioDescriptor(
                Constants.ADDR_AUDIO_SYSTEM, mTvLogicalAddress, sadsToRespond_1);
        assertThat(mNativeWrapper.getResultMessages()).contains(expected1);
        mNativeWrapper.clearResultMessages();
        action.processCommand(response1);
        mTestLooper.dispatchAll();

        HdmiCecMessage expected2 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                new int[]{Constants.AUDIO_CODEC_AAC, Constants.AUDIO_CODEC_DTS,
                        Constants.AUDIO_CODEC_ATRAC, Constants.AUDIO_CODEC_DDP});
        byte[] sadsToRespond_2 = new byte[]{
                0x37, 0x64, 0x5A,
                0x3F, 0x4B, 0x00,
                0x47, 0x20, 0x0A,
                0x4F, 0x18, 0x4A};
        HdmiCecMessage response2 = HdmiCecMessageBuilder.buildReportShortAudioDescriptor(
                Constants.ADDR_AUDIO_SYSTEM, mTvLogicalAddress, sadsToRespond_2);
        assertThat(mNativeWrapper.getResultMessages()).contains(expected2);
        mNativeWrapper.clearResultMessages();
        action.processCommand(response2);
        mTestLooper.dispatchAll();

        HdmiCecMessage expected3 = HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                mTvLogicalAddress, Constants.ADDR_AUDIO_SYSTEM,
                new int[]{Constants.AUDIO_CODEC_DTSHD, Constants.AUDIO_CODEC_TRUEHD,
                        Constants.AUDIO_CODEC_DST, Constants.AUDIO_CODEC_MAX});
        byte[] sadsToRespond_3 = new byte[]{
                0x5F, 0x4B, 0x00,
                0x67, 0x20, 0x0A,
                0x6F, 0x18, 0x4A,
                0x7F, 0x4B, 0x00};
        HdmiCecMessage response3 = HdmiCecMessageBuilder.buildReportShortAudioDescriptor(
                Constants.ADDR_AUDIO_SYSTEM, mTvLogicalAddress, sadsToRespond_3);
        assertThat(mNativeWrapper.getResultMessages()).contains(expected3);
        mNativeWrapper.clearResultMessages();
        action.processCommand(response3);
        mTestLooper.dispatchAll();

        assertThat(mSupportedSads.size()).isEqualTo(12);
        assertThat(Arrays.equals(sadsToRespond_1,
                concatenateSads(mSupportedSads.subList(0, 4)))).isTrue();
        assertThat(Arrays.equals(sadsToRespond_2,
                concatenateSads(mSupportedSads.subList(4, 8)))).isTrue();
        assertThat(Arrays.equals(sadsToRespond_3,
                concatenateSads(mSupportedSads.subList(8, 12)))).isTrue();
    }
}
