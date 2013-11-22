/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.hardware.camera2;

import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.CameraDevice.CaptureListener;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.Surface;

import java.util.HashSet;
import java.util.Objects;


/**
 * <p>An immutable package of settings and outputs needed to capture a single
 * image from the camera device.</p>
 *
 * <p>Contains the configuration for the capture hardware (sensor, lens, flash),
 * the processing pipeline, the control algorithms, and the output buffers. Also
 * contains the list of target Surfaces to send image data to for this
 * capture.</p>
 *
 * <p>CaptureRequests can be created by using a {@link Builder} instance,
 * obtained by calling {@link CameraDevice#createCaptureRequest}</p>
 *
 * <p>CaptureRequests are given to {@link CameraDevice#capture} or
 * {@link CameraDevice#setRepeatingRequest} to capture images from a camera.</p>
 *
 * <p>Each request can specify a different subset of target Surfaces for the
 * camera to send the captured data to. All the surfaces used in a request must
 * be part of the surface list given to the last call to
 * {@link CameraDevice#configureOutputs}, when the request is submitted to the
 * camera device.</p>
 *
 * <p>For example, a request meant for repeating preview might only include the
 * Surface for the preview SurfaceView or SurfaceTexture, while a
 * high-resolution still capture would also include a Surface from a ImageReader
 * configured for high-resolution JPEG images.</p>
 *
 * @see CameraDevice#capture
 * @see CameraDevice#setRepeatingRequest
 * @see CameraDevice#createCaptureRequest
 */
public final class CaptureRequest extends CameraMetadata implements Parcelable {

    private final HashSet<Surface> mSurfaceSet;
    private final CameraMetadataNative mSettings;

    private Object mUserTag;

    /**
     * Construct empty request.
     *
     * Used by Binder to unparcel this object only.
     */
    private CaptureRequest() {
        mSettings = new CameraMetadataNative();
        mSurfaceSet = new HashSet<Surface>();
    }

    /**
     * Clone from source capture request.
     *
     * Used by the Builder to create an immutable copy.
     */
    @SuppressWarnings("unchecked")
    private CaptureRequest(CaptureRequest source) {
        mSettings = new CameraMetadataNative(source.mSettings);
        mSurfaceSet = (HashSet<Surface>) source.mSurfaceSet.clone();
        mUserTag = source.mUserTag;
    }

