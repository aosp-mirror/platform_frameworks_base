/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.annotation.Nullable;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.impl.CaptureResultExtras;
import android.hardware.camera2.impl.PhysicalCaptureResultInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * <p>The total assembled results of a single image capture from the image sensor.</p>
 *
 * <p>Contains the final configuration for the capture hardware (sensor, lens,
 * flash), the processing pipeline, the control algorithms, and the output
 * buffers.</p>
 *
 * <p>A {@code TotalCaptureResult} is produced by a {@link CameraDevice} after processing a
 * {@link CaptureRequest}. All properties listed for capture requests can also
 * be queried on the capture result, to determine the final values used for
 * capture. The result also includes additional metadata about the state of the
 * camera device during the capture.</p>
 *
 * <p>All properties returned by {@link CameraCharacteristics#getAvailableCaptureResultKeys()}
 * are available (that is {@link CaptureResult#get} will return non-{@code null}, if and only if
 * that key that was enabled by the request. A few keys such as
 * {@link CaptureResult#STATISTICS_FACES} are disabled by default unless enabled with a switch (such
 * as {@link CaptureRequest#STATISTICS_FACE_DETECT_MODE}). Refer to each key documentation on
 * a case-by-case basis.</p>
 *
 * <p>For a logical multi-camera device, if the CaptureRequest contains a surface for an underlying
 * physical camera, the corresponding {@link TotalCaptureResult} object will include the metadata
 * for that physical camera. And its keys and values can be accessed by
 * {@link #getPhysicalCameraKey}. If all requested surfaces are for the logical camera, no
 * metadata for physical camera will be included.</p>
 *
 * <p>{@link TotalCaptureResult} objects are immutable.</p>
 *
 * @see CameraDevice.CaptureCallback#onCaptureCompleted
 */
public final class TotalCaptureResult extends CaptureResult {

    private final List<CaptureResult> mPartialResults;
    private final int mSessionId;
    // The map between physical camera id and capture result
    private final HashMap<String, CameraMetadataNative> mPhysicalCaptureResults;

    /**
     * Takes ownership of the passed-in camera metadata and the partial results
     *
     * @param partials a list of partial results; {@code null} will be substituted for an empty list
     * @hide
     */
    public TotalCaptureResult(CameraMetadataNative results, CaptureRequest parent,
            CaptureResultExtras extras, List<CaptureResult> partials, int sessionId,
            PhysicalCaptureResultInfo physicalResults[]) {
        super(results, parent, extras);

        if (partials == null) {
            mPartialResults = new ArrayList<>();
        } else {
            mPartialResults = partials;
        }

        mSessionId = sessionId;

        mPhysicalCaptureResults = new HashMap<String, CameraMetadataNative>();
        for (PhysicalCaptureResultInfo onePhysicalResult : physicalResults) {
            mPhysicalCaptureResults.put(onePhysicalResult.getCameraId(),
                    onePhysicalResult.getCameraMetadata());
        }
    }

    /**
     * Creates a request-less result.
     *
     * <p><strong>For testing only.</strong></p>
     * @hide
     */
    public TotalCaptureResult(CameraMetadataNative results, int sequenceId) {
        super(results, sequenceId);

        mPartialResults = new ArrayList<>();
        mSessionId = CameraCaptureSession.SESSION_ID_NONE;
        mPhysicalCaptureResults = new HashMap<String, CameraMetadataNative>();
    }

    /**
     * Get the read-only list of partial results that compose this total result.
     *
     * <p>The list is returned is unmodifiable; attempting to modify it will result in a
     * {@code UnsupportedOperationException} being thrown.</p>
     *
     * <p>The list size will be inclusive between {@code 0} and
     * {@link CameraCharacteristics#REQUEST_PARTIAL_RESULT_COUNT}, with elements in ascending order
     * of when {@link CameraCaptureSession.CaptureCallback#onCaptureProgressed} was invoked.</p>
     *
     * @return unmodifiable list of partial results
     */
    @NonNull
    public List<CaptureResult> getPartialResults() {
        return Collections.unmodifiableList(mPartialResults);
    }

    /**
     * Get the ID of the session where the capture request of this result was submitted.
     *
     * @return The session ID
     * @hide
     */
    public int getSessionId() {
        return mSessionId;
    }

    /**
     * Get a capture result field value for a particular physical camera id.
     *
     * <p>The field definitions can be found in {@link CaptureResult}.</p>
     *
     * <p>This function can be called for logical camera devices, which are devices that have
     * REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA capability and calls to {@link
     * CameraCharacteristics#getPhysicalCameraIds} return a non-empty list of physical devices that
     * are backing the logical camera. The camera id included in physicalCameraId argument
     * selects an individual physical device, and returns its specific capture result field.</p>
     *
     * <p>This function should only be called if one or more streams from the underlying
     * 'physicalCameraId' was requested by the corresponding capture request.</p>
     *
     * @throws IllegalArgumentException if the key was not valid, or the physicalCameraId is not
     * applicable to the current camera, or a stream from 'physicalCameraId' is not requested by the
     * corresponding capture request.
     *
     * @param key The result field to read.
     * @param physicalCameraId The physical camera the result originates from.
     *
     * @return The value of that key, or {@code null} if the field is not set.
     */
    @Nullable
    public <T> T getPhysicalCameraKey(Key<T> key, @NonNull String physicalCameraId) {
        if (!mPhysicalCaptureResults.containsKey(physicalCameraId)) {
            throw new IllegalArgumentException(
                    "No TotalCaptureResult exists for physical camera " + physicalCameraId);
        }

        CameraMetadataNative physicalMetadata = mPhysicalCaptureResults.get(physicalCameraId);
        return physicalMetadata.get(key);
    }
}
