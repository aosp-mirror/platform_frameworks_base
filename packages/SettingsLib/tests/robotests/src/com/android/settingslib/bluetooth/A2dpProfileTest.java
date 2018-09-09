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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.res.Resources;

import com.android.settingslib.R;
import com.android.settingslib.testutils.shadow.ShadowBluetoothAdapter;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowBluetoothAdapter.class})
public class A2dpProfileTest {

    @Mock
    private Context mContext;
    @Mock
    private CachedBluetoothDeviceManager mDeviceManager;
    @Mock
    private LocalBluetoothProfileManager mProfileManager;
    @Mock
    private BluetoothDevice mDevice;
    @Mock
    private BluetoothA2dp mBluetoothA2dp;
    private BluetoothProfile.ServiceListener mServiceListener;

    private A2dpProfile mProfile;
    private ShadowBluetoothAdapter mShadowBluetoothAdapter;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mShadowBluetoothAdapter = Shadow.extract(BluetoothAdapter.getDefaultAdapter());
        mProfile = new A2dpProfile(mContext, mDeviceManager, mProfileManager);
        mServiceListener = mShadowBluetoothAdapter.getServiceListener();
        mServiceListener.onServiceConnected(BluetoothProfile.A2DP, mBluetoothA2dp);
    }

    @Test
    public void supportsHighQualityAudio() {
        when(mBluetoothA2dp.supportsOptionalCodecs(any())).thenReturn(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED);
        assertThat(mProfile.supportsHighQualityAudio(mDevice)).isTrue();

        when(mBluetoothA2dp.supportsOptionalCodecs(any())).thenReturn(
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED);
        assertThat(mProfile.supportsHighQualityAudio(mDevice)).isFalse();

        when(mBluetoothA2dp.supportsOptionalCodecs(any())).thenReturn(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORT_UNKNOWN);
        assertThat(mProfile.supportsHighQualityAudio(mDevice)).isFalse();
    }

    @Test
    public void isHighQualityAudioEnabled() {
        when(mBluetoothA2dp.getOptionalCodecsEnabled(any())).thenReturn(
                BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED);
        assertThat(mProfile.isHighQualityAudioEnabled(mDevice)).isTrue();

        when(mBluetoothA2dp.getOptionalCodecsEnabled(any())).thenReturn(
                BluetoothA2dp.OPTIONAL_CODECS_PREF_DISABLED);
        assertThat(mProfile.isHighQualityAudioEnabled(mDevice)).isFalse();

        // If we don't have a stored pref for whether optional codecs should be enabled or not,
        // then isHighQualityAudioEnabled() should return true or false based on whether optional
        // codecs are supported. If the device is connected then we should ask it directly, but if
        // the device isn't connected then rely on the stored pref about such support.
        when(mBluetoothA2dp.getOptionalCodecsEnabled(any())).thenReturn(
                BluetoothA2dp.OPTIONAL_CODECS_PREF_UNKNOWN);
        when(mBluetoothA2dp.getConnectionState(any())).thenReturn(
                BluetoothProfile.STATE_DISCONNECTED);

        when(mBluetoothA2dp.supportsOptionalCodecs(any())).thenReturn(
                BluetoothA2dp.OPTIONAL_CODECS_NOT_SUPPORTED);
        assertThat(mProfile.isHighQualityAudioEnabled(mDevice)).isFalse();

        when(mBluetoothA2dp.supportsOptionalCodecs(any())).thenReturn(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED);
        assertThat(mProfile.isHighQualityAudioEnabled(mDevice)).isTrue();

        when(mBluetoothA2dp.getConnectionState(any())).thenReturn(
                BluetoothProfile.STATE_CONNECTED);
        BluetoothCodecStatus status = mock(BluetoothCodecStatus.class);
        when(mBluetoothA2dp.getCodecStatus(mDevice)).thenReturn(status);
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
            new String[]{"Default", "SBC", "AAC", "aptX", "aptX HD", "LDAC"};

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
        when(mBluetoothA2dp.supportsOptionalCodecs(any())).thenReturn(
                BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED);
    }

    @Test
    public void getLableCodecsNotSupported() {
        setupLabelTest();
        when(mBluetoothA2dp.supportsOptionalCodecs(any())).thenReturn(
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
        when(mBluetoothA2dp.getCodecStatus(mDevice)).thenReturn(status);
        when(status.getCodecsSelectableCapabilities()).thenReturn(configs);

        when(config.isMandatoryCodec()).thenReturn(true);
        when(config.getCodecName()).thenReturn("SBC");
        assertThat(mProfile.getHighQualityAudioOptionLabel(mDevice)).isEqualTo(
                String.format(KNOWN_CODEC_LABEL, config.getCodecName()));
    }

    @Test
    public void getLabelDeviceConnectedWithHighQualityCodec() {
        setupLabelTest();
        when(mBluetoothA2dp.getConnectionState(any())).thenReturn(
                BluetoothProfile.STATE_CONNECTED);
        BluetoothCodecStatus status = mock(BluetoothCodecStatus.class);
        BluetoothCodecConfig config = mock(BluetoothCodecConfig.class);
        BluetoothCodecConfig[] configs = {config};
        when(mBluetoothA2dp.getCodecStatus(mDevice)).thenReturn(status);
        when(status.getCodecsSelectableCapabilities()).thenReturn(configs);

        when(config.isMandatoryCodec()).thenReturn(false);
        when(config.getCodecType()).thenReturn(4);
        when(config.getCodecName()).thenReturn("LDAC");
        assertThat(mProfile.getHighQualityAudioOptionLabel(mDevice)).isEqualTo(
                String.format(KNOWN_CODEC_LABEL, config.getCodecName()));
    }
}
