/*
 * Copyright (C) 2012 The Android Open Source Project
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

/**
 * <p>The results of a single image capture from the image sensor.</p>
 *
 * <p>Contains the final configuration for the capture hardware (sensor, lens,
 * flash), the processing pipeline, the control algorithms, and the output
 * buffers.</p>
 *
 * <p>CaptureResults are produced by a {@link CameraDevice} after processing a
 * {@link CaptureRequest}. All properties listed for capture requests can also
 * be queried on the capture result, to determine the final values used for
 * capture. The result also includes additional metadata about the state of the
 * camera device during the capture.</p>
 *
 */
public final class CaptureResult extends CameraMetadata {

    private final CameraMetadataNative mResults;
    private final CaptureRequest mRequest;
    private final int mSequenceId;

    /**
     * Takes ownership of the passed-in properties object
     * @hide
     */
    public CaptureResult(CameraMetadataNative results, CaptureRequest parent, int sequenceId) {
        if (results == null) {
            throw new IllegalArgumentException("results was null");
        }

        if (parent == null) {
            throw new IllegalArgumentException("parent was null");
        }

        mResults = results;
        mRequest = parent;
        mSequenceId = sequenceId;
    }

    @Override
    public <T> T get(Key<T> key) {
        return mResults.get(key);
    }

    /**
     * Get the request associated with this result.
     *
     * <p>Whenever a request is successfully captured, with
     * {@link CameraDevice.CaptureListener#onCaptureCompleted},
     * the {@code result}'s {@code getRequest()} will return that {@code request}.
     * </p>
     *
     * <p>In particular,
     * <code><pre>cameraDevice.capture(someRequest, new CaptureListener() {
     *     {@literal @}Override
     *     void onCaptureCompleted(CaptureRequest myRequest, CaptureResult myResult) {
     *         assert(myResult.getRequest.equals(myRequest) == true);
     *     }
     * };
     * </code></pre>
     * </p>
     *
     * @return The request associated with this result. Never {@code null}.
     */
    public CaptureRequest getRequest() {
        return mRequest;
    }

    /**
     * Get the frame number associated with this result.
     *
     * <p>Whenever a request has been processed, regardless of failure or success,
     * it gets a unique frame number assigned to its future result/failure.</p>
     *
     * <p>This value monotonically increments, starting with 0,
     * for every new result or failure; and the scope is the lifetime of the
     * {@link CameraDevice}.</p>
     *
     * @return int frame number
     */
    public int getFrameNumber() {
        return get(REQUEST_FRAME_COUNT);
    }

    /**
     * The sequence ID for this failure that was returned by the
     * {@link CameraDevice#capture} family of functions.
     *
     * <p>The sequence ID is a unique monotonically increasing value starting from 0,
     * incremented every time a new group of requests is submitted to the CameraDevice.</p>
     *
     * @return int The ID for the sequence of requests that this capture result is a part of
     *
     * @see CameraDevice.CaptureListener#onCaptureSequenceCompleted
     */
    public int getSequenceId() {
        return mSequenceId;
    }

    /*@O~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~
     * The key entries below this point are generated from metadata
     * definitions in /system/media/camera/docs. Do not modify by hand or
     * modify the comment blocks at the start or end.
     *~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~*/


    /**
     * <p>A color transform matrix to use to transform
     * from sensor RGB color space to output linear sRGB color space</p>
     * <p>This matrix is either set by the camera device when the request
     * {@link CaptureRequest#COLOR_CORRECTION_MODE android.colorCorrection.mode} is not TRANSFORM_MATRIX, or
     * directly by the application in the request when the
     * {@link CaptureRequest#COLOR_CORRECTION_MODE android.colorCorrection.mode} is TRANSFORM_MATRIX.</p>
     * <p>In the latter case, the camera device may round the matrix to account
     * for precision issues; the final rounded matrix should be reported back
     * in this matrix result metadata. The transform should keep the magnitude
     * of the output color values within <code>[0, 1.0]</code> (assuming input color
     * values is within the normalized range <code>[0, 1.0]</code>), or clipping may occur.</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_MODE
     */
    public static final Key<Rational[]> COLOR_CORRECTION_TRANSFORM =
            new Key<Rational[]>("android.colorCorrection.transform", Rational[].class);

    /**
     * <p>Gains applying to Bayer raw color channels for
     * white-balance.</p>
     * <p>The 4-channel white-balance gains are defined in
     * the order of <code>[R G_even G_odd B]</code>, where <code>G_even</code> is the gain
     * for green pixels on even rows of the output, and <code>G_odd</code>
     * is the gain for green pixels on the odd rows. if a HAL
     * does not support a separate gain for even/odd green channels,
     * it should use the <code>G_even</code> value, and write <code>G_odd</code> equal to
     * <code>G_even</code> in the output result metadata.</p>
     * <p>This array is either set by the camera device when the request
     * {@link CaptureRequest#COLOR_CORRECTION_MODE android.colorCorrection.mode} is not TRANSFORM_MATRIX, or
     * directly by the application in the request when the
     * {@link CaptureRequest#COLOR_CORRECTION_MODE android.colorCorrection.mode} is TRANSFORM_MATRIX.</p>
     * <p>The output should be the gains actually applied by the camera device to
     * the current frame.</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_MODE
     */
    public static final Key<float[]> COLOR_CORRECTION_GAINS =
            new Key<float[]>("android.colorCorrection.gains", float[].class);

    /**
     * <p>The ID sent with the latest
     * CAMERA2_TRIGGER_PRECAPTURE_METERING call</p>
     * <p>Must be 0 if no
     * CAMERA2_TRIGGER_PRECAPTURE_METERING trigger received yet
     * by HAL. Always updated even if AE algorithm ignores the
     * trigger</p>
     * @hide
     */
    public static final Key<Integer> CONTROL_AE_PRECAPTURE_ID =
            new Key<Integer>("android.control.aePrecaptureId", int.class);

    /**
     * <p>The desired mode for the camera device's
     * auto-exposure routine.</p>
     * <p>This control is only effective if {@link CaptureRequest#CONTROL_MODE android.control.mode} is
     * AUTO.</p>
     * <p>When set to any of the ON modes, the camera device's
     * auto-exposure routine is enabled, overriding the
     * application's selected exposure time, sensor sensitivity,
     * and frame duration ({@link CaptureRequest#SENSOR_EXPOSURE_TIME android.sensor.exposureTime},
     * {@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity}, and
     * {@link CaptureRequest#SENSOR_FRAME_DURATION android.sensor.frameDuration}). If one of the FLASH modes
     * is selected, the camera device's flash unit controls are
     * also overridden.</p>
     * <p>The FLASH modes are only available if the camera device
     * has a flash unit ({@link CameraCharacteristics#FLASH_INFO_AVAILABLE android.flash.info.available} is <code>true</code>).</p>
     * <p>If flash TORCH mode is desired, this field must be set to
     * ON or OFF, and {@link CaptureRequest#FLASH_MODE android.flash.mode} set to TORCH.</p>
     * <p>When set to any of the ON modes, the values chosen by the
     * camera device auto-exposure routine for the overridden
     * fields for a given capture will be available in its
     * CaptureResult.</p>
     *
     * @see CaptureRequest#CONTROL_MODE
     * @see CameraCharacteristics#FLASH_INFO_AVAILABLE
     * @see CaptureRequest#FLASH_MODE
     * @see CaptureRequest#SENSOR_EXPOSURE_TIME
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     * @see CaptureRequest#SENSOR_SENSITIVITY
     * @see #CONTROL_AE_MODE_OFF
     * @see #CONTROL_AE_MODE_ON
     * @see #CONTROL_AE_MODE_ON_AUTO_FLASH
     * @see #CONTROL_AE_MODE_ON_ALWAYS_FLASH
     * @see #CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE
     */
    public static final Key<Integer> CONTROL_AE_MODE =
            new Key<Integer>("android.control.aeMode", int.class);

    /**
     * <p>List of areas to use for
     * metering.</p>
     * <p>Each area is a rectangle plus weight: xmin, ymin,
     * xmax, ymax, weight. The rectangle is defined to be inclusive of the
     * specified coordinates.</p>
     * <p>The coordinate system is based on the active pixel array,
     * with (0,0) being the top-left pixel in the active pixel array, and
     * ({@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.width - 1,
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.height - 1) being the
     * bottom-right pixel in the active pixel array. The weight
     * should be nonnegative.</p>
     * <p>If all regions have 0 weight, then no specific metering area
     * needs to be used by the camera device. If the metering region is
     * outside the current {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}, the camera device
     * will ignore the sections outside the region and output the
     * used sections in the frame metadata.</p>
     *
     * @see CaptureRequest#SCALER_CROP_REGION
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     */
    public static final Key<int[]> CONTROL_AE_REGIONS =
            new Key<int[]>("android.control.aeRegions", int[].class);

