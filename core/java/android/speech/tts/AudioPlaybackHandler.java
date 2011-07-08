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
import android.util.Log;

import java.util.Iterator;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

class AudioPlaybackHandler {
    private static final String TAG = "TTS.AudioPlaybackHandler";
    private static final boolean DBG = false;

    private static final int MIN_AUDIO_BUFFER_SIZE = 8192;

    private static final int SYNTHESIS_START = 1;
    private static final int SYNTHESIS_DATA_AVAILABLE = 2;
    private static final int SYNTHESIS_COMPLETE_DATA_AVAILABLE = 3;
    private static final int SYNTHESIS_DONE = 4;

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
     */
    synchronized public void stop(MessageParams token) {
        if (token == null) {
            return;
        }

        removeMessages(token);

        if (token.getType() == MessageParams.TYPE_SYNTHESIS) {
            AudioTrack current = ((SynthesisMessageParams) token).getAudioTrack();
            if (current != null) {
                // Stop the current audio track if it's still playing.
                // The audio track is thread safe in this regard.
                current.stop();
            }
            mQueue.add(new ListEntry(SYNTHESIS_DONE, token, HIGH_PRIORITY));
        } else  {
            final MessageParams current = getCurrentParams();

            if (current != null) {
                if (token.getType() == MessageParams.TYPE_AUDIO) {
                    ((AudioMessageParams) current).getPlayer().stop();
                } else if (token.getType() == MessageParams.TYPE_SILENCE) {
                    ((SilenceMessageParams) current).getConditionVariable().open();
                }
            }
        }
    }

    synchronized public void removePlaybackItems(String callingApp) {
        removeMessages(callingApp);
        stop(getCurrentParams());
    }

    synchronized public void removeAllItems() {
        removeAllMessages();
        stop(getCurrentParams());
    }

    /**
     * Shut down the audio playback thread.
     */
    synchronized public void quit() {
        stop(getCurrentParams());
        mQueue.add(new ListEntry(SHUTDOWN, null, HIGH_PRIORITY));
    }

    void enqueueSynthesisStart(SynthesisMessageParams token) {
        mQueue.add(new ListEntry(SYNTHESIS_START, token));
    }

    void enqueueSynthesisDataAvailable(SynthesisMessageParams token) {
        mQueue.add(new ListEntry(SYNTHESIS_DATA_AVAILABLE, token));
    }

    void enqueueSynthesisCompleteDataAvailable(SynthesisMessageParams token) {
        mQueue.add(new ListEntry(SYNTHESIS_COMPLETE_DATA_AVAILABLE, token));
    }

    void enqueueSynthesisDone(SynthesisMessageParams token) {
        mQueue.add(new ListEntry(SYNTHESIS_DONE, token));
    }

    void enqueueAudio(AudioMessageParams token) {
        mQueue.add(new ListEntry(PLAY_AUDIO, token));
    }

