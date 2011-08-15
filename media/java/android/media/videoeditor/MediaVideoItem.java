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
import android.graphics.Bitmap;
import android.media.videoeditor.MediaArtistNativeHelper.ClipSettings;
import android.media.videoeditor.MediaArtistNativeHelper.Properties;
import android.media.videoeditor.VideoEditorProfile;
import android.view.Surface;
import android.view.SurfaceHolder;

/**
 * This class represents a video clip item on the storyboard
 * {@hide}
 */
public class MediaVideoItem extends MediaItem {

    /**
     *  Instance variables
     */
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
    private MediaArtistNativeHelper mMANativeHelper;
    private VideoEditorImpl mVideoEditor;
    /**
     *  The audio waveform data
     */
    private SoftReference<WaveformData> mWaveformData;

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
            int renderingMode) throws IOException {
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
    MediaVideoItem(VideoEditor editor, String mediaItemId, String filename,
            int renderingMode, long beginMs, long endMs, int volumePercent, boolean muted,
            String audioWaveformFilename)  throws IOException {
        super(editor, mediaItemId, filename, renderingMode);

        if (editor instanceof VideoEditorImpl) {
            mMANativeHelper = ((VideoEditorImpl)editor).getNativeContext();
            mVideoEditor = ((VideoEditorImpl)editor);
        }

        final Properties properties;
        try {
             properties = mMANativeHelper.getMediaProperties(filename);
        } catch ( Exception e) {
            throw new IllegalArgumentException(e.getMessage() + " : " + filename);
        }

        /** Check the platform specific maximum import resolution */
        VideoEditorProfile veProfile = VideoEditorProfile.get();
        if (veProfile == null) {
            throw new RuntimeException("Can't get the video editor profile");
        }
        final int maxInputWidth = veProfile.maxInputVideoFrameWidth;
        final int maxInputHeight = veProfile.maxInputVideoFrameHeight;
        if ((properties.width > maxInputWidth) ||
            (properties.height > maxInputHeight)) {
            throw new IllegalArgumentException(
                "Unsupported import resolution. Supported maximum width:" +
                maxInputWidth + " height:" + maxInputHeight +
                ", current width:" + properties.width +
                " height:" + properties.height);
        }
        switch (mMANativeHelper.getFileType(properties.fileType)) {
            case MediaProperties.FILE_3GP:
            case MediaProperties.FILE_MP4:
            case MediaProperties.FILE_M4V:
                break;

            default:
                throw new IllegalArgumentException("Unsupported Input File Type");
        }

        switch (mMANativeHelper.getVideoCodecType(properties.videoFormat)) {
            case MediaProperties.VCODEC_H263:
            case MediaProperties.VCODEC_H264BP:
            case MediaProperties.VCODEC_H264MP:
            case MediaProperties.VCODEC_MPEG4:
                break;

            default:
                throw new IllegalArgumentException("Unsupported Video Codec Format in Input File");
        }

        /* Check if the profile is unsupported. */
        if (properties.profileAndLevel == MediaProperties.UNDEFINED_VIDEO_PROFILE) {
            throw new IllegalArgumentException("Unsupported Video Codec Profile in Input File");
        }

        mWidth = properties.width;
        mHeight = properties.height;
        mAspectRatio = mMANativeHelper.getAspectRatio(properties.width,
                properties.height);
        mFileType = mMANativeHelper.getFileType(properties.fileType);
        mVideoType = mMANativeHelper.getVideoCodecType(properties.videoFormat);
        mVideoProfile = properties.profileAndLevel;
        mDurationMs = properties.videoDuration;
        mVideoBitrate = properties.videoBitrate;
        mAudioBitrate = properties.audioBitrate;
        mFps = (int)properties.averageFrameRate;
        mAudioType = mMANativeHelper.getAudioCodecType(properties.audioFormat);
        mAudioChannels = properties.audioChannels;
        mAudioSamplingFrequency =  properties.audioSamplingFrequency;
        mBeginBoundaryTimeMs = beginMs;
        mEndBoundaryTimeMs = endMs == END_OF_FILE ? mDurationMs : endMs;
        mVolumePercentage = volumePercent;
        mMuted = muted;
        mAudioWaveformFilename = audioWaveformFilename;
        if (audioWaveformFilename != null) {
            mWaveformData = new SoftReference<WaveformData>(
                        new WaveformData(audioWaveformFilename));
        } else {
            mWaveformData = null;
        }
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
            throw new IllegalArgumentException("setExtractBoundaries: Invalid start time");
        }

        if (endMs > mDurationMs) {
            throw new IllegalArgumentException("setExtractBoundaries: Invalid end time");
        }

        if ((endMs != -1) && (beginMs >= endMs) ) {
            throw new IllegalArgumentException("setExtractBoundaries: Start time is greater than end time");
        }

        if ((beginMs < 0) || ((endMs != -1) && (endMs < 0))) {
            throw new IllegalArgumentException("setExtractBoundaries: Start time or end time is negative");
        }

        mMANativeHelper.setGeneratePreview(true);

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
        adjustTransitions();
        mVideoEditor.updateTimelineDuration();
        /**
         *  Note that the start and duration of any effects and overlays are
         *  not adjusted nor are they automatically removed if they fall
         *  outside the new boundaries.
         */
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
        if (timeMs > mDurationMs) {
            throw new IllegalArgumentException("Time Exceeds duration");
        }

        if (timeMs < 0) {
            throw new IllegalArgumentException("Invalid Time duration");
        }

        if ((width <= 0) || (height <= 0)) {
            throw new IllegalArgumentException("Invalid Dimensions");
        }

        return mMANativeHelper.getPixels(super.getFilename(), width, height,timeMs);
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public void getThumbnailList(int width, int height,
                                 long startMs, long endMs,
                                 int thumbnailCount,
                                 int[] indices,
                                 GetThumbnailListCallback callback)
                                 throws IOException {
        if (startMs > endMs) {
            throw new IllegalArgumentException("Start time is greater than end time");
        }

        if (endMs > mDurationMs) {
            throw new IllegalArgumentException("End time is greater than file duration");
        }

        if ((height <= 0) || (width <= 0)) {
            throw new IllegalArgumentException("Invalid dimension");
        }

        mMANativeHelper.getPixelsList(super.getFilename(), width,
                height, startMs, endMs, thumbnailCount, indices, callback);
    }

    /*
     * {@inheritDoc}
     */
    @Override
    void invalidateTransitions(long startTimeMs, long durationMs) {
        /**
         *  Check if the item overlaps with the beginning and end transitions
         */
        if (mBeginTransition != null) {
            if (isOverlapping(startTimeMs, durationMs,
                    mBeginBoundaryTimeMs, mBeginTransition.getDuration())) {
                mBeginTransition.invalidate();
            }
        }

        if (mEndTransition != null) {
            final long transitionDurationMs = mEndTransition.getDuration();
            if (isOverlapping(startTimeMs, durationMs,
                    mEndBoundaryTimeMs - transitionDurationMs, transitionDurationMs)) {
                mEndTransition.invalidate();
            }
        }
    }

    /*
     * {@inheritDoc}
     */
    @Override
    void invalidateTransitions(long oldStartTimeMs, long oldDurationMs, long newStartTimeMs,
            long newDurationMs) {
        /**
         *  Check if the item overlaps with the beginning and end transitions
         */
        if (mBeginTransition != null) {
            final long transitionDurationMs = mBeginTransition.getDuration();
            final boolean oldOverlap = isOverlapping(oldStartTimeMs, oldDurationMs,
                    mBeginBoundaryTimeMs, transitionDurationMs);
            final boolean newOverlap = isOverlapping(newStartTimeMs, newDurationMs,
                    mBeginBoundaryTimeMs, transitionDurationMs);
            /**
             * Invalidate transition if:
             *
             * 1. New item overlaps the transition, the old one did not
             * 2. New item does not overlap the transition, the old one did
             * 3. New and old item overlap the transition if begin or end
             * time changed
             */
            if (newOverlap != oldOverlap) { // Overlap has changed
                mBeginTransition.invalidate();
            } else if (newOverlap) { // Both old and new overlap
                if ((oldStartTimeMs != newStartTimeMs) ||
                        !(oldStartTimeMs + oldDurationMs > transitionDurationMs &&
                        newStartTimeMs + newDurationMs > transitionDurationMs)) {
                    mBeginTransition.invalidate();
                }
            }
        }

        if (mEndTransition != null) {
            final long transitionDurationMs = mEndTransition.getDuration();
            final boolean oldOverlap = isOverlapping(oldStartTimeMs, oldDurationMs,
                    mEndBoundaryTimeMs - transitionDurationMs, transitionDurationMs);
            final boolean newOverlap = isOverlapping(newStartTimeMs, newDurationMs,
                    mEndBoundaryTimeMs - transitionDurationMs, transitionDurationMs);
            /**
             * Invalidate transition if:
             *
             * 1. New item overlaps the transition, the old one did not
             * 2. New item does not overlap the transition, the old one did
             * 3. New and old item overlap the transition if begin or end
             * time changed
             */
            if (newOverlap != oldOverlap) { // Overlap has changed
                mEndTransition.invalidate();
            } else if (newOverlap) { // Both old and new overlap
                if ((oldStartTimeMs + oldDurationMs != newStartTimeMs + newDurationMs) ||
                        ((oldStartTimeMs > mEndBoundaryTimeMs - transitionDurationMs) ||
                        newStartTimeMs > mEndBoundaryTimeMs - transitionDurationMs)) {
                    mEndTransition.invalidate();
                }
            }
        }
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

    /*
     * {@inheritDoc}
     */
    @Override
    public long getDuration() {
        return mDurationMs;
    }

    /*
     * {@inheritDoc}
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
        if (surfaceHolder == null) {
            throw new IllegalArgumentException("Surface Holder is null");
        }

        if (timeMs > mDurationMs || timeMs < 0) {
            throw new IllegalArgumentException("requested time not correct");
        }

        final Surface surface = surfaceHolder.getSurface();
        if (surface == null) {
            throw new RuntimeException("Surface could not be retrieved from Surface holder");
        }

        if (mFilename != null) {
            return mMANativeHelper.renderMediaItemPreviewFrame(surface,
                    mFilename,timeMs,mWidth,mHeight);
        } else {
            return 0;
        }
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
        int frameDuration = 0;
        int sampleCount = 0;
        final String projectPath = mMANativeHelper.getProjectPath();
        /**
         *  Waveform file does not exist
         */
        if (mAudioWaveformFilename == null ) {
            /**
             * Since audioWaveformFilename will not be supplied,it is  generated
             */
            String mAudioWaveFileName = null;

            mAudioWaveFileName =
                String.format(projectPath + "/" + "audioWaveformFile-"+ getId() + ".dat");
            /**
             * Logic to get frame duration = (no. of frames per sample * 1000)/
             * sampling frequency
             */
            if (mMANativeHelper.getAudioCodecType(mAudioType) ==
                MediaProperties.ACODEC_AMRNB ) {
                frameDuration = (MediaProperties.SAMPLES_PER_FRAME_AMRNB*1000)/
                MediaProperties.DEFAULT_SAMPLING_FREQUENCY;
                sampleCount = MediaProperties.SAMPLES_PER_FRAME_AMRNB;
            } else if (mMANativeHelper.getAudioCodecType(mAudioType) ==
                MediaProperties.ACODEC_AMRWB ) {
                frameDuration = (MediaProperties.SAMPLES_PER_FRAME_AMRWB * 1000)/
                MediaProperties.DEFAULT_SAMPLING_FREQUENCY;
                sampleCount = MediaProperties.SAMPLES_PER_FRAME_AMRWB;
            } else if (mMANativeHelper.getAudioCodecType(mAudioType) ==
                MediaProperties.ACODEC_AAC_LC ) {
                frameDuration = (MediaProperties.SAMPLES_PER_FRAME_AAC * 1000)/
                MediaProperties.DEFAULT_SAMPLING_FREQUENCY;
                sampleCount = MediaProperties.SAMPLES_PER_FRAME_AAC;
            }

            mMANativeHelper.generateAudioGraph( getId(),
                    mFilename,
                    mAudioWaveFileName,
                    frameDuration,
                    MediaProperties.DEFAULT_CHANNEL_COUNT,
                    sampleCount,
                    listener,
                    true);
            /**
             * Record the generated file name
             */
            mAudioWaveformFilename = mAudioWaveFileName;
        }
        mWaveformData =
            new SoftReference<WaveformData>(new WaveformData(mAudioWaveformFilename));
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
    String getAudioWaveformFilename() {
        return mAudioWaveformFilename;
    }

    /**
     * Invalidate the AudioWaveform File
     */
    void invalidate() {
        if (mAudioWaveformFilename != null) {
            new File(mAudioWaveformFilename).delete();
            mAudioWaveformFilename = null;
        }
    }

    /**
     * @return The waveform data
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
            } catch(IOException e) {
                throw e;
            }
            mWaveformData = new SoftReference<WaveformData>(waveformData);
            return waveformData;
        } else {
            return null;
        }
    }

    /**
     * Set volume of the Audio track of this mediaItem
     *
     * @param volumePercent in %/. 100% means no change; 50% means half value, 200%
     *            means double, 0% means silent.
     * @throws UsupportedOperationException if volume value is not supported
     */
    public void setVolume(int volumePercent) {
        if ((volumePercent <0) || (volumePercent >100)) {
            throw new IllegalArgumentException("Invalid volume");
        }

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
        mMANativeHelper.setGeneratePreview(true);
        mMuted = muted;
        if (mBeginTransition != null) {
            mBeginTransition.invalidate();
        }
        if (mEndTransition != null) {
            mEndTransition.invalidate();
        }
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

    /**
     * @return The Video media item properties in ClipSettings class object
     * {@link android.media.videoeditor.MediaArtistNativeHelper.ClipSettings}
     */
    ClipSettings getVideoClipProperties() {
        ClipSettings clipSettings = new ClipSettings();
        clipSettings.clipPath = getFilename();
        clipSettings.fileType = mMANativeHelper.getMediaItemFileType(getFileType());
        clipSettings.beginCutTime = (int)getBoundaryBeginTime();
        clipSettings.endCutTime = (int)getBoundaryEndTime();
        clipSettings.mediaRendering = mMANativeHelper.getMediaItemRenderingMode(getRenderingMode());

        return clipSettings;
    }
}