    /**
     * <p>Current state of AE algorithm</p>
     * <p>Switching between or enabling AE modes ({@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode}) always
     * resets the AE state to INACTIVE. Similarly, switching between {@link CaptureRequest#CONTROL_MODE android.control.mode},
     * or {@link CaptureRequest#CONTROL_SCENE_MODE android.control.sceneMode} if <code>{@link CaptureRequest#CONTROL_MODE android.control.mode} == USE_SCENE_MODE</code> resets all
     * the algorithm states to INACTIVE.</p>
     * <p>The camera device can do several state transitions between two results, if it is
     * allowed by the state transition table. For example: INACTIVE may never actually be
     * seen in a result.</p>
     * <p>The state in the result is the state for this image (in sync with this image): if
     * AE state becomes CONVERGED, then the image data associated with this result should
     * be good to use.</p>
     * <p>Below are state transition tables for different AE modes.</p>
     * <table>
     * <thead>
     * <tr>
     * <th align="center">State</th>
     * <th align="center">Transition Cause</th>
     * <th align="center">New State</th>
     * <th align="center">Notes</th>
     * </tr>
     * </thead>
     * <tbody>
     * <tr>
     * <td align="center">INACTIVE</td>
     * <td align="center"></td>
     * <td align="center">INACTIVE</td>
     * <td align="center">Camera device auto exposure algorithm is disabled</td>
     * </tr>
     * </tbody>
     * </table>
     * <p>When {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} is AE_MODE_ON_*:</p>
     * <table>
     * <thead>
     * <tr>
     * <th align="center">State</th>
     * <th align="center">Transition Cause</th>
     * <th align="center">New State</th>
     * <th align="center">Notes</th>
     * </tr>
     * </thead>
     * <tbody>
     * <tr>
     * <td align="center">INACTIVE</td>
     * <td align="center">Camera device initiates AE scan</td>
     * <td align="center">SEARCHING</td>
     * <td align="center">Values changing</td>
     * </tr>
     * <tr>
     * <td align="center">INACTIVE</td>
     * <td align="center">{@link CaptureRequest#CONTROL_AE_LOCK android.control.aeLock} is ON</td>
     * <td align="center">LOCKED</td>
     * <td align="center">Values locked</td>
     * </tr>
     * <tr>
     * <td align="center">SEARCHING</td>
     * <td align="center">Camera device finishes AE scan</td>
     * <td align="center">CONVERGED</td>
     * <td align="center">Good values, not changing</td>
     * </tr>
     * <tr>
     * <td align="center">SEARCHING</td>
     * <td align="center">Camera device finishes AE scan</td>
     * <td align="center">FLASH_REQUIRED</td>
     * <td align="center">Converged but too dark w/o flash</td>
     * </tr>
     * <tr>
     * <td align="center">SEARCHING</td>
     * <td align="center">{@link CaptureRequest#CONTROL_AE_LOCK android.control.aeLock} is ON</td>
     * <td align="center">LOCKED</td>
     * <td align="center">Values locked</td>
     * </tr>
     * <tr>
     * <td align="center">CONVERGED</td>
     * <td align="center">Camera device initiates AE scan</td>
     * <td align="center">SEARCHING</td>
     * <td align="center">Values changing</td>
     * </tr>
     * <tr>
     * <td align="center">CONVERGED</td>
     * <td align="center">{@link CaptureRequest#CONTROL_AE_LOCK android.control.aeLock} is ON</td>
     * <td align="center">LOCKED</td>
     * <td align="center">Values locked</td>
     * </tr>
     * <tr>
     * <td align="center">FLASH_REQUIRED</td>
     * <td align="center">Camera device initiates AE scan</td>
     * <td align="center">SEARCHING</td>
     * <td align="center">Values changing</td>
     * </tr>
     * <tr>
     * <td align="center">FLASH_REQUIRED</td>
     * <td align="center">{@link CaptureRequest#CONTROL_AE_LOCK android.control.aeLock} is ON</td>
     * <td align="center">LOCKED</td>
     * <td align="center">Values locked</td>
     * </tr>
     * <tr>
     * <td align="center">LOCKED</td>
     * <td align="center">{@link CaptureRequest#CONTROL_AE_LOCK android.control.aeLock} is OFF</td>
     * <td align="center">SEARCHING</td>
     * <td align="center">Values not good after unlock</td>
     * </tr>
     * <tr>
     * <td align="center">LOCKED</td>
     * <td align="center">{@link CaptureRequest#CONTROL_AE_LOCK android.control.aeLock} is OFF</td>
     * <td align="center">CONVERGED</td>
     * <td align="center">Values good after unlock</td>
     * </tr>
     * <tr>
     * <td align="center">LOCKED</td>
     * <td align="center">{@link CaptureRequest#CONTROL_AE_LOCK android.control.aeLock} is OFF</td>
     * <td align="center">FLASH_REQUIRED</td>
     * <td align="center">Exposure good, but too dark</td>
     * </tr>
     * <tr>
     * <td align="center">PRECAPTURE</td>
     * <td align="center">Sequence done. {@link CaptureRequest#CONTROL_AE_LOCK android.control.aeLock} is OFF</td>
     * <td align="center">CONVERGED</td>
     * <td align="center">Ready for high-quality capture</td>
     * </tr>
     * <tr>
     * <td align="center">PRECAPTURE</td>
     * <td align="center">Sequence done. {@link CaptureRequest#CONTROL_AE_LOCK android.control.aeLock} is ON</td>
     * <td align="center">LOCKED</td>
     * <td align="center">Ready for high-quality capture</td>
     * </tr>
     * <tr>
     * <td align="center">Any state</td>
     * <td align="center">{@link CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER android.control.aePrecaptureTrigger} is START</td>
     * <td align="center">PRECAPTURE</td>
     * <td align="center">Start AE precapture metering sequence</td>
     * </tr>
     * </tbody>
     * </table>
     * <p>For the above table, the camera device may skip reporting any state changes that happen
     * without application intervention (i.e. mode switch, trigger, locking). Any state that
     * can be skipped in that manner is called a transient state.</p>
     * <p>For example, for above AE modes (AE_MODE_ON_*), in addition to the state transitions
     * listed in above table, it is also legal for the camera device to skip one or more
     * transient states between two results. See below table for examples:</p>
     * <table>
     * <thead>
     * <tr>
     * <th align="center">State</th>
     * <th align="center">Transition Cause</th>
     * <th align="center">New State</th>
     * <th align="center">Notes</th>
     * </tr>
     * </thead>
     * <tbody>
     * <tr>
     * <td align="center">INACTIVE</td>
     * <td align="center">Camera device finished AE scan</td>
     * <td align="center">CONVERGED</td>
     * <td align="center">Values are already good, transient states are skipped by camera device.</td>
     * </tr>
     * <tr>
     * <td align="center">Any state</td>
     * <td align="center">{@link CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER android.control.aePrecaptureTrigger} is START, sequence done</td>
     * <td align="center">FLASH_REQUIRED</td>
     * <td align="center">Converged but too dark w/o flash after a precapture sequence, transient states are skipped by camera device.</td>
     * </tr>
     * <tr>
     * <td align="center">Any state</td>
     * <td align="center">{@link CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER android.control.aePrecaptureTrigger} is START, sequence done</td>
     * <td align="center">CONVERGED</td>
     * <td align="center">Converged after a precapture sequence, transient states are skipped by camera device.</td>
     * </tr>
     * <tr>
     * <td align="center">CONVERGED</td>
     * <td align="center">Camera device finished AE scan</td>
     * <td align="center">FLASH_REQUIRED</td>
     * <td align="center">Converged but too dark w/o flash after a new scan, transient states are skipped by camera device.</td>
     * </tr>
     * <tr>
     * <td align="center">FLASH_REQUIRED</td>
     * <td align="center">Camera device finished AE scan</td>
     * <td align="center">CONVERGED</td>
     * <td align="center">Converged after a new scan, transient states are skipped by camera device.</td>
     * </tr>
     * </tbody>
     * </table>
     *
     * @see CaptureRequest#CONTROL_AE_LOCK
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
     * @see CaptureRequest#CONTROL_MODE
     * @see CaptureRequest#CONTROL_SCENE_MODE
     * @see #CONTROL_AE_STATE_INACTIVE
     * @see #CONTROL_AE_STATE_SEARCHING
     * @see #CONTROL_AE_STATE_CONVERGED
     * @see #CONTROL_AE_STATE_LOCKED
     * @see #CONTROL_AE_STATE_FLASH_REQUIRED
     * @see #CONTROL_AE_STATE_PRECAPTURE
     */
    public static final Key<Integer> CONTROL_AE_STATE =
            new Key<Integer>("android.control.aeState", int.class);

    /**
     * <p>Whether AF is currently enabled, and what
     * mode it is set to</p>
     * <p>Only effective if {@link CaptureRequest#CONTROL_MODE android.control.mode} = AUTO and the lens is not fixed focus
     * (i.e. <code>{@link CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE android.lens.info.minimumFocusDistance} &gt; 0</code>).</p>
     * <p>If the lens is controlled by the camera device auto-focus algorithm,
     * the camera device will report the current AF status in {@link CaptureResult#CONTROL_AF_STATE android.control.afState}
     * in result metadata.</p>
     *
     * @see CaptureResult#CONTROL_AF_STATE
     * @see CaptureRequest#CONTROL_MODE
     * @see CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE
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
     * <p>List of areas to use for focus
     * estimation.</p>
     * <p>Each area is a rectangle plus weight: xmin, ymin,
     * xmax, ymax, weight. The rectangle is defined to be inclusive of the
     * specified coordinates.</p>
     * <p>The coordinate system is based on the active pixel array,
     * with (0,0) being the top-left pixel in the active pixel array, and
     * ({@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.width - 1,
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.height - 1) being the
     * bottom-right pixel in the active pixel array. The weight
     * should be nonnegative.</p>
     * <p>If all regions have 0 weight, then no specific focus area
     * needs to be used by the camera device. If the focusing region is
     * outside the current {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}, the camera device
     * will ignore the sections outside the region and output the
     * used sections in the frame metadata.</p>
     *
     * @see CaptureRequest#SCALER_CROP_REGION
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     */
    public static final Key<int[]> CONTROL_AF_REGIONS =
            new Key<int[]>("android.control.afRegions", int[].class);

