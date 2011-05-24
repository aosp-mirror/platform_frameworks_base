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
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

/**
 * Speech synthesis request that plays the audio as it is received.
 */
class PlaybackSynthesisRequest extends SynthesisRequest {

    private static final String TAG = "PlaybackSynthesisRequest";
    private static final boolean DBG = false;

    private static final int MIN_AUDIO_BUFFER_SIZE = 8192;

    /**
     * Audio stream type. Must be one of the STREAM_ contants defined in
     * {@link android.media.AudioManager}.
     */
    private final int mStreamType;

    /**
     * Volume, in the range [0.0f, 1.0f]. The default value is
     * {@link TextToSpeech.Engine#DEFAULT_VOLUME} (1.0f).
     */
    private final float mVolume;

    /**
     * Left/right position of the audio, in the range [-1.0f, 1.0f].
     * The default value is {@link TextToSpeech.Engine#DEFAULT_PAN} (0.0f).
     */
    private final float mPan;

    private final Object mStateLock = new Object();
    private final Handler mAudioTrackHandler;
    private volatile AudioTrack mAudioTrack = null;
    private boolean mStopped = false;
    private boolean mDone = false;
    private volatile boolean mWriteErrorOccured;

    PlaybackSynthesisRequest(String text, Bundle params,
            int streamType, float volume, float pan, Handler audioTrackHandler) {
        super(text, params);
        mStreamType = streamType;
        mVolume = volume;
        mPan = pan;
        mAudioTrackHandler = audioTrackHandler;
        mWriteErrorOccured = false;
    }

    @Override
    void stop() {
        if (DBG) Log.d(TAG, "stop()");
        synchronized (mStateLock) {
            mStopped = true;
            cleanUp();
        }
    }

    // Always guarded by mStateLock.
    private void cleanUp() {
        if (DBG) Log.d(TAG, "cleanUp()");
        if (mAudioTrack == null) {
            return;
        }

        final AudioTrack audioTrack = mAudioTrack;
        mAudioTrack = null;

        // Clean up on the audiotrack handler thread.
        //
        // NOTE: It isn't very clear whether AudioTrack is thread safe.
        // If it is we can clean up on the current (synthesis) thread.
        mAudioTrackHandler.post(new Runnable() {
            @Override
            public void run() {
                audioTrack.flush();
                audioTrack.stop();
                audioTrack.release();
            }
        });
    }

    @Override
    public int getMaxBufferSize() {
        // The AudioTrack buffer will be at least MIN_AUDIO_BUFFER_SIZE, so that should always be
        // a safe buffer size to pass in.
        return MIN_AUDIO_BUFFER_SIZE;
    }

    @Override
    boolean isDone() {
        return mDone;
    }

    // TODO: add a thread that writes to the AudioTrack?
    @Override
    public int start(int sampleRateInHz, int audioFormat, int channelCount) {
        if (DBG) {
            Log.d(TAG, "start(" + sampleRateInHz + "," + audioFormat
                    + "," + channelCount + ")");
        }

        synchronized (mStateLock) {
            if (mStopped) {
                if (DBG) Log.d(TAG, "Request has been aborted.");
                return TextToSpeech.ERROR;
            }
            if (mAudioTrack != null) {
                Log.e(TAG, "start() called twice");
                cleanUp();
                return TextToSpeech.ERROR;
            }

            mAudioTrack = createStreamingAudioTrack(sampleRateInHz, audioFormat, channelCount);
            if (mAudioTrack == null) {
                return TextToSpeech.ERROR;
            }
        }

        return TextToSpeech.SUCCESS;
    }

