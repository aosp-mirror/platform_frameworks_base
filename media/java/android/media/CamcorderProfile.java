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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.os.Build;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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

    /**
     * Quality level corresponding to the VGA (640 x 480) resolution.
     */
    public static final int QUALITY_VGA = 9;

    /**
     * Quality level corresponding to 4k-DCI (4096 x 2160) resolution.
     */
    public static final int QUALITY_4KDCI = 10;

    /**
     * Quality level corresponding to QHD (2560 x 1440) resolution
     */
    public static final int QUALITY_QHD = 11;

    /**
     * Quality level corresponding to 2K (2048 x 1080) resolution
     */
    public static final int QUALITY_2K = 12;

    /**
     * Quality level corresponding to 8K UHD (7680 x 4320) resolution
     */
    public static final int QUALITY_8KUHD = 13;

    // Start and end of quality list
    private static final int QUALITY_LIST_START = QUALITY_LOW;
    private static final int QUALITY_LIST_END = QUALITY_8KUHD;

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

    /**
     * Time lapse quality level corresponding to the VGA (640 x 480) resolution.
     */
    public static final int QUALITY_TIME_LAPSE_VGA = 1009;

    /**
     * Time lapse quality level corresponding to the 4k-DCI (4096 x 2160) resolution.
     */
    public static final int QUALITY_TIME_LAPSE_4KDCI = 1010;

    /**
     * Time lapse quality level corresponding to the QHD (2560 x 1440) resolution.
     */
    public static final int QUALITY_TIME_LAPSE_QHD = 1011;

    /**
     * Time lapse quality level corresponding to the 2K (2048 x 1080) resolution.
     */
    public static final int QUALITY_TIME_LAPSE_2K = 1012;

    /**
     * Time lapse quality level corresponding to the 8K UHD (7680 x 4320) resolution.
     */
    public static final int QUALITY_TIME_LAPSE_8KUHD = 1013;

    // Start and end of timelapse quality list
    private static final int QUALITY_TIME_LAPSE_LIST_START = QUALITY_TIME_LAPSE_LOW;
    private static final int QUALITY_TIME_LAPSE_LIST_END = QUALITY_TIME_LAPSE_8KUHD;

    /**
     * High speed ( >= 100fps) quality level corresponding to the lowest available resolution.
     * <p>
     * For all the high speed profiles defined below ((from {@link #QUALITY_HIGH_SPEED_LOW} to
     * {@link #QUALITY_HIGH_SPEED_2160P}), they are similar as normal recording profiles, with just
     * higher output frame rate and bit rate. Therefore, setting these profiles with
     * {@link MediaRecorder#setProfile} without specifying any other encoding parameters will
     * produce high speed videos rather than slow motion videos that have different capture and
     * output (playback) frame rates. To record slow motion videos, the application must set video
     * output (playback) frame rate and bit rate appropriately via
     * {@link MediaRecorder#setVideoFrameRate} and {@link MediaRecorder#setVideoEncodingBitRate}
     * based on the slow motion factor. If the application intends to do the video recording with
     * {@link MediaCodec} encoder, it must set each individual field of {@link MediaFormat}
     * similarly according to this CamcorderProfile.
     * </p>
     *
     * @see #videoBitRate
     * @see #videoFrameRate
     * @see MediaRecorder
     * @see MediaCodec
     * @see MediaFormat
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

    /**
     * High speed ( >= 100fps) quality level corresponding to the CIF (352 x 288)
     */
    public static final int QUALITY_HIGH_SPEED_CIF = 2006;

    /**
     * High speed ( >= 100fps) quality level corresponding to the VGA (640 x 480)
     */
    public static final int QUALITY_HIGH_SPEED_VGA = 2007;

    /**
     * High speed ( >= 100fps) quality level corresponding to the 4K-DCI (4096 x 2160)
     */
    public static final int QUALITY_HIGH_SPEED_4KDCI = 2008;

    // Start and end of high speed quality list
    private static final int QUALITY_HIGH_SPEED_LIST_START = QUALITY_HIGH_SPEED_LOW;
    private static final int QUALITY_HIGH_SPEED_LIST_END = QUALITY_HIGH_SPEED_4KDCI;

    /**
     * @hide
     */
    @IntDef({
        QUALITY_LOW,
        QUALITY_HIGH,
        QUALITY_QCIF,
        QUALITY_CIF,
        QUALITY_480P,
        QUALITY_720P,
        QUALITY_1080P,
        QUALITY_QVGA,
        QUALITY_2160P,
        QUALITY_VGA,
        QUALITY_4KDCI,
        QUALITY_QHD,
        QUALITY_2K,
        QUALITY_8KUHD,

        QUALITY_TIME_LAPSE_LOW ,
        QUALITY_TIME_LAPSE_HIGH,
        QUALITY_TIME_LAPSE_QCIF,
        QUALITY_TIME_LAPSE_CIF,
        QUALITY_TIME_LAPSE_480P,
        QUALITY_TIME_LAPSE_720P,
        QUALITY_TIME_LAPSE_1080P,
        QUALITY_TIME_LAPSE_QVGA,
        QUALITY_TIME_LAPSE_2160P,
        QUALITY_TIME_LAPSE_VGA,
        QUALITY_TIME_LAPSE_4KDCI,
        QUALITY_TIME_LAPSE_QHD,
        QUALITY_TIME_LAPSE_2K,
        QUALITY_TIME_LAPSE_8KUHD,

        QUALITY_HIGH_SPEED_LOW,
        QUALITY_HIGH_SPEED_HIGH,
        QUALITY_HIGH_SPEED_480P,
        QUALITY_HIGH_SPEED_720P,
        QUALITY_HIGH_SPEED_1080P,
        QUALITY_HIGH_SPEED_2160P,
        QUALITY_HIGH_SPEED_CIF,
        QUALITY_HIGH_SPEED_VGA,
        QUALITY_HIGH_SPEED_4KDCI,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Quality {}

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
     * <p>
     * This is the target recorded video output bit rate if the application configures the video
     * recording via {@link MediaRecorder#setProfile} without specifying any other
     * {@link MediaRecorder} encoding parameters. For example, for high speed quality profiles (from
     * {@link #QUALITY_HIGH_SPEED_LOW} to {@link #QUALITY_HIGH_SPEED_2160P}), this is the bit rate
     * where the video is recorded with. If the application intends to record slow motion videos
     * with the high speed quality profiles, it must set a different video bit rate that is
     * corresponding to the desired recording output bit rate (i.e., the encoded video bit rate
     * during normal playback) via {@link MediaRecorder#setVideoEncodingBitRate}. For example, if
     * {@link #QUALITY_HIGH_SPEED_720P} advertises 240fps {@link #videoFrameRate} and 64Mbps
     * {@link #videoBitRate} in the high speed CamcorderProfile, and the application intends to
     * record 1/8 factor slow motion recording videos, the application must set 30fps via
     * {@link MediaRecorder#setVideoFrameRate} and 8Mbps ( {@link #videoBitRate} * slow motion
     * factor) via {@link MediaRecorder#setVideoEncodingBitRate}. Failing to do so will result in
     * videos with unexpected frame rate and bit rate, or {@link MediaRecorder} error if the output
     * bit rate exceeds the encoder limit. If the application intends to do the video recording with
     * {@link MediaCodec} encoder, it must set each individual field of {@link MediaFormat}
     * similarly according to this CamcorderProfile.
     * </p>
     *
     * @see #videoFrameRate
     * @see MediaRecorder
     * @see MediaCodec
     * @see MediaFormat
     */
    public int videoBitRate;

    /**
     * The target video frame rate in frames per second.
     * <p>
     * This is the target recorded video output frame rate per second if the application configures
     * the video recording via {@link MediaRecorder#setProfile} without specifying any other
     * {@link MediaRecorder} encoding parameters. For example, for high speed quality profiles (from
     * {@link #QUALITY_HIGH_SPEED_LOW} to {@link #QUALITY_HIGH_SPEED_2160P}), this is the frame rate
     * where the video is recorded and played back with. If the application intends to create slow
     * motion use case with the high speed quality profiles, it must set a different video frame
     * rate that is corresponding to the desired output (playback) frame rate via
     * {@link MediaRecorder#setVideoFrameRate}. For example, if {@link #QUALITY_HIGH_SPEED_720P}
     * advertises 240fps {@link #videoFrameRate} in the CamcorderProfile, and the application
     * intends to create 1/8 factor slow motion recording videos, the application must set 30fps via
     * {@link MediaRecorder#setVideoFrameRate}. Failing to do so will result in high speed videos
     * with normal speed playback frame rate (240fps for above example). If the application intends
     * to do the video recording with {@link MediaCodec} encoder, it must set each individual field
     * of {@link MediaFormat} similarly according to this CamcorderProfile.
     * </p>
     *
     * @see #videoBitRate
     * @see MediaRecorder
     * @see MediaCodec
     * @see MediaFormat
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
     * Returns the default camcorder profile at the given quality level for the first back-facing
     * camera on the device. If the device has no back-facing camera, this returns null.
     * @param quality the target quality level for the camcorder profile
     * @see #get(int, int)
     * @deprecated Use {@link #getAll} instead
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
     * Returns the default camcorder profile for the given camera at the given quality level.
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
     * @param cameraId the id for the camera. Integer camera ids parsed from the list received by
     *                 invoking {@link CameraManager#getCameraIdList} can be used as long as they
     *                 are {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE}
     *                 and not
     *                 {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL EXTERNAL}.
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
     * @deprecated Use {@link #getAll} instead
     * @throws IllegalArgumentException if quality is not one of the defined QUALITY_ values.
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
     * Returns all encoder profiles of a camcorder profile for the given camera at
     * the given quality level.
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
     * @param cameraId the id for the camera. Numeric camera ids from the list received by invoking
     *                 {@link CameraManager#getCameraIdList} can be used as long as they are
     *                 {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE}
     *                 and not
     *                 {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL EXTERNAL}.
     * @param quality the target quality level for the camcorder profile.
     * @return null if there are no encoder profiles defined for the quality level for the
     * given camera.
     * @throws IllegalArgumentException if quality is not one of the defined QUALITY_ values.
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
    @Nullable public static EncoderProfiles getAll(
            @NonNull String cameraId, @Quality int quality) {
        if (!((quality >= QUALITY_LIST_START &&
               quality <= QUALITY_LIST_END) ||
              (quality >= QUALITY_TIME_LAPSE_LIST_START &&
               quality <= QUALITY_TIME_LAPSE_LIST_END) ||
               (quality >= QUALITY_HIGH_SPEED_LIST_START &&
               quality <= QUALITY_HIGH_SPEED_LIST_END))) {
            String errMessage = "Unsupported quality level: " + quality;
            throw new IllegalArgumentException(errMessage);
        }

        // TODO: get all profiles
        int id;
        try {
            id = Integer.valueOf(cameraId);
        } catch (NumberFormatException e) {
            return null;
        }
        return native_get_camcorder_profiles(id, quality);
    }

    /**
     * Returns true if a camcorder profile exists for the first back-facing
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
     * Returns true if a camcorder profile exists for the given camera at
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
     * @param cameraId the id for the camera. Integer camera ids parsed from the list received by
     *                 invoking {@link CameraManager#getCameraIdList} can be used as long as they
     *                 are {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE}
     *                 and not
     *                 {@link CameraMetadata#INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL EXTERNAL}.
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private static native final void native_init();
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private static native final CamcorderProfile native_get_camcorder_profile(
            int cameraId, int quality);
    private static native final EncoderProfiles native_get_camcorder_profiles(
            int cameraId, int quality);
    private static native final boolean native_has_camcorder_profile(
            int cameraId, int quality);
}
