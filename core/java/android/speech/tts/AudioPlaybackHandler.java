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
import android.text.TextUtils;
import android.util.Log;

import java.util.Iterator;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

class AudioPlaybackHandler {
    private static final String TAG = "TTS.AudioPlaybackHandler";
    private static final boolean DBG_THREADING = false;
    private static final boolean DBG = false;

    private static final int MIN_AUDIO_BUFFER_SIZE = 8192;

    private static final int SYNTHESIS_START = 1;
    private static final int SYNTHESIS_DATA_AVAILABLE = 2;
    private static final int SYNTHESIS_DONE = 3;

    private static final int PLAY_AUDIO = 5;
    private static final int PLAY_SILENCE = 6;

    private static final int SHUTDOWN = -1;

    private static final int DEFAULT_PRIORITY = 1;
    private static final int HIGH_PRIORITY = 0;

    private final PriorityBlockingQueue<ListEntry> mQueue =
            new PriorityBlockingQueue<ListEntry>();
    private final Thread mHandlerThread;

    private volatile MessageParams mCurrentParams = null;
    // Used only for book keeping and error detection.
    private volatile SynthesisMessageParams mLastSynthesisRequest = null;
    // Used to order incoming messages in our priority queue.
    private final AtomicLong mSequenceIdCtr = new AtomicLong(0);


    AudioPlaybackHandler() {
        mHandlerThread = new Thread(new MessageLoop(), "TTS.AudioPlaybackThread");
    }

    public void start() {
        mHandlerThread.start();
    }

    /**
     * Stops all synthesis for a given {@code token}. If the current token
     * is currently being processed, an effort will be made to stop it but
     * that is not guaranteed.
     *
     * NOTE: This assumes that all other messages in the queue with {@code token}
     * have been removed already.
     *
     * NOTE: Must be called synchronized on {@code AudioPlaybackHandler.this}.
     */
    private void stop(MessageParams token) {
        if (token == null) {
            return;
        }

        if (DBG) Log.d(TAG, "Stopping token : " + token);

        if (token.getType() == MessageParams.TYPE_SYNTHESIS) {
            AudioTrack current = ((SynthesisMessageParams) token).getAudioTrack();
            if (current != null) {
                // Stop the current audio track if it's still playing.
                // The audio track is thread safe in this regard. The current
                // handleSynthesisDataAvailable call will return soon after this
                // call.
                current.stop();
            }
            // This is safe because PlaybackSynthesisCallback#stop would have
            // been called before this method, and will no longer enqueue any
            // audio for this token.
            //
            // (Even if it did, all it would result in is a warning message).
            mQueue.add(new ListEntry(SYNTHESIS_DONE, token, HIGH_PRIORITY));
        } else if (token.getType() == MessageParams.TYPE_AUDIO) {
            ((AudioMessageParams) token).getPlayer().stop();
            // No cleanup required for audio messages.
        } else if (token.getType() == MessageParams.TYPE_SILENCE) {
            ((SilenceMessageParams) token).getConditionVariable().open();
            // No cleanup required for silence messages.
        }
    }

    // -----------------------------------------------------
    // Methods that add and remove elements from the queue. These do not
    // need to be synchronized strictly speaking, but they make the behaviour
    // a lot more predictable. (though it would still be correct without
    // synchronization).
    // -----------------------------------------------------

    synchronized public void removePlaybackItems(Object callerIdentity) {
        if (DBG_THREADING) Log.d(TAG, "Removing all callback items for : " + callerIdentity);
        removeMessages(callerIdentity);

        final MessageParams current = getCurrentParams();
        if (current != null && (current.getCallerIdentity() == callerIdentity)) {
            stop(current);
        }

        final MessageParams lastSynthesis = mLastSynthesisRequest;

        if (lastSynthesis != null && lastSynthesis != current &&
                (lastSynthesis.getCallerIdentity() == callerIdentity)) {
            stop(lastSynthesis);
        }
    }

    synchronized public void removeAllItems() {
        if (DBG_THREADING) Log.d(TAG, "Removing all items");
        removeAllMessages();

        final MessageParams current = getCurrentParams();
        final MessageParams lastSynthesis = mLastSynthesisRequest;
        stop(current);

        if (lastSynthesis != null && lastSynthesis != current) {
            stop(lastSynthesis);
        }
    }

