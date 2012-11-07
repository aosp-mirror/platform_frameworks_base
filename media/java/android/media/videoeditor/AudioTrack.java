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

import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;

import android.media.videoeditor.MediaArtistNativeHelper.Properties;

/**
 * This class allows to handle an audio track. This audio file is mixed with the
 * audio samples of the media items.
 * {@hide}
 */
public class AudioTrack {

    /**
     *  Instance variables
     *  Private object for calling native methods via MediaArtistNativeHelper
     */
    private final MediaArtistNativeHelper mMANativeHelper;
    private final String mUniqueId;
    private final String mFilename;
    private long mStartTimeMs;
    private long mTimelineDurationMs;
    private int mVolumePercent;
    private long mBeginBoundaryTimeMs;
    private long mEndBoundaryTimeMs;
    private boolean mLoop;
    private boolean mMuted;
    private final long mDurationMs;
    private final int mAudioChannels;
    private final int mAudioType;
    private final int mAudioBitrate;
    private final int mAudioSamplingFrequency;
    /**
     *  Ducking variables
     */
    private int mDuckingThreshold;
    private int mDuckedTrackVolume;
    private boolean mIsDuckingEnabled;

    /**
     *  The audio waveform filename
     */
    private String mAudioWaveformFilename;

    /**
     *  The audio waveform data
     */
    private SoftReference<WaveformData> mWaveformData;

    /**
     * An object of this type cannot be instantiated by using the default
     * constructor
     */
    @SuppressWarnings("unused")
    private AudioTrack() throws IOException {
        this(null, null, null);
    }

    /**
     * Constructor
     *
     * @param editor The video editor reference
     * @param audioTrackId The audio track id
     * @param filename The absolute file name
     *
     * @throws IOException if file is not found
     * @throws IllegalArgumentException if file format is not supported or if
     *         the codec is not supported or if editor is not of type
     *         VideoEditorImpl.
     */
    public AudioTrack(VideoEditor editor, String audioTrackId, String filename) throws IOException {
        this(editor, audioTrackId, filename, 0, 0, MediaItem.END_OF_FILE, false, 100, false, false,
                0, 0, null);
    }

    /**
     * Constructor
     *
     * @param editor The video editor reference
     * @param audioTrackId The audio track id
     * @param filename The audio filename. In case file contains Audio and Video,
     *         only the Audio stream will be used as Audio Track.
     * @param startTimeMs the start time in milliseconds (relative to the
     *         timeline)
     * @param beginMs start time in the audio track in milliseconds (relative to
     *         the beginning of the audio track)
     * @param endMs end time in the audio track in milliseconds (relative to the
     *         beginning of the audio track)
     * @param loop true to loop the audio track
     * @param volume The volume in percentage
     * @param muted true if the audio track is muted
     * @param threshold Ducking will be activated when the relative energy in
     *         the media items audio signal goes above this value. The valid
     *         range of values is 0 to 90.
     * @param duckedTrackVolume The relative volume of the audio track when
     *         ducking is active. The valid range of values is 0 to 100.
     * @param audioWaveformFilename The name of the waveform file
     *
     * @throws IOException if file is not found
     * @throws IllegalArgumentException if file format is not supported or if
     *             the codec is not supported or if editor is not of type
     *             VideoEditorImpl.
     */
    AudioTrack(VideoEditor editor, String audioTrackId, String filename,
               long startTimeMs,long beginMs, long endMs, boolean loop,
               int volume, boolean muted,boolean duckingEnabled,
               int duckThreshold, int duckedTrackVolume,
            String audioWaveformFilename) throws IOException {
        Properties properties = null;

        File file = new File(filename);
        if (!file.exists()) {
            throw new IOException(filename + " not found ! ");
        }

        /*Compare file_size with 2GB*/
        if (VideoEditor.MAX_SUPPORTED_FILE_SIZE <= file.length()) {
            throw new IllegalArgumentException("File size is more than 2GB");
        }

        if (editor instanceof VideoEditorImpl) {
            mMANativeHelper = ((VideoEditorImpl)editor).getNativeContext();
        } else {
            throw new IllegalArgumentException("editor is not of type VideoEditorImpl");
        }
        try {
          properties = mMANativeHelper.getMediaProperties(filename);
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage() + " : " + filename);
        }
        int fileType = mMANativeHelper.getFileType(properties.fileType);
        switch (fileType) {
            case MediaProperties.FILE_3GP:
            case MediaProperties.FILE_MP4:
            case MediaProperties.FILE_MP3:
            case MediaProperties.FILE_AMR:
                break;

            default: {
                throw new IllegalArgumentException("Unsupported input file type: " + fileType);
            }
        }
        switch (mMANativeHelper.getAudioCodecType(properties.audioFormat)) {
            case MediaProperties.ACODEC_AMRNB:
            case MediaProperties.ACODEC_AMRWB:
            case MediaProperties.ACODEC_AAC_LC:
            case MediaProperties.ACODEC_MP3:
                break;
            default:
                throw new IllegalArgumentException("Unsupported Audio Codec Format in Input File");
        }