    /**
     * <p>Current state of AF algorithm.</p>
     * <p>Switching between or enabling AF modes ({@link CaptureRequest#CONTROL_AF_MODE android.control.afMode}) always
     * resets the AF state to INACTIVE. Similarly, switching between {@link CaptureRequest#CONTROL_MODE android.control.mode},
     * or {@link CaptureRequest#CONTROL_SCENE_MODE android.control.sceneMode} if <code>{@link CaptureRequest#CONTROL_MODE android.control.mode} == USE_SCENE_MODE</code> resets all
     * the algorithm states to INACTIVE.</p>
     * <p>The camera device can do several state transitions between two results, if it is
     * allowed by the state transition table. For example: INACTIVE may never actually be
     * seen in a result.</p>
     * <p>The state in the result is the state for this image (in sync with this image): if
     * AF state becomes FOCUSED, then the image data associated with this result should
     * be sharp.</p>
     * <p>Below are state transition tables for different AF modes.</p>
     * <p>When {@link CaptureRequest#CONTROL_AF_MODE android.control.afMode} is AF_MODE_OFF or AF_MODE_EDOF:</p>
     * <table>
     * <thead>
     * <tr>
     * <th align="center">State</th>
     * <th align="center">Transition Cause</th>
     * <th align="center">New State</th>
     * <th align="center">Notes</th>
     * </tr>
     * </thead>
     * <tbody>
     * <tr>
     * <td align="center">INACTIVE</td>
     * <td align="center"></td>
     * <td align="center">INACTIVE</td>
     * <td align="center">Never changes</td>
     * </tr>
     * </tbody>
     * </table>
     * <p>When {@link CaptureRequest#CONTROL_AF_MODE android.control.afMode} is AF_MODE_AUTO or AF_MODE_MACRO:</p>
     * <table>
     * <thead>
     * <tr>
     * <th align="center">State</th>
     * <th align="center">Transition Cause</th>
     * <th align="center">New State</th>
     * <th align="center">Notes</th>
     * </tr>
     * </thead>
     * <tbody>
     * <tr>
     * <td align="center">INACTIVE</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">ACTIVE_SCAN</td>
     * <td align="center">Start AF sweep, Lens now moving</td>
     * </tr>
     * <tr>
     * <td align="center">ACTIVE_SCAN</td>
     * <td align="center">AF sweep done</td>
     * <td align="center">FOCUSED_LOCKED</td>
     * <td align="center">Focused, Lens now locked</td>
     * </tr>
     * <tr>
     * <td align="center">ACTIVE_SCAN</td>
     * <td align="center">AF sweep done</td>
     * <td align="center">NOT_FOCUSED_LOCKED</td>
     * <td align="center">Not focused, Lens now locked</td>
     * </tr>
     * <tr>
     * <td align="center">ACTIVE_SCAN</td>
     * <td align="center">AF_CANCEL</td>
     * <td align="center">INACTIVE</td>
     * <td align="center">Cancel/reset AF, Lens now locked</td>
     * </tr>
     * <tr>
     * <td align="center">FOCUSED_LOCKED</td>
     * <td align="center">AF_CANCEL</td>
     * <td align="center">INACTIVE</td>
     * <td align="center">Cancel/reset AF</td>
     * </tr>
     * <tr>
     * <td align="center">FOCUSED_LOCKED</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">ACTIVE_SCAN</td>
     * <td align="center">Start new sweep, Lens now moving</td>
     * </tr>
     * <tr>
     * <td align="center">NOT_FOCUSED_LOCKED</td>
     * <td align="center">AF_CANCEL</td>
     * <td align="center">INACTIVE</td>
     * <td align="center">Cancel/reset AF</td>
     * </tr>
     * <tr>
     * <td align="center">NOT_FOCUSED_LOCKED</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">ACTIVE_SCAN</td>
     * <td align="center">Start new sweep, Lens now moving</td>
     * </tr>
     * <tr>
     * <td align="center">Any state</td>
     * <td align="center">Mode change</td>
     * <td align="center">INACTIVE</td>
     * <td align="center"></td>
     * </tr>
     * </tbody>
     * </table>
     * <p>For the above table, the camera device may skip reporting any state changes that happen
     * without application intervention (i.e. mode switch, trigger, locking). Any state that
     * can be skipped in that manner is called a transient state.</p>
     * <p>For example, for these AF modes (AF_MODE_AUTO and AF_MODE_MACRO), in addition to the
     * state transitions listed in above table, it is also legal for the camera device to skip
     * one or more transient states between two results. See below table for examples:</p>
     * <table>
     * <thead>
     * <tr>
     * <th align="center">State</th>
     * <th align="center">Transition Cause</th>
     * <th align="center">New State</th>
     * <th align="center">Notes</th>
     * </tr>
     * </thead>
     * <tbody>
     * <tr>
     * <td align="center">INACTIVE</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">FOCUSED_LOCKED</td>
     * <td align="center">Focus is already good or good after a scan, lens is now locked.</td>
     * </tr>
     * <tr>
     * <td align="center">INACTIVE</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">NOT_FOCUSED_LOCKED</td>
     * <td align="center">Focus failed after a scan, lens is now locked.</td>
     * </tr>
     * <tr>
     * <td align="center">FOCUSED_LOCKED</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">FOCUSED_LOCKED</td>
     * <td align="center">Focus is already good or good after a scan, lens is now locked.</td>
     * </tr>
     * <tr>
     * <td align="center">NOT_FOCUSED_LOCKED</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">FOCUSED_LOCKED</td>
     * <td align="center">Focus is good after a scan, lens is not locked.</td>
     * </tr>
     * </tbody>
     * </table>
     * <p>When {@link CaptureRequest#CONTROL_AF_MODE android.control.afMode} is AF_MODE_CONTINUOUS_VIDEO:</p>
     * <table>
     * <thead>
     * <tr>
     * <th align="center">State</th>
     * <th align="center">Transition Cause</th>
     * <th align="center">New State</th>
     * <th align="center">Notes</th>
     * </tr>
     * </thead>
     * <tbody>
     * <tr>
     * <td align="center">INACTIVE</td>
     * <td align="center">Camera device initiates new scan</td>
     * <td align="center">PASSIVE_SCAN</td>
     * <td align="center">Start AF scan, Lens now moving</td>
     * </tr>
     * <tr>
     * <td align="center">INACTIVE</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">NOT_FOCUSED_LOCKED</td>
     * <td align="center">AF state query, Lens now locked</td>
     * </tr>
     * <tr>
     * <td align="center">PASSIVE_SCAN</td>
     * <td align="center">Camera device completes current scan</td>
     * <td align="center">PASSIVE_FOCUSED</td>
     * <td align="center">End AF scan, Lens now locked</td>
     * </tr>
     * <tr>
     * <td align="center">PASSIVE_SCAN</td>
     * <td align="center">Camera device fails current scan</td>
     * <td align="center">PASSIVE_UNFOCUSED</td>
     * <td align="center">End AF scan, Lens now locked</td>
     * </tr>
     * <tr>
     * <td align="center">PASSIVE_SCAN</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">FOCUSED_LOCKED</td>
     * <td align="center">Immediate trans. If focus is good, Lens now locked</td>
     * </tr>
     * <tr>
     * <td align="center">PASSIVE_SCAN</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">NOT_FOCUSED_LOCKED</td>
     * <td align="center">Immediate trans. if focus is bad, Lens now locked</td>
     * </tr>
     * <tr>
     * <td align="center">PASSIVE_SCAN</td>
     * <td align="center">AF_CANCEL</td>
     * <td align="center">INACTIVE</td>
     * <td align="center">Reset lens position, Lens now locked</td>
     * </tr>
     * <tr>
     * <td align="center">PASSIVE_FOCUSED</td>
     * <td align="center">Camera device initiates new scan</td>
     * <td align="center">PASSIVE_SCAN</td>
     * <td align="center">Start AF scan, Lens now moving</td>
     * </tr>
     * <tr>
     * <td align="center">PASSIVE_UNFOCUSED</td>
     * <td align="center">Camera device initiates new scan</td>
     * <td align="center">PASSIVE_SCAN</td>
     * <td align="center">Start AF scan, Lens now moving</td>
     * </tr>
     * <tr>
     * <td align="center">PASSIVE_FOCUSED</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">FOCUSED_LOCKED</td>
     * <td align="center">Immediate trans. Lens now locked</td>
     * </tr>
     * <tr>
     * <td align="center">PASSIVE_UNFOCUSED</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">NOT_FOCUSED_LOCKED</td>
     * <td align="center">Immediate trans. Lens now locked</td>
     * </tr>
     * <tr>
     * <td align="center">FOCUSED_LOCKED</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">FOCUSED_LOCKED</td>
     * <td align="center">No effect</td>
     * </tr>
     * <tr>
     * <td align="center">FOCUSED_LOCKED</td>
     * <td align="center">AF_CANCEL</td>
     * <td align="center">INACTIVE</td>
     * <td align="center">Restart AF scan</td>
     * </tr>
     * <tr>
     * <td align="center">NOT_FOCUSED_LOCKED</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">NOT_FOCUSED_LOCKED</td>
     * <td align="center">No effect</td>
     * </tr>
     * <tr>
     * <td align="center">NOT_FOCUSED_LOCKED</td>
     * <td align="center">AF_CANCEL</td>
     * <td align="center">INACTIVE</td>
     * <td align="center">Restart AF scan</td>
     * </tr>
     * </tbody>
     * </table>
     * <p>When {@link CaptureRequest#CONTROL_AF_MODE android.control.afMode} is AF_MODE_CONTINUOUS_PICTURE:</p>
     * <table>
     * <thead>
     * <tr>
     * <th align="center">State</th>
     * <th align="center">Transition Cause</th>
     * <th align="center">New State</th>
     * <th align="center">Notes</th>
     * </tr>
     * </thead>
     * <tbody>
     * <tr>
     * <td align="center">INACTIVE</td>
     * <td align="center">Camera device initiates new scan</td>
     * <td align="center">PASSIVE_SCAN</td>
     * <td align="center">Start AF scan, Lens now moving</td>
     * </tr>
     * <tr>
     * <td align="center">INACTIVE</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">NOT_FOCUSED_LOCKED</td>
     * <td align="center">AF state query, Lens now locked</td>
     * </tr>
     * <tr>
     * <td align="center">PASSIVE_SCAN</td>
     * <td align="center">Camera device completes current scan</td>
     * <td align="center">PASSIVE_FOCUSED</td>
     * <td align="center">End AF scan, Lens now locked</td>
     * </tr>
     * <tr>
     * <td align="center">PASSIVE_SCAN</td>
     * <td align="center">Camera device fails current scan</td>
     * <td align="center">PASSIVE_UNFOCUSED</td>
     * <td align="center">End AF scan, Lens now locked</td>
     * </tr>
     * <tr>
     * <td align="center">PASSIVE_SCAN</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">FOCUSED_LOCKED</td>
     * <td align="center">Eventual trans. once focus good, Lens now locked</td>
     * </tr>
     * <tr>
     * <td align="center">PASSIVE_SCAN</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">NOT_FOCUSED_LOCKED</td>
     * <td align="center">Eventual trans. if cannot focus, Lens now locked</td>
     * </tr>
     * <tr>
     * <td align="center">PASSIVE_SCAN</td>
     * <td align="center">AF_CANCEL</td>
     * <td align="center">INACTIVE</td>
     * <td align="center">Reset lens position, Lens now locked</td>
     * </tr>
     * <tr>
     * <td align="center">PASSIVE_FOCUSED</td>
     * <td align="center">Camera device initiates new scan</td>
     * <td align="center">PASSIVE_SCAN</td>
     * <td align="center">Start AF scan, Lens now moving</td>
     * </tr>
     * <tr>
     * <td align="center">PASSIVE_UNFOCUSED</td>
     * <td align="center">Camera device initiates new scan</td>
     * <td align="center">PASSIVE_SCAN</td>
     * <td align="center">Start AF scan, Lens now moving</td>
     * </tr>
     * <tr>
     * <td align="center">PASSIVE_FOCUSED</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">FOCUSED_LOCKED</td>
     * <td align="center">Immediate trans. Lens now locked</td>
     * </tr>
     * <tr>
     * <td align="center">PASSIVE_UNFOCUSED</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">NOT_FOCUSED_LOCKED</td>
     * <td align="center">Immediate trans. Lens now locked</td>
     * </tr>
     * <tr>
     * <td align="center">FOCUSED_LOCKED</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">FOCUSED_LOCKED</td>
     * <td align="center">No effect</td>
     * </tr>
     * <tr>
     * <td align="center">FOCUSED_LOCKED</td>
     * <td align="center">AF_CANCEL</td>
     * <td align="center">INACTIVE</td>
     * <td align="center">Restart AF scan</td>
     * </tr>
     * <tr>
     * <td align="center">NOT_FOCUSED_LOCKED</td>
     * <td align="center">AF_TRIGGER</td>
     * <td align="center">NOT_FOCUSED_LOCKED</td>
     * <td align="center">No effect</td>
     * </tr>
     * <tr>
     * <td align="center">NOT_FOCUSED_LOCKED</td>
     * <td align="center">AF_CANCEL</td>
     * <td align="center">INACTIVE</td>
     * <td align="center">Restart AF scan</td>
     * </tr>
     * </tbody>
     * </table>
     * <p>When switch between AF_MODE_CONTINUOUS_* (CAF modes) and AF_MODE_AUTO/AF_MODE_MACRO
     * (AUTO modes), the initial INACTIVE or PASSIVE_SCAN states may be skipped by the
     * camera device. When a trigger is included in a mode switch request, the trigger
     * will be evaluated in the context of the new mode in the request.
     * See below table for examples:</p>
     * <table>
     * <thead>
     * <tr>
     * <th align="center">State</th>
     * <th align="center">Transition Cause</th>
     * <th align="center">New State</th>
     * <th align="center">Notes</th>
     * </tr>
     * </thead>
     * <tbody>
     * <tr>
     * <td align="center">any state</td>
     * <td align="center">CAF--&gt;AUTO mode switch</td>
     * <td align="center">INACTIVE</td>
     * <td align="center">Mode switch without trigger, initial state must be INACTIVE</td>
     * </tr>
     * <tr>
     * <td align="center">any state</td>
     * <td align="center">CAF--&gt;AUTO mode switch with AF_TRIGGER</td>
     * <td align="center">trigger-reachable states from INACTIVE</td>
     * <td align="center">Mode switch with trigger, INACTIVE is skipped</td>
     * </tr>
     * <tr>
     * <td align="center">any state</td>
     * <td align="center">AUTO--&gt;CAF mode switch</td>
     * <td align="center">passively reachable states from INACTIVE</td>
     * <td align="center">Mode switch without trigger, passive transient state is skipped</td>
     * </tr>
     * </tbody>
     * </table>
     *
     * @see CaptureRequest#CONTROL_AF_MODE
     * @see CaptureRequest#CONTROL_MODE
     * @see CaptureRequest#CONTROL_SCENE_MODE
     * @see #CONTROL_AF_STATE_INACTIVE
     * @see #CONTROL_AF_STATE_PASSIVE_SCAN
     * @see #CONTROL_AF_STATE_PASSIVE_FOCUSED
     * @see #CONTROL_AF_STATE_ACTIVE_SCAN
     * @see #CONTROL_AF_STATE_FOCUSED_LOCKED
     * @see #CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
     * @see #CONTROL_AF_STATE_PASSIVE_UNFOCUSED
     */
    public static final Key<Integer> CONTROL_AF_STATE =
            new Key<Integer>("android.control.afState", int.class);

    /**
     * <p>The ID sent with the latest
     * CAMERA2_TRIGGER_AUTOFOCUS call</p>
     * <p>Must be 0 if no CAMERA2_TRIGGER_AUTOFOCUS trigger
     * received yet by HAL. Always updated even if AF algorithm
     * ignores the trigger</p>
     * @hide
     */
    public static final Key<Integer> CONTROL_AF_TRIGGER_ID =
            new Key<Integer>("android.control.afTriggerId", int.class);

    /**
     * <p>Whether AWB is currently setting the color
     * transform fields, and what its illumination target
     * is.</p>
     * <p>This control is only effective if {@link CaptureRequest#CONTROL_MODE android.control.mode} is AUTO.</p>
     * <p>When set to the ON mode, the camera device's auto white balance
     * routine is enabled, overriding the application's selected
     * {@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform}, {@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains} and
     * {@link CaptureRequest#COLOR_CORRECTION_MODE android.colorCorrection.mode}.</p>
     * <p>When set to the OFF mode, the camera device's auto white balance
     * routine is disabled. The application manually controls the white
     * balance by {@link CaptureRequest#COLOR_CORRECTION_TRANSFORM android.colorCorrection.transform}, {@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains}
     * and {@link CaptureRequest#COLOR_CORRECTION_MODE android.colorCorrection.mode}.</p>
     * <p>When set to any other modes, the camera device's auto white balance
     * routine is disabled. The camera device uses each particular illumination
     * target for white balance adjustment.</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_GAINS
     * @see CaptureRequest#COLOR_CORRECTION_MODE
     * @see CaptureRequest#COLOR_CORRECTION_TRANSFORM
     * @see CaptureRequest#CONTROL_MODE
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
     * <p>List of areas to use for illuminant
     * estimation.</p>
     * <p>Only used in AUTO mode.</p>
     * <p>Each area is a rectangle plus weight: xmin, ymin,
     * xmax, ymax, weight. The rectangle is defined to be inclusive of the
     * specified coordinates.</p>
     * <p>The coordinate system is based on the active pixel array,
     * with (0,0) being the top-left pixel in the active pixel array, and
     * ({@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.width - 1,
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.height - 1) being the
     * bottom-right pixel in the active pixel array. The weight
     * should be nonnegative.</p>
     * <p>If all regions have 0 weight, then no specific auto-white balance (AWB) area
     * needs to be used by the camera device. If the AWB region is
     * outside the current {@link CaptureRequest#SCALER_CROP_REGION android.scaler.cropRegion}, the camera device
     * will ignore the sections outside the region and output the
     * used sections in the frame metadata.</p>
     *
     * @see CaptureRequest#SCALER_CROP_REGION
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     */
    public static final Key<int[]> CONTROL_AWB_REGIONS =
            new Key<int[]>("android.control.awbRegions", int[].class);

