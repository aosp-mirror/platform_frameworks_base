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
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.impl.CaptureResultExtras;

import java.util.ArrayList;
import java.util.Collections;
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
 * <p>{@link TotalCaptureResult} objects are immutable.</p>
 *
 * @see CameraDevice.CaptureCallback#onCaptureCompleted
 */
public final class TotalCaptureResult extends CaptureResult {

    private final List<CaptureResult> mPartialResults;
    private final int mSessionId;

    /**
     * Takes ownership of the passed-in camera metadata and the partial results
     *
     * @param partials a list of partial results; {@code null} will be substituted for an empty list
     * @hide
     */
    public TotalCaptureResult(CameraMetadataNative results, CaptureRequest parent,
            CaptureResultExtras extras, List<CaptureResult> partials, int sessionId) {
        super(results, parent, extras);

        if (partials == null) {
            mPartialResults = new ArrayList<>();
        } else {
            mPartialResults = partials;
        }

        mSessionId = sessionId;
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
}
