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

package android.hardware.photography;

import android.graphics.Rect;

import java.util.List;

/**
 * <p>The properties describing a
 * {@link CameraDevice CameraDevice}.</p>
 *
 * <p>These properties are fixed for a given CameraDevice, and can be queried
 * through the {@link CameraManager CameraManager}
 * interface in addition to through the CameraDevice interface.</p>
 *
 * @see CameraDevice
 * @see CameraManager
 */
public final class CameraProperties extends CameraMetadata {
    /**
     * The model name of the camera. For fixed (non-removable) cameras, this is
     * the manufacturer's name. For removable cameras, this is a string that
     * uniquely identifies the camera model and manufacturer. The
     * {@link #INFO_IDENTIFIER} can be used to distinguish between multiple
     * removable cameras of the same model.
     */
    public static final Key<String> INFO_MODEL =
            new Key<String>("android.info.model");

    /**
     * A unique identifier for this camera. For removable cameras, every
     * camera will have a unique identifier, including two cameras of the
     * same model and manufacturer. For non-removable cameras, the
     * identifier is equal to the the device's id.
     */
    public static final Key<String> INFO_IDENTIFIER =
            new Key<String>("android.info.identifier");

    /**
     * <p>Whether this camera is removable or not.</p>
     *
     * <p>Applications using a removable camera must be prepared for the camera
     * to be disconnected during use. Use the {@link #INFO_IDENTIFIER} field to
     * determine if this camera is a match for a camera device seen earlier.</p>
     */
    public static final Key<Boolean> INFO_REMOVABLE =
            new Key<Boolean>("android.info.isRemovable");

    /**
     * <p>The hardware operational model of this device. One of the
     * INFO_SUPPORTED_HARDWARE_LEVEL_* constants.</p>
     *
     * <p>Limited-capability devices have a number of limitations relative to
     * full-capability cameras. Roughly, they have capabilities comparable to
     * those provided by the deprecated {@link android.hardware.Camera}
     * class.</p>
     *
     * <p>Specifically, limited-mode devices:</p>
     *
     * <ol>
     *
     *  <li>Do not provide per-frame result metadata for repeating
     *  captures. This means that a CaptureListener will not be called for
     *  captures done through {@link CameraDevice#setRepeatingRequest
     *  setRepeatingRequest} or {@link CameraDevice#setRepeatingBurst
     *  setRepeatingBurst}.</li>
     *
     *  <li>Do not support complete result metadata. Only a few fields are
     *  provided, such as the timestamp (TODO).</li>
     *
     *  <li>Do not support manual capture controls. Only the (TODO)
     *  ANDROID_CONTROL_* fields and TODO controls are used, and the various
     *  AE/AF/AWB_OFF control values cannot be used.</li>
     *
     *  <li>Do not support high frame rate captures. To obtain high frame rate,
     *  the {@link CameraDevice#setRepeatingRequest setRepeatingRequest} method
     *  must be used. The {@link CameraDevice#capture capture},
     *  {@link CameraDevice#captureBurst captureBurst}, and
     *  {@link CameraDevice#setRepeatingBurst setRepeatingBurst} methods will
     *  result in slow frame captures.</li>
     *
     * </ol>
     *
     * @see #INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED
     * @see #INFO_SUPPORTED_HARDWARE_LEVEL_FULL
     */
    public static final Key<Integer> INFO_SUPPORTED_HARDWARE_LEVEL =
            new Key<Integer>("android.info.supportedHardwareLevel");

    /**
     * <p>The type reported by limited-capability camera devices.</p>
     *
     * <p>Limited-capability devices have a number of limitations relative to
     * full-capability cameras. Roughly, they have capabilities comparable to
     * those provided by the deprecated {@link android.hardware.Camera}
     * class.</p>
     *
     * <p>Specifically, limited-mode devices:</p>
     *
     * <ol>
     *
     *  <li>Do not provide per-frame result metadata for repeating
     *  captures. This means that a CaptureListener will not be called for
     *  captures done through {@link CameraDevice#setRepeatingRequest
     *  setRepeatingRequest} or {@link CameraDevice#setRepeatingBurst
     *  setRepeatingBurst}.</li>
     *
     *  <li>Do not support complete result metadata. Only a few fields are
     *  provided, such as the timestamp (TODO).</li>
     *
     *  <li>Do not support manual capture controls. Only the (TODO)
     *  ANDROID_CONTROL_* fields and TODO controls are used, and the various
     *  AE/AF/AWB_OFF control values cannot be used.</li>
     *
     *  <li>Do not support high frame rate captures. To obtain high frame rate,
     *  the {@link CameraDevice#setRepeatingRequest setRepeatingRequest} method
     *  must be used. The {@link CameraDevice#capture capture},
     *  {@link CameraDevice#captureBurst captureBurst}, and
     *  {@link CameraDevice#setRepeatingBurst setRepeatingBurst} methods will
     *  result in slow frame captures.</li>
     *
     * </ol>
     *
     * @see #INFO_SUPPORTED_HARDWARE_LEVEL
     */
    public static final int INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED = 0;