    /**
     * <p>Current state of AWB algorithm</p>
     * <p>Switching between or enabling AWB modes ({@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode}) always
     * resets the AWB state to INACTIVE. Similarly, switching between {@link CaptureRequest#CONTROL_MODE android.control.mode},
     * or {@link CaptureRequest#CONTROL_SCENE_MODE android.control.sceneMode} if <code>{@link CaptureRequest#CONTROL_MODE android.control.mode} == USE_SCENE_MODE</code> resets all
     * the algorithm states to INACTIVE.</p>
     * <p>The camera device can do several state transitions between two results, if it is
     * allowed by the state transition table. So INACTIVE may never actually be seen in
     * a result.</p>
     * <p>The state in the result is the state for this image (in sync with this image): if
     * AWB state becomes CONVERGED, then the image data associated with this result should
     * be good to use.</p>
     * <p>Below are state transition tables for different AWB modes.</p>
     * <p>When <code>{@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode} != AWB_MODE_AUTO</code>:</p>
     * <table>
     * <thead>
     * <tr>
     * <th align="center">State</th>
     * <th align="center">Transition Cause</th>
     * <th align="center">New State</th>
     * <th align="center">Notes</th>
     * </tr>
     * </thead>
     * <tbody>
     * <tr>
     * <td align="center">INACTIVE</td>
     * <td align="center"></td>
     * <td align="center">INACTIVE</td>
     * <td align="center">Camera device auto white balance algorithm is disabled</td>
     * </tr>
     * </tbody>
     * </table>
     * <p>When {@link CaptureRequest#CONTROL_AWB_MODE android.control.awbMode} is AWB_MODE_AUTO:</p>
     * <table>
     * <thead>
     * <tr>
     * <th align="center">State</th>
     * <th align="center">Transition Cause</th>
     * <th align="center">New State</th>
     * <th align="center">Notes</th>
     * </tr>
     * </thead>
     * <tbody>
     * <tr>
     * <td align="center">INACTIVE</td>
     * <td align="center">Camera device initiates AWB scan</td>
     * <td align="center">SEARCHING</td>
     * <td align="center">Values changing</td>
     * </tr>
     * <tr>
     * <td align="center">INACTIVE</td>
     * <td align="center">{@link CaptureRequest#CONTROL_AWB_LOCK android.control.awbLock} is ON</td>
     * <td align="center">LOCKED</td>
     * <td align="center">Values locked</td>
     * </tr>
     * <tr>
     * <td align="center">SEARCHING</td>
     * <td align="center">Camera device finishes AWB scan</td>
     * <td align="center">CONVERGED</td>
     * <td align="center">Good values, not changing</td>
     * </tr>
     * <tr>
     * <td align="center">SEARCHING</td>
     * <td align="center">{@link CaptureRequest#CONTROL_AWB_LOCK android.control.awbLock} is ON</td>
     * <td align="center">LOCKED</td>
     * <td align="center">Values locked</td>
     * </tr>
     * <tr>
     * <td align="center">CONVERGED</td>
     * <td align="center">Camera device initiates AWB scan</td>
     * <td align="center">SEARCHING</td>
     * <td align="center">Values changing</td>
     * </tr>
     * <tr>
     * <td align="center">CONVERGED</td>
     * <td align="center">{@link CaptureRequest#CONTROL_AWB_LOCK android.control.awbLock} is ON</td>
     * <td align="center">LOCKED</td>
     * <td align="center">Values locked</td>
     * </tr>
     * <tr>
     * <td align="center">LOCKED</td>
     * <td align="center">{@link CaptureRequest#CONTROL_AWB_LOCK android.control.awbLock} is OFF</td>
     * <td align="center">SEARCHING</td>
     * <td align="center">Values not good after unlock</td>
     * </tr>
     * </tbody>
     * </table>
     * <p>For the above table, the camera device may skip reporting any state changes that happen
     * without application intervention (i.e. mode switch, trigger, locking). Any state that
     * can be skipped in that manner is called a transient state.</p>
     * <p>For example, for this AWB mode (AWB_MODE_AUTO), in addition to the state transitions
     * listed in above table, it is also legal for the camera device to skip one or more
     * transient states between two results. See below table for examples:</p>
     * <table>
     * <thead>
     * <tr>
     * <th align="center">State</th>
     * <th align="center">Transition Cause</th>
     * <th align="center">New State</th>
     * <th align="center">Notes</th>
     * </tr>
     * </thead>
     * <tbody>
     * <tr>
     * <td align="center">INACTIVE</td>
     * <td align="center">Camera device finished AWB scan</td>
     * <td align="center">CONVERGED</td>
     * <td align="center">Values are already good, transient states are skipped by camera device.</td>
     * </tr>
     * <tr>
     * <td align="center">LOCKED</td>
     * <td align="center">{@link CaptureRequest#CONTROL_AWB_LOCK android.control.awbLock} is OFF</td>
     * <td align="center">CONVERGED</td>
     * <td align="center">Values good after unlock, transient states are skipped by camera device.</td>
     * </tr>
     * </tbody>
     * </table>
     *
     * @see CaptureRequest#CONTROL_AWB_LOCK
     * @see CaptureRequest#CONTROL_AWB_MODE
     * @see CaptureRequest#CONTROL_MODE
     * @see CaptureRequest#CONTROL_SCENE_MODE
     * @see #CONTROL_AWB_STATE_INACTIVE
     * @see #CONTROL_AWB_STATE_SEARCHING
     * @see #CONTROL_AWB_STATE_CONVERGED
     * @see #CONTROL_AWB_STATE_LOCKED
     */
    public static final Key<Integer> CONTROL_AWB_STATE =
            new Key<Integer>("android.control.awbState", int.class);

    /**
     * <p>Overall mode of 3A control
     * routines.</p>
     * <p>High-level 3A control. When set to OFF, all 3A control
     * by the camera device is disabled. The application must set the fields for
     * capture parameters itself.</p>
     * <p>When set to AUTO, the individual algorithm controls in
     * android.control.* are in effect, such as {@link CaptureRequest#CONTROL_AF_MODE android.control.afMode}.</p>
     * <p>When set to USE_SCENE_MODE, the individual controls in
     * android.control.* are mostly disabled, and the camera device implements
     * one of the scene mode settings (such as ACTION, SUNSET, or PARTY)
     * as it wishes. The camera device scene mode 3A settings are provided by
     * android.control.sceneModeOverrides.</p>
     * <p>When set to OFF_KEEP_STATE, it is similar to OFF mode, the only difference
     * is that this frame will not be used by camera device background 3A statistics
     * update, as if this frame is never captured. This mode can be used in the scenario
     * where the application doesn't want a 3A manual control capture to affect
     * the subsequent auto 3A capture results.</p>
     *
     * @see CaptureRequest#CONTROL_AF_MODE
     * @see #CONTROL_MODE_OFF
     * @see #CONTROL_MODE_AUTO
     * @see #CONTROL_MODE_USE_SCENE_MODE
     * @see #CONTROL_MODE_OFF_KEEP_STATE
     */
    public static final Key<Integer> CONTROL_MODE =
            new Key<Integer>("android.control.mode", int.class);

    /**
     * <p>Operation mode for edge
     * enhancement.</p>
     * <p>Edge/sharpness/detail enhancement. OFF means no
     * enhancement will be applied by the camera device.</p>
     * <p>This must be set to one of the modes listed in {@link CameraCharacteristics#EDGE_AVAILABLE_EDGE_MODES android.edge.availableEdgeModes}.</p>
     * <p>FAST/HIGH_QUALITY both mean camera device determined enhancement
     * will be applied. HIGH_QUALITY mode indicates that the
     * camera device will use the highest-quality enhancement algorithms,
     * even if it slows down capture rate. FAST means the camera device will
     * not slow down capture rate when applying edge enhancement.</p>
     *
     * @see CameraCharacteristics#EDGE_AVAILABLE_EDGE_MODES
     * @see #EDGE_MODE_OFF
     * @see #EDGE_MODE_FAST
     * @see #EDGE_MODE_HIGH_QUALITY
     */
    public static final Key<Integer> EDGE_MODE =
            new Key<Integer>("android.edge.mode", int.class);

    /**
     * <p>The desired mode for for the camera device's flash control.</p>
     * <p>This control is only effective when flash unit is available
     * (<code>{@link CameraCharacteristics#FLASH_INFO_AVAILABLE android.flash.info.available} == true</code>).</p>
     * <p>When this control is used, the {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} must be set to ON or OFF.
     * Otherwise, the camera device auto-exposure related flash control (ON_AUTO_FLASH,
     * ON_ALWAYS_FLASH, or ON_AUTO_FLASH_REDEYE) will override this control.</p>
     * <p>When set to OFF, the camera device will not fire flash for this capture.</p>
     * <p>When set to SINGLE, the camera device will fire flash regardless of the camera
     * device's auto-exposure routine's result. When used in still capture case, this
     * control should be used along with AE precapture metering sequence
     * ({@link CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER android.control.aePrecaptureTrigger}), otherwise, the image may be incorrectly exposed.</p>
     * <p>When set to TORCH, the flash will be on continuously. This mode can be used
     * for use cases such as preview, auto-focus assist, still capture, or video recording.</p>
     * <p>The flash status will be reported by {@link CaptureResult#FLASH_STATE android.flash.state} in the capture result metadata.</p>
     *
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER
     * @see CameraCharacteristics#FLASH_INFO_AVAILABLE
     * @see CaptureResult#FLASH_STATE
     * @see #FLASH_MODE_OFF
     * @see #FLASH_MODE_SINGLE
     * @see #FLASH_MODE_TORCH
     */
    public static final Key<Integer> FLASH_MODE =
            new Key<Integer>("android.flash.mode", int.class);

    /**
     * <p>Current state of the flash
     * unit.</p>
     * <p>When the camera device doesn't have flash unit
     * (i.e. <code>{@link CameraCharacteristics#FLASH_INFO_AVAILABLE android.flash.info.available} == false</code>), this state will always be UNAVAILABLE.
     * Other states indicate the current flash status.</p>
     *
     * @see CameraCharacteristics#FLASH_INFO_AVAILABLE
     * @see #FLASH_STATE_UNAVAILABLE
     * @see #FLASH_STATE_CHARGING
     * @see #FLASH_STATE_READY
     * @see #FLASH_STATE_FIRED
     */
    public static final Key<Integer> FLASH_STATE =
            new Key<Integer>("android.flash.state", int.class);

    /**
     * <p>Set operational mode for hot pixel correction.</p>
     * <p>Valid modes for this camera device are listed in
     * {@link CameraCharacteristics#HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES android.hotPixel.availableHotPixelModes}.</p>
     * <p>Hotpixel correction interpolates out, or otherwise removes, pixels
     * that do not accurately encode the incoming light (i.e. pixels that
     * are stuck at an arbitrary value).</p>
     *
     * @see CameraCharacteristics#HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES
     * @see #HOT_PIXEL_MODE_OFF
     * @see #HOT_PIXEL_MODE_FAST
     * @see #HOT_PIXEL_MODE_HIGH_QUALITY
     */
    public static final Key<Integer> HOT_PIXEL_MODE =
            new Key<Integer>("android.hotPixel.mode", int.class);

    /**
     * <p>GPS coordinates to include in output JPEG
     * EXIF</p>
     */
    public static final Key<double[]> JPEG_GPS_COORDINATES =
            new Key<double[]>("android.jpeg.gpsCoordinates", double[].class);

    /**
     * <p>32 characters describing GPS algorithm to
     * include in EXIF</p>
     */
    public static final Key<String> JPEG_GPS_PROCESSING_METHOD =
            new Key<String>("android.jpeg.gpsProcessingMethod", String.class);

    /**
     * <p>Time GPS fix was made to include in
     * EXIF</p>
     */
    public static final Key<Long> JPEG_GPS_TIMESTAMP =
            new Key<Long>("android.jpeg.gpsTimestamp", long.class);

    /**
     * <p>Orientation of JPEG image to
     * write</p>
     */
    public static final Key<Integer> JPEG_ORIENTATION =
            new Key<Integer>("android.jpeg.orientation", int.class);

    /**
     * <p>Compression quality of the final JPEG
     * image</p>
     * <p>85-95 is typical usage range</p>
     */
    public static final Key<Byte> JPEG_QUALITY =
            new Key<Byte>("android.jpeg.quality", byte.class);

    /**
     * <p>Compression quality of JPEG
     * thumbnail</p>
     */
    public static final Key<Byte> JPEG_THUMBNAIL_QUALITY =
            new Key<Byte>("android.jpeg.thumbnailQuality", byte.class);

    /**
     * <p>Resolution of embedded JPEG thumbnail</p>
     * <p>When set to (0, 0) value, the JPEG EXIF will not contain thumbnail,
     * but the captured JPEG will still be a valid image.</p>
     * <p>When a jpeg image capture is issued, the thumbnail size selected should have
     * the same aspect ratio as the jpeg image.</p>
     */
    public static final Key<android.hardware.camera2.Size> JPEG_THUMBNAIL_SIZE =
            new Key<android.hardware.camera2.Size>("android.jpeg.thumbnailSize", android.hardware.camera2.Size.class);

    /**
     * <p>The ratio of lens focal length to the effective
     * aperture diameter.</p>
     * <p>This will only be supported on the camera devices that
     * have variable aperture lens. The aperture value can only be
     * one of the values listed in {@link CameraCharacteristics#LENS_INFO_AVAILABLE_APERTURES android.lens.info.availableApertures}.</p>
     * <p>When this is supported and {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} is OFF,
     * this can be set along with {@link CaptureRequest#SENSOR_EXPOSURE_TIME android.sensor.exposureTime},
     * {@link CaptureRequest#SENSOR_SENSITIVITY android.sensor.sensitivity}, and {@link CaptureRequest#SENSOR_FRAME_DURATION android.sensor.frameDuration}
     * to achieve manual exposure control.</p>
     * <p>The requested aperture value may take several frames to reach the
     * requested value; the camera device will report the current (intermediate)
     * aperture size in capture result metadata while the aperture is changing.
     * While the aperture is still changing, {@link CaptureResult#LENS_STATE android.lens.state} will be set to MOVING.</p>
     * <p>When this is supported and {@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} is one of
     * the ON modes, this will be overridden by the camera device
     * auto-exposure algorithm, the overridden values are then provided
     * back to the user in the corresponding result.</p>
     *
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CameraCharacteristics#LENS_INFO_AVAILABLE_APERTURES
     * @see CaptureResult#LENS_STATE
     * @see CaptureRequest#SENSOR_EXPOSURE_TIME
     * @see CaptureRequest#SENSOR_FRAME_DURATION
     * @see CaptureRequest#SENSOR_SENSITIVITY
     */
    public static final Key<Float> LENS_APERTURE =
            new Key<Float>("android.lens.aperture", float.class);

