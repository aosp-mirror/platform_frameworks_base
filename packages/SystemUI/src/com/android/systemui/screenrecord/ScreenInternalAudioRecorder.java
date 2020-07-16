/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.screenrecord;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Recording internal audio
 */
public class ScreenInternalAudioRecorder {
    private static String TAG = "ScreenAudioRecorder";
    private static final int TIMEOUT = 500;
    private static final float MIC_VOLUME_SCALE = 1.4f;
    private AudioRecord mAudioRecord;
    private AudioRecord mAudioRecordMic;
    private Config mConfig = new Config();
    private Thread mThread;
    private MediaProjection mMediaProjection;
    private MediaCodec mCodec;
    private long mPresentationTime;
    private long mTotalBytes;
    private MediaMuxer mMuxer;
    private boolean mMic;

    private int mTrackId = -1;

    public ScreenInternalAudioRecorder(String outFile, MediaProjection mp, boolean includeMicInput)
            throws IOException {
        mMic = includeMicInput;
        mMuxer = new MediaMuxer(outFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        mMediaProjection = mp;
        Log.d(TAG, "creating audio file " + outFile);
        setupSimple();
    }
    /**
     * Audio recoding configuration
     */
    public static class Config {
        public int channelOutMask = AudioFormat.CHANNEL_OUT_MONO;
        public int channelInMask = AudioFormat.CHANNEL_IN_MONO;
        public int encoding = AudioFormat.ENCODING_PCM_16BIT;
        public int sampleRate = 44100;
        public int bitRate = 196000;
        public int bufferSizeBytes = 1 << 17;
        public boolean privileged = true;
        public boolean legacy_app_looback = false;

        @Override
        public String toString() {
            return  "channelMask=" + channelOutMask
                    + "\n   encoding=" + encoding
                    + "\n sampleRate=" + sampleRate
                    + "\n bufferSize=" + bufferSizeBytes
                    + "\n privileged=" + privileged
                    + "\n legacy app looback=" + legacy_app_looback;
        }

    }

    private void setupSimple() throws IOException {
        int size = AudioRecord.getMinBufferSize(
                mConfig.sampleRate, mConfig.channelInMask,
                mConfig.encoding) * 2;

        Log.d(TAG, "audio buffer size: " + size);

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(mConfig.encoding)
                .setSampleRate(mConfig.sampleRate)
                .setChannelMask(mConfig.channelOutMask)
                .build();

        AudioPlaybackCaptureConfiguration playbackConfig =
                new AudioPlaybackCaptureConfiguration.Builder(mMediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build();

        mAudioRecord = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .build();

        if (mMic) {
            mAudioRecordMic = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    mConfig.sampleRate, AudioFormat.CHANNEL_IN_MONO, mConfig.encoding, size);
        }

        mCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaFormat medFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, mConfig.sampleRate, 1);
        medFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        medFormat.setInteger(MediaFormat.KEY_BIT_RATE, mConfig.bitRate);
        medFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, mConfig.encoding);
        mCodec.configure(medFormat,
                null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        mThread = new Thread(() -> {
            short[] bufferInternal = null;
            short[] bufferMic = null;
            byte[] buffer = null;

            if (mMic) {
                bufferInternal = new short[size / 2];
                bufferMic = new short[size / 2];
            } else {
                buffer = new byte[size];
            }

            while (true) {
                int readBytes = 0;
                int readShortsInternal = 0;
                int readShortsMic = 0;
                if (mMic) {
                    readShortsInternal = mAudioRecord.read(bufferInternal, 0,
                            bufferInternal.length);
                    readShortsMic = mAudioRecordMic.read(bufferMic, 0, bufferMic.length);

                    // modify the volume
                    bufferMic = scaleValues(bufferMic,
                            readShortsMic, MIC_VOLUME_SCALE);
                    readBytes = Math.min(readShortsInternal, readShortsMic) * 2;
                    buffer = addAndConvertBuffers(bufferInternal, readShortsInternal, bufferMic,
                            readShortsMic);
                } else {
                    readBytes = mAudioRecord.read(buffer, 0, buffer.length);
                }

                //exit the loop when at end of stream
                if (readBytes < 0) {
                    Log.e(TAG, "read error " + readBytes +
                            ", shorts internal: " + readShortsInternal +
                            ", shorts mic: " + readShortsMic);
                    break;
                }
                encode(buffer, readBytes);
            }
            endStream();
        });
    }