    void enqueueSilence(SilenceMessageParams token) {
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
     * Remove all messages from the queue that contain the supplied token.
     * Note that the Iterator is thread safe, and other methods can safely
     * continue adding to the queue at this point.
     */
    synchronized private void removeMessages(MessageParams token) {
        if (token == null) {
            return;
        }

        Iterator<ListEntry> it = mQueue.iterator();

        while (it.hasNext()) {
            final ListEntry current = it.next();
            if (current.mMessage == token) {
                it.remove();
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
        } else if (entry.mWhat == SYNTHESIS_COMPLETE_DATA_AVAILABLE) {
            handleSynthesisCompleteDataAvailable(msg);
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
        final AudioTrack audioTrack = createStreamingAudioTrack(
                param.mStreamType, param.mSampleRateInHz, param.mAudioFormat,
                param.mChannelCount, param.mVolume, param.mPan);

        if (DBG) Log.d(TAG, "Created audio track [" + audioTrack.hashCode() + "]");

        param.setAudioTrack(audioTrack);
    }

    // More data available to be flushed to the audio track.
    private void handleSynthesisDataAvailable(MessageParams msg) {
        final SynthesisMessageParams param = (SynthesisMessageParams) msg;
        if (param.getAudioTrack() == null) {
            Log.w(TAG, "Error : null audio track in handleDataAvailable.");
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
        while (count < bufferCopy.mLength) {
            // Note that we don't take bufferCopy.mOffset into account because
            // it is guaranteed to be 0.
            int written = audioTrack.write(bufferCopy.mBytes, count, bufferCopy.mLength);
            if (written <= 0) {
                break;
            }
            count += written;
        }

        param.mLogger.onPlaybackStart();
    }

    private void handleSynthesisDone(MessageParams msg) {
        final SynthesisMessageParams params = (SynthesisMessageParams) msg;
        handleSynthesisDone(params);
        // This call is delayed more than it should be, but we are
        // certain at this point that we have all the data we want.
        params.mLogger.onWriteData();
    }

    // Flush all remaining data to the audio track, stop it and release
    // all it's resources.
    private void handleSynthesisDone(SynthesisMessageParams params) {
        if (DBG) Log.d(TAG, "handleSynthesisDone()");
        final AudioTrack audioTrack = params.getAudioTrack();

        try {
            if (audioTrack != null) {
                if (DBG) Log.d(TAG, "Releasing audio track [" + audioTrack.hashCode() + "]");
                // The last call to AudioTrack.write( ) will return only after
                // all data from the audioTrack has been sent to the mixer, so
                // it's safe to release at this point.
                audioTrack.release();
            }
        } finally {
            params.setAudioTrack(null);
            params.getDispatcher().dispatchUtteranceCompleted();
            mLastSynthesisRequest = null;
        }
    }

    private void handleSynthesisCompleteDataAvailable(MessageParams msg) {
        final SynthesisMessageParams params = (SynthesisMessageParams) msg;
        if (DBG) Log.d(TAG, "completeAudioAvailable(" + params + ")");

        params.mLogger.onPlaybackStart();

        // Channel config and bytes per frame are checked before
        // this message is sent.
        int channelConfig = AudioPlaybackHandler.getChannelConfig(params.mChannelCount);
        int bytesPerFrame = AudioPlaybackHandler.getBytesPerFrame(params.mAudioFormat);

        SynthesisMessageParams.ListEntry entry = params.getNextBuffer();

        if (entry == null) {
            Log.w(TAG, "completeDataAvailable : No buffers available to play.");
            return;
        }

        final AudioTrack audioTrack = new AudioTrack(params.mStreamType, params.mSampleRateInHz,
                channelConfig, params.mAudioFormat, entry.mLength, AudioTrack.MODE_STATIC);

        // So that handleDone can access this correctly.
        params.mAudioTrack = audioTrack;

        try {
            audioTrack.write(entry.mBytes, entry.mOffset, entry.mLength);
            setupVolume(audioTrack, params.mVolume, params.mPan);
            audioTrack.play();
            blockUntilDone(audioTrack, bytesPerFrame, entry.mLength);
            if (DBG) Log.d(TAG, "Wrote data to audio track successfully : " + entry.mLength);
        } catch (IllegalStateException ex) {
            Log.e(TAG, "Playback error", ex);
        } finally {
            handleSynthesisDone(msg);
        }
    }


    private static void blockUntilDone(AudioTrack audioTrack, int bytesPerFrame, int length) {
        int lengthInFrames = length / bytesPerFrame;
        int currentPosition = 0;
        while ((currentPosition = audioTrack.getPlaybackHeadPosition()) < lengthInFrames) {
            long estimatedTimeMs = ((lengthInFrames - currentPosition) * 1000) /
                    audioTrack.getSampleRate();
            audioTrack.getPlayState();
            if (DBG) Log.d(TAG, "About to sleep for : " + estimatedTimeMs + " ms," +
                    " Playback position : " + currentPosition);
            try {
                Thread.sleep(estimatedTimeMs);
            } catch (InterruptedException ie) {
                break;
            }
        }
    }

    private static AudioTrack createStreamingAudioTrack(int streamType, int sampleRateInHz,
            int audioFormat, int channelCount, float volume, float pan) {
        int channelConfig = getChannelConfig(channelCount);

        int minBufferSizeInBytes
                = AudioTrack.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        int bufferSizeInBytes = Math.max(MIN_AUDIO_BUFFER_SIZE, minBufferSizeInBytes);

        AudioTrack audioTrack = new AudioTrack(streamType, sampleRateInHz, channelConfig,
                audioFormat, bufferSizeInBytes, AudioTrack.MODE_STREAM);
        if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "Unable to create audio track.");
            audioTrack.release();
            return null;
        }

        setupVolume(audioTrack, volume, pan);
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

    static int getBytesPerFrame(int audioFormat) {
        if (audioFormat == AudioFormat.ENCODING_PCM_8BIT) {
            return 1;
        } else if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
            return 2;
        }

        return -1;
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
