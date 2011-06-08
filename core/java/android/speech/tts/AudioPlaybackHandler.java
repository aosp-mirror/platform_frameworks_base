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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.speech.tts.SynthesisMessageParams.ListEntry;
import android.util.Log;

class AudioPlaybackHandler extends Handler {
    private static final String TAG = "TTS.AudioPlaybackHandler";
    private static final boolean DBG = false;

    private static final int MIN_AUDIO_BUFFER_SIZE = 8192;

    private static final int SYNTHESIS_START = 1;
    private static final int SYNTHESIS_DATA_AVAILABLE = 2;
    private static final int SYNTHESIS_COMPLETE_DATA_AVAILABLE = 3;
    private static final int SYNTHESIS_DONE = 4;

    private static final int PLAY_AUDIO = 5;
    private static final int PLAY_SILENCE = 6;

    // Accessed by multiple threads, synchronized by "this".
    private MessageParams mCurrentParams;
    // Used only for book keeping and error detection.
    private SynthesisMessageParams mLastSynthesisRequest;

    AudioPlaybackHandler(Looper looper) {
        super(looper);
    }

    @Override
    public synchronized void handleMessage(Message msg) {
        if (msg.what == SYNTHESIS_START) {
            mCurrentParams = (SynthesisMessageParams) msg.obj;
            handleSynthesisStart(msg);
        } else if (msg.what == SYNTHESIS_DATA_AVAILABLE) {
            handleSynthesisDataAvailable(msg);
        } else if (msg.what == SYNTHESIS_DONE) {
            handleSynthesisDone(msg);
        } else if (msg.what == SYNTHESIS_COMPLETE_DATA_AVAILABLE) {
            handleSynthesisCompleteDataAvailable(msg);
        } else if (msg.what == PLAY_AUDIO) {
            handleAudio(msg);
        } else if (msg.what == PLAY_SILENCE) {
            handleSilence(msg);
        }

        mCurrentParams = null;
    }

    /**
     * Stops all synthesis for a given {@code token}. If the current token
     * is currently being processed, an effort will be made to stop it but
     * that is not guaranteed.
     */
    synchronized public void stop(MessageParams token) {
        removeCallbacksAndMessages(token);

        if (token.getType() == MessageParams.TYPE_SYNTHESIS) {
            sendMessageAtFrontOfQueue(obtainMessage(SYNTHESIS_DONE, token));
        } else if (token == mCurrentParams) {
            if (token.getType() == MessageParams.TYPE_AUDIO) {
                ((AudioMessageParams) mCurrentParams).getPlayer().stop();
            } else if (token.getType() == MessageParams.TYPE_SILENCE) {
                ((SilenceMessageParams) mCurrentParams).getConditionVariable().open();
            }
        }
    }

    /**
     * Shut down the audio playback thread.
     */
    synchronized public void quit() {
        if (mCurrentParams != null) {
            stop(mCurrentParams);
        }
        getLooper().quit();
    }

    void enqueueSynthesisStart(SynthesisMessageParams token) {
        sendMessage(obtainMessage(SYNTHESIS_START, token));
    }

    void enqueueSynthesisDataAvailable(SynthesisMessageParams token) {
        sendMessage(obtainMessage(SYNTHESIS_DATA_AVAILABLE, token));
    }

    void enqueueSynthesisCompleteDataAvailable(SynthesisMessageParams token) {
        sendMessage(obtainMessage(SYNTHESIS_COMPLETE_DATA_AVAILABLE, token));
    }

    void enqueueSynthesisDone(SynthesisMessageParams token) {
        sendMessage(obtainMessage(SYNTHESIS_DONE, token));
    }

    void enqueueAudio(AudioMessageParams token) {
        sendMessage(obtainMessage(PLAY_AUDIO, token));
    }

    void enqueueSilence(SilenceMessageParams token) {
        sendMessage(obtainMessage(PLAY_SILENCE, token));
    }

    // -----------------------------------------
    // End of public API methods.
    // -----------------------------------------

    // Currently implemented as blocking the audio playback thread for the
    // specified duration. If a call to stop() is made, the thread
    // unblocks.
    private void handleSilence(Message msg) {
        if (DBG) Log.d(TAG, "handleSilence()");
        SilenceMessageParams params = (SilenceMessageParams) msg.obj;
        if (params.getSilenceDurationMs() > 0) {
            params.getConditionVariable().block(params.getSilenceDurationMs());
        }
        params.getDispatcher().dispatchUtteranceCompleted();
        if (DBG) Log.d(TAG, "handleSilence() done.");
    }

    // Plays back audio from a given URI. No TTS engine involvement here.
    private void handleAudio(Message msg) {
        if (DBG) Log.d(TAG, "handleAudio()");
        AudioMessageParams params = (AudioMessageParams) msg.obj;
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
    private void handleSynthesisStart(Message msg) {
        if (DBG) Log.d(TAG, "handleSynthesisStart()");
        final SynthesisMessageParams param = (SynthesisMessageParams) msg.obj;

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

        param.setAudioTrack(audioTrack);
    }

    // More data available to be flushed to the audio track.
    private void handleSynthesisDataAvailable(Message msg) {
        final SynthesisMessageParams param = (SynthesisMessageParams) msg.obj;
        if (param.getAudioTrack() == null) {
            Log.w(TAG, "Error : null audio track in handleDataAvailable.");
            return;
        }

        if (param != mLastSynthesisRequest) {
            Log.e(TAG, "Call to dataAvailable without done() / start()");
            return;
        }

        final AudioTrack audioTrack = param.getAudioTrack();
        final ListEntry bufferCopy = param.getNextBuffer();

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
    }

    private void handleSynthesisDone(Message msg) {
        final SynthesisMessageParams params = (SynthesisMessageParams) msg.obj;
        handleSynthesisDone(params);
    }

    // Flush all remaining data to the audio track, stop it and release
    // all it's resources.
    private void handleSynthesisDone(SynthesisMessageParams params) {
        if (DBG) Log.d(TAG, "handleSynthesisDone()");
        final AudioTrack audioTrack = params.getAudioTrack();

        try {
            if (audioTrack != null) {
                audioTrack.flush();
                audioTrack.stop();
                audioTrack.release();
            }
        } finally {
            params.setAudioTrack(null);
            params.getDispatcher().dispatchUtteranceCompleted();
            mLastSynthesisRequest = null;
        }
    }

    private void handleSynthesisCompleteDataAvailable(Message msg) {
        final SynthesisMessageParams params = (SynthesisMessageParams) msg.obj;
        if (DBG) Log.d(TAG, "completeAudioAvailable(" + params + ")");

        // Channel config and bytes per frame are checked before
        // this message is sent.
        int channelConfig = AudioPlaybackHandler.getChannelConfig(params.mChannelCount);
        int bytesPerFrame = AudioPlaybackHandler.getBytesPerFrame(params.mAudioFormat);

        ListEntry entry = params.getNextBuffer();

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
