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

package android.companion.virtual.audio;

import static android.media.AudioFormat.CHANNEL_IN_MONO;
import static android.media.AudioFormat.CHANNEL_OUT_MONO;
import static android.media.AudioFormat.ENCODING_PCM_16BIT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertThrows;

import android.companion.virtual.audio.VirtualAudioDevice.AudioConfigurationChangeCallback;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioFormat;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecordingConfiguration;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class VirtualAudioSessionTest {

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();
    @Mock
    private AudioConfigurationChangeCallback mCallback;
    private static final int APP_UID = 100;
    private static final int APP_UID2 = 200;
    private static final AudioFormat AUDIO_CAPTURE_FORMAT =
            new AudioFormat.Builder()
                    .setSampleRate(48000)
                    .setEncoding(ENCODING_PCM_16BIT)
                    .setChannelMask(CHANNEL_IN_MONO)
                    .build();
    private static final AudioFormat AUDIO_INJECT_FORMAT =
            new AudioFormat.Builder()
                    .setSampleRate(48000)
                    .setEncoding(ENCODING_PCM_16BIT)
                    .setChannelMask(CHANNEL_OUT_MONO)
                    .build();
    private Context mContext;
    private VirtualAudioSession mVirtualAudioSession;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = Mockito.spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));
        mVirtualAudioSession = new VirtualAudioSession(
                mContext, mCallback, /* executor= */ null);
    }

    @Test
    public void startAudioCapture_isSuccessful() {
        AudioCapture audioCapture = mVirtualAudioSession.startAudioCapture(AUDIO_CAPTURE_FORMAT);

        assertThat(audioCapture).isNotNull();
        assertThat(mVirtualAudioSession.getAudioCapture()).isEqualTo(audioCapture);
    }

    @Test
    public void startAudioCapture_audioCaptureAlreadyStarted_throws() {
        mVirtualAudioSession.startAudioCapture(AUDIO_CAPTURE_FORMAT);

        assertThrows(IllegalStateException.class,
                () -> mVirtualAudioSession.startAudioCapture(AUDIO_CAPTURE_FORMAT));
    }

    @Test
    public void startAudioInjection_isSuccessful() {
        AudioInjection audioInjection = mVirtualAudioSession.startAudioInjection(
                AUDIO_INJECT_FORMAT);

        assertThat(audioInjection).isNotNull();
        assertThat(mVirtualAudioSession.getAudioInjection()).isEqualTo(audioInjection);
    }

    @Test
    public void startAudioInjection_audioInjectionAlreadyStarted_throws() {
        mVirtualAudioSession.startAudioInjection(AUDIO_INJECT_FORMAT);

        assertThrows(IllegalStateException.class,
                () -> mVirtualAudioSession.startAudioInjection(AUDIO_INJECT_FORMAT));
    }

    @Test
    public void onAppsNeedingAudioRoutingChanged_neverStartAudioCaptureOrInjection_throws() {
        int[] uids = new int[]{APP_UID};

        assertThrows(IllegalStateException.class,
                () -> mVirtualAudioSession.onAppsNeedingAudioRoutingChanged(uids));
    }

    @Test
    public void onAppsNeedingAudioRoutingChanged_cachesReroutedApps() {
        mVirtualAudioSession.startAudioCapture(AUDIO_CAPTURE_FORMAT);
        mVirtualAudioSession.startAudioInjection(AUDIO_INJECT_FORMAT);
        int[] appUids = new int[]{APP_UID};

        mVirtualAudioSession.onAppsNeedingAudioRoutingChanged(appUids);

        assertThat(Arrays.equals(mVirtualAudioSession.getReroutedAppUids().toArray(),
                appUids)).isTrue();
    }

    @Test
    public void onAppsNeedingAudioRoutingChanged_receiveManyTimes_reroutedAppsSizeIsCorrect() {
        mVirtualAudioSession.startAudioCapture(AUDIO_CAPTURE_FORMAT);
        mVirtualAudioSession.startAudioInjection(AUDIO_INJECT_FORMAT);
        int[] appUids = new int[]{APP_UID, APP_UID2};

        mVirtualAudioSession.onAppsNeedingAudioRoutingChanged(new int[]{1234});
        mVirtualAudioSession.onAppsNeedingAudioRoutingChanged(new int[]{5678});
        mVirtualAudioSession.onAppsNeedingAudioRoutingChanged(appUids);

        assertThat(Arrays.equals(mVirtualAudioSession.getReroutedAppUids().toArray(),
                appUids)).isTrue();
        assertThat(mVirtualAudioSession.getReroutedAppUids().size()).isEqualTo(2);
    }

    @Test
    public void close_releasesCaptureAndInjection() {
        mVirtualAudioSession.startAudioCapture(AUDIO_CAPTURE_FORMAT);
        mVirtualAudioSession.startAudioInjection(AUDIO_INJECT_FORMAT);

        mVirtualAudioSession.close();

        assertThat(mVirtualAudioSession.getAudioCapture()).isNull();
        assertThat(mVirtualAudioSession.getAudioInjection()).isNull();
    }

    @Test
    public void onPlaybackConfigChanged_sendsCallback() {
        List<AudioPlaybackConfiguration> configs = new ArrayList<>();

        mVirtualAudioSession.getAudioConfigChangedListener().onPlaybackConfigChanged(configs);

        verify(mCallback, timeout(2000)).onPlaybackConfigChanged(configs);
    }

    @Test
    public void onRecordingConfigChanged_sendCallback() {
        List<AudioRecordingConfiguration> configs = new ArrayList<>();

        mVirtualAudioSession.getAudioConfigChangedListener().onRecordingConfigChanged(configs);

        verify(mCallback, timeout(2000)).onRecordingConfigChanged(configs);
    }
}