    /**
     * @return false iff the queue is empty and no queue item is currently
     *        being handled, true otherwise.
     */
    public boolean isSpeaking() {
        return (mQueue.peek() != null) || (mCurrentParams != null);
    }

    /**
     * Shut down the audio playback thread.
     */
    synchronized public void quit() {
        removeAllMessages();
        stop(getCurrentParams());
        mQueue.add(new ListEntry(SHUTDOWN, null, HIGH_PRIORITY));
    }

    synchronized void enqueueSynthesisStart(SynthesisMessageParams token) {
        if (DBG_THREADING) Log.d(TAG, "Enqueuing synthesis start : " + token);
        mQueue.add(new ListEntry(SYNTHESIS_START, token));
    }

    synchronized void enqueueSynthesisDataAvailable(SynthesisMessageParams token) {
        if (DBG_THREADING) Log.d(TAG, "Enqueuing synthesis data available : " + token);
        mQueue.add(new ListEntry(SYNTHESIS_DATA_AVAILABLE, token));
    }

    synchronized void enqueueSynthesisDone(SynthesisMessageParams token) {
        if (DBG_THREADING) Log.d(TAG, "Enqueuing synthesis done : " + token);
        mQueue.add(new ListEntry(SYNTHESIS_DONE, token));
    }

    synchronized void enqueueAudio(AudioMessageParams token) {
        if (DBG_THREADING) Log.d(TAG, "Enqueuing audio : " + token);
        mQueue.add(new ListEntry(PLAY_AUDIO, token));
    }

    synchronized void enqueueSilence(SilenceMessageParams token) {
        if (DBG_THREADING) Log.d(TAG, "Enqueuing silence : " + token);
        mQueue.add(new ListEntry(PLAY_SILENCE, token));
    }

    // -----------------------------------------
    // End of public API methods.
    // -----------------------------------------

    // -----------------------------------------
    // Methods for managing the message queue.
    // -----------------------------------------

    /*
     * The MessageLoop is a handler like implementation that
     * processes messages from a priority queue.
     */
    private final class MessageLoop implements Runnable {
        @Override
        public void run() {
            while (true) {
                ListEntry entry = null;
                try {
                    entry = mQueue.take();
                } catch (InterruptedException ie) {
                    return;
                }

                if (entry.mWhat == SHUTDOWN) {
                    if (DBG) Log.d(TAG, "MessageLoop : Shutting down");
                    return;
                }

                if (DBG) {
                    Log.d(TAG, "MessageLoop : Handling message :" + entry.mWhat
                            + " ,seqId : " + entry.mSequenceId);
                }

                setCurrentParams(entry.mMessage);
                handleMessage(entry);
                setCurrentParams(null);
            }
        }
    }

    /*
     * Atomically clear the queue of all messages.
     */
    synchronized private void removeAllMessages() {
        mQueue.clear();
    }

    /*
     * Remove all messages that originate from a given calling app.
     */
    synchronized private void removeMessages(Object callerIdentity) {
        Iterator<ListEntry> it = mQueue.iterator();

        while (it.hasNext()) {
            final ListEntry current = it.next();
            // The null check is to prevent us from removing control messages,
            // such as a shutdown message.
            if (current.mMessage != null &&
                    current.mMessage.getCallerIdentity() == callerIdentity) {
                it.remove();
            }
        }
    }

    /*
     * An element of our priority queue of messages. Each message has a priority,
     * and a sequence id (defined by the order of enqueue calls). Among messages
     * with the same priority, messages that were received earlier win out.
     */
    private final class ListEntry implements Comparable<ListEntry> {
        final int mWhat;
        final MessageParams mMessage;
        final int mPriority;
        final long mSequenceId;

        private ListEntry(int what, MessageParams message) {
            this(what, message, DEFAULT_PRIORITY);
        }

        private ListEntry(int what, MessageParams message, int priority) {
            mWhat = what;
            mMessage = message;
            mPriority = priority;
            mSequenceId = mSequenceIdCtr.incrementAndGet();
        }

        @Override
        public int compareTo(ListEntry that) {
            if (that == this) {
                return 0;
            }

            // Note that this is always 0, 1 or -1.
            int priorityDiff = mPriority - that.mPriority;
            if (priorityDiff == 0) {
                // The == case cannot occur.
                return (mSequenceId < that.mSequenceId) ? -1 : 1;
            }

            return priorityDiff;
        }
    }