        if (endMs == MediaItem.END_OF_FILE) {
            endMs = properties.audioDuration;
        }

        mUniqueId = audioTrackId;
        mFilename = filename;
        mStartTimeMs = startTimeMs;
        mDurationMs = properties.audioDuration;
        mAudioChannels = properties.audioChannels;
        mAudioBitrate = properties.audioBitrate;
        mAudioSamplingFrequency = properties.audioSamplingFrequency;
        mAudioType = properties.audioFormat;
        mTimelineDurationMs = endMs - beginMs;
        mVolumePercent = volume;

        mBeginBoundaryTimeMs = beginMs;
        mEndBoundaryTimeMs = endMs;

        mLoop = loop;
        mMuted = muted;
        mIsDuckingEnabled = duckingEnabled;
        mDuckingThreshold = duckThreshold;
        mDuckedTrackVolume = duckedTrackVolume;

        mAudioWaveformFilename = audioWaveformFilename;
        if (audioWaveformFilename != null) {
            mWaveformData =
                new SoftReference<WaveformData>(new WaveformData(audioWaveformFilename));
        } else {
            mWaveformData = null;
        }
    }

    /**
     * Get the id of the audio track
     *
     * @return The id of the audio track
     */
    public String getId() {
        return mUniqueId;
    }

    /**
     * Get the filename for this audio track source.
     *
     * @return The filename as an absolute file name
     */
    public String getFilename() {
        return mFilename;
    }

    /**
     * Get the number of audio channels in the source of this audio track
     *
     * @return The number of audio channels in the source of this audio track
     */
    public int getAudioChannels() {
        return mAudioChannels;
    }

    /**
     * Get the audio codec of the source of this audio track
     *
     * @return The audio codec of the source of this audio track
     * {@link android.media.videoeditor.MediaProperties}
     */
    public int getAudioType() {
        return mAudioType;
    }

    /**
     * Get the audio sample frequency of the audio track
     *
     * @return The audio sample frequency of the audio track
     */
    public int getAudioSamplingFrequency() {
        return mAudioSamplingFrequency;
    }

    /**
     * Get the audio bitrate of the audio track
     *
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
     *         0, then volume becomes mute. It it is set to 100, then volume
     *         is same as original volume. It it is set to 200, then volume
     *         is doubled (provided that volume amplification is supported)
     *
     * @throws UnsupportedOperationException if volume amplification is
     *         requested and is not supported.
     */
    public void setVolume(int volumePercent) {
        if (volumePercent > MediaProperties.AUDIO_MAX_VOLUME_PERCENT) {
            throw new IllegalArgumentException("Volume set exceeds maximum allowed value");
        }

        if (volumePercent < 0) {
            throw new IllegalArgumentException("Invalid Volume ");
        }

        /**
         *  Force update of preview settings
         */
        mMANativeHelper.setGeneratePreview(true);

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
     * Mute/Unmute the audio track
     *
     * @param muted true to mute the audio track. SetMute(true) will make
     *         the volume of this Audio Track to 0.
     */
    public void setMute(boolean muted) {
        /**
         *  Force update of preview settings
         */
        mMANativeHelper.setGeneratePreview(true);
        mMuted = muted;
    }

    /**
     * Check if the audio track is muted
     *
     * @return true if the audio track is muted
     */
    public boolean isMuted() {
        return mMuted;
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
     * Get the audio track duration
     *
     * @return The duration in milliseconds. This value represents actual audio
     *         track duration. This value is not effected by 'enableLoop' or
     *         'setExtractBoundaries'.
     */
    public long getDuration() {
        return mDurationMs;
    }

    /**
     * Get the audio track timeline duration
     *
     * @return The timeline duration as defined by the begin and end boundaries
     */
    public long getTimelineDuration() {
        return mTimelineDurationMs;
    }

    /**
     * Sets the start and end marks for trimming an audio track
     *
     * @param beginMs start time in the audio track in milliseconds (relative to
     *         the beginning of the audio track)
     * @param endMs end time in the audio track in milliseconds (relative to the
     *         beginning of the audio track)
     */
    public void setExtractBoundaries(long beginMs, long endMs) {
        if (beginMs > mDurationMs) {
            throw new IllegalArgumentException("Invalid start time");
        }
        if (endMs > mDurationMs) {
            throw new IllegalArgumentException("Invalid end time");
        }
        if (beginMs < 0) {
            throw new IllegalArgumentException("Invalid start time; is < 0");
        }
        if (endMs < 0) {
            throw new IllegalArgumentException("Invalid end time; is < 0");
        }

        /**
         *  Force update of preview settings
         */
        mMANativeHelper.setGeneratePreview(true);

        mBeginBoundaryTimeMs = beginMs;
        mEndBoundaryTimeMs = endMs;

        mTimelineDurationMs = mEndBoundaryTimeMs - mBeginBoundaryTimeMs;
    }

    /**
     * Get the boundary begin time
     *
     * @return The boundary begin time
     */
    public long getBoundaryBeginTime() {
        return mBeginBoundaryTimeMs;
    }

    /**
     * Get the boundary end time
     *
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
        if (!mLoop) {
            /**
             *  Force update of preview settings
             */
            mMANativeHelper.setGeneratePreview(true);
            mLoop = true;
        }
    }

    /**
     * Disable the loop mode
     */
    public void disableLoop() {
        if (mLoop) {
            /**
             *  Force update of preview settings
             */
            mMANativeHelper.setGeneratePreview(true);
            mLoop = false;
        }
    }

    /**
     * Check if looping is enabled
     *
     * @return true if looping is enabled
     */
    public boolean isLooping() {
        return mLoop;
    }

    /**
     * Disable the audio duck effect
     */
    public void disableDucking() {
        if (mIsDuckingEnabled) {
            /**
             *  Force update of preview settings
             */
            mMANativeHelper.setGeneratePreview(true);
            mIsDuckingEnabled = false;
        }
    }

    /**
     * Enable ducking by specifying the required parameters
     *
     * @param threshold Ducking will be activated when the energy in
     *         the media items audio signal goes above this value. The valid
     *         range of values is 0db to 90dB. 0dB is equivalent to disabling
     *         ducking.
     * @param duckedTrackVolume The relative volume of the audio track when ducking
     *         is active. The valid range of values is 0 to 100.
     */
    public void enableDucking(int threshold, int duckedTrackVolume) {
        if (threshold < 0 || threshold > 90) {
            throw new IllegalArgumentException("Invalid threshold value: " + threshold);
        }

        if (duckedTrackVolume < 0 || duckedTrackVolume > 100) {
            throw new IllegalArgumentException("Invalid duckedTrackVolume value: "
                    + duckedTrackVolume);
        }

        /**
         *  Force update of preview settings
         */
        mMANativeHelper.setGeneratePreview(true);

        mDuckingThreshold = threshold;
        mDuckedTrackVolume = duckedTrackVolume;
        mIsDuckingEnabled = true;
    }

    /**
     * Check if ducking is enabled
     *
     * @return true if ducking is enabled
     */
    public boolean isDuckingEnabled() {
        return mIsDuckingEnabled;
    }

    /**
     * Get the ducking threshold.
     *
     * @return The ducking threshold
     */
    public int getDuckingThreshhold() {
        return mDuckingThreshold;
    }

    /**
     * Get the ducked track volume.
     *
     * @return The ducked track volume
     */
    public int getDuckedTrackVolume() {
        return mDuckedTrackVolume;
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
     *         audio track
     * @throws IllegalStateException if the codec type is unsupported
     */
    public void extractAudioWaveform(ExtractAudioWaveformProgressListener listener)
    throws IOException {
        if (mAudioWaveformFilename == null) {
            /**
             *  AudioWaveformFilename is generated
             */
            final String projectPath = mMANativeHelper.getProjectPath();
            final String audioWaveFilename = String.format(projectPath + "/audioWaveformFile-"
                    + getId() + ".dat");

            /**
             * Logic to get frame duration = (no. of frames per sample * 1000)/
             * sampling frequency
             */
            final int frameDuration;
            final int sampleCount;
            final int codecType = mMANativeHelper.getAudioCodecType(mAudioType);
            switch (codecType) {
                case MediaProperties.ACODEC_AMRNB: {
                    frameDuration = (MediaProperties.SAMPLES_PER_FRAME_AMRNB * 1000)
                    / MediaProperties.DEFAULT_SAMPLING_FREQUENCY;
                    sampleCount = MediaProperties.SAMPLES_PER_FRAME_AMRNB;
                    break;
                }

                case MediaProperties.ACODEC_AMRWB: {
                    frameDuration = (MediaProperties.SAMPLES_PER_FRAME_AMRWB * 1000)
                    / MediaProperties.DEFAULT_SAMPLING_FREQUENCY;
                    sampleCount = MediaProperties.SAMPLES_PER_FRAME_AMRWB;
                    break;
                }

                case MediaProperties.ACODEC_AAC_LC: {
                    frameDuration = (MediaProperties.SAMPLES_PER_FRAME_AAC * 1000)
                    / MediaProperties.DEFAULT_SAMPLING_FREQUENCY;
                    sampleCount = MediaProperties.SAMPLES_PER_FRAME_AAC;
                    break;
                }

                case MediaProperties.ACODEC_MP3: {
                    frameDuration = (MediaProperties.SAMPLES_PER_FRAME_MP3 * 1000)
                    / MediaProperties.DEFAULT_SAMPLING_FREQUENCY;
                    sampleCount = MediaProperties.SAMPLES_PER_FRAME_MP3;
                    break;
                }

                default: {
                    throw new IllegalStateException("Unsupported codec type: "
                                                                   + codecType);
                }
            }

            mMANativeHelper.generateAudioGraph( mUniqueId,
                    mFilename,
                    audioWaveFilename,
                    frameDuration,
                    MediaProperties.DEFAULT_CHANNEL_COUNT,
                    sampleCount,
                    listener,
                    false);
            /**
             *  Record the generated file name
             */
            mAudioWaveformFilename = audioWaveFilename;
        }
        mWaveformData = new SoftReference<WaveformData>(new WaveformData(mAudioWaveformFilename));
    }

    /**
     * Get the audio waveform file name if extractAudioWaveform was successful.
     *
     * @return the name of the file, null if the file does not exist
     */
    String getAudioWaveformFilename() {
        return mAudioWaveformFilename;
    }

    /**
     * Delete the waveform file
     */
    void invalidate() {
        if (mAudioWaveformFilename != null) {
            new File(mAudioWaveformFilename).delete();
            mAudioWaveformFilename = null;
            mWaveformData = null;
        }
    }

    /**
     * Get the audio waveform data.
     *
     * @return The waveform data
     *
     * @throws IOException if the waveform file cannot be found
     */
    public WaveformData getWaveformData() throws IOException {
        if (mWaveformData == null) {
            return null;
        }

        WaveformData waveformData = mWaveformData.get();
        if (waveformData != null) {
            return waveformData;
        } else if (mAudioWaveformFilename != null) {
            try {
                waveformData = new WaveformData(mAudioWaveformFilename);
            } catch (IOException e) {
                throw e;
            }
            mWaveformData = new SoftReference<WaveformData>(waveformData);
            return waveformData;
        } else {
            return null;
        }
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
