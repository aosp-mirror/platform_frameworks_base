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

package com.android.server.voiceinteraction;

import static android.service.voice.HotwordAudioStream.KEY_AUDIO_STREAM_COPY_BUFFER_LENGTH_BYTES;

import static com.android.server.voiceinteraction.HotwordAudioStreamCopier.DEFAULT_COPY_BUFFER_LENGTH_BYTES;
import static com.android.server.voiceinteraction.HotwordAudioStreamCopier.MAX_COPY_BUFFER_LENGTH_BYTES;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;

import android.app.AppOpsManager;
import android.media.AudioFormat;
import android.media.MediaSyncEvent;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.platform.test.annotations.Presubmit;
import android.service.voice.HotwordAudioStream;
import android.service.voice.HotwordDetectedResult;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.jetbrains.annotations.NotNull;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class HotwordAudioStreamCopierTest {

    private static final int DETECTOR_TYPE = 1;
    private static final int VOICE_INTERACTOR_UID = 999;
    private static final String VOICE_INTERACTOR_PACKAGE_NAME = "VIPackageName";
    private static final String VOICE_INTERACTOR_ATTRIBUTION_TAG = "VIAttributionTag";
    private static final AudioFormat FAKE_AUDIO_FORMAT =
            new AudioFormat.Builder()
                    .setSampleRate(32000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();

    private HotwordAudioStreamCopier mCopier;
    private AppOpsManager mAppOpsManager;

    @Before
    public void setUp() {
        mAppOpsManager = mock(AppOpsManager.class);
        mCopier = new HotwordAudioStreamCopier(mAppOpsManager, DETECTOR_TYPE, VOICE_INTERACTOR_UID,
                VOICE_INTERACTOR_PACKAGE_NAME, VOICE_INTERACTOR_ATTRIBUTION_TAG);
    }

    @Test
    public void testDefaultCopyBufferLength() throws Exception {
        ParcelFileDescriptor[] fakeAudioStreamPipe = ParcelFileDescriptor.createPipe();
        try {
            // There is no copy buffer length is specified in the metadata.
            // HotwordAudioStreamCopier should use the default copy buffer length.
            List<HotwordAudioStream> originalAudioStreams = new ArrayList<>();
            HotwordAudioStream audioStream = new HotwordAudioStream.Builder(FAKE_AUDIO_FORMAT,
                    fakeAudioStreamPipe[0]).build();
            originalAudioStreams.add(audioStream);
            HotwordDetectedResult originalResult = buildHotwordDetectedResultWithStreams(
                    originalAudioStreams);

            HotwordDetectedResult managedResult = mCopier.startCopyingAudioStreams(
                    originalResult);
            List<HotwordAudioStream> managedAudioStreams = managedResult.getAudioStreams();
            assertThat(managedAudioStreams.size()).isEqualTo(1);

            ParcelFileDescriptor readFd =
                    managedAudioStreams.get(0).getAudioStreamParcelFileDescriptor();
            ParcelFileDescriptor writeFd = fakeAudioStreamPipe[1];
            verifyCopyBufferLength(DEFAULT_COPY_BUFFER_LENGTH_BYTES, readFd, writeFd);
        } finally {
            closeAudioStreamPipe(fakeAudioStreamPipe);
        }
    }

    @Test
    public void testCustomCopyBufferLength() throws Exception {
        List<ParcelFileDescriptor[]> fakeAudioStreamPipes = new ArrayList<>();
        try {
            // We create 4 audio streams, with various small prime values specified in the metadata.
            // HotwordAudioStreamCopier reads data in chunks the size of the buffer. In
            // verifyCopyBufferLength(), we check if the number of bytes read from the copied stream
            // is a multiple of the buffer length.
            //
            // By using prime numbers, this ensures that HotwordAudioStreamCopier is reading the
            // correct buffer length for the corresponding stream, since multiples of different
            // primes cannot be equal. A small number helps ensure that the test reads the copied
            // stream before HotwordAudioStreamCopier can copy the entire source stream (which has
            // a large size).
            int[] copyBufferLengths = new int[]{2, 3, 5, 7};
            List<HotwordAudioStream> originalAudioStreams = new ArrayList<>();
            for (int i = 0; i < copyBufferLengths.length; i++) {
                ParcelFileDescriptor[] fakeAudioStreamPipe = ParcelFileDescriptor.createPipe();
                fakeAudioStreamPipes.add(fakeAudioStreamPipe);
                HotwordAudioStream audioStream = new HotwordAudioStream.Builder(FAKE_AUDIO_FORMAT,
                        fakeAudioStreamPipe[0]).build();
                audioStream.getMetadata().putInt(KEY_AUDIO_STREAM_COPY_BUFFER_LENGTH_BYTES,
                        copyBufferLengths[i]);
                originalAudioStreams.add(audioStream);
            }
            HotwordDetectedResult originalResult = buildHotwordDetectedResultWithStreams(
                    originalAudioStreams);

            HotwordDetectedResult managedResult = mCopier.startCopyingAudioStreams(
                    originalResult);
            List<HotwordAudioStream> managedAudioStreams = managedResult.getAudioStreams();
            assertThat(managedAudioStreams.size()).isEqualTo(copyBufferLengths.length);

            for (int i = 0; i < copyBufferLengths.length; i++) {
                ParcelFileDescriptor readFd =
                        managedAudioStreams.get(i).getAudioStreamParcelFileDescriptor();
                ParcelFileDescriptor writeFd = fakeAudioStreamPipes.get(i)[1];
                verifyCopyBufferLength(copyBufferLengths[i], readFd, writeFd);
            }
        } finally {
            for (ParcelFileDescriptor[] fakeAudioStreamPipe : fakeAudioStreamPipes) {
                closeAudioStreamPipe(fakeAudioStreamPipe);
            }
        }
    }

    @Test
    public void testInvalidCopyBufferLength_NonPositive() throws Exception {
        ParcelFileDescriptor[] fakeAudioStreamPipe = ParcelFileDescriptor.createPipe();
        try {
            // An invalid copy buffer length (non-positive) is specified in the metadata.
            // HotwordAudioStreamCopier should use the default copy buffer length.
            List<HotwordAudioStream> originalAudioStreams = new ArrayList<>();
            HotwordAudioStream audioStream = new HotwordAudioStream.Builder(FAKE_AUDIO_FORMAT,
                    fakeAudioStreamPipe[0]).build();
            audioStream.getMetadata().putInt(KEY_AUDIO_STREAM_COPY_BUFFER_LENGTH_BYTES, 0);
            originalAudioStreams.add(audioStream);
            HotwordDetectedResult originalResult = buildHotwordDetectedResultWithStreams(
                    originalAudioStreams);

            HotwordDetectedResult managedResult = mCopier.startCopyingAudioStreams(
                    originalResult);
            List<HotwordAudioStream> managedAudioStreams = managedResult.getAudioStreams();
            assertThat(managedAudioStreams.size()).isEqualTo(1);

            ParcelFileDescriptor readFd =
                    managedAudioStreams.get(0).getAudioStreamParcelFileDescriptor();
            ParcelFileDescriptor writeFd = fakeAudioStreamPipe[1];
            verifyCopyBufferLength(DEFAULT_COPY_BUFFER_LENGTH_BYTES, readFd, writeFd);
        } finally {
            closeAudioStreamPipe(fakeAudioStreamPipe);
        }
    }

    @Test
    public void testInvalidCopyBufferLength_ExceedsMaximum() throws Exception {
        ParcelFileDescriptor[] fakeAudioStreamPipe = ParcelFileDescriptor.createPipe();
        try {
            // An invalid copy buffer length (exceeds the maximum) is specified in the metadata.
            // HotwordAudioStreamCopier should use the default copy buffer length.
            List<HotwordAudioStream> originalAudioStreams = new ArrayList<>();
            HotwordAudioStream audioStream = new HotwordAudioStream.Builder(FAKE_AUDIO_FORMAT,
                    fakeAudioStreamPipe[0]).build();
            audioStream.getMetadata().putInt(KEY_AUDIO_STREAM_COPY_BUFFER_LENGTH_BYTES,
                    MAX_COPY_BUFFER_LENGTH_BYTES + 1);
            originalAudioStreams.add(audioStream);
            HotwordDetectedResult originalResult = buildHotwordDetectedResultWithStreams(
                    originalAudioStreams);

            HotwordDetectedResult managedResult = mCopier.startCopyingAudioStreams(
                    originalResult);
            List<HotwordAudioStream> managedAudioStreams = managedResult.getAudioStreams();
            assertThat(managedAudioStreams.size()).isEqualTo(1);

            ParcelFileDescriptor readFd =
                    managedAudioStreams.get(0).getAudioStreamParcelFileDescriptor();
            ParcelFileDescriptor writeFd = fakeAudioStreamPipe[1];
            verifyCopyBufferLength(DEFAULT_COPY_BUFFER_LENGTH_BYTES, readFd, writeFd);
        } finally {
            closeAudioStreamPipe(fakeAudioStreamPipe);
        }
    }

    @Test
    public void testInvalidCopyBufferLength_NotAnInt() throws Exception {
        ParcelFileDescriptor[] fakeAudioStreamPipe = ParcelFileDescriptor.createPipe();
        try {
            // An invalid copy buffer length (not an int) is specified in the metadata.
            // HotwordAudioStreamCopier should use the default copy buffer length.
            List<HotwordAudioStream> originalAudioStreams = new ArrayList<>();
            HotwordAudioStream audioStream = new HotwordAudioStream.Builder(FAKE_AUDIO_FORMAT,
                    fakeAudioStreamPipe[0]).build();
            audioStream.getMetadata().putString(KEY_AUDIO_STREAM_COPY_BUFFER_LENGTH_BYTES,
                    "Not an int");
            originalAudioStreams.add(audioStream);
            HotwordDetectedResult originalResult = buildHotwordDetectedResultWithStreams(
                    originalAudioStreams);

            HotwordDetectedResult managedResult = mCopier.startCopyingAudioStreams(
                    originalResult);
            List<HotwordAudioStream> managedAudioStreams = managedResult.getAudioStreams();
            assertThat(managedAudioStreams.size()).isEqualTo(1);

            ParcelFileDescriptor readFd =
                    managedAudioStreams.get(0).getAudioStreamParcelFileDescriptor();
            ParcelFileDescriptor writeFd = fakeAudioStreamPipe[1];
            verifyCopyBufferLength(DEFAULT_COPY_BUFFER_LENGTH_BYTES, readFd, writeFd);
        } finally {
            closeAudioStreamPipe(fakeAudioStreamPipe);
        }
    }

    private void verifyCopyBufferLength(int expectedCopyBufferLength, ParcelFileDescriptor readFd,
            ParcelFileDescriptor writeFd) throws IOException {
        byte[] bytesToRepeat = new byte[]{99};
        try (InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(readFd);
             OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(writeFd)) {
            writeToFakeAudioStreamPipe(os, bytesToRepeat, MAX_COPY_BUFFER_LENGTH_BYTES);
            byte[] actualBytesRead = new byte[MAX_COPY_BUFFER_LENGTH_BYTES];
            int numBytesRead = is.read(actualBytesRead);

            // HotwordAudioStreamCopier reads data in chunks the size of the buffer. We write MAX
            // bytes but the actual number of bytes read from the copied stream should be a
            // multiple of the buffer length.
            assertThat(numBytesRead % expectedCopyBufferLength).isEqualTo(0);
        }
    }

    @NotNull
    private static HotwordDetectedResult buildHotwordDetectedResultWithStreams(
            List<HotwordAudioStream> audioStreams) {
        return new HotwordDetectedResult.Builder()
                .setConfidenceLevel(HotwordDetectedResult.CONFIDENCE_LEVEL_LOW)
                .setMediaSyncEvent(MediaSyncEvent.createEvent(
                        MediaSyncEvent.SYNC_EVENT_PRESENTATION_COMPLETE))
                .setHotwordOffsetMillis(100)
                .setHotwordDurationMillis(1000)
                .setAudioChannel(1)
                .setHotwordDetectionPersonalized(true)
                .setScore(100)
                .setPersonalizedScore(100)
                .setHotwordPhraseId(1)
                .setAudioStreams(audioStreams)
                .setExtras(new PersistableBundle())
                .build();
    }

    private static void writeToFakeAudioStreamPipe(OutputStream writeOutputStream,
            byte[] bytesToRepeat, int totalBytesToWrite) throws IOException {
        // Create the fake stream buffer, consisting of bytesToRepeat, repeated as many times as
        // needed to get to totalBytesToWrite.
        byte[] fakeAudioData = new byte[totalBytesToWrite];
        int bytesWritten = 0;
        while (bytesWritten + bytesToRepeat.length <= totalBytesToWrite) {
            System.arraycopy(bytesToRepeat, 0, fakeAudioData, bytesWritten, bytesToRepeat.length);
            bytesWritten += bytesToRepeat.length;
        }
        if (bytesWritten < totalBytesToWrite) {
            int bytesLeft = totalBytesToWrite - bytesWritten;
            System.arraycopy(bytesToRepeat, 0, fakeAudioData, bytesWritten, bytesLeft);
        }

        writeOutputStream.write(fakeAudioData);
    }

    private static void closeAudioStreamPipe(ParcelFileDescriptor[] parcelFileDescriptors)
            throws IOException {
        if (parcelFileDescriptors != null) {
            parcelFileDescriptors[0].close();
            parcelFileDescriptors[1].close();
        }
    }

}