    private void setCurrentParams(MessageParams p) {
        if (DBG_THREADING) {
            if (p != null) {
                Log.d(TAG, "Started handling :" + p);
            } else {
                Log.d(TAG, "End handling : " + mCurrentParams);
            }
        }
        mCurrentParams = p;
    }

    private MessageParams getCurrentParams() {
        return mCurrentParams;
    }

    // -----------------------------------------
    // Methods for dealing with individual messages, the methods
    // below do the actual work.
    // -----------------------------------------

    private void handleMessage(ListEntry entry) {
        final MessageParams msg = entry.mMessage;
        if (entry.mWhat == SYNTHESIS_START) {
            handleSynthesisStart(msg);
        } else if (entry.mWhat == SYNTHESIS_DATA_AVAILABLE) {
            handleSynthesisDataAvailable(msg);
        } else if (entry.mWhat == SYNTHESIS_DONE) {
            handleSynthesisDone(msg);
        } else if (entry.mWhat == PLAY_AUDIO) {
            handleAudio(msg);
        } else if (entry.mWhat == PLAY_SILENCE) {
            handleSilence(msg);
        }
    }

    // Currently implemented as blocking the audio playback thread for the
    // specified duration. If a call to stop() is made, the thread
    // unblocks.
    private void handleSilence(MessageParams msg) {
        if (DBG) Log.d(TAG, "handleSilence()");
        SilenceMessageParams params = (SilenceMessageParams) msg;
        params.getDispatcher().dispatchOnStart();
        if (params.getSilenceDurationMs() > 0) {
            params.getConditionVariable().block(params.getSilenceDurationMs());
        }
        params.getDispatcher().dispatchOnDone();
        if (DBG) Log.d(TAG, "handleSilence() done.");
    }

    // Plays back audio from a given URI. No TTS engine involvement here.
    private void handleAudio(MessageParams msg) {
        if (DBG) Log.d(TAG, "handleAudio()");
        AudioMessageParams params = (AudioMessageParams) msg;
        params.getDispatcher().dispatchOnStart();
        // Note that the BlockingMediaPlayer spawns a separate thread.
        //
        // TODO: This can be avoided.
        params.getPlayer().startAndWait();
        params.getDispatcher().dispatchOnDone();
        if (DBG) Log.d(TAG, "handleAudio() done.");
    }

    // Denotes the start of a new synthesis request. We create a new
    // audio track, and prepare it for incoming data.
    //
    // Note that since all TTS synthesis happens on a single thread, we
    // should ALWAYS see the following order :
    //
    // handleSynthesisStart -> handleSynthesisDataAvailable(*) -> handleSynthesisDone
    // OR
    // handleSynthesisCompleteDataAvailable.
    private void handleSynthesisStart(MessageParams msg) {
        if (DBG) Log.d(TAG, "handleSynthesisStart()");
        final SynthesisMessageParams param = (SynthesisMessageParams) msg;

        // Oops, looks like the engine forgot to call done(). We go through
        // extra trouble to clean the data to prevent the AudioTrack resources
        // from being leaked.
        if (mLastSynthesisRequest != null) {
            Log.e(TAG, "Error : Missing call to done() for request : " +
                    mLastSynthesisRequest);
            handleSynthesisDone(mLastSynthesisRequest);
        }

        mLastSynthesisRequest = param;

        // Create the audio track.
        final AudioTrack audioTrack = createStreamingAudioTrack(param);

        if (DBG) Log.d(TAG, "Created audio track [" + audioTrack.hashCode() + "]");

        param.setAudioTrack(audioTrack);
        msg.getDispatcher().dispatchOnStart();
    }

