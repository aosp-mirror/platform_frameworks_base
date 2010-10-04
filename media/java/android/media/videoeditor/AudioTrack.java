/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.media.videoeditor;

import java.io.IOException;

import android.util.Log;

/**
 * This class allows to handle an audio track. This audio file is mixed with the
 * audio samples of the MediaItems.
 * {@hide}
 */
public class AudioTrack {
    // Logging
    private static final String TAG = "AudioTrack";

    // Instance variables
    private final String mUniqueId;
    private final String mFilename;
    private final long mDurationMs;
    private long mStartTimeMs;
    private long mTimelineDurationMs;
    private int mVolumePercent;
    private long mBeginBoundaryTimeMs;
    private long mEndBoundaryTimeMs;
    private boolean mLoop;

    private final int mAudioChannels;
    private final int mAudioType;
    private final int mAudioBitrate;
    private final int mAudioSamplingFrequency;

    // Ducking variables
    private int mDuckingThreshold;
    private int mDuckingLowVolume;
    private boolean mIsDuckingEnabled;

    // The audio waveform filename
    private String mAudioWaveformFilename;
    private PlaybackThread mPlaybackThread;

    /**
     * This listener interface is used by the AudioTrack to emit playback
     * progress notifications.
     */
    public interface PlaybackProgressListener {
        /**
         * This method notifies the listener of the current time position while
         * playing an audio track
         *
         * @param audioTrack The audio track
         * @param timeMs The current playback position (expressed in milliseconds
         *            since the beginning of the audio track).
         * @param end true if the end of the audio track was reached
         */
        public void onProgress(AudioTrack audioTrack, long timeMs, boolean end);
    }

    /**
     * The playback thread
     */
    private class PlaybackThread extends Thread {
        // Instance variables
        private final PlaybackProgressListener mListener;
        private final long mFromMs, mToMs;
        private boolean mRun;
        private final boolean mLoop;
        private long mPositionMs;

        /**
         * Constructor
         *
         * @param fromMs The time (relative to the beginning of the audio track)
         *            at which the playback will start
         * @param toMs The time (relative to the beginning of the audio track) at
         *            which the playback will stop. Use -1 to play to the end of
         *            the audio track
         * @param loop true if the playback should be looped once it reaches the
         *            end
         * @param listener The listener which will be notified of the playback
         *            progress
         */
        public PlaybackThread(long fromMs, long toMs, boolean loop,
                PlaybackProgressListener listener) {
            mPositionMs = mFromMs = fromMs;
            if (toMs < 0) {
                mToMs = mDurationMs;
            } else {
                mToMs = toMs;
            }
            mLoop = loop;
            mListener = listener;
            mRun = true;
        }

