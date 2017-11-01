// Copyright 2011 Google Inc. All Rights Reserved.

package android.speech.tts;

import android.media.AudioFormat;
import android.media.AudioTrack;
import android.speech.tts.TextToSpeechService.AudioOutputParams;
import android.util.Log;

/**
 * Exposes parts of the {@link AudioTrack} API by delegating calls to an
 * underlying {@link AudioTrack}. Additionally, provides methods like
 * {@link #waitAndRelease()} that will block until all audiotrack
 * data has been flushed to the mixer, and is estimated to have completed
 * playback.
 */
class BlockingAudioTrack {
    private static final String TAG = "TTS.BlockingAudioTrack";
    private static final boolean DBG = false;


    /**
     * The minimum increment of time to wait for an AudioTrack to finish
     * playing.
     */
    private static final long MIN_SLEEP_TIME_MS = 20;

    /**
     * The maximum increment of time to sleep while waiting for an AudioTrack
     * to finish playing.
     */
    private static final long MAX_SLEEP_TIME_MS = 2500;

    /**
     * The maximum amount of time to wait for an audio track to make progress while
     * it remains in PLAYSTATE_PLAYING. This should never happen in normal usage, but
     * could happen in exceptional circumstances like a media_server crash.
     */
    private static final long MAX_PROGRESS_WAIT_MS = MAX_SLEEP_TIME_MS;

    /**
     * Minimum size of the buffer of the underlying {@link android.media.AudioTrack}
     * we create.
     */
    private static final int MIN_AUDIO_BUFFER_SIZE = 8192;


    private final AudioOutputParams mAudioParams;
    private final int mSampleRateInHz;
    private final int mAudioFormat;
    private final int mChannelCount;


    private final int mBytesPerFrame;
    /**
     * A "short utterance" is one that uses less bytes than the audio
     * track buffer size (mAudioBufferSize). In this case, we need to call
     * {@link AudioTrack#stop()} to send pending buffers to the mixer, and slightly
     * different logic is required to wait for the track to finish.
     *
     * Not volatile, accessed only from the audio playback thread.
     */
    private boolean mIsShortUtterance;
    /**
     * Will be valid after a call to {@link #init()}.
     */
    private int mAudioBufferSize;
    private int mBytesWritten = 0;

    // Need to be seen by stop() which can be called from another thread. mAudioTrack will be
    // set to null only after waitAndRelease().
    private Object mAudioTrackLock = new Object();
    private AudioTrack mAudioTrack;
    private volatile boolean mStopped;

    private int mSessionId;

    BlockingAudioTrack(AudioOutputParams audioParams, int sampleRate,
            int audioFormat, int channelCount) {
        mAudioParams = audioParams;
        mSampleRateInHz = sampleRate;
        mAudioFormat = audioFormat;
        mChannelCount = channelCount;

        mBytesPerFrame = AudioFormat.getBytesPerSample(mAudioFormat) * mChannelCount;
        mIsShortUtterance = false;
        mAudioBufferSize = 0;
        mBytesWritten = 0;

        mAudioTrack = null;
        mStopped = false;
    }

    public boolean init() {
        AudioTrack track = createStreamingAudioTrack();
        synchronized (mAudioTrackLock) {
            mAudioTrack = track;
        }

        if (track == null) {
            return false;
        } else {
            return true;
        }
    }

    public void stop() {
        synchronized (mAudioTrackLock) {
            if (mAudioTrack != null) {
                mAudioTrack.stop();
            }
            mStopped = true;
        }
    }

    public int write(byte[] data) {
        AudioTrack track = null;
        synchronized (mAudioTrackLock) {
            track = mAudioTrack;
        }

        if (track == null || mStopped) {
            return -1;
        }
        final int bytesWritten = writeToAudioTrack(track, data);

        mBytesWritten += bytesWritten;
        return bytesWritten;
    }

    public void waitAndRelease() {
        AudioTrack track = null;
        synchronized (mAudioTrackLock) {
            track = mAudioTrack;
        }
        if (track == null) {
            if (DBG) Log.d(TAG, "Audio track null [duplicate call to waitAndRelease ?]");
            return;
        }

        // For "small" audio tracks, we have to stop() them to make them mixable,
        // else the audio subsystem will wait indefinitely for us to fill the buffer
        // before rendering the track mixable.
        //
        // If mStopped is true, the track would already have been stopped, so not
        // much point not doing that again.
        if (mBytesWritten < mAudioBufferSize && !mStopped) {
            if (DBG) {
                Log.d(TAG, "Stopping audio track to flush audio, state was : " +
                        track.getPlayState() + ",stopped= " + mStopped);
            }

            mIsShortUtterance = true;
            track.stop();
        }

        // Block until the audio track is done only if we haven't stopped yet.
        if (!mStopped) {
            if (DBG) Log.d(TAG, "Waiting for audio track to complete : " + mAudioTrack.hashCode());
            blockUntilDone(mAudioTrack);
        }

        // The last call to AudioTrack.write( ) will return only after
        // all data from the audioTrack has been sent to the mixer, so
        // it's safe to release at this point.
        if (DBG) Log.d(TAG, "Releasing audio track [" + track.hashCode() + "]");
        synchronized (mAudioTrackLock) {
            mAudioTrack = null;
        }
        track.release();
    }


