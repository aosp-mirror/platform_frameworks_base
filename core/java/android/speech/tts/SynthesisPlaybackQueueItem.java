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

import android.speech.tts.TextToSpeechService.UtteranceProgressDispatcher;
import android.util.Log;

import java.util.LinkedList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages the playback of a list of byte arrays representing audio data
 * that are queued by the engine to an audio track.
 */
final class SynthesisPlaybackQueueItem extends PlaybackQueueItem {
    private static final String TAG = "TTS.SynthQueueItem";
    private static final boolean DBG = false;

    /**
     * Maximum length of audio we leave unconsumed by the audio track.
     * Calls to {@link #put(byte[])} will block until we have less than
     * this amount of audio left to play back.
     */
    private static final long MAX_UNCONSUMED_AUDIO_MS = 500;

    /**
     * Guards accesses to mDataBufferList and mUnconsumedBytes.
     */
    private final Lock mListLock = new ReentrantLock();
    private final Condition mReadReady = mListLock.newCondition();
    private final Condition mNotFull = mListLock.newCondition();

    // Guarded by mListLock.
    private final LinkedList<ListEntry> mDataBufferList = new LinkedList<ListEntry>();
    // Guarded by mListLock.
    private int mUnconsumedBytes;

    /*
     * While mStopped and mIsError can be written from any thread, mDone is written
     * only from the synthesis thread. All three variables are read from the
     * audio playback thread.
     */
    private volatile boolean mStopped;
    private volatile boolean mDone;
    private volatile boolean mIsError;

    private final BlockingAudioTrack mAudioTrack;
    private final EventLogger mLogger;


    SynthesisPlaybackQueueItem(int streamType, int sampleRate,
            int audioFormat, int channelCount,
            float volume, float pan, UtteranceProgressDispatcher dispatcher,
            Object callerIdentity, EventLogger logger) {
        super(dispatcher, callerIdentity);

        mUnconsumedBytes = 0;

        mStopped = false;
        mDone = false;
        mIsError = false;

        mAudioTrack = new BlockingAudioTrack(streamType, sampleRate, audioFormat,
                channelCount, volume, pan);
        mLogger = logger;
    }


    @Override
    public void run() {
        final UtteranceProgressDispatcher dispatcher = getDispatcher();
        dispatcher.dispatchOnStart();


        if (!mAudioTrack.init()) {
            dispatcher.dispatchOnError();
            return;
        }

        try {
            byte[] buffer = null;

            // take() will block until:
            //
            // (a) there is a buffer available to tread. In which case
            // a non null value is returned.
            // OR (b) stop() is called in which case it will return null.
            // OR (c) done() is called in which case it will return null.
            while ((buffer = take()) != null) {
                mAudioTrack.write(buffer);
                mLogger.onAudioDataWritten();
            }

        } catch (InterruptedException ie) {
            if (DBG) Log.d(TAG, "Interrupted waiting for buffers, cleaning up.");
        }

        mAudioTrack.waitAndRelease();

        if (mIsError) {
            dispatcher.dispatchOnError();
        } else {
            dispatcher.dispatchOnDone();
        }

        mLogger.onWriteData();
    }

    @Override
    void stop(boolean isError) {
        try {
            mListLock.lock();

            // Update our internal state.
            mStopped = true;
            mIsError = isError;

            // Wake up the audio playback thread if it was waiting on take().
            // take() will return null since mStopped was true, and will then
            // break out of the data write loop.
            mReadReady.signal();

            // Wake up the synthesis thread if it was waiting on put(). Its
            // buffers will no longer be copied since mStopped is true. The
            // PlaybackSynthesisCallback that this synthesis corresponds to
            // would also have been stopped, and so all calls to
            // Callback.onDataAvailable( ) will return errors too.
            mNotFull.signal();
        } finally {
            mListLock.unlock();
        }

        // Stop the underlying audio track. This will stop sending
        // data to the mixer and discard any pending buffers that the
        // track holds.
        mAudioTrack.stop();
    }

    void done() {
        try {
            mListLock.lock();

            // Update state.
            mDone = true;

            // Unblocks the audio playback thread if it was waiting on take()
            // after having consumed all available buffers. It will then return
            // null and leave the write loop.
            mReadReady.signal();

            // Just so that engines that try to queue buffers after
            // calling done() don't block the synthesis thread forever. Ideally
            // this should be called from the same thread as put() is, and hence
            // this call should be pointless.
            mNotFull.signal();
        } finally {
            mListLock.unlock();
        }
    }


    void put(byte[] buffer) throws InterruptedException {
        try {
            mListLock.lock();
            long unconsumedAudioMs = 0;

            while ((unconsumedAudioMs = mAudioTrack.getAudioLengthMs(mUnconsumedBytes)) >
                    MAX_UNCONSUMED_AUDIO_MS && !mStopped) {
                mNotFull.await();
            }

            // Don't bother queueing the buffer if we've stopped. The playback thread
            // would have woken up when stop() is called (if it was blocked) and will
            // proceed to leave the write loop since take() will return null when
            // stopped.
            if (mStopped) {
                return;
            }

            mDataBufferList.add(new ListEntry(buffer));
            mUnconsumedBytes += buffer.length;
            mReadReady.signal();
        } finally {
            mListLock.unlock();
        }
    }

    private byte[] take() throws InterruptedException {
        try {
            mListLock.lock();

            // Block if there are no available buffers, and stop() has not
            // been called and done() has not been called.
            while (mDataBufferList.size() == 0 && !mStopped && !mDone) {
                mReadReady.await();
            }

            // If stopped, return null so that we can exit the playback loop
            // as soon as possible.
            if (mStopped) {
                return null;
            }

            // Remove the first entry from the queue.
            ListEntry entry = mDataBufferList.poll();

            // This is the normal playback loop exit case, when done() was
            // called. (mDone will be true at this point).
            if (entry == null) {
                return null;
            }

            mUnconsumedBytes -= entry.mBytes.length;
            // Unblock the waiting writer. We use signal() and not signalAll()
            // because there will only be one thread waiting on this (the
            // Synthesis thread).
            mNotFull.signal();

            return entry.mBytes;
        } finally {
            mListLock.unlock();
        }
    }

    static final class ListEntry {
        final byte[] mBytes;

        ListEntry(byte[] bytes) {
            mBytes = bytes;
        }
    }
}
