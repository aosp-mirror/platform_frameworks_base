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

import android.media.AudioTrack;
import android.speech.tts.TextToSpeechService.UtteranceCompletedDispatcher;

import java.util.LinkedList;

/**
 * Params required to play back a synthesis request.
 */
final class SynthesisMessageParams extends MessageParams {
    final int mStreamType;
    final int mSampleRateInHz;
    final int mAudioFormat;
    final int mChannelCount;
    final float mVolume;
    final float mPan;
    final EventLogger mLogger;

    volatile AudioTrack mAudioTrack;
    // Not volatile, accessed only from the synthesis thread.
    int mBytesWritten;
    int mAudioBufferSize;

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

        // initially null.
        mAudioTrack = null;
        mBytesWritten = 0;
        mAudioBufferSize = 0;
    }

    @Override
    int getType() {
        return TYPE_SYNTHESIS;
    }

    synchronized void addBuffer(byte[] buffer, int offset, int length) {
        mDataBufferList.add(new ListEntry(buffer, offset, length));
    }

    synchronized void addBuffer(byte[] buffer) {
        mDataBufferList.add(new ListEntry(buffer, 0, buffer.length));
    }

    synchronized ListEntry getNextBuffer() {
        return mDataBufferList.poll();
    }


    void setAudioTrack(AudioTrack audioTrack) {
        mAudioTrack = audioTrack;
    }

    AudioTrack getAudioTrack() {
        return mAudioTrack;
    }

    static final class ListEntry {
        final byte[] mBytes;
        final int mOffset;
        final int mLength;

        ListEntry(byte[] bytes, int offset, int length) {
            mBytes = bytes;
            mOffset = offset;
            mLength = length;
        }
    }
}

