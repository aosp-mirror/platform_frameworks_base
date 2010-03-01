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

package android.media;

/**
 * The CamcorderProfile class is used to retrieve the
 * predefined camcorder profile settings for camcorder applications.
 * The compressed output from a recording session with a given
 * CamcorderProfile contains two tracks: one for auido and one for video.
 *
 * <p>Each profile specifies the following set of parameters:
 * <ul>
 * <li> The file output format, @see android.media.MediaRecorder.OutputFormat
 * <li> Video codec format, @see android.media.MediaRecorder.VideoEncoder
 * <li> Video bit rate in bits per second
 * <li> Video frame rate in frames per second
 * <li> Video frame width and height,
 * <li> Audio codec format, @see android.media.MediaRecorder.AudioEncoder
 * <li> Audio bit rate in bits per second,
 * <li> Audio sample rate
 * <li> Number of audio channels for recording.
 * </ul>
 */
public class CamcorderProfile
{
    private final int mDuration;  // Recording duration in seconds

    /**
     * The Quality class represents the quality level of each CamcorderProfile.
     *
     * The output from recording sessions with high quality level usually may have
     * larger output bit rate, better video and/or audio recording quality, and
     * laerger video frame resolution and higher audio sampling rate, etc, than those
     * with low quality level.
     */
    public enum Quality {
       /* Do not change these values/ordinals without updating their counterpart
        * in include/media/MediaProfiles.h!
        */
        HIGH,
        LOW
    };

    /**
     * Returns the recording duration in seconds for LOW quality CamcorderProfile
     * used by the MMS application.
     */
    public static final int getMmsRecordingDurationInSeconds() {
        return get(Quality.LOW).mDuration;
    }

    /**
     * The quality level of the camcorder profile
     * @see android.media.CamcorderProfile.Quality
     */
    public final Quality mQuality;

    /**
     * The file output format of the camcorder profile
     * @see android.media.MediaRecorder.OutputFormat
     */
    public final int mFileFormat;

    /**
     * The video encoder being used for the video track
     * @see android.media.MediaRecorder.VideoEncoder
     */
    public final int mVideoCodec;

    /**
     * The target video output bit rate in bits per second
     */
    public final int mVideoBitRate;

    /**
     * The target video frame rate in frames per second
     */
    public final int mVideoFrameRate;

    /**
     * The target video frame width in pixels
     */
    public final int mVideoFrameWidth;

    /**
     * The target video frame height in pixels
     */
    public final int mVideoFrameHeight;

    /**
     * The audio encoder being used for the audio track.
     * @see android.media.MediaRecorder.AudioEncoder
     */
    public final int mAudioCodec;

    /**
     * The target audio output bit rate in bits per second
     */
    public final int mAudioBitRate;

    /**
     * The audio sampling rate used for the audio track
     */
    public final int mAudioSampleRate;

    /**
     * The number of audio channels used for the audio track
     */
    public final int mAudioChannels;

    /**
     * Returns the camcorder profile for the given quality.
     * @param quality the target quality level for the camcorder profile
     * @see android.media.CamcorderProfile.Quality
     */
    public static CamcorderProfile get(Quality quality) {
        return native_get_camcorder_profile(quality.ordinal());
    }

    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    // Private constructor called by JNI
    private CamcorderProfile(int duration,
                             int quality,
                             int fileFormat,
                             int videoCodec,
                             int videoBitRate,
                             int videoFrameRate,
                             int videoWidth,
                             int videoHeight,
                             int audioCodec,
                             int audioBitRate,
                             int audioSampleRate,
                             int audioChannels) {

        mDuration         = duration;
        mQuality          = Quality.values()[quality];
        mFileFormat       = fileFormat;
        mVideoCodec       = videoCodec;
        mVideoBitRate     = videoBitRate;
        mVideoFrameRate   = videoFrameRate;
        mVideoFrameWidth  = videoWidth;
        mVideoFrameHeight = videoHeight;
        mAudioCodec       = audioCodec;
        mAudioBitRate     = audioBitRate;
        mAudioSampleRate  = audioSampleRate;
        mAudioChannels    = audioChannels;
    }

    // Methods implemented by JNI
    private static native final void native_init();
    private static native final CamcorderProfile native_get_camcorder_profile(int quality);
}