    /**
     * Take ownership of passed-in settings.
     *
     * Used by the Builder to create a mutable CaptureRequest.
     */
    private CaptureRequest(CameraMetadataNative settings) {
        mSettings = settings;
        mSurfaceSet = new HashSet<Surface>();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(Key<T> key) {
        return mSettings.get(key);
    }

    /**
     * Retrieve the tag for this request, if any.
     *
     * <p>This tag is not used for anything by the camera device, but can be
     * used by an application to easily identify a CaptureRequest when it is
     * returned by
     * {@link CameraDevice.CaptureListener#onCaptureCompleted CaptureListener.onCaptureCompleted}
     * </p>
     *
     * @return the last tag Object set on this request, or {@code null} if
     *     no tag has been set.
     * @see Builder#setTag
     */
    public Object getTag() {
        return mUserTag;
    }

    /**
     * Determine whether this CaptureRequest is equal to another CaptureRequest.
     *
     * <p>A request is considered equal to another is if it's set of key/values is equal, it's
     * list of output surfaces is equal, and the user tag is equal.</p>
     *
     * @param other Another instance of CaptureRequest.
     *
     * @return True if the requests are the same, false otherwise.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof CaptureRequest
                && equals((CaptureRequest)other);
    }

    private boolean equals(CaptureRequest other) {
        return other != null
                && Objects.equals(mUserTag, other.mUserTag)
                && mSurfaceSet.equals(other.mSurfaceSet)
                && mSettings.equals(other.mSettings);
    }

    @Override
    public int hashCode() {
        return mSettings.hashCode();
    }

    public static final Parcelable.Creator<CaptureRequest> CREATOR =
            new Parcelable.Creator<CaptureRequest>() {
        @Override
        public CaptureRequest createFromParcel(Parcel in) {
            CaptureRequest request = new CaptureRequest();
            request.readFromParcel(in);

            return request;
        }

        @Override
        public CaptureRequest[] newArray(int size) {
            return new CaptureRequest[size];
        }
    };

    /**
     * Expand this object from a Parcel.
     * Hidden since this breaks the immutability of CaptureRequest, but is
     * needed to receive CaptureRequests with aidl.
     *
     * @param in The parcel from which the object should be read
     * @hide
     */
    public void readFromParcel(Parcel in) {
        mSettings.readFromParcel(in);

        mSurfaceSet.clear();

        Parcelable[] parcelableArray = in.readParcelableArray(Surface.class.getClassLoader());

        if (parcelableArray == null) {
            return;
        }

        for (Parcelable p : parcelableArray) {
            Surface s = (Surface) p;
            mSurfaceSet.add(s);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        mSettings.writeToParcel(dest, flags);
        dest.writeParcelableArray(mSurfaceSet.toArray(new Surface[mSurfaceSet.size()]), flags);
    }

    /**
     * A builder for capture requests.
     *
     * <p>To obtain a builder instance, use the
     * {@link CameraDevice#createCaptureRequest} method, which initializes the
     * request fields to one of the templates defined in {@link CameraDevice}.
     *
     * @see CameraDevice#createCaptureRequest
     * @see #TEMPLATE_PREVIEW
     * @see #TEMPLATE_RECORD
     * @see #TEMPLATE_STILL_CAPTURE
     * @see #TEMPLATE_VIDEO_SNAPSHOT
     * @see #TEMPLATE_MANUAL
     */
    public final static class Builder {

        private final CaptureRequest mRequest;

        /**
         * Initialize the builder using the template; the request takes
         * ownership of the template.
         *
         * @hide
         */
        public Builder(CameraMetadataNative template) {
            mRequest = new CaptureRequest(template);
        }

        /**
         * <p>Add a surface to the list of targets for this request</p>
         *
         * <p>The Surface added must be one of the surfaces included in the most
         * recent call to {@link CameraDevice#configureOutputs}, when the
         * request is given to the camera device.</p>
         *
         * <p>Adding a target more than once has no effect.</p>
         *
         * @param outputTarget Surface to use as an output target for this request
         */
        public void addTarget(Surface outputTarget) {
            mRequest.mSurfaceSet.add(outputTarget);
        }

        /**
         * <p>Remove a surface from the list of targets for this request.</p>
         *
         * <p>Removing a target that is not currently added has no effect.</p>
         *
         * @param outputTarget Surface to use as an output target for this request
         */
        public void removeTarget(Surface outputTarget) {
            mRequest.mSurfaceSet.remove(outputTarget);
        }

        /**
         * Set a capture request field to a value. The field definitions can be
         * found in {@link CaptureRequest}.
         *
         * @param key The metadata field to write.
         * @param value The value to set the field to, which must be of a matching
         * type to the key.
         */
        public <T> void set(Key<T> key, T value) {
            mRequest.mSettings.set(key, value);
        }

        /**
         * Get a capture request field value. The field definitions can be
         * found in {@link CaptureRequest}.
         *
         * @throws IllegalArgumentException if the key was not valid
         *
         * @param key The metadata field to read.
         * @return The value of that key, or {@code null} if the field is not set.
         */
        public <T> T get(Key<T> key) {
            return mRequest.mSettings.get(key);
        }

        /**
         * Set a tag for this request.
         *
         * <p>This tag is not used for anything by the camera device, but can be
         * used by an application to easily identify a CaptureRequest when it is
         * returned by
         * {@link CameraDevice.CaptureListener#onCaptureCompleted CaptureListener.onCaptureCompleted}
         *
         * @param tag an arbitrary Object to store with this request
         * @see CaptureRequest#getTag
         */
        public void setTag(Object tag) {
            mRequest.mUserTag = tag;
        }

        /**
         * Build a request using the current target Surfaces and settings.
         *
         * @return A new capture request instance, ready for submission to the
         * camera device.
         */
        public CaptureRequest build() {
            return new CaptureRequest(mRequest);
        }


        /**
         * @hide
         */
        public boolean isEmpty() {
            return mRequest.mSettings.isEmpty();
        }

    }

    /*@O~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~
     * The key entries below this point are generated from metadata
     * definitions in /system/media/camera/docs. Do not modify by hand or
     * modify the comment blocks at the start or end.
     *~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~*/

    /**
     * <p>
     * When android.control.awbMode is not OFF, TRANSFORM_MATRIX
     * should be ignored.
     * </p>
     * @see #COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
     * @see #COLOR_CORRECTION_MODE_FAST
     * @see #COLOR_CORRECTION_MODE_HIGH_QUALITY
     */
    public static final Key<Integer> COLOR_CORRECTION_MODE =
            new Key<Integer>("android.colorCorrection.mode", int.class);

    /**
     * <p>
     * A color transform matrix to use to transform
     * from sensor RGB color space to output linear sRGB color space
     * </p>
     * <p>
     * This matrix is either set by HAL when the request
     * android.colorCorrection.mode is not TRANSFORM_MATRIX, or
     * directly by the application in the request when the
     * android.colorCorrection.mode is TRANSFORM_MATRIX.
     * </p><p>
     * In the latter case, the HAL may round the matrix to account
     * for precision issues; the final rounded matrix should be
     * reported back in this matrix result metadata.
     * </p>
     */
    public static final Key<Rational[]> COLOR_CORRECTION_TRANSFORM =
            new Key<Rational[]>("android.colorCorrection.transform", Rational[].class);

    /**
     * <p>
     * Gains applying to Bayer color channels for
     * white-balance
     * </p>
     * <p>
     * The 4-channel white-balance gains are defined in
     * the order of [R G_even G_odd B], where G_even is the gain
     * for green pixels on even rows of the output, and G_odd
     * is the gain for greenpixels on the odd rows. if a HAL
     * does not support a separate gain for even/odd green channels,
     * it should use the G_even value,and write G_odd equal to
     * G_even in the output result metadata.
     * </p><p>
     * This array is either set by HAL when the request
     * android.colorCorrection.mode is not TRANSFORM_MATRIX, or
     * directly by the application in the request when the
     * android.colorCorrection.mode is TRANSFORM_MATRIX.
     * </p><p>
     * The ouput should be the gains actually applied by the HAL to
     * the current frame.
     * </p>
     */
    public static final Key<float[]> COLOR_CORRECTION_GAINS =
            new Key<float[]>("android.colorCorrection.gains", float[].class);

    /**
     * <p>
     * Enum for controlling
     * antibanding
     * </p>
     * @see #CONTROL_AE_ANTIBANDING_MODE_OFF
     * @see #CONTROL_AE_ANTIBANDING_MODE_50HZ
     * @see #CONTROL_AE_ANTIBANDING_MODE_60HZ
     * @see #CONTROL_AE_ANTIBANDING_MODE_AUTO
     */
    public static final Key<Integer> CONTROL_AE_ANTIBANDING_MODE =
            new Key<Integer>("android.control.aeAntibandingMode", int.class);

    /**
     * <p>
     * Adjustment to AE target image
     * brightness
     * </p>
     * <p>
     * For example, if EV step is 0.333, '6' will mean an
     * exposure compensation of +2 EV; -3 will mean an exposure
     * compensation of -1
     * </p>
     */
    public static final Key<Integer> CONTROL_AE_EXPOSURE_COMPENSATION =
            new Key<Integer>("android.control.aeExposureCompensation", int.class);

    /**
     * <p>
     * Whether AE is currently locked to its latest
     * calculated values
     * </p>
     * <p>
     * Note that even when AE is locked, the flash may be
     * fired if the AE mode is ON_AUTO_FLASH / ON_ALWAYS_FLASH /
     * ON_AUTO_FLASH_REDEYE.
     * </p>
     */
    public static final Key<Boolean> CONTROL_AE_LOCK =
            new Key<Boolean>("android.control.aeLock", boolean.class);

    /**
     * <p>
     * Whether AE is currently updating the sensor
     * exposure and sensitivity fields
     * </p>
     * <p>
     * Only effective if android.control.mode =
     * AUTO
     * </p>
     * @see #CONTROL_AE_MODE_OFF
     * @see #CONTROL_AE_MODE_ON
     * @see #CONTROL_AE_MODE_ON_AUTO_FLASH
     * @see #CONTROL_AE_MODE_ON_ALWAYS_FLASH
     * @see #CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE
     */
    public static final Key<Integer> CONTROL_AE_MODE =
            new Key<Integer>("android.control.aeMode", int.class);

    /**
     * <p>
     * List of areas to use for
     * metering
     * </p>
     * <p>
     * Each area is a rectangle plus weight: xmin, ymin,
     * xmax, ymax, weight. The rectangle is defined inclusive of the
     * specified coordinates.
     * </p><p>
     * The coordinate system is based on the active pixel array,
     * with (0,0) being the top-left pixel in the active pixel array, and
     * (android.sensor.info.activeArraySize.width - 1,
     * android.sensor.info.activeArraySize.height - 1) being the
     * bottom-right pixel in the active pixel array. The weight
     * should be nonnegative.
     * </p><p>
     * If all regions have 0 weight, then no specific metering area
     * needs to be used by the HAL. If the metering region is
     * outside the current android.scaler.cropRegion, the HAL
     * should ignore the sections outside the region and output the
     * used sections in the frame metadata
     * </p>
     */
    public static final Key<int[]> CONTROL_AE_REGIONS =
            new Key<int[]>("android.control.aeRegions", int[].class);

    /**
     * <p>
     * Range over which fps can be adjusted to
     * maintain exposure
     * </p>
     * <p>
     * Only constrains AE algorithm, not manual control
     * of android.sensor.exposureTime
     * </p>
     */
    public static final Key<int[]> CONTROL_AE_TARGET_FPS_RANGE =
            new Key<int[]>("android.control.aeTargetFpsRange", int[].class);

    /**
     * <p>
     * Whether the HAL must trigger precapture
     * metering.
     * </p>
     * <p>
     * This entry is normally set to IDLE, or is not
     * included at all in the request settings. When included and
     * set to START, the HAL must trigger the autoexposure
     * precapture metering sequence.
     * </p><p>
     * The effect of AE precapture trigger depends on the current
     * AE mode and state; see the camera HAL device v3 header for
     * details.
     * </p>
     * @see #CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
     * @see #CONTROL_AE_PRECAPTURE_TRIGGER_START
     */
    public static final Key<Integer> CONTROL_AE_PRECAPTURE_TRIGGER =
            new Key<Integer>("android.control.aePrecaptureTrigger", int.class);

    /**
     * <p>
     * Whether AF is currently enabled, and what
     * mode it is set to
     * </p>
     * @see #CONTROL_AF_MODE_OFF
     * @see #CONTROL_AF_MODE_AUTO
     * @see #CONTROL_AF_MODE_MACRO
     * @see #CONTROL_AF_MODE_CONTINUOUS_VIDEO
     * @see #CONTROL_AF_MODE_CONTINUOUS_PICTURE
     * @see #CONTROL_AF_MODE_EDOF
     */
    public static final Key<Integer> CONTROL_AF_MODE =
            new Key<Integer>("android.control.afMode", int.class);

    /**
     * <p>
     * List of areas to use for focus
     * estimation
     * </p>
     * <p>
     * Each area is a rectangle plus weight: xmin, ymin,
     * xmax, ymax, weight. The rectangle is defined inclusive of the
     * specified coordinates.
     * </p><p>
     * The coordinate system is based on the active pixel array,
     * with (0,0) being the top-left pixel in the active pixel array, and
     * (android.sensor.info.activeArraySize.width - 1,
     * android.sensor.info.activeArraySize.height - 1) being the
     * bottom-right pixel in the active pixel array. The weight
     * should be nonnegative.
     * </p><p>
     * If all regions have 0 weight, then no specific focus area
     * needs to be used by the HAL. If the focusing region is
     * outside the current android.scaler.cropRegion, the HAL
     * should ignore the sections outside the region and output the
     * used sections in the frame metadata
     * </p>
     */
    public static final Key<int[]> CONTROL_AF_REGIONS =
            new Key<int[]>("android.control.afRegions", int[].class);

    /**
     * <p>
     * Whether the HAL must trigger autofocus.
     * </p>
     * <p>
     * This entry is normally set to IDLE, or is not
     * included at all in the request settings.
     * </p><p>
     * When included and set to START, the HAL must trigger the
     * autofocus algorithm. The effect of AF trigger depends on the
     * current AF mode and state; see the camera HAL device v3
     * header for details. When set to CANCEL, the HAL must cancel
     * any active trigger, and return to initial AF state.
     * </p>
     * @see #CONTROL_AF_TRIGGER_IDLE
     * @see #CONTROL_AF_TRIGGER_START
     * @see #CONTROL_AF_TRIGGER_CANCEL
     */
    public static final Key<Integer> CONTROL_AF_TRIGGER =
            new Key<Integer>("android.control.afTrigger", int.class);

    /**
     * <p>
     * Whether AWB is currently locked to its
     * latest calculated values
     * </p>
     * <p>
     * Note that AWB lock is only meaningful for AUTO
     * mode; in other modes, AWB is already fixed to a specific
     * setting
     * </p>
     */
    public static final Key<Boolean> CONTROL_AWB_LOCK =
            new Key<Boolean>("android.control.awbLock", boolean.class);

    /**
     * <p>
     * Whether AWB is currently setting the color
     * transform fields, and what its illumination target
     * is
     * </p>
     * <p>
     * [BC - AWB lock,AWB modes]
     * </p>
     * @see #CONTROL_AWB_MODE_OFF
     * @see #CONTROL_AWB_MODE_AUTO
     * @see #CONTROL_AWB_MODE_INCANDESCENT
     * @see #CONTROL_AWB_MODE_FLUORESCENT
     * @see #CONTROL_AWB_MODE_WARM_FLUORESCENT
     * @see #CONTROL_AWB_MODE_DAYLIGHT
     * @see #CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
     * @see #CONTROL_AWB_MODE_TWILIGHT
     * @see #CONTROL_AWB_MODE_SHADE
     */
    public static final Key<Integer> CONTROL_AWB_MODE =
            new Key<Integer>("android.control.awbMode", int.class);

    /**
     * <p>
     * List of areas to use for illuminant
     * estimation
     * </p>
     * <p>
     * Only used in AUTO mode.
     * </p><p>
     * Each area is a rectangle plus weight: xmin, ymin,
     * xmax, ymax, weight. The rectangle is defined inclusive of the
     * specified coordinates.
     * </p><p>
     * The coordinate system is based on the active pixel array,
     * with (0,0) being the top-left pixel in the active pixel array, and
     * (android.sensor.info.activeArraySize.width - 1,
     * android.sensor.info.activeArraySize.height - 1) being the
     * bottom-right pixel in the active pixel array. The weight
     * should be nonnegative.
     * </p><p>
     * If all regions have 0 weight, then no specific metering area
     * needs to be used by the HAL. If the metering region is
     * outside the current android.scaler.cropRegion, the HAL
     * should ignore the sections outside the region and output the
     * used sections in the frame metadata
     * </p>
     */
    public static final Key<int[]> CONTROL_AWB_REGIONS =
            new Key<int[]>("android.control.awbRegions", int[].class);

    /**
     * <p>
     * Information to 3A routines about the purpose
     * of this capture, to help decide optimal 3A
     * strategy
     * </p>
     * <p>
     * Only used if android.control.mode != OFF.
     * </p>
     * @see #CONTROL_CAPTURE_INTENT_CUSTOM
     * @see #CONTROL_CAPTURE_INTENT_PREVIEW
     * @see #CONTROL_CAPTURE_INTENT_STILL_CAPTURE
     * @see #CONTROL_CAPTURE_INTENT_VIDEO_RECORD
     * @see #CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT
     * @see #CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG
     */
    public static final Key<Integer> CONTROL_CAPTURE_INTENT =
            new Key<Integer>("android.control.captureIntent", int.class);

    /**
     * <p>
     * Whether any special color effect is in use.
     * Only used if android.control.mode != OFF
     * </p>
     * @see #CONTROL_EFFECT_MODE_OFF
     * @see #CONTROL_EFFECT_MODE_MONO
     * @see #CONTROL_EFFECT_MODE_NEGATIVE
     * @see #CONTROL_EFFECT_MODE_SOLARIZE
     * @see #CONTROL_EFFECT_MODE_SEPIA
     * @see #CONTROL_EFFECT_MODE_POSTERIZE
     * @see #CONTROL_EFFECT_MODE_WHITEBOARD
     * @see #CONTROL_EFFECT_MODE_BLACKBOARD
     * @see #CONTROL_EFFECT_MODE_AQUA
     */
    public static final Key<Integer> CONTROL_EFFECT_MODE =
            new Key<Integer>("android.control.effectMode", int.class);

    /**
     * <p>
     * Overall mode of 3A control
     * routines
     * </p>
     * @see #CONTROL_MODE_OFF
     * @see #CONTROL_MODE_AUTO
     * @see #CONTROL_MODE_USE_SCENE_MODE
     */
    public static final Key<Integer> CONTROL_MODE =
            new Key<Integer>("android.control.mode", int.class);

    /**
     * <p>
     * Which scene mode is active when
     * android.control.mode = SCENE_MODE
     * </p>
     * @see #CONTROL_SCENE_MODE_UNSUPPORTED
     * @see #CONTROL_SCENE_MODE_FACE_PRIORITY
     * @see #CONTROL_SCENE_MODE_ACTION
     * @see #CONTROL_SCENE_MODE_PORTRAIT
     * @see #CONTROL_SCENE_MODE_LANDSCAPE
     * @see #CONTROL_SCENE_MODE_NIGHT
     * @see #CONTROL_SCENE_MODE_NIGHT_PORTRAIT
     * @see #CONTROL_SCENE_MODE_THEATRE
     * @see #CONTROL_SCENE_MODE_BEACH
     * @see #CONTROL_SCENE_MODE_SNOW
     * @see #CONTROL_SCENE_MODE_SUNSET
     * @see #CONTROL_SCENE_MODE_STEADYPHOTO
     * @see #CONTROL_SCENE_MODE_FIREWORKS
     * @see #CONTROL_SCENE_MODE_SPORTS
     * @see #CONTROL_SCENE_MODE_PARTY
     * @see #CONTROL_SCENE_MODE_CANDLELIGHT
     * @see #CONTROL_SCENE_MODE_BARCODE
     */
    public static final Key<Integer> CONTROL_SCENE_MODE =
            new Key<Integer>("android.control.sceneMode", int.class);

    /**
     * <p>
     * Whether video stabilization is
     * active
     * </p>
     * <p>
     * If enabled, video stabilization can modify the
     * android.scaler.cropRegion to keep the video stream
     * stabilized
     * </p>
     */
    public static final Key<Boolean> CONTROL_VIDEO_STABILIZATION_MODE =
            new Key<Boolean>("android.control.videoStabilizationMode", boolean.class);

    /**
     * <p>
     * Operation mode for edge
     * enhancement
     * </p>
     * @see #EDGE_MODE_OFF
     * @see #EDGE_MODE_FAST
     * @see #EDGE_MODE_HIGH_QUALITY
     */
    public static final Key<Integer> EDGE_MODE =
            new Key<Integer>("android.edge.mode", int.class);

    /**
     * <p>
     * Select flash operation mode
     * </p>
     * @see #FLASH_MODE_OFF
     * @see #FLASH_MODE_SINGLE
     * @see #FLASH_MODE_TORCH
     */
    public static final Key<Integer> FLASH_MODE =
            new Key<Integer>("android.flash.mode", int.class);

    /**
     * <p>
     * GPS coordinates to include in output JPEG
     * EXIF
     * </p>
     */
    public static final Key<double[]> JPEG_GPS_COORDINATES =
            new Key<double[]>("android.jpeg.gpsCoordinates", double[].class);

    /**
     * <p>
     * 32 characters describing GPS algorithm to
     * include in EXIF
     * </p>
     */
    public static final Key<String> JPEG_GPS_PROCESSING_METHOD =
            new Key<String>("android.jpeg.gpsProcessingMethod", String.class);

    /**
     * <p>
     * Time GPS fix was made to include in
     * EXIF
     * </p>
     */
    public static final Key<Long> JPEG_GPS_TIMESTAMP =
            new Key<Long>("android.jpeg.gpsTimestamp", long.class);

    /**
     * <p>
     * Orientation of JPEG image to
     * write
     * </p>
     */
    public static final Key<Integer> JPEG_ORIENTATION =
            new Key<Integer>("android.jpeg.orientation", int.class);

    /**
     * <p>
     * Compression quality of the final JPEG
     * image
     * </p>
     * <p>
     * 85-95 is typical usage range
     * </p>
     */
    public static final Key<Byte> JPEG_QUALITY =
            new Key<Byte>("android.jpeg.quality", byte.class);

    /**
     * <p>
     * Compression quality of JPEG
     * thumbnail
     * </p>
     */
    public static final Key<Byte> JPEG_THUMBNAIL_QUALITY =
            new Key<Byte>("android.jpeg.thumbnailQuality", byte.class);

    /**
     * <p>
     * Resolution of embedded JPEG
     * thumbnail
     * </p>
     */
    public static final Key<android.hardware.camera2.Size> JPEG_THUMBNAIL_SIZE =
            new Key<android.hardware.camera2.Size>("android.jpeg.thumbnailSize", android.hardware.camera2.Size.class);

    /**
     * <p>
     * Size of the lens aperture
     * </p>
     * <p>
     * Will not be supported on most devices. Can only
     * pick from supported list
     * </p>
     */
    public static final Key<Float> LENS_APERTURE =
            new Key<Float>("android.lens.aperture", float.class);

    /**
     * <p>
     * State of lens neutral density
     * filter(s)
     * </p>
     * <p>
     * Will not be supported on most devices. Can only
     * pick from supported list
     * </p>
     */
    public static final Key<Float> LENS_FILTER_DENSITY =
            new Key<Float>("android.lens.filterDensity", float.class);

    /**
     * <p>
     * Lens optical zoom setting
     * </p>
     * <p>
     * Will not be supported on most devices.
     * </p>
     */
    public static final Key<Float> LENS_FOCAL_LENGTH =
            new Key<Float>("android.lens.focalLength", float.class);

    /**
     * <p>
     * Distance to plane of sharpest focus,
     * measured from frontmost surface of the lens
     * </p>
     * <p>
     * 0 = infinity focus. Used value should be clamped
     * to (0,minimum focus distance)
     * </p>
     */
    public static final Key<Float> LENS_FOCUS_DISTANCE =
            new Key<Float>("android.lens.focusDistance", float.class);

    /**
     * <p>
     * Whether optical image stabilization is
     * enabled.
     * </p>
     * <p>
     * Will not be supported on most devices.
     * </p>
     * @see #LENS_OPTICAL_STABILIZATION_MODE_OFF
     * @see #LENS_OPTICAL_STABILIZATION_MODE_ON
     */
    public static final Key<Integer> LENS_OPTICAL_STABILIZATION_MODE =
            new Key<Integer>("android.lens.opticalStabilizationMode", int.class);

    /**
     * <p>
     * Mode of operation for the noise reduction
     * algorithm
     * </p>
     * @see #NOISE_REDUCTION_MODE_OFF
     * @see #NOISE_REDUCTION_MODE_FAST
     * @see #NOISE_REDUCTION_MODE_HIGH_QUALITY
     */
    public static final Key<Integer> NOISE_REDUCTION_MODE =
            new Key<Integer>("android.noiseReduction.mode", int.class);

    /**
     * <p>
     * An application-specified ID for the current
     * request. Must be maintained unchanged in output
     * frame
     * </p>
     *
     * @hide
     */
    public static final Key<Integer> REQUEST_ID =
            new Key<Integer>("android.request.id", int.class);

    /**
     * <p>
     * (x, y, width, height).
     * </p><p>
     * A rectangle with the top-level corner of (x,y) and size
     * (width, height). The region of the sensor that is used for
     * output. Each stream must use this rectangle to produce its
     * output, cropping to a smaller region if necessary to
     * maintain the stream's aspect ratio.
     * </p><p>
     * HAL2.x uses only (x, y, width)
     * </p>
     * <p>
     * Any additional per-stream cropping must be done to
     * maximize the final pixel area of the stream.
     * </p><p>
     * For example, if the crop region is set to a 4:3 aspect
     * ratio, then 4:3 streams should use the exact crop
     * region. 16:9 streams should further crop vertically
     * (letterbox).
     * </p><p>
     * Conversely, if the crop region is set to a 16:9, then 4:3
     * outputs should crop horizontally (pillarbox), and 16:9
     * streams should match exactly. These additional crops must
     * be centered within the crop region.
     * </p><p>
     * The output streams must maintain square pixels at all
     * times, no matter what the relative aspect ratios of the
     * crop region and the stream are.  Negative values for
     * corner are allowed for raw output if full pixel array is
     * larger than active pixel array. Width and height may be
     * rounded to nearest larger supportable width, especially
     * for raw output, where only a few fixed scales may be
     * possible. The width and height of the crop region cannot
     * be set to be smaller than floor( activeArraySize.width /
     * android.scaler.maxDigitalZoom ) and floor(
     * activeArraySize.height / android.scaler.maxDigitalZoom),
     * respectively.
     * </p>
     */
    public static final Key<android.graphics.Rect> SCALER_CROP_REGION =
            new Key<android.graphics.Rect>("android.scaler.cropRegion", android.graphics.Rect.class);

    /**
     * <p>
     * Duration each pixel is exposed to
     * light.
     * </p><p>
     * If the sensor can't expose this exact duration, it should shorten the
     * duration exposed to the nearest possible value (rather than expose longer).
     * </p>
     * <p>
     * 1/10000 - 30 sec range. No bulb mode
     * </p>
     */
    public static final Key<Long> SENSOR_EXPOSURE_TIME =
            new Key<Long>("android.sensor.exposureTime", long.class);

    /**
     * <p>
     * Duration from start of frame exposure to
     * start of next frame exposure
     * </p>
     * <p>
     * Exposure time has priority, so duration is set to
     * max(duration, exposure time + overhead)
     * </p>
     */
    public static final Key<Long> SENSOR_FRAME_DURATION =
            new Key<Long>("android.sensor.frameDuration", long.class);

    /**
     * <p>
     * Gain applied to image data. Must be
     * implemented through analog gain only if set to values
     * below 'maximum analog sensitivity'.
     * </p><p>
     * If the sensor can't apply this exact gain, it should lessen the
     * gain to the nearest possible value (rather than gain more).
     * </p>
     * <p>
     * ISO 12232:2006 REI method
     * </p>
     */
    public static final Key<Integer> SENSOR_SENSITIVITY =
            new Key<Integer>("android.sensor.sensitivity", int.class);

    /**
     * <p>
     * State of the face detector
     * unit
     * </p>
     * <p>
     * Whether face detection is enabled, and whether it
     * should output just the basic fields or the full set of
     * fields. Value must be one of the
     * android.statistics.info.availableFaceDetectModes.
     * </p>
     * @see #STATISTICS_FACE_DETECT_MODE_OFF
     * @see #STATISTICS_FACE_DETECT_MODE_SIMPLE
     * @see #STATISTICS_FACE_DETECT_MODE_FULL
     */
    public static final Key<Integer> STATISTICS_FACE_DETECT_MODE =
            new Key<Integer>("android.statistics.faceDetectMode", int.class);

    /**
     * <p>
     * Whether the HAL needs to output the lens
     * shading map in output result metadata
     * </p>
     * <p>
     * When set to ON,
     * android.statistics.lensShadingMap must be provided in
     * the output result metdata.
     * </p>
     * @see #STATISTICS_LENS_SHADING_MAP_MODE_OFF
     * @see #STATISTICS_LENS_SHADING_MAP_MODE_ON
     */
    public static final Key<Integer> STATISTICS_LENS_SHADING_MAP_MODE =
            new Key<Integer>("android.statistics.lensShadingMapMode", int.class);

    /**
     * <p>
     * Table mapping blue input values to output
     * values
     * </p>
     * <p>
     * Tonemapping / contrast / gamma curve for the blue
     * channel, to use when android.tonemap.mode is CONTRAST_CURVE.
     * </p><p>
     * See android.tonemap.curveRed for more details.
     * </p>
     */
    public static final Key<float[]> TONEMAP_CURVE_BLUE =
            new Key<float[]>("android.tonemap.curveBlue", float[].class);

    /**
     * <p>
     * Table mapping green input values to output
     * values
     * </p>
     * <p>
     * Tonemapping / contrast / gamma curve for the green
     * channel, to use when android.tonemap.mode is CONTRAST_CURVE.
     * </p><p>
     * See android.tonemap.curveRed for more details.
     * </p>
     */
    public static final Key<float[]> TONEMAP_CURVE_GREEN =
            new Key<float[]>("android.tonemap.curveGreen", float[].class);

    /**
     * <p>
     * Table mapping red input values to output
     * values
     * </p>
     * <p>
     * Tonemapping / contrast / gamma curve for the red
     * channel, to use when android.tonemap.mode is CONTRAST_CURVE.
     * </p><p>
     * Since the input and output ranges may vary depending on
     * the camera pipeline, the input and output pixel values
     * are represented by normalized floating-point values
     * between 0 and 1, with 0 == black and 1 == white.
     * </p><p>
     * The curve should be linearly interpolated between the
     * defined points. The points will be listed in increasing
     * order of P_IN. For example, if the array is: [0.0, 0.0,
     * 0.3, 0.5, 1.0, 1.0], then the input->output mapping
     * for a few sample points would be: 0 -> 0, 0.15 ->
     * 0.25, 0.3 -> 0.5, 0.5 -> 0.64
     * </p>
     */
    public static final Key<float[]> TONEMAP_CURVE_RED =
            new Key<float[]>("android.tonemap.curveRed", float[].class);

    /**
     * @see #TONEMAP_MODE_CONTRAST_CURVE
     * @see #TONEMAP_MODE_FAST
     * @see #TONEMAP_MODE_HIGH_QUALITY
     */
    public static final Key<Integer> TONEMAP_MODE =
            new Key<Integer>("android.tonemap.mode", int.class);

    /**
     * <p>
     * This LED is nominally used to indicate to the user
     * that the camera is powered on and may be streaming images back to the
     * Application Processor. In certain rare circumstances, the OS may
     * disable this when video is processed locally and not transmitted to
     * any untrusted applications.
     * </p><p>
     * In particular, the LED *must* always be on when the data could be
     * transmitted off the device. The LED *should* always be on whenever
     * data is stored locally on the device.
     * </p><p>
     * The LED *may* be off if a trusted application is using the data that
     * doesn't violate the above rules.
     * </p>
     *
     * @hide
     */
    public static final Key<Boolean> LED_TRANSMIT =
            new Key<Boolean>("android.led.transmit", boolean.class);

    /**
     * <p>
     * Whether black-level compensation is locked
     * to its current values, or is free to vary
     * </p>
     * <p>
     * When set to ON, the values used for black-level
     * compensation must not change until the lock is set to
     * OFF
     * </p><p>
     * Since changes to certain capture parameters (such as
     * exposure time) may require resetting of black level
     * compensation, the HAL must report whether setting the
     * black level lock was successful in the output result
     * metadata.
     * </p><p>
     * The black level locking must happen at the sensor, and not at the ISP.
     * If for some reason black level locking is no longer legal (for example,
     * the analog gain has changed, which forces black levels to be
     * recalculated), then the HAL is free to override this request (and it
     * must report 'OFF' when this does happen) until the next time locking
     * is legal again.
     * </p>
     */
    public static final Key<Boolean> BLACK_LEVEL_LOCK =
            new Key<Boolean>("android.blackLevel.lock", boolean.class);

    /*~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~
     * End generated code
     *~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~O@*/
}