    /**
     * <p>The type reported by full-capability camera devices</p>
     *
     * <p>Full-capability devices allow for per-frame control of capture
     * hardware and post-processing parameters at high frame rates. They also
     * provide output data at high resolution in uncompressed formats, in
     * addition to compressed JPEG output.</p>
     *
     * @see #INFO_SUPPORTED_HARDWARE_LEVEL
     */
    public static final int INFO_SUPPORTED_HARDWARE_LEVEL_FULL = 1;

    /**
     * <p>The available output formats from this camera device. When using a
     * {@link android.media.ImageReader} as an output target, the
     * ImageReader must be configured to use one of these formats.</p>
     *
     * <p>The list is a subset of the formats defined in
     * {@link android.graphics.ImageFormat}.</p>
     *
     * <p>The image formats {@link android.graphics.ImageFormat#JPEG},
     * {@link android.graphics.ImageFormat#YUV_420_888} are guaranteed to be
     * supported.</p>
     */
    public static final Key<Integer[]> SCALER_AVAILABLE_FORMATS =
            new Key<Integer[]>("android.scaler.availableFormats");

    /**
     * <p>The available output sizes for JPEG buffers from this camera
     * device. When using a {@link android.media.ImageReader} as an output
     * target, the ImageReader must be configured to use one of these sizes
     * when using format {@link android.graphics.ImageFormat#JPEG}.</p>
     */
    public static final Key<Size[]> SCALER_AVAILABLE_JPEG_SIZES =
            new Key<Size[]>("android.scaler.availableJpegSizes");

    /**
     * <p>The available sizes for output buffers from this camera device, when
     * the buffers are neither of the {@link android.graphics.ImageFormat#JPEG}
     * or of the {@link android.graphics.ImageFormat#RAW_SENSOR} type.</p>
     *
     * <p>When using a {@link android.view.SurfaceView},
     * {@link android.graphics.SurfaceTexture},
     * {@link android.media.MediaRecorder}, {@link android.media.MediaCodec}, or
     * {@link android.renderscript.Allocation} as an output target, that target
     * must be configured to one of these sizes. See
     * {@link CameraDevice#configureOutputs} for details.
     *
     * <p>When using a {@link android.media.ImageReader} as an output
     * target, the ImageReader must be configured to use one of these sizes
     * when using format {@link android.graphics.ImageFormat#YUV_420_888}.</p>
     *
     */
    public static final Key<Size[]> SCALER_AVAILABLE_PROCESSED_SIZES =
            new Key<Size[]>("android.scaler.availableProcessedSizes");

    /**
     * <p>The available sizes for output buffers from this camera device, when
     * the buffers are of the {@link android.graphics.ImageFormat#RAW_SENSOR} type. This type of output may not be
     * supported by the device; check {@link #SCALER_AVAILABLE_FORMATS} to
     * check. In that case, this list will not exist.</p>
     *
     * <p>When using a {@link android.media.ImageReader} as an output
     * target, the ImageReader must be configured to use one of these sizes
     * when using image format {@link android.graphics.ImageFormat#RAW_SENSOR}.</p>
     */
    public static final Key<Size[]> SCALER_AVAILABLE_RAW_SIZES =
            new Key<Size[]>("android.scaler.availableRawSizes");

    /**
     * <p>The coordinates of the sensor's active pixel array, relative to its
     * total pixel array. These are the pixels that are actually used for image
     * capture. The active pixel region may be smaller than the total number of
     * pixels, if some pixels are used for other tasks such as calibrating the
     * sensor's black level. If all pixels available for readout are used for
     * imaging, then this rectangle will be
     * {@code (0,0) - (SENSOR_PIXEL_ARRAY_SIZE.width,
     * SENSOR_PIXEL_ARRAY_SIZE.height)}.</p>
     *
     * <p>If raw sensor capture is supported by this device, the width and
     * height of the active pixel array match up to one of the supported raw
     * capture sizes, and using that size will capture just the active pixel
     * array region.</p>
     *
     * <p>Most other coordinates used by the camera device (for example, for
     * metering and crop region selection, or for reporting detected faces) use
     * a coordinate system based on the active array dimensions, with (0,0)
     * being the top-left corner of the active array.</p>
     */
    public static final Key<Rect> SENSOR_ACTIVE_ARRAY_SIZE =
            new Key<Rect>("android.sensor.activeArraySize");

    /**
     * <p>The size of the sensor's total pixel array available for readout. Some
     * of these pixels may not be used for image capture, in which case
     * {@link #SENSOR_ACTIVE_ARRAY_SIZE} will describe a rectangle smaller than
     * this. If raw sensor capture is supported by this device, this is one of
     * the supported capture sizes.</p>
     */
    public static final Key<Size> SENSOR_PIXEL_ARRAY_SIZE =
            new Key<Size>("android.sensor.activeArraySize");

    // TODO: Many more of these.

}
