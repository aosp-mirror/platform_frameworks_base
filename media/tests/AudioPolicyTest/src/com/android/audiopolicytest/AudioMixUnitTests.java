/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.audiopolicytest;

import static android.media.AudioFormat.CHANNEL_OUT_MONO;
import static android.media.AudioFormat.CHANNEL_OUT_STEREO;
import static android.media.AudioFormat.ENCODING_PCM_16BIT;
import static android.media.audiopolicy.AudioMixingRule.MIX_ROLE_INJECTOR;
import static android.media.audiopolicy.AudioMixingRule.MIX_ROLE_PLAYERS;
import static android.media.audiopolicy.AudioMixingRule.RULE_MATCH_AUDIO_SESSION_ID;
import static android.media.audiopolicy.AudioMixingRule.RULE_MATCH_UID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.media.AudioFormat;
import android.media.AudioSystem;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicyConfig;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.common.testing.EqualsTester;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for AudioMix.
 *
 * Run with "atest AudioMixUnitTests".
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class AudioMixUnitTests {
    private static final AudioFormat OUTPUT_FORMAT_STEREO_44KHZ_PCM =
            new AudioFormat.Builder()
                    .setSampleRate(44000)
                    .setChannelMask(CHANNEL_OUT_STEREO)
                    .setEncoding(ENCODING_PCM_16BIT).build();
    private static final AudioFormat OUTPUT_FORMAT_MONO_16KHZ_PCM =
            new AudioFormat.Builder()
                    .setSampleRate(16000)
                    .setChannelMask(CHANNEL_OUT_MONO)
                    .setEncoding(ENCODING_PCM_16BIT).build();
    private static final AudioFormat INPUT_FORMAT_MONO_16KHZ_PCM =
            new AudioFormat.Builder()
                    .setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(ENCODING_PCM_16BIT).build();

    @Test
    public void testEquals() {
        final EqualsTester equalsTester = new EqualsTester();

        // --- Equality group 1
        final AudioMix playbackAudioMixWithSessionId42AndUid123 =
                new AudioMix.Builder(new AudioMixingRule.Builder()
                        .setTargetMixRole(MIX_ROLE_PLAYERS)
                        .addMixRule(RULE_MATCH_AUDIO_SESSION_ID, 42)
                        .addMixRule(RULE_MATCH_UID, 123).build())
                        .setFormat(OUTPUT_FORMAT_STEREO_44KHZ_PCM)
                        .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK).build();
        final AudioMix playbackAudioMixWithUid123AndSessionId42 =
                new AudioMix.Builder(new AudioMixingRule.Builder()
                        .setTargetMixRole(MIX_ROLE_PLAYERS)
                        .addMixRule(RULE_MATCH_UID, 123)
                        .addMixRule(RULE_MATCH_AUDIO_SESSION_ID, 42).build())
                        .setFormat(OUTPUT_FORMAT_STEREO_44KHZ_PCM)
                        .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK).build();
        equalsTester.addEqualityGroup(
                playbackAudioMixWithSessionId42AndUid123,
                playbackAudioMixWithUid123AndSessionId42,
                writeToAndFromParcel(playbackAudioMixWithSessionId42AndUid123),
                writeToAndFromParcel(playbackAudioMixWithUid123AndSessionId42));

        // --- Equality group 2
        final AudioMix recordingAudioMixWithSessionId42AndUid123 =
                new AudioMix.Builder(new AudioMixingRule.Builder()
                        .setTargetMixRole(MIX_ROLE_INJECTOR)
                        .addMixRule(RULE_MATCH_AUDIO_SESSION_ID, 42)
                        .addMixRule(RULE_MATCH_UID, 123).build())
                        .setFormat(INPUT_FORMAT_MONO_16KHZ_PCM)
                        .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK).build();
        final AudioMix recordingAudioMixWithUid123AndSessionId42 =
                new AudioMix.Builder(new AudioMixingRule.Builder()
                        .setTargetMixRole(MIX_ROLE_INJECTOR)
                        .addMixRule(RULE_MATCH_AUDIO_SESSION_ID, 42)
                        .addMixRule(RULE_MATCH_UID, 123).build())
                        .setFormat(INPUT_FORMAT_MONO_16KHZ_PCM)
                        .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK).build();
        equalsTester.addEqualityGroup(recordingAudioMixWithSessionId42AndUid123,
                recordingAudioMixWithUid123AndSessionId42,
                writeToAndFromParcel(recordingAudioMixWithSessionId42AndUid123),
                writeToAndFromParcel(recordingAudioMixWithUid123AndSessionId42));

        // --- Equality group 3
        final AudioMix recordingAudioMixWithSessionId42AndUid123Render =
                new AudioMix.Builder(new AudioMixingRule.Builder()
                        .setTargetMixRole(MIX_ROLE_INJECTOR)
                        .addMixRule(RULE_MATCH_AUDIO_SESSION_ID, 42)
                        .addMixRule(RULE_MATCH_UID, 123).build())
                        .setFormat(INPUT_FORMAT_MONO_16KHZ_PCM)
                        .setRouteFlags(
                                AudioMix.ROUTE_FLAG_LOOP_BACK | AudioMix.ROUTE_FLAG_RENDER).build();
        equalsTester.addEqualityGroup(recordingAudioMixWithSessionId42AndUid123Render,
                writeToAndFromParcel(recordingAudioMixWithSessionId42AndUid123Render));

        // --- Equality group 4
        final AudioMix playbackAudioMixWithUid123 =
                new AudioMix.Builder(new AudioMixingRule.Builder()
                        .setTargetMixRole(MIX_ROLE_PLAYERS)
                        .addMixRule(RULE_MATCH_UID, 123).build())
                        .setFormat(OUTPUT_FORMAT_MONO_16KHZ_PCM)
                        .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK).build();
        equalsTester.addEqualityGroup(playbackAudioMixWithUid123,
                writeToAndFromParcel(playbackAudioMixWithUid123));

        // --- Equality group 5
        final AudioMix playbackAudioMixWithUid42 =
                new AudioMix.Builder(new AudioMixingRule.Builder()
                        .setTargetMixRole(MIX_ROLE_PLAYERS)
                        .addMixRule(RULE_MATCH_UID, 42).build())
                        .setFormat(OUTPUT_FORMAT_MONO_16KHZ_PCM)
                        .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK).build();
        equalsTester.addEqualityGroup(playbackAudioMixWithUid42,
                writeToAndFromParcel(playbackAudioMixWithUid42));

        equalsTester.testEquals();
    }

    @Test
    public void buildRenderToRemoteSubmix_success() {
        final String deviceAddress = "address";
        final AudioMix audioMix = new AudioMix.Builder(new AudioMixingRule.Builder()
                .setTargetMixRole(MIX_ROLE_PLAYERS)
                .addMixRule(RULE_MATCH_UID, 42).build())
                .setFormat(OUTPUT_FORMAT_MONO_16KHZ_PCM)
                .setRouteFlags(AudioMix.ROUTE_FLAG_RENDER)
                .setDevice(AudioSystem.DEVICE_OUT_REMOTE_SUBMIX, /*address=*/deviceAddress).build();

        assertEquals(deviceAddress, audioMix.getRegistration());
        assertEquals(OUTPUT_FORMAT_MONO_16KHZ_PCM, audioMix.getFormat());
        assertEquals(AudioMix.ROUTE_FLAG_RENDER, audioMix.getRouteFlags());
    }

    @Test
    public void buildLoopbackAndRenderToRemoteSubmix_success() {
        final String deviceAddress = "address";
        final AudioMix audioMix = new AudioMix.Builder(new AudioMixingRule.Builder()
                .setTargetMixRole(MIX_ROLE_PLAYERS)
                .addMixRule(RULE_MATCH_UID, 42).build())
                .setFormat(OUTPUT_FORMAT_MONO_16KHZ_PCM)
                .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK_RENDER)
                .setDevice(AudioSystem.DEVICE_OUT_REMOTE_SUBMIX, /*address=*/deviceAddress).build();

        assertEquals(deviceAddress, audioMix.getRegistration());
        assertEquals(OUTPUT_FORMAT_MONO_16KHZ_PCM, audioMix.getFormat());
        assertEquals(AudioMix.ROUTE_FLAG_LOOP_BACK_RENDER, audioMix.getRouteFlags());
    }

    @Test
    public void buildRenderToSpeaker_success() {
        final AudioMix audioMix = new AudioMix.Builder(new AudioMixingRule.Builder()
                .setTargetMixRole(MIX_ROLE_PLAYERS)
                .addMixRule(RULE_MATCH_UID, 42).build())
                .setFormat(OUTPUT_FORMAT_MONO_16KHZ_PCM)
                .setRouteFlags(AudioMix.ROUTE_FLAG_RENDER)
                .setDevice(AudioSystem.DEVICE_OUT_SPEAKER, /*address=*/"").build();

        assertEquals(OUTPUT_FORMAT_MONO_16KHZ_PCM, audioMix.getFormat());
        assertEquals(AudioMix.ROUTE_FLAG_RENDER, audioMix.getRouteFlags());
    }

    @Test
    public void buildLoopbackForPlayerMix_success() {
        final AudioMix audioMix = new AudioMix.Builder(new AudioMixingRule.Builder()
                .setTargetMixRole(MIX_ROLE_PLAYERS)
                .addMixRule(RULE_MATCH_UID, 42).build())
                .setFormat(OUTPUT_FORMAT_MONO_16KHZ_PCM)
                .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK).build();

        assertEquals(OUTPUT_FORMAT_MONO_16KHZ_PCM, audioMix.getFormat());
        assertEquals(AudioMix.ROUTE_FLAG_LOOP_BACK, audioMix.getRouteFlags());
    }

    @Test
    public void buildLoopbackWithDevice_throws() {
        assertThrows(IllegalArgumentException.class, () -> new AudioMix.Builder(
                new AudioMixingRule.Builder()
                        .setTargetMixRole(MIX_ROLE_PLAYERS)
                        .addMixRule(RULE_MATCH_UID, 42).build())
                .setFormat(OUTPUT_FORMAT_MONO_16KHZ_PCM)
                .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK)
                .setDevice(AudioSystem.DEVICE_OUT_SPEAKER, /*address=*/"").build());
    }

    @Test
    public void buildRenderWithoutDevice_throws() {
        assertThrows(IllegalArgumentException.class, () -> new AudioMix.Builder(
                new AudioMixingRule.Builder()
                        .setTargetMixRole(MIX_ROLE_PLAYERS)
                        .addMixRule(RULE_MATCH_UID, 42).build())
                .setFormat(OUTPUT_FORMAT_MONO_16KHZ_PCM)
                .setRouteFlags(AudioMix.ROUTE_FLAG_RENDER).build());
    }



    private static AudioMix writeToAndFromParcel(AudioMix audioMix) {
        AudioPolicyConfig apc = new AudioPolicyConfig(new ArrayList<>(List.of(audioMix)));
        Parcel parcel = Parcel.obtain();
        apc.writeToParcel(parcel, /*flags=*/0);
        parcel.setDataPosition(0);
        AudioMix unmarshalledMix =
                AudioPolicyConfig.CREATOR.createFromParcel(parcel).getMixes().get(0);
        parcel.recycle();
        return unmarshalledMix;
    }
}
