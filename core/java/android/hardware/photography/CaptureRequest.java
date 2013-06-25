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

import android.view.Surface;

import java.util.List;


/**
 * <p>All the settings required to capture a single image from the image sensor.</p>
 *
 * <p>Contains the configuration for the capture hardware (sensor, lens, flash),
 * the processing pipeline, the control algorithms, and the output buffers.</p>
 *
 * <p>CaptureRequests can be created by calling
 * {@link CameraDevice#createCaptureRequest}</p>
 *
 * <p>CaptureRequests are given to {@link CameraDevice#capture} or
 * {@link CameraDevice#setRepeatingRequest} to capture images from a camera.</p>
 *
 * <p>Each request can specify a different subset of target Surfaces for the
 * camera to send the captured data to. All the surfaces used in a request must
 * be part of the surface list given to the last call to
 * {@link CameraDevice#configureOutputs}.</p>
 *
 * <p>For example, a request meant for repeating preview might only include the
 * Surface for the preview SurfaceView or SurfaceTexture, while a
 * high-resolution still capture would also include a Surface from a ImageReader
 * configured for high-resolution JPEG images.</p>
 *
 * @see CameraDevice#capture
 * @see CameraDevice#setRepeatingRequest
 * @see CameraDevice#createRequest
 */
public final class CaptureRequest extends CameraMetadata {

    /**
     * The exposure time for this capture, in nanoseconds.
     */
    public static final Key SENSOR_EXPOSURE_TIME =
            new Key<Long>("android.sensor.exposureTime");

    /**
     * The sensor sensitivity (gain) setting for this camera.
     * This is represented as an ISO sensitivity value
     */
    public static final Key SENSOR_SENSITIVITY =
            new Key<Integer>("android.sensor.sensitivity");

    // Many more settings

    CaptureRequest() {
    }

    /**
     * <p>Add a surface to the list of targets for this request</p>
     *
     * <p>The Surface added must be one of the surfaces included in the last
     * call to {@link CameraDevice#configureOutputs}.</p>
     */
    public void addTarget(Surface outputTarget) {
    }

    /**
     * <p>Remove a surface from the list of targets for this request.</p>
     */
    public void removeTarget(Surface outputTarget) {
    }

}