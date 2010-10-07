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

import android.graphics.Bitmap;
import android.view.SurfaceHolder;

/**
 * This class represents a video clip item on the storyboard
 * {@hide}
 */
public class MediaVideoItem extends MediaItem {
    // Instance variables
    private final int mWidth;
    private final int mHeight;
    private final int mAspectRatio;
    private final int mFileType;
    private final int mVideoType;
    private final int mVideoProfile;
    private final int mVideoBitrate;
    private final long mDurationMs;
    private final int mAudioBitrate;
    private final int mFps;
    private final int mAudioType;
    private final int mAudioChannels;
    private final int mAudioSamplingFrequency;

    private long mBeginBoundaryTimeMs;
    private long mEndBoundaryTimeMs;
    private int mVolumePercentage;
    private boolean mMuted;
    private String mAudioWaveformFilename;

    /**
     * An object of this type cannot be instantiated with a default constructor
     */
    @SuppressWarnings("unused")
    private MediaVideoItem() throws IOException {
        this(null, null, null, RENDERING_MODE_BLACK_BORDER);
    }

    /**
     * Constructor
     *
     * @param editor The video editor reference
     * @param mediaItemId The MediaItem id
     * @param filename The image file name
     * @param renderingMode The rendering mode
     *
     * @throws IOException if the file cannot be opened for reading
     */
    public MediaVideoItem(VideoEditor editor, String mediaItemId, String filename,
            int renderingMode)
        throws IOException {
        this(editor, mediaItemId, filename, renderingMode, 0, END_OF_FILE, 100, false, null);
    }

    /**
     * Constructor
     *
     * @param editor The video editor reference
     * @param mediaItemId The MediaItem id
     * @param filename The image file name
     * @param renderingMode The rendering mode
     * @param beginMs Start time in milliseconds. Set to 0 to extract from the
     *           beginning
     * @param endMs End time in milliseconds. Set to {@link #END_OF_FILE} to
     *           extract until the end
     * @param volumePercent in %/. 100% means no change; 50% means half value, 200%
     *            means double, 0% means silent.
     * @param muted true if the audio is muted
     * @param audioWaveformFilename The name of the audio waveform file
     *
     * @throws IOException if the file cannot be opened for reading
     */
    MediaVideoItem(VideoEditor editor, String mediaItemId, String filename, int renderingMode,
            long beginMs, long endMs, int volumePercent, boolean muted,
            String audioWaveformFilename)  throws IOException {
        super(editor, mediaItemId, filename, renderingMode);
        // TODO: Set these variables correctly
        mWidth = 1080;
        mHeight = 720;
        mAspectRatio = MediaProperties.ASPECT_RATIO_3_2;
        mFileType = MediaProperties.FILE_MP4;
        mVideoType = MediaProperties.VCODEC_H264BP;
        // Do we have predefined values for this variable?
        mVideoProfile = 0;
        // Can video and audio duration be different?
        mDurationMs = 10000;
        mVideoBitrate = 800000;
        mAudioBitrate = 30000;
        mFps = 30;
        mAudioType = MediaProperties.ACODEC_AAC_LC;
        mAudioChannels = 2;
        mAudioSamplingFrequency = 16000;

        mBeginBoundaryTimeMs = beginMs;
        mEndBoundaryTimeMs = endMs == END_OF_FILE ? mDurationMs : endMs;
        mVolumePercentage = volumePercent;
        mMuted = muted;
        mAudioWaveformFilename = audioWaveformFilename;
    }

