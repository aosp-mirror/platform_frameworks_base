/*
 * Copyright 2015 The Android Open Source Project
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

import android.annotation.NonNull;
import android.hardware.camera2.params.StreamConfigurationMap;

import java.util.List;

/**
 * A constrained high speed capture session for a {@link CameraDevice}, used for capturing high
 * speed images from the {@link CameraDevice} for high speed video recording use case.
 * <p>
 * A CameraHighSpeedCaptureSession is created by providing a set of target output surfaces to
 * {@link CameraDevice#createConstrainedHighSpeedCaptureSession}, Once created, the session is
 * active until a new session is created by the camera device, or the camera device is closed.
 * </p>
 * <p>
 * An active high speed capture session is a specialized capture session that is only targeted at
 * high speed video recording (>=120fps) use case if the camera device supports high speed video
 * capability (i.e., {@link CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES} contains
 * {@link CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO}). It only
 * accepts request lists created via {@link #createHighSpeedRequestList}, and the request list can
 * only be submitted to this session via {@link CameraCaptureSession#captureBurst captureBurst}, or
 * {@link CameraCaptureSession#setRepeatingBurst setRepeatingBurst}. See
 * {@link CameraDevice#createConstrainedHighSpeedCaptureSession} for more details of the
 * limitations.
 * </p>
 * <p>
 * Creating a session is an expensive operation and can take several hundred milliseconds, since it
 * requires configuring the camera device's internal pipelines and allocating memory buffers for
 * sending images to the desired targets. Therefore the setup is done asynchronously, and
 * {@link CameraDevice#createConstrainedHighSpeedCaptureSession} will send the ready-to-use
 * CameraCaptureSession to the provided listener's
 * {@link CameraCaptureSession.StateCallback#onConfigured} callback. If configuration cannot be
 * completed, then the {@link CameraCaptureSession.StateCallback#onConfigureFailed} is called, and
 * the session will not become active.
 * </p>
 * <!--
 * <p>
 * Any capture requests (repeating or non-repeating) submitted before the session is ready will be
 * queued up and will begin capture once the session becomes ready. In case the session cannot be
 * configured and {@link CameraCaptureSession.StateCallback#onConfigureFailed onConfigureFailed} is
 * called, all queued capture requests are discarded.  </p>
 * -->
 * <p>
 * If a new session is created by the camera device, then the previous session is closed, and its
 * associated {@link CameraCaptureSession.StateCallback#onClosed onClosed} callback will be
 * invoked. All of the session methods will throw an IllegalStateException if called once the
 * session is closed.
 * </p>
 * <p>
 * A closed session clears any repeating requests (as if {@link #stopRepeating} had been called),
 * but will still complete all of its in-progress capture requests as normal, before a newly created
 * session takes over and reconfigures the camera device.
 * </p>
 */
public abstract class CameraConstrainedHighSpeedCaptureSession extends CameraCaptureSession {

    /**
     * <p>Create a unmodifiable list of requests that is suitable for constrained high speed capture
     * session streaming.</p>
     *
     * <p>High speed video streaming creates significant performance pressure on the camera device,
     * so to achieve efficient high speed streaming, the camera device may have to aggregate
     * multiple frames together. This means requests must be sent in batched groups, with all
     * requests sharing the same settings. This method takes the list of output target
     * Surfaces (subject to the output Surface requirements specified by the constrained high speed
     * session) and a {@link CaptureRequest request}, and generates a request list that has the same
     * controls for each request. The input {@link CaptureRequest request} must contain the target
     * output Surfaces and target high speed FPS range that is one of the
     * {@link StreamConfigurationMap#getHighSpeedVideoFpsRangesFor} for the Surface size.</p>
     *
     * <p>If both preview and recording Surfaces are specified in the {@code request}, the
     * {@link CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE target FPS range} in the input
     * {@link CaptureRequest request} must be a fixed frame rate FPS range, where the
     * {@link android.util.Range#getLower minimal FPS} ==
     * {@link android.util.Range#getUpper() maximum FPS}. The created request list will contain
     * a interleaved request pattern such that the preview output FPS is at least 30fps, the
     * recording output FPS is {@link android.util.Range#getUpper() maximum FPS} of the requested
     * FPS range. The application can submit this request list directly to an active high speed
     * capture session to achieve high speed video recording. When only preview or recording
     * Surface is specified, this method will return a list of request that have the same controls
     * and output targets for all requests.</p>
     *
     * <p>Submitting a request list created by this method to a normal capture session will result
     * in an {@link IllegalArgumentException} if the high speed
     * {@link CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE FPS range} is not supported by
     * {@link CameraCharacteristics#CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES}.</p>
     *
     * @param request The high speed capture request that will be used to generate the high speed
     *                request list.
     * @return A unmodifiable CaptureRequest list that is suitable for constrained high speed
     *         capture.
     *
     * @throws IllegalArgumentException if the set of output Surfaces in the request do not meet the
     *                                  high speed video capability requirements, or the camera
     *                                  device doesn't support high speed video capability, or the
     *                                  request doesn't meet the high speed video capability
     *                                  requirements, or the request doesn't contain the required
     *                                  controls for high speed capture.
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if the camera device has been closed
     *
     * @see CameraDevice#createConstrainedHighSpeedCaptureSession
     * @see CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE
     * @see android.hardware.camera2.params.StreamConfigurationMap#getHighSpeedVideoSizes
     * @see android.hardware.camera2.params.StreamConfigurationMap#getHighSpeedVideoFpsRangesFor
     * @see CameraCharacteristics#REQUEST_AVAILABLE_CAPABILITIES
     * @see CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO
     */
    @NonNull
    public abstract List<CaptureRequest> createHighSpeedRequestList(
            @NonNull CaptureRequest request) throws CameraAccessException;

}
