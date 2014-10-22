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
 * Retrieves the
 * predefined camcorder profile settings for camcorder applications.
 * These settings are read-only.
 *
 * <p>The compressed output from a recording session with a given
 * CamcorderProfile contains two tracks: one for audio and one for video.
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
    // Do not change these values/ordinals without updating their counterpart
    // in include/media/MediaProfiles.h!

    /**
     * Quality level corresponding to the lowest available resolution.
     */
    public static final int QUALITY_LOW  = 0;

    /**
     * Quality level corresponding to the highest available resolution.
     */
    public static final int QUALITY_HIGH = 1;

    /**
     * Quality level corresponding to the qcif (176 x 144) resolution.
     */
    public static final int QUALITY_QCIF = 2;

    /**
     * Quality level corresponding to the cif (352 x 288) resolution.
     */
    public static final int QUALITY_CIF = 3;

    /**
     * Quality level corresponding to the 480p (720 x 480) resolution.
     * Note that the horizontal resolution for 480p can also be other
     * values, such as 640 or 704, instead of 720.
     */
    public static final int QUALITY_480P = 4;

    /**
     * Quality level corresponding to the 720p (1280 x 720) resolution.
     */
    public static final int QUALITY_720P = 5;

    /**
     * Quality level corresponding to the 1080p (1920 x 1080) resolution.
     * Note that the vertical resolution for 1080p can also be 1088,
     * instead of 1080 (used by some vendors to avoid cropping during
     * video playback).
     */
    public static final int QUALITY_1080P = 6;

    /**
     * Quality level corresponding to the QVGA (320x240) resolution.
     */
    public static final int QUALITY_QVGA = 7;

    /**
     * Quality level corresponding to the 2160p (3840x2160) resolution.
     */
    public static final int QUALITY_2160P = 8;

    // Start and end of quality list
    private static final int QUALITY_LIST_START = QUALITY_LOW;
    private static final int QUALITY_LIST_END = QUALITY_2160P;

    /**
     * Time lapse quality level corresponding to the lowest available resolution.
     */
    public static final int QUALITY_TIME_LAPSE_LOW  = 1000;

    /**
     * Time lapse quality level corresponding to the highest available resolution.
     */
    public static final int QUALITY_TIME_LAPSE_HIGH = 1001;

    /**
     * Time lapse quality level corresponding to the qcif (176 x 144) resolution.
     */
    public static final int QUALITY_TIME_LAPSE_QCIF = 1002;

    /**
     * Time lapse quality level corresponding to the cif (352 x 288) resolution.
     */
    public static final int QUALITY_TIME_LAPSE_CIF = 1003;

    /**
     * Time lapse quality level corresponding to the 480p (720 x 480) resolution.
     */
    public static final int QUALITY_TIME_LAPSE_480P = 1004;

    /**
     * Time lapse quality level corresponding to the 720p (1280 x 720) resolution.
     */
    public static final int QUALITY_TIME_LAPSE_720P = 1005;

    /**
     * Time lapse quality level corresponding to the 1080p (1920 x 1088) resolution.
     */
    public static final int QUALITY_TIME_LAPSE_1080P = 1006;

    /**
     * Time lapse quality level corresponding to the QVGA (320 x 240) resolution.
     */
    public static final int QUALITY_TIME_LAPSE_QVGA = 1007;

    /**
     * Time lapse quality level corresponding to the 2160p (3840 x 2160) resolution.
     */
    public static final int QUALITY_TIME_LAPSE_2160P = 1008;

    // Start and end of timelapse quality list
    private static final int QUALITY_TIME_LAPSE_LIST_START = QUALITY_TIME_LAPSE_LOW;
    private static final int QUALITY_TIME_LAPSE_LIST_END = QUALITY_TIME_LAPSE_2160P;

    /**
     * High speed ( >= 100fps) quality level corresponding to the lowest available resolution.
     */
    public static final int QUALITY_HIGH_SPEED_LOW = 2000;

    /**
     * High speed ( >= 100fps) quality level corresponding to the highest available resolution.
     */
    public static final int QUALITY_HIGH_SPEED_HIGH = 2001;

    /**
     * High speed ( >= 100fps) quality level corresponding to the 480p (720 x 480) resolution.
     *
     * Note that the horizontal resolution for 480p can also be other
     * values, such as 640 or 704, instead of 720.
     */
    public static final int QUALITY_HIGH_SPEED_480P = 2002;

    /**
     * High speed ( >= 100fps) quality level corresponding to the 720p (1280 x 720) resolution.
     */
    public static final int QUALITY_HIGH_SPEED_720P = 2003;

    /**
     * High speed ( >= 100fps) quality level corresponding to the 1080p (1920 x 1080 or 1920x1088)
     * resolution.
     */
    public static final int QUALITY_HIGH_SPEED_1080P = 2004;

    /**
     * High speed ( >= 100fps) quality level corresponding to the 2160p (3840 x 2160)
     * resolution.
     */
    public static final int QUALITY_HIGH_SPEED_2160P = 2005;

    // Start and end of high speed quality list
    private static final int QUALITY_HIGH_SPEED_LIST_START = QUALITY_HIGH_SPEED_LOW;
    private static final int QUALITY_HIGH_SPEED_LIST_END = QUALITY_HIGH_SPEED_2160P;

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
     * @see #get(int, int)
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
     *
     * Quality levels QUALITY_LOW, QUALITY_HIGH are guaranteed to be supported, while
     * other levels may or may not be supported. The supported levels can be checked using
     * {@link #hasProfile(int, int)}.
     * QUALITY_LOW refers to the lowest quality available, while QUALITY_HIGH refers to
     * the highest quality available.
     * QUALITY_LOW/QUALITY_HIGH have to match one of qcif, cif, 480p, 720p, 1080p or 2160p.
     * E.g. if the device supports 480p, 720p, 1080p and 2160p, then low is 480p and high is
     * 2160p.
     *
     * The same is true for time lapse quality levels, i.e. QUALITY_TIME_LAPSE_LOW,
     * QUALITY_TIME_LAPSE_HIGH are guaranteed to be supported and have to match one of
     * qcif, cif, 480p, 720p, 1080p, or 2160p.
     *
     * For high speed quality levels, they may or may not be supported. If a subset of the levels
     * are supported, QUALITY_HIGH_SPEED_LOW and QUALITY_HIGH_SPEED_HIGH are guaranteed to be
     * supported and have to match one of 480p, 720p, or 1080p.
     *
     * A camcorder recording session with higher quality level usually has higher output
     * bit rate, better video and/or audio recording quality, larger video frame
     * resolution and higher audio sampling rate, etc, than those with lower quality
     * level.
     *
     * @param cameraId the id for the camera
     * @param quality the target quality level for the camcorder profile.
     * @see #QUALITY_LOW
     * @see #QUALITY_HIGH
     * @see #QUALITY_QCIF
     * @see #QUALITY_CIF
     * @see #QUALITY_480P
     * @see #QUALITY_720P
     * @see #QUALITY_1080P
     * @see #QUALITY_2160P
     * @see #QUALITY_TIME_LAPSE_LOW
     * @see #QUALITY_TIME_LAPSE_HIGH
     * @see #QUALITY_TIME_LAPSE_QCIF
     * @see #QUALITY_TIME_LAPSE_CIF
     * @see #QUALITY_TIME_LAPSE_480P
     * @see #QUALITY_TIME_LAPSE_720P
     * @see #QUALITY_TIME_LAPSE_1080P
     * @see #QUALITY_TIME_LAPSE_2160P
     * @see #QUALITY_HIGH_SPEED_LOW
     * @see #QUALITY_HIGH_SPEED_HIGH
     * @see #QUALITY_HIGH_SPEED_480P
     * @see #QUALITY_HIGH_SPEED_720P
     * @see #QUALITY_HIGH_SPEED_1080P
     * @see #QUALITY_HIGH_SPEED_2160P
    */
    public static CamcorderProfile get(int cameraId, int quality) {
        if (!((quality >= QUALITY_LIST_START &&
               quality <= QUALITY_LIST_END) ||
              (quality >= QUALITY_TIME_LAPSE_LIST_START &&
               quality <= QUALITY_TIME_LAPSE_LIST_END) ||
               (quality >= QUALITY_HIGH_SPEED_LIST_START &&
               quality <= QUALITY_HIGH_SPEED_LIST_END))) {
            String errMessage = "Unsupported quality level: " + quality;
            throw new IllegalArgumentException(errMessage);
        }
        return native_get_camcorder_profile(cameraId, quality);
    }

    /**
     * Returns true if camcorder profile exists for the first back-facing
     * camera at the given quality level.
     *
     * <p>
     * When using the Camera 2 API in {@code LEGACY} mode (i.e. when
     * {@link android.hardware.camera2.CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL} is set
     * to
     * {@link android.hardware.camera2.CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY}),
     * {@link #hasProfile} may return {@code true} for unsupported resolutions.  To ensure a
     * a given resolution is supported in LEGACY mode, the configuration given in
     * {@link android.hardware.camera2.CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP}
     * must contain the the resolution in the supported output sizes.  The recommended way to check
     * this is with
     * {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputSizes(Class)} with the
     * class of the desired recording endpoint, and check that the desired resolution is contained
     * in the list returned.
     * </p>
     * @see android.hardware.camera2.CameraManager
     * @see android.hardware.camera2.CameraCharacteristics
     *
     * @param quality the target quality level for the camcorder profile
     */
    public static boolean hasProfile(int quality) {
        int numberOfCameras = Camera.getNumberOfCameras();
        CameraInfo cameraInfo = new CameraInfo();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == CameraInfo.CAMERA_FACING_BACK) {
                return hasProfile(i, quality);
            }
        }
        return false;
    }

    /**
     * Returns true if camcorder profile exists for the given camera at
     * the given quality level.
     *
     * <p>
     * When using the Camera 2 API in LEGACY mode (i.e. when
     * {@link android.hardware.camera2.CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL} is set
     * to
     * {@link android.hardware.camera2.CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY}),
     * {@link #hasProfile} may return {@code true} for unsupported resolutions.  To ensure a
     * a given resolution is supported in LEGACY mode, the configuration given in
     * {@link android.hardware.camera2.CameraCharacteristics#SCALER_STREAM_CONFIGURATION_MAP}
     * must contain the the resolution in the supported output sizes.  The recommended way to check
     * this is with
     * {@link android.hardware.camera2.params.StreamConfigurationMap#getOutputSizes(Class)} with the
     * class of the desired recording endpoint, and check that the desired resolution is contained
     * in the list returned.
     * </p>
     * @see android.hardware.camera2.CameraManager
     * @see android.hardware.camera2.CameraCharacteristics
     *
     * @param cameraId the id for the camera
     * @param quality the target quality level for the camcorder profile
     */
    public static boolean hasProfile(int cameraId, int quality) {
        return native_has_camcorder_profile(cameraId, quality);
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
    private static native final boolean native_has_camcorder_profile(
            int cameraId, int quality);
}
