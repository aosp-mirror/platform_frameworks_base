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

import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;

/**
 * The CamcorderProfile class is used to retrieve the
 * predefined camcorder profile settings for camcorder applications.
 * These settings are read-only.
 *
 * The compressed output from a recording session with a given
 * CamcorderProfile contains two tracks: one for auido and one for video.
 *
 * <p>Each profile specifies the following set of parameters:
 * <ul>
 * <li> The file output format
 * <li> Video codec format
 * <li> Video bit rate in bits per second
 * <li> Video frame rate in frames per second
 * <li> Video frame width and height,
 * <li> Audio codec format
 * <li> Audio bit rate in bits per second,
 * <li> Audio sample rate
 * <li> Number of audio channels for recording.
 * </ul>
 */
public class CamcorderProfile
{
    /**
     * The output from camcorder recording sessions can have different quality levels.
     *
     * Currently, we define two quality levels: high quality and low quality.
     * A camcorder recording session with high quality level usually has higher output bit
     * rate, better video and/or audio recording quality, larger video frame
     * resolution and higher audio sampling rate, etc, than those with low quality
     * level.
     *
     * Do not change these values/ordinals without updating their counterpart
     * in include/media/MediaProfiles.h!
     */
    public static final int QUALITY_LOW  = 0;
    public static final int QUALITY_HIGH = 1;

    /**
     * Default recording duration in seconds before the session is terminated.
     * This is useful for applications like MMS has limited file size requirement.
     */
    public int duration;

    /**
     * The quality level of the camcorder profile
     */
    public int quality;

    /**
     * The file output format of the camcorder profile
     * @see android.media.MediaRecorder.OutputFormat
     */
    public int fileFormat;

    /**
     * The video encoder being used for the video track
     * @see android.media.MediaRecorder.VideoEncoder
     */
    public int videoCodec;

    /**
     * The target video output bit rate in bits per second
     */
    public int videoBitRate;

    /**
     * The target video frame rate in frames per second
     */
    public int videoFrameRate;

    /**
     * The target video frame width in pixels
     */
    public int videoFrameWidth;

    /**
     * The target video frame height in pixels
     */
    public int videoFrameHeight;

    /**
     * The audio encoder being used for the audio track.
     * @see android.media.MediaRecorder.AudioEncoder
     */
    public int audioCodec;

    /**
     * The target audio output bit rate in bits per second
     */
    public int audioBitRate;

    /**
     * The audio sampling rate used for the audio track
     */
    public int audioSampleRate;

    /**
     * The number of audio channels used for the audio track
     */
    public int audioChannels;

    /**
     * Returns the camcorder profile for the first back-facing camera on the
     * device at the given quality level. If the device has no back-facing
     * camera, this returns null.
     * @param quality the target quality level for the camcorder profile
     */
    public static CamcorderProfile get(int quality) {
        int numberOfCameras = Camera.getNumberOfCameras();
        CameraInfo cameraInfo = new CameraInfo();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == CameraInfo.CAMERA_FACING_BACK) {
                return get(i, quality);
            }
        }
        return null;
    }

    /**
     * Returns the camcorder profile for the given camera at the given
     * quality level.
     * @param cameraId the id for the camera
     * @param quality the target quality level for the camcorder profile
     */
    public static CamcorderProfile get(int cameraId, int quality) {
        if (quality < QUALITY_LOW || quality > QUALITY_HIGH) {
            String errMessage = "Unsupported quality level: " + quality;
            throw new IllegalArgumentException(errMessage);
        }
        return native_get_camcorder_profile(cameraId, quality);
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

        this.duration         = duration;
        this.quality          = quality;
        this.fileFormat       = fileFormat;
        this.videoCodec       = videoCodec;
        this.videoBitRate     = videoBitRate;
        this.videoFrameRate   = videoFrameRate;
        this.videoFrameWidth  = videoWidth;
        this.videoFrameHeight = videoHeight;
        this.audioCodec       = audioCodec;
        this.audioBitRate     = audioBitRate;
        this.audioSampleRate  = audioSampleRate;
        this.audioChannels    = audioChannels;
    }

    // Methods implemented by JNI
    private static native final void native_init();
    private static native final CamcorderProfile native_get_camcorder_profile(
            int cameraId, int quality);
}