    /**
     * <p>State of lens neutral density filter(s).</p>
     * <p>This will not be supported on most camera devices. On devices
     * where this is supported, this may only be set to one of the
     * values included in {@link CameraCharacteristics#LENS_INFO_AVAILABLE_FILTER_DENSITIES android.lens.info.availableFilterDensities}.</p>
     * <p>Lens filters are typically used to lower the amount of light the
     * sensor is exposed to (measured in steps of EV). As used here, an EV
     * step is the standard logarithmic representation, which are
     * non-negative, and inversely proportional to the amount of light
     * hitting the sensor.  For example, setting this to 0 would result
     * in no reduction of the incoming light, and setting this to 2 would
     * mean that the filter is set to reduce incoming light by two stops
     * (allowing 1/4 of the prior amount of light to the sensor).</p>
     * <p>It may take several frames before the lens filter density changes
     * to the requested value. While the filter density is still changing,
     * {@link CaptureResult#LENS_STATE android.lens.state} will be set to MOVING.</p>
     *
     * @see CameraCharacteristics#LENS_INFO_AVAILABLE_FILTER_DENSITIES
     * @see CaptureResult#LENS_STATE
     */
    public static final Key<Float> LENS_FILTER_DENSITY =
            new Key<Float>("android.lens.filterDensity", float.class);

    /**
     * <p>The current lens focal length; used for optical zoom.</p>
     * <p>This setting controls the physical focal length of the camera
     * device's lens. Changing the focal length changes the field of
     * view of the camera device, and is usually used for optical zoom.</p>
     * <p>Like {@link CaptureRequest#LENS_FOCUS_DISTANCE android.lens.focusDistance} and {@link CaptureRequest#LENS_APERTURE android.lens.aperture}, this
     * setting won't be applied instantaneously, and it may take several
     * frames before the lens can change to the requested focal length.
     * While the focal length is still changing, {@link CaptureResult#LENS_STATE android.lens.state} will
     * be set to MOVING.</p>
     * <p>This is expected not to be supported on most devices.</p>
     *
     * @see CaptureRequest#LENS_APERTURE
     * @see CaptureRequest#LENS_FOCUS_DISTANCE
     * @see CaptureResult#LENS_STATE
     */
    public static final Key<Float> LENS_FOCAL_LENGTH =
            new Key<Float>("android.lens.focalLength", float.class);

    /**
     * <p>Distance to plane of sharpest focus,
     * measured from frontmost surface of the lens</p>
     * <p>Should be zero for fixed-focus cameras</p>
     */
    public static final Key<Float> LENS_FOCUS_DISTANCE =
            new Key<Float>("android.lens.focusDistance", float.class);

    /**
     * <p>The range of scene distances that are in
     * sharp focus (depth of field)</p>
     * <p>If variable focus not supported, can still report
     * fixed depth of field range</p>
     */
    public static final Key<float[]> LENS_FOCUS_RANGE =
            new Key<float[]>("android.lens.focusRange", float[].class);

    /**
     * <p>Sets whether the camera device uses optical image stabilization (OIS)
     * when capturing images.</p>
     * <p>OIS is used to compensate for motion blur due to small movements of
     * the camera during capture. Unlike digital image stabilization, OIS makes
     * use of mechanical elements to stabilize the camera sensor, and thus
     * allows for longer exposure times before camera shake becomes
     * apparent.</p>
     * <p>This is not expected to be supported on most devices.</p>
     * @see #LENS_OPTICAL_STABILIZATION_MODE_OFF
     * @see #LENS_OPTICAL_STABILIZATION_MODE_ON
     */
    public static final Key<Integer> LENS_OPTICAL_STABILIZATION_MODE =
            new Key<Integer>("android.lens.opticalStabilizationMode", int.class);

    /**
     * <p>Current lens status.</p>
     * <p>For lens parameters {@link CaptureRequest#LENS_FOCAL_LENGTH android.lens.focalLength}, {@link CaptureRequest#LENS_FOCUS_DISTANCE android.lens.focusDistance},
     * {@link CaptureRequest#LENS_FILTER_DENSITY android.lens.filterDensity} and {@link CaptureRequest#LENS_APERTURE android.lens.aperture}, when changes are requested,
     * they may take several frames to reach the requested values. This state indicates
     * the current status of the lens parameters.</p>
     * <p>When the state is STATIONARY, the lens parameters are not changing. This could be
     * either because the parameters are all fixed, or because the lens has had enough
     * time to reach the most recently-requested values.
     * If all these lens parameters are not changable for a camera device, as listed below:</p>
     * <ul>
     * <li>Fixed focus (<code>{@link CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE android.lens.info.minimumFocusDistance} == 0</code>), which means
     * {@link CaptureRequest#LENS_FOCUS_DISTANCE android.lens.focusDistance} parameter will always be 0.</li>
     * <li>Fixed focal length ({@link CameraCharacteristics#LENS_INFO_AVAILABLE_FOCAL_LENGTHS android.lens.info.availableFocalLengths} contains single value),
     * which means the optical zoom is not supported.</li>
     * <li>No ND filter ({@link CameraCharacteristics#LENS_INFO_AVAILABLE_FILTER_DENSITIES android.lens.info.availableFilterDensities} contains only 0).</li>
     * <li>Fixed aperture ({@link CameraCharacteristics#LENS_INFO_AVAILABLE_APERTURES android.lens.info.availableApertures} contains single value).</li>
     * </ul>
     * <p>Then this state will always be STATIONARY.</p>
     * <p>When the state is MOVING, it indicates that at least one of the lens parameters
     * is changing.</p>
     *
     * @see CaptureRequest#LENS_APERTURE
     * @see CaptureRequest#LENS_FILTER_DENSITY
     * @see CaptureRequest#LENS_FOCAL_LENGTH
     * @see CaptureRequest#LENS_FOCUS_DISTANCE
     * @see CameraCharacteristics#LENS_INFO_AVAILABLE_APERTURES
     * @see CameraCharacteristics#LENS_INFO_AVAILABLE_FILTER_DENSITIES
     * @see CameraCharacteristics#LENS_INFO_AVAILABLE_FOCAL_LENGTHS
     * @see CameraCharacteristics#LENS_INFO_MINIMUM_FOCUS_DISTANCE
     * @see #LENS_STATE_STATIONARY
     * @see #LENS_STATE_MOVING
     */
    public static final Key<Integer> LENS_STATE =
            new Key<Integer>("android.lens.state", int.class);

    /**
     * <p>Mode of operation for the noise reduction
     * algorithm</p>
     * <p>Noise filtering control. OFF means no noise reduction
     * will be applied by the camera device.</p>
     * <p>This must be set to a valid mode in
     * {@link CameraCharacteristics#NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES android.noiseReduction.availableNoiseReductionModes}.</p>
     * <p>FAST/HIGH_QUALITY both mean camera device determined noise filtering
     * will be applied. HIGH_QUALITY mode indicates that the camera device
     * will use the highest-quality noise filtering algorithms,
     * even if it slows down capture rate. FAST means the camera device should not
     * slow down capture rate when applying noise filtering.</p>
     *
     * @see CameraCharacteristics#NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES
     * @see #NOISE_REDUCTION_MODE_OFF
     * @see #NOISE_REDUCTION_MODE_FAST
     * @see #NOISE_REDUCTION_MODE_HIGH_QUALITY
     */
    public static final Key<Integer> NOISE_REDUCTION_MODE =
            new Key<Integer>("android.noiseReduction.mode", int.class);

    /**
     * <p>Whether a result given to the framework is the
     * final one for the capture, or only a partial that contains a
     * subset of the full set of dynamic metadata
     * values.</p>
     * <p>The entries in the result metadata buffers for a
     * single capture may not overlap, except for this entry. The
     * FINAL buffers must retain FIFO ordering relative to the
     * requests that generate them, so the FINAL buffer for frame 3 must
     * always be sent to the framework after the FINAL buffer for frame 2, and
     * before the FINAL buffer for frame 4. PARTIAL buffers may be returned
     * in any order relative to other frames, but all PARTIAL buffers for a given
     * capture must arrive before the FINAL buffer for that capture. This entry may
     * only be used by the camera device if quirks.usePartialResult is set to 1.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * @hide
     */
    public static final Key<Boolean> QUIRKS_PARTIAL_RESULT =
            new Key<Boolean>("android.quirks.partialResult", boolean.class);

    /**
     * <p>A frame counter set by the framework. This value monotonically
     * increases with every new result (that is, each new result has a unique
     * frameCount value).</p>
     * <p>Reset on release()</p>
     */
    public static final Key<Integer> REQUEST_FRAME_COUNT =
            new Key<Integer>("android.request.frameCount", int.class);

    /**
     * <p>An application-specified ID for the current
     * request. Must be maintained unchanged in output
     * frame</p>
     * @hide
     */
    public static final Key<Integer> REQUEST_ID =
            new Key<Integer>("android.request.id", int.class);

    /**
     * <p>Specifies the number of pipeline stages the frame went
     * through from when it was exposed to when the final completed result
     * was available to the framework.</p>
     * <p>Depending on what settings are used in the request, and
     * what streams are configured, the data may undergo less processing,
     * and some pipeline stages skipped.</p>
     * <p>See {@link CameraCharacteristics#REQUEST_PIPELINE_MAX_DEPTH android.request.pipelineMaxDepth} for more details.</p>
     *
     * @see CameraCharacteristics#REQUEST_PIPELINE_MAX_DEPTH
     */
    public static final Key<Byte> REQUEST_PIPELINE_DEPTH =
            new Key<Byte>("android.request.pipelineDepth", byte.class);

    /**
     * <p>(x, y, width, height).</p>
     * <p>A rectangle with the top-level corner of (x,y) and size
     * (width, height). The region of the sensor that is used for
     * output. Each stream must use this rectangle to produce its
     * output, cropping to a smaller region if necessary to
     * maintain the stream's aspect ratio.</p>
     * <p>HAL2.x uses only (x, y, width)</p>
     * <p>Any additional per-stream cropping must be done to
     * maximize the final pixel area of the stream.</p>
     * <p>For example, if the crop region is set to a 4:3 aspect
     * ratio, then 4:3 streams should use the exact crop
     * region. 16:9 streams should further crop vertically
     * (letterbox).</p>
     * <p>Conversely, if the crop region is set to a 16:9, then 4:3
     * outputs should crop horizontally (pillarbox), and 16:9
     * streams should match exactly. These additional crops must
     * be centered within the crop region.</p>
     * <p>The output streams must maintain square pixels at all
     * times, no matter what the relative aspect ratios of the
     * crop region and the stream are.  Negative values for
     * corner are allowed for raw output if full pixel array is
     * larger than active pixel array. Width and height may be
     * rounded to nearest larger supportable width, especially
     * for raw output, where only a few fixed scales may be
     * possible. The width and height of the crop region cannot
     * be set to be smaller than floor( activeArraySize.width /
     * {@link CameraCharacteristics#SCALER_AVAILABLE_MAX_DIGITAL_ZOOM android.scaler.availableMaxDigitalZoom} ) and floor(
     * activeArraySize.height /
     * {@link CameraCharacteristics#SCALER_AVAILABLE_MAX_DIGITAL_ZOOM android.scaler.availableMaxDigitalZoom}), respectively.</p>
     *
     * @see CameraCharacteristics#SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
     */
    public static final Key<android.graphics.Rect> SCALER_CROP_REGION =
            new Key<android.graphics.Rect>("android.scaler.cropRegion", android.graphics.Rect.class);

    /**
     * <p>Duration each pixel is exposed to
     * light.</p>
     * <p>If the sensor can't expose this exact duration, it should shorten the
     * duration exposed to the nearest possible value (rather than expose longer).</p>
     * <p>1/10000 - 30 sec range. No bulb mode</p>
     */
    public static final Key<Long> SENSOR_EXPOSURE_TIME =
            new Key<Long>("android.sensor.exposureTime", long.class);