    // More data available to be flushed to the audio track.
    private void handleSynthesisDataAvailable(MessageParams msg) {
        final SynthesisMessageParams param = (SynthesisMessageParams) msg;
        if (param.getAudioTrack() == null) {
            Log.w(TAG, "Error : null audio track in handleDataAvailable : " + param);
            return;
        }

        if (param != mLastSynthesisRequest) {
            Log.e(TAG, "Call to dataAvailable without done() / start()");
            return;
        }

        final AudioTrack audioTrack = param.getAudioTrack();
        final SynthesisMessageParams.ListEntry bufferCopy = param.getNextBuffer();

        if (bufferCopy == null) {
            Log.e(TAG, "No buffers available to play.");
            return;
        }

        int playState = audioTrack.getPlayState();
        if (playState == AudioTrack.PLAYSTATE_STOPPED) {
            if (DBG) Log.d(TAG, "AudioTrack stopped, restarting : " + audioTrack.hashCode());
            audioTrack.play();
        }
        int count = 0;
        while (count < bufferCopy.mBytes.length) {
            // Note that we don't take bufferCopy.mOffset into account because
            // it is guaranteed to be 0.
            int written = audioTrack.write(bufferCopy.mBytes, count, bufferCopy.mBytes.length);
            if (written <= 0) {
                break;
            }
            count += written;
        }
        param.mBytesWritten += count;
        param.mLogger.onPlaybackStart();
    }

    // Wait for the audio track to stop playing, and then release its resources.
    private void handleSynthesisDone(MessageParams msg) {
        final SynthesisMessageParams params = (SynthesisMessageParams) msg;

        if (DBG) Log.d(TAG, "handleSynthesisDone()");
        final AudioTrack audioTrack = params.getAudioTrack();

        if (audioTrack == null) {
            params.getDispatcher().dispatchOnError();
            return;
        }

        if (params.mBytesWritten < params.mAudioBufferSize) {
            if (DBG) Log.d(TAG, "Stopping audio track to flush audio, state was : " +
                    audioTrack.getPlayState());
            params.mIsShortUtterance = true;
            audioTrack.stop();
        }

        if (DBG) Log.d(TAG, "Waiting for audio track to complete : " +
                audioTrack.hashCode());
        blockUntilDone(params);
        if (DBG) Log.d(TAG, "Releasing audio track [" + audioTrack.hashCode() + "]");

        // The last call to AudioTrack.write( ) will return only after
        // all data from the audioTrack has been sent to the mixer, so
        // it's safe to release at this point. Make sure release() and the call
        // that set the audio track to null are performed atomically.
        synchronized (this) {
            // Never allow the audioTrack to be observed in a state where
            // it is released but non null. The only case this might happen
            // is in the various stopFoo methods that call AudioTrack#stop from
            // different threads, but they are synchronized on AudioPlayBackHandler#this
            // too.
            audioTrack.release();
            params.setAudioTrack(null);
        }
        if (params.isError()) {
            params.getDispatcher().dispatchOnError();
        } else {
            params.getDispatcher().dispatchOnDone();
        }
        mLastSynthesisRequest = null;
        params.mLogger.onWriteData();
    }

    /**
     * The minimum increment of time to wait for an audiotrack to finish
     * playing.
     */
    private static final long MIN_SLEEP_TIME_MS = 20;

    /**
     * The maximum increment of time to sleep while waiting for an audiotrack
     * to finish playing.
     */
    private static final long MAX_SLEEP_TIME_MS = 2500;

    /**
     * The maximum amount of time to wait for an audio track to make progress while
     * it remains in PLAYSTATE_PLAYING. This should never happen in normal usage, but
     * could happen in exceptional circumstances like a media_server crash.
     */
    private static final long MAX_PROGRESS_WAIT_MS = MAX_SLEEP_TIME_MS;

    private static void blockUntilDone(SynthesisMessageParams params) {
        if (params.mAudioTrack == null || params.mBytesWritten <= 0) {
            return;
        }

        if (params.mIsShortUtterance) {
            // In this case we would have called AudioTrack#stop() to flush
            // buffers to the mixer. This makes the playback head position
            // unobservable and notification markers do not work reliably. We
            // have no option but to wait until we think the track would finish
            // playing and release it after.
            //
            // This isn't as bad as it looks because (a) We won't end up waiting
            // for much longer than we should because even at 4khz mono, a short
            // utterance weighs in at about 2 seconds, and (b) such short utterances
            // are expected to be relatively infrequent and in a stream of utterances
            // this shows up as a slightly longer pause.
            blockUntilEstimatedCompletion(params);
        } else {
            blockUntilCompletion(params);
        }
    }