    private short[] scaleValues(short[] buff, int len, float scale) {
        for (int i = 0; i < len; i++) {
            int oldValue = buff[i];
            int newValue = (int) (buff[i] * scale);
            if (newValue > Short.MAX_VALUE) {
                newValue = Short.MAX_VALUE;
            } else if (newValue < Short.MIN_VALUE) {
                newValue = Short.MIN_VALUE;
            }
            buff[i] = (short) (newValue);
        }
        return buff;
    }
    private byte[] addAndConvertBuffers(short[] a1, int a1Limit, short[] a2, int a2Limit) {
        int size = Math.max(a1Limit, a2Limit);
        if (size < 0) return new byte[0];
        byte[] buff = new byte[size * 2];
        for (int i = 0; i < size; i++) {
            int sum;
            if (i > a1Limit) {
                sum = a2[i];
            } else if (i > a2Limit) {
                sum = a1[i];
            } else {
                sum = (int) a1[i] + (int) a2[i];
            }

            if (sum > Short.MAX_VALUE) sum = Short.MAX_VALUE;
            if (sum < Short.MIN_VALUE) sum = Short.MIN_VALUE;
            int byteIndex = i * 2;
            buff[byteIndex] = (byte) (sum & 0xff);
            buff[byteIndex + 1] = (byte) ((sum >> 8) & 0xff);
        }
        return buff;
    }

    private void encode(byte[] buffer, int readBytes) {
        int offset = 0;
        while (readBytes > 0) {
            int totalBytesRead = 0;
            int bufferIndex = mCodec.dequeueInputBuffer(TIMEOUT);
            if (bufferIndex < 0) {
                writeOutput();
                return;
            }
            ByteBuffer buff = mCodec.getInputBuffer(bufferIndex);
            buff.clear();
            int bufferSize = buff.capacity();
            int bytesToRead = readBytes > bufferSize ? bufferSize : readBytes;
            totalBytesRead += bytesToRead;
            readBytes -= bytesToRead;
            buff.put(buffer, offset, bytesToRead);
            offset += bytesToRead;
            mCodec.queueInputBuffer(bufferIndex, 0, bytesToRead, mPresentationTime, 0);
            mTotalBytes += totalBytesRead;
            mPresentationTime = 1000000L * (mTotalBytes / 2) / mConfig.sampleRate;

            writeOutput();
        }
    }

    private void endStream() {
        int bufferIndex = mCodec.dequeueInputBuffer(TIMEOUT);
        mCodec.queueInputBuffer(bufferIndex, 0, 0, mPresentationTime,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        writeOutput();
    }

    private void writeOutput() {
        while (true) {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int bufferIndex = mCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT);
            if (bufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                mTrackId = mMuxer.addTrack(mCodec.getOutputFormat());
                mMuxer.start();
                continue;
            }
            if (bufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }
            if (mTrackId < 0) return;
            ByteBuffer buff = mCodec.getOutputBuffer(bufferIndex);

            if (!((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    && bufferInfo.size != 0)) {
                mMuxer.writeSampleData(mTrackId, buff, bufferInfo);
            }
            mCodec.releaseOutputBuffer(bufferIndex, false);
        }
    }

    /**
    * start recording
     * @throws IllegalStateException if recording fails to initialize
    */
    public void start() throws IllegalStateException {
        if (mThread != null) {
            Log.e(TAG, "a recording is being done in parallel or stop is not called");
        }
        mAudioRecord.startRecording();
        if (mMic) mAudioRecordMic.startRecording();
        Log.d(TAG, "channel count " + mAudioRecord.getChannelCount());
        mCodec.start();
        if (mAudioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            throw new IllegalStateException("Audio recording failed to start");
        }
        mThread.start();
    }

    /**
     * end recording
     */
    public void end() {
        mAudioRecord.stop();
        if (mMic) {
            mAudioRecordMic.stop();
        }
        mAudioRecord.release();
        if (mMic) {
            mAudioRecordMic.release();
        }
        try {
            mThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mCodec.stop();
        mCodec.release();
        mMuxer.stop();
        mMuxer.release();
        mThread = null;
    }
}