    /**
     * <p>Duration from start of frame exposure to
     * start of next frame exposure.</p>
     * <p>The maximum frame rate that can be supported by a camera subsystem is
     * a function of many factors:</p>
     * <ul>
     * <li>Requested resolutions of output image streams</li>
     * <li>Availability of binning / skipping modes on the imager</li>
     * <li>The bandwidth of the imager interface</li>
     * <li>The bandwidth of the various ISP processing blocks</li>
     * </ul>
     * <p>Since these factors can vary greatly between different ISPs and
     * sensors, the camera abstraction tries to represent the bandwidth
     * restrictions with as simple a model as possible.</p>
     * <p>The model presented has the following characteristics:</p>
     * <ul>
     * <li>The image sensor is always configured to output the smallest
     * resolution possible given the application's requested output stream
     * sizes.  The smallest resolution is defined as being at least as large
     * as the largest requested output stream size; the camera pipeline must
     * never digitally upsample sensor data when the crop region covers the
     * whole sensor. In general, this means that if only small output stream
     * resolutions are configured, the sensor can provide a higher frame
     * rate.</li>
     * <li>Since any request may use any or all the currently configured
     * output streams, the sensor and ISP must be configured to support
     * scaling a single capture to all the streams at the same time.  This
     * means the camera pipeline must be ready to produce the largest
     * requested output size without any delay.  Therefore, the overall
     * frame rate of a given configured stream set is governed only by the
     * largest requested stream resolution.</li>
     * <li>Using more than one output stream in a request does not affect the
     * frame duration.</li>
     * <li>Certain format-streams may need to do additional background processing
     * before data is consumed/produced by that stream. These processors
     * can run concurrently to the rest of the camera pipeline, but
     * cannot process more than 1 capture at a time.</li>
     * </ul>
     * <p>The necessary information for the application, given the model above,
     * is provided via the {@link CameraCharacteristics#SCALER_AVAILABLE_MIN_FRAME_DURATIONS android.scaler.availableMinFrameDurations} field.
     * These are used to determine the maximum frame rate / minimum frame
     * duration that is possible for a given stream configuration.</p>
     * <p>Specifically, the application can use the following rules to
     * determine the minimum frame duration it can request from the camera
     * device:</p>
     * <ol>
     * <li>Let the set of currently configured input/output streams
     * be called <code>S</code>.</li>
     * <li>Find the minimum frame durations for each stream in <code>S</code>, by
     * looking it up in {@link CameraCharacteristics#SCALER_AVAILABLE_MIN_FRAME_DURATIONS android.scaler.availableMinFrameDurations} (with
     * its respective size/format). Let this set of frame durations be called
     * <code>F</code>.</li>
     * <li>For any given request <code>R</code>, the minimum frame duration allowed
     * for <code>R</code> is the maximum out of all values in <code>F</code>. Let the streams
     * used in <code>R</code> be called <code>S_r</code>.</li>
     * </ol>
     * <p>If none of the streams in <code>S_r</code> have a stall time (listed in
     * {@link CameraCharacteristics#SCALER_AVAILABLE_STALL_DURATIONS android.scaler.availableStallDurations}), then the frame duration in
     * <code>F</code> determines the steady state frame rate that the application will
     * get if it uses <code>R</code> as a repeating request. Let this special kind
     * of request be called <code>Rsimple</code>.</p>
     * <p>A repeating request <code>Rsimple</code> can be <em>occasionally</em> interleaved
     * by a single capture of a new request <code>Rstall</code> (which has at least
     * one in-use stream with a non-0 stall time) and if <code>Rstall</code> has the
     * same minimum frame duration this will not cause a frame rate loss
     * if all buffers from the previous <code>Rstall</code> have already been
     * delivered.</p>
     * <p>For more details about stalling, see
     * {@link CameraCharacteristics#SCALER_AVAILABLE_STALL_DURATIONS android.scaler.availableStallDurations}.</p>
     *
     * @see CameraCharacteristics#SCALER_AVAILABLE_MIN_FRAME_DURATIONS
     * @see CameraCharacteristics#SCALER_AVAILABLE_STALL_DURATIONS
     */
    public static final Key<Long> SENSOR_FRAME_DURATION =
            new Key<Long>("android.sensor.frameDuration", long.class);

    /**
     * <p>Gain applied to image data. Must be
     * implemented through analog gain only if set to values
     * below 'maximum analog sensitivity'.</p>
     * <p>If the sensor can't apply this exact gain, it should lessen the
     * gain to the nearest possible value (rather than gain more).</p>
     * <p>ISO 12232:2006 REI method</p>
     */
    public static final Key<Integer> SENSOR_SENSITIVITY =
            new Key<Integer>("android.sensor.sensitivity", int.class);

    /**
     * <p>Time at start of exposure of first
     * row</p>
     * <p>Monotonic, should be synced to other timestamps in
     * system</p>
     */
    public static final Key<Long> SENSOR_TIMESTAMP =
            new Key<Long>("android.sensor.timestamp", long.class);

    /**
     * <p>The temperature of the sensor, sampled at the time
     * exposure began for this frame.</p>
     * <p>The thermal diode being queried should be inside the sensor PCB, or
     * somewhere close to it.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * <p><b>Full capability</b> -
     * Present on all camera devices that report being {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL_FULL HARDWARE_LEVEL_FULL} devices in the
     * {@link CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL android.info.supportedHardwareLevel} key</p>
     *
     * @see CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL
     */
    public static final Key<Float> SENSOR_TEMPERATURE =
            new Key<Float>("android.sensor.temperature", float.class);

    /**
     * <p>A reference illumination source roughly matching the current scene
     * illumination, which is used to describe the sensor color space
     * transformations.</p>
     * <p>The values in this tag correspond to the values defined for the
     * EXIF LightSource tag. These illuminants are standard light sources
     * that are often used for calibrating camera devices.</p>
     * @see #SENSOR_REFERENCE_ILLUMINANT_DAYLIGHT
     * @see #SENSOR_REFERENCE_ILLUMINANT_FLUORESCENT
     * @see #SENSOR_REFERENCE_ILLUMINANT_TUNGSTEN
     * @see #SENSOR_REFERENCE_ILLUMINANT_FLASH
     * @see #SENSOR_REFERENCE_ILLUMINANT_FINE_WEATHER
     * @see #SENSOR_REFERENCE_ILLUMINANT_CLOUDY_WEATHER
     * @see #SENSOR_REFERENCE_ILLUMINANT_SHADE
     * @see #SENSOR_REFERENCE_ILLUMINANT_DAYLIGHT_FLUORESCENT
     * @see #SENSOR_REFERENCE_ILLUMINANT_DAY_WHITE_FLUORESCENT
     * @see #SENSOR_REFERENCE_ILLUMINANT_COOL_WHITE_FLUORESCENT
     * @see #SENSOR_REFERENCE_ILLUMINANT_WHITE_FLUORESCENT
     * @see #SENSOR_REFERENCE_ILLUMINANT_STANDARD_A
     * @see #SENSOR_REFERENCE_ILLUMINANT_STANDARD_B
     * @see #SENSOR_REFERENCE_ILLUMINANT_STANDARD_C
     * @see #SENSOR_REFERENCE_ILLUMINANT_D55
     * @see #SENSOR_REFERENCE_ILLUMINANT_D65
     * @see #SENSOR_REFERENCE_ILLUMINANT_D75
     * @see #SENSOR_REFERENCE_ILLUMINANT_D50
     * @see #SENSOR_REFERENCE_ILLUMINANT_ISO_STUDIO_TUNGSTEN
     */
    public static final Key<Integer> SENSOR_REFERENCE_ILLUMINANT =
            new Key<Integer>("android.sensor.referenceIlluminant", int.class);

    /**
     * <p>A per-device calibration transform matrix to be applied after the
     * color space transform when rendering the raw image buffer.</p>
     * <p>This matrix is expressed as a 3x3 matrix in row-major-order, and
     * contains a per-device calibration transform that maps colors
     * from reference camera color space (i.e. the "golden module"
     * colorspace) into this camera device's linear native sensor color
     * space for the current scene illumination and white balance choice.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     */
    public static final Key<Rational[]> SENSOR_CALIBRATION_TRANSFORM =
            new Key<Rational[]>("android.sensor.calibrationTransform", Rational[].class);

    /**
     * <p>A matrix that transforms color values from CIE XYZ color space to
     * reference camera color space when rendering the raw image buffer.</p>
     * <p>This matrix is expressed as a 3x3 matrix in row-major-order, and
     * contains a color transform matrix that maps colors from the CIE
     * XYZ color space to the reference camera raw color space (i.e. the
     * "golden module" colorspace) for the current scene illumination and
     * white balance choice.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     */
    public static final Key<Rational[]> SENSOR_COLOR_TRANSFORM =
            new Key<Rational[]>("android.sensor.colorTransform", Rational[].class);

    /**
     * <p>A matrix that transforms white balanced camera colors to the CIE XYZ
     * colorspace with a D50 whitepoint.</p>
     * <p>This matrix is expressed as a 3x3 matrix in row-major-order, and contains
     * a color transform matrix that maps a unit vector in the linear native
     * sensor color space to the D50 whitepoint in CIE XYZ color space.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     */
    public static final Key<Rational[]> SENSOR_FORWARD_MATRIX =
            new Key<Rational[]>("android.sensor.forwardMatrix", Rational[].class);

    /**
     * <p>The estimated white balance at the time of capture.</p>
     * <p>The estimated white balance encoded as the RGB values of the
     * perfectly neutral color point in the linear native sensor color space.
     * The order of the values is R, G, B; where R is in the lowest index.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     */
    public static final Key<Rational[]> SENSOR_NEUTRAL_COLOR_POINT =
            new Key<Rational[]>("android.sensor.neutralColorPoint", Rational[].class);

    /**
     * <p>A mapping containing a hue shift, saturation scale, and value scale
     * for each pixel.</p>
     * <p>hue_samples, saturation_samples, and value_samples are given in
     * {@link CameraCharacteristics#SENSOR_PROFILE_HUE_SAT_MAP_DIMENSIONS android.sensor.profileHueSatMapDimensions}.</p>
     * <p>Each entry of this map contains three floats corresponding to the
     * hue shift, saturation scale, and value scale, respectively; where the
     * hue shift has the lowest index. The map entries are stored in the tag
     * in nested loop order, with the value divisions in the outer loop, the
     * hue divisions in the middle loop, and the saturation divisions in the
     * inner loop. All zero input saturation entries are required to have a
     * value scale factor of 1.0.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CameraCharacteristics#SENSOR_PROFILE_HUE_SAT_MAP_DIMENSIONS
     */
    public static final Key<float[]> SENSOR_PROFILE_HUE_SAT_MAP =
            new Key<float[]>("android.sensor.profileHueSatMap", float[].class);

    /**
     * <p>A list of x,y samples defining a tone-mapping curve for gamma adjustment.</p>
     * <p>This tag contains a default tone curve that can be applied while
     * processing the image as a starting point for user adjustments.
     * The curve is specified as a list of value pairs in linear gamma.
     * The curve is interpolated using a cubic spline.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     */
    public static final Key<float[]> SENSOR_PROFILE_TONE_CURVE =
            new Key<float[]>("android.sensor.profileToneCurve", float[].class);

    /**
     * <p>The worst-case divergence between Bayer green channels.</p>
     * <p>This value is an estimate of the worst case split between the
     * Bayer green channels in the red and blue rows in the sensor color
     * filter array.</p>
     * <p>The green split is calculated as follows:</p>
     * <ol>
     * <li>A 5x5 pixel (or larger) window W within the active sensor array is
     * chosen. The term 'pixel' here is taken to mean a group of 4 Bayer
     * mosaic channels (R, Gr, Gb, B).  The location and size of the window
     * chosen is implementation defined, and should be chosen to provide a
     * green split estimate that is both representative of the entire image
     * for this camera sensor, and can be calculated quickly.</li>
     * <li>The arithmetic mean of the green channels from the red
     * rows (mean_Gr) within W is computed.</li>
     * <li>The arithmetic mean of the green channels from the blue
     * rows (mean_Gb) within W is computed.</li>
     * <li>The maximum ratio R of the two means is computed as follows:
     * <code>R = max((mean_Gr + 1)/(mean_Gb + 1), (mean_Gb + 1)/(mean_Gr + 1))</code></li>
     * </ol>
     * <p>The ratio R is the green split divergence reported for this property,
     * which represents how much the green channels differ in the mosaic
     * pattern.  This value is typically used to determine the treatment of
     * the green mosaic channels when demosaicing.</p>
     * <p>The green split value can be roughly interpreted as follows:</p>
     * <ul>
     * <li>R &lt; 1.03 is a negligible split (&lt;3% divergence).</li>
     * <li>1.20 &lt;= R &gt;= 1.03 will require some software
     * correction to avoid demosaic errors (3-20% divergence).</li>
     * <li>R &gt; 1.20 will require strong software correction to produce
     * a usuable image (&gt;20% divergence).</li>
     * </ul>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     */
    public static final Key<Float> SENSOR_GREEN_SPLIT =
            new Key<Float>("android.sensor.greenSplit", float.class);

    /**
     * <p>When enabled, the sensor sends a test pattern instead of
     * doing a real exposure from the camera.</p>
     * <p>When a test pattern is enabled, all manual sensor controls specified
     * by android.sensor.* should be ignored. All other controls should
     * work as normal.</p>
     * <p>For example, if manual flash is enabled, flash firing should still
     * occur (and that the test pattern remain unmodified, since the flash
     * would not actually affect it).</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * @see #SENSOR_TEST_PATTERN_MODE_OFF
     * @see #SENSOR_TEST_PATTERN_MODE_SOLID_COLOR
     * @see #SENSOR_TEST_PATTERN_MODE_COLOR_BARS
     * @see #SENSOR_TEST_PATTERN_MODE_COLOR_BARS_FADE_TO_GRAY
     * @see #SENSOR_TEST_PATTERN_MODE_PN9
     * @see #SENSOR_TEST_PATTERN_MODE_CUSTOM1
     */
    public static final Key<Integer> SENSOR_TEST_PATTERN_MODE =
            new Key<Integer>("android.sensor.testPatternMode", int.class);