    private void setupVolume(AudioTrack audioTrack, float volume, float pan) {
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

    private float clip(float value, float min, float max) {
        return value > max ? max : (value < min ? min : value);
    }

    @Override
    public int audioAvailable(byte[] buffer, int offset, int length) {
        if (DBG) {
            Log.d(TAG, "audioAvailable(byte[" + buffer.length + "],"
                    + offset + "," + length + ")");
        }
        if (length > getMaxBufferSize() || length <= 0) {
            throw new IllegalArgumentException("buffer is too large or of zero length (" +
                    + length + " bytes)");
        }
        synchronized (mStateLock) {
            if (mWriteErrorOccured) {
                if (DBG) Log.d(TAG, "Error writing to audio track, count < 0");
                return TextToSpeech.ERROR;
            }
            if (mStopped) {
                if (DBG) Log.d(TAG, "Request has been aborted.");
                return TextToSpeech.ERROR;
            }
            if (mAudioTrack == null) {
                Log.e(TAG, "audioAvailable(): Not started");
                return TextToSpeech.ERROR;
            }
            final AudioTrack audioTrack = mAudioTrack;
            // Sigh, another copy.
            final byte[] bufferCopy = new byte[length];
            System.arraycopy(buffer, offset, bufferCopy, 0, length);

            mAudioTrackHandler.post(new Runnable() {
                @Override
                public void run() {
                    int playState = audioTrack.getPlayState();
                    if (playState == AudioTrack.PLAYSTATE_STOPPED) {
                        if (DBG) Log.d(TAG, "AudioTrack stopped, restarting");
                        audioTrack.play();
                    }
                    // TODO: loop until all data is written?
                    if (DBG) Log.d(TAG, "AudioTrack.write()");
                    int count = audioTrack.write(bufferCopy, 0, bufferCopy.length);
                    // The semantics of this change very slightly. Earlier, we would
                    // report an error immediately, Now we will return an error on
                    // the next API call, usually done( ) or another audioAvailable( )
                    // call.
                    if (count < 0) {
                        mWriteErrorOccured = true;
                    }
                }
            });

            return TextToSpeech.SUCCESS;
        }
    }

    @Override
    public int done() {
        if (DBG) Log.d(TAG, "done()");
        synchronized (mStateLock) {
            if (mWriteErrorOccured) {
                if (DBG) Log.d(TAG, "Error writing to audio track, count < 0");
                return TextToSpeech.ERROR;
            }
            if (mStopped) {
                if (DBG) Log.d(TAG, "Request has been aborted.");
                return TextToSpeech.ERROR;
            }
            if (mAudioTrack == null) {
                Log.e(TAG, "done(): Not started");
                return TextToSpeech.ERROR;
            }
            mDone = true;
            cleanUp();
        }
        return TextToSpeech.SUCCESS;
    }

    @Override
    public void error() {
        if (DBG) Log.d(TAG, "error()");
        synchronized (mStateLock) {
            cleanUp();
        }
    }

    @Override
    public int completeAudioAvailable(int sampleRateInHz, int audioFormat, int channelCount,
            byte[] buffer, int offset, int length) {
        if (DBG) {
            Log.d(TAG, "completeAudioAvailable(" + sampleRateInHz + "," + audioFormat
                    + "," + channelCount + "byte[" + buffer.length + "],"
                    + offset + "," + length + ")");
        }

        synchronized (mStateLock) {
            if (mStopped) {
                if (DBG) Log.d(TAG, "Request has been aborted.");
                return TextToSpeech.ERROR;
            }
            if (mAudioTrack != null) {
                Log.e(TAG, "start() called before completeAudioAvailable()");
                cleanUp();
                return TextToSpeech.ERROR;
            }

            int channelConfig = getChannelConfig(channelCount);
            if (channelConfig < 0) {
                Log.e(TAG, "Unsupported number of channels :" + channelCount);
                cleanUp();
                return TextToSpeech.ERROR;
            }
            int bytesPerFrame = getBytesPerFrame(audioFormat);
            if (bytesPerFrame < 0) {
                Log.e(TAG, "Unsupported audio format :" + audioFormat);
                cleanUp();
                return TextToSpeech.ERROR;
            }

            mAudioTrack = new AudioTrack(mStreamType, sampleRateInHz, channelConfig,
                    audioFormat, buffer.length, AudioTrack.MODE_STATIC);
            if (mAudioTrack == null) {
                return TextToSpeech.ERROR;
            }

            try {
                mAudioTrack.write(buffer, offset, length);
                setupVolume(mAudioTrack, mVolume, mPan);
                mAudioTrack.play();
                blockUntilDone(mAudioTrack, bytesPerFrame, length);
                mDone = true;
                if (DBG) Log.d(TAG, "Wrote data to audio track succesfully : " + length);
            } catch (IllegalStateException ex) {
                Log.e(TAG, "Playback error", ex);
                return TextToSpeech.ERROR;
            } finally {
                cleanUp();
            }
        }

        return TextToSpeech.SUCCESS;
    }

    private void blockUntilDone(AudioTrack audioTrack, int bytesPerFrame, int length) {
        int lengthInFrames = length / bytesPerFrame;
        int currentPosition = 0;
        while ((currentPosition = audioTrack.getPlaybackHeadPosition()) < lengthInFrames) {
            long estimatedTimeMs = ((lengthInFrames - currentPosition) * 1000) /
                    audioTrack.getSampleRate();
            if (DBG) Log.d(TAG, "About to sleep for : " + estimatedTimeMs + " ms," +
                    " Playback position : " + currentPosition);
            try {
                Thread.sleep(estimatedTimeMs);
            } catch (InterruptedException ie) {
                break;
            }
        }
    }

    private int getBytesPerFrame(int audioFormat) {
        if (audioFormat == AudioFormat.ENCODING_PCM_8BIT) {
            return 1;
        } else if (audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
            return 2;
        }

        return -1;
    }

    private int getChannelConfig(int channelCount) {
        if (channelCount == 1) {
            return AudioFormat.CHANNEL_OUT_MONO;
        } else if (channelCount == 2){
            return AudioFormat.CHANNEL_OUT_STEREO;
        }

        return -1;
    }

    private AudioTrack createStreamingAudioTrack(int sampleRateInHz, int audioFormat,
            int channelCount) {
        int channelConfig = getChannelConfig(channelCount);

        if (channelConfig < 0) {
            Log.e(TAG, "Unsupported number of channels : " + channelCount);
            return null;
        }

        int minBufferSizeInBytes
                = AudioTrack.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        int bufferSizeInBytes = Math.max(MIN_AUDIO_BUFFER_SIZE, minBufferSizeInBytes);
        AudioTrack audioTrack = new AudioTrack(mStreamType, sampleRateInHz, channelConfig,
                audioFormat, bufferSizeInBytes, AudioTrack.MODE_STREAM);
        if (audioTrack == null) {
            return null;
        }

        if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            audioTrack.release();
            return null;
        }
        setupVolume(audioTrack, mVolume, mPan);
        return audioTrack;
    }
}