    private static void blockUntilEstimatedCompletion(SynthesisMessageParams params) {
        final int lengthInFrames = params.mBytesWritten / params.mBytesPerFrame;
        final long estimatedTimeMs = (lengthInFrames * 1000 / params.mSampleRateInHz);

        if (DBG) Log.d(TAG, "About to sleep for: " + estimatedTimeMs + "ms for a short utterance");

        try {
            Thread.sleep(estimatedTimeMs);
        } catch (InterruptedException ie) {
            // Do nothing.
        }
    }

    private static void blockUntilCompletion(SynthesisMessageParams params) {
        final AudioTrack audioTrack = params.mAudioTrack;
        final int lengthInFrames = params.mBytesWritten / params.mBytesPerFrame;

        int previousPosition = -1;
        int currentPosition = 0;
        long blockedTimeMs = 0;

        while ((currentPosition = audioTrack.getPlaybackHeadPosition()) < lengthInFrames &&
                audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {

            final long estimatedTimeMs = ((lengthInFrames - currentPosition) * 1000) /
                    audioTrack.getSampleRate();
            final long sleepTimeMs = clip(estimatedTimeMs, MIN_SLEEP_TIME_MS, MAX_SLEEP_TIME_MS);

            // Check if the audio track has made progress since the last loop
            // iteration. We should then add in the amount of time that was
            // spent sleeping in the last iteration.
            if (currentPosition == previousPosition) {
                // This works only because the sleep time that would have been calculated
                // would be the same in the previous iteration too.
                blockedTimeMs += sleepTimeMs;
                // If we've taken too long to make progress, bail.
                if (blockedTimeMs > MAX_PROGRESS_WAIT_MS) {
                    Log.w(TAG, "Waited unsuccessfully for " + MAX_PROGRESS_WAIT_MS + "ms " +
                            "for AudioTrack to make progress, Aborting");
                    break;
                }
            } else {
                blockedTimeMs = 0;
            }
            previousPosition = currentPosition;

            if (DBG) Log.d(TAG, "About to sleep for : " + sleepTimeMs + " ms," +
                    " Playback position : " + currentPosition + ", Length in frames : "
                    + lengthInFrames);
            try {
                Thread.sleep(sleepTimeMs);
            } catch (InterruptedException ie) {
                break;
            }
        }
    }

    private static final long clip(long value, long min, long max) {
        if (value < min) {
            return min;
        }

        if (value > max) {
            return max;
        }

        return value;
    }

    private static AudioTrack createStreamingAudioTrack(SynthesisMessageParams params) {
        final int channelConfig = getChannelConfig(params.mChannelCount);
        final int sampleRateInHz = params.mSampleRateInHz;
        final int audioFormat = params.mAudioFormat;

        int minBufferSizeInBytes
                = AudioTrack.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        int bufferSizeInBytes = Math.max(MIN_AUDIO_BUFFER_SIZE, minBufferSizeInBytes);

        AudioTrack audioTrack = new AudioTrack(params.mStreamType, sampleRateInHz, channelConfig,
                audioFormat, bufferSizeInBytes, AudioTrack.MODE_STREAM);
        if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "Unable to create audio track.");
            audioTrack.release();
            return null;
        }
        params.mAudioBufferSize = bufferSizeInBytes;

        setupVolume(audioTrack, params.mVolume, params.mPan);
        return audioTrack;
    }

    static int getChannelConfig(int channelCount) {
        if (channelCount == 1) {
            return AudioFormat.CHANNEL_OUT_MONO;
        } else if (channelCount == 2){
            return AudioFormat.CHANNEL_OUT_STEREO;
        }

        return 0;
    }

    private static void setupVolume(AudioTrack audioTrack, float volume, float pan) {
        float vol = clip(volume, 0.0f, 1.0f);
        float panning = clip(pan, -1.0f, 1.0f);
        float volLeft = vol;
        float volRight = vol;
        if (panning > 0.0f) {
            volLeft *= (1.0f - panning);
        } else if (panning < 0.0f) {
            volRight *= (1.0f + panning);
        }
        if (DBG) Log.d(TAG, "volLeft=" + volLeft + ",volRight=" + volRight);
        if (audioTrack.setStereoVolume(volLeft, volRight) != AudioTrack.SUCCESS) {
            Log.e(TAG, "Failed to set volume");
        }
    }

    private static float clip(float value, float min, float max) {
        return value > max ? max : (value < min ? min : value);
    }

}
