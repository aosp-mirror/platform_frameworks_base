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

package com.android.loudnesscodecapitest;

import static android.media.audio.Flags.FLAG_LOUDNESS_CONFIGURATOR_API;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.IAudioService;
import android.media.LoudnessCodecConfigurator;
import android.media.LoudnessCodecConfigurator.OnLoudnessCodecUpdateListener;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.PersistableBundle;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.List;
import java.util.concurrent.Executors;

/**
 * Unit tests for {@link LoudnessCodecConfigurator} checking the internal interactions with a mocked
 * {@link IAudioService} without any real IPC interactions.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class LoudnessCodecConfiguratorTest {
    private static final String TAG = "LoudnessCodecConfiguratorTest";

    private static final String TEST_MEDIA_AUDIO_CODEC_PREFIX = "audio/";
    private static final int TEST_AUDIO_TRACK_BUFFER_SIZE = 2048;
    private static final int TEST_AUDIO_TRACK_SAMPLERATE = 48000;
    private static final int TEST_AUDIO_TRACK_CHANNELS = 2;

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Mock
    private IAudioService mAudioService;

    private LoudnessCodecConfigurator mLcc;

    @Before
    public void setUp() {
        mLcc = LoudnessCodecConfigurator.createForTesting(mAudioService,
                Executors.newSingleThreadExecutor(), new OnLoudnessCodecUpdateListener() {});
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void setAudioTrack_callsAudioServiceStart() throws Exception {
        final AudioTrack track = createAudioTrack();

        mLcc.addMediaCodec(createAndConfigureMediaCodec());
        mLcc.setAudioTrack(track);

        verify(mAudioService).startLoudnessCodecUpdates(eq(track.getPlayerIId()),
                anyList());
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void getLoudnessCodecParams_callsAudioServiceGetLoudness() throws Exception {
        when(mAudioService.getLoudnessParams(anyInt(), any())).thenReturn(new PersistableBundle());
        final AudioTrack track = createAudioTrack();

        mLcc.getLoudnessCodecParams(track, createAndConfigureMediaCodec());

        verify(mAudioService).getLoudnessParams(eq(track.getPlayerIId()), any());
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void setAudioTrack_addsAudioServicePiidCodecs() throws Exception {
        final AudioTrack track = createAudioTrack();
        final MediaCodec mediaCodec = createAndConfigureMediaCodec();

        mLcc.addMediaCodec(mediaCodec);
        mLcc.setAudioTrack(track);

        verify(mAudioService).startLoudnessCodecUpdates(eq(track.getPlayerIId()), anyList());
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void setAudioTrackTwice_ignoresSecondCall() throws Exception {
        final AudioTrack track = createAudioTrack();
        final MediaCodec mediaCodec = createAndConfigureMediaCodec();

        mLcc.addMediaCodec(mediaCodec);
        mLcc.setAudioTrack(track);
        mLcc.setAudioTrack(track);

        verify(mAudioService, times(1)).startLoudnessCodecUpdates(eq(track.getPlayerIId()),
                anyList());
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void setTrackNull_stopCodecUpdates() throws Exception {
        final AudioTrack track = createAudioTrack();

        mLcc.addMediaCodec(createAndConfigureMediaCodec());
        mLcc.setAudioTrack(track);

        mLcc.setAudioTrack(null);  // stops updates
        verify(mAudioService).stopLoudnessCodecUpdates(eq(track.getPlayerIId()));
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void addMediaCodecTwice_triggersIAE() throws Exception {
        final MediaCodec mediaCodec = createAndConfigureMediaCodec();

        mLcc.addMediaCodec(mediaCodec);

        assertThrows(IllegalArgumentException.class, () -> mLcc.addMediaCodec(mediaCodec));
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void setClearTrack_removeAllAudioServicePiidCodecs() throws Exception {
        final ArgumentCaptor<List> argument = ArgumentCaptor.forClass(List.class);

        final AudioTrack track = createAudioTrack();

        mLcc.addMediaCodec(createAndConfigureMediaCodec());
        mLcc.setAudioTrack(track);
        verify(mAudioService).startLoudnessCodecUpdates(eq(track.getPlayerIId()),
                argument.capture());
        assertEquals(argument.getValue().size(), 1);

        mLcc.addMediaCodec(createAndConfigureMediaCodec());
        mLcc.setAudioTrack(null);
        verify(mAudioService).stopLoudnessCodecUpdates(eq(track.getPlayerIId()));
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void removeAddedMediaCodecAfterSetTrack_callsAudioServiceRemoveCodec() throws Exception {
        final AudioTrack track = createAudioTrack();
        final MediaCodec mediaCodec = createAndConfigureMediaCodec();

        mLcc.addMediaCodec(mediaCodec);
        mLcc.setAudioTrack(track);
        mLcc.removeMediaCodec(mediaCodec);

        verify(mAudioService).removeLoudnessCodecInfo(eq(track.getPlayerIId()), any());
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void addMediaCodecAfterSetTrack_callsAudioServiceAdd() throws Exception {
        final AudioTrack track = createAudioTrack();

        mLcc.addMediaCodec(createAndConfigureMediaCodec());
        mLcc.setAudioTrack(track);
        verify(mAudioService).startLoudnessCodecUpdates(eq(track.getPlayerIId()), anyList());

        mLcc.addMediaCodec(createAndConfigureMediaCodec());
        verify(mAudioService).addLoudnessCodecInfo(eq(track.getPlayerIId()), anyInt(), any());
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void removeMediaCodecAfterSetTrack_callsAudioServiceRemove() throws Exception {
        final AudioTrack track = createAudioTrack();
        final MediaCodec mediaCodec = createAndConfigureMediaCodec();

        mLcc.addMediaCodec(mediaCodec);
        mLcc.setAudioTrack(track);
        verify(mAudioService).startLoudnessCodecUpdates(eq(track.getPlayerIId()), anyList());

        mLcc.removeMediaCodec(mediaCodec);
        verify(mAudioService).removeLoudnessCodecInfo(eq(track.getPlayerIId()), any());
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void removeWrongMediaCodecAfterSetTrack_triggersIAE() throws Exception {
        final AudioTrack track = createAudioTrack();

        mLcc.addMediaCodec(createAndConfigureMediaCodec());
        mLcc.setAudioTrack(track);
        verify(mAudioService).startLoudnessCodecUpdates(eq(track.getPlayerIId()), anyList());

        assertThrows(IllegalArgumentException.class,
                () -> mLcc.removeMediaCodec(createAndConfigureMediaCodec()));
    }

    private static AudioTrack createAudioTrack() {
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().build())
                .setBufferSizeInBytes(TEST_AUDIO_TRACK_BUFFER_SIZE)
                .setAudioFormat(new AudioFormat.Builder()
                        .setChannelMask(TEST_AUDIO_TRACK_CHANNELS)
                        .setSampleRate(TEST_AUDIO_TRACK_SAMPLERATE).build())
                .build();
    }

    private MediaCodec createAndConfigureMediaCodec() throws Exception {
        AssetFileDescriptor testFd = InstrumentationRegistry.getInstrumentation().getContext()
                .getResources()
                .openRawResourceFd(R.raw.noise_2ch_48khz_tlou_19lufs_anchor_17lufs_mp4);

        MediaExtractor extractor;
        extractor = new MediaExtractor();
        extractor.setDataSource(testFd.getFileDescriptor(), testFd.getStartOffset(),
                testFd.getLength());
        testFd.close();

        assertEquals("wrong number of tracks", 1, extractor.getTrackCount());
        MediaFormat format = extractor.getTrackFormat(0);
        String mime = format.getString(MediaFormat.KEY_MIME);
        assertTrue("not an audio file", mime.startsWith(TEST_MEDIA_AUDIO_CODEC_PREFIX));
        final MediaCodec mediaCodec = MediaCodec.createDecoderByType(mime);

        Log.v(TAG, "configuring with " + format);
        mediaCodec.configure(format, null /* surface */, null /* crypto */, 0 /* flags */);

        return mediaCodec;
    }
}
