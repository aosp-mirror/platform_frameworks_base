/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.speech.tts;

import android.media.AudioFormat;
import android.media.AudioTrack;
import android.speech.tts.TextToSpeechService.UtteranceCompletedDispatcher;

import java.util.LinkedList;

/**
 * Params required to play back a synthesis request.
 */
final class SynthesisMessageParams extends MessageParams {
    private static final long MAX_UNCONSUMED_AUDIO_MS = 500;

    final int mStreamType;
    final int mSampleRateInHz;
    final int mAudioFormat;
    final int mChannelCount;
    final float mVolume;
    final float mPan;
    final EventLogger mLogger;

    final int mBytesPerFrame;

    volatile AudioTrack mAudioTrack;
    // Written by the synthesis thread, but read on the audio playback
    // thread.
    volatile int mBytesWritten;
    // A "short utterance" is one that uses less bytes than the audio
    // track buffer size (mAudioBufferSize). In this case, we need to call
    // AudioTrack#stop() to send pending buffers to the mixer, and slightly
    // different logic is required to wait for the track to finish.
    //
    // Not volatile, accessed only from the audio playback thread.
    boolean mIsShortUtterance;
    int mAudioBufferSize;
    // Always synchronized on "this".
    int mUnconsumedBytes;

    private final LinkedList<ListEntry> mDataBufferList = new LinkedList<ListEntry>();

    SynthesisMessageParams(int streamType, int sampleRate,
            int audioFormat, int channelCount,
            float volume, float pan, UtteranceCompletedDispatcher dispatcher,
            String callingApp, EventLogger logger) {
        super(dispatcher, callingApp);

        mStreamType = streamType;
        mSampleRateInHz = sampleRate;
        mAudioFormat = audioFormat;
        mChannelCount = channelCount;
        mVolume = volume;
        mPan = pan;
        mLogger = logger;

        mBytesPerFrame = getBytesPerFrame(mAudioFormat) * mChannelCount;

        // initially null.
        mAudioTrack = null;
        mBytesWritten = 0;
        mAudioBufferSize = 0;
    }

    @Override
    int getType() {
        return TYPE_SYNTHESIS;
    }

    synchronized void addBuffer(byte[] buffer) {
        long unconsumedAudioMs = 0;

        while ((unconsumedAudioMs = getUnconsumedAudioLengthMs()) > MAX_UNCONSUMED_AUDIO_MS) {
            try {
                wait();
            } catch (InterruptedException ie) {
                return;
            }
        }

        mDataBufferList.add(new ListEntry(buffer));
        mUnconsumedBytes += buffer.length;
    }

    synchronized void clearBuffers() {
        mDataBufferList.clear();
        mUnconsumedBytes = 0;
        notifyAll();
    }

    synchronized ListEntry getNextBuffer() {
        ListEntry entry = mDataBufferList.poll();
        if (entry != null) {
            mUnconsumedBytes -= entry.mBytes.length;
            notifyAll();
        }

        return entry;
    }

    void setAudioTrack(AudioTrack audioTrack) {
        mAudioTrack = audioTrack;
    }

    AudioTrack getAudioTrack() {
        return mAudioTrack;
    }

    // Must be called synchronized on this.
    private long getUnconsumedAudioLengthMs() {
        final int unconsumedFrames = mUnconsumedBytes / mBytesPerFrame;
        final long estimatedTimeMs = unconsumedFrames * 1000 / mSampleRateInHz;

        return estimatedTimeMs;
    }

    private static int getBytesPerFrame(int audioFormat) {
        if (audioFormat == AudioFormat.ENCODING_PCM_8BIT) {
            return 1;
        } else if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
            return 2;
        }

        return -1;
    }

    static final class ListEntry {
        final byte[] mBytes;

        ListEntry(byte[] bytes) {
            mBytes = bytes;
        }
    }
}

