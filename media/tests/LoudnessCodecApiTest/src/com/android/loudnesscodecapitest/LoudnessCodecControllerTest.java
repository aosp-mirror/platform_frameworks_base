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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.IAudioService;
import android.media.LoudnessCodecController;
import android.media.LoudnessCodecController.OnLoudnessCodecUpdateListener;
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
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.concurrent.Executors;

/**
 * Unit tests for {@link LoudnessCodecController} checking the internal interactions with a mocked
 * {@link IAudioService} without any real IPC interactions.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class LoudnessCodecControllerTest {
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

    private LoudnessCodecController mLcc;

    private int mSessionId;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final AudioManager audioManager = (AudioManager) context.getSystemService(
                AudioManager.class);
        mSessionId = 0;
        if (audioManager != null) {
            mSessionId = audioManager.generateAudioSessionId();
        }
        mLcc = LoudnessCodecController.createForTesting(mSessionId,
                Executors.newSingleThreadExecutor(), new OnLoudnessCodecUpdateListener() {
                }, mAudioService);
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void createLcc_callsAudioServiceStart() throws Exception {
        verify(mAudioService).startLoudnessCodecUpdates(eq(mSessionId));
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void getLoudnessCodecParams_callsAudioServiceGetLoudness() throws Exception {
        when(mAudioService.getLoudnessParams(any())).thenReturn(new PersistableBundle());
        final MediaCodec mediaCodec = createAndConfigureMediaCodec();

        try {
            mLcc.addMediaCodec(mediaCodec);
            mLcc.getLoudnessCodecParams(mediaCodec);

            verify(mAudioService).getLoudnessParams(any());
        } finally {
            mediaCodec.release();
        }
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void release_stopCodecUpdates() throws Exception {
        final MediaCodec mediaCodec = createAndConfigureMediaCodec();

        try {
            mLcc.addMediaCodec(mediaCodec);
            mLcc.close();  // stops updates

            verify(mAudioService).stopLoudnessCodecUpdates(eq(mSessionId));
        } finally {
            mediaCodec.release();
        }
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void addMediaCodecTwice_triggersIAE() throws Exception {
        final MediaCodec mediaCodec = createAndConfigureMediaCodec();

        try {
            mLcc.addMediaCodec(mediaCodec);

            assertThrows(IllegalArgumentException.class, () -> mLcc.addMediaCodec(mediaCodec));
        } finally {
            mediaCodec.release();
        }
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void addUnconfiguredMediaCodec_returnsFalse() throws Exception {
        final MediaCodec mediaCodec = MediaCodec.createDecoderByType("audio/mpeg");

        try {
            assertFalse(mLcc.addMediaCodec(mediaCodec));
        } finally {
            mediaCodec.release();
        }
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void removeAddedMediaCodecAfterSetTrack_callsAudioServiceRemoveCodec() throws Exception {
        final MediaCodec mediaCodec = createAndConfigureMediaCodec();

        try {
            mLcc.addMediaCodec(mediaCodec);
            mLcc.removeMediaCodec(mediaCodec);

            verify(mAudioService).removeLoudnessCodecInfo(eq(mSessionId), any());
        } finally {
            mediaCodec.release();
        }
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void addMediaCodec_callsAudioServiceAdd() throws Exception {
        final MediaCodec mediaCodec = createAndConfigureMediaCodec();

        try {
            mLcc.addMediaCodec(mediaCodec);
            verify(mAudioService).addLoudnessCodecInfo(eq(mSessionId), anyInt(), any());
        } finally {
            mediaCodec.release();
        }
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void removeMediaCodec_callsAudioServiceRemove() throws Exception {
        final MediaCodec mediaCodec = createAndConfigureMediaCodec();

        try {
            mLcc.addMediaCodec(mediaCodec);
            verify(mAudioService).addLoudnessCodecInfo(eq(mSessionId), anyInt(), any());

            mLcc.removeMediaCodec(mediaCodec);
            verify(mAudioService).removeLoudnessCodecInfo(eq(mSessionId), any());
        } finally {
            mediaCodec.release();
        }
    }

    @Test
    @RequiresFlagsEnabled(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void removeWrongMediaCodec_triggersIAE() throws Exception {
        final MediaCodec mediaCodec1 = createAndConfigureMediaCodec();
        final MediaCodec mediaCodec2 = createAndConfigureMediaCodec();

        try {
            mLcc.addMediaCodec(mediaCodec1);
            verify(mAudioService).addLoudnessCodecInfo(eq(mSessionId), anyInt(), any());

            assertThrows(IllegalArgumentException.class,
                    () -> mLcc.removeMediaCodec(mediaCodec2));
        } finally {
            mediaCodec1.release();
            mediaCodec2.release();
        }
    }

    private MediaCodec createAndConfigureMediaCodec() throws Exception {
        AssetFileDescriptor testFd = InstrumentationRegistry.getInstrumentation().getContext()
                .getResources()
                .openRawResourceFd(R.raw.noise_2ch_48khz_tlou_19lufs_anchor_17lufs_mp4);

        MediaExtractor extractor;
        extractor = new MediaExtractor();
        try {
            extractor.setDataSource(testFd.getFileDescriptor(), testFd.getStartOffset(),
                    testFd.getLength());
            assertEquals("wrong number of tracks", 1, extractor.getTrackCount());
            MediaFormat format = extractor.getTrackFormat(0);
            String mime = format.getString(MediaFormat.KEY_MIME);
            assertTrue("not an audio file", mime.startsWith(TEST_MEDIA_AUDIO_CODEC_PREFIX));
            final MediaCodec mediaCodec = MediaCodec.createDecoderByType(mime);

            Log.v(TAG, "configuring with " + format);
            mediaCodec.configure(format, null /* surface */, null /* crypto */, 0 /* flags */);
            return mediaCodec;
        } finally {
            testFd.close();
            extractor.release();
        }
    }
}
