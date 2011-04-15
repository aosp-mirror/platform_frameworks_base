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
    private AudioTrack mAudioTrack = null;
    private boolean mStopped = false;

    PlaybackSynthesisRequest(String text, int streamType, float volume, float pan) {
        super(text);
        mStreamType = streamType;
        mVolume = volume;
        mPan = pan;
    }

    @Override
    void stop() {
        if (DBG) Log.d(TAG, "stop()");
        synchronized (mStateLock) {
            mStopped = true;
            cleanUp();
        }
    }

    private void cleanUp() {
        if (DBG) Log.d(TAG, "cleanUp()");
        if (mAudioTrack != null) {
            mAudioTrack.flush();
            mAudioTrack.stop();
            // TODO: do we need to wait for playback to finish before releasing?
            mAudioTrack.release();
            mAudioTrack = null;
        }
    }

    @Override
    public int getMaxBufferSize() {
        // The AudioTrack buffer will be at least MIN_AUDIO_BUFFER_SIZE, so that should always be
        // a safe buffer size to pass in.
        return MIN_AUDIO_BUFFER_SIZE;
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

            mAudioTrack = createAudioTrack(sampleRateInHz, audioFormat, channelCount,
                    AudioTrack.MODE_STREAM);
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
        if (length > getMaxBufferSize()) {
            throw new IllegalArgumentException("buffer is too large (" + length + " bytes)");
        }
        synchronized (mStateLock) {
            if (mStopped) {
                if (DBG) Log.d(TAG, "Request has been aborted.");
                return TextToSpeech.ERROR;
            }
            if (mAudioTrack == null) {
                Log.e(TAG, "audioAvailable(): Not started");
                return TextToSpeech.ERROR;
            }
            int playState = mAudioTrack.getPlayState();
            if (playState == AudioTrack.PLAYSTATE_STOPPED) {
                if (DBG) Log.d(TAG, "AudioTrack stopped, restarting");
                mAudioTrack.play();
            }
            // TODO: loop until all data is written?
            if (DBG) Log.d(TAG, "AudioTrack.write()");
            int count = mAudioTrack.write(buffer, offset, length);
            if (DBG) Log.d(TAG, "AudioTrack.write() returned " + count);
            if (count < 0) {
                Log.e(TAG, "Writing to AudioTrack failed: " + count);
                cleanUp();
                return TextToSpeech.ERROR;
            } else {
                return TextToSpeech.SUCCESS;
            }
        }
    }

    @Override
    public int done() {
        if (DBG) Log.d(TAG, "done()");
        synchronized (mStateLock) {
            if (mStopped) {
                if (DBG) Log.d(TAG, "Request has been aborted.");
                return TextToSpeech.ERROR;
            }
            if (mAudioTrack == null) {
                Log.e(TAG, "done(): Not started");
                return TextToSpeech.ERROR;
            }
            cleanUp();
        }
        return TextToSpeech.SUCCESS;
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

            mAudioTrack = createAudioTrack(sampleRateInHz, audioFormat, channelCount,
                    AudioTrack.MODE_STATIC);
            if (mAudioTrack == null) {
                return TextToSpeech.ERROR;
            }

            try {
                mAudioTrack.write(buffer, offset, length);
                mAudioTrack.play();
            } catch (IllegalStateException ex) {
                Log.e(TAG, "Playback error", ex);
                return TextToSpeech.ERROR;
            } finally {
                cleanUp();
            }
        }

        return TextToSpeech.SUCCESS;
    }

    private AudioTrack createAudioTrack(int sampleRateInHz, int audioFormat, int channelCount,
            int mode) {
        int channelConfig;
        if (channelCount == 1) {
            channelConfig = AudioFormat.CHANNEL_OUT_MONO;
        } else if (channelCount == 2){
            channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
        } else {
            Log.e(TAG, "Unsupported number of channels: " + channelCount);
            return null;
        }

        int minBufferSizeInBytes
                = AudioTrack.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        int bufferSizeInBytes = Math.max(MIN_AUDIO_BUFFER_SIZE, minBufferSizeInBytes);
        AudioTrack audioTrack = new AudioTrack(mStreamType, sampleRateInHz, channelConfig,
                audioFormat, bufferSizeInBytes, mode);
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