    /**
     * <p>Quality of lens shading correction applied
     * to the image data.</p>
     * <p>When set to OFF mode, no lens shading correction will be applied by the
     * camera device, and an identity lens shading map data will be provided
     * if <code>{@link CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE android.statistics.lensShadingMapMode} == ON</code>. For example, for lens
     * shading map with size specified as <code>{@link CameraCharacteristics#LENS_INFO_SHADING_MAP_SIZE android.lens.info.shadingMapSize} = [ 4, 3 ]</code>,
     * the output {@link CaptureResult#STATISTICS_LENS_SHADING_MAP android.statistics.lensShadingMap} for this case will be an identity map
     * shown below:</p>
     * <pre><code>[ 1.0, 1.0, 1.0, 1.0,  1.0, 1.0, 1.0, 1.0,
     * 1.0, 1.0, 1.0, 1.0,  1.0, 1.0, 1.0, 1.0,
     * 1.0, 1.0, 1.0, 1.0,  1.0, 1.0, 1.0, 1.0,
     * 1.0, 1.0, 1.0, 1.0,  1.0, 1.0, 1.0, 1.0,
     * 1.0, 1.0, 1.0, 1.0,   1.0, 1.0, 1.0, 1.0,
     * 1.0, 1.0, 1.0, 1.0,  1.0, 1.0, 1.0, 1.0 ]
     * </code></pre>
     * <p>When set to other modes, lens shading correction will be applied by the
     * camera device. Applications can request lens shading map data by setting
     * {@link CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE android.statistics.lensShadingMapMode} to ON, and then the camera device will provide
     * lens shading map data in {@link CaptureResult#STATISTICS_LENS_SHADING_MAP android.statistics.lensShadingMap}, with size specified
     * by {@link CameraCharacteristics#LENS_INFO_SHADING_MAP_SIZE android.lens.info.shadingMapSize}.</p>
     *
     * @see CameraCharacteristics#LENS_INFO_SHADING_MAP_SIZE
     * @see CaptureResult#STATISTICS_LENS_SHADING_MAP
     * @see CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE
     * @see #SHADING_MODE_OFF
     * @see #SHADING_MODE_FAST
     * @see #SHADING_MODE_HIGH_QUALITY
     */
    public static final Key<Integer> SHADING_MODE =
            new Key<Integer>("android.shading.mode", int.class);

    /**
     * <p>State of the face detector
     * unit</p>
     * <p>Whether face detection is enabled, and whether it
     * should output just the basic fields or the full set of
     * fields. Value must be one of the
     * {@link CameraCharacteristics#STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES android.statistics.info.availableFaceDetectModes}.</p>
     *
     * @see CameraCharacteristics#STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES
     * @see #STATISTICS_FACE_DETECT_MODE_OFF
     * @see #STATISTICS_FACE_DETECT_MODE_SIMPLE
     * @see #STATISTICS_FACE_DETECT_MODE_FULL
     */
    public static final Key<Integer> STATISTICS_FACE_DETECT_MODE =
            new Key<Integer>("android.statistics.faceDetectMode", int.class);

    /**
     * <p>List of unique IDs for detected
     * faces</p>
     * <p>Only available if faceDetectMode == FULL</p>
     * @hide
     */
    public static final Key<int[]> STATISTICS_FACE_IDS =
            new Key<int[]>("android.statistics.faceIds", int[].class);

    /**
     * <p>List of landmarks for detected
     * faces</p>
     * <p>Only available if faceDetectMode == FULL</p>
     * @hide
     */
    public static final Key<int[]> STATISTICS_FACE_LANDMARKS =
            new Key<int[]>("android.statistics.faceLandmarks", int[].class);

    /**
     * <p>List of the bounding rectangles for detected
     * faces</p>
     * <p>Only available if faceDetectMode != OFF</p>
     * @hide
     */
    public static final Key<android.graphics.Rect[]> STATISTICS_FACE_RECTANGLES =
            new Key<android.graphics.Rect[]>("android.statistics.faceRectangles", android.graphics.Rect[].class);

    /**
     * <p>List of the face confidence scores for
     * detected faces</p>
     * <p>Only available if faceDetectMode != OFF. The value should be
     * meaningful (for example, setting 100 at all times is illegal).</p>
     * @hide
     */
    public static final Key<byte[]> STATISTICS_FACE_SCORES =
            new Key<byte[]>("android.statistics.faceScores", byte[].class);

    /**
     * <p>The shading map is a low-resolution floating-point map
     * that lists the coefficients used to correct for vignetting, for each
     * Bayer color channel.</p>
     * <p>The least shaded section of the image should have a gain factor
     * of 1; all other sections should have gains above 1.</p>
     * <p>When {@link CaptureRequest#COLOR_CORRECTION_MODE android.colorCorrection.mode} = TRANSFORM_MATRIX, the map
     * must take into account the colorCorrection settings.</p>
     * <p>The shading map is for the entire active pixel array, and is not
     * affected by the crop region specified in the request. Each shading map
     * entry is the value of the shading compensation map over a specific
     * pixel on the sensor.  Specifically, with a (N x M) resolution shading
     * map, and an active pixel array size (W x H), shading map entry
     * (x,y) ϵ (0 ... N-1, 0 ... M-1) is the value of the shading map at
     * pixel ( ((W-1)/(N-1)) * x, ((H-1)/(M-1)) * y) for the four color channels.
     * The map is assumed to be bilinearly interpolated between the sample points.</p>
     * <p>The channel order is [R, Geven, Godd, B], where Geven is the green
     * channel for the even rows of a Bayer pattern, and Godd is the odd rows.
     * The shading map is stored in a fully interleaved format, and its size
     * is provided in the camera static metadata by {@link CameraCharacteristics#LENS_INFO_SHADING_MAP_SIZE android.lens.info.shadingMapSize}.</p>
     * <p>The shading map should have on the order of 30-40 rows and columns,
     * and must be smaller than 64x64.</p>
     * <p>As an example, given a very small map defined as:</p>
     * <pre><code>{@link CameraCharacteristics#LENS_INFO_SHADING_MAP_SIZE android.lens.info.shadingMapSize} = [ 4, 3 ]
     * {@link CaptureResult#STATISTICS_LENS_SHADING_MAP android.statistics.lensShadingMap} =
     * [ 1.3, 1.2, 1.15, 1.2,  1.2, 1.2, 1.15, 1.2,
     * 1.1, 1.2, 1.2, 1.2,  1.3, 1.2, 1.3, 1.3,
     * 1.2, 1.2, 1.25, 1.1,  1.1, 1.1, 1.1, 1.0,
     * 1.0, 1.0, 1.0, 1.0,  1.2, 1.3, 1.25, 1.2,
     * 1.3, 1.2, 1.2, 1.3,   1.2, 1.15, 1.1, 1.2,
     * 1.2, 1.1, 1.0, 1.2,  1.3, 1.15, 1.2, 1.3 ]
     * </code></pre>
     * <p>The low-resolution scaling map images for each channel are
     * (displayed using nearest-neighbor interpolation):</p>
     * <p><img alt="Red lens shading map" src="../../../../images/camera2/metadata/android.statistics.lensShadingMap/red_shading.png" />
     * <img alt="Green (even rows) lens shading map" src="../../../../images/camera2/metadata/android.statistics.lensShadingMap/green_e_shading.png" />
     * <img alt="Green (odd rows) lens shading map" src="../../../../images/camera2/metadata/android.statistics.lensShadingMap/green_o_shading.png" />
     * <img alt="Blue lens shading map" src="../../../../images/camera2/metadata/android.statistics.lensShadingMap/blue_shading.png" /></p>
     * <p>As a visualization only, inverting the full-color map to recover an
     * image of a gray wall (using bicubic interpolation for visual quality) as captured by the sensor gives:</p>
     * <p><img alt="Image of a uniform white wall (inverse shading map)" src="../../../../images/camera2/metadata/android.statistics.lensShadingMap/inv_shading.png" /></p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_MODE
     * @see CameraCharacteristics#LENS_INFO_SHADING_MAP_SIZE
     * @see CaptureResult#STATISTICS_LENS_SHADING_MAP
     */
    public static final Key<float[]> STATISTICS_LENS_SHADING_MAP =
            new Key<float[]>("android.statistics.lensShadingMap", float[].class);

    /**
     * <p>The best-fit color channel gains calculated
     * by the camera device's statistics units for the current output frame.</p>
     * <p>This may be different than the gains used for this frame,
     * since statistics processing on data from a new frame
     * typically completes after the transform has already been
     * applied to that frame.</p>
     * <p>The 4 channel gains are defined in Bayer domain,
     * see {@link CaptureRequest#COLOR_CORRECTION_GAINS android.colorCorrection.gains} for details.</p>
     * <p>This value should always be calculated by the AWB block,
     * regardless of the android.control.* current values.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#COLOR_CORRECTION_GAINS
     * @hide
     */
    public static final Key<float[]> STATISTICS_PREDICTED_COLOR_GAINS =
            new Key<float[]>("android.statistics.predictedColorGains", float[].class);

    /**
     * <p>The best-fit color transform matrix estimate
     * calculated by the camera device's statistics units for the current
     * output frame.</p>
     * <p>The camera device will provide the estimate from its
     * statistics unit on the white balance transforms to use
     * for the next frame. These are the values the camera device believes
     * are the best fit for the current output frame. This may
     * be different than the transform used for this frame, since
     * statistics processing on data from a new frame typically
     * completes after the transform has already been applied to
     * that frame.</p>
     * <p>These estimates must be provided for all frames, even if
     * capture settings and color transforms are set by the application.</p>
     * <p>This value should always be calculated by the AWB block,
     * regardless of the android.control.* current values.</p>
     * <p><b>Optional</b> - This value may be {@code null} on some devices.</p>
     * @hide
     */
    public static final Key<Rational[]> STATISTICS_PREDICTED_COLOR_TRANSFORM =
            new Key<Rational[]>("android.statistics.predictedColorTransform", Rational[].class);

    /**
     * <p>The camera device estimated scene illumination lighting
     * frequency.</p>
     * <p>Many light sources, such as most fluorescent lights, flicker at a rate
     * that depends on the local utility power standards. This flicker must be
     * accounted for by auto-exposure routines to avoid artifacts in captured images.
     * The camera device uses this entry to tell the application what the scene
     * illuminant frequency is.</p>
     * <p>When manual exposure control is enabled
     * (<code>{@link CaptureRequest#CONTROL_AE_MODE android.control.aeMode} == OFF</code> or <code>{@link CaptureRequest#CONTROL_MODE android.control.mode} == OFF</code>),
     * the {@link CaptureRequest#CONTROL_AE_ANTIBANDING_MODE android.control.aeAntibandingMode} doesn't do the antibanding, and the
     * application can ensure it selects exposure times that do not cause banding
     * issues by looking into this metadata field. See {@link CaptureRequest#CONTROL_AE_ANTIBANDING_MODE android.control.aeAntibandingMode}
     * for more details.</p>
     * <p>Report NONE if there doesn't appear to be flickering illumination.</p>
     *
     * @see CaptureRequest#CONTROL_AE_ANTIBANDING_MODE
     * @see CaptureRequest#CONTROL_AE_MODE
     * @see CaptureRequest#CONTROL_MODE
     * @see #STATISTICS_SCENE_FLICKER_NONE
     * @see #STATISTICS_SCENE_FLICKER_50HZ
     * @see #STATISTICS_SCENE_FLICKER_60HZ
     */
    public static final Key<Integer> STATISTICS_SCENE_FLICKER =
            new Key<Integer>("android.statistics.sceneFlicker", int.class);

    /**
     * <p>Operating mode for hotpixel map generation.</p>
     * <p>If set to ON, a hotpixel map is returned in {@link CaptureResult#STATISTICS_HOT_PIXEL_MAP android.statistics.hotPixelMap}.
     * If set to OFF, no hotpixel map should be returned.</p>
     * <p>This must be set to a valid mode from {@link CameraCharacteristics#STATISTICS_INFO_AVAILABLE_HOT_PIXEL_MAP_MODES android.statistics.info.availableHotPixelMapModes}.</p>
     *
     * @see CaptureResult#STATISTICS_HOT_PIXEL_MAP
     * @see CameraCharacteristics#STATISTICS_INFO_AVAILABLE_HOT_PIXEL_MAP_MODES
     */
    public static final Key<Boolean> STATISTICS_HOT_PIXEL_MAP_MODE =
            new Key<Boolean>("android.statistics.hotPixelMapMode", boolean.class);

    /**
     * <p>List of <code>(x, y)</code> coordinates of hot/defective pixels on the sensor.</p>
     * <p>A coordinate <code>(x, y)</code> must lie between <code>(0, 0)</code>, and
     * <code>(width - 1, height - 1)</code> (inclusive), which are the top-left and
     * bottom-right of the pixel array, respectively. The width and
     * height dimensions are given in {@link CameraCharacteristics#SENSOR_INFO_PIXEL_ARRAY_SIZE android.sensor.info.pixelArraySize}.
     * This may include hot pixels that lie outside of the active array
     * bounds given by {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE android.sensor.info.activeArraySize}.</p>
     *
     * @see CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE
     * @see CameraCharacteristics#SENSOR_INFO_PIXEL_ARRAY_SIZE
     */
    public static final Key<int[]> STATISTICS_HOT_PIXEL_MAP =
            new Key<int[]>("android.statistics.hotPixelMap", int[].class);

