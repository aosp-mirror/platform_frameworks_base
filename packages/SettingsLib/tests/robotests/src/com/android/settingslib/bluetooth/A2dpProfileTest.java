/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.settingslib.bluetooth;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.res.Resources;

import com.android.settingslib.R;
import com.android.settingslib.wrapper.BluetoothA2dpWrapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class A2dpProfileTest {

    @Mock Context mContext;
    @Mock LocalBluetoothAdapter mAdapter;
    @Mock CachedBluetoothDeviceManager mDeviceManager;
    @Mock LocalBluetoothProfileManager mProfileManager;
    @Mock BluetoothDevice mDevice;
    @Mock BluetoothA2dp mBluetoothA2dp;
    @Mock BluetoothA2dpWrapper mBluetoothA2dpWrapper;
    BluetoothProfile.ServiceListener mServiceListener;

    A2dpProfile mProfile;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        // Capture the A2dpServiceListener our A2dpProfile will pass during its constructor, so that
        // we can call its onServiceConnected method and get it to use our mock BluetoothA2dp
        // object.
        doAnswer((invocation) -> {
            mServiceListener = (BluetoothProfile.ServiceListener) invocation.getArguments()[1];
            return null;
        }).when(mAdapter).getProfileProxy(any(Context.class), any(), eq(BluetoothProfile.A2DP));

        mProfile = new A2dpProfile(mContext, mAdapter, mDeviceManager, mProfileManager);
        mServiceListener.onServiceConnected(BluetoothProfile.A2DP, mBluetoothA2dp);
        mProfile.setBluetoothA2dpWrapper(mBluetoothA2dpWrapper);
    }

    @Test
    public void supportsHighQualityAudio() {
        when(mBluetoothA2dpWrapper.supportsOptionalCodecs(any())).thenReturn(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED);
        assertThat(mProfile.supportsHighQualityAudio(mDevice)).isTrue();

        when(mBluetoothA2dpWrapper.supportsOptionalCodecs(any())).thenReturn(
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED);
        assertThat(mProfile.supportsHighQualityAudio(mDevice)).isFalse();

        when(mBluetoothA2dpWrapper.supportsOptionalCodecs(any())).thenReturn(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN);
        assertThat(mProfile.supportsHighQualityAudio(mDevice)).isFalse();
    }

    @Test
    public void isHighQualityAudioEnabled() {
        when(mBluetoothA2dpWrapper.getOptionalCodecsEnabled(any())).thenReturn(
                BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED);
        assertThat(mProfile.isHighQualityAudioEnabled(mDevice)).isTrue();

        when(mBluetoothA2dpWrapper.getOptionalCodecsEnabled(any())).thenReturn(
                BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED);
        assertThat(mProfile.isHighQualityAudioEnabled(mDevice)).isFalse();

        // If we don't have a stored pref for whether optional codecs should be enabled or not,
        // then isHighQualityAudioEnabled() should return true or false based on whether optional
        // codecs are supported. If the device is connected then we should ask it directly, but if
        // the device isn't connected then rely on the stored pref about such support.
        when(mBluetoothA2dpWrapper.getOptionalCodecsEnabled(any())).thenReturn(
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN);
        when(mBluetoothA2dp.getConnectionState(any())).thenReturn(
                BluetoothProfile.STATE_DISCONNECTED);

        when(mBluetoothA2dpWrapper.supportsOptionalCodecs(any())).thenReturn(
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED);
        assertThat(mProfile.isHighQualityAudioEnabled(mDevice)).isFalse();

        when(mBluetoothA2dpWrapper.supportsOptionalCodecs(any())).thenReturn(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED);
        assertThat(mProfile.isHighQualityAudioEnabled(mDevice)).isTrue();

        when(mBluetoothA2dp.getConnectionState(any())).thenReturn(
                BluetoothProfile.STATE_CONNECTED);
        BluetoothCodecStatus status = mock(BluetoothCodecStatus.class);
        when(mBluetoothA2dpWrapper.getCodecStatus(mDevice)).thenReturn(status);
        BluetoothCodecConfig config = mock(BluetoothCodecConfig.class);
        when(status.getCodecConfig()).thenReturn(config);
        when(config.isMandatoryCodec()).thenReturn(false);
        assertThat(mProfile.isHighQualityAudioEnabled(mDevice)).isTrue();
        when(config.isMandatoryCodec()).thenReturn(true);
        assertThat(mProfile.isHighQualityAudioEnabled(mDevice)).isFalse();
    }

    // Strings to use in fake resource lookups.
    private static String KNOWN_CODEC_LABEL = "Use high quality audio: %1$s";
    private static String UNKNOWN_CODEC_LABEL = "Use high quality audio";
    private static String[] CODEC_NAMES =
            new String[] { "Default", "SBC", "AAC", "aptX", "aptX HD", "LDAC" };

    /**
     * Helper for setting up several tests of getHighQualityAudioOptionLabel
     */
    private void setupLabelTest() {
        // SettingsLib doesn't have string resource lookup working for robotests, so fake our own
        // string loading.
        when(mContext.getString(eq(R.string.bluetooth_profile_a2dp_high_quality),
                any(String.class))).thenAnswer((invocation) -> {
            return String.format(KNOWN_CODEC_LABEL, invocation.getArguments()[1]);
        });
        when(mContext.getString(eq(R.string.bluetooth_profile_a2dp_high_quality_unknown_codec)))
                .thenReturn(UNKNOWN_CODEC_LABEL);

        final Resources res = mock(Resources.class);
        when(mContext.getResources()).thenReturn(res);
        when(res.getStringArray(eq(R.array.bluetooth_a2dp_codec_titles)))
                .thenReturn(CODEC_NAMES);

        // Most tests want to simulate optional codecs being supported by the device, so do that
        // by default here.
        when(mBluetoothA2dpWrapper.supportsOptionalCodecs(any())).thenReturn(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED);
    }

    @Test
    public void getLableCodecsNotSupported() {
        setupLabelTest();
        when(mBluetoothA2dpWrapper.supportsOptionalCodecs(any())).thenReturn(
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED);
        assertThat(mProfile.getHighQualityAudioOptionLabel(mDevice)).isEqualTo(UNKNOWN_CODEC_LABEL);
    }

    @Test
    public void getLabelDeviceDisconnected() {
        setupLabelTest();
        when(mBluetoothA2dp.getConnectionState(any())).thenReturn(
                BluetoothProfile.STATE_DISCONNECTED);
        assertThat(mProfile.getHighQualityAudioOptionLabel(mDevice)).isEqualTo(UNKNOWN_CODEC_LABEL);
    }

    @Test
    public void getLabelDeviceConnectedButNotHighQualityCodec() {
        setupLabelTest();
        when(mBluetoothA2dp.getConnectionState(any())).thenReturn(
                BluetoothProfile.STATE_CONNECTED);
        BluetoothCodecStatus status = mock(BluetoothCodecStatus.class);
        BluetoothCodecConfig config = mock(BluetoothCodecConfig.class);
        BluetoothCodecConfig[] configs = {config};
        when(mBluetoothA2dpWrapper.getCodecStatus(mDevice)).thenReturn(status);
        when(status.getCodecsSelectableCapabilities()).thenReturn(configs);

        when(config.isMandatoryCodec()).thenReturn(true);
        assertThat(mProfile.getHighQualityAudioOptionLabel(mDevice)).isEqualTo(UNKNOWN_CODEC_LABEL);
    }

    @Test
    public void getLabelDeviceConnectedWithHighQualityCodec() {
        setupLabelTest();
        when(mBluetoothA2dp.getConnectionState(any())).thenReturn(
                BluetoothProfile.STATE_CONNECTED);
        BluetoothCodecStatus status = mock(BluetoothCodecStatus.class);
        BluetoothCodecConfig config = mock(BluetoothCodecConfig.class);
        BluetoothCodecConfig[] configs = {config};
        when(mBluetoothA2dpWrapper.getCodecStatus(mDevice)).thenReturn(status);
        when(status.getCodecsSelectableCapabilities()).thenReturn(configs);

        when(config.isMandatoryCodec()).thenReturn(false);
        when(config.getCodecType()).thenReturn(4);
        when(config.getCodecName()).thenReturn("LDAC");
        assertThat(mProfile.getHighQualityAudioOptionLabel(mDevice)).isEqualTo(
                String.format(KNOWN_CODEC_LABEL, config.getCodecName()));
    }
}
