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

    synchronized public void removePlaybackItems(String callingApp) {
        if (DBG_THREADING) Log.d(TAG, "Removing all callback items for : " + callingApp);
        removeMessages(callingApp);

        final MessageParams current = getCurrentParams();
        if (current != null && TextUtils.equals(callingApp, current.getCallingApp())) {
            stop(current);
        }
    }

    synchronized public void removeAllItems() {
        if (DBG_THREADING) Log.d(TAG, "Removing all items");
        removeAllMessages();
        stop(getCurrentParams());
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
    synchronized private void removeMessages(String callingApp) {
        Iterator<ListEntry> it = mQueue.iterator();

        while (it.hasNext()) {
            final ListEntry current = it.next();
            // The null check is to prevent us from removing control messages,
            // such as a shutdown message.
            if (current.mMessage != null &&
                    callingApp.equals(current.mMessage.getCallingApp())) {
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
        if (params.getSilenceDurationMs() > 0) {
            params.getConditionVariable().block(params.getSilenceDurationMs());
        }
        params.getDispatcher().dispatchUtteranceCompleted();
        if (DBG) Log.d(TAG, "handleSilence() done.");
    }

    // Plays back audio from a given URI. No TTS engine involvement here.
    private void handleAudio(MessageParams msg) {
        if (DBG) Log.d(TAG, "handleAudio()");
        AudioMessageParams params = (AudioMessageParams) msg;
        // Note that the BlockingMediaPlayer spawns a separate thread.
        //
        // TODO: This can be avoided.
        params.getPlayer().startAndWait();
        params.getDispatcher().dispatchUtteranceCompleted();
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
            Log.w(TAG, "Error : Missing call to done() for request : " +
                    mLastSynthesisRequest);
            handleSynthesisDone(mLastSynthesisRequest);
        }

        mLastSynthesisRequest = param;

        // Create the audio track.
        final AudioTrack audioTrack = createStreamingAudioTrack(param);

        if (DBG) Log.d(TAG, "Created audio track [" + audioTrack.hashCode() + "]");

        param.setAudioTrack(audioTrack);
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
            return;
        }

        if (params.mBytesWritten < params.mAudioBufferSize) {
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
        params.getDispatcher().dispatchUtteranceCompleted();
        mLastSynthesisRequest = null;
        params.mLogger.onWriteData();
    }

    /**
     * The minimum increment of time to wait for an audiotrack to finish
     * playing.
     */
    private static final long MIN_SLEEP_TIME_MS = 20;

    private static void blockUntilDone(SynthesisMessageParams params) {
        if (params.mAudioTrack == null || params.mBytesWritten <= 0) {
            return;
        }

        final AudioTrack audioTrack = params.mAudioTrack;
        final int bytesPerFrame = params.mBytesPerFrame;
        final int lengthInBytes = params.mBytesWritten;
        final int lengthInFrames = lengthInBytes / bytesPerFrame;

        int currentPosition = 0;
        while ((currentPosition = audioTrack.getPlaybackHeadPosition()) < lengthInFrames) {
            if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                break;
            }

            final long estimatedTimeMs = ((lengthInFrames - currentPosition) * 1000) /
                    audioTrack.getSampleRate();

            final long sleepTimeMs = Math.max(estimatedTimeMs, MIN_SLEEP_TIME_MS);

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