    /**
     * <p>Tonemapping / contrast / gamma curve for the blue
     * channel, to use when {@link CaptureRequest#TONEMAP_MODE android.tonemap.mode} is
     * CONTRAST_CURVE.</p>
     * <p>See {@link CaptureRequest#TONEMAP_CURVE_RED android.tonemap.curveRed} for more details.</p>
     *
     * @see CaptureRequest#TONEMAP_CURVE_RED
     * @see CaptureRequest#TONEMAP_MODE
     */
    public static final Key<float[]> TONEMAP_CURVE_BLUE =
            new Key<float[]>("android.tonemap.curveBlue", float[].class);

    /**
     * <p>Tonemapping / contrast / gamma curve for the green
     * channel, to use when {@link CaptureRequest#TONEMAP_MODE android.tonemap.mode} is
     * CONTRAST_CURVE.</p>
     * <p>See {@link CaptureRequest#TONEMAP_CURVE_RED android.tonemap.curveRed} for more details.</p>
     *
     * @see CaptureRequest#TONEMAP_CURVE_RED
     * @see CaptureRequest#TONEMAP_MODE
     */
    public static final Key<float[]> TONEMAP_CURVE_GREEN =
            new Key<float[]>("android.tonemap.curveGreen", float[].class);

    /**
     * <p>Tonemapping / contrast / gamma curve for the red
     * channel, to use when {@link CaptureRequest#TONEMAP_MODE android.tonemap.mode} is
     * CONTRAST_CURVE.</p>
     * <p>Each channel's curve is defined by an array of control points:</p>
     * <pre><code>{@link CaptureRequest#TONEMAP_CURVE_RED android.tonemap.curveRed} =
     * [ P0in, P0out, P1in, P1out, P2in, P2out, P3in, P3out, ..., PNin, PNout ]
     * 2 &lt;= N &lt;= {@link CameraCharacteristics#TONEMAP_MAX_CURVE_POINTS android.tonemap.maxCurvePoints}</code></pre>
     * <p>These are sorted in order of increasing <code>Pin</code>; it is always
     * guaranteed that input values 0.0 and 1.0 are included in the list to
     * define a complete mapping. For input values between control points,
     * the camera device must linearly interpolate between the control
     * points.</p>
     * <p>Each curve can have an independent number of points, and the number
     * of points can be less than max (that is, the request doesn't have to
     * always provide a curve with number of points equivalent to
     * {@link CameraCharacteristics#TONEMAP_MAX_CURVE_POINTS android.tonemap.maxCurvePoints}).</p>
     * <p>A few examples, and their corresponding graphical mappings; these
     * only specify the red channel and the precision is limited to 4
     * digits, for conciseness.</p>
     * <p>Linear mapping:</p>
     * <pre><code>{@link CaptureRequest#TONEMAP_CURVE_RED android.tonemap.curveRed} = [ 0, 0, 1.0, 1.0 ]
     * </code></pre>
     * <p><img alt="Linear mapping curve" src="../../../../images/camera2/metadata/android.tonemap.curveRed/linear_tonemap.png" /></p>
     * <p>Invert mapping:</p>
     * <pre><code>{@link CaptureRequest#TONEMAP_CURVE_RED android.tonemap.curveRed} = [ 0, 1.0, 1.0, 0 ]
     * </code></pre>
     * <p><img alt="Inverting mapping curve" src="../../../../images/camera2/metadata/android.tonemap.curveRed/inverse_tonemap.png" /></p>
     * <p>Gamma 1/2.2 mapping, with 16 control points:</p>
     * <pre><code>{@link CaptureRequest#TONEMAP_CURVE_RED android.tonemap.curveRed} = [
     * 0.0000, 0.0000, 0.0667, 0.2920, 0.1333, 0.4002, 0.2000, 0.4812,
     * 0.2667, 0.5484, 0.3333, 0.6069, 0.4000, 0.6594, 0.4667, 0.7072,
     * 0.5333, 0.7515, 0.6000, 0.7928, 0.6667, 0.8317, 0.7333, 0.8685,
     * 0.8000, 0.9035, 0.8667, 0.9370, 0.9333, 0.9691, 1.0000, 1.0000 ]
     * </code></pre>
     * <p><img alt="Gamma = 1/2.2 tonemapping curve" src="../../../../images/camera2/metadata/android.tonemap.curveRed/gamma_tonemap.png" /></p>
     * <p>Standard sRGB gamma mapping, per IEC 61966-2-1:1999, with 16 control points:</p>
     * <pre><code>{@link CaptureRequest#TONEMAP_CURVE_RED android.tonemap.curveRed} = [
     * 0.0000, 0.0000, 0.0667, 0.2864, 0.1333, 0.4007, 0.2000, 0.4845,
     * 0.2667, 0.5532, 0.3333, 0.6125, 0.4000, 0.6652, 0.4667, 0.7130,
     * 0.5333, 0.7569, 0.6000, 0.7977, 0.6667, 0.8360, 0.7333, 0.8721,
     * 0.8000, 0.9063, 0.8667, 0.9389, 0.9333, 0.9701, 1.0000, 1.0000 ]
     * </code></pre>
     * <p><img alt="sRGB tonemapping curve" src="../../../../images/camera2/metadata/android.tonemap.curveRed/srgb_tonemap.png" /></p>
     *
     * @see CaptureRequest#TONEMAP_CURVE_RED
     * @see CameraCharacteristics#TONEMAP_MAX_CURVE_POINTS
     * @see CaptureRequest#TONEMAP_MODE
     */
    public static final Key<float[]> TONEMAP_CURVE_RED =
            new Key<float[]>("android.tonemap.curveRed", float[].class);

    /**
     * <p>High-level global contrast/gamma/tonemapping control.</p>
     * <p>When switching to an application-defined contrast curve by setting
     * {@link CaptureRequest#TONEMAP_MODE android.tonemap.mode} to CONTRAST_CURVE, the curve is defined
     * per-channel with a set of <code>(in, out)</code> points that specify the
     * mapping from input high-bit-depth pixel value to the output
     * low-bit-depth value.  Since the actual pixel ranges of both input
     * and output may change depending on the camera pipeline, the values
     * are specified by normalized floating-point numbers.</p>
     * <p>More-complex color mapping operations such as 3D color look-up
     * tables, selective chroma enhancement, or other non-linear color
     * transforms will be disabled when {@link CaptureRequest#TONEMAP_MODE android.tonemap.mode} is
     * CONTRAST_CURVE.</p>
     * <p>This must be set to a valid mode in
     * {@link CameraCharacteristics#TONEMAP_AVAILABLE_TONE_MAP_MODES android.tonemap.availableToneMapModes}.</p>
     * <p>When using either FAST or HIGH_QUALITY, the camera device will
     * emit its own tonemap curve in {@link CaptureRequest#TONEMAP_CURVE_RED android.tonemap.curveRed},
     * {@link CaptureRequest#TONEMAP_CURVE_GREEN android.tonemap.curveGreen}, and {@link CaptureRequest#TONEMAP_CURVE_BLUE android.tonemap.curveBlue}.
     * These values are always available, and as close as possible to the
     * actually used nonlinear/nonglobal transforms.</p>
     * <p>If a request is sent with TRANSFORM_MATRIX with the camera device's
     * provided curve in FAST or HIGH_QUALITY, the image's tonemap will be
     * roughly the same.</p>
     *
     * @see CameraCharacteristics#TONEMAP_AVAILABLE_TONE_MAP_MODES
     * @see CaptureRequest#TONEMAP_CURVE_BLUE
     * @see CaptureRequest#TONEMAP_CURVE_GREEN
     * @see CaptureRequest#TONEMAP_CURVE_RED
     * @see CaptureRequest#TONEMAP_MODE
     * @see #TONEMAP_MODE_CONTRAST_CURVE
     * @see #TONEMAP_MODE_FAST
     * @see #TONEMAP_MODE_HIGH_QUALITY
     */
    public static final Key<Integer> TONEMAP_MODE =
            new Key<Integer>("android.tonemap.mode", int.class);

    /**
     * <p>This LED is nominally used to indicate to the user
     * that the camera is powered on and may be streaming images back to the
     * Application Processor. In certain rare circumstances, the OS may
     * disable this when video is processed locally and not transmitted to
     * any untrusted applications.</p>
     * <p>In particular, the LED <em>must</em> always be on when the data could be
     * transmitted off the device. The LED <em>should</em> always be on whenever
     * data is stored locally on the device.</p>
     * <p>The LED <em>may</em> be off if a trusted application is using the data that
     * doesn't violate the above rules.</p>
     * @hide
     */
    public static final Key<Boolean> LED_TRANSMIT =
            new Key<Boolean>("android.led.transmit", boolean.class);

    /**
     * <p>Whether black-level compensation is locked
     * to its current values, or is free to vary.</p>
     * <p>Whether the black level offset was locked for this frame.  Should be
     * ON if {@link CaptureRequest#BLACK_LEVEL_LOCK android.blackLevel.lock} was ON in the capture request, unless
     * a change in other capture settings forced the camera device to
     * perform a black level reset.</p>
     *
     * @see CaptureRequest#BLACK_LEVEL_LOCK
     */
    public static final Key<Boolean> BLACK_LEVEL_LOCK =
            new Key<Boolean>("android.blackLevel.lock", boolean.class);

    /**
     * <p>The frame number corresponding to the last request
     * with which the output result (metadata + buffers) has been fully
     * synchronized.</p>
     * <p>When a request is submitted to the camera device, there is usually a
     * delay of several frames before the controls get applied. A camera
     * device may either choose to account for this delay by implementing a
     * pipeline and carefully submit well-timed atomic control updates, or
     * it may start streaming control changes that span over several frame
     * boundaries.</p>
     * <p>In the latter case, whenever a request's settings change relative to
     * the previous submitted request, the full set of changes may take
     * multiple frame durations to fully take effect. Some settings may
     * take effect sooner (in less frame durations) than others.</p>
     * <p>While a set of control changes are being propagated, this value
     * will be CONVERGING.</p>
     * <p>Once it is fully known that a set of control changes have been
     * finished propagating, and the resulting updated control settings
     * have been read back by the camera device, this value will be set
     * to a non-negative frame number (corresponding to the request to
     * which the results have synchronized to).</p>
     * <p>Older camera device implementations may not have a way to detect
     * when all camera controls have been applied, and will always set this
     * value to UNKNOWN.</p>
     * <p>FULL capability devices will always have this value set to the
     * frame number of the request corresponding to this result.</p>
     * <p><em>Further details</em>:</p>
     * <ul>
     * <li>Whenever a request differs from the last request, any future
     * results not yet returned may have this value set to CONVERGING (this
     * could include any in-progress captures not yet returned by the camera
     * device, for more details see pipeline considerations below).</li>
     * <li>Submitting a series of multiple requests that differ from the
     * previous request (e.g. r1, r2, r3 s.t. r1 != r2 != r3)
     * moves the new synchronization frame to the last non-repeating
     * request (using the smallest frame number from the contiguous list of
     * repeating requests).</li>
     * <li>Submitting the same request repeatedly will not change this value
     * to CONVERGING, if it was already a non-negative value.</li>
     * <li>When this value changes to non-negative, that means that all of the
     * metadata controls from the request have been applied, all of the
     * metadata controls from the camera device have been read to the
     * updated values (into the result), and all of the graphics buffers
     * corresponding to this result are also synchronized to the request.</li>
     * </ul>
     * <p><em>Pipeline considerations</em>:</p>
     * <p>Submitting a request with updated controls relative to the previously
     * submitted requests may also invalidate the synchronization state
     * of all the results corresponding to currently in-flight requests.</p>
     * <p>In other words, results for this current request and up to
     * {@link CameraCharacteristics#REQUEST_PIPELINE_MAX_DEPTH android.request.pipelineMaxDepth} prior requests may have their
     * android.sync.frameNumber change to CONVERGING.</p>
     *
     * @see CameraCharacteristics#REQUEST_PIPELINE_MAX_DEPTH
     * @see #SYNC_FRAME_NUMBER_CONVERGING
     * @see #SYNC_FRAME_NUMBER_UNKNOWN
     * @hide
     */
    public static final Key<Integer> SYNC_FRAME_NUMBER =
            new Key<Integer>("android.sync.frameNumber", int.class);

    /*~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~
     * End generated code
     *~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~@~O@*/

    /**
     * <p>
     * List of the {@link Face Faces} detected through camera face detection
     * in this result.
     * </p>
     * <p>
     * Only available if {@link #STATISTICS_FACE_DETECT_MODE} {@code !=}
     * {@link CameraMetadata#STATISTICS_FACE_DETECT_MODE_OFF OFF}.
     * </p>
     *
     * @see Face
     */
    public static final Key<Face[]> STATISTICS_FACES =
            new Key<Face[]>("android.statistics.faces", Face[].class);
}