    /**
     * Sets the start and end marks for trimming a video media item.
     * This method will adjust the duration of bounding transitions, effects
     * and overlays if the current duration of the transactions become greater
     * than the maximum allowable duration.
     *
     * @param beginMs Start time in milliseconds. Set to 0 to extract from the
     *           beginning
     * @param endMs End time in milliseconds. Set to {@link #END_OF_FILE} to
     *           extract until the end
     *
     * @throws IllegalArgumentException if the start time is greater or equal than
     *           end time, the end time is beyond the file duration, the start time
     *           is negative
     */
    public void setExtractBoundaries(long beginMs, long endMs) {
        if (beginMs > mDurationMs) {
            throw new IllegalArgumentException("Invalid start time");
        }
        if (endMs > mDurationMs) {
            throw new IllegalArgumentException("Invalid end time");
        }

        if (beginMs != mBeginBoundaryTimeMs) {
            if (mBeginTransition != null) {
                mBeginTransition.invalidate();
            }
        }

        if (endMs != mEndBoundaryTimeMs) {
            if (mEndTransition != null) {
                mEndTransition.invalidate();
            }
        }

        mBeginBoundaryTimeMs = beginMs;
        mEndBoundaryTimeMs = endMs;

        adjustElementsDuration();
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

    /*
     * {@inheritDoc}
     */
    @Override
    public void addEffect(Effect effect) {
        if (effect instanceof EffectKenBurns) {
            throw new IllegalArgumentException("Ken Burns effects cannot be applied to MediaVideoItem");
        }
        super.addEffect(effect);
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public Bitmap getThumbnail(int width, int height, long timeMs) {
        return null;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public Bitmap[] getThumbnailList(int width, int height, long startMs, long endMs,
            int thumbnailCount) throws IOException {
        return null;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int getAspectRatio() {
        return mAspectRatio;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int getFileType() {
        return mFileType;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int getWidth() {
        return mWidth;
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public int getHeight() {
        return mHeight;
    }

    /**
     * @return The duration of the video clip
     */
    public long getDuration() {
        return mDurationMs;
    }

    /**
     * @return The timeline duration. This is the actual duration in the
     *      timeline (trimmed duration)
     */
    @Override
    public long getTimelineDuration() {
        return mEndBoundaryTimeMs - mBeginBoundaryTimeMs;
    }

    /**
     * Render a frame according to the playback (in the native aspect ratio) for
     * the specified media item. All effects and overlays applied to the media
     * item are ignored. The extract boundaries are also ignored. This method
     * can be used to playback frames when implementing trimming functionality.
     *
     * @param surfaceHolder SurfaceHolder used by the application
     * @param timeMs time corresponding to the frame to display (relative to the
     *            the beginning of the media item).
     * @return The accurate time stamp of the frame that is rendered .
     * @throws IllegalStateException if a playback, preview or an export is
     *             already in progress
     * @throws IllegalArgumentException if time is negative or greater than the
     *             media item duration
     */
    public long renderFrame(SurfaceHolder surfaceHolder, long timeMs) {
        return timeMs;
    }

    /**
     * This API allows to generate a file containing the sample volume levels of
     * the Audio track of this media item. This function may take significant
     * time and is blocking. The file can be retrieved using
     * getAudioWaveformFilename().
     *
     * @param listener The progress listener
     *
     * @throws IOException if the output file cannot be created
     * @throws IllegalArgumentException if the mediaItem does not have a valid
     *             Audio track
     */
    public void extractAudioWaveform(ExtractAudioWaveformProgressListener listener)
            throws IOException {
        // TODO: Set mAudioWaveformFilename at the end once the export is complete
    }

    /**
     * Get the audio waveform file name if {@link #extractAudioWaveform()} was
     * successful. The file format is as following:
     * <ul>
     *  <li>first 4 bytes provide the number of samples for each value, as big-endian signed</li>
     *  <li>4 following bytes is the total number of values in the file, as big-endian signed</li>
     *  <li>all values follow as bytes Name is unique.</li>
     *</ul>
     * @return the name of the file, null if the file has not been computed or
     *         if there is no Audio track in the mediaItem
     */
    public String getAudioWaveformFilename() {
        return mAudioWaveformFilename;
    }

    /**
     * Set volume of the Audio track of this mediaItem
     *
     * @param volumePercent in %/. 100% means no change; 50% means half value, 200%
     *            means double, 0% means silent.
     * @throws UsupportedOperationException if volume value is not supported
     */
    public void setVolume(int volumePercent) {
        mVolumePercentage = volumePercent;
    }

    /**
     * Get the volume value of the audio track as percentage. Call of this
     * method before calling setVolume will always return 100%
     *
     * @return the volume in percentage
     */
    public int getVolume() {
        return mVolumePercentage;
    }

    /**
     * @param muted true to mute the media item
     */
    public void setMute(boolean muted) {
        mMuted = muted;
    }

    /**
     * @return true if the media item is muted
     */
    public boolean isMuted() {
        return mMuted;
    }

    /**
     * @return The video type
     */
    public int getVideoType() {
        return mVideoType;
    }

    /**
     * @return The video profile
     */
    public int getVideoProfile() {
        return mVideoProfile;
    }

    /**
     * @return The video bitrate
     */
    public int getVideoBitrate() {
        return mVideoBitrate;
    }

    /**
     * @return The audio bitrate
     */
    public int getAudioBitrate() {
        return mAudioBitrate;
    }

    /**
     * @return The number of frames per second
     */
    public int getFps() {
        return mFps;
    }

    /**
     * @return The audio codec
     */
    public int getAudioType() {
        return mAudioType;
    }

    /**
     * @return The number of audio channels
     */
    public int getAudioChannels() {
        return mAudioChannels;
    }

    /**
     * @return The audio sample frequency
     */
    public int getAudioSamplingFrequency() {
        return mAudioSamplingFrequency;
    }
}