    static int getChannelConfig(int channelCount) {
        if (channelCount == 1) {
            return AudioFormat.CHANNEL_OUT_MONO;
        } else if (channelCount == 2){
            return AudioFormat.CHANNEL_OUT_STEREO;
        }

        return 0;
    }

    long getAudioLengthMs(int numBytes) {
        final int unconsumedFrames = numBytes / mBytesPerFrame;
        final long estimatedTimeMs = unconsumedFrames * 1000 / mSampleRateInHz;

        return estimatedTimeMs;
    }

    private static int writeToAudioTrack(AudioTrack audioTrack, byte[] bytes) {
        if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            if (DBG) Log.d(TAG, "AudioTrack not playing, restarting : " + audioTrack.hashCode());
            audioTrack.play();
        }

        int count = 0;
        while (count < bytes.length) {
            // Note that we don't take bufferCopy.mOffset into account because
            // it is guaranteed to be 0.
            int written = audioTrack.write(bytes, count, bytes.length);
            if (written <= 0) {
                break;
            }
            count += written;
        }
        return count;
    }

    private AudioTrack createStreamingAudioTrack() {
        final int channelConfig = getChannelConfig(mChannelCount);

        int minBufferSizeInBytes
                = AudioTrack.getMinBufferSize(mSampleRateInHz, channelConfig, mAudioFormat);
        int bufferSizeInBytes = Math.max(MIN_AUDIO_BUFFER_SIZE, minBufferSizeInBytes);

        AudioFormat audioFormat = (new AudioFormat.Builder())
                .setChannelMask(channelConfig)
                .setEncoding(mAudioFormat)
                .setSampleRate(mSampleRateInHz).build();
        AudioTrack audioTrack = new AudioTrack(mAudioParams.mAudioAttributes,
                audioFormat, bufferSizeInBytes, AudioTrack.MODE_STREAM,
                mAudioParams.mSessionId);

        if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "Unable to create audio track.");
            audioTrack.release();
            return null;
        }

        mAudioBufferSize = bufferSizeInBytes;

        setupVolume(audioTrack, mAudioParams.mVolume, mAudioParams.mPan);
        return audioTrack;
    }

    private void blockUntilDone(AudioTrack audioTrack) {
        if (mBytesWritten <= 0) {
            return;
        }

        if (mIsShortUtterance) {
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
            blockUntilEstimatedCompletion();
        } else {
            blockUntilCompletion(audioTrack);
        }
    }

    private void blockUntilEstimatedCompletion() {
        final int lengthInFrames = mBytesWritten / mBytesPerFrame;
        final long estimatedTimeMs = (lengthInFrames * 1000 / mSampleRateInHz);

        if (DBG) Log.d(TAG, "About to sleep for: " + estimatedTimeMs + "ms for a short utterance");

        try {
            Thread.sleep(estimatedTimeMs);
        } catch (InterruptedException ie) {
            // Do nothing.
        }
    }

    private void blockUntilCompletion(AudioTrack audioTrack) {
        final int lengthInFrames = mBytesWritten / mBytesPerFrame;

        int previousPosition = -1;
        int currentPosition = 0;
        long blockedTimeMs = 0;

        while ((currentPosition = audioTrack.getPlaybackHeadPosition()) < lengthInFrames &&
                audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING && !mStopped) {

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

            if (DBG) {
                Log.d(TAG, "About to sleep for : " + sleepTimeMs + " ms," +
                        " Playback position : " + currentPosition + ", Length in frames : "
                        + lengthInFrames);
            }
            try {
                Thread.sleep(sleepTimeMs);
            } catch (InterruptedException ie) {
                break;
            }
        }
    }

    private static void setupVolume(AudioTrack audioTrack, float volume, float pan) {
        final float vol = clip(volume, 0.0f, 1.0f);
        final float panning = clip(pan, -1.0f, 1.0f);

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

    private static final long clip(long value, long min, long max) {
        return value < min ? min : (value < max ? value : max);
    }

    private static final float clip(float value, float min, float max) {
        return value < min ? min : (value < max ? value : max);
    }

    /**
     * @see
     *     AudioTrack#setPlaybackPositionUpdateListener(AudioTrack.OnPlaybackPositionUpdateListener).
     */
    public void setPlaybackPositionUpdateListener(
            AudioTrack.OnPlaybackPositionUpdateListener listener) {
        synchronized (mAudioTrackLock) {
            if (mAudioTrack != null) {
                mAudioTrack.setPlaybackPositionUpdateListener(listener);
            }
        }
    }

    /** @see AudioTrack#setNotificationMarkerPosition(int). */
    public void setNotificationMarkerPosition(int frames) {
        synchronized (mAudioTrackLock) {
            if (mAudioTrack != null) {
                mAudioTrack.setNotificationMarkerPosition(frames);
            }
        }
    }
}
