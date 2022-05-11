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
import android.util.MathUtils;

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
    private boolean mStarted;

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
            byte[] buffer = new byte[size];

            if (mMic) {
                bufferInternal = new short[size / 2];
                bufferMic = new short[size / 2];
            }

            int readBytes = 0;
            int readShortsInternal = 0;
            int offsetShortsInternal = 0;
            int readShortsMic = 0;
            int offsetShortsMic = 0;
            while (true) {
                if (mMic) {
                    readShortsInternal = mAudioRecord.read(bufferInternal, offsetShortsInternal,
                            bufferInternal.length - offsetShortsInternal);
                    readShortsMic = mAudioRecordMic.read(
                            bufferMic, offsetShortsMic, bufferMic.length - offsetShortsMic);

                    // if both error, end the recording
                    if (readShortsInternal < 0 && readShortsMic < 0) {
                        break;
                    }

                    // if one has an errors, fill its buffer with zeros and assume it is mute
                    // with the same size as the other buffer
                    if (readShortsInternal < 0) {
                        readShortsInternal = readShortsMic;
                        offsetShortsInternal = offsetShortsMic;
                        java.util.Arrays.fill(bufferInternal, (short) 0);
                    }

                    if (readShortsMic < 0) {
                        readShortsMic = readShortsInternal;
                        offsetShortsMic = offsetShortsInternal;
                        java.util.Arrays.fill(bufferMic, (short) 0);
                    }

                    // Add offset (previous unmixed values) to the buffer
                    readShortsInternal += offsetShortsInternal;
                    readShortsMic += offsetShortsMic;

                    int minShorts = Math.min(readShortsInternal, readShortsMic);
                    readBytes = minShorts * 2;

                    // modify the volume
                    // scale only mixed shorts
                    scaleValues(bufferMic, minShorts, MIC_VOLUME_SCALE);
                    // Mix the two buffers
                    addAndConvertBuffers(bufferInternal, bufferMic, buffer, minShorts);

                    // shift unmixed shorts to the beginning of the buffer
                    shiftToStart(bufferInternal, minShorts, offsetShortsInternal);
                    shiftToStart(bufferMic, minShorts, offsetShortsMic);

                    // reset the offset for the next loop
                    offsetShortsInternal = readShortsInternal - minShorts;
                    offsetShortsMic = readShortsMic - minShorts;
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

    /**
     * moves all bits from start to end to the beginning of the array
     */
    private void shiftToStart(short[] target, int start, int end) {
        for (int i = 0; i  < end - start; i++) {
            target[i] = target[start + i];
        }
    }

    private void scaleValues(short[] buff, int len, float scale) {
        for (int i = 0; i < len; i++) {
            int newValue = (int) (buff[i] * scale);
            buff[i] = (short) MathUtils.constrain(newValue, Short.MIN_VALUE, Short.MAX_VALUE);
        }
    }

    private void addAndConvertBuffers(short[] src1, short[] src2, byte[] dst, int sizeShorts) {
        for (int i = 0; i < sizeShorts; i++) {
            int sum;
            sum = (short) MathUtils.constrain(
                    (int) src1[i] + (int) src2[i], Short.MIN_VALUE, Short.MAX_VALUE);
            int byteIndex = i * 2;
            dst[byteIndex] = (byte) (sum & 0xff);
            dst[byteIndex + 1] = (byte) ((sum >> 8) & 0xff);
        }
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
    public synchronized void start() throws IllegalStateException {
        if (mStarted) {
            if (mThread == null) {
                throw new IllegalStateException("Recording stopped and can't restart (single use)");
            }
            throw new IllegalStateException("Recording already started");
        }
        mStarted = true;
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