        /*
         * {@inheritDoc}
         */
        @Override
        public void run() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "===> PlaybackThread.run enter");
            }

            while (mRun) {
                try {
                    sleep(100);
                } catch (InterruptedException ex) {
                    break;
                }

                mPositionMs += 100;

                if (mPositionMs >= mToMs) {
                    if (!mLoop) {
                        if (mListener != null) {
                            mListener.onProgress(AudioTrack.this, mPositionMs, true);
                        }
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "PlaybackThread.run playback complete");
                        }
                        break;
                    } else {
                        // Fire a notification for the end of the clip
                        if (mListener != null) {
                            mListener.onProgress(AudioTrack.this, mToMs, false);
                        }

                        // Rewind
                        mPositionMs = mFromMs;
                        if (mListener != null) {
                            mListener.onProgress(AudioTrack.this, mPositionMs, false);
                        }
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(TAG, "PlaybackThread.run playback complete");
                        }
                    }
                } else {
                    if (mListener != null) {
                        mListener.onProgress(AudioTrack.this, mPositionMs, false);
                    }
                }
            }
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "===> PlaybackThread.run exit");
            }
        }

        /**
         * Stop the playback
         *
         * @return The stop position
         */
        public long stopPlayback() {
            mRun = false;
            try {
                join();
            } catch (InterruptedException ex) {
            }
            return mPositionMs;
        }
    };

    /**
     * An object of this type cannot be instantiated by using the default
     * constructor
     */
    @SuppressWarnings("unused")
    private AudioTrack() throws IOException {
        this(null, null);
    }

    /**
     * Constructor
     * @param audioTrackId The AudioTrack id
     * @param filename The absolute file name
     *
     * @throws IOException if file is not found
     * @throws IllegalArgumentException if file format is not supported or if
     *             the codec is not supported
     */
    public AudioTrack(String audioTrackId, String filename) throws IOException {
        mUniqueId = audioTrackId;
        mFilename = filename;
        mStartTimeMs = 0;
        // TODO: This value represents to the duration of the audio file
        mDurationMs = 300000;
        // TODO: This value needs to be read from the audio track of the source
        // file
        mAudioChannels = 2;
        mAudioType = MediaProperties.ACODEC_AAC_LC;
        mAudioBitrate = 128000;
        mAudioSamplingFrequency = 44100;

        mTimelineDurationMs = mDurationMs;
        mVolumePercent = 100;

        // Play the entire audio track
        mBeginBoundaryTimeMs = 0;
        mEndBoundaryTimeMs = mDurationMs;

        // By default loop is disabled
        mLoop = false;

        // Ducking is enabled by default
        mDuckingThreshold = 0;
        mDuckingLowVolume = 0;
        mIsDuckingEnabled = true;

        // The audio waveform file is generated later
        mAudioWaveformFilename = null;
    }

    /**
     * @return The id of the audio track
     */
    public String getId() {
        return mUniqueId;
    }

    /**
     * Get the filename source for this audio track.
     *
     * @return The filename as an absolute file name
     */
    public String getFilename() {
        return mFilename;
    }

    /**
     * @return The number of audio channels in the source of this audio track
     */
    public int getAudioChannels() {
        return mAudioChannels;
    }

    /**
     * @return The audio codec of the source of this audio track
     */
    public int getAudioType() {
        return mAudioType;
    }

    /**
     * @return The audio sample frequency of the audio track
     */
    public int getAudioSamplingFrequency() {
        return mAudioSamplingFrequency;
    }

    /**
     * @return The audio bitrate of the audio track
     */
    public int getAudioBitrate() {
        return mAudioBitrate;
    }

    /**
     * Set the volume of this audio track as percentage of the volume in the
     * original audio source file.
     *
     * @param volumePercent Percentage of the volume to apply. If it is set to
     *            0, then volume becomes mute. It it is set to 100, then volume
     *            is same as original volume. It it is set to 200, then volume
     *            is doubled (provided that volume amplification is supported)
     *
     * @throws UnsupportedOperationException if volume amplification is
     *             requested and is not supported.
     */
    public void setVolume(int volumePercent) {
        mVolumePercent = volumePercent;
    }

    /**
     * Get the volume of the audio track as percentage of the volume in the
     * original audio source file.
     *
     * @return The volume in percentage
     */
    public int getVolume() {
        return mVolumePercent;
    }

    /**
     * Set the start time of this audio track relative to the storyboard
     * timeline. Default value is 0.
     *
     * @param startTimeMs the start time in milliseconds
     */
    public void setStartTime(long startTimeMs) {
        mStartTimeMs = startTimeMs;
    }

    /**
     * Get the start time of this audio track relative to the storyboard
     * timeline.
     *
     * @return The start time in milliseconds
     */
    public long getStartTime() {
        return mStartTimeMs;
    }

    /**
     * @return The duration in milliseconds. This value represents the audio
     *         track duration (not looped)
     */
    public long getDuration() {
        return mDurationMs;
    }

    /**
     * @return The timeline duration. If looping is enabled this value
     *         represents the duration of the looped audio track, otherwise it
     *         is the duration of the audio track (mDurationMs).
     */
    public long getTimelineDuration() {
        return mTimelineDurationMs;
    }

    /**
     * Sets the start and end marks for trimming an audio track
     *
     * @param beginMs start time in the audio track in milliseconds (relative to
     *            the beginning of the audio track)
     * @param endMs end time in the audio track in milliseconds (relative to the
     *            beginning of the audio track)
     */
    public void setExtractBoundaries(long beginMs, long endMs) {
        if (beginMs > mDurationMs) {
            throw new IllegalArgumentException("Invalid start time");
        }
        if (endMs > mDurationMs) {
            throw new IllegalArgumentException("Invalid end time");
        }

        mBeginBoundaryTimeMs = beginMs;
        mEndBoundaryTimeMs = endMs;
        if (mLoop) {
            // TODO: Compute mDurationMs (from the beginning of the loop until
            // the end of all the loops.
            mTimelineDurationMs = mEndBoundaryTimeMs - mBeginBoundaryTimeMs;
        } else {
            mTimelineDurationMs = mEndBoundaryTimeMs - mBeginBoundaryTimeMs;
        }
    }

    /**
     * @return The boundary begin time
     */
    public long getBoundaryBeginTime() {
        return mBeginBoundaryTimeMs;
    }

    /**
     * @return The boundary end time
     */
    public long getBoundaryEndTime() {
        return mEndBoundaryTimeMs;
    }

    /**
     * Enable the loop mode for this audio track. Note that only one of the
     * audio tracks in the timeline can have the loop mode enabled. When looping
     * is enabled the samples between mBeginBoundaryTimeMs and
     * mEndBoundaryTimeMs are looped.
     */
    public void enableLoop() {
        mLoop = true;
    }

    /**
     * Disable the loop mode
     */
    public void disableLoop() {
        mLoop = false;
    }

    /**
     * @return true if looping is enabled
     */
    public boolean isLooping() {
        return mLoop;
    }

    /**
     * Disable the audio duck effect
     */
    public void disableDucking() {
        mIsDuckingEnabled = false;
    }

    /**
     * TODO DEFINE
     *
     * @param threshold
     * @param lowVolume
     * @param volume
     */
    public void enableDucking(int threshold, int lowVolume, int volume) {
        mDuckingThreshold = threshold;
        mDuckingLowVolume = lowVolume;
        mIsDuckingEnabled = true;
    }

    /**
     * @return true if ducking is enabled
     */
    public boolean isDuckingEnabled() {
        return mIsDuckingEnabled;
    }

    /**
     * @return The ducking threshold
     */
    public int getDuckingThreshhold() {
        return mDuckingThreshold;
    }

    /**
     * @return The ducking low level
     */
    public int getDuckingLowVolume() {
        return mDuckingLowVolume;
    }

    /**
     * Start the playback of this audio track. This method does not block (does
     * not wait for the playback to complete).
     *
     * @param fromMs The time (relative to the beginning of the audio track) at
     *            which the playback will start
     * @param toMs The time (relative to the beginning of the audio track) at
     *            which the playback will stop. Use -1 to play to the end of the
     *            audio track
     * @param loop true if the playback should be looped once it reaches the end
     * @param listener The listener which will be notified of the playback
     *            progress
     * @throws IllegalArgumentException if fromMs or toMs is beyond the playback
     *             duration
     * @throws IllegalStateException if a playback, preview or an export is
     *             already in progress
     */
    public void startPlayback(long fromMs, long toMs, boolean loop,
            PlaybackProgressListener listener) {
        if (fromMs >= mDurationMs) {
            return;
        }
        mPlaybackThread = new PlaybackThread(fromMs, toMs, loop, listener);
        mPlaybackThread.start();
    }

    /**
     * Stop the audio track playback. This method blocks until the ongoing
     * playback is stopped.
     *
     * @return The accurate current time when stop is effective expressed in
     *         milliseconds
     */
    public long stopPlayback() {
        final long stopTimeMs;
        if (mPlaybackThread != null) {
            stopTimeMs = mPlaybackThread.stopPlayback();
            mPlaybackThread = null;
        } else {
            stopTimeMs = 0;
        }
        return stopTimeMs;
    }

    /**
     * This API allows to generate a file containing the sample volume levels of
     * this audio track object. This function may take significant time and is
     * blocking. The filename can be retrieved using getAudioWaveformFilename().
     *
     * @param listener The progress listener
     *
     * @throws IOException if the output file cannot be created
     * @throws IllegalArgumentException if the audio file does not have a valid
     *             audio track
     */
    public void extractAudioWaveform(ExtractAudioWaveformProgressListener listener)
            throws IOException {
        // TODO: Set mAudioWaveformFilename at the end once the extract is
        // complete
    }

    /**
     * Get the audio waveform file name if extractAudioWaveform was successful.
     * The file format is as following:
     * <ul>
     * <li>first 4 bytes provide the number of samples for each value, as
     * big-endian signed</li>
     * <li>4 following bytes is the total number of values in the file, as
     * big-endian signed</li>
     * <li>then, all values follow as bytes</li>
     * </ul>
     *
     * @return the name of the file, null if the file does not exist
     */
    public String getAudioWaveformFilename() {
        return mAudioWaveformFilename;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object object) {
        if (!(object instanceof AudioTrack)) {
            return false;
        }
        return mUniqueId.equals(((AudioTrack)object).mUniqueId);
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return mUniqueId.hashCode();
    }
}